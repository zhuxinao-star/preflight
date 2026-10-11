package com.preflight.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从动态 SQL 原文里读出「这个参数是在跟哪一列比、怎么比、能不能多值、这一列的取值域是什么」。
 *
 * 存在的理由：③ 面板要给用户候选值，而参数→列的对应关系从来没被存过（{@code db_objects} 只有表名清单，
 * {@code table_meta} 只有列名类型）。这份信息只在这条语句的文本里，所以读取期现算、不落库
 * （与 {@link #restoreNames} 同一条铁律：派生视图不落库，schema_hash 不变，历史用例不会转待复核），
 * 算不出来就返回 null 让界面说"不知道"，绝不猜列名。
 *
 * 四类事实，全部来自这张卡自己的源码文本：
 * 1) 别名 → 表（{@code JOIN KDETL.X c}）
 * 2) 数据字典（{@code (SELECT qzsm FROM t_sjzd WHERE zdmc='ZS专业投资者类型16693' AND zdqz=jjmy.PROF_TYPE)}）
 *    —— 形状是通用的：一条 SELECT 单列、WHERE 里一个"列=字面量"加一个"列=外层列"，不写死 t_sjzd
 * 3) 枚举字面量（{@code decode(a.clzt,'0','未完成','2','已完成',默认)}、{@code case when x='1' then '一'}、
 *    以及本句里出现过的 {@code 列='字面量'}）
 * 4) 判断表达式里的字面量（{@code <if test="sfdqxcpgl == &quot;1&quot;">}）——只用于"这个参数只参与判定"的情形
 */
public final class SqlParamClause {

    /** 字典：值列/标签列在字典表里，键列=某个固定值；outerColumn 是这条语句里被翻译的那一列（原样，带别名） */
    public record DictRef(String table, String keyColumn, String keyValue,
                          String valueColumn, String labelColumn, String outerColumn) {}

    public record Labeled(String value, String label) {}

    /**
     * column/operator/multi 任一项算不出来就是 null——界面按"未知"处理，不拿猜测去填库。
     * table 为 null 时 tableNote 说明为什么（别名没在 FROM 里出现等）。
     * delimiter 只在 multi=true 时非空：那是**源码里本来就写着**的分隔符（{@code fn_split('${x}',',')}），
     * {@code in (${x})} 这种没有函数包的按 "," 拼（因为值就是原样拼进括号）。
     * basis 说清这些候选值的来路（界面原样透出，用户才知道该不该信）。
     */
    public record Clause(String column, String table, String tableNote, String operator,
                         Boolean multi, String delimiter, DictRef dict, List<Labeled> codeValues,
                         String basis, String raw) {}

    /** {@code update} 也算表名/别名的出处：{@code update T w set …} 里 w 就是 T，不认这一形整条 update 都定不到表 */
    private static final Pattern CDATA = Pattern.compile("(?s)<!\\[CDATA\\[(.*?)\\]\\]>");
    private static final Pattern ALIAS = Pattern.compile(
            "(?i)\\b(?:from|join|update)\\s+([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)(?:\\s+(?:as\\s+)?([A-Za-z_][\\w$]*))?");
    /** 逗号表清单里的一张：只在上一张刚认出来时接着用 {@code lookingAt} 逐张续认 */
    private static final Pattern COMMA_TABLE = Pattern.compile(
            "(?i)\\s*,\\s*([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)(?:\\s+(?:as\\s+)?([A-Za-z_][\\w$]*))?");
    /** 别名表里不该出现的词：它们不是别名，是下一个子句的开头 */
    private static final Set<String> NOT_ALIAS = Set.of("where", "on", "group", "order", "having", "set",
            "values", "select", "from", "join", "inner", "left", "right", "full", "cross", "union", "and", "or",
            "as", "using", "start", "with", "connect", "minus", "intersect", "dual");
    private static final Pattern DICT_SUB = Pattern.compile(
            "(?is)\\(\\s*select\\s+([A-Za-z_][\\w$]*)\\s+from\\s+([A-Za-z_][\\w$.]*)\\s+where\\s+([^()]*?)\\s*\\)");
    private static final Pattern COND_EQ = Pattern.compile(
            "(?i)^\\s*([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)\\s*=\\s*(?:'([^']*)'|([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?))\\s*$");
    private static final Pattern DECODE = Pattern.compile("(?i)\\bdecode\\s*\\(");
    private static final Pattern CASE_WHEN = Pattern.compile(
            "(?i)\\bwhen\\s+([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)\\s*=\\s*'([^']*)'\\s+then\\s+'([^']*)'");
    private static final Pattern COL_LITERAL = Pattern.compile(
            "(?i)\\b([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)\\s*=\\s*'([^']{1,60})'");
    private static final Pattern TEST_ATTR = Pattern.compile("(?i)test\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TEST_LITERAL = Pattern.compile(
            "([A-Za-z_]\\w*)\\s*(?:==|!=)\\s*(?:'([^']*)'|\"([^\"]*)\")");
    /** 一个标识符，允许带 {@code 别名.列} / {@code 库.表.列} 的点号尾巴——必须整体匹配，否则只剩别名 */
    private static final Pattern COLUMN_TOK = Pattern.compile("[A-Za-z_][\\w$]*(?:\\.\\s*[A-Za-z_][\\w$]*)*");
    private static final Pattern OP = Pattern.compile(
            "(?i)(>=|<=|<>|!=|\\bnot\\s+in\\b|\\bin\\b|\\blike\\b|\\bbetween\\b|=|>|<)");
    private static final Pattern SPLIT_FN = Pattern.compile("(?i)\\b([a-z_]*split[a-z_]*)\\s*\\(([^()]*)\\)");
    private static final Pattern BETWEEN = Pattern.compile("(?i)\\bbetween\\b");
    /** 判断表达式里的字面量先抹掉引号内容才切 and/or，避免 {@code and 'a' = 'b'} 误判 */
    private static final Set<String> SQL_NOISE = Set.of("select", "from", "where", "and", "or", "null", "is",
            "in", "like", "not", "nvl", "to_char", "to_number", "substr", "count", "sum", "max", "min",
            "trim", "case", "when", "then", "else", "end", "as", "on", "decode", "exists", "distinct",
            "between", "update", "insert", "into", "values", "set", "start", "with", "connect", "prior");

    private SqlParamClause() {}

    // ==================== 1) 别名 → 表 ====================

    public static Map<String, String> aliases(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null) return out;
        for (String[] pair : tablePairs(collapseCdata(stripTags(body)))) remember(out, pair[0], pair[1]);
        return out;
    }

    /** 语句里出现过的全部表名（含 {@code from a, b} 逗号清单），保序。 */
    static List<String> tableNames(String bare) {
        List<String> out = new ArrayList<>();
        for (String[] pair : tablePairs(bare)) out.add(pair[0]);
        return out;
    }

    /**
     * 扫出这条语句的 {@code (表, 别名)} 对：{@code from}／{@code join}／{@code update} 起头认一对，
     * 后面紧跟的逗号清单（{@code from t_a a, t_b b}）续着认——真实项目里这种老式多表写法不少，
     * 只认第一张会让其余别名全部定不到表。
     */
    private static List<String[]> tablePairs(String bare) {
        List<String[]> out = new ArrayList<>();
        Matcher m = ALIAS.matcher(bare);
        Matcher c = COMMA_TABLE.matcher(bare);
        while (m.find()) {
            out.add(new String[]{m.group(1), m.group(2)});
            int pos = m.end();
            while (pos < bare.length() && c.region(pos, bare.length()).lookingAt()) {
                out.add(new String[]{c.group(1), c.group(2)});
                pos = c.end();
            }
        }
        return out;
    }

    /** 记住 {@code 别名 → 表}，同时把表名自己（含去掉 schema 前缀的尾巴）也记上 */
    private static void remember(Map<String, String> out, String table, String alias) {
        out.putIfAbsent(norm(table), table);
        int dot = table.lastIndexOf('.');
        if (dot > 0) out.putIfAbsent(norm(table.substring(dot + 1)), table);
        // 别名撞上子句开头词（where/on/set…）时不收，但限定写法 表名.列 已经记上了
        if (alias == null || NOT_ALIAS.contains(alias.toLowerCase())) return;
        out.putIfAbsent(norm(alias), table);
    }

    // ==================== 2) 数据字典 ====================

    /** key = 被翻译那一列的归一化名（{@code 别名.列} 与裸 {@code 列} 各挂一条，裸列歧义时不挂） */
    public static Map<String, DictRef> dicts(String body) {
        Map<String, DictRef> out = new LinkedHashMap<>();
        if (body == null) return out;
        String bare = collapseCdata(stripTags(body));
        Matcher m = DICT_SUB.matcher(bare);
        while (m.find()) {
            String labelCol = m.group(1);
            String dictTable = m.group(2);
            String where = m.group(3);
            String keyCol = null, keyValue = null, valCol = null, outerCol = null;
            for (String part : where.split("(?i)\\s+and\\s+")) {
                Matcher c = COND_EQ.matcher(part.trim());
                if (!c.matches()) continue;
                String left = c.group(1);
                if (c.group(2) != null) {
                    if (keyCol == null) { keyCol = left; keyValue = c.group(2); }
                } else if (c.group(3) != null) {
                    if (valCol == null) { valCol = left; outerCol = c.group(3); }
                }
            }
            if (keyCol == null || valCol == null || outerCol == null) continue;   // 形状不合，不猜
            putDict(out, outerCol, new DictRef(dictTable, keyCol, keyValue, valCol, labelCol, outerCol));
        }
        return out;
    }

    // ==================== 3)+4) 代码字面量 ====================

    /** 一张卡里「列 → 源码中出现过的取值」索引。
     *  整名（{@code A.CLZT}）一定挂；裸名（{@code CLZT}）只在**没有被两个不同整名争用**时才挂
     *  （多表同名列时按裸名兜底就是把值挂到别的表上，所以判定为歧义的裸名两边都不给）。 */
    public static final class ValueIndex {
        private final Map<String, List<Labeled>> map = new LinkedHashMap<>();
        private final Set<String> shadow = new LinkedHashSet<>();

        void add(String col, Labeled v) {
            String full = norm(col);
            if (full.isEmpty()) return;
            List<Labeled> list = map.computeIfAbsent(full, k -> new ArrayList<>());
            if (!list.contains(v)) list.add(v);
            int dot = full.lastIndexOf('.');
            if (dot <= 0 || shadow.contains(full)) return;
            String tail = full.substring(dot + 1);
            if (shadow.contains(tail)) return;
            List<Labeled> held = map.get(tail);
            if (held == null) map.put(tail, list);      // 同一个列表对象：整名那条后续追加的值这里也看得见
            else if (held != list) { map.remove(tail); shadow.add(tail); }
        }

        /** 整名优先、裸名兜底；裸名判定过歧义则不兜底 */
        public List<Labeled> lookup(String col) {
            String full = norm(col);
            if (full.isEmpty()) return null;
            List<Labeled> v = map.get(full);
            if (v != null) return v;
            int dot = full.lastIndexOf('.');
            if (dot <= 0) return null;
            String tail = full.substring(dot + 1);
            return shadow.contains(tail) ? null : map.get(tail);
        }
    }

    public static ValueIndex codeValues(String body) {
        ValueIndex out = new ValueIndex();
        if (body == null) return out;
        String bare = collapseCdata(stripTags(body));

        Matcher dec = DECODE.matcher(bare);
        while (dec.find()) {
            int close = matching(bare, dec.end() - 1);
            if (close < 0) continue;
            List<String> args = splitArgs(bare.substring(dec.end(), close));
            if (args.size() < 3) continue;
            String col = args.get(0);
            int pairs = (args.size() - 1) / 2;                    // 末尾可能是默认值，落单的那个参数不是标签
            for (int i = 0; i < pairs; i++) {
                String v = unquote(args.get(1 + i * 2));
                if (v == null) continue;
                out.add(col, new Labeled(v, unquote(args.get(2 + i * 2))));
            }
        }
        Matcher cw = CASE_WHEN.matcher(bare);
        while (cw.find()) out.add(cw.group(1), new Labeled(cw.group(2), cw.group(3)));

        Matcher cl = COL_LITERAL.matcher(bare);
        while (cl.find()) {
            String lit = cl.group(2);
            if (lit.contains("${") || lit.contains("#{")) continue;   // 占位符站点不是取值域
            out.add(cl.group(1), new Labeled(lit, null));
        }
        return out;
    }

    /** 只参与条件判断的参数（{@code <if test="x == '1'">}）：候选值就是判定表达式里那几个字面量。key=参数名 */
    public static Map<String, List<Labeled>> testLiterals(String dynamicBody) {
        Map<String, List<Labeled>> out = new LinkedHashMap<>();
        if (dynamicBody == null) return out;
        Matcher t = TEST_ATTR.matcher(dynamicBody);
        while (t.find()) {
            String expr = t.group(1).replace("&quot;", "\"").replace("&#39;", "'");
            Matcher m = TEST_LITERAL.matcher(expr);
            while (m.find()) {
                String lit = m.group(2) != null ? m.group(2) : m.group(3);
                if (lit == null || lit.isEmpty()) continue;
                String key = norm(m.group(1));
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(new Labeled(lit, null));
            }
        }
        dedupe(out);
        return out;
    }

    // ==================== 5) 语句只有一张表 / INSERT 的值位次 ====================

    private static final Pattern WRITE_TABLE = Pattern.compile(
            "(?i)\\b(?:insert\\s+into|update)\\s+([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)");
    private static final Pattern INSERT_HEAD = Pattern.compile(
            "(?i)\\binsert\\s+into\\s+([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)\\s*\\(");

    /** 整条语句只涉及一张表时返回它，否则 null——多表时裸列名归到哪张表都算猜。 */
    public static String soleTable(String body) {
        if (body == null) return null;
        String bare = collapseCdata(stripTags(body));
        Set<String> ts = new LinkedHashSet<>();
        for (String t : tableNames(bare)) ts.add(norm(t));
        Matcher w = WRITE_TABLE.matcher(bare);
        while (w.find()) ts.add(norm(w.group(1)));
        ts.remove("DUAL");
        return ts.size() == 1 ? ts.iterator().next() : null;
    }

    /** 动态原文里 {@code insert into T (列…) values (值…)} 的「占位符原文 → 表 + 列」（键见 {@link #phKey}）。
     *
     *  值位与列名逐位对齐是 SQL 自己的规定，与有没有 {@code ?} 无关，所以直接在原文上算：
     *  {@code insert … values ('${wjbh}', …)} 这种整句拼进去的写法（真实卡的写入语句一大半是这样）也认得出来。
     *  四种情形一律不映射——此时"第 N 位"这个事实已经不成立：
     *  ① 列数 ≠ 值数（含 {@code <if>} 段只写了列没写值）；② 一个值位里含 0 个或多个占位符；
     *  ③ 列名写法认不出（带函数、带注释）；④ 同一个占位符落在两个不同列上（{@code cjyg}/{@code xgyg} 都填操作員号）。 */
    public static Map<String, String[]> insertSlots(String body) {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (body == null) return out;
        String bare = collapseCdata(stripTags(body));
        Set<String> dup = new LinkedHashSet<>();
        Matcher m = INSERT_HEAD.matcher(bare);
        while (m.find()) {
            String table = m.group(1);
            int open = m.end() - 1, close = matching(bare, open);
            if (close < 0) continue;
            List<String> cols = splitArgs(bare.substring(open + 1, close));
            List<String> vals = valueArgs(bare, close + 1);
            if (vals == null || vals.size() != cols.size()) continue;
            for (int i = 0; i < vals.size(); i++) {
                List<String> phs = placeholders(vals.get(i));
                if (phs.size() != 1) continue;
                String col = cols.get(i).trim();
                if (!COLUMN_TOK.matcher(col).matches()) continue;
                String key = phKey(phs.get(0));
                if (dup.contains(key)) continue;
                String[] held = out.get(key);
                if (held != null && !norm(held[1]).equals(norm(col))) { out.remove(key); dup.add(key); }
                else out.putIfAbsent(key, new String[]{table, col});
            }
        }
        return out;
    }

    /** 列清单右括号之后的那一串值：{@code values (…)} 或 {@code select … [from …]}，按顶层逗号切开；认不出形状返回 null。
     *  {@code insert into T (…) select} 与 values 一样是逐位对齐的（SQL 规定），真实卡里 {@code … select seq.nextval, #{X} from dual}
     *  就是这个写法，所以两形都认。select 那一支读到顶层 from 为止，别把后面的表名当值。 */
    private static List<String> valueArgs(String bare, int from) {
        int at = from;
        while (at < bare.length() && Character.isWhitespace(bare.charAt(at))) at++;
        Matcher head = Pattern.compile("(?i)^(values|select)\\b").matcher(bare.substring(at));
        if (!head.find()) return null;
        boolean vals = head.group(1).equalsIgnoreCase("values");
        at += head.end();
        if (!vals) {                                       // select：截到顶层 from
            Matcher f = Pattern.compile("(?i)\\bfrom\\b").matcher(bare);
            int end = bare.length(), pos = at;
            while (f.find(pos)) {
                pos = f.end();
                if (inQuote(bare, f.start()) || parenDepth(bare, f.start()) != 0) continue;
                end = f.start();
                break;
            }
            return splitArgs(bare.substring(at, end));
        }
        while (at < bare.length() && Character.isWhitespace(bare.charAt(at))) at++;
        if (at >= bare.length() || bare.charAt(at) != '(') return null;
        int close = matching(bare, at);
        return close < 0 ? null : splitArgs(bare.substring(at + 1, close));
    }

    /** 一段文本里出现的占位符原文（{@code ${x}} / {@code #{x, jdbcType=…}}），按出现顺序 */
    private static List<String> placeholders(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[#$]\\{[^{}]*}").matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 占位符原文当键：空白与大小写都不算差异（{@code #{x ,jdbcType=NUMERIC}} 与 {@code #{ X , jdbcType=numeric}} 同一个站点） */
    private static String phKey(String placeholder) {
        return placeholder.replaceAll("\\s+", "").toUpperCase();
    }

    // ==================== 卡级事实包（一次算好，供全部参数复用） ====================

    /** 一张卡的所有派生事实。全部由 {@code dynamic_body} 原文现算，不落库。 */
    public static final class Card {
        final Map<String, String> alias;
        final Map<String, DictRef> dict;
        final ValueIndex values;
        final Map<String, List<Labeled>> testLits;
        final String soleTable;
        final Map<String, String[]> ins;

        Card(Map<String, String> alias, Map<String, DictRef> dict, ValueIndex values,
             Map<String, List<Labeled>> testLits, String soleTable, Map<String, String[]> ins) {
            this.alias = alias;
            this.dict = dict;
            this.values = values;
            this.testLits = testLits;
            this.soleTable = soleTable;
            this.ins = ins;
        }

        /** 界面上「只参与条件判断」那一档的候选值（{@code <if test="x == '1'">} 里的字面量） */
        public List<Labeled> testValues(String name) {
            List<Labeled> v = name == null ? null : testLits.get(norm(name));
            return v == null ? List.of() : List.copyOf(v);
        }

        /** 参数所在条件块 → 条款。条件块里认不出列时，再看这个占位符是不是正落在 insert 的某个值位上。
         *  补的是**列名与表名**这一件事：条件块自己算出来的候选值、运算符、多值分隔符一概保留，
         *  否则"列名认出来了、原本能给的判定字面量反而没了"，等于修一处坏一处。 */
        public Clause clause(String site, String placeholder, String test) {
            Clause c = analyze(site, placeholder, alias, dict, values,
                    testValues(placeholder == null ? null : paramOf(placeholder)), test, soleTable);
            if (c != null && c.column() != null) return c;
            String[] vc = placeholder == null ? null : ins.get(phKey(placeholder));
            if (vc == null) return c;                    // 既不在条件块上、也不在 insert 的列清单上：不猜
            String column = vc[1];
            List<Labeled> codes = new ArrayList<>();
            if (c != null) codes.addAll(c.codeValues());
            if (codes.isEmpty()) {
                List<Labeled> byCol = values.lookup(column);
                if (byCol != null) codes.addAll(byCol);
            }
            DictRef dictRef = c != null && c.dict() != null ? c.dict() : lookupDict(dict, column);
            if (codes.isEmpty() && dictRef != null) {
                codes.add(new Labeled(dictRef.keyValue(), "字典键（取值见数据字典 " + dictRef.table() + "）"));
            }
            String from = "列名取自 " + vc[0] + " 的 insert 列清单——这个占位符正写在该列的值位上";
            return new Clause(column, vc[0], null, c == null ? null : c.operator(),
                    c == null ? Boolean.FALSE : c.multi(), c == null ? null : c.delimiter(),
                    dictRef, List.copyOf(codes),
                    codes.isEmpty() || c == null ? from : from + "；候选值来路：" + c.basis(),
                    c == null ? "insert into " + vc[0] + " (…" + column + "…) values (…)" : c.raw());
        }
    }

    /** {@code ${x}} / {@code #{x, jdbcType=…}} → 参数名（与 SqlDynamicAssembler.simpleName 同口径：取末段） */
    private static String paramOf(String placeholder) {
        String s = placeholder.replaceAll("^[#$]\\{\\s*", "").replaceAll("\\s*}$", "");
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** 一张卡的派生事实：只用 {@code dynamic_body} 原文，与本次填了什么值无关（所以换参数不用重算事实包）。 */
    public static Card forCard(String dynamicBody) {
        return new Card(aliases(dynamicBody), dicts(dynamicBody), codeValues(dynamicBody),
                testLiterals(dynamicBody), soleTable(dynamicBody), insertSlots(dynamicBody));
    }

    // ==================== 单个参数的条款分析 ====================

    /**
     * @param site        含占位符的那一段（{@link #chunkOf} 切出来的一个条件块）
     * @param placeholder 占位符原文（{@code ${x}} 或 {@code #{x, jdbcType=VARCHAR}}），用来在 site 里定位
     * @param testOnly    该参数在 {@code <if test>} 里出现过的字面量（"只参与判定"那一档的候选值）
     * @param soleTable   整条语句只有一张表时的那张表（裸列名才敢归它）
     */
    public static Clause analyze(String site, String placeholder, Map<String, String> aliasMap,
                                 Map<String, DictRef> dictMap, ValueIndex valueMap,
                                 List<Labeled> testOnly, String test, String soleTable) {
        if (site == null || placeholder == null) return null;
        int at = site.indexOf(placeholder);
        if (at < 0) return null;

        String op = null;
        String left = null, right = null;
        int lops = -1, lope = -1, rops = -1, rope = -1;
        Matcher o = OP.matcher(site);
        while (o.find()) {
            if (inQuote(site, o.start())) continue;
            if (o.end() <= at) {                       // 占位符左侧：留最靠近它的那一个（= 真正管着它的那个）
                lops = o.start(); lope = o.end(); op = canon(o.group(1));
            } else if (o.start() >= at + placeholder.length()) {
                if (rops < 0) { rops = o.start(); rope = o.end(); }
                break;
            } else break;                              // 运算符与占位符重叠，写法看不懂
        }
        if (lops >= 0) { left = site.substring(0, lops); right = site.substring(lope); }
        else if (rops >= 0) { left = site.substring(0, rops); right = site.substring(rope); op = canon(OP.matcher(site.substring(rops, rope)).group(1)); }

        String column = onlyColumn(left);
        Boolean multi = null;
        String delimiter = null;
        Matcher sp = SPLIT_FN.matcher(site);
        while (sp.find()) {
            if (!sp.group(2).contains(placeholder)) continue;
            List<String> args = splitArgs(sp.group(2));
            multi = true;
            if (args.size() >= 2) delimiter = unquote(args.get(1));
            break;
        }
        if (multi == null) {
            if ("in".equals(op) || "not in".equals(op)) { multi = true; delimiter = ","; }
            else if (op != null) multi = false;
        }

        String table = null, tableNote = null;
        if (column != null) {
            int dot = column.lastIndexOf('.');
            if (dot > 0) {
                table = aliasMap == null ? null : aliasMap.get(norm(column.substring(0, dot)));
                if (table == null) tableNote = "别名「" + column.substring(0, dot) + "」没在这条语句的 FROM/JOIN 里出现，表名定不下来";
            } else {
                table = aliasMap == null ? null : aliasMap.get(norm(column));
                // 裸列名只有"整条语句就一张表"时才敢归它，多表时归谁都算猜
                if (table == null) table = soleTable;
                if (table == null) tableNote = "这一列没写别名，" + (soleTable == null
                        ? "而这条语句又不止一张表，定不下来它属于哪张表，先不查库"
                        : "按列名在 FROM 里也没找到对应的表，先不查库");
            }
        }
        DictRef dict = dictMap == null ? null : lookupDict(dictMap, column);
        List<Labeled> codes = new ArrayList<>();
        String basis;
        if (column != null && valueMap != null) {
            List<Labeled> byCol = valueMap.lookup(column);
            if (byCol != null) codes.addAll(byCol);
        }
        if (!codes.isEmpty()) {
            basis = "从这条语句自己的源码里读出来的枚举写法（decode / case / " + (column == null ? "本句" : column) + "='…'）";
        } else if (dict != null) {
            codes.add(new Labeled(dict.keyValue(), "字典键（取值见数据字典 " + dict.table() + "）"));
            basis = "这一列被本句的数据字典子查询翻译（" + dict.table() + "）";
        } else if (testOnly != null && !testOnly.isEmpty()) {
            codes.addAll(testOnly);
            basis = "这个参数只出现在条件判断里（" + (test == null ? "" : test) + "），候选值是判断表达式中出现的字面量";
        } else {
            basis = "源码里没找到这一列的取值范围，只能自己填";
        }
        return new Clause(column, table, tableNote, op, multi, delimiter, dict, List.copyOf(codes), basis,
                site.trim());
    }

    /** 占位符所在位置 → 它所在的那一段：以引号外的 and / or / where 与**括号外的逗号**为界。
     *  逗号必须按括号深度判：{@code set a=1, b=${x}} 里那个逗号是分句界，
     *  {@code values (?, ?, ${x})} / {@code in (a, b)} 里的逗号不是（深度 &gt; 0）。 */
    public static String chunkOf(String text, int pos) {
        // 先等长摘掉注释：注释里的 and／where 不是分句界，注释里的条件也不是生效条件，
        // 留着只会把候选值挂到错的列上（真实卡 15928 的 --AND a.qhours >= '08:45' 就把 08:45 挂给了日期列 a.days）。
        // 摘完长度不变，下面的下标与「位于 …」那行出处照常成立。
        text = text == null ? "" : stripComments(text);
        if (text.isEmpty()) return "";
        int at = Math.min(Math.max(pos, 0), text.length() - 1);
        int[] cuts = cuts(text);
        int from = 0, to = text.length();
        for (int i = 0; i < cuts.length; i += 2) {
            if (cuts[i + 1] <= at && cuts[i + 1] > from) from = cuts[i + 1];
        }
        for (int i = 0; i < cuts.length; i += 2) {
            if (cuts[i] >= at && cuts[i] < to) { to = cuts[i]; break; }
        }
        while (from < to && (Character.isWhitespace(text.charAt(from)) || text.charAt(from) == ',')) from++;
        String chunk = text.substring(from, to).trim();
        // 切完不含占位符（极端写法）时退回整段，宁可给宽一点也不给错块
        return chunk.isEmpty() ? text.trim() : chunk;
    }

    /** 该文本里所有"分句界"的 [起, 止)：引号外的 and / or / where / set，加上括号深度为 0 的逗号 */
    private static int[] cuts(String text) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?i)\\b(and|or|where|set|between)\\b|,").matcher(text);
        int since = 0;                                  // 上一个分句界的末尾：从这里起算"这一段里出现过 between 吗"
        while (m.find()) {
            if (inQuote(text, m.start())) continue;
            String kw = m.group(1) == null ? "," : m.group(1).toLowerCase();
            if (kw.equals("between")) continue;         // between 自己不是界，只是给后面那个 and 作的记号
            if (kw.equals(",")) {
                if (parenDepth(text, m.start()) != 0) continue;   // 括号里的逗号是列表分隔，不是分句界
            } else if (kw.equals("and") && BETWEEN.matcher(text.substring(since, m.start())).find()) {
                since = m.end();                        // 这个 and 是 between 区间的另一半：不切开，但记号用掉了
                continue;
            }
            // and / or / where / set 一律算界（子查询内部的也算）：管着占位符的那一句就是它左右最近的这两个界之间的一段
            // set 认作界：update 表 别名 set 列 = x 里 "set" 左边那一段不属于这个条件
            out.add(m.start());
            out.add(m.end());
            since = m.end();
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 该位置处于第几层括号里（引号内的括号不计） */
    private static int parenDepth(String text, int pos) {
        int depth = 0;
        for (int i = 0; i < pos; i++) {
            if (inQuote(text, i)) continue;
            char ch = text.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth = Math.max(0, depth - 1);
        }
        return depth;
    }


    // ==================== 工具 ====================

    private static String canon(String op) {
        return op.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static void dedupe(Map<String, List<Labeled>> map) {
        for (List<Labeled> list : map.values()) {
            List<Labeled> keep = new ArrayList<>();
            for (Labeled v : list) if (!keep.contains(v)) keep.add(v);
            list.clear();
            list.addAll(keep);
        }
    }

    /** 字典按"整名优先、裸名兜底"挂；裸名两处不同整名时两处都不挂（歧义就不给候选） */
    private static void putDict(Map<String, DictRef> map, String col, DictRef ref) {
        String full = norm(col);
        if (full.isEmpty()) return;
        map.putIfAbsent(full, ref);
        int dot = full.lastIndexOf('.');
        if (dot <= 0) return;
        String tail = full.substring(dot + 1);
        DictRef held = map.get(tail);
        if (held == null) map.put(tail, ref);
        else if (held != ref) map.remove(tail);
    }

    /** 整名优先、裸名兜底：同一条语句里的裸列名几乎必然是同一列；裸名判定过歧义（shadow）则不兜底 */
    private static DictRef lookupDict(Map<String, DictRef> map, String col) {
        String full = norm(col);
        if (full.isEmpty()) return null;
        DictRef v = map.get(full);
        if (v != null) return v;
        int dot = full.lastIndexOf('.');
        return dot > 0 ? map.get(full.substring(dot + 1)) : null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s*\\.\\s*", ".").toUpperCase();
    }

    /**
     * 去掉 XML 标签只留 SQL 文本；标签换成空格，免得 {@code </if>and} 粘在一起。
     *
     * CDATA 段必须整段保住原样，只对段外的文字做标签剥离，两个理由都是实测出来的：
     * ① {@code <[^>]+>} 会一路吃到第一个 {@code >}，而 {@code <![CDATA[} 里通常不含 {@code >}，
     *    整段 SQL（含 from／where）会被当成一个标签吞掉——真实卡 12825 的「别名表」因此是空的；
     * ② 把 CDATA 标记换成空格后再统一剥标签也不太行：段内 {@code when sysdate < to_date(…)} 的 {@code <}
     *    会一路配到后面的 {@code >}，真实卡 14615 的 {@code from t_xq_dcgl temp} 就是这样被连带删掉的。
     * 段内保留的 {@code <}／{@code >} 只是比较运算符，条款分析靠引号与括号深度认界，不会误当标签。
     */
    static String stripTags(String body) {
        Matcher m = CDATA.matcher(body);
        StringBuilder sb = new StringBuilder();
        int at = 0;
        while (m.find()) {
            sb.append(tagsOnly(body.substring(at, m.start()))).append(' ').append(m.group(1)).append(' ');
            at = m.end();
        }
        sb.append(tagsOnly(body.substring(at)));
        return sb.toString();
    }

    /** 标签剥离（只用于 CDATA 段外）；末尾再补一次 {@code <![CDATA[} 是为了没闭合的半截标记 */
    private static String tagsOnly(String s) {
        return s.replaceAll("<[^>]+>", " ").replace("<![CDATA[", " ");
    }

    /** 事实包统一的压平：先去 SQL 注释，再压空白（顺序不能反——注释是靠行尾结束的，压成单行后就再也认不出 {@code --} 的界） */
    private static String collapseCdata(String s) {
        return stripComments(s.replace("<![CDATA[", " ").replace("]]>", " "))
                .replaceAll("[ \\t\\r]+", " ").replaceAll(" *\\n *", " ");
    }

    /**
     * 摘掉 SQL 注释：{@code --…行尾} 与 {@code /* …}{@code /}，引号里的 {@code --} 不算注释。
     *
     * 为什么要单独走这一趟：真实卡 15928 有一句被注释掉的 {@code --AND a.qhours >= '08:45'}，
     * 字面量证据把它当成了生效条件，给日期列 {@code a.days} 挂上候选值 08:45——
     * 注释里的写法不是源码的意图，宁可少给候选也不能给错。
     *
     * 注释段一律等长换成空格（行注释保住结尾的换行），长度不变，按位置切条款的那套下标才照常成立。
     */
    static String stripComments(String s) {
        char[] c = s.toCharArray();
        boolean in = false;
        for (int i = 0; i < c.length; i++) {
            char ch = c[i];
            if (ch == '\'') {
                // 引号内的 -- 与 /* 不是注释；'' 是转义，不算收尾（与 inQuote 同一口径）
                if (!in) in = true;
                else if (i + 1 < c.length && c[i + 1] == '\'') i++;
                else in = false;
            } else if (in) {
                // 字面量内部原样保留
            } else if (ch == '-' && i + 1 < c.length && c[i + 1] == '-') {
                int j = i;
                while (j < c.length && c[j] != '\n') c[j++] = ' ';
                i = j - 1;                                     // 停在换行前，换行自己留下
            } else if (ch == '/' && i + 1 < c.length && c[i + 1] == '*') {
                int end = s.indexOf("*/", i + 2);
                int j = end < 0 ? c.length : end + 2;
                for (int k = i; k < j; k++) c[k] = ' ';
                i = j - 1;
            }
        }
        return new String(c);
    }

    /** 该位置是否落在单引号字面量内部（Oracle 的 '' 是转义，不算收尾） */
    static boolean inQuote(String s, int pos) {
        boolean in = false;
        for (int i = 0; i < pos && i < s.length(); i++) {
            if (s.charAt(i) != '\'') continue;
            if (in && i + 1 < s.length() && s.charAt(i + 1) == '\'') { i++; continue; }
            in = !in;
        }
        return in;
    }

    private static int matching(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            if (inQuote(s, i)) continue;
            char ch = s.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** 按顶层逗号切参数，引号与括号内的逗号不算 */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'' && !inQuote(s, i)) {
                int j = i + 1;
                while (j < s.length()) {
                    if (s.charAt(j) == '\'' && !(j + 1 < s.length() && s.charAt(j + 1) == '\'')) break;
                    j++;
                }
                i = Math.max(j, i);
                continue;
            }
            if (inQuote(s, i)) continue;
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) { out.add(s.substring(start, i).trim()); start = i + 1; }
        }
        if (start < s.length()) out.add(s.substring(start).trim());
        return out;
    }

    private static String unquote(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() < 2 || t.charAt(0) != '\'' || t.charAt(t.length() - 1) != '\'') return null;
        return t.substring(1, t.length() - 1).replace("''", "'");
    }

    /**
     * 比较式另一侧只剩"一列"时才认：函数名（紧跟左括号）、数字、引号字面量都先去掉。
     * 认不出唯一一列就返回 null——宁可不给候选，也不能把值挂到错的列上。
     */
    static String onlyColumn(String side) {
        if (side == null) return null;
        String s = QUOTED.matcher(side).replaceAll(" ");
        s = PLACEHOLDERS.matcher(s).replaceAll(" ");        // 别处的占位符是值，不是列名
        List<String> hits = new ArrayList<>();
        Matcher m = COLUMN_TOK.matcher(s);
        while (m.find()) {
            String tok = m.group().replaceAll("\\.\\s*", ".");
            if (SQL_NOISE.contains(tok.toLowerCase())) continue;
            if (tok.indexOf('.') < 0 && m.end() < s.length()) {
                int after = m.end();
                while (after < s.length() && Character.isWhitespace(s.charAt(after))) after++;
                if (after < s.length() && s.charAt(after) == '(') continue;         // 函数名
            }
            hits.add(tok);
        }
        if (hits.size() != 1) return null;
        String col = hits.get(0);
        return col.indexOf('.') >= 0 || col.length() > 1 ? col : null;              // 单字母多半是函数别名，不认
    }

    /** 条件里的字符串字面量（先抹掉才不会被当成列名） */
    private static final Pattern QUOTED = Pattern.compile("'(?:[^']|'')*'");
    /** 同一段里的其它占位符：它们是值，不是列名（{@code a.rq between #{x} and #{y}} 里 x 不该被当成一列） */
    private static final Pattern PLACEHOLDERS = Pattern.compile("[#$]\\{[^{}]*}");
}
