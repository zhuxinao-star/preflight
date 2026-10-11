package com.preflight.learn;

import com.preflight.common.FileTexts;
import com.preflight.parser.DefaultValueRules;
import com.preflight.parser.LoginSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第二条登录证据源：<b>前端调用点</b>（第四十九轮）。
 *
 * <p>为什么要有第二条。第 47 轮那 8 张登录线索卡里<b>没有登录那一步</b>：国泰海通的登录入口是
 * Spring Cloud Gateway 上的过滤器路由（{@code LoginConfig} / {@code LoginParamParser} /
 * {@code LoginProvider}，{@code application.yml} 里 {@code loginUrl: /cc/login}），
 * 盘上没有注解可扫，注解式扫描对它天生是瞎的。同一个事实在<b>前端调用点</b>上写得明明白白
 * （{@code axios.post(prefix + '/ni-gateway/cc/login', encrypt_ECB(loginData, randomCode),
 * {headers:{'Content-Type':'text/plain'}})}）。这条通路用的正是第 35、42 轮「固定入参按 js 调用点证据」
 * 那同一思路，只是换到登录这件事上。
 *
 * <p>三条各自独立的能力，判定全是跨项目通行的形状，没有一个项目字面量：
 * <ol>
 *   <li><b>把卡片路径补成"真打出去的那个地址"</b>：调用点写 {@code /ni-gateway/cc/getToken}
 *       而卡片学的是 {@code /cc/getToken} 时，那一段对外前缀按调用点补，并点名是哪一行补的。
 *       不补的话铺出来的流照着内部路径 404——卡片没学错，是<b>对外地址</b>这条事实压根不在卡片里。</li>
 *   <li><b>把注解扫不到的那一步（登录入口本身）铺出来</b>：动词、Content-Type、整包加密算法与
 *       密钥来自哪一步、负载里有哪些格、哪一格是先单独混淆过的、验证码只有位数学得动，
 *       全部从调用点与它旁边的字面量读。</li>
 *   <li><b>把会话接上</b>：{@code const authorization = response.headers.authorization} 与
 *       {@code setToken('Bearer ' + authorization)} 这一对写法就是"会话在响应头里、带出去要加 scheme"，
 *       铺成 {@code extract} ＋ {@code apply}。</li>
 * </ol>
 *
 * <p>硬约束与前 48 轮一致：<b>不编值</b>（拿不到来源的格照空串摆、原因点名）、读取期现算<b>绝不落库</b>、
 * 基数有上限且如实说截断、<b>只读盘上不碰网络</b>（真试跑是另一件事，要用户点【试跑】）。
 */
@Service
public class FrontLoginChainService {

    private static final Logger log = LoggerFactory.getLogger(FrontLoginChainService.class);

    private static final int WALK_DEPTH = 12;
    /** 一次扫描最多摊开这么多文件（基数保护；到顶要如实说"后面的没看"） */
    private static final int MAX_FILES = 2000;
    /** 单文件超过这个字节数不看（生成的 sdk / 打包产物都在这个量级） */
    private static final long MAX_FILE_BYTES = 600_000L;
    /** 平摊行长超过这个数当打包产物跳过：vite/webpack 出来的 chunk 是一行几万字 */
    private static final double MINIFIED_AVG_LINE = 300d;
    /** 摆出来的调用点上限 */
    private static final int CALL_SITE_CAP = 60;
    /** 登录负载字面量最多收这么多格 */
    private static final int LITERAL_KEY_CAP = 16;
    /** 一个字面量最多往后凑这么多行 */
    private static final int LITERAL_WINDOW = 40;
    /** 取值式最多列几条候选（前端一行 || 能写无数条） */
    private static final int EXTRACT_CAND_CAP = 4;
    /** 取值式看调用点往后这么多行（"取回来只为给下一步用"的那种小函数） */
    private static final int RETURN_WINDOW = 8;

    // ---- 调用形状：与 FrontCallSiteIndex 同一份定义（"怎么读一个前端调用点"只许有一处）------
    private static final Pattern VERB_CALL = FrontCallSiteIndex.VERB_CALL;
    private static final Pattern URL_PROP = FrontCallSiteIndex.URL_PROP;
    private static final Pattern BASE_URL = FrontCallSiteIndex.BASE_URL;
    private static final Pattern METHOD_PROP = Pattern.compile("\\bmethod\\s*[:=]\\s*['\"`](\\w{3,7})['\"`]");
    private static final Pattern IMPORT_FROM = Pattern.compile("\\bfrom\\s+['\"][^'\"]*?/([\\w.-]{2,40})['\"]");
    /** 具名 import：{@code import { encrypt_ECB } from '@/utils/sm4'} —— 模块名那一头常写着算法 */
    private static final Pattern NAMED_IMPORT = Pattern.compile(
            "\\bimport\\s*(?:type\\s+)?\\{([^}]{1,400})\\}\\s*from\\s*['\"]([^'\"]{2,160})['\"]");
    /** 默认 import：{@code import sm4 from '@/utils/sm4'} */
    private static final Pattern DEFAULT_IMPORT = Pattern.compile(
            "\\bimport\\s+(\\w{2,40})\\s*from\\s*['\"]([^'\"]{2,160})['\"]");
    /** 加密算法名（跨项目通行的算法叫法，不认任何一家的包装名） */
    private static final List<String> ALGOS = List.of("sm4", "sm1", "aes", "rijndael", "des", "tripledes",
            "tdes", "3des", "rc4", "rc6", "blowfish", "camellia", "twofish", "idea", "aria", "seed");
    /** 就近的 Content-Type：{headers:{'Content-Type':'text/plain'}} */
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "['\"]?[Cc]ontent-?[Tt]ype['\"]?\\s*[:=]\\s*['\"]([\\w/+.-]{3,40})");

    // ---- 登录那一步的负载与会话 --------------------------------------------------------------
    /** 顶层一格：'userCode': form.value.username —— 键名带不带引号都认 */
    private static final Pattern LIT_KEY = Pattern.compile("^\\s*['\"]?([\\w]{2,40})['\"]?\\s*:\\s*(.{0,140})$");
    /** 整包加密：encrypt_ECB(loginData, randomCode) —— 第一参是正文，第二参才是密钥 */
    private static final Pattern WHOLE_CODEC = Pattern.compile(
            "\\b(\\w{0,20}(?:sm4|aes|des|rc4)\\w{0,20}|encrypt\\w{0,16}|\\w{2,16}[Ee]ncrypt\\w{0,16})"
                    + "\\s*\\(\\s*[\\w$.{}]+\\s*,\\s*([\\w$]{2,40})\\s*\\)");
    /** 单格先混淆：commonUtil.encrypt(form.value.password, '888888') —— 第二参是字面量才是加密因子 */
    private static final Pattern FIELD_CODEC = Pattern.compile(
            "\\b(\\w{2,24})\\.encrypt\\s*\\(\\s*[^,()]{0,60}\\s*,\\s*['\"]([^'\"\\n]{1,24})['\"]\\s*\\)");
    /** 会话在响应头里：const authorization = response.headers.authorization */
    private static final Pattern HEADER_SESSION = Pattern.compile(
            "\\b(?:const|let|var)\\s+(\\w{2,40})\\s*=\\s*[\\w$.]*\\.headers\\??\\.(\\w{2,40})\\b");
    /** 会话带出去的 scheme：setToken('Bearer ' + authorization) */
    private static final Pattern TOKEN_SCHEME = Pattern.compile("['\"]([A-Za-z]{3,12})\\s+['\"]\\s*\\+\\s*(\\w{2,40})");
    /** 这一步返回里留下哪个字段：response.data?.body?.token（一行里 || 的都是候选） */
    private static final Pattern DATA_FIELD = Pattern.compile(
            "\\.data\\??\\.((?:body\\??\\.)?([\\w$]{2,40}))");    /** 前端自己写的位数规则：{ len: 4 } */
    private static final Pattern CODE_LEN = Pattern.compile("\\blen\\s*:\\s*(\\d{1,2})\\b");
    /** 业务成败怎么看：service.ts 里 response.data.code == '0' 这一句就是信封的判定形状 */
    private static final Pattern SUCCESS_ENVELOPE = Pattern.compile(
            "\\.data\\.(\\w{1,20})\\s*===?\\s*['\"](\\w{1,8})['\"]");
    /** 整包是字符串还是对象：JSON.stringify(x) 再交给加密，说明发的是文本 */
    private static final Pattern STRINGIFIED = Pattern.compile("JSON\\.stringify\\s*\\(\\s*([\\w$]{2,40})\\s*\\)");

    /** 验证码图那种地址不是一步接口 */
    private static final Pattern IMAGE_TAIL = Pattern.compile(
            "\\.(png|jpg|jpeg|gif|bmp|webp|svg)$", Pattern.CASE_INSENSITIVE);

    /** TS 类型名：出现在"键: 值"的位置说明这一整块是 interface 声明，不是要发出去的负载 */
    private static final Set<String> TS_TYPES = Set.of("string", "number", "boolean", "any", "unknown",
            "object", "null", "undefined", "void", "never", "symbol", "bigint");

    /**
     * 扫这个项目登记的前端仓。
     *
     * @param roots     前端仓目录（老工程那些"前端脚本在后端 static 目录里"的仓，第 35 轮的调用点通路已经在管，
     *                  这一条只补 repo_type='frontend' 的仓）
     * @param cardPaths 这个项目<b>真学到的</b>接口卡路径全集，用来判两件事：卡片路径缺了哪段对外前缀、
     *                  调用点有没有多学出一步注解扫不到的
     */
    public Result scan(List<String> roots, Set<String> cardPaths) {
        Result r = new Result();
        List<Path> files = pick(roots, r);
        List<FileFacts> facts = new ArrayList<>();
        for (Path f : files) {
            FileFacts ff = readOne(f);
            if (ff != null) facts.add(ff);
        }
        r.filesScanned = files.size();
        resolve(r, facts, cardPaths);
        return r;
    }

    // ---------------------------------------------------------------- 一趟读盘：一个文件读一次

    /** 一个文件算出来的小事实（原文只在这一趟里活着，不留存） */
    private static class FileFacts {
        String rel;
        String base = "";
        Set<String> imports = new LinkedHashSet<>();
        /** 具名/默认 import 带回来的名字 → 它来自哪个模块的最后一段（{@code encrypt_ECB → sm4}） */
        Map<String, Import> named = new LinkedHashMap<>();
        List<CallSite> calls = new ArrayList<>();
        List<Literal> literals = new ArrayList<>();
        List<Codec> codecs = new ArrayList<>();
        Session session;
        String captchaLen = "";
        String successField = "";
        String successValue = "";
        String successEvidence = "";
        boolean stringifyBody;
    }

    /** 一个 import 名字的事实：模块最后一段与它出现在第几行 */
    private static class Import {
        final String tail;
        final int line;

        Import(String tail, int line) {
            this.tail = tail;
            this.line = line;
        }
    }

    private FileFacts readOne(Path f) {
        String text;
        try {
            if (Files.size(f) > MAX_FILE_BYTES) return null;
            text = FileTexts.decode(Files.readAllBytes(f)).text();
        } catch (Exception e) {
            return null;
        }
        if (text == null || text.isBlank()) return null;
        String[] lines = FileTexts.lines(text);
        if (lines.length == 0 || (double) text.length() / lines.length > MINIFIED_AVG_LINE) return null;

        FileFacts ff = new FileFacts();
        ff.rel = shownAs(f);
        String lowName = ff.rel.toLowerCase(Locale.ROOT);
        String lowText = text.toLowerCase(Locale.ROOT);
        // 只有登录语义的文件才摊开细看：验证码、账号、会话这些词在跨项目前端里就是这一屏的东西
        if (!loginMatter(lowName, lowText)) return null;

        Matcher b = BASE_URL.matcher(text);
        if (b.find()) ff.base = b.group(1);
        Matcher imp = IMPORT_FROM.matcher(text);
        while (imp.find() && ff.imports.size() < 20) ff.imports.add(imp.group(1));
        // 具名 import 的名字→模块：加密函数叫什么不要紧，紧的是它是从哪个模块带回来的（@/utils/sm4）
        Matcher ni = NAMED_IMPORT.matcher(text);
        while (ni.find() && ff.named.size() < 80) {
            String tail = lastSegment(ni.group(2));
            int ln = 1 + countLines(text, ni.start());
            for (String raw : ni.group(1).split("[,\\n]")) {
                String nm = importName(raw);
                if (!nm.isEmpty()) ff.named.putIfAbsent(nm, new Import(tail, ln));
            }
        }
        Matcher di = DEFAULT_IMPORT.matcher(text);
        while (di.find() && ff.named.size() < 80) {
            ff.named.putIfAbsent(di.group(1), new Import(lastSegment(di.group(2)), 1 + countLines(text, di.start())));
        }

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = VERB_CALL.matcher(line);
            while (m.find()) {
                ff.calls.add(newCall(m.group(1).toUpperCase(Locale.ROOT), m.group(2), ff.rel, i + 1, "动词调用", lines));
            }
            m = URL_PROP.matcher(line);
            while (m.find()) {
                ff.calls.add(newCall(nearbyMethod(lines, i), m.group(1), ff.rel, i + 1, "url 属性", lines));
            }
            Matcher w = WHOLE_CODEC.matcher(line);
            while (w.find()) {
                Codec c = new Codec(w.group(1), w.group(2), ff.rel, i + 1);
                resolveAlgo(c, ff);
                ff.codecs.add(c);
            }
        }
        Matcher h = HEADER_SESSION.matcher(text);
        while (h.find() && ff.session == null) {
            Session s = new Session(h.group(1), h.group(2), ff.rel, 1 + countLines(text, h.start()));
            Matcher sc = TOKEN_SCHEME.matcher(text);
            while (sc.find()) {
                if (sc.group(2).equals(s.var)) {
                    s.scheme = sc.group(1);
                    break;
                }
            }
            if (looksLikeSession(s)) ff.session = s;
        }
        for (int i = 0; i < lines.length && ff.literals.size() < 8; i++) {
            if (!lines[i].contains("{")) continue;
            Literal lit = literalAt(ff.rel, lines, i);
            if (lit != null) ff.literals.add(lit);
        }
        Matcher len = CODE_LEN.matcher(text);
        if (len.find() && LoginSemantics.mentionsCaptcha(text)) {
            ff.captchaLen = len.group(1) + "@" + ff.rel + ":" + (1 + countLines(text, len.start()));
        }
        Matcher se = SUCCESS_ENVELOPE.matcher(text);
        if (se.find()) {
            ff.successField = se.group(1);
            ff.successValue = se.group(2);
            ff.successEvidence = ff.rel + ":" + (1 + countLines(text, se.start()));
        }
        ff.stringifyBody = STRINGIFIED.matcher(text).find();
        return ff;
    }

    private static CallSite newCall(String verb, String path, String file, int line, String via, String[] lines) {
        CallSite c = new CallSite(verb, path, file, line, via);
        c.role = LoginSemantics.roleOf(path);
        // Content-Type 只在调用点往下 6 行里找：它就是这一个请求的形状，不是整个文件的
        for (int k = line - 1; k <= Math.min(lines.length - 1, line + 5); k++) {
            Matcher ct = CONTENT_TYPE.matcher(lines[k]);
            if (ct.find()) {
                c.contentType = ct.group(1);
                c.contentTypeLine = k + 1;
                break;
            }
        }
        c.extract = returnField(lines, line - 1);
        return c;
    }

    /**
     * 这一步的返回里哪个字段会被留下；前端一行写了多条 {@code ||} 就都摆成候选，
     * 按<b>它自己写的顺序</b>试（引擎认 "a || b" 这种多候选取值式）。
     *
     * <p>{@code response.data?.body} 这种整包兜底说明字段其实挂在 body 下，所以补一条
     * {@code $.body.<字段>} 的候选；而光秃秃的 {@code $.body}（没有字段名）不算一条取值式，
     * 摆进去只会把整包字符串当成密钥发出去。
     */
    private static Extract returnField(String[] lines, int from) {
        Extract e = new Extract();
        for (int k = from; k <= Math.min(lines.length - 1, from + RETURN_WINDOW); k++) {
            String line = lines[k];
            if (!line.contains(".data")) continue;
            Matcher m = DATA_FIELD.matcher(line);
            while (m.find()) {
                String seg = m.group(1);
                String field = m.group(2);
                // 光秃秃的 response.data?.body 是容器不是字段，摆成取值式只会把整包字符串当值发出去
                if (field.equalsIgnoreCase("body") && !seg.contains(".")) continue;
                String path = seg.startsWith("body") ? "$.body." + field : "$." + field;
                if (e.candidates.size() < EXTRACT_CAND_CAP && !e.candidates.contains(path)) {
                    e.candidates.add(path);
                }
            }
            if (line.contains("data?.body") || line.contains("data.body")) {
                for (String c : new ArrayList<>(e.candidates)) {
                    if (!c.startsWith("$.") || c.startsWith("$.body.")) continue;
                    String alt = "$.body." + c.substring(2);      // 不用 replaceFirst：$ 在替换串里是分组引用
                    if (e.candidates.size() < EXTRACT_CAND_CAP && !e.candidates.contains(alt)) {
                        e.candidates.add(alt);
                    }
                }
            }
            if (!e.candidates.isEmpty()) {
                e.path = String.join(" || ", e.candidates);
                e.var = leafVar(e.candidates.get(0));
                e.evidence = shownLine(k, line);
                return e;
            }
        }
        return null;
    }

    /** 证据行只留"第几行 + 那一行的原文（截一下）"，长行不配整条摆到界面上 */
    private static String shownLine(int idx, String line) {
        String s = line.trim();
        return (idx + 1) + "行" + (s.length() > 90 ? s.substring(0, 90) + "…" : " " + s);
    }

    /** 取值式 → 变量名：$.body.randomCode 与 $.randomCode 都叫 randomCode */
    private static String leafVar(String jsonPath) {
        int dot = jsonPath.lastIndexOf('.');
        return dot > 0 ? jsonPath.substring(dot + 1) : "";
    }

    /** 文件里出现这些词才值得摊开看：跨项目通行的英文语义名，不认任何一家项目的专有叫法 */
    private static boolean loginMatter(String lowName, String lowText) {
        for (String w : LoginSemantics.ALL_WORDS) {
            if (lowName.contains(w) || lowText.contains(w)) return true;
        }
        return lowText.contains("password") || lowText.contains("captcha");
    }

    /** import 清单里的一项要留下的名字：{@code type Foo}、{@code bar as Baz} 都取实际能用到的那个 */
    private static String importName(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return "";
        int as = s.lastIndexOf(" as ");
        if (as >= 0) s = s.substring(as + 4).trim();
        if (s.startsWith("type ")) s = s.substring(5).trim();
        return s.matches("\\w{2,40}") ? s : "";
    }

    /**
     * 加密算法那一半从哪儿认：<b>先认调用名自己</b>（{@code sm4Encrypt}），<b>再认它是从哪个模块 import
     * 回来的</b>（{@code import { encrypt_ECB } from '@/utils/sm4'}）。两处都没有就交不出算法——
     * 只写了模式的名字（{@code encrypt_ECB}）压根没说是什么算法，蒙一个 SM4 出去就是编值。
     */
    private static void resolveAlgo(Codec c, FileFacts ff) {
        String hay = c.call.toLowerCase(Locale.ROOT);
        for (String a : ALGOS) {
            if (hay.contains(a)) {
                c.algo = a;
                c.algoVia = "调用名 " + c.call + "（" + c.file + ":" + c.line + "）";
                return;
            }
        }
        Import im = ff.named.get(c.call);
        if (im == null) return;
        for (String a : ALGOS) {
            if (im.tail.toLowerCase(Locale.ROOT).contains(a)) {
                c.algo = a;
                c.algoVia = "它 import 自模块 " + im.tail + "（" + c.file + ":" + im.line + " 行）";
                return;
            }
        }
    }

    /** url 属性那行没写动词时前后各看 3 行找 method: 'GET'；还没有就留空（由出口按"带负载＝POST"推） */
    private static String nearbyMethod(String[] lines, int i) {
        for (int k = Math.max(0, i - 3); k <= Math.min(lines.length - 1, i + 3); k++) {
            Matcher mm = METHOD_PROP.matcher(lines[k]);
            if (mm.find()) return mm.group(1).toUpperCase(Locale.ROOT);
        }
        return "";
    }

    /**
     * 从第 start 行往后凑一个花括号字面量，只收<b>顶层</b>键。
     *
     * @return null 表示这既不是字面量、或者它不是登录负载。门槛是"账号格与密码格至少出一个、
     *         且整格里有我们认得的语义键"：登录表单才有这一对，别的对象长得再像也不收。
     */
    private static Literal literalAt(String file, String[] lines, int start) {
        int depth = 0;
        List<Key> keys = new ArrayList<>();
        boolean acc = false, pwd = false, code = false;
        for (int i = start; i < Math.min(lines.length, start + LITERAL_WINDOW); i++) {
            String raw = lines[i];
            for (int c = 0; c < raw.length(); c++) {
                char ch = raw.charAt(c);
                if (ch == '{') depth++;
                else if (ch == '}') depth--;
            }
            if (depth >= 1) {
                Matcher k = LIT_KEY.matcher(raw);
                if (k.find() && depth == 1 && keys.size() < LITERAL_KEY_CAP) {
                    String name = k.group(1);
                    String val = k.group(2).replaceAll("[,;]\\s*$", "").trim();
                    if (val.startsWith("[") || val.startsWith("function") || val.startsWith("async")) continue;
                    // 类型声明不是负载：{@code password: string // 密码} 那一整块是 interface，发不出去
                    if (isTypeDecl(val)) return null;
                    String n = DefaultValueRules.normalize(name);
                    keys.add(new Key(name, val, i + 1));
                    if (LoginSemantics.isAccount(n)) acc = true;
                    if (LoginSemantics.isPassword(n)) pwd = true;
                    if (LoginSemantics.isCode(n)) code = true;
                }
            }
            if (depth <= 0 && i > start) break;
        }
        if (keys.size() < 2 || !(acc || pwd)) return null;
        Literal l = new Literal();
        l.file = file;
        l.line = start + 1;
        l.keys = keys;
        l.account = acc;
        l.password = pwd;
        l.code = code;
        return l;
    }

    /** 这一格的"值"其实是个 TS 类型名（interface 的字段声明），不是要发出去的东西 */
    private static boolean isTypeDecl(String val) {
        String v = val.replaceAll("//.*$", "").replaceAll("/\\*.*?\\*/", "").trim();
        v = v.replaceAll("<[^<>]{1,60}>", "").replace("[]", "").trim();
        return TS_TYPES.contains(v.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- 装配

    private void resolve(Result r, List<FileFacts> facts, Set<String> cardPaths) {
        Map<String, String> moduleBase = new LinkedHashMap<>();
        for (FileFacts ff : facts) {
            if (!ff.base.isEmpty()) moduleBase.putIfAbsent(lastSegment(ff.rel), ff.base);
        }
        // 这一趟读到的部署前缀（各个 axios 实例的 baseURL）：算链的键时先摘掉它，见 LoginSemantics.chainOf
        for (FileFacts ff : facts) {
            String b = !ff.base.isEmpty() ? ff.base : importedBase(ff, moduleBase);
            if (!b.isEmpty()) r.prefixes.add(b);
        }
        for (FileFacts ff : facts) {
            String base = !ff.base.isEmpty() ? ff.base : importedBase(ff, moduleBase);
            for (CallSite c : ff.calls) {
                if (c.path == null || !c.path.startsWith("/") || "other".equals(c.role)) continue;
                if (IMAGE_TAIL.matcher(c.path).find()) continue;      // 验证码图那种地址不算一步接口
                c.absPath = base.isEmpty() || c.path.startsWith(base + "/")
                        ? c.path : joinPrefix(base, c.path);
                c.base = base;
                if (!c.absPath.equals(c.path)) {
                    c.prefixVia = ff.rel + " 的 baseURL " + base + "（" + c.line + "行）";
                }
                // 链的键按<b>项目内部的那个路径</b>算（先把这一趟读到的部署前缀摘掉）：
                // 网关那一段在两条证据源里写得不一样——注解学的卡不带、前端调用点有写全的、
                // 也有走另一个实例压根没带的。按整条路径算就把同一次登录过程切成两条链，
                // 每条都"没有登录那一步"，整条流一条都铺不出来（真实盘与合成夹具各坏过一次）。
                c.chain = r.chainKey(c.path);
                r.addSite(c);
            }
            r.codecs.addAll(ff.codecs);
            if (r.session == null && ff.session != null) r.session = ff.session;
            if (r.captchaLen.isEmpty() && !ff.captchaLen.isEmpty()) r.captchaLen = ff.captchaLen;
            // 请求封装层（自己写着 baseURL 的那个文件）里那句 code == 0，才是"这一步算不算成"的判据；
            // 业务组件里同形的比较只管它自己那一次调用，不能当成整条流的判据
            if (!ff.successField.isEmpty() && (r.successField.isEmpty() || (!ff.base.isEmpty() && !r.successFromWrapper))) {
                r.successField = ff.successField;
                r.successValue = ff.successValue;
                r.successEvidence = ff.successEvidence;
                r.successFromWrapper = !ff.base.isEmpty();
            }
            if (ff.stringifyBody) r.stringifyFiles.add(ff.rel);
            for (Literal l : ff.literals) {
                if (r.loginLiteral == null || score(l) > score(r.loginLiteral)) r.loginLiteral = l;
            }
        }
        // ①卡片缺的那段对外前缀：调用点路径比卡片长、末段同名，才算"同一个接口的对外写法"
        for (CallSite c : r.sites) {
            for (String card : cardPaths) {
                if (card == null || card.isBlank() || card.equals(c.absPath) || !c.absPath.endsWith(card)) continue;
                String t1 = LoginSemantics.tail(DefaultValueRules.normalize(c.absPath));
                String t2 = LoginSemantics.tail(DefaultValueRules.normalize(card));
                if (!t1.equals(t2)) continue;
                r.cardAsCalled.putIfAbsent(card, new Rewrite(c.absPath,
                        "前端调用点 " + c.evidence() + "（" + c.via + "）", c));
            }
        }
        // ②压根没有调用点的卡片（验证码图那种只当 img src 出现的地址）：照同目录那一步的前缀补，
        //   依据要点名"前缀是从哪个调用点的 baseURL 带过来的"——这是<b>借来的</b>事实，不是学到的
        Map<String, CallSite> dirTwin = new LinkedHashMap<>();
        for (CallSite c : r.sites) {
            if (!c.base.isEmpty()) dirTwin.putIfAbsent(c.chain, c);
        }
        for (String card : cardPaths) {
            if (card == null || card.isBlank() || r.cardAsCalled.containsKey(card)) continue;
            CallSite twin = dirTwin.get(r.chainKey(card));
            if (twin == null || twin.base.isEmpty()) continue;
            r.cardAsCalled.put(card, new Rewrite(joinPrefix(twin.base, card),
                    "借同目录的前缀：那一步的 baseURL " + twin.base + "，来自前端调用点 " + twin.evidence(), twin));
        }
    }

    private static boolean looksLikeSession(Session s) {
        String hay = (s.var + " " + s.header).toLowerCase(Locale.ROOT);
        return hay.contains("authorization") || hay.contains("token") || hay.contains("session")
                || hay.contains("jwt") || hay.contains("ticket");
    }

    private static String importedBase(FileFacts ff, Map<String, String> moduleBase) {
        for (String imp : ff.imports) {
            String base = moduleBase.get(imp);
            if (base != null) return base;
        }
        return "";
    }

    /**
     * 哪一份字面量才是"登录要发出去的那份"。
     *
     * <p>只数账号与密码不够：客服坐席签到、改密码弹窗都带 username/password，键还更多。
     * 登录负载的通用特征是<b>账号＋密码＋校验码那一格同时出现</b>（校验码只有登录这一步要人眼读图），
     * 其次才是它所在文件自己写着登录这个词。都不带权重时按文件名字典序先到的赢，等于把
     * "哪个目录先被扫到"当成了判据——第四十九轮探针就是这么挑中 {@code webChat/index.vue} 的签到参数、
     * 把 {@code LoginView.vue} 里真正的登录负载挤掉的。
     */
    private static int score(Literal l) {
        int s = 0;
        if (l.account) s += 3;
        if (l.password) s += 3;
        if (l.code) s += 3;
        String f = DefaultValueRules.normalize(l.file == null ? "" : l.file);
        for (String w : LoginSemantics.LOGIN_WORDS) {
            if (f.contains(w.replaceAll("[^0-9a-z]", ""))) {
                s += 2;
                break;
            }
        }
        s += Math.min(l.keys.size(), 5);
        for (Key k : l.keys) {
            if (FIELD_CODEC.matcher(k.valueExpr).find()) {
                s += 4;                       // 有一格先单独混淆过：这就是提交前打包的那份负载
                break;
            }
        }
        return s;
    }

    /** 前缀拼接的规矩与调用点索引共用一个出口（不许造出双斜杠，也不许把已带前缀的再叠一遍） */
    private static String joinPrefix(String base, String path) {
        return FrontCallSiteIndex.joinPrefix(base, path);
    }

    // ---------------------------------------------------------------- 遍历

    private List<Path> pick(List<String> roots, Result r) {
        Set<Path> seen = new LinkedHashSet<>();
        for (String dir : roots) {
            if (dir == null || dir.isBlank()) continue;
            Path root = Path.of(dir).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) continue;
            int before = seen.size();
            try (var stream = Files.walk(root, WALK_DEPTH)) {
                stream.filter(Files::isRegularFile).filter(p -> wanted(root, p)).sorted()
                        .forEach(p -> {
                            if (seen.size() < MAX_FILES) seen.add(p); else r.filesCapped = true;
                        });
            } catch (IOException e) {
                log.debug("前端仓扫描跳过 {}: {}", root, e.toString());
                r.unreadable.add(root.getFileName().toString());
            }
            r.repoFiles.put(root.getFileName().toString(), seen.size() - before);
        }
        return new ArrayList<>(seen);
    }

    private static boolean wanted(Path root, Path p) {
        return FrontCallSiteIndex.wanted(root, p);
    }

    /** 证据上摆成"从 src/ 起"的样子：整条盘路径既长又没信息量（与调用点索引同一个写法） */
    private static String shownAs(Path f) {
        return FrontCallSiteIndex.shownAs(f);
    }

    private static String lastSegment(String rel) {
        return FrontCallSiteIndex.lastSegment(rel);
    }

    private static int countLines(String text, int upto) {
        int n = 0;
        for (int i = 0; i < Math.min(upto, text.length()); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    // ---------------------------------------------------------------- 出口形状

    /** 一条前端调用点（absPath 已经是"真打出去的那个样子"） */
    public static class CallSite {
        public final String verb;
        public final String path;
        public final String file;
        public final int line;
        public final String via;
        public String role = "other";
        public String absPath;
        public String chain = "";
        /** 这个调用点所在文件的 baseURL（空＝这一层没写前缀，也就没得借给别人） */
        public String base = "";
        public String prefixVia = "";
        public String contentType = "";
        public int contentTypeLine;
        public Extract extract;
        final List<String> also = new ArrayList<>();

        CallSite(String verb, String path, String file, int line, String via) {
            this.verb = verb;
            this.path = path;
            this.file = file;
            this.line = line;
            this.via = via;
        }

        /** 动词没写出来时按形状推：带负载的才可能是 POST，其余按 GET 摆，并把这条推断说出来 */
        public String verbOrGuess(boolean hasBody) {
            if (verb != null && !verb.isBlank()) return verb;
            return hasBody ? "POST" : "GET";
        }

        public boolean verbGuessed() {
            return verb == null || verb.isBlank();
        }

        public String evidence() {
            return file + ":" + line;
        }

        public String evidenceAll() {
            return also.isEmpty() ? evidence() : evidence() + "（另 " + also.size() + " 处同址："
                    + String.join("、", also.subList(0, Math.min(3, also.size()))) + "）";
        }
    }

    /** 登录负载字面量里的一格 */
    public static class Key {
        public final String name;
        public final String valueExpr;
        public final int line;

        Key(String name, String valueExpr, int line) {
            this.name = name;
            this.valueExpr = valueExpr;
            this.line = line;
        }
    }

    static class Literal {
        String file;
        int line;
        List<Key> keys = List.of();
        boolean account;
        boolean password;
        /** 这份字面量里有验证码那一格：登录负载长这样的旁证 */
        boolean code;
    }

    /** 会话怎么接：响应头里那个名字 + 带出去要加的 scheme */
    public static class Session {
        public final String var;
        public final String header;
        public final String file;
        public final int line;
        public String scheme = "";

        Session(String var, String header, String file, int line) {
            this.var = var;
            this.header = header;
            this.file = file;
            this.line = line;
        }
    }

    /** 一次整包加密的发现：函数名 + 密钥那个变量名 */
    public static class Codec {
        public final String call;
        public final String keyVar;
        public final String file;
        public final int line;
        /** 算法（sm4/aes/des…）；没学到是空串，出口要点名"算法没学到" */
        public String algo = "";
        /** 算法是从哪一句认出来的（界面上要说依据） */
        public String algoVia = "";

        Codec(String call, String keyVar, String file, int line) {
            this.call = call;
            this.keyVar = keyVar;
            this.file = file;
            this.line = line;
        }

        /** 模式只看调用名自己写没写（ECB/CBC）；没写就交不出，不许按"一般是 ECB"蒙 */
        public String mode() {
            String hay = call.toLowerCase(Locale.ROOT);
            if (hay.contains("cbc")) return "cbc";
            if (hay.contains("ecb")) return "ecb";
            return "";
        }

        /**
         * 引擎认的整块编码名＝算法＋模式，两个都学到才给得出来。
         *
         * <p>缺哪一半由 {@link #algoNote()} 说清，出口据此点名"这一步要加密，但算法/模式没学到"，
         * 不许蒙一个。
         */
        public String engineAlgo() {
            String m = mode();
            return algo.isEmpty() || m.isEmpty() ? "" : algo + "-" + m;
        }

        /** 学到了一半还是全学了：界面恒显那句要说的是这个 */
        public String algoNote() {
            String m = mode();
            if (!algo.isEmpty() && !m.isEmpty()) return "整包加密 " + algo + "-" + m + "（依据：" + algoVia + "）";
            if (algo.isEmpty()) return "这一步整包加密，但算法名没学到：调用点只写了 " + call
                    + "，它的 import 模块名里也没有算法词（" + file + ":" + line + "）";
            return "算法学到是 " + algo + "，但模式没学到：调用名 " + call + " 里没写 ECB/CBC（" + file + ":" + line + "）";
        }
    }

    /**
     * 一张卡片"前端实际怎么调"的改写：路径 + 这条改写从哪句代码来 + 依据那个调用点。
     *
     * <p>两种来源都在 {@link #resolve} 里说清了：① 前端调用点自己带这个路径；② 卡片没有调用点
     * （验证码图那种），借同目录另一步的 baseURL。②是<b>借来的</b>事实，界面必须点名。
     */
    public static class Rewrite {
        public final String path;
        public final String via;
        public final CallSite site;

        Rewrite(String path, String via, CallSite site) {
            this.path = path;
            this.via = via;
            this.site = site;
        }
    }

    /** 某一步的返回里要留给后面用的那个值（多条候选按前端自己写的顺序试） */
    public static class Extract {
        public String var = "";
        public String path = "$";
        public String evidence = "";
        public final List<String> candidates = new ArrayList<>();
    }

    /** 扫描结果：调用点 + 三条从前端读回来的事实 + 扫了多少的基数 */
    public static class Result {
        public final List<CallSite> sites = new ArrayList<>();
        public final List<Codec> codecs = new ArrayList<>();
        /** 卡片路径 → 前端真调用地址（①按调用点改写 ②借同目录前缀改写；两种都带依据） */
        public final Map<String, Rewrite> cardAsCalled = new LinkedHashMap<>();
        public final Map<String, Integer> repoFiles = new LinkedHashMap<>();
        public final List<String> unreadable = new ArrayList<>();
        public int sitesMerged;
        public int sitesCapped;
        public int filesScanned;
        public boolean filesCapped;
        String captchaLen = "";
        Literal loginLiteral;
        Session session;
        /** 这一趟读到的部署前缀（各 axios 实例的 baseURL）：算链的键之前先把它摘掉 */
        public final Set<String> prefixes = new LinkedHashSet<>();
        /** 「这一步算成了」的判据：前端响应拦截器里那个比较（{@code data.code == '0'}） */
        public String successField = "";
        public String successValue = "";
        public String successEvidence = "";
        /** successField 是不是从请求封装层（写着 baseURL 的那个文件）读来的——是就先于业务组件那句 */
        boolean successFromWrapper;
        /** 请求体是整串 JSON 文本发出去的那些文件（决定铺不铺 content_type） */
        public final List<String> stringifyFiles = new ArrayList<>();

        void addSite(CallSite c) {
            for (CallSite old : sites) {
                if (old.absPath.equals(c.absPath) && old.role.equals(c.role)) {
                    old.also.add(c.evidence());
                    if (old.extract == null) old.extract = c.extract;
                    if (old.contentType.isEmpty()) old.contentType = c.contentType;
                    sitesMerged++;
                    return;
                }
            }
            if (sites.size() >= CALL_SITE_CAP) {
                sitesCapped++;
                return;
            }
            sites.add(c);
        }

        /** 前端有、卡片没有的那一步（登录入口本身常在网关过滤器里，注解扫不到） */
        public List<CallSite> notLearned(Set<String> cardPaths) {
            Set<CallSite> asCard = new LinkedHashSet<>();
            for (Rewrite rw : cardAsCalled.values()) asCard.add(rw.site);   // 已经被算作某张卡的调用点，不算"多学出来的一步"
            List<CallSite> out = new ArrayList<>();
            for (CallSite c : sites) {
                if (asCard.contains(c)) continue;
                if (!cardPaths.contains(c.absPath) && !cardPaths.contains(c.path)) out.add(c);
            }
            return out;
        }

        /**
         * 这条链的键：先把这一趟真读到的部署前缀摘掉，再按项目内部的那个路径算。
         *
         * <p>卡片那侧与调用点那侧必须共用这一个出口——两边算出来的键不一样，同一次登录就被切成
         * 两条链，两条都"没有登录那一步"，整条流铺不出来（第四十九轮探针两次抓到，方向相反：
         * 真实盘上登录调用点写全了网关前缀，合成夹具上登录调用点反倒没带前缀）。
         */
        public String chainKey(String path) {
            return LoginSemantics.chainOf(path, prefixes);
        }

        /**
         * 这条链上"哪一步的返回留下这个变量"：登录负载与加密密钥都靠它对上号。
         *
         * <p>必须按链找，不能只按变量名取第一条：同一个变量名在别的模块里也会被取
         * （真实盘上 /sse 那条链和登录链都留 token）。按名字取第一条就把别条链的那一步
         * 当成"上一步"，登录包里的 token 因此对不上号、被说成"页面上另一个输入框"。
         */
        public CallSite producerOf(String varName, String chain) {
            if (varName == null || varName.isBlank() || chain == null || chain.isBlank()) return null;
            for (CallSite c : sites) {
                if (c.extract != null && varName.equals(c.extract.var) && chain.equals(c.chain)) return c;
            }
            return null;
        }

        /** 密钥变量 → 那次整包加密（同一步的 encode_key 靠它对上"哪一步的返回给了这个密钥"） */
        public Codec codecFor(String keyVar) {
            for (Codec c : codecs) if (c.keyVar.equals(keyVar)) return c;
            return null;
        }

        /** 登录负载那几格（账号格与密码格同时在的那一份字面量）；没有就是空清单 */
        public List<Key> loginBody() {
            return loginLiteral == null ? List.of() : loginLiteral.keys;
        }

        public String loginBodyEvidence() {
            return loginLiteral == null ? "" : loginLiteral.file + ":" + loginLiteral.line;
        }

        /**
         * 单格先混淆的那种形状：X.encrypt(表单值, '因子')。
         * 键＝请求体里的位置（归一化后），值＝盘上那个<b>加密因子字面量</b>本身；
         * 用什么引擎编码器由出口判（目前这一形只有 ka＝金证前端 ka.encrypt 同款逐位变换）。
         */
        public Map<String, String> fieldCodec() {
            Map<String, String> out = new LinkedHashMap<>();
            if (loginLiteral == null) return out;
            for (Key k : loginLiteral.keys) {
                Matcher m = FIELD_CODEC.matcher(k.valueExpr);
                if (m.find()) out.put(DefaultValueRules.normalize(k.name), m.group(2));
            }
            return out;
        }

        public String captchaLen() {
            return captchaLen;
        }

        public Session session() {
            return session;
        }

        /** 这一屏要说清的基数：扫了几个仓几个文件、读到几处、怎么截断的，全自述 */
        public String basisNote() {
            StringBuilder sb = new StringBuilder();
            sb.append("扫了 ").append(repoFiles.size()).append(" 个前端仓里 ").append(sum(repoFiles))
                    .append(" 个脚本文件（候选文件上限 ").append(MAX_FILES).append("、").append("单文件上限 ")
                    .append(MAX_FILE_BYTES / 1024).append("KB、平摊行长超 ").append((int) MINIFIED_AVG_LINE)
                    .append(" 字符的打包产物不看）");
            if (filesCapped) sb.append("：到上限就停了，后面的文件没看");
            sb.append("，读到 ").append(sites.size()).append(" 处登录语义调用点");
            if (sitesMerged > 0) {
                sb.append("（另 ").append(sitesMerged).append(" 处是同一个地址的其它写法，证据并到那一行）");
            }
            if (sitesCapped > 0) sb.append("（还有 ").append(sitesCapped).append(" 处超出摆出的上限，没列）");
            if (!unreadable.isEmpty()) sb.append("；读不到的仓：").append(String.join("、", unreadable));
            return sb.toString();
        }

        private static int sum(Map<String, Integer> m) {
            int n = 0;
            for (int v : m.values()) n += v;
            return n;
        }
    }
}
