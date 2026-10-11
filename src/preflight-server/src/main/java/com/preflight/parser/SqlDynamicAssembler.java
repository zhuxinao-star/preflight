package com.preflight.parser;

import org.w3c.dom.CDATASection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis 动态 SQL 拼装器（第 14 轮）。
 *
 * 输入 db_objects.dynamic_body（语句内层原文，占位符与标签都还在），输出"按填进来的参数拼出的可执行 SQL"：
 * 命中的 {@code <if>}/{@code <when>} 进 SQL，没命中的整段剔除——等同 MyBatis 运行时行为，
 * 所以互斥条件、重复 order by 不会同时出现（拍平模板做不到）。
 *
 * 判断表达式只认白名单写法（变量与 null / 空串 / 字面量 比较，支持 and / or），
 * 认不出的一律不拼并标 unknown，由界面给【强制包含】开关——不猜。
 * {@code #{}} 走 JDBC 绑定（values 与 ? 同序），{@code ${}} 按字面替换（这本来就是应用自己的写法）。
 */
public final class SqlDynamicAssembler {

    /** 条件片段。id 在"与参数无关的预扫描"里按文档顺序定死，所以人工覆盖不会因填了什么而漂移。
     *  参数名带前缀：{@code #{x}} = JDBC 绑定参数，{@code ${x}} = 字面替换（应用自己的写法，值要含 SQL 片段本身，
     *  例如 order by 段要填 "order by xxx"）；tests = 判断表达式里引用的变量（不填就永远拼不进来，也要给输入框） */
    public record Fragment(int id, String kind, String test, String preview, List<String> params,
                           List<String> tests, boolean included, String reason, String level) {}

    /** 需要填的参数。kind=hash 是绑定的 ?，kind=dollar 是字面替换；fragId=0 表示来自固定段（恒需填）。
     *  hashSlots = 本次拼出的 SQL 里由它产生的 {@code ?} 是第几个（1 起，同名多处会列多个）——
     *  界面据此说"值会作为第 N 个 ? 送进库"，而不是自己按片段列表反推（固定段参数不属于任何片段，反推必错）。
     *  outType = 占位符自己声明的出参类型（{@code #{RC,mode=OUT,jdbcType=CURSOR}} → CURSOR），普通入参为 null。
     *  出参不向人要值：值没有去处，硬塞给库就是达梦那个 cursorStmt 空指针。
     *  clause = 这一位在自己那段 SQL 里"跟哪一列比、怎么比、能不能多值、这一列的取值域"（第 27 轮）。
     *  它是读取期从原文现算的派生视图，不落库 ⇒ schema_hash 不变、历史用例不会转待复核。
     *  算不出来就是 null：界面照旧只给一个空输入框，绝不拿猜的列名去查库。
     *  emitted=false（第 28 轮）：这一段本次没拼进 SQL，值填了也进不去（要它得先【强制包含】那一段）。
     *  之所以仍然列出来、仍然给条款与候选值：候选值本来就是"该往这一位填什么"的答复，
     *  而旧规则只在拼装时登记参数 ⇒ 一个都没填时参数清单是空的 ⇒ 候选值一条都给不出，
     *  等于要求用户先填对值才看得到该填什么（真实卡 4881 的 24 个参数全中这一条）。
     *  位序与绑定值只认真正拼进去的那些：{@code values}/{@code outSlots}/{@code hashSlots} 一概不含未拼进的位。 */
    public record Param(String name, String kind, int fragId, String context, String test, List<Integer> hashSlots,
                        String outType, SqlParamClause.Clause clause, boolean emitted) {
        /** 老调用点（未推条款、本次确实拼进的场合）仍可用 */
        public Param(String name, String kind, int fragId, String context, String test, List<Integer> hashSlots,
                     String outType) {
            this(name, kind, fragId, context, test, hashSlots, outType, null, true);
        }

        public Param(String name, String kind, int fragId, String context, String test, List<Integer> hashSlots,
                     String outType, SqlParamClause.Clause clause) {
            this(name, kind, fragId, context, test, hashSlots, outType, clause, true);
        }
    }

    /** 拼出 SQL 里的一个出参位（第 slot 个 ?）。执行层据此 registerOutParameter 并把游标读成独立结果集，
     *  界面据此不再标成"没填"。位序仍然保留（绑定值恒为 null），这样 values 与 ? 永远逐位对得上。 */
    public record OutSlot(int slot, String name, String type) {}

    public record Result(List<Fragment> fragments, List<Param> params, String sql, List<Object> values,
                         List<String> errors, List<String> warnings, List<OutSlot> outSlots,
                         Map<String, List<SqlParamClause.Labeled>> testValues) {
        public Result(List<Fragment> fragments, List<Param> params, String sql, List<Object> values,
                      List<String> errors, List<String> warnings) {
            this(fragments, params, sql, values, errors, warnings, List.of(), Map.of());
        }

        public Result(List<Fragment> fragments, List<Param> params, String sql, List<Object> values,
                      List<String> errors, List<String> warnings, List<OutSlot> outSlots) {
            this(fragments, params, sql, values, errors, warnings, outSlots, Map.of());
        }
    }

    /** 还原结果。sql=null 表示这条没还原，reason 说明为什么（界面继续显示拍平模板，并把原因原样透出）。
     *  hashNames / dollarNames 按出现顺序逐位对应模板里的第 N 个 {@code ?} / 第 N 处 DOLLAR 哨兵，
     *  界面据此给 ② 区的绑定行标出"这一位是 #{OP_CODE}"。 */
    public record Named(String sql, List<String> hashNames, List<String> dollarNames, String reason) {
        static Named skip(String why) { return new Named(null, List.of(), List.of(), why); }
    }

    private static final Pattern HASH = Pattern.compile("#\\{\\s*([^}]+?)\\s*}");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{\\s*([^}]+?)\\s*}");
    /** 占位符全文（含属性段）。形参与 MybatisMapperParser 清洗时用的正则同形——
     *  清洗认不出的（嵌套花括号）这里也认不出，两边的数量才可能配得上。 */
    private static final Pattern HASH_FULL = Pattern.compile("#\\{[^{}]*}");
    private static final Pattern DOLLAR_FULL = Pattern.compile("\\$\\{[^{}]*}");
    /** 模板里的 DOLLAR 哨兵。前后挨着标识符字符就不算（'DOLLAR_AMT' 这类真实字面量不能被当成占位符） */
    private static final Pattern DOLLAR_TOKEN = Pattern.compile("(?<![A-Za-z0-9_$#])DOLLAR(?![A-Za-z0-9_$])");
    private static final String SENTINEL = "DOLLAR";
    private static final Set<String> CONDITIONAL = Set.of("if", "when", "otherwise");

    /** 认识的判断写法：{@code 变量 == / != 字面量}（字面量含 null、'x'、"x"、数字、{@code 'x'.toString()}）、两侧可换，
     *  以及裸变量 {@code <if test="dabh">}（OGNL 真值）；多个原子用 and / or 串，带括号的一律不猜 */
    private static final Pattern TO_STRING = Pattern.compile("(['\"])([^'\"]*)\\1\\s*\\.\\s*toString\\(\\s*\\)");
    private static final Pattern CMP = Pattern.compile("==|!=");
    private static final Pattern VAR = Pattern.compile("[A-Za-z_]\\w*(?:\\.\\w+)*");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    /** 判断表达式里的字面量（先抹掉才不会把 'abc' 里的 abc 当成变量名） */
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern PLACEHOLDER_IN_TEST = Pattern.compile("[#$]\\{[^}]*}");
    private static final Set<String> TEST_WORDS = Set.of("null", "true", "false", "and", "or", "not", "empty", "instanceof");
    /** resolve() 认不出某一侧时的哨兵（new String 保证不与任何正常值同引用） */
    private static final String BAD = new String("\u0000preflight-unknown");

    private SqlDynamicAssembler() {}

    // ==================== 对外入口 ====================

    /**
     * @param inputs    参数名 → 值（留空与"没有这个参数"同义，与 MyBatis 的 {@code x != null} 判定一致）
     * @param overrides 片段 id → 人工强制包含(true)/排除(false)
     */
    public static Result compose(String dynamicBody, Map<String, String> inputs, Map<Integer, Boolean> overrides) {
        Map<String, String> in = inputs == null ? Map.of() : inputs;
        Map<Integer, Boolean> ov = overrides == null ? Map.of() : overrides;
        if (dynamicBody == null || dynamicBody.isBlank()) {
            return new Result(List.of(), List.of(), null, List.of(),
                    List.of("这张卡没有动态 SQL 原文（学习时未存，或语句本就没有动态标签），只能按拍平模板人工补条件；重新学习后可按条件填参"),
                    List.of());
        }
        Element root;
        try {
            root = parseRoot(dynamicBody);
        } catch (Exception e) {
            return new Result(List.of(), List.of(), null, List.of(),
                    List.of("动态原文解析失败：" + e.getMessage()), List.of());
        }

        Ctx c = new Ctx(in, ov);
        index(root, c);                       // 预扫描：片段 id 与静态信息，与参数完全无关
        scanAll(root, c, 0, null);            // 第 28 轮：另一趟"全部片段的参数事实"，与本次拼没拼进无关
        StringBuilder out = new StringBuilder();
        renderChildren(root, out, c, 0, null); // 渲染：只有命中的片段会往里写

        // 第 24 轮：没填不再拦下整条。用户口径「没填的都按空字符，入参那里就是 ''」——
        // 真实卡 call p_hcgl_hf_mdgl.hf_100109_proc 有 25 处 '${x}' 全在引号里，一个不填本该拼出 ('', '', …)，
        // 旧规则却把 19 个参数一律列成"必须填"，等于把 ③ 面板变成填空题。
        // 引号外的 ${} 空着会拼成语法不通的语句：照拼，让库自己报错（报错链与行号定位见第 23 轮 b）。
        // 条件判定不跟着改：仍按"没填＝没有这个值"（第 14 轮口径），否则 <if test="x != null"> 会因空串不为 null 莫名拼进来。
        List<String> emptyDollar = new ArrayList<>();
        List<String> emptyHash = new ArrayList<>();
        for (Param p : c.paramList) {
            if (p.outType() != null) continue;         // 出参不向人要值
            String v = in.get(p.name());
            if (v != null && !v.isBlank()) continue;
            ("dollar".equals(p.kind()) ? emptyDollar : emptyHash).add(p.name());
        }
        String emptyNote = emptyNames(emptyDollar, "${}", "按空串拼进去（引号外的会拼成空，库会自己报错）")
                + emptyNames(emptyHash, "#{}", "按空值 NULL 绑定");
        if (!emptyNote.isEmpty()) c.warnings.add("有参数没填，已照样拼出 SQL：" + emptyNote.trim());
        String sql = collapse(out.toString());
        if (sql.isBlank()) c.warnings.add("拼出来的 SQL 为空——多半所有条件段都被排除了");
        // values 里合法地含 null（没填的入参位、以及出参位），List.copyOf 会直接 NPE —— 这里必须用允许 null 的包装
        return new Result(c.drafts(), withClauses(c, dynamicBody), sql,
                java.util.Collections.unmodifiableList(new ArrayList<>(c.values)),
                List.copyOf(c.errors), List.copyOf(c.warnings), List.copyOf(c.outSlots),
                SqlParamClause.testLiterals(dynamicBody));
    }

    /**
     * 第 27 轮：给每个参数挂上"它在跟哪一列比"的条款。第 28 轮起：清单不再只有本次拼进去的那些。
     *
     * 用的是登记参数时留下的那一份原文与位置（与界面上「位于 …」那行出处提示同源），
     * 所以候选值一定挂在用户看得见的同一个条件块上，不会说一套发一套。
     * 卡级事实包（别名表 / 字典子查询 / 枚举字面量 / 只有一张表 / insert 的列清单）整体算一次，供全部参数复用。
     *
     * 顺序＝先真正拼进的（与旧行为逐条一致：位序、绑定值、"没填"提醒都不受影响），
     * 再补上"原文里有、这一段这次没拼进"的（按文档顺序，emitted=false）。同一个名字只要拼进过一次，
     * 就只按拼进的那一处推条款——未拼进的那一处可能比的是另一列，拿它当候选值来路会把值挂错列。
     */
    private static List<Param> withClauses(Ctx c, String dynamicBody) {
        List<Param> out = new ArrayList<>(c.paramList.size() + c.scanList.size());
        if (c.paramList.isEmpty() && c.scanList.isEmpty()) return out;
        SqlParamClause.Card card;
        try {
            card = SqlParamClause.forCard(dynamicBody);
        } catch (RuntimeException e) {
            return c.paramList;      // 事实包都建不起来：整份退回"自己填"，绝不拦下拼装
        }
        for (int i = 0; i < c.paramList.size(); i++) {
            Param p = c.paramList.get(i);
            out.add(new Param(p.name(), p.kind(), p.fragId(), p.context(), p.test(), p.hashSlots(), p.outType(),
                    clauseOf(card, c.sites, i, p.test()), p.emitted()));
        }
        for (int i = 0; i < c.scanList.size(); i++) {
            Param p = c.scanList.get(i);
            if (c.emittedNames.contains(p.name())) continue;   // 拼进过的那一处已经登记过，条款以它为准
            out.add(new Param(p.name(), p.kind(), p.fragId(), p.context(), p.test(), p.hashSlots(), p.outType(),
                    clauseOf(card, c.scanSites, i, p.test()), false));
        }
        return List.copyOf(out);
    }

    /** 条款只是候选值的来路：算失败就把这一个框退回"自己填"，绝不拦下整条拼装 */
    private static SqlParamClause.Clause clauseOf(SqlParamClause.Card card, List<String[]> sites, int i, String test) {
        String[] site = i < sites.size() ? sites.get(i) : null;
        if (site == null) return null;
        try {
            return card.clause(SqlParamClause.chunkOf(site[0], sitePos(site[0], site[1])), site[1], test);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 占位符在原文里可能被写好几遍（同名多处、或同一句既在生效条件里又在注释里出现过）：
     * 条款与界面上「位于 …」那行出处都认**第一处没被 SQL 注释吃掉的**出现——注释里那句不是生效条件，
     * 按它推候选值会把值挂到错的列上（真实卡 16020 的 {@code --and D.lxdh1 like '%${LXDH}%'} 就是这种写法）。
     * 整段都在注释里时退回第一处，宁可指到注释也不指到别的地方，与 context 同一条口径。
     */
    private static int sitePos(String text, String placeholder) {
        int first = text.indexOf(placeholder);
        if (first < 0 || placeholder == null || placeholder.isEmpty()) return Math.max(first, 0);
        String clean = SqlParamClause.stripComments(text);       // 等长：注释段整段换成空格
        for (int at = first; at >= 0; at = text.indexOf(placeholder, at + 1)) {
            if (!clean.substring(at, at + placeholder.length()).isBlank()) return at;
        }
        return first;
    }

    /** "「a」「b」…等 19 个 ${} 按空串拼进去" —— 只列前 8 个，绝不静默吞掉漏填 */
    private static String emptyNames(List<String> names, String kind, String what) {
        if (names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(names.size(), 8);
        for (int i = 0; i < shown; i++) sb.append("「").append(names.get(i)).append("」");
        if (names.size() > shown) sb.append("…等 ").append(names.size()).append(" 个");
        return sb.append(" ").append(kind).append(" ").append(what).append("；").toString();
    }

    // ==================== 读取期还原：让占位符带上名字（第 23 轮） ====================

    /**
     * 把拍平模板里的匿名占位符还原成源码原样：第 k 个 {@code ?} 换回第 k 个 {@code #{...}}，
     * 第 k 处 {@code DOLLAR} 哨兵换回第 k 个 {@code ${...}}（都按文档顺序）。
     *
     * 只做"占位符那一小段"的替换，其余字符逐字节照抄模板，所以数量对不上时宁可不还原并说明原因——
     * 半还原的语句会让人把值填错位置，比看不到名字更坏。
     * 纯读取期派生：不落库、不重学、不改变执行用的语句。
     */
    public static Named restoreNames(String template, String dynamicBody) {
        if (template == null || template.isBlank()) return Named.skip("这张卡没有可展示的 SQL 模板");
        if (dynamicBody == null || dynamicBody.isBlank()) {
            return Named.skip("这张卡没有留下动态 SQL 原文（学它的那版采集器还没有这一项），占位符的名字已经找不回来；"
                    + "到项目页对该仓库点一次【学习】会自动补采——源码没改也会被重扫，因为增量缓存按采集版本记账");
        }
        List<String> hashes = fullMatches(HASH_FULL, dynamicBody);
        List<String> dollars = fullMatches(DOLLAR_FULL, dynamicBody);
        List<Boolean> dollarQuoted = quotedSites(DOLLAR_FULL, dynamicBody);
        List<Integer> qSites = bindSites(template);
        if (qSites.size() != hashes.size()) {
            return Named.skip("模板里有 " + qSites.size() + " 个 ?，原文里有 " + hashes.size()
                    + " 个 #{}，逐位对不上，整条不还原");
        }
        List<Integer> dSites = tokenSites(template);
        if (dSites.size() != dollars.size()) {
            return Named.skip("模板里有 " + dSites.size() + " 处 DOLLAR 占位，原文里有 " + dollars.size()
                    + " 个 ${}，逐位对不上，整条不还原");
        }

        List<Site> sites = new ArrayList<>();
        for (int i = 0; i < qSites.size(); i++) {
            sites.add(new Site(qSites.get(i), qSites.get(i) + 1, hashes.get(i)));
        }
        for (int i = 0; i < dSites.size(); i++) {
            int at = dSites.get(i);
            int start = at, end = at + SENTINEL.length();
            if (!dollarQuoted.get(i)) {
                // 源码里不带引号时，清洗那步替它补了一对引号（否则会产出 ''DOLLAR''）；还原要连补来的引号一起去掉
                if (at < 1 || at + SENTINEL.length() >= template.length()
                        || template.charAt(at - 1) != '\'' || template.charAt(end) != '\'') {
                    return Named.skip("第 " + (i + 1) + " 处 DOLLAR 两侧的引号形态与原文对不上，整条不还原");
                }
                start = at - 1;
                end = end + 1;
            }
            sites.add(new Site(start, end, dollars.get(i)));
        }
        // 位置与替换文本必须成对排序：只排位置会把名字错配到别的占位符上（第 23 轮一次性验证脚本抓出来的）
        sites.sort(Comparator.comparingInt(s -> s.start));
        StringBuilder sb = new StringBuilder(template.length() + 64);
        int at = 0;
        for (Site s : sites) {
            if (s.start < at) return Named.skip("占位符位置互相重叠，整条不还原");
            sb.append(template, at, s.start).append(s.text);
            at = s.end;
        }
        sb.append(template, at, template.length());
        return new Named(sb.toString(), hashes, dollars, null);
    }

    /** 一个待替换的占位符区间（模板里的 [start,end) → 原文里的写法） */
    private record Site(int start, int end, String text) {}

    /** 占位符全文（含 {@code ,mode=OUT,jdbcType=CURSOR} 这类属性），按文档顺序 */
    private static List<String> fullMatches(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 每个 {@code ${}} 在原文里是否本就包在引号中（{@code '${x}'} 或 {@code '%${x}%'}）。
     *  判定与 MybatisMapperParser.replaceDollarLiterals 逐字一致，两边口径必须同步改。 */
    private static List<Boolean> quotedSites(Pattern p, String s) {
        List<Boolean> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) {
            int l = m.start() - 1;
            while (l >= 0 && s.charAt(l) == '%') l--;
            int r = m.end();
            while (r < s.length() && s.charAt(r) == '%') r++;
            out.add(l >= 0 && s.charAt(l) == '\'' && r < s.length() && s.charAt(r) == '\'');
        }
        return out;
    }

    /** 与前端 bindSites 同规则：单引号字面量里的 ? 不算绑定位（Oracle 用 '' 转义单引号）。
     *  两边口径不一致就会出现"界面少一行、值填错位"，所以只在扫描规则上保持一致。 */
    static List<Integer> bindSites(String sql) {
        List<Integer> out = new ArrayList<>();
        boolean inStr = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (inStr) {
                if (ch == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') i++;
                    else inStr = false;
                }
            } else if (ch == '\'') inStr = true;
            else if (ch == '?') out.add(i);
        }
        return out;
    }

    private static List<Integer> tokenSites(String template) {
        List<Integer> out = new ArrayList<>();
        Matcher m = DOLLAR_TOKEN.matcher(template);
        while (m.find()) out.add(m.start());
        return out;
    }

    // ==================== 上下文 ====================

    private static final class Ctx {
        final Map<String, String> in;
        final Map<Integer, Boolean> overrides;
        final Map<Element, Integer> ids = new IdentityHashMap<>();
        final List<Draft> drafts = new ArrayList<>();
        final Map<Integer, Verdict> verdicts = new LinkedHashMap<>();
        final List<Param> paramList = new ArrayList<>();
        /** 与 paramList 逐条对齐：[含占位符的那段原文, 占位符原文]。第 27 轮的条款分析用它，
         *  保证"候选值挂在哪个条件块上"与界面上那行出处提示是同一处。 */
        final List<String[]> sites = new ArrayList<>();
        /** 第 28 轮：与"本次拼没拼进"无关的全片段参数事实（文档顺序），只用来给候选值与出处。
         *  位序与绑定值一律不在这里——那是 {@link #paramList}/{@link #values} 的活儿。 */
        final List<Param> scanList = new ArrayList<>();
        final List<String[]> scanSites = new ArrayList<>();
        final Map<String, Integer> scanIdx = new LinkedHashMap<>();
        /** 真正拼进 SQL 的参数名：同名只要拼进过一次，就以那一处为准，未拼进的那一处不再给事实 */
        final Set<String> emittedNames = new LinkedHashSet<>();
        final List<OutSlot> outSlots = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int nextId = 1;

        Ctx(Map<String, String> in, Map<Integer, Boolean> overrides) {
            this.in = in;
            this.overrides = overrides;
        }

        List<Fragment> drafts() {
            List<Fragment> out = new ArrayList<>();
            for (Draft d : drafts) {
                Verdict v = verdicts.get(d.id());
                out.add(new Fragment(d.id(), d.kind(), d.test(), d.preview(), d.params(), d.tests(),
                        v == null ? false : v.yes(),
                        v == null ? "上层条件没拼进来（或本段位于未展开的 <foreach> 内），这一段本次不参与拼装" : v.reason(),
                        v == null ? "blocked" : v.level()));
            }
            return List.copyOf(out);
        }

        /** ordinal>0：这是一个 {@code #{}} 位，占拼出 SQL 的第 ordinal 个 ?；dollar 位传 0。
         *  同名多次出现时合并到同一条参数（绑定值仍逐位入列），所以 slots 是列表不是单值。
         *  outType 非空 = 这一位是出参（占位符自己声明的），同趟记进 outSlots 供执行层用。 */
        void register(String name, String kind, int fragId, String context, String test, int ordinal, String outType,
                      String site, String placeholder) {
            if (outType != null && ordinal > 0) outSlots.add(new OutSlot(ordinal, name, outType));
            for (int i = 0; i < paramList.size(); i++) {
                Param p = paramList.get(i);
                if (!p.name().equals(name)) continue;
                if (ordinal > 0) {
                    List<Integer> slots = new ArrayList<>(p.hashSlots());
                    slots.add(ordinal);
                    paramList.set(i, new Param(p.name(), p.kind(), p.fragId(), p.context(), p.test(), slots,
                            p.outType() != null ? p.outType() : outType));
                }
                return;
            }
            paramList.add(new Param(name, kind, fragId, context, test,
                    ordinal > 0 ? List.of(ordinal) : List.of(), outType));
            sites.add(new String[]{site, placeholder});
            emittedNames.add(name);
        }

        /** 第 28 轮：与拼装决策无关的一趟登记——只记"这个占位符落在哪一段、跟哪一列比"，
         *  绝不碰 {@code values}／{@code outSlots}／位序：那三样是执行侧的事实，只认真正拼进 SQL 的位。 */
        void scan(String name, String kind, int fragId, String context, String test, String outType,
                  String site, String placeholder) {
            Integer at = scanIdx.get(name);
            if (at != null) return;                       // 同名多处：按第一处出现给事实，与 register 同口径
            scanIdx.put(name, scanList.size());
            scanList.add(new Param(name, kind, fragId, context, test, List.of(), outType));
            scanSites.add(new String[]{site, placeholder});
        }
    }

    private record Draft(int id, String kind, String test, String preview, List<String> params, List<String> tests) {}

    private static void index(Node parent, Ctx c) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                if (CONDITIONAL.contains(e.getTagName())) {
                    int id = c.nextId++;
                    c.ids.put(e, id);
                    String test = testOf(e);
                    c.drafts.add(new Draft(id, e.getTagName(), test, previewOf(e), namesOf(e),
                            "otherwise".equals(e.getTagName()) ? List.of() : testVarsOf(test)));
                }
                index(e, c);
            }
        }
    }

    /** {@code <if>}/{@code <when>} 的判断表达式原文；{@code <otherwise>} 没有 test，给一句人话 */
    private static String testOf(Element e) {
        return "otherwise".equals(e.getTagName()) ? "<otherwise>（前面的 when 都不成立时）" : e.getAttribute("test");
    }

    /**
     * 第 28 轮：第二趟遍历——把**每一段**里的占位符都登记成参数事实，不看这一拼不拼得进来。
     *
     * 为什么要有这一趟：候选值本来就是回答"这一位该填什么"的，可它过去只在拼装时登记参数，
     * 于是"一个都没填"的开局（用户真实的开局）参数清单是空的 ⇒ 条款算不出 ⇒ 候选值一条都没有 ⇒
     * 用户更不知道该填什么。死锁就长在"必须先拼进来才认识这个参数"这一条上。
     *
     * 与渲染那趟的三处刻意不同，都在守住第 14/22/24 轮的执行侧口径：
     * ① 不做条件判定（{@code <if>} 的 test 只作为出处信息记下，不影响这参数出不出现在清单里）；
     * ② 不产生 {@code ?} 位序、不写 {@code values}、不写 {@code outSlots}（所以未拼进的位绝不会发到库里）；
     * ③ 不进 {@code <foreach>}（{@code #{item.x}} 是集合展开位的循环变量，不是向人要的入参）、
     *    不进 {@code <selectKey>}（那是另一条生成主键的语句）。
     */
    private static void scanAll(Node parent, Ctx c, int fragId, String test) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof CDATASection || n.getNodeType() == Node.TEXT_NODE) {
                scanText(n.getNodeValue(), c, fragId, test);
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                String tag = e.getTagName();
                if ("foreach".equals(tag) || "selectKey".equals(tag)) continue;
                if (CONDITIONAL.contains(tag)) scanAll(e, c, id(c, e), testOf(e));
                else scanAll(e, c, fragId, test);
            }
        }
    }

    /** 一段原文里的每个占位符 → 参数事实。与 {@link #appendText} 逐字同构（含"先抹 #{} 再扫 ${}"的次序），
     *  这样同一段文字在两趟里推出的条款与「位于 …」出处一定指向同一处；只是不改文本、不登记位序。 */
    private static void scanText(String text, Ctx c, int fragId, String test) {
        Matcher h = HASH.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (h.find()) {
            String ph = h.group();
            h.appendReplacement(sb, Matcher.quoteReplacement("?"));
            c.scan(simpleName(h.group(1)), "hash", fragId, context(text, sitePos(text, ph)), test,
                    outTypeOf(h.group(1)), text, ph);
        }
        h.appendTail(sb);
        String text2 = sb.toString();

        Matcher d = DOLLAR.matcher(text2);
        while (d.find()) {
            String ph = d.group();
            c.scan(simpleName(d.group(1)), "dollar", fragId, context(text2, sitePos(text2, ph)), test,
                    null, text2, ph);
        }
    }

    // ==================== 渲染 ====================

    private static void renderChildren(Node parent, StringBuilder out, Ctx c, int fragId, String test) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof CDATASection || n.getNodeType() == Node.TEXT_NODE) {
                appendText(n.getNodeValue(), out, c, fragId, test);
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                renderElement((Element) n, out, c, fragId, test);
            }
        }
    }

    private static void renderElement(Element el, StringBuilder out, Ctx c, int fragId, String test) {
        switch (el.getTagName()) {
            case "if" -> {
                Verdict v = decide(id(c, el), el.getAttribute("test"), c);
                if (v.yes()) renderChildren(el, out, c, id(c, el), el.getAttribute("test"));
            }
            case "choose" -> renderChoose(el, out, c);
            case "where" -> {
                String buf = collapse(renderInto(el, c, fragId, test));
                if (!buf.isBlank()) out.append(" WHERE ").append(stripWords(buf, "and", "or"));
            }
            case "set" -> {
                String buf = collapse(renderInto(el, c, fragId, test));
                if (!buf.isBlank()) {
                    if (buf.endsWith(",")) buf = buf.substring(0, buf.length() - 1);
                    out.append(" SET ").append(buf);
                }
            }
            case "trim" -> {
                String buf = collapse(renderInto(el, c, fragId, test));
                if (!buf.isBlank()) {
                    buf = stripOverrides(buf, el.getAttribute("prefixOverrides"), true);
                    buf = stripOverrides(buf, el.getAttribute("suffixOverrides"), false);
                    out.append(' ').append(el.getAttribute("prefix")).append(' ').append(buf)
                            .append(' ').append(el.getAttribute("suffix"));
                }
            }
            case "foreach" -> {
                c.warnings.add("含 <foreach> 集合展开（需要数组入参），未自动拼装，该处留了标记，请人工补全后再执行");
                out.append(" /*foreach 未展开*/ ");
            }
            case "bind" -> c.warnings.add("含 <bind name=\"" + el.getAttribute("name") + "\">，未求值，引用它的条件需人工核对");
            case "selectKey" -> c.warnings.add("含 <selectKey>（主键生成语句），已从直测 SQL 中剔除");
            case "include" -> c.warnings.add("含未内联的 <include refid=\"" + el.getAttribute("refid") + "\">，该片段内容缺失");
            default -> {
                c.warnings.add("遇到未支持的标签 <" + el.getTagName() + ">，已按纯文本取用其内容，直测前请人工核对");
                renderChildren(el, out, c, fragId, test);
            }
        }
    }

    private static void renderChoose(Element choose, StringBuilder out, Ctx c) {
        List<Element> whens = new ArrayList<>();
        Element otherwise = null;
        for (Node n = choose.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                if ("when".equals(e.getTagName())) whens.add(e);
                else if ("otherwise".equals(e.getTagName())) otherwise = e;
            }
        }
        int hit = -1;
        for (int i = 0; i < whens.size(); i++) {
            Element w = whens.get(i);
            Verdict v = decide(id(c, w), w.getAttribute("test"), c);
            if (hit < 0 && v.yes()) hit = i;
        }
        for (int i = 0; i < whens.size(); i++) {
            Element w = whens.get(i);
            int wid = id(c, w);
            boolean include = i == hit;
            if (include) renderChildren(w, out, c, wid, w.getAttribute("test"));
            Verdict auto = c.verdicts.get(wid);
            String reason = include ? "命中：choose 取第一个成立的 when"
                    : hit >= 0 ? "未拼进：choose 已由前面的 when 命中"
                    : auto == null ? "未判定" : auto.reason();
            String level = include ? "auto" : hit >= 0 ? "auto" : (auto == null ? "unknown" : auto.level());
            c.verdicts.put(wid, new Verdict(include, reason, level));
        }
        if (otherwise != null) {
            int oid = id(c, otherwise);
            boolean include = hit < 0;
            if (include) renderChildren(otherwise, out, c, oid, "<otherwise>");
            c.verdicts.put(oid, new Verdict(include,
                    include ? "前面的 when 都没命中，按 MyBatis 语义走 otherwise" : "未拼进：已有 when 命中",
                    "auto"));
        }
    }

    private static int id(Ctx c, Element el) {
        Integer id = c.ids.get(el);
        return id == null ? 0 : id;
    }

    private static String renderInto(Element el, Ctx c, int fragId, String test) {
        StringBuilder sb = new StringBuilder();
        renderChildren(el, sb, c, fragId, test);
        return sb.toString();
    }

    /** 文本里的占位符换成 ? / 字面值，并登记参数（第 24 轮：没填的 ${} 代成空串，不再原样留着让人以为漏替换） */
    private static void appendText(String text, StringBuilder out, Ctx c, int fragId, String test) {
        Matcher h = HASH.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (h.find()) {
            String raw = h.group(1);
            String name = simpleName(raw);
            String outType = outTypeOf(raw);
            String ph = h.group();
            h.appendReplacement(sb, Matcher.quoteReplacement("?"));
            // 出参位仍占一个 ?（保持逐位对齐），但绝不把人填的东西绑上去
            c.values.add(outType == null ? toBindValue(c.in.get(name)) : null);
            c.register(name, "hash", fragId, context(text, sitePos(text, ph)), test, c.values.size(), outType,
                    text, ph);
        }
        h.appendTail(sb);

        Matcher d = DOLLAR.matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        String text2 = sb.toString();
        while (d.find()) {
            String name = simpleName(d.group(1));
            String v = c.in.get(name);
            String ph = d.group();
            d.appendReplacement(sb2, Matcher.quoteReplacement(v == null || v.isBlank() ? "" : v));
            c.register(name, "dollar", fragId, context(text2, sitePos(text2, ph)), test, 0, null,
                    text2, ph);
        }
        d.appendTail(sb2);
        out.append(sb2);
    }

    // ==================== 判断求值（白名单；认不出就不拼） ====================

    private record Verdict(boolean yes, String reason, String level) {}

    private static Verdict decide(int id, String test, Ctx c) {
        String t = test == null ? "" : test.trim();
        Verdict v;
        Boolean forced = c.overrides.get(id);
        if (forced != null) {
            v = new Verdict(forced, forced ? "人工强制包含" : "人工强制排除", "forced");
        } else {
            v = switch (evalTest(t, c.in)) {
                case YES -> new Verdict(true, "条件成立：" + t, "auto");
                case NO -> new Verdict(false, "条件不成立，整段没拼进来：" + t, "auto");
                case UNKNOWN -> new Verdict(false, "判断表达式超出可识别范围，默认不拼（可点【强制包含】）：" + t, "unknown");
            };
        }
        c.verdicts.put(id, v);
        return v;
    }

    /** 三态判定：包内可见，便于用例表直接驱动（EvalCheck） */
    enum Tri { YES, NO, UNKNOWN }

    static Tri evalTest(String expr, Map<String, String> in) {
        // 'X'.toString() 是 MyBatis 里最常见的写法（等价于 'X'），先剥掉再判括号
        String e = TO_STRING.matcher(expr == null ? "" : expr).replaceAll("$1$2$1")
                .replace('\n', ' ').replace('\r', ' ').trim();
        if (e.isEmpty() || e.contains("(") || e.contains(")")) return Tri.UNKNOWN;
        Tri or = Tri.NO;
        for (String orPart : e.split("(?i)\\bor\\b")) {
            Tri and = Tri.YES;
            for (String atom : orPart.split("(?i)\\band\\b")) {
                Tri t = evalAtom(atom, in);
                if (t == Tri.UNKNOWN) { and = Tri.UNKNOWN; break; }
                if (t == Tri.NO) { and = Tri.NO; break; }
            }
            if (and == Tri.UNKNOWN) return Tri.UNKNOWN;
            if (and == Tri.YES) or = Tri.YES;
        }
        return or;
    }

    private static Tri evalAtom(String atom, Map<String, String> in) {
        String a = atom.trim();
        if (a.isEmpty()) return Tri.UNKNOWN;
        Matcher op = CMP.matcher(a);
        if (!op.find()) return truthy(a, in);            // 裸变量：OGNL 真值判定
        String cmp = op.group();
        String left = a.substring(0, op.start()).trim();
        String right = a.substring(op.end()).trim();
        if (op.find()) return Tri.UNKNOWN;               // 一条式子里比两次，不猜
        String l = resolve(left, in), r = resolve(right, in);
        if (l == BAD || r == BAD) return Tri.UNKNOWN;    // 两侧写法超出白名单
        Tri eq = eqOf(l, r);
        return "!=".equals(cmp) ? (eq == Tri.YES ? Tri.NO : Tri.YES) : eq;
    }

    /** {@code <if test="dabh">}：非空即真（留空＝没这个参数，与 valueOf 的口径一致） */
    private static Tri truthy(String a, Map<String, String> in) {
        if (!VAR.matcher(a).matches() || "true".equals(a) || "false".equals(a)) return Tri.UNKNOWN;
        String v = valueOf(a, in);
        return v == null ? Tri.NO : Tri.YES;
    }

    /** 把一侧解析成字符串值；变量取参数值，字面量去引号；认不出返回 BAD */
    private static String resolve(String side, Map<String, String> in) {
        if (side.isEmpty()) return BAD;
        char c0 = side.charAt(0);
        if (c0 == '\'' || c0 == '"') {
            if (side.length() < 2 || side.charAt(side.length() - 1) != c0) return BAD;
            return side.substring(1, side.length() - 1);
        }
        if ("null".equals(side)) return null;
        if (NUMBER.matcher(side).matches()) return side;
        if (VAR.matcher(side).matches() && !"true".equals(side) && !"false".equals(side)) return valueOf(side, in);
        return BAD;
    }

    /** 界面上参数都是字符串，所以数字字面量额外按数值比（{@code sfzdkh == 0} 能命中 "0"/"00"） */
    private static Tri eqOf(String a, String b) {
        if (a == null || b == null) return (a == null && b == null) ? Tri.YES : Tri.NO;
        if (a.equals(b)) return Tri.YES;
        try {
            return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0 ? Tri.YES : Tri.NO;
        } catch (NumberFormatException e) {
            return Tri.NO;
        }
    }

    /** 界面上参数都是字符串：留空＝没有这个参数；`a.b` 这种带前缀的写法按末段命中 */
    private static String valueOf(String name, Map<String, String> in) {
        String v = in.get(name);
        if (v == null) {
            String suffix = "." + name;
            for (Map.Entry<String, String> e : in.entrySet()) {
                if (e.getKey().endsWith(suffix)) { v = e.getValue(); break; }
            }
        }
        if (v == null || v.isBlank()) return null;
        return v.trim();
    }

    // ==================== 工具 ====================

    private static String simpleName(String raw) {
        String s = raw.trim();
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma); // #{x, jdbcType=VARCHAR}
        int dot = s.lastIndexOf('.');
        return (dot >= 0 ? s.substring(dot + 1) : s).trim();
    }

    /** {@code #{RC1,mode=OUT,jdbcType=CURSOR, javaType=java.sql.ResultSet}} 里声明的出参类型；入参返回 null。
     *  这些属性原来被 simpleName 一刀切掉，到达执行层就只剩一个普通 ?——达梦在游标位上 setObject(null) 就抛
     *  {@code paramDesc.cursorStmt is null}。属性值可能换行写，所以整段按空白折叠后再找。 */
    private static String outTypeOf(String raw) {
        String s = raw.replaceAll("\\s+", " ");
        String mode = attr(s, "mode");
        if (mode == null || mode.equalsIgnoreCase("IN")) return null;
        String jdbc = attr(s, "jdbcType");
        if (jdbc != null) return jdbc.toUpperCase();
        String java = attr(s, "javaType");
        if (java != null) return java.contains("ResultSet") ? "CURSOR" : null;
        return "CURSOR"; // MyBatis 里存储过程游标 OUT 的简写形态
    }

    private static String attr(String s, String key) {
        Matcher m = Pattern.compile("(?i)\\b" + key + "\\s*=\\s*([^,]+)").matcher(s);
        if (!m.find()) return null;
        String v = m.group(1).trim();
        return v.isEmpty() ? null : v;
    }

    private static Object toBindValue(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        if (t.matches("-?\\d{1,9}")) return Integer.parseInt(t);
        if (t.matches("-?\\d{10,18}")) {
            try { return Long.parseLong(t); } catch (NumberFormatException e) { return t; }
        }
        return t;
    }

    private static String context(String text, int pos) {
        int from = Math.max(0, pos - 26);
        String left = text.substring(from, pos).replaceAll("\\s+", " ").trim();
        String right = text.substring(pos, Math.min(text.length(), pos + 26)).replaceAll("\\s+", " ").trim();
        return (left.isEmpty() ? "" : "…" + left) + " ▮ " + right;
    }

    private static List<String> namesOf(Node el) {
        Set<String> names = new LinkedHashSet<>();
        collectNames(el, names);
        return List.copyOf(names);
    }

    /** 判断表达式里引用的变量名。只为"给人填"而列，不参与求值（求值另有白名单），所以宁可多列不可漏列 */
    static List<String> testVarsOf(String test) {
        if (test == null || test.isBlank()) return List.of();
        // 'X'.toString() 先还原成 'X'，再抹字面量与占位符，剩下的标识符才是变量名
        String bare = PLACEHOLDER_IN_TEST.matcher(QUOTED.matcher(TO_STRING.matcher(test).replaceAll("$1$2$1")).replaceAll(" ")).replaceAll(" ");
        LinkedHashSet<String> vars = new LinkedHashSet<>();
        Matcher m = VAR.matcher(bare);
        while (m.find()) {
            String v = m.group();
            if (!TEST_WORDS.contains(v.toLowerCase())) vars.add(v);
        }
        return List.copyOf(vars);
    }

    private static void collectNames(Node n, Set<String> names) {
        int type = n.getNodeType();
        if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
            String t = n.getNodeValue();
            Matcher h = HASH.matcher(t);
            while (h.find()) names.add("#{" + simpleName(h.group(1)) + "}");
            Matcher d = DOLLAR.matcher(t);
            while (d.find()) names.add("${" + simpleName(d.group(1)) + "}");
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) collectNames(c, names);
    }

    /** 片段预览：原样文字（占位符保留，一眼看出这段要哪些值） */
    private static String previewOf(Node el) {
        StringBuilder sb = new StringBuilder();
        rawText(el, sb);
        String s = collapse(sb.toString());
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static void rawText(Node n, StringBuilder sb) {
        int type = n.getNodeType();
        if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) sb.append(n.getNodeValue());
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) rawText(c, sb);
    }

    private static String collapse(String s) {
        return s.replaceAll("[ \\t\\r]+", " ").replaceAll(" *\\n *", " ").trim();
    }

    private static String stripWords(String buf, String... words) {
        String s = buf.trim();
        for (String w : words) {
            if (s.regionMatches(true, 0, w, 0, w.length()) && s.length() > w.length()
                    && !Character.isLetterOrDigit(s.charAt(w.length()))) {
                return s.substring(w.length()).trim();
            }
        }
        return s;
    }

    private static String stripOverrides(String buf, String overrides, boolean leading) {
        if (overrides == null || overrides.isBlank()) return buf;
        String s = buf.trim();
        for (String o : overrides.split("\\|")) {
            String t = o.trim();
            if (t.isEmpty()) continue;
            if (leading && s.regionMatches(true, 0, t, 0, t.length())) s = s.substring(t.length()).trim();
            if (!leading && s.length() >= t.length()
                    && s.regionMatches(true, s.length() - t.length(), t, 0, t.length())) {
                s = s.substring(0, s.length() - t.length()).trim();
            }
        }
        return s;
    }

    private static Element parseRoot(String dynamicBody) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = dbf.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document doc = builder.parse(new InputSource(new StringReader("<dyn>" + dynamicBody + "</dyn>")));
        return doc.getDocumentElement();
    }
}
