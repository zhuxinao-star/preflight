package com.preflight.common;

/**
 * 列表分页的偏移计算（第 20 轮）。
 * <p>
 * page / size 都是从 URL 裸进来的，用 int 直接算 (page-1)*size 会溢出成负数；SQLite 把负 OFFSET
 * 当成 0，于是"越界页"返回的其实是第 1 页——列表看着有货，翻到第 2 页还是那些行，比直接报错更坏。
 * 所有 {@code LIMIT ? OFFSET ?} 的列表接口统一走这里（页码/条数各自的下限上限仍在调用方按各自默认值夹）。
 */
public final class Paging {

    private Paging() {
    }

    /** 偏移：按 long 乘再封顶，保证大页码拿到空列表而不是第 1 页 */
    public static int offset(int page, int size) {
        long o = (long) (page - 1) * size;
        return o > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) o;
    }
}
