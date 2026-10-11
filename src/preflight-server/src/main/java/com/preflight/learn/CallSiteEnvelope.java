package com.preflight.learn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从分发入口的 JS 调用点原文里认「信封字段」（第卅五轮）。
 *
 * <p>纯字符串分析，不碰盘也不碰库：给一份前端脚本原文 + 该代码在文件里的行号，
 * 返回那些行所在的<b>最内层对象字面量</b>顶层出现的信封键（{@code service} / {@code func} /
 * {@code func_url} / {@code bex_codes} / {@code pageoffset} / {@code pagecount}）与它们的字面量值。
 *
 * <p>为什么按调用点认而不是写死一套：老工程同一个 {@code /redis_ajax} 入口有三种带法
 * （bex 直调只带 {@code bex_codes}；转发中心带 {@code service}+{@code func}+{@code func_url}；
 * 还有只带 {@code service} 的原子服务调用），把五项固定参数一律塞进文档，
 * 就是在给普通 bex / service 编造不存在的入参。
 */
public final class CallSiteEnvelope {

    /** 认的信封键 → 界面上的一句话说明（顺序即文档里的展示顺序） */
    public static final Map<String, String> KNOWN = new LinkedHashMap<>();
    static {
        KNOWN.put("service", "转发/服务代码");
        KNOWN.put("func", "功能号");
        KNOWN.put("func_url", "功能号完整路径");
        KNOWN.put("bex_codes", "业务代码（bex 直调）");
        KNOWN.put("pageoffset", "页码");
        KNOWN.put("pagecount", "每页条数");
    }

    /**
     * 一个在调用点上真实出现的信封键。
     * {@code via}：{@code line}＝字典记的行号那一行里确实有这个码；{@code content}＝行号漂了，
     * 按内容锚点（同一份文件里含这个码的那一行）找回来的。界面上要能分得清这两种（第卅六轮 N46）。
     */
    public record Observed(String field, String value, boolean literal, String at, String via) {}

    /** 内容锚点最多找回这么多行：一个通用码可能在同一文件里出现几十次，全收会把不相干的调用点也算进来 */
    public static final int CONTENT_CAP = 12;

    private CallSiteEnvelope() {}

    /**
     * @param src   JS 文件原文
     * @param lines 该代码在文件里出现的行号（1 起，来自分发字典的 evidence）
     * @param code  该代码本身：用它把锚点定到行内出现的位置上。只按行首定位会落到
     *              {@code ka.ajax({ req:[{...}], func: function(){} })} 的<b>外层</b>对象上，
     *              把回调键 {@code func} 当成信封字段（真踩过这个坑）。
     *
     * <p>三层定位，强到弱（第卅六轮 N46：SVN 更新后字典记的行号会漂，只认行号就会把信封退化成兜底推断）：
     * <ol>
     *   <li><b>行号命中</b>：记的那一行里确实还有这个码——最可信，直接按行内位置锚定。</li>
     *   <li><b>内容命中</b>：整份文件里找含这个码的行（最多 {@link #CONTENT_CAP} 行，先看未注释的），
     *       按内容锚点找回。每条 {@code at} 都写明"字典记第 X 行 → 现在第 Y 行"。</li>
     *   <li><b>行首兜底</b>：这一行没有这个码、文件里也搜不到时，退回按整行外层对象认
     *       （值来自变量的调用点就是这种，老口径保留，不让本轮改动收走）。</li>
     * </ol>
     */
    public static Result collect(String src, List<Integer> lines, String code) {
        List<Observed> out = new ArrayList<>();
        if (src == null || src.isBlank() || code == null || code.isBlank()) return new Result(out, 0, 0);
        List<int[]> objs = objects(src);
        List<Integer> stale = new ArrayList<>();
        for (int ln : lines) {
            int start = offsetOfLine(src, ln);
            if (start < 0) continue;
            int off = codeOffset(src, start, code);
            if (off >= 0) {
                keysAt(src, objs, off, "第 " + ln + " 行" + (isComment(src, start) ? "（注释掉的调用）" : ""), "line", out);
            } else {
                stale.add(ln);        // 行号漂了或值来自变量：留给后两层处理
            }
        }
        if (!out.isEmpty()) return new Result(out, 0, 0);   // 行号命中：没走内容锚点，基数事实留给调用方报 0

        List<Integer> hits = contentLines(src, code);
        int used = Math.min(hits.size(), CONTENT_CAP);
        for (int ln : hits.subList(0, used)) {
            int start = offsetOfLine(src, ln);
            int off = codeOffset(src, start, code);
            if (off < 0) continue;
            String from = stale.isEmpty() ? "" : "（字典记第 " + join(stale) + " 行，行号已漂）";
            keysAt(src, objs, off, "第 " + ln + " 行" + from + (isComment(src, start) ? "（注释掉的调用）" : ""),
                    "content", out);
        }
        if (!out.isEmpty()) return new Result(out, hits.size(), used);
        for (int ln : stale) {          // 第三层：整行外层对象，老口径
            int start = offsetOfLine(src, ln);
            if (start >= 0) {
                keysAt(src, objs, start, "第 " + ln + " 行（这一行没有这个码，按整行外层对象认）",
                        "line", out);
            }
        }
        return new Result(out, hits.size(), used);
    }

    private static String join(List<Integer> xs) {
        StringBuilder b = new StringBuilder();
        for (int x : xs) b.append(b.length() == 0 ? "" : "、").append(x);
        return b.toString();
    }

    /**
     * 整份文件里"含这个码"的行（1 起）：要求码前后不是字母数字下划线，
     * 否则搜 {@code 1001} 会命中 {@code 10010118} 这种更长的码。
     * 未注释的行排在前面——注释掉的调用点只在没有别的候选时才用。
     */
    private static List<Integer> contentLines(String src, String code) {
        List<Integer> live = new ArrayList<>(), commented = new ArrayList<>();
        int line = 0, i = 0;
        while (i < src.length()) {
            int nl = src.indexOf('\n', i);
            if (nl < 0) nl = src.length();
            line++;
            if (codeOffset(src, i, code) >= 0) (isComment(src, i) ? commented : live).add(line);
            i = nl + 1;
        }
        live.addAll(commented);
        return live;
    }

    /** 该行内该代码第一次出现的位置（前后不能粘着别的字母数字）；找不到返回 -1 */
    private static int codeOffset(String src, int lineStart, String code) {
        int lineEnd = src.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = src.length();
        String seg = src.substring(lineStart, lineEnd);
        String low = seg.toLowerCase(java.util.Locale.ROOT);
        String needle = code.toLowerCase(java.util.Locale.ROOT);
        for (int at = low.indexOf(needle); at >= 0; at = low.indexOf(needle, at + 1)) {
            if (!isNameChar(seg, at - 1) && !isNameChar(seg, at + needle.length())) return lineStart + at + 1;
        }
        return -1;
    }

    private static boolean isNameChar(String s, int i) {
        if (i < 0 || i >= s.length()) return false;
        char c = s.charAt(i);
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static void keysAt(String src, List<int[]> objs, int off, String tag, String via, List<Observed> out) {
        int[] obj = smallestContaining(objs, off);
        if (obj == null) return;
        int n0 = out.size();
        keys(src, obj, tag, via, out);
        if (out.size() == n0) {
            // 锚点落在嵌套对象里时，往外再看一层——只接受还很小的一层，避免把整个函数体当成请求体
            int[] parent = smallestContaining(objs, obj[0] - 1);
            if (parent != null && parent != obj && parent[1] - parent[0] <= 600) keys(src, parent, tag, via, out);
        }
    }

    /**
     * 一次定位的结果：认出来的字段 + 内容锚点的基数事实。
     * {@code contentHits}＝文件里含这个码的行有几行、{@code contentUsed}＝实际看了几行（受 {@link #CONTENT_CAP} 限），
     * 两者不等时调用方要照实说"还有 N 行没看"，不能装作看全了。
     */
    public record Result(List<Observed> observed, int contentHits, int contentUsed) {}

    /** 单遍扫描：所有字符串外的花括号配对（引号内的括号不参与配对） */
    private static List<int[]> objects(String src) {
        List<int[]> pairs = new ArrayList<>();
        List<Integer> stack = new ArrayList<>();
        char q = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (q != 0) {
                if (c == '\\') i++;
                else if (c == q) q = 0;
                continue;
            }
            if (c == '\'' || c == '"') { q = c; continue; }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                int nl = src.indexOf('\n', i);
                i = nl < 0 ? src.length() : nl;
                continue;
            }
            if (c == '{') stack.add(i);
            else if (c == '}' && !stack.isEmpty()) pairs.add(new int[]{stack.remove(stack.size() - 1), i});
        }
        return pairs;
    }

    private static int[] smallestContaining(List<int[]> objs, int off) {
        int[] best = null;
        for (int[] o : objs) {
            if (o[0] <= off && off <= o[1] && (best == null || (o[1] - o[0]) < (best[1] - best[0]))) best = o;
        }
        return best;
    }

    /** 取对象字面量顶层的 {@code key: value}，只认 {@link #KNOWN} 里的键 */
    private static void keys(String src, int[] obj, String at, String via, List<Observed> out) {
        int depth = 0;
        int i = obj[0];
        char q = 0;
        while (i <= obj[1]) {
            char c = src.charAt(i);
            if (q != 0) {
                if (c == '\\') { i += 2; continue; }
                if (c == q) q = 0;
                i++;
                continue;
            }
            if (c == '\'' || c == '"') { q = c; i++; continue; }
            if (c == '{') { depth++; i++; continue; }
            if (c == '}') { depth--; i++; continue; }
            if (depth != 1) { i++; continue; }
            if (!isIdentStart(c)) { i++; continue; }
            int s = i;
            while (i <= obj[1] && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_' || src.charAt(i) == '$')) i++;
            String key = src.substring(s, i);
            int j = skipSpace(src, i, obj[1]);
            if (j > obj[1] || src.charAt(j) != ':') continue;
            if (!KNOWN.containsKey(key)) continue;
            int vStart = skipSpace(src, j + 1, obj[1]);
            int vEnd = valueEnd(src, vStart, obj[1]);
            Observed o = toObserved(key, src.substring(vStart, vEnd).trim(), at, via);
            if (o != null) out.add(o);
            i = vEnd;
        }
    }

    /** 值到哪结束：顶层逗号或对象右括号（字符串内的逗号不算） */
    private static int valueEnd(String src, int from, int close) {
        int depth = 0;
        char q = 0;
        for (int i = from; i <= close; i++) {
            char c = src.charAt(i);
            if (q != 0) {
                if (c == '\\') { i++; continue; }
                if (c == q) q = 0;
                continue;
            }
            if (c == '\'' || c == '"') { q = c; continue; }
            if (c == '{' || c == '[' || c == '(') depth++;
            else if (c == '}' || c == ']' || c == ')') {
                if (depth == 0 && c == '}') return i;
                depth--;
            } else if (c == ',' && depth == 0) return i;
        }
        return close;
    }

    private static Observed toObserved(String key, String raw, String at, String via) {
        // 分发入口的 ajax 选项对象里本来就有个同名回调键（func: function(...)），那是回调不是字段
        if (raw.startsWith("function") || raw.startsWith("=>") || raw.endsWith("=>") || raw.startsWith("new ")) {
            return null;
        }
        boolean quoted = raw.length() >= 2 && (raw.charAt(0) == '\'' || raw.charAt(0) == '"')
                && raw.charAt(raw.length() - 1) == raw.charAt(0);
        if (quoted) return new Observed(key, raw.substring(1, raw.length() - 1), true, at, via);
        if (raw.matches("-?\\d+")) return new Observed(key, raw, true, at, via);
        return new Observed(key, "", false, at, via);
    }

    private static boolean isComment(String src, int lineStart) {
        int i = skipSpace(src, lineStart, Math.min(src.length() - 1, lineStart + 40));
        return i + 1 < src.length() && src.charAt(i) == '/' && (src.charAt(i + 1) == '/' || src.charAt(i + 1) == '*');
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static int skipSpace(String src, int i, int limit) {
        while (i <= limit && (src.charAt(i) == ' ' || src.charAt(i) == '\t' || src.charAt(i) == '\r' || src.charAt(i) == '\n')) i++;
        return i;
    }

    private static int offsetOfLine(String src, int line) {
        if (line < 1) return -1;
        int off = 0;
        for (int seen = 1; seen < line; seen++) {
            off = src.indexOf('\n', off);
            if (off < 0) return -1;
            off++;
        }
        return off < src.length() ? off : -1;
    }
}
