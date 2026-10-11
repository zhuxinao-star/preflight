package com.preflight.parser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「这一步是不是登录语义的哪一步」的唯一判据（第四十九轮收口）。
 *
 * <p>第 47、48 两轮里这套词表存在三处：线索端点的过滤键、卡片草稿的角色判定、前端调用点的链判定。
 * 三份词表早晚会差几个词，而差一个词的结果是"这条链到底是不是登录"在两个出口给出两个答案
 * （第卅轮口径：一条事实一个出口）。本轮把它收成一个类，三个调用方共用。
 *
 * <p>词表只认跨项目通行的英文语义名，不认任何一家项目的专有叫法：认了别家的路径写法，
 * 下一家就会既认不出也说不清为什么认不出。
 */
public final class LoginSemantics {

    /** 登出语义：铺进登录流会立刻把刚拿到的会话打没 */
    public static final List<String> LOGOUT_WORDS = List.of("logout", "signout", "logoff", "unauth");
    /** 取码语义：值只能人眼读图，系统不认图 */
    public static final List<String> CAPTCHA_WORDS = List.of("captcha", "kaptcha", "validatecode", "validate_code",
            "checkcode", "imagecode", "vcode", "vercode", "yzm", "randomcode", "random_code");
    /** 取会话语义：通常是上一步响应里那个值 */
    public static final List<String> TOKEN_WORDS = List.of("token", "session", "jti");
    /** 提交账号密码那一步 */
    public static final List<String> LOGIN_WORDS = List.of("login", "signin", "sign-in", "credential", "auth", "sso");

    /** 键名像账号的那些：只认归一化后的整名，不做子串匹配（fileName 里也有 user，不能算账号） */
    public static final Set<String> ACCOUNT_KEYS = Set.of(
            "user", "usr", "username", "user_name", "usrname", "usr_name", "usercode", "user_code",
            "userno", "user_no", "userid", "user_id", "useraccount", "user_account",
            "loginname", "login_name", "loginid", "login_id", "account", "acct", "acctno", "acct_no",
            "staff", "staffno", "staff_no", "staffcode", "staff_code", "ygbh", "emp", "empno", "emp_no",
            "empcode", "emp_code", "operator", "operatorno", "operator_no", "opuser", "op_user",
            "opruser", "opr_user", "operno", "oper_no", "operid", "oper_id");

    /** 键名像密码的那些：命中就填凭据引用，明文本身不进草稿 */
    public static final Set<String> PASSWORD_KEYS = Set.of(
            "password", "passwd", "pwd", "psw", "pass", "userpass", "user_pass", "userpwd", "user_pwd",
            "passwords", "loginpwd", "login_pwd");

    /** 键名像校验码的那些：留空且要说清"为什么给不出值" */
    public static final Set<String> CODE_KEYS = Set.of(
            "code", "captcha", "validatecode", "validate_code", "checkcode", "imagecode",
            "vcode", "vercode", "yzm", "randomcode", "random_code", "authcode", "smscode");

    /**
     * 线索端点用来挑"哪些卡算登录相关"的全部词表：就是那四张角色词表的并集。
     *
     * <p>收成并集是因为这两套词早晚会分家——一份决定"这一张摆不摆出来"、一份决定"摆出来算哪一步"，
     * 分家的结果是某个登录接口在界面上压根不存在，却在铺链时被算成登录那一步。
     */
    public static final List<String> ALL_WORDS = words();

    private LoginSemantics() {}

    private static List<String> words() {
        List<String> w = new ArrayList<>();
        w.addAll(LOGOUT_WORDS);
        w.addAll(CAPTCHA_WORDS);
        w.addAll(TOKEN_WORDS);
        w.addAll(LOGIN_WORDS);
        return List.copyOf(w);
    }

    /** 命中了哪个词（用来在界面上说"为什么这一张算登录线索"）；没命中返回 null */
    public static String matchedWord(String path, String name) {
        Set<String> runs = runs((path == null ? "" : path) + " " + (name == null ? "" : name));
        return firstHit(runs, ALL_WORDS);
    }

    /** 这一张是不是登录链上的一步（含登出——登出也要摆出来并说明为什么不铺进流） */
    public static boolean isLoginSemantic(String path, String name) {
        return matchedWord(path, name) != null;
    }

    /**
     * 只按路径末段判角色：卡片名字里的方法名不参与（{@code #getLoginUserInfo} 那种会被误判成登录入口）。
     * 判定顺序是登出 → 取码 → 取会话 → 登录，先命中的算数（{@code /cc/validateCode} 里也有 "code"，
     * 但它不是提交账号那一步）。
     *
     * <p>切词必须用<b>原样大小写</b>的那份路径：先转小写会抹掉驼峰边界，{@code /cc/getToken} 就成了
     * 一个整词 "gettoken"，取会话那一步既认不出、也没法说清为什么认不出（第四十九轮探针实测）。
     * 比较时两边都过 {@link #squeeze}，写法仍然一致。
     */
    public static String roleOf(String path) {
        String raw = path == null ? "" : path;
        Set<String> tail = endWords(tail(raw));
        Set<String> all = runs(raw);
        if (firstHit(tail, LOGOUT_WORDS) != null || firstHit(all, LOGOUT_WORDS) != null) return "logout";
        if (firstHit(tail, CAPTCHA_WORDS) != null || firstHit(all, CAPTCHA_WORDS) != null) return "captcha";
        if (firstHit(tail, TOKEN_WORDS) != null) return "token";
        if (firstHit(tail, LOGIN_WORDS) != null) return "login";
        return "other";
    }

    /**
     * 末段这个"动作名"的两种写法：整段拼起来的样子 ＋ <b>最后一个词</b>。
     *
     * <p>取会话与提交账号那两步只看末段，且必须看"这个名字<b>是在要什么</b>"：{@code getSession} 要的是
     * 会话，{@code sendSessionMessage} 发的是消息、{@code getQueuingSessionByAgent} 查的是排队。
     * 整段拼写里每一个词都算命中，就会把客服会话那一整排业务接口都判成"取会话那一步"——第四十九轮
     * 探针在真实前端仓里把 20 处调用点里的 11 处算成了登录链的步骤。登出与取码那两个词足够独特，
     * 仍允许在整条路径里找。
     */
    private static Set<String> endWords(String tailSegment) {
        Set<String> out = new LinkedHashSet<>();
        List<String> tk = tokens(tailSegment);
        if (tk.isEmpty()) return out;
        StringBuilder b = new StringBuilder();
        for (String t : tk) b.append(t);
        out.add(b.toString());
        out.add(tk.get(tk.size() - 1));
        return out;
    }

    /**
     * 取"哪条链"＝<b>路径的父目录</b>（最多两段），第四十九轮修正。
     *
     * <p>第 48 轮按"前两段"取，两个词的调用点是对的（{@code /unvisit/platform/login → /unvisit/platform}），
     * 但一个词的调用点会把<b>动作本身</b>算进链名（{@code /cc/getToken → /cc/gettoken}），
     * 于是同一条链上取码、取号、登录三张卡各成一条链，每条都"没有登录那一步"，
     * 整条能铺出来的流一条都不剩。链是"这些步骤属于同一次登录过程"，末段是动作，不该进键。
     */
    public static String chainOf(String path) {
        return chainOf(path, java.util.List.of());
    }

    /**
     * 同上，但先摘掉<b>部署前缀</b>那几段，第四十九轮收口。
     *
     * <p>为什么必须摘：网关那一段在两条证据源里写得<b>不一样</b>。注解学到的路径是应用内部的
     * （{@code /cc/getToken}），前端有的调用点照着对外地址写（{@code /ni-gateway/cc/login}）、
     * 有的走另一个 axios 实例连前缀都没带（{@code /open/doLogin}）。按整条路径算链就把
     * <b>同一次登录过程切成两条链</b>，每条都"没有登录那一步"，整条流一条都铺不出来
     * （真实盘与合成夹具各坏过一次，方向还相反）。
     *
     * <p>前缀只认<b>这一趟扫描真读到的 baseURL</b>（由调用方给），不认任何写死的网关名：
     * 别家项目的前缀在这里既不该生效、也不该被当成模块名。
     *
     * @param dropHeads 这一趟读到的部署前缀（形如 {@code /ni-gateway}、{@code /api/v1}），空＝不摘
     */
    public static String chainOf(String path, java.util.Collection<String> dropHeads) {
        String n = DefaultValueRules.normalize(path == null ? "" : path);
        List<String> segs = new ArrayList<>();
        for (String s : n.replaceAll("^/+", "").replaceAll("/+$", "").split("/")) {
            if (!s.isBlank()) segs.add(s);
        }
        Set<String> heads = new LinkedHashSet<>();
        for (String h : dropHeads == null ? java.util.List.<String>of() : dropHeads) {
            String hn = DefaultValueRules.normalize(h == null ? "" : h);
            for (String s : hn.replaceAll("^/+", "").replaceAll("/+$", "").split("/")) {
                if (!s.isBlank()) heads.add(squeeze(s));             // 逐段收：多段前缀（/api/v1）也得摘得动
            }
        }
        while (segs.size() > 2 && heads.contains(squeeze(segs.get(0)))) {
            segs.remove(0);                                          // 只摘最前面那几段"部署才带的那一层"
        }
        if (segs.size() <= 1) return "/";                            // 根上一步（/login）：就一条链
        List<String> parents = segs.subList(0, segs.size() - 1);
        List<String> take = parents.subList(Math.max(0, parents.size() - 2), parents.size());
        return "/" + String.join("/", take);
    }

    public static String tail(String normalizedPath) {
        String p = normalizedPath.replaceAll("/+$", "");
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** 点路径的叶子（数组位 x[].y 取 y）——分类只看叶子名，容器键不参与 */
    public static String leafOf(String dotPath) {
        String s = dotPath == null ? "" : dotPath;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        return s.endsWith("[]") ? s.substring(0, s.length() - 2) : s;
    }

    public static boolean isAccount(String normalizedLeaf) {
        return ACCOUNT_KEYS.contains(normalizedLeaf);
    }

    public static boolean isPassword(String normalizedLeaf) {
        return PASSWORD_KEYS.contains(normalizedLeaf);
    }

    public static boolean isCode(String normalizedLeaf) {
        return CODE_KEYS.contains(normalizedLeaf);
    }

    /**
     * 「这段文字（可以是一整个文件）里出现过取码语义的那个词」：整词判定，词表只有
     * {@link #CAPTCHA_WORDS} 这一处。
     *
     * <p>为什么不是 {@code contains("captcha")}：那等于只认一家的写法。端点叫 {@code validateCode}、
     * {@code checkCode}、{@code randomCode} 的项目照样要验证码，前端也照样把自己要求的位数写在盘上；
     * 门槛只认 "captcha" 时那位数就永远读不回来，草稿只能摆一个空串，而"填几位就能过"这件事明明有证据。
     */
    public static boolean mentionsCaptcha(String text) {
        if (text == null || text.isBlank()) return false;
        Set<String> run = new LinkedHashSet<>();
        for (String p : text.split("[^0-9A-Za-z]+")) {
            if (p.isEmpty() || p.length() > 64) continue;             // 打包产物那种超长串不算词
            run.add(squeeze(p));                                      // validateCode → validatecode
            for (String q : p.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                run.add(squeeze(q));                                  // 也要认得拆开的每一段
            }
        }
        return firstHit(run, CAPTCHA_WORDS) != null;
    }

    /**
     * 「这段文字里有没有登录语义的那个词」唯一的判据：认<b>整词</b>，不认子串。
     *
     * <p>为什么收成单点、又为什么不能用 {@code contains}：子串匹配会把 {@code getMyAuthorizedMenu}
     * （查权限菜单）算成登录，只因 "Authorized" 里含着 "auth"。一个假阳性的代价不是界面上多摆一张卡，
     * 而是<b>这条链被算成"有登录那一步"</b>，于是铺出一条照着权限查询去登录的流。第 49 轮在真实前端仓
     * 实测：27 处调用点里有 26 处是这么被误判的。改成整词后 {@code /cc/getToken}、{@code signIn}、
     * {@code validateCode}、{@code getLoginUserInfo} 这些真写法照旧认得出——它们本来就是一整个词。
     */
    public static String firstHit(Set<String> runs, List<String> words) {
        for (String w : words) if (runs.contains(squeeze(w))) return w;
        return null;
    }

    /** 这段文字的拼写法：每个词本身、以及连续若干个词拼起来的样子（路径最多切 24 个词，防病态长名） */
    public static Set<String> runs(String s) {
        Set<String> out = new LinkedHashSet<>();
        List<String> tk = tokens(s);
        for (int i = 0; i < tk.size(); i++) {
            StringBuilder b = new StringBuilder();
            for (int j = i; j < tk.size(); j++) {
                b.append(tk.get(j));
                out.add(b.toString());
            }
        }
        return out;
    }

    /** 驼峰、下划线、中划线、点、斜杠都算词边界；数字不切（{@code v2} 是一个词） */
    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String p : s.split("[^0-9A-Za-z]+")) {
            if (p.isEmpty()) continue;
            for (String q : p.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                if (!q.isEmpty()) out.add(squeeze(q));
            }
            if (out.size() >= 24) break;
        }
        return out;
    }

    /** 词表项与切出来的词用同一个写法比：去掉分隔符、小写（{@code sign-in}＝{@code SignIn}＝{@code signin}） */
    private static String squeeze(String w) {
        return w.replaceAll("[^0-9A-Za-z]", "").toLowerCase(Locale.ROOT);
    }
}
