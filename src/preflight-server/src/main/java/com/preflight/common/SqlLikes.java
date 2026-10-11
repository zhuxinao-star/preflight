package com.preflight.common;

/**
 * LIKE 通配符转义（第 18 轮在改动流里第一次出现，第卅五轮收成单点）。
 *
 * <p>用户输入里的 {@code %} 与 {@code _} 是 LIKE 的通配符：不转义时搜「100%」会命中所有以 100 开头的
 * 值，搜「a_b」会命中 "axb"——这种假命中比搜不到更坏，因为列表看着有货、数字却对不上。
 * 配套写法是 SQL 里加 {@code ESCAPE '\'}，两者必须一起用，缺一个等于没转。
 */
public final class SqlLikes {

    /** 与 {@link #escape} 配套的 ESCAPE 子句片段，拼在 LIKE ? 后面 */
    public static final String ESCAPE_CLAUSE = " ESCAPE '\\'";

    private SqlLikes() {
    }

    /** 把用户输入的 LIKE 通配符还原成字面量（配 {@code ESCAPE '\'} 使用） */
    public static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 转义后再包成「包含匹配」的 pattern */
    public static String contains(String s) {
        return "%" + escape(s) + "%";
    }
}
