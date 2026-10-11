package com.preflight.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 文本 → 列清单 / 真正被读的键 / 存储过程调用位序（第卅五轮）。
 *
 * <p>为什么要这一层：老工程的出入参几乎没人写进 viewConf/bexConf（用户原话「几乎没有几个接口会把
 * 出入参配置到 xml 里面」），但语句本身就摆在 mapper 与存储过程里。出参按「这条语句真正 SELECT
 * 出来的列」算，入参按「这条语句真正读的键」算，都比声明表更接近事实。
 *
 * <p>只认外层第一条 select 的列，子查询与拼接片段不在这儿猜——认不出就照实返回空，由上层说明原因。
 * 纯函数、不碰盘、不落库。
 */
public final class SqlColumns {

    /** 一列：label＝响应里能看到的键名，expr＝语句原文，conf＝high|low|star（星号＝列名要连库才知道） */
    public record Column(String label, String expr, String conf) {}

    /** call 语句的一个实参：kind＝out|bind|dollar|literal|empty */
    public record Arg(String kind, String name, String raw) {}

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern HASH = Pattern.compile("#\\{\\s*([^},;\\s]+)([^}]*)}");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{\\s*([^},;\\s]+)[^}]*}");
    private static final Pattern TEST_VAR = Pattern.compile(
            "\\b(?:if|when)\\b[^>]*?\\btest\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_$]*)");
    private static final Pattern SIMPLE_COL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_$]*\\.)?([A-Za-z_][A-Za-z0-9_$]*)$");
    private static final Pattern TRAILING_ALIAS = Pattern.compile("[)\\s'\"`]\\s+([A-Za-z_][A-Za-z0-9_$]*)$");
    /**
     * 漏写 {@code AS} 的裸别名：{@code dy.bzxx dybzxx}、{@code wj.wjmc wjmc}。
     * 一项里只有「一个（可带表前缀的）列 + 一个词」时，第二个词除了别名没有别的解释——
     * 这条不认，Oracle 写法最多的那批语句就会整列丢名（第四十二轮在 khdc100019 上撞见）。
     */
    private static final Pattern IMPLICIT_ALIAS = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_$]*\\.)?[A-Za-z_][A-Za-z0-9_$]*\\s+([A-Za-z_][A-Za-z0-9_$]*)");
    private static final Pattern CALL_REF = Pattern.compile(
            "\\bcall\\s+([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)?)\\s*\\(", Pattern.CASE_INSENSITIVE);
    /** 与 SqlDynamicAssembler 同一口径：占位符自己声明 mode=OUT 才是出参位 */
    private static final Pattern OUT_DECL = Pattern.compile("(?i)\\bmode\\s*=\\s*OUT\\b");

    private SqlColumns() {
    }

    /** 去注释、折叠空白，并把动态标签留下的 <![CDATA[ ]]> 记号清掉 */
    public static String clean(String sql) {
        if (sql == null) return "";
        String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        s = s.replace("<![CDATA[", " ").replace("]]>", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * 外层 select 的列清单。找不到 select、或只有 {@code select *} 时返回能算出的部分，
     * 星号作为 conf=star 的一列如实带出，交给上层说明「列名要连库才知道」。
     */
    public static List<Column> columns(String sql) {
        String s = stripInto(clean(sql));
        int at = topLevelKeyword(s, "select", 0);
        if (at < 0) return List.of();
        int from = topLevelKeyword(s, "from", at + 6);
        int end = from < 0 ? s.length() : from;
        List<Column> out = new ArrayList<>();
        for (String item : splitTop(s.substring(at + 6, end))) {
            Column c = column(item.trim());
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * 外层只有 {@code select *} 时（老工程里 {@code select * from (select a,b…) x} 这种包一层的写法很常见）
     * 往里再认一层，并把 conf 标成 {@code inner}——列名来自内层，界面要说清这是退让后的结论。
     */
    public static List<Column> columnsLoose(String sql) {
        List<Column> outer = columns(sql);
        boolean onlyStar = !outer.isEmpty() && outer.stream().allMatch(c -> "star".equals(c.conf()));
        if (!onlyStar) return outer;
        String s = stripInto(clean(sql));
        int from = topLevelKeyword(s, "from", 0);
        if (from < 0) return outer;
        List<Column> out = innerColumns(s, from);
        return out.isEmpty() ? outer : out;
    }

    /**
     * {@code from} 之后的那一层 SELECT 的列清单。派生表通常整个包在括号里（实测 t_sql 就是
     * {@code select * from (select …) as sjts from t_dw a}），所以这里找 select 时不要求深度 0，
     * 找到后按「它自己的括号深度」去找它的 from——深度不同层的关键字不能混着切。
     */
    private static List<Column> innerColumns(String s, int afterFrom) {
        int inner = firstKeyword(s, "select", afterFrom);
        if (inner < 0) return List.of();
        int base = depthAt(s, inner);
        int ifrom = keywordAtDepth(s, "from", inner + 6, base);
        List<Column> out = new ArrayList<>();
        for (String item : splitTop(s.substring(inner + 6, ifrom < 0 ? s.length() : ifrom))) {
            Column c = column(item.trim());
            if (c != null && !"star".equals(c.conf())) out.add(new Column(c.label(), c.expr(), "inner"));
        }
        return out;
    }

    /** {@code SELECT x INTO v_var FROM …}：INTO 是 PL/SQL 的赋值，不是列 */
    private static String stripInto(String s) {
        int at = topLevelKeyword(s, "into", 0);
        if (at < 0) return s;
        int next = Math.max(topLevelKeyword(s, "from", at), topLevelKeyword(s, "where", at));
        if (next < 0) next = s.length();
        return s.substring(0, at) + " " + s.substring(next);
    }

    private static Column column(String item) {
        if (item.isEmpty()) return null;
        if (item.equals("*") || item.matches("(?i)[A-Za-z_][A-Za-z0-9_$]*\\.\\*")) {
            return new Column("*", item, "star");
        }
        int as = topLevelKeyword(item, "as", 0);
        if (as >= 0) {
            String alias = unquote(item.substring(as + 2).trim().split("\\s+")[0]);
            return new Column(alias, item, "high");
        }
        Matcher m = SIMPLE_COL.matcher(item);
        if (m.matches()) return new Column(m.group(2), item, "high");
        Matcher im = IMPLICIT_ALIAS.matcher(item);
        if (im.matches()) return new Column(im.group(2), item, "high");
        // 表达式后跟裸别名：nvl(a.b,0) FZTBH / '1' as sjly 已在上一条处理
        Matcher t = TRAILING_ALIAS.matcher(item);
        if (t.find()) return new Column(t.group(1), item, "high");
        if (item.toLowerCase(Locale.ROOT).startsWith("select")) {   // 子查询项：别名一定在末尾括号之后
            String rest = item.substring(item.lastIndexOf(')') + 1).trim();
            if (!rest.isEmpty()) return new Column(unquote(rest.split("\\s+")[0]), item, "high");
        }
        return new Column(null, item, "low");   // 算不出名字：把原文交出去，界面照实展示
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s.replace("\"", "");
    }

    /**
     * 这条语句真正读的键：#{x}（不含声明为 OUT 的位）、${x}，以及 &lt;if test="X …"&gt; 里的判定变量。
     * 返回 name → 来源说明。判定变量单列出来是对的：它决定条件片段拼不拼，本身就是入参。
     */
    public static Map<String, String> bindings(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        String s = clean(raw);
        Matcher h = HASH.matcher(raw);
        while (h.find()) {
            if (OUT_DECL.matcher(h.group(2) == null ? "" : h.group(2)).find()) continue;
            out.putIfAbsent(simple(h.group(1)), "SQL 绑定 #{…}");
        }
        Matcher d = DOLLAR.matcher(raw);
        while (d.find()) out.putIfAbsent(simple(d.group(1)), "SQL 拼接 ${…}（值直接进语句）");
        Matcher t = TEST_VAR.matcher(raw);
        while (t.find()) {
            Matcher v = IDENT.matcher(t.group(1));
            while (v.find()) {
                String name = v.group(1);
                if (List.of("null", "true", "false", "and", "or", "not", "eq", "neq", "gt", "lt").contains(name.toLowerCase(Locale.ROOT)))
                    continue;
                if (t.group(1).toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT) + ".")) continue;
                out.putIfAbsent(name, "动态条件 test=\"…" + "\"");
            }
        }
        for (String k : new LinkedHashSet<>(out.keySet())) {   // s 已清洗：只在清洗后确认它不是纯注释里的东西
            if (!s.toLowerCase(Locale.ROOT).contains(k.toLowerCase(Locale.ROOT))) out.remove(k);
        }
        return out;
    }

    private static String simple(String expr) {
        String s = expr.split(",")[0].trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** 存储过程调用的引用名（可能是 P_KDCC.UP_MID_28102044 这种 包.过程）；不是 call 语句返回 null */
    public static String callRef(String sql) {
        Matcher m = CALL_REF.matcher(clean(sql));
        return m.find() ? m.group(1) : null;
    }

    /** call 的实参按位序拆开——出参游标是第几位、哪一位接的是业务键，都靠这个位序 */
    public static List<Arg> callArgs(String sql) {
        String s = clean(sql);
        Matcher m = CALL_REF.matcher(s);
        if (!m.find()) return List.of();
        int open = s.indexOf('(', m.end() - 1);
        int close = matching(s, open);
        if (open < 0 || close < 0) return List.of();
        List<Arg> out = new ArrayList<>();
        for (String a : splitTop(s.substring(open + 1, close))) {
            String t = a.trim();
            if (t.isEmpty()) out.add(new Arg("empty", "", ""));
            else if (t.startsWith("#{")) {
                Matcher h = HASH.matcher(t);
                if (!h.find()) { out.add(new Arg("literal", "", t)); continue; }
                out.add(new Arg(OUT_DECL.matcher(h.group(2)).find() ? "out" : "bind", simple(h.group(1)), t));
            } else if (t.contains("${")) {
                Matcher d = DOLLAR.matcher(t);
                out.add(new Arg("dollar", d.find() ? simple(d.group(1)) : "", t));
            } else if (t.equals("''") || t.equals("\"\"")) {
                out.add(new Arg("empty", "", t));
            } else {
                out.add(new Arg("literal", "", t));
            }
        }
        return out;
    }

    // ---------- 通用扫描工具 ----------

    /** 深度 0 上找关键字（词边界、忽略大小写），找不到返回 -1 */
    private static int topLevelKeyword(String s, String kw, int from) {
        return keywordAtDepth(s, kw, from, 0);
    }

    /** 在指定括号深度上找关键字：{@code base} 就是目标关键字自己所在的深度 */
    private static int keywordAtDepth(String s, String kw, int from, int base) {
        String lower = s.toLowerCase(Locale.ROOT);
        int depth = 0, inStr = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr == 0 && (c == '\'' || c == '"')) inStr = c == '\'' ? 1 : 2;
            else if (inStr == 1 && c == '\'') inStr = 0;
            else if (inStr == 2 && c == '"') inStr = 0;
            if (inStr != 0) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == base && i >= from && lower.startsWith(kw, i) && boundary(s, i, kw.length())) {
                return i;
            }
        }
        return -1;
    }

    /** 任意深度上第一次出现该关键字（找派生表内层的 select） */
    private static int firstKeyword(String s, String kw, int from) {
        String lower = s.toLowerCase(Locale.ROOT);
        int inStr = 0;
        for (int i = Math.max(from, 0); i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr == 0 && (c == '\'' || c == '"')) inStr = c == '\'' ? 1 : 2;
            else if (inStr == 1 && c == '\'') inStr = 0;
            else if (inStr == 2 && c == '"') inStr = 0;
            if (inStr != 0) continue;
            if (lower.startsWith(kw, i) && boundary(s, i, kw.length())) return i;
        }
        return -1;
    }

    /** 某位置处的括号深度（引号内的括号不算） */
    private static int depthAt(String s, int at) {
        int depth = 0, inStr = 0;
        for (int i = 0; i < at && i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr == 0 && (c == '\'' || c == '"')) inStr = c == '\'' ? 1 : 2;
            else if (inStr == 1 && c == '\'') inStr = 0;
            else if (inStr == 2 && c == '"') inStr = 0;
            if (inStr != 0) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
        }
        return depth;
    }

    private static boolean boundary(String s, int at, int len) {
        boolean left = at == 0 || !Character.isLetterOrDigit(s.charAt(at - 1)) && s.charAt(at - 1) != '_';
        int r = at + len;
        boolean right = r >= s.length() || !Character.isLetterOrDigit(s.charAt(r)) && s.charAt(r) != '_';
        return left && right;
    }

    /** 深度 0、引号外按逗号拆分 */
    public static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, inStr = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr == 0 && (c == '\'' || c == '"')) inStr = c == '\'' ? 1 : 2;
            else if (inStr == 1 && c == '\'') inStr = 0;
            else if (inStr == 2 && c == '"') inStr = 0;
            if (inStr != 0) continue;
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** open 处配对的闭括号下标；不配对返回 -1 */
    public static int matching(String s, int open) {
        if (open < 0 || open >= s.length() || s.charAt(open) != '(') return -1;
        int depth = 0, inStr = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr == 0 && (c == '\'' || c == '"')) inStr = c == '\'' ? 1 : 2;
            else if (inStr == 1 && c == '\'') inStr = 0;
            else if (inStr == 2 && c == '"') inStr = 0;
            if (inStr != 0) continue;
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
