package com.preflight.repo;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

/**
 * 两份文件内容 → 与 {@code git diff} 同格式的 unified diff。
 * SVN 通道的改动快照是按需拉的（手上只有两个版本的文件内容，没有现成 diff 文本），
 * 格式与 Git 通道保持一致，前端的行级/词级高亮才能一套代码两用。
 */
public final class UnifiedDiff {

    /** 默认上下文：与 git/SVN 客户端一致，也是历史快照与归因用的那一版（{@code file_changes.patch_file}） */
    public static final int DEFAULT_CONTEXT = 3;
    /** 界面选「整文件」时传这个值：够大即等于不截断上下文，又不会让行号加法溢出 */
    public static final int FULL_CONTEXT = 1_000_000;
    /** 允许的上下文档位（界面下拉与参数校验共用，别在两处各写一遍数字） */
    public static final int[] ALLOWED_CONTEXTS = {3, 10, 30, FULL_CONTEXT};
    private static final String NO_NEWLINE = "\\ No newline at end of file";

    private UnifiedDiff() {
    }

    /** 一侧文件：行索引 + 是否整份都是 CRLF（全 CRLF 时按 git 的口径去掉行尾的 \r） */
    private record Side(RawText text, boolean deCr) {
        Side(byte[] bytes) {
            this(new RawText(bytes == null ? new byte[0] : bytes), RawText.isCrLfText(bytes == null ? new byte[0] : bytes));
        }

        int size() {
            return text.size();
        }

        /** 该侧最后一行是否缺尾部换行符（要单独打一行提示，否则看着像少一行） */
        boolean tailNoNewline(int idx) {
            return idx == size() - 1 && text.isMissingNewlineAtEnd();
        }

        String line(int idx) {
            String s = text.getString(idx);   // 带行尾分隔符
            if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
            if (deCr && s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            return s;
        }
    }

    /** oldBytes 长度为 0 = 新增文件；newBytes 长度为 0 = 删除文件。上下文行数由调用方给（评审要看整段业务时按 FULL_CONTEXT） */
    public static String render(String path, String oldLabel, byte[] oldBytes, String newLabel, byte[] newBytes, int context) {
        int ctx = Math.max(0, context);
        Side a = new Side(oldBytes);
        Side b = new Side(newBytes);
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
                .diff(RawTextComparator.DEFAULT, a.text, b.text);

        StringBuilder out = new StringBuilder();
        out.append("--- ").append(header(path, oldBytes, oldLabel)).append('\n');
        out.append("+++ ").append(header(path, newBytes, newLabel)).append('\n');

        int next = 0;
        while (next < edits.size()) {
            // 相邻改动之间距离不超过 2*ctx 就并进同一个 hunk（与 git 默认口径一致）
            int first = next;
            int oldEnd = edits.get(next).getEndA();
            int newEnd = edits.get(next).getEndB();
            while (next + 1 < edits.size()) {
                Edit e = edits.get(next + 1);
                if (e.getBeginA() - oldEnd > ctx * 2L || e.getBeginB() - newEnd > ctx * 2L) break;
                next++;
                oldEnd = e.getEndA();
                newEnd = e.getEndB();
            }
            next++;
            Edit last = edits.get(next - 1);

            int oi = Math.max(0, edits.get(first).getBeginA() - ctx);
            int ni = Math.max(0, edits.get(first).getBeginB() - ctx);
            int oTo = Math.min(a.size(), last.getEndA() + ctx);
            int nTo = Math.min(b.size(), last.getEndB() + ctx);
            out.append("@@ -").append(span(oi + 1, oTo - oi)).append(" +").append(span(ni + 1, nTo - ni)).append(" @@\n");

            for (int k = first; k < next; k++) {
                Edit e = edits.get(k);
                while (oi < e.getBeginA()) ctx(out, a, b, oi++, ni++);   // 改动前的公共行：两侧等长同内容，只出一份
                while (oi < e.getEndA()) del(out, a, oi++);
                while (ni < e.getEndB()) add(out, b, ni++);
            }
            while (oi < oTo) ctx(out, a, b, oi++, ni++);
        }
        return out.toString();
    }

    private static String header(String path, byte[] bytes, String label) {
        if (bytes == null || bytes.length == 0) return "/dev/null";
        return path + "\t(" + label + ")";
    }

    /** 数量 1 时省略 ",1"（git 的写法），0 时写成 "N,0" */
    private static String span(int start, int count) {
        if (count == 0) return (start - 1) + ",0";
        return count == 1 ? String.valueOf(start) : start + "," + count;
    }

    private static void ctx(StringBuilder out, Side a, Side b, int ai, int bi) {
        out.append(' ').append(a.line(ai)).append('\n');
        if (a.tailNoNewline(ai) || b.tailNoNewline(bi)) out.append(NO_NEWLINE).append('\n');
    }

    private static void del(StringBuilder out, Side a, int ai) {
        out.append('-').append(a.line(ai)).append('\n');
        if (a.tailNoNewline(ai)) out.append(NO_NEWLINE).append('\n');
    }

    private static void add(StringBuilder out, Side b, int bi) {
        out.append('+').append(b.line(bi)).append('\n');
        if (b.tailNoNewline(bi)) out.append(NO_NEWLINE).append('\n');
    }
}
