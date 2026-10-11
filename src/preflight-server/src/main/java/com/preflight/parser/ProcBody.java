package com.preflight.parser;

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
 * 存储过程 / 包体源码的纯文本分析（第卅五轮）。
 *
 * <p>盘上的过程代码是这一轮出参的主要来源：接口语句 {@code call P_KDCC.UP_MID_28102044(…)} 只说明
 * 「有两位游标 OUT」，真正返回哪些列写在过程体里的 {@code OPEN RC1 FOR …}。所以这里做三件事：
 * 形参（含 {@code -- 中文说明}，那是唯一能拿到参数中文名又不连库的证据）、
 * {@code OPEN 游标 FOR} 的目标语句、动态语句变量的字符串拼接还原。
 *
 * <p>注释里的 {@code --OPEN RC2 FOR …} 不算数，所以先去注释再匹配。
 */
public final class ProcBody {

    /** 形参：名字 + IN/OUT 模式 + 类型 + 同行注释里的中文名 */
    public record Param(String name, String mode, String type, String title) {}

    /** OPEN 游标 FOR 目标：shape＝direct（后面就是 SELECT）|dynamic（给的是变量名）|unknown */
    public record Open(String cursor, String sql, String shape, int line) {}

    /** 游标被函数返回值赋值：{@code rc2 := p_kdemp.FN_EMPLIMITQUERY2(t_sql, nb_params, …)} */
    public record Assign(String cursor, String callee, List<String> args, int line) {}

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_$]*)\\b.*?--\\s*(.+)$");
    private static final Pattern PARAM = Pattern.compile(
            "(?i)^([A-Za-z_][A-Za-z0-9_$]*)\\s+(?:(IN\\s+OUT|OUT|IN)\\s+)?(.+)$");

    private ProcBody() {
    }

    /** 先去注释但保留换行（行号要能对上盘上的源码） */
    public static String stripComments(String text) {
        if (text == null) return "";
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    /**
     * 括号里的形参清单。注释先按行取中文名（{@code v_p_gybh int, -- 员工编号}），
     * 再剥注释按逗号切——所以中文名和切分用的是同一份原文，不会错位。
     */
    public static List<Param> params(String headerRaw) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (String line : headerRaw.split("\\r?\\n")) {
            Matcher m = TITLE.matcher(line);
            if (m.matches()) {
                String t = m.group(2).trim().replaceAll("[,;]$", "");
                if (!t.isEmpty()) titles.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), t);
            }
        }
        List<Param> out = new ArrayList<>();
        for (String item : SqlColumns.splitTop(stripComments(headerRaw))) {
            String t = item.trim().replaceAll(",+$", "");
            Matcher m = PARAM.matcher(t);
            if (!m.matches()) continue;
            String mode = m.group(2) == null ? "in" : m.group(2).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            out.add(new Param(m.group(1), mode, m.group(3).trim(),
                    titles.getOrDefault(m.group(1).toLowerCase(Locale.ROOT), "")));
        }
        return out;
    }

    /** {@code OPEN <cursor> FOR …;} 的每一处目标（同一游标可能被多个分支打开，全给出来） */
    public static List<Open> opens(String body, String cursor) {
        List<Open> out = new ArrayList<>();
        String s = stripComments(body);
        Pattern p = Pattern.compile("(?i)\\bopen\\s+" + Pattern.quote(cursor) + "\\s+for\\b");
        Matcher m = p.matcher(s);
        while (m.find()) {
            int at = m.end();
            int semi = depthZeroSemicolon(s, at);
            String target = s.substring(at, semi < 0 ? s.length() : semi).trim();
            int nl = (int) s.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
            out.add(new Open(cursor, SqlColumns.clean(target), shape(target), nl));
        }
        return out;
    }

    private static String shape(String target) {
        String t = SqlColumns.clean(target).toLowerCase(Locale.ROOT);
        if (t.startsWith("select") || t.startsWith("with")) return "direct";
        if (t.matches("[A-Za-z_][A-Za-z0-9_$]*")) return "dynamic";
        return "unknown";
    }

    /** 本体内所有 {@code OPEN … FOR}（不限游标名）：跳进被调函数时用 */
    public static List<Open> opensAny(String body) {
        List<Open> out = new ArrayList<>();
        String s = stripComments(body);
        Matcher m = Pattern.compile("(?i)\\bopen\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+for\\b").matcher(s);
        while (m.find()) {
            int at = m.end();
            int semi = depthZeroSemicolon(s, at);
            String target = s.substring(at, semi < 0 ? s.length() : semi).trim();
            out.add(new Open(m.group(1), SqlColumns.clean(target), shape(target), lineOf(s, m.start())));
        }
        return out;
    }

    /** {@code rc2 := p_kdemp.FN_EMPLIMITQUERY2(t_sql, …)}：出参游标由函数返回值的写法 */
    public static List<Assign> assignedCalls(String body, String cursor) {
        List<Assign> out = new ArrayList<>();
        String s = stripComments(body);
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(cursor) + "\\s*:=\\s*([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\(")
                .matcher(s);
        while (m.find()) {
            int open = s.indexOf('(', m.end() - 1);
            int close = SqlColumns.matching(s, open);
            if (open < 0 || close < 0) continue;
            List<String> args = new ArrayList<>();
            for (String a : SqlColumns.splitTop(s.substring(open + 1, close))) args.add(a.trim());
            out.add(new Assign(cursor, m.group(1), args, lineOf(s, m.start())));
        }
        return out;
    }

    /** 某个 OPEN 变量在本体里可能由哪些形参喂进来：顺着赋值一层层走，函数调用当黑盒，走不到就不列 */
    public static List<String> sourceParams(String body, List<Param> params, String var) {
        Set<String> names = new LinkedHashSet<>();
        for (Param p : params) names.add(p.name().toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        if (var == null || var.isBlank() || names.isEmpty()) return out;
        walkSource(stripComments(body), names, var, new LinkedHashSet<>(), out, 0);
        return out;
    }

    private static void walkSource(String s, Set<String> params, String var, Set<String> visited,
                                   List<String> out, int depth) {
        if (depth > 3 || !visited.add(var.toLowerCase(Locale.ROOT))) return;
        for (String rhs : assignments(s, var)) {
            for (String id : topLevelIdents(rhs)) {
                String key = id.toLowerCase(Locale.ROOT);
                if (key.equals(var.toLowerCase(Locale.ROOT))) continue;
                if (params.contains(key)) {
                    if (!out.contains(key)) out.add(key);
                } else {
                    walkSource(s, params, id, visited, out, depth + 1);
                }
            }
        }
    }

    /** 本体里对变量的全部赋值右值（含条件分支里的再赋值） */
    private static List<String> assignments(String stripped, String var) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(var) + "\\s*:=\\s*").matcher(stripped);
        while (m.find()) {
            int at = m.end();
            int semi = depthZeroSemicolon(stripped, at);
            out.add(stripped.substring(at, semi < 0 ? stripped.length() : semi));
        }
        return out;
    }

    /**
     * 括号外、引号外的标识符：函数调用（后面紧跟左括号）与 {@code pkg.name} 的成员段都不算——
     * 调用结果参与拼接时认不出被调函数读了哪个形参，宁可不列也不把它算成来源。
     */
    private static List<String> topLevelIdents(String rhs) {
        List<String> out = new ArrayList<>();
        int depth = 0, inStr = 0, i = 0;
        while (i < rhs.length()) {
            char c = rhs.charAt(i);
            if (inStr == 0 && c == '\'') {
                inStr = 1;
                i++;
                continue;
            }
            if (inStr == 1) {
                if (c == '\'') {
                    if (i + 1 < rhs.length() && rhs.charAt(i + 1) == '\'') i += 2;
                    else inStr = 0;
                }
                i++;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && (Character.isLetter(c) || c == '_')) {
                int j = i;
                while (j < rhs.length() && (Character.isLetterOrDigit(rhs.charAt(j)) || rhs.charAt(j) == '_'
                        || rhs.charAt(j) == '$' || rhs.charAt(j) == '.')) j++;
                boolean isCall = j < rhs.length() && rhs.charAt(j) == '(';
                String token = rhs.substring(i, j);
                if (!isCall && !token.endsWith(".")) {
                    int dot = token.lastIndexOf('.');
                    out.add(dot >= 0 ? token.substring(dot + 1) : token);
                }
                i = j;
                continue;
            }
            i++;
        }
        return out;
    }

    /** 变量第一次被赋值那一行（盘上行号，用于证据）；找不到返回 0 */
    public static int assignLine(String body, String var) {
        String s = stripComments(body);
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(var) + "\\s*:=\\s*['\"]").matcher(s);
        return m.find() ? lineOf(s, m.start()) : 0;
    }

    private static int lineOf(String s, int at) {
        return (int) s.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
    }

    /**
     * 把 {@code t_sql := 'select …' || 'from …'} 的字符串片段按出现顺序接起来，
     * 中间被变量替换掉的段保持原位——够用来认外层列名，认不出的地方不硬编。
     *
     * <p>老写法是「先赋骨架，后面几条 {@code t_sql := t_sql || ' and …'} 往上追加」
     * （实测 UP_MID_28102044 的 t_sql 三条赋值就是这种），所以：首条起头，引用了自己名字的按顺序接上，
     * 整段重新赋值的（另一条分支的完整语句）不接——接起来会拼出两条 SELECT，反而认不出列。
     */
    public static String dynamic(String body, String var) {
        String s = stripComments(body);
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(var) + "\\s*:=\\s*").matcher(s);
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        while (m.find()) {
            int at = m.end();
            int semi = depthZeroSemicolon(s, at);
            String rhs = s.substring(at, semi < 0 ? s.length() : semi);
            if (!started) {
                sb.append(joinLiterals(rhs, var));
                started = true;
                continue;
            }
            if (referencesSelf(rhs, var)) sb.append(' ').append(joinLiterals(rhs, var));
        }
        return sb.toString().trim();
    }

    private static boolean referencesSelf(String rhs, String var) {
        for (String id : topLevelIdents(rhs)) {
            if (id.equalsIgnoreCase(var)) return true;
        }
        return false;
    }

    /** 引号内是字面量、引号外一律留在原位（变量名可见，便于看出片段边界），被拼接的变量自身不算内容 */
    private static String joinLiterals(String rhs, String var) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < rhs.length()) {
            char c = rhs.charAt(i);
            if (c == '\'') {
                int j = i + 1;
                while (j < rhs.length()) {
                    if (rhs.charAt(j) == '\'') {
                        if (j + 1 < rhs.length() && rhs.charAt(j + 1) == '\'') {
                            j += 2;   // '' 是引号本身
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                if (j >= rhs.length()) break;   // 引号没闭合：这段不要了，不猜
                sb.append(rhs, i + 1, j).append(' ');
                i = j + 1;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int j = i;
                while (j < rhs.length() && (Character.isLetterOrDigit(rhs.charAt(j)) || rhs.charAt(j) == '_'
                        || rhs.charAt(j) == '$')) j++;
                String id = rhs.substring(i, j);
                if (!id.equalsIgnoreCase(var)) sb.append(id);   // t_sql := t_sql || … 里的自己不算 SQL 内容
                i = j;
            } else if (c == '.' || c == '(' || c == ')') {
                sb.append(c);
                i++;
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    private static int depthZeroSemicolon(String s, int from) {
        int depth = 0, inStr = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr == 0 && c == '\'') inStr = 1;
            else if (inStr == 1 && c == '\'') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inStr = 0;
            }
            if (inStr == 1) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ';' && depth <= 0) return i;
        }
        return -1;
    }
}
