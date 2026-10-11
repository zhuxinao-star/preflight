package com.preflight.yapi;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * YApi 文档草稿的人工维护层（第卅八轮）。
 *
 * 草稿本身每次按服务字典 + 盘上代码现算（推导值不落库），这里只存"人与自动结果不一样的地方"：
 * 改过哪几列就存哪几列，与自动一致的行不落，恢复自动＝删行。读取时按 slot（自动行的字段名）逐行合并，
 * 所以重学后新算出来的字段照样会出现，已维护的那几行也不会被冲掉。
 */
@Service
public class YapiDocOverrideService {

    private final JdbcTemplate jdbc;

    public YapiDocOverrideService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String low(Object o) {
        return String.valueOf(o == null ? "" : o).trim().toLowerCase(Locale.ROOT);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 一个功能号的全部覆盖行，按 io 分组、组内按 slot 索引 */
    private Map<String, Map<String, Map<String, Object>>> load(long projectId, String kind, String code) {
        Map<String, Map<String, Map<String, Object>>> byIo = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT io, slot, added, hidden, ov_field, ov_type, ov_title, edited_at, edited_by"
                        + " FROM yapi_doc_overrides WHERE project_id=? AND kind=? AND code=?", projectId, kind, code)) {
            byIo.computeIfAbsent(str(r.get("io")), k -> new LinkedHashMap<>()).put(str(r.get("slot")), r);
        }
        return byIo;
    }

    /**
     * 把覆盖层合进自动算出来的行：返回 rows（进文档的）与 hidden（人删掉的，界面折叠列出）。
     * 每一行都带 slot 与 manual/added/orphan 身份，预览、Schema、差异与推送读的是同一份 rows。
     */
    public Map<String, Object> apply(long projectId, String kind, String code, String io,
                                     List<Map<String, Object>> auto) {
        Map<String, Map<String, Object>> ov = load(projectId, kind, code)
                .getOrDefault(io, new LinkedHashMap<>());
        List<Map<String, Object>> merged = new ArrayList<>();
        List<Map<String, Object>> hidden = new ArrayList<>();
        Set<String> autoSlots = new LinkedHashSet<>();
        for (Map<String, Object> a : auto) {
            String slot = low(a.get("field"));
            if (slot.isEmpty()) continue;
            autoSlots.add(slot);
            Map<String, Object> row = new LinkedHashMap<>(a);
            row.put("slot", slot);
            Map<String, Object> o = ov.get(slot);
            if (o != null) {
                List<String> cols = new ArrayList<>();
                if (o.get("ov_field") != null) { row.put("field", o.get("ov_field")); cols.add("字段名"); }
                if (o.get("ov_type") != null) { row.put("type", o.get("ov_type")); cols.add("类型"); }
                if (o.get("ov_title") != null) { row.put("title", o.get("ov_title")); cols.add("说明"); }
                // 自动那一份是什么也要给界面看：改了哪列、原来是哪列，悬停要说得出，不然"恢复自动"是盲的
                row.put("autoField", a.get("field"));
                row.put("autoType", str(a.get("type")));
                row.put("autoTitle", str(a.get("title")));
                row.put("editedAt", str(o.get("edited_at")));
                if (is1(o.get("hidden"))) {
                    hidden.add(row);
                    continue;
                }
                if (!cols.isEmpty()) {
                    row.put("manual", true);
                    row.put("manualCols", cols);
                }
            }
            merged.add(row);
        }
        // 覆盖行里自动结果已经没有的那些：人工新增的（added）与"代码改了所以找不到了"的（orphan）分开标
        for (Map.Entry<String, Map<String, Object>> e : ov.entrySet()) {
            if (autoSlots.contains(e.getKey())) continue;
            Map<String, Object> o = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", e.getKey());
            row.put("field", o.get("ov_field") == null ? e.getKey() : o.get("ov_field"));
            row.put("type", str(o.get("ov_type")));
            row.put("title", str(o.get("ov_title")));
            row.put("fixed", false);
            row.put("value", "");
            row.put("editedAt", str(o.get("edited_at")));
            if (is1(o.get("hidden"))) {
                row.put("added", true);
                hidden.add(row);
                continue;
            }
            row.put("manual", true);
            if (is1(o.get("added"))) {
                row.put("added", true);
                row.put("manualCols", List.of("整行新增"));
            } else {
                row.put("orphan", true);
                row.put("manualCols", List.of("代码里已找不到"));
            }
            merged.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", merged);
        out.put("hidden", hidden);
        out.put("manualCount", countIf(merged, "manual"));
        out.put("orphanCount", countIf(merged, "orphan"));
        out.put("latestEdit", latest(ov));
        return out;
    }

    private static String latest(Map<String, Map<String, Object>> ov) {
        String s = "";
        for (Map<String, Object> o : ov.values()) {
            String t = str(o.get("edited_at"));
            if (t.compareTo(s) > 0) s = t;
        }
        return s;
    }

    private static boolean is1(Object o) {
        Integer n = num(o);
        return n != null && n == 1;
    }

    private static int countIf(List<Map<String, Object>> rows, String key) {
        int n = 0;
        for (Map<String, Object> r : rows) if (Boolean.TRUE.equals(r.get(key))) n++;
        return n;
    }

    private static Integer num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? null : Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    /** 标题与路径的人工覆盖（一个功能号一份） */
    public Map<String, Object> meta(long projectId, String kind, String code) {
        return jdbc.queryForList("SELECT ov_title, ov_path, edited_at FROM yapi_doc_meta"
                        + " WHERE project_id=? AND kind=? AND code=?", projectId, kind, code)
                .stream().findFirst().orElse(null);
    }

    /**
     * 保存：只写差异。submitted 是界面上当前这一份行清单（每行带 slot / field / type / title，hidden 表示"人删了但留着可恢复"）。
     * 与自动一致的行会被删掉（＝恢复自动），所以这张表永远只装"人真的改过的东西"。
     */
    public Map<String, Object> save(long projectId, String kind, String code, String io,
                                    List<Map<String, Object>> auto, List<Map<String, Object>> submitted,
                                    String user) {
        Set<String> autoSlots = new LinkedHashSet<>();
        Map<String, Map<String, Object>> autoBy = new LinkedHashMap<>();
        for (Map<String, Object> a : auto) {
            String s = low(a.get("field"));
            if (s.isEmpty() || !autoSlots.add(s)) continue;
            autoBy.put(s, a);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> dup = new ArrayList<>();
        int blank = 0;
        for (Map<String, Object> r : submitted) {
            String name = low(r.get("field"));
            if (name.isEmpty()) { blank++; continue; }
            if (!seen.add(name)) dup.add(name);
        }
        if (blank > 0) {
            return Map.of("ok", false, "error", "有 " + blank + " 行的字段名是空的：字段名要填，整行不要就点【删（可恢复）】");
        }
        if (!dup.isEmpty()) {
            return Map.of("ok", false, "error", "字段名重复（不区分大小写）：" + String.join("、", dup)
                    + "——同名两行推给 YApi 只剩一条，先改掉再保存");
        }
        // 这一份里每一行按它自己带来的 slot 认归属（改过名的行 slot 仍是自动那一位的名字，所以改名不会丢证据）。
        // "是不是人工新增"只由自动集合说了算，不信界面传来的标记——界面标记只能说明它从哪一档来。
        Map<String, Map<String, Object>> bySlot = new LinkedHashMap<>();
        for (Map<String, Object> r : submitted) {
            String own = low(r.get("field"));
            String slot = str(r.get("slot")).isBlank() ? own : low(r.get("slot"));
            if (bySlot.containsKey(slot)) {
                return Map.of("ok", false, "error", "有两行都说是同一位自动字段「" + slot + "」改来的：这一份自相矛盾，"
                        + "先点【↺ 恢复自动】再改");
            }
            bySlot.put(slot, r);
        }
        Set<String> kept = new LinkedHashSet<>(bySlot.keySet());

        int changed = 0, added = 0, hidden = 0, cleared = 0;
        for (Map.Entry<String, Map<String, Object>> e : bySlot.entrySet()) {
            String slot = e.getKey();
            Map<String, Object> r = e.getValue();
            Map<String, Object> a = autoBy.get(slot);
            boolean isNew = a == null;   // 自动集合里没有这一位＝人加的（原来"代码里已找不到"的行也归到这里，等于自愈）
            boolean isHidden = Boolean.TRUE.equals(r.get("hidden"));
            String field = str(r.get("field")).trim();
            String ovField = isNew || !field.equalsIgnoreCase(slot) ? field : null;
            String ovType = diff(a, "type", r.get("type"), isNew);
            String ovTitle = diff(a, "title", r.get("title"), isNew);
            if (!isNew && !isHidden && ovField == null && ovType == null && ovTitle == null) {
                cleared += jdbc.update("DELETE FROM yapi_doc_overrides WHERE project_id=? AND kind=? AND code=? AND io=? AND slot=?",
                        projectId, kind, code, io, slot);
                continue;
            }
            jdbc.update("INSERT INTO yapi_doc_overrides(project_id,kind,code,io,slot,added,hidden,"
                            + "ov_field,ov_type,ov_title,edited_at,edited_by) VALUES (?,?,?,?,?,?,?,?,?,?,datetime('now','localtime'),?)"
                            + " ON CONFLICT(project_id,kind,code,io,slot) DO UPDATE SET added=excluded.added,"
                            + " hidden=excluded.hidden, ov_field=excluded.ov_field, ov_type=excluded.ov_type,"
                            + " ov_title=excluded.ov_title, edited_at=excluded.edited_at, edited_by=excluded.edited_by",
                    projectId, kind, code, io, slot, isNew ? 1 : 0, isHidden ? 1 : 0, ovField, ovType, ovTitle, user);
            if (isHidden) hidden++;
            else if (isNew) added++;
            else changed++;
        }
        // 自动行里有、这一份里却没有的（既没改名也没被恢复）＝人删掉了：记成 hidden，不删证据，界面上还能捞回来
        for (String slot : autoSlots) {
            if (kept.contains(slot)) continue;
            jdbc.update("INSERT INTO yapi_doc_overrides(project_id,kind,code,io,slot,added,hidden,edited_at,edited_by)"
                            + " VALUES (?,?,?, ?,?,0,1,datetime('now','localtime'),?)"
                            + " ON CONFLICT(project_id,kind,code,io,slot) DO UPDATE SET hidden=1, added=0,"
                            + " edited_at=excluded.edited_at, edited_by=excluded.edited_by",
                    projectId, kind, code, io, slot, user);
            kept.add(slot);
            hidden++;
        }
        // 之前维护过、这一份里彻底不要了的行（改名后留下的旧 slot、被删掉的孤儿行）：清掉，别留幽灵
        for (Map<String, Object> old : jdbc.queryForList(
                "SELECT slot FROM yapi_doc_overrides WHERE project_id=? AND kind=? AND code=? AND io=?",
                projectId, kind, code, io)) {
            String slot = str(old.get("slot"));
            if (kept.contains(slot)) continue;
            cleared += jdbc.update("DELETE FROM yapi_doc_overrides WHERE project_id=? AND kind=? AND code=? AND io=? AND slot=?",
                    projectId, kind, code, io, slot);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("changed", changed);
        out.put("added", added);
        out.put("hidden", hidden);
        out.put("cleared", cleared);
        out.put("io", io);
        return out;
    }

    /** 与自动值不同才存；新增行没有自动值，一律按"人写的"存 */
    private static String diff(Map<String, Object> auto, String key, Object submitted, boolean isNew) {
        String s = str(submitted).trim();
        if (isNew) return s.isEmpty() ? null : s;
        if (auto == null) return null;
        String a = str(auto.get(key)).trim();
        return s.equals(a) ? null : s;
    }

    /** 保存标题/路径覆盖：与自动一致就删行（＝恢复自动） */
    public Map<String, Object> saveMeta(long projectId, String kind, String code,
                                        String autoTitle, String autoPath, String title, String path, String user) {
        String t = title == null ? "" : title.trim();
        String p = path == null ? "" : path.trim();
        String ovTitle = t.isEmpty() || t.equals(autoTitle.trim()) ? null : t;
        String ovPath = p.isEmpty() || p.equals(autoPath.trim()) ? null : p;
        if (ovTitle == null && ovPath == null) {
            jdbc.update("DELETE FROM yapi_doc_meta WHERE project_id=? AND kind=? AND code=?", projectId, kind, code);
            return Map.of("ok", true, "manual", false);
        }
        jdbc.update("INSERT INTO yapi_doc_meta(project_id,kind,code,ov_title,ov_path,edited_at,edited_by)"
                        + " VALUES (?,?,?,?,?,datetime('now','localtime'),?)"
                        + " ON CONFLICT(project_id,kind,code) DO UPDATE SET ov_title=excluded.ov_title,"
                        + " ov_path=excluded.ov_path, edited_at=excluded.edited_at, edited_by=excluded.edited_by",
                projectId, kind, code, ovTitle, ovPath, user);
        return Map.of("ok", true, "manual", true);
    }

    /** 待同步列表用：这个项目的哪些功能号被人工维护过（含几处） */
    public Map<String, Integer> manualCounts(long projectId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT kind, code, COUNT(*) n FROM yapi_doc_overrides WHERE project_id=? GROUP BY kind, code", projectId)) {
            out.merge(str(r.get("kind")) + "|" + str(r.get("code")), num(r.get("n")), Integer::sum);
        }
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT kind, code FROM yapi_doc_meta WHERE project_id=?", projectId)) {
            out.merge(str(r.get("kind")) + "|" + str(r.get("code")), 1, Integer::sum);
        }
        return out;
    }

    /**
     * 这一个功能号被人工维护了几处（含被删掉的行、标题/路径各算一处）。
     * 故意直接复用 {@link #manualCounts}：待同步列表与文档编辑器显示的是同一个数，
     * 两处各写一条 SQL 迟早会不一致——这张表只装人工改过的行，全项目扫一次是常数级开销。
     */
    public int countFor(long projectId, String kind, String code) {
        return manualCounts(projectId).getOrDefault(kind + "|" + code, 0);
    }

    /** 删项目时的出口：这两张表按 project_id 归属，必须跟着项目一起走。
     *  删仓库不在这儿清——维护过的行按功能号记账，仓库重新学回来时那份人工内容还要用。 */
    public int purge(long projectId) {
        int n = jdbc.update("DELETE FROM yapi_doc_overrides WHERE project_id=?", projectId);
        n += jdbc.update("DELETE FROM yapi_doc_meta WHERE project_id=?", projectId);
        return n;
    }
}
