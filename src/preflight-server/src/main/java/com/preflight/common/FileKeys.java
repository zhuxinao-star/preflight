package com.preflight.common;

import java.util.Locale;

/**
 * 文件路径的归一比较键（第卅五轮抽出单点）。
 *
 * <p>为什么要有：commit 里存的是仓库相对路径、解析器证据里可能是绝对路径，两者不能直接 equals；
 * 而「改动碰到哪个字段」与「这个字段属于哪个功能号」两件事必须比同一个键，
 * 否则会出现一边算命中、一边算没命中。所以整套判据收在这一个函数里。
 */
public final class FileKeys {

    private FileKeys() {
    }

    /** 取路径末尾「所在目录/文件名」并小写：够区分 metaData / mybatis 下同名文件之外的绝大多数场景 */
    public static String tail(String path) {
        if (path == null) return "";
        String s = path.replace('\\', '/').trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        int i = s.lastIndexOf('/');
        if (i < 0) return s.toLowerCase(Locale.ROOT);
        int j = s.lastIndexOf('/', i - 1);
        return s.substring(j + 1).toLowerCase(Locale.ROOT);
    }
}
