package com.preflight.parser;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * 金证 KJDP 框架适配（work_app / work_app_xc 这类 web.xml + Spring bean-name URL 的老工程）。
 *
 * 事实依据（均在真实仓库核对过）：
 * - 入口：web.xml 把 /redis_ajax 等映射到 DispatcherServlet，真实处理器是 Spring 里
 *   bean name 形如 /**&#47;redis_ajax 的 GenericFrontController 家族。
 * - 线格式：POST body = {"REQUESTS":[{"REQ_MSG_HDR":{…},"REQ_COMM_DATA":{…}}]}，
 *   服务码放在 REQ_COMM_DATA.service；只写 bex_codes 时前端自动补 service=P9999999。
 * - 参数契约：WEB-INF/conf/metaData/{serviceConf,bexConf} 的 XML 声明，以及前端 ka.js 调用点的字面量键。
 */
@Component
public class KjdpDispatchAdapter implements DispatchAdapter {

    private static final Pattern BEAN_URL = Pattern.compile("^/\\*\\*/(.+)$");
    private static final Pattern FRONT_CLASS = Pattern.compile("(GenericFront|NofilterFront|FrontController)");
    private static final long MAX_JS_BYTES = 2_000_000L;

    @Override
    public String displayName() {
        return "金证 KJDP";
    }

    @Override
    public boolean detect(Path repoRoot) {
        if (!Files.isRegularFile(repoRoot.resolve("WEB-INF/web.xml"))) return false;
        if (Files.isDirectory(repoRoot.resolve("WEB-INF/conf/metaData"))) return true;
        return firstKjdpJar(repoRoot) != null;
    }

    @Override
    public Map<String, DispatchSpec> entries(Path repoRoot) {
        Map<String, DispatchSpec> out = new LinkedHashMap<>();
        List<Path> configs = new ArrayList<>();
        Path conf = repoRoot.resolve("WEB-INF/conf");
        if (Files.isDirectory(conf)) {
            try (Stream<Path> w = Files.walk(conf, 3)) {
                w.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .forEach(configs::add);
            } catch (Exception ignore) {
            }
        }
        Map<String, String> beans = new LinkedHashMap<>();
        for (Path p : configs) {
            try {
                beans.putAll(beanUrls(p, null));
            } catch (Exception ignore) {
            }
        }
        // 框架自带配置在 jar 内（META-INF/config/spring/*.xml），只读 kjdp*.jar，不遍历全部依赖
        Path jar = firstKjdpJar(repoRoot);
        if (jar != null) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                zf.stream().filter(e -> e.getName().startsWith("META-INF/config/spring/") && e.getName().endsWith(".xml"))
                        .forEach(e -> {
                            try (InputStream in = zf.getInputStream(e)) {
                                beans.putAll(beanUrls(in, e.getName()));
                            } catch (Exception ignore) {
                            }
                        });
            } catch (Exception ignore) {
            }
        }
        for (Map.Entry<String, String> en : beans.entrySet()) {
            Matcher m = BEAN_URL.matcher(en.getKey());
            if (!m.matches()) continue;
            String cls = en.getValue();
            if (cls == null || !FRONT_CLASS.matcher(cls).find()) continue;
            String path = "/" + m.group(1);
            out.put(path, spec(path, cls));
        }
        return out;
    }

    /** KJDP 业务分发口的信封形状（与前端 ka.js:1079-1091 逐字对应） */
    private DispatchSpec spec(String path, String cls) {
        Map<String, Object> hdr = new LinkedHashMap<>();
        hdr.put("OP_CODE", "1");
        hdr.put("OP_ROLE", "");
        hdr.put("OP_BRANCH", "");
        hdr.put("OP_SITE", "");
        hdr.put("USER_TICKET_INFO", "");
        hdr.put("OP_WAY", "1");
        hdr.put("OP_LANGUAGE", "1");
        hdr.put("OP_PROGRAM", "");
        // "$code" 由测试台换成当前选中的服务码（服务端操作员信息按会话补齐，前端这几个字段就发功能号）
        hdr.put("SERVER_ID", DefaultValueRules.CODE_TEMPLATE);
        hdr.put("MSG_ID", DefaultValueRules.CODE_TEMPLATE);
        return new DispatchSpec(path, cls, "REQUESTS", "REQ_MSG_HDR", "REQ_COMM_DATA",
                "service", "bex_codes", "P9999999", "returnType=json&noEncrypt=1",
                // 头字段清单没有盘上的 Java 出处可学（这套信封是前端 ka.js 写出来的，十个键已经全在
                // hdrTemplate 里），所以 hdrFields 留空——界面按模板出请求体，不另立一套说法
                "text/xml; charset=utf-8", hdr, List.of());
    }

    @Override
    public List<DispatchTarget> declaredTargets(Path repoRoot) {
        List<DispatchTarget> out = new ArrayList<>();
        Path meta = repoRoot.resolve("WEB-INF/conf/metaData");
        if (!Files.isDirectory(meta)) return out;
        String entry = "/redis_ajax";
        Map<String, DispatchSpec> es = entries(repoRoot);
        if (!es.isEmpty()) entry = es.keySet().iterator().next();

        Path svc = meta.resolve("serviceConf");
        if (Files.isDirectory(svc)) {
            try (Stream<Path> w = Files.walk(svc)) {
                for (Path p : w.filter(Files::isRegularFile).toList()) {
                    out.addAll(parseServices(p, entry));
                }
            } catch (Exception ignore) {
            }
        }
        Path bex = meta.resolve("bexConf");
        if (Files.isDirectory(bex)) {
            try (Stream<Path> w = Files.walk(bex)) {
                for (Path p : w.filter(Files::isRegularFile).toList()) {
                    out.addAll(parseBexs(p, entry));
                }
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    private List<DispatchTarget> parseServices(Path file, String entry) {
        List<DispatchTarget> out = new ArrayList<>();
        for (Element e : elements(file, "service")) {
            String code = attr(e, "service_code");
            if (code == null) continue;
            out.add(new DispatchTarget("service", code, attr(e, "service_name"), entry, List.of(),
                    List.of(relOf(file) + " <service>")));
        }
        return out;
    }

    private List<DispatchTarget> parseBexs(Path file, String entry) {
        List<DispatchTarget> out = new ArrayList<>();
        for (Element e : elements(file, "bex")) {
            String code = attr(e, "bex_code");
            if (code == null) continue;
            List<DispatchParam> params = new ArrayList<>();
            NodeList ps = e.getElementsByTagName("param");
            for (int i = 0; i < ps.getLength(); i++) {
                Element pe = (Element) ps.item(i);
                String key = attr(pe, "para_code");
                if (key == null) continue;
                String label = attr(pe, "para_name");
                params.add(new DispatchParam(key, jsonType(attr(pe, "data_type")), attr(pe, "def_val"),
                        key.equalsIgnoreCase(label) ? null : label, "metaData", relOf(file)));
            }
            out.add(new DispatchTarget("bex", code, attr(e, "bex_name"), entry, params,
                    List.of(relOf(file) + " <bex bex_code=\"" + code + "\">")));
        }
        return out;
    }

    /** KJDP 的 data_type 是 JDBC 类型码，只见到 12(字符)/2、4(数值) 三档 */
    private String jsonType(String dataType) {
        if (dataType == null) return "string";
        return switch (dataType.trim()) {
            case "2", "3", "4", "5", "6", "7", "8" -> "number";
            default -> "string";
        };
    }

    // ---------- 调用链入参（第 11 轮） ----------

    /** KJDP 的 atom 从请求负载里取数据的固定写法：Map params = (Map) data.getBusinessData("params") */
    private static final Pattern BUSINESS_DATA = Pattern.compile(
            "(\\w+)\\s*=\\s*(?:\\(\\s*(?:java\\.util\\.)?[Mm]ap[^)]*\\)\\s*)?(?:[\\w.]+\\s*\\.\\s*)?getBusinessData"
                    + "\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final int MAX_CHAIN_FILES = 400;

    /**
     * service_code → service_business.business_code → ① bexConf 里同名 bex 的 &lt;param&gt; 声明
     * ② atomConf 的 atom_class#atom_method，本仓有 .java 时交给通用的按键名反推引擎（第九轮那套）。
     * 实测口径：work_app 554 个服务里，走 ① 能拿到带中文说明与默认值的入参表，走 ② 覆盖本仓有源码的 atom。
     */
    @Override
    public List<DispatchTarget> chainTargets(Path repoRoot) {
        List<DispatchTarget> out = new ArrayList<>();
        Path meta = repoRoot.resolve("WEB-INF/conf/metaData");
        if (!Files.isDirectory(meta)) return out;
        Map<String, List<String>> chains = new LinkedHashMap<>();
        Map<String, String> svcNames = new LinkedHashMap<>();
        for (Path p : xmlUnder(meta.resolve("serviceConf"))) {
            for (Element e : elements(p, "service")) {
                String code = attr(e, "service_code");
                if (code == null) continue;
                if (attr(e, "service_name") != null) svcNames.put(code, attr(e, "service_name"));
                NodeList bs = e.getElementsByTagName("service_business");
                for (int i = 0; i < bs.getLength(); i++) {
                    String bc = attr((Element) bs.item(i), "business_code");
                    if (bc != null) chains.computeIfAbsent(code, k -> new ArrayList<>()).add(bc);
                }
            }
        }
        if (chains.isEmpty()) return out;

        Map<String, List<DispatchParam>> bexParams = new LinkedHashMap<>();
        for (Path p : xmlUnder(meta.resolve("bexConf"))) {
            for (DispatchTarget t : parseBexs(p, "/redis_ajax")) {
                if (!t.params().isEmpty()) bexParams.putIfAbsent(t.code(), t.params());
            }
        }
        Map<String, List<String[]>> atoms = new LinkedHashMap<>();
        for (Path p : xmlUnder(meta.resolve("atomConf"))) {
            for (Element e : elements(p, "atom")) {
                String ac = attr(e, "atom_code"), cls = attr(e, "atom_class"), ms = attr(e, "atom_method");
                if (ac == null || cls == null || ms == null) continue;
                atoms.computeIfAbsent(ac, k -> new ArrayList<>()).add(new String[]{cls, ms});
            }
        }
        BodyFieldInferer inferer = null;
        Map<String, Path> atomSources = new LinkedHashMap<>();   // "com.x.Y#m" → .java
        if (!atoms.isEmpty()) {
            Map<String, List<Path>> byName = javaByName(repoRoot);
            List<Path> sources = new ArrayList<>();
            for (List<String[]> list : atoms.values()) {
                for (String[] am : list) {
                    Path f = sourceOf(am[0], byName);
                    if (f == null) continue;
                    atomSources.put(am[0] + "#" + am[1], f);
                    if (!sources.contains(f)) sources.add(f);
                }
            }
            if (!sources.isEmpty() && sources.size() <= MAX_CHAIN_FILES) {
                inferer = new BodyFieldInferer(new JavaSourceIndex(sources));
            }
        }

        for (Map.Entry<String, List<String>> en : chains.entrySet()) {
            List<DispatchParam> ps = new ArrayList<>();
            List<String> ev = new ArrayList<>();
            for (String bc : en.getValue()) {
                List<DispatchParam> declared = bexParams.get(bc);
                if (declared != null) {
                    for (DispatchParam p : declared) {
                        addParam(ps, new DispatchParam(p.key(), p.type(), p.def(), p.label(),
                                "chain", "调用链 " + en.getKey() + " → 业务 " + bc + "（bexConf 声明）"));
                    }
                    addEvidence(ev, en.getKey() + " → " + bc + "（bexConf）");
                    continue;
                }
                if (inferer == null) continue;
                for (String[] am : atoms.getOrDefault(bc, List.of())) {
                    Path f = atomSources.get(am[0] + "#" + am[1]);
                    if (f == null) continue;
                    List<String> seeds = businessDataVars(f);
                    if (seeds.isEmpty()) continue;
                    int before = ps.size();
                    for (BodyFieldInferer.InferredKey k : inferer.infer(f, am[1], seeds, List.of())) {
                        if ("low".equals(k.confidence())) continue;   // SQL 占位兜底对 atom 无意义
                        addParam(ps, new DispatchParam(k.key(), k.jsonType(), null, null, "chain",
                                "调用链 " + bc + " → " + simpleOf(am[0]) + "#" + am[1] + " 从 getBusinessData 负载里取值"));
                    }
                    if (ps.size() > before || before == 0) {
                        addEvidence(ev, bc + " → " + am[0] + "#" + am[1] + "（本仓源码）");
                    }
                }
            }
            if (!ps.isEmpty()) {
                out.add(new DispatchTarget("service", en.getKey(), svcNames.get(en.getKey()),
                        "/redis_ajax", ps, ev));
            }
        }
        return out;
    }

    private void addParam(List<DispatchParam> ps, DispatchParam cand) {
        for (DispatchParam p : ps) {
            if (p.key().equalsIgnoreCase(cand.key())) return;   // 既有证据优先，不覆盖
        }
        ps.add(cand);
    }

    private void addEvidence(List<String> ev, String s) {
        if (ev.size() < 5 && !ev.contains(s)) ev.add(s);
    }

    /** atom_class 全名 → 本仓 .java（按包路径尾段匹配，避免同名类认错） */
    private Path sourceOf(String fqn, Map<String, List<Path>> byName) {
        int dot = fqn.lastIndexOf('.');
        String tail = (dot < 0 ? fqn : fqn.substring(dot + 1)) + ".java";
        String pkg = dot < 0 ? "" : fqn.substring(0, dot).replace('.', '/');
        Path best = null;
        for (Path p : byName.getOrDefault(tail, List.of())) {
            String rel = p.toString().replace('\\', '/');
            if (pkg.isEmpty() || rel.contains("/" + pkg + "/")) best = p;
        }
        return best;
    }

    private Map<String, List<Path>> javaByName(Path repoRoot) {
        Map<String, List<Path>> out = new LinkedHashMap<>();
        try (Stream<Path> w = Files.walk(repoRoot)) {
            w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .limit(20000)
                    .forEach(p -> out.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p));
        } catch (Exception ignore) {
        }
        return out;
    }

    /** 一个 atom 文件里从请求负载接出来的变量名（getBusinessData("params") / ("commParams")） */
    private List<String> businessDataVars(Path f) {
        Map<String, Boolean> names = new LinkedHashMap<>();
        try {
            Matcher m = BUSINESS_DATA.matcher(readTolerant(f));
            while (m.find() && names.size() < 6) names.put(m.group(1), true);
        } catch (Exception ignore) {
        }
        return new ArrayList<>(names.keySet());
    }

    private String simpleOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private List<Path> xmlUnder(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> w = Files.walk(dir)) {
            w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .forEach(out::add);
        } catch (Exception ignore) {
        }
        return out;
    }

    // ---------- 前端调用点 ----------

    private static final Pattern CALL_KEY = Pattern.compile(
            "\\b(service|bex_codes)\\s*:\\s*['\"]([^'\"]{1,64})['\"]");
    private static final Pattern JSDOC_PARAM = Pattern.compile(
            "@param\\s*\\{\\s*(\\w+)\\s*}\\s+(\\w+)\\s*([^\\r\\n]*)");

    @Override
    public List<DispatchTarget> callSiteTargets(List<Path> jsRepoRoots) {
        Map<String, DispatchTarget> merged = new LinkedHashMap<>();
        for (Path root : jsRepoRoots) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> w = Files.walk(root)) {
                w.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".js"))
                        .filter(p -> !p.toString().replace('\\', '/').contains("/node_modules/"))
                        .forEach(files::add);
            } catch (Exception e) {
                continue;
            }
            for (Path f : files) {
                try {
                    if (Files.size(f) > MAX_JS_BYTES) continue;
                    scanJs(f, merged);
                } catch (Exception ignore) {
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private void scanJs(Path file, Map<String, DispatchTarget> merged) throws Exception {
        String text = readTolerant(file);
        Matcher m = CALL_KEY.matcher(text);
        String rel = relOf(file);
        while (m.find()) {
            String kind = "service".equals(m.group(1)) ? "service" : "bex";
            // 老工程里确有 bex_codes: " getXxx" 这种带空格的写法，不 trim 会和 metaData 的码分成两条
            String code = m.group(2).trim();
            if (code.isEmpty()) continue;
            String obj = enclosingObject(text, m.start());
            if (obj == null) continue;
            Map<String, String[]> doc = precedingDoc(text, m.start());
            List<DispatchParam> params = new ArrayList<>();
            for (Map.Entry<String, String> k : topLevelLiterals(obj).entrySet()) {
                String key = k.getKey();
                if (key.equals("service") || key.equals("bex_codes")) continue;
                String[] dl = doc.get(key.toLowerCase(Locale.ROOT));
                params.add(new DispatchParam(key, dl != null ? jsType(dl[0]) : "string",
                        literal(k.getValue()), dl != null && !dl[1].isBlank() ? dl[1] : null,
                        "js", rel + ":" + lineOf(text, m.start())));
            }
            String mk = kind + "|" + code;
            DispatchTarget old = merged.get(mk);
            if (old == null) {
                merged.put(mk, new DispatchTarget(kind, code, null, "/redis_ajax", params,
                        List.of(rel + ":" + lineOf(text, m.start()))));
            } else {
                List<DispatchParam> ps = new ArrayList<>(old.params());
                for (DispatchParam p : params) {
                    if (ps.stream().noneMatch(x -> x.key().equalsIgnoreCase(p.key()))) ps.add(p);
                }
                List<String> ev = new ArrayList<>(old.evidence());
                String mark = rel + ":" + lineOf(text, m.start());
                if (ev.size() < 3 && !ev.contains(mark)) ev.add(mark);
                merged.put(mk, new DispatchTarget(kind, code, old.name(), old.entryPath(), ps, ev));
            }
        }
    }

    /** 向前找到包住该位置的对象字面量，再向后配平收尾；过大或配平失败返回 null */
    private String enclosingObject(String text, int pos) {
        int depth = 0;
        int start = -1;
        for (int i = pos - 1; i >= 0 && pos - i < 6000; i--) {
            char c = text.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                if (depth == 0) {
                    start = i;
                    break;
                }
                depth--;
            }
        }
        if (start < 0) return null;
        depth = 0;
        for (int i = start; i < text.length() && i - start < 8000; i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * 对象字面量的顶层键 → 字面量值。跳过字符串/注释；嵌套层内的内容一律视为非简单值；
     * 只在整体深度归零时收尾，所以不会被嵌套对象的闭合括号误触发。
     */
    private Map<String, String> topLevelLiterals(String obj) {
        Map<String, String> out = new LinkedHashMap<>();
        int depth = 0;
        int i = 0;
        StringBuilder tok = new StringBuilder();
        String key = null;
        while (i < obj.length()) {
            char c = obj.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int j = skipString(obj, i, c);
                String body = obj.substring(Math.min(i + 1, obj.length()), Math.min(Math.max(i + 1, j - 1), obj.length()));
                if (depth == 1) {
                    if (key == null) {
                        int k = colonAfter(obj, j);
                        if (k < obj.length() && obj.charAt(k) == ':') {
                            key = body;
                            tok.setLength(0);
                            i = k + 1;
                            continue;
                        }
                    }
                    tok.append(body);
                }
                i = j;
                continue;
            }
            if (c == '/' && i + 1 < obj.length() && obj.charAt(i + 1) == '/') {
                while (i < obj.length() && obj.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < obj.length() && obj.charAt(i + 1) == '*') {
                int j = obj.indexOf("*/", i + 2);
                i = j < 0 ? obj.length() : j + 2;
                continue;
            }
            if (c == '{' || c == '[' || c == '(') {
                depth++;
                if (depth > 1 && key != null) tok.append("#");
                i++;
                continue;
            }
            if (c == '}' || c == ']' || c == ')') {
                depth--;
                if (depth == 0) {
                    if (key != null) out.put(key, tok.toString().trim());
                    return out;
                }
                i++;
                continue;
            }
            if (depth == 1 && c == ',') {
                if (key != null) {
                    out.put(key, tok.toString().trim());
                    key = null;
                    tok.setLength(0);
                }
                i++;
                continue;
            }
            if (depth == 1 && c == ':' && key == null) {
                key = tok.toString().trim().replaceAll("^[\"'`]|[\"'`]$", "");
                tok.setLength(0);
                i++;
                continue;
            }
            if (depth == 1) tok.append(c);
            i++;
        }
        return out;
    }

    private int colonAfter(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private int skipString(String s, int from, char quote) {
        int i = from + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) return i + 1;
            i++;
        }
        return i;
    }

    /** 调用点上方注释里的 @param {Type} name 中文说明 → name(小写) → [type, 中文] */
    private Map<String, String[]> precedingDoc(String text, int pos) {
        Map<String, String[]> out = new LinkedHashMap<>();
        int from = Math.max(0, text.lastIndexOf('\n', Math.max(0, pos - 1)));
        int back = Math.max(0, pos - 900);
        String head = text.substring(back, from);
        Matcher m = JSDOC_PARAM.matcher(head);
        while (m.find()) {
            out.put(m.group(2).toLowerCase(Locale.ROOT), new String[]{m.group(1), m.group(3).trim()});
        }
        return out;
    }

    private String jsType(String docType) {
        if (docType == null) return "string";
        return switch (docType.toLowerCase(Locale.ROOT)) {
            case "number", "int", "integer", "long", "float", "double" -> "number";
            case "boolean", "bool" -> "boolean";
            case "object", "json", "array" -> "object";
            default -> "string";
        };
    }

    /** 字面量值只保留短字符串样例，避免把整段代码当默认值存下来 */
    private String literal(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.length() > 40 || v.isEmpty()) return null;
        if (v.matches("^-?\\d+(\\.\\d+)?$") || v.equals("true") || v.equals("false")) return v;
        if (v.matches("^['\"`].*['\"`]$")) return v.substring(1, v.length() - 1);
        return null;
    }

    private int lineOf(String text, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    // ---------- 通用小工具 ----------

    private Map<String, String> beanUrls(Path file, String tag) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return beanUrls(in, tag == null ? file.getFileName().toString() : tag);
        }
    }

    private Map<String, String> beanUrls(InputStream in, String tag) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element e : elements(in, tag, "bean")) {
            String name = e.getAttribute("name");
            if (name.isEmpty()) name = e.getAttribute("id");
            if (!name.startsWith("/**/")) continue;
            out.put(name, e.getAttribute("class"));
        }
        return out;
    }

    private List<Element> elements(Path file, String tag) {
        try (InputStream in = Files.newInputStream(file)) {
            return elements(in, relOf(file), tag);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Element> elements(InputStream in, String tagSource, String tag) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder b = dbf.newDocumentBuilder();
        b.setEntityResolver((p, s) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
        Document doc = b.parse(in);
        List<Element> out = new ArrayList<>();
        NodeList list = doc.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) out.add((Element) list.item(i));
        return out;
    }

    private String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String relOf(Path p) {
        Path base = p.getFileName();
        Path parent = p.getParent();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 4 && parent != null; i++) {
            parts.add(0, parent.getFileName().toString());
            parent = parent.getParent();
        }
        parts.add(base.toString());
        return String.join("/", parts);
    }

    /** 老工程 JS 编码不统一：先按 UTF-8 严格解，失败退回 GBK */
    private String readTolerant(Path f) throws Exception {
        byte[] raw = Files.readAllBytes(f);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (Exception e) {
            return new String(raw, Charset.forName("GBK"));
        }
    }

    private Path firstKjdpJar(Path repoRoot) {
        Path lib = repoRoot.resolve("WEB-INF/lib");
        if (!Files.isDirectory(lib)) return null;
        try (Stream<Path> w = Files.list(lib)) {
            return w.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith("kjdp") && n.endsWith(".jar");
            }).min(Comparator.comparing(p -> p.getFileName().toString().length())).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
