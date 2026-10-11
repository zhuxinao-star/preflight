package com.preflight.repo;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一 diff（unified format）→ 新文件侧"真正被改过的行区间"。
 * 只算 '+' 行连成的区间，上下文行不算改动；整段只删不加的 hunk 退化为删除位置那一行
 * （删掉的内容仍然属于它所在的语句，不能当成"没改"）。
 * 用途：把"改了哪个文件"收窄成"改了文件里的哪几行"，再由语句锚点换算成具体语句。
 */
public final class PatchRanges {

    private PatchRanges() {
    }

    /** 返回按起始行升序、互不相交的 [start,end]（1 基、闭区间）。patch 为 null / 认不出 hunk 时给空表。 */
    public static List<int[]> newSideRanges(String patch) {
        List<int[]> runs = new ArrayList<>();
        if (patch == null || patch.isEmpty()) return runs;
        long cur = 0;          // 当前行在新文件里的行号；<=0 = 还没进 hunk
        long hunkStart = 0;
        int runsAtHunkStart = 0;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("@@")) {
                flushDeleteOnly(runs, cur, hunkStart, runsAtHunkStart);
                hunkStart = parseNewStart(line);
                cur = hunkStart;
                runsAtHunkStart = runs.size();
                continue;
            }
            if (cur <= 0) {
                // hunk 之前：文件头 +++/--- / 索引行，全部跳过
                continue;
            }
            if (line.startsWith("\\")) continue; // "\ No newline at end of file"
            if (line.startsWith("+")) {
                append(runs, cur);
                cur++;
            } else if (!line.startsWith("-")) {
                cur++; // 上下文行（含 git 偶尔省掉前导空格的空行）
            }
        }
        flushDeleteOnly(runs, cur, hunkStart, runsAtHunkStart);
        return runs;
    }

    /** 本 hunk 一条 '+' 都没加（纯删除）时，按删除位置那一行算，保证被删语句仍算命中 */
    private static void flushDeleteOnly(List<int[]> runs, long cur, long hunkStart, int runsAtHunkStart) {
        if (cur > 0 && runs.size() == runsAtHunkStart) append(runs, hunkStart);
    }

    /** "@@ -a,b +c,d @@" 的 c；认不出返回 0（该 hunk 整体作废，不会误判成第 1 行） */
    private static long parseNewStart(String header) {
        int plus = header.indexOf(" +");
        int close = plus < 0 ? -1 : header.indexOf("@@", plus);
        if (plus < 0 || close <= plus) return 0;
        String spec = header.substring(plus + 2, close).trim();
        int comma = spec.indexOf(',');
        try {
            long v = Long.parseLong(comma < 0 ? spec : spec.substring(0, comma).trim());
            return v <= 0 ? 0 : v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void append(List<int[]> runs, long line) {
        int l = (int) Math.max(1, Math.min(line, Integer.MAX_VALUE));
        if (!runs.isEmpty() && runs.get(runs.size() - 1)[1] >= l - 1) {
            runs.get(runs.size() - 1)[1] = Math.max(runs.get(runs.size() - 1)[1], l);
        } else {
            runs.add(new int[]{l, l});
        }
    }
}
