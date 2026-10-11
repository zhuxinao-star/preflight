package com.preflight.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis mapper XML 的"语句锚点"：每条 select/insert/update/delete 在文件里的起止行。
 * 与 {@link MybatisMapperParser} 用同一套命名（文件名 + "." + statement id），
 * 所以提交改动行 → 语句名 → db_objects.name 能直接对上，不需要给 file_bindings 再加行号列
 * （那会让每次重学因行位移产生新绑定行，见 N32）。
 */
public final class MybatisAnchors {

    public record Anchor(String name, String tag, String id, int lineStart, int lineEnd) {
    }

    private static final Pattern OPEN = Pattern.compile("<(select|insert|update|delete)\\b");
    private static final Pattern ID = Pattern.compile("\\bid\\s*=\\s*[\"']([^\"']+)[\"']");

    private MybatisAnchors() {
    }

    /**
     * 逐行扫一遍源码文本（不建 DOM，避免与解析器的 include 内联相互干扰）。
     * 属性换行、CDATA 里的尖括号都按"标签起始行 → 同名结束行"计，宁可区间偏宽也不漏；
     * 起止行都取 1 基闭区间。
     */
    public static List<Anchor> scan(String fileName, String xml) {
        List<Anchor> out = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return out;
        String[] lines = blankNonCode(xml).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = OPEN.matcher(lines[i]);
            if (!m.find()) continue;
            String tag = m.group(1);
            // id 可能在同一行的后续属性里，也可能被换行分开：向下找到本标签的 '>' 为止
            String id = findId(lines, i, m.end());
            if (id == null || id.isBlank()) continue;
            int end = findClose(lines, i, tag);
            out.add(new Anchor(fileName + "." + id, tag, id, i + 1, end));
            i = end - 1; // 语句之间不重叠，跳过本段
        }
        return out;
    }

    /**
     * 把 XML 注释（可跨行）与 CDATA 内容替换成空格，行数与行号一一对应。
     * 注释里的 "&lt;select ...&gt;" 字样会被当成语句开头并把后面的真语句整段跳掉，必须先抹掉。
     */
    private static String blankNonCode(String xml) {
        StringBuilder sb = new StringBuilder(xml);
        int i = 0;
        while (i < sb.length()) {
            int c = sb.charAt(i);
            if (c == '<' && sb.indexOf("<!--", i) == i) {
                int e = sb.indexOf("-->", i + 4);
                blank(sb, i, e < 0 ? sb.length() : e + 3);
                i = e < 0 ? sb.length() : e + 3;
            } else if (c == '<' && sb.indexOf("<![CDATA[", i) == i) {
                int e = sb.indexOf("]]>", i + 9);
                blank(sb, i, e < 0 ? sb.length() : e + 3);
                i = e < 0 ? sb.length() : e + 3;
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    private static void blank(StringBuilder sb, int from, int to) {
        for (int k = from; k < to; k++) {
            if (sb.charAt(k) != '\n' && sb.charAt(k) != '\r') sb.setCharAt(k, ' ');
        }
    }

    /** 本条语句是否覆盖到 start..end 这个改动区间 */
    public static boolean overlaps(Anchor a, int start, int end) {
        return a.lineStart() <= end && a.lineEnd() >= start;
    }

    private static String findId(String[] lines, int openLine, int afterTag) {
        StringBuilder sb = new StringBuilder(lines[openLine].substring(Math.min(afterTag, lines[openLine].length())));
        for (int j = openLine; j < Math.min(openLine + 6, lines.length); j++) {
            Matcher id = ID.matcher(sb);
            if (id.find()) return id.group(1).trim();
            if (sb.indexOf(">") >= 0) return null; // 标签已闭合仍无 id：不是语句头（如 <selectKey>）
            if (j + 1 < lines.length) sb.append(' ').append(lines[j + 1]);
        }
        return null;
    }

    /** 从起始行往下找同名结束标签，支持同名嵌套（理论上不会发生，防御一行） */
    private static int findClose(String[] lines, int from, String tag) {
        Pattern close = Pattern.compile("</" + tag + "\\s*>");
        Pattern selfClose = Pattern.compile("<" + tag + "\\b[^>]*/>");
        int depth = 1;
        for (int j = from + 1; j < lines.length; j++) {
            String l = lines[j];
            if (selfClose.matcher(l).find()) return j + 1; // 自闭合语句
            Matcher c = close.matcher(l);
            if (c.find()) {
                depth--;
                if (depth == 0) return j + 1;
            }
            if (OPEN.matcher(l).find() && !l.contains("</")) depth++;
        }
        return lines.length;
    }
}
