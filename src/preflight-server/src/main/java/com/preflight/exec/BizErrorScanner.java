package com.preflight.exec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 业务错误码判定：库把语句执行成功了（没有 SQLException），但结果集或出参游标自己带回一列错误码且不为 0。
 * ④ 结果区、执行记录列表、记录详情必须共用这一个 scan —— 判定口径只许有一处。
 */
public final class BizErrorScanner {

    /** 系统设置里可增删；留空＝主动关掉判定 */
    public static final String DEFAULT_WHITELIST = "ERRORCODE,ERRCODE,SQLCODE,RET_CODE,RETURN_CODE,F_ERRORCODE";
    public static final String SETTINGS_KEY = "biz_error_columns";

    /** 只用来取"人话"随错误码一起展示，不参与判定 */
    private static final List<String> MESSAGE_COLUMNS =
            List.of("ERRORMSG", "ERROR_MSG", "ERR_MSG", "ERRMSG", "ERROR_TEXT", "ERROR_INFO", "ERRORDESC", "ERROR_DESC");

    private static final Set<String> TEXT_OK = Set.of(
            "OK", "SUCCESS", "SUCCEED", "SUCCEEDED", "TRUE", "Y", "YES", "NONE", "NOERROR", "NO_ERROR", "NORMAL", "正常");

    private BizErrorScanner() {}

    /** where=机器可辨的位置（result / cursor:游标名），whereLabel=给人看的中文位置 */
    public record Hit(String where, String whereLabel, String column, int row, String value, String message) {
        /** 落库快照与接口响应共用这一份字段名，两处各写一遍迟早分叉 */
        public Map<String, Object> toJson() {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("where", where);
            m.put("whereLabel", whereLabel);
            m.put("column", column);
            m.put("row", row);
            m.put("value", value);
            m.put("message", message);
            return m;
        }
    }

    public static List<Map<String, Object>> toJson(List<Hit> hits) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hit h : hits) out.add(h.toJson());
        return out;
    }

    /** 白名单解析：大写归一、去空、保序去重。中文逗号和分号也认——界面上就是这么教的。 */
    public static Set<String> parseWhitelist(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String part : raw.split("[,，;；\\s]+")) {
            String t = part.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 从 settings 取白名单：没写过这一行＝没定制过，用默认词；写过但留空＝用户主动关掉判定。
     * 这两件事必须分清，否则"我在设置里清空了"下一轮又自己长回来。
     */
    public static Set<String> whitelistFrom(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        try {
            List<String> got = jdbc.queryForList("SELECT value FROM settings WHERE key=?", String.class, SETTINGS_KEY);
            return got.isEmpty() ? parseWhitelist(DEFAULT_WHITELIST) : parseWhitelist(got.get(0));
        } catch (Exception e) {
            return parseWhitelist(DEFAULT_WHITELIST);
        }
    }

    public static List<Hit> scanRun(DbExecutorService.DbResult r, Set<String> whitelist) {
        if (r == null) return List.of();
        List<Hit> out = new ArrayList<>(scan(r.columns(), r.rows(), "result", "主结果集", whitelist));
        for (var c : r.outCursors()) {
            out.addAll(scan(c.columns(), c.rows(), "cursor:" + c.name(), "出参游标 " + c.name(), whitelist));
        }
        return out;
    }

    /** 一列命中白名单，就按这一列逐行判；同名列出现多次时取第一个，并把命中列回传给界面说明。 */
    public static List<Hit> scan(List<String> columns, List<List<Object>> rows,
                                 String where, String whereLabel, Set<String> whitelist) {
        List<Hit> hits = new ArrayList<>();
        if (columns == null || rows == null || columns.isEmpty() || rows.isEmpty()
                || whitelist == null || whitelist.isEmpty()) {
            return hits;
        }
        int codeCol = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (whitelist.contains(normalize(columns.get(i)))) {
                codeCol = i;
                break;
            }
        }
        if (codeCol < 0) return hits;
        String codeName = columns.get(codeCol);
        int msgCol = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (MESSAGE_COLUMNS.contains(normalize(columns.get(i)))) {
                msgCol = i;
                break;
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            if (row == null || codeCol >= row.size()) continue;
            Object v = row.get(codeCol);
            if (!isErrorCode(v)) continue;
            hits.add(new Hit(where, whereLabel, codeName, r + 1, String.valueOf(v),
                    msgCol >= 0 && msgCol < row.size() ? text(row.get(msgCol)) : null));
        }
        return hits;
    }

    /** 值是不是"错误码非 0"。认不准的（布尔之外的奇怪对象）宁可标出来让人看一眼，也不静默放过。 */
    static boolean isErrorCode(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).compareTo(BigDecimal.ZERO) != 0;
            } catch (NumberFormatException e) {
                return true;
            }
        }
        String s = text(v);
        if (s == null || s.isEmpty()) return false;
        try {
            return new BigDecimal(s).compareTo(BigDecimal.ZERO) != 0;
        } catch (NumberFormatException ignored) {
            // 非数字的码值：只有明写在成功词表里的才算正常，其余按"这一行没成功"处理
            return !TEXT_OK.contains(s.toUpperCase(Locale.ROOT));
        }
    }

    private static String normalize(String column) {
        if (column == null) return "";
        String s = column.trim().toUpperCase(Locale.ROOT);
        int dot = s.lastIndexOf('.');
        return dot >= 0 && dot + 1 < s.length() ? s.substring(dot + 1) : s;
    }

    private static String text(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
