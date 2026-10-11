package com.preflight.common;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长任务进度登记表（第 20 轮）。
 *
 * 为什么要有它：前端原来的反馈基座只能做到"点下去之后按钮转圈 + 顶栏在飞"，用户等 30 秒
 * 仍然不知道是在跑第几条、还是已经卡死。可把这些接口全改成异步任务 + 轮询，改动面太大
 * （学习那条就是这么做的，代价是多一张 tasks 表和一套状态机）。
 *
 * 这里换成进程内登记表：调用方在入口 begin(pid, 标签, 总数)，循环里 step(pid, 说明)，出口 end(pid)；
 * 前端 api() 自动带上 pid 并按秒 GET /api/progress?id=。同步接口一行不用改协议，
 * 没登记进度的按钮自动退化成"已 N 秒"的秒表——两种情况都不会漏反馈。
 *
 * 只在单实例内有效（本系统就是单机 SQLite 单进程），因此不落库：进度是秒级即弃的数据，
 * 重启后没有意义，写库反而会把 WAL 拖进每次循环。
 */
@Component
public class ProgressRegistry {

    /** 一条进度。total=0 表示只知道"在跑"、还不知道总量。 */
    public record Entry(String label, int total, int done, String note, long startMs, long updateMs, boolean finished) {
    }

    /** 超过这个时长没更新的条目视为调用方已经异常退出，随下一次 begin 回收 */
    private static final long TTL_MS = 10 * 60 * 1000L;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void begin(String pid, String label, int total) {
        if (pid == null || pid.isBlank()) return;
        long now = System.currentTimeMillis();
        entries.put(pid, new Entry(label, Math.max(0, total), 0, "", now, now, false));
        if (entries.size() > 64) entries.entrySet().removeIf(e -> now - e.getValue().updateMs() > TTL_MS);
    }

    /** 循环里每完成一件调用一次；note 可空，用于"正在处理 xxx"这类阶段说明 */
    public void step(String pid, String note) {
        if (pid == null || pid.isBlank()) return;
        entries.computeIfPresent(pid, (k, v) ->
                new Entry(v.label(), v.total(), v.done() + 1, note == null ? v.note() : note,
                        v.startMs(), System.currentTimeMillis(), false));
    }

    public void end(String pid) {
        if (pid == null || pid.isBlank()) return;
        entries.computeIfPresent(pid, (k, v) -> new Entry(v.label(), v.total(), v.done(), v.note(),
                v.startMs(), System.currentTimeMillis(), true));
    }

    public Entry get(String pid) {
        return pid == null ? null : entries.get(pid);
    }
}
