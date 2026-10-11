package com.preflight.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.llm.LlmGatewayService;
import com.preflight.parser.BodyShape;
import com.preflight.parser.DefaultValueRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 用例生成器：优先 LLM（基于接口卡/SQL 卡 + 可选改动上下文），失败/不可用降级为内置参数模板
 * （空值 / 边界极值 / 超长串），保证离线也能产出基础用例。增量：只对受影响目标生成。
 */
@Service
public class CaseService {

    private final JdbcTemplate jdbc;
    private final LlmGatewayService llm;
    private final ObjectMapper mapper;

    public CaseService(JdbcTemplate jdbc, LlmGatewayService llm, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.mapper = mapper;
    }

    public record GenResult(int created, boolean llmUsed, List<String> notes) {}

    public GenResult generate(String targetType, long targetId, boolean useLlm) {
        List<String> notes = new ArrayList<>();
        int created = 0;
        boolean llmUsed = false;

        Map<String, Object> card = targetType.equals("interface")
                ? jdbc.queryForMap("SELECT * FROM interfaces WHERE id=?", targetId)
                : jdbc.queryForMap("SELECT * FROM db_objects WHERE id=?", targetId);

        if (useLlm && llm.isAvailable()) {
            try {
                created += generateByLlm(targetType, targetId, card, notes);
                llmUsed = true;
            } catch (Exception e) {
                notes.add("LLM 生成失败，已降级为模板用例：" + e.getMessage());
            }
        } else if (useLlm) {
            notes.add("LLM 未配置或不可用，使用模板用例（离线模式）");
        }
        if (!llmUsed) {
            created += generateByTemplate(targetType, targetId, card);
        }
        return new GenResult(created, llmUsed, notes);
    }

    @SuppressWarnings("unchecked")
    private int generateByLlm(String targetType, long targetId, Map<String, Object> card, List<String> notes) throws Exception {
        Map<String, Object> def = new LinkedHashMap<>();
        if ("interface".equals(targetType)) {
            def.put("kind", "http");
            def.put("method", card.get("http_method"));
            def.put("path", card.get("path"));
            def.put("paramsSchema", card.get("params_schema") == null ? Map.of()
                    : mapper.readValue((String) card.get("params_schema"), Map.class));
        } else {
            def.put("kind", "sql");
            def.put("name", card.get("name"));
            def.put("sqlTemplate", card.get("sql_template"));
            def.put("signature", card.get("signature") == null ? List.of()
                    : mapper.readValue((String) card.get("signature"), List.class));
        }
        String prompt = "目标定义：" + mapper.writeValueAsString(def)
                + "\n请设计 3~5 条测试用例（正常/边界/异常）。params 结构：HTTP 用例为 {\"path\":{},\"query\":{},\"body\":{}}，"
                + "SQL 用例为按顺序的参数数组。只输出 JSON 数组。";
        String text = llm.chat("testcase", prompt).text();
        int s = text.indexOf('[');
        int e = text.lastIndexOf(']');
        if (s < 0 || e <= s) throw new IllegalStateException("LLM 未返回 JSON 数组");
        List<Map<String, Object>> cases = mapper.readValue(text.substring(s, e + 1), List.class);
        String schemaHash = currentSchemaHash(targetType, targetId);
        int created = 0;
        for (Map<String, Object> c : cases) {
            String name = String.valueOf(c.getOrDefault("name", "用例"));
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_cases WHERE target_type=? AND target_id=? AND name=?",
                    Integer.class, targetType, targetId, name);
            if (dup != null && dup > 0) continue;
            jdbc.update("INSERT INTO test_cases(target_type, target_id, name, scenario, params, expect, origin, base_schema_hash, status) VALUES (?,?,?,?,?,?,?,?,?)",
                    targetType, targetId, name,
                    String.valueOf(c.getOrDefault("scenario", "")),
                    mapper.writeValueAsString(c.getOrDefault("params", Map.of())),
                    String.valueOf(c.getOrDefault("expect", "")), "llm", schemaHash, "pending");
            created++;
        }
        return created;
    }

    private String currentSchemaHash(String targetType, long targetId) {
        try {
            if ("interface".equals(targetType)) {
                return jdbc.queryForObject("SELECT schema_hash FROM interfaces WHERE id=?", String.class, targetId);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private int generateByTemplate(String targetType, long targetId, Map<String, Object> card) {
        int created = 0;
        if ("interface".equals(targetType)) {
            try {
                Map<String, Object> schema = card.get("params_schema") == null ? Map.of()
                        : mapper.readValue((String) card.get("params_schema"), Map.class);
                Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                BiFunction<String, Map<String, Object>, Object> sampler =
                        BodyShape.sampler(DefaultValueRules.functionNo((String) card.get("path")));
                Map<String, Object> normal = splitByIn(props, sampler, false);
                created += saveTemplateCase(targetType, targetId, "模板·正常路径", "按参数 schema 构造的正常值", normal);
                Map<String, Object> empty = splitByIn(props, BodyShape::sampleValue, true);
                created += saveTemplateCase(targetType, targetId, "模板·空值校验", "全部可空参数传空，验证接口健壮性（path 参数保持必填样例值）", empty);
                Map<String, Object> extreme = splitByIn(props, CaseService::extremeValue, false);
                created += saveTemplateCase(targetType, targetId, "模板·边界极值", "超长字符串/数值边界", extreme);
            } catch (Exception e) {
                created += saveTemplateCase(targetType, targetId, "模板·冒烟", "无参数冒烟调用", Map.of("path", Map.of(), "query", Map.of(), "body", Map.of()));
            }
        } else {
            int q = 0;
            String sqlT = String.valueOf(card.getOrDefault("sql_template", ""));
            for (int i = 0; i < sqlT.length(); i++) if (sqlT.charAt(i) == '?') q++;
            List<Object> normal = new ArrayList<>();
            List<Object> nulls = new ArrayList<>();
            List<Object> extreme = new ArrayList<>();
            for (int i = 0; i < q; i++) { normal.add("1"); nulls.add(null); extreme.add(999999999); }
            created += saveTemplateCase(targetType, targetId, "模板·正常参数", "按 ? 占位符自动生成样例参数，可按需修改", Map.of("values", normal));
            created += saveTemplateCase(targetType, targetId, "模板·空值", "占位符全部传 NULL，验证 SQL 健壮性", Map.of("values", nulls));
            created += saveTemplateCase(targetType, targetId, "模板·边界值", "占位符取数值上界", Map.of("values", extreme));
        }
        return created;
    }

    /** 按 schema 的 in 标记把参数拆到 path/query/body 三段；body 段按 DTO 字段摊平（发出去的 JSON 形状） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> splitByIn(Map<String, Object> props,
                                          BiFunction<String, Map<String, Object>, Object> valFn,
                                          boolean blankQuery) {
        Map<String, Object> path = new LinkedHashMap<>();
        Map<String, Object> query = new LinkedHashMap<>();
        for (Map.Entry<String, Object> en : props.entrySet()) {
            Map<String, Object> ps = (Map<String, Object>) en.getValue();
            String in = String.valueOf(ps.getOrDefault("in", "query"));
            switch (in) {
                case "path" -> path.put(en.getKey(), valFn.apply(en.getKey(), ps));
                case "body" -> { }   // body 走 BodyShape 摊平，见下
                default -> query.put(en.getKey(), blankQuery ? "" : valFn.apply(en.getKey(), ps));
            }
        }
        Map<String, Object> body = BodyShape.flatten(props, valFn, blankQuery);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", path);
        params.put("query", query);
        params.put("body", body);
        return params;
    }

    /** 用例复核修复（04-补齐 §3.4）：按当前 schema 补齐缺失参数，保留人工填写的非空值，重置健康状态 */
    @SuppressWarnings("unchecked")
    public GenResult repair(long caseId) {
        Map<String, Object> tc = jdbc.queryForMap("SELECT * FROM test_cases WHERE id=?", caseId);
        String targetType = (String) tc.get("target_type");
        long targetId = ((Number) tc.get("target_id")).longValue();
        if (!"interface".equals(targetType)) {
            jdbc.update("UPDATE test_cases SET status='approved' WHERE id=?", caseId);
            return new GenResult(0, false, List.of("SQL 用例无参数 schema，已直接恢复"));
        }
        Map<String, Object> card = jdbc.queryForMap("SELECT * FROM interfaces WHERE id=?", targetId);
        Map<String, Object> schema;
        try {
            schema = card.get("params_schema") == null ? Map.of()
                    : mapper.readValue((String) card.get("params_schema"), Map.class);
        } catch (Exception e) {
            schema = Map.of();
        }
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        Map<String, Object> fresh = splitByIn(props,
                BodyShape.sampler(DefaultValueRules.functionNo((String) card.get("path"))), false);
        // 保留人工编辑：旧参数中值非空且仍存在于 schema 的条目优先于新样例
        Map<String, Object> old = new LinkedHashMap<>();
        try {
            if (tc.get("params") != null) old.putAll(mapper.readValue((String) tc.get("params"), Map.class));
        } catch (Exception ignore) {
        }
        List<String> keptKeys = new ArrayList<>();
        // 兼容历史二次编码数据：解析出 String 时再解一层
        for (int attempt = 0; attempt < 2 && old.isEmpty(); attempt++) {
            try {
                Object parsed = tc.get("params") == null ? null : mapper.readValue((String) tc.get("params"), Object.class);
                if (parsed instanceof String s) {
                    tc = new LinkedHashMap<>(tc);
                    tc.put("params", s);
                    continue;
                }
                if (parsed instanceof Map<?, ?> pm) {
                    pm.forEach((k, v) -> old.put(String.valueOf(k), v));
                }
            } catch (Exception ignore) {
                break;
            }
        }
        Set<String> bodyKeys = BodyShape.topLevelKeys(props);
        for (String section : List.of("path", "query", "body")) {
            Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) fresh.getOrDefault(section, Map.of()));
            Object oldSec = "body".equals(section)
                    ? unwrapLegacyBody(old.get(section), props, bodyKeys)
                    : old.get(section);
            if (oldSec instanceof Map<?, ?> om) {
                for (Map.Entry<?, ?> en : om.entrySet()) {
                    String key = String.valueOf(en.getKey());
                    Object v = en.getValue();
                    // body 段按 DTO 字段摊平，键名要对齐摊平后的顶层字段而不是 Java 形参名
                    boolean inSchema = "body".equals(section) ? bodyKeys.contains(key) : props.containsKey(key);
                    if (inSchema && !blankValue(v)) {
                        merged.put(key, mergeKept(merged.get(key), v));
                        keptKeys.add(section + "." + key);
                    }
                }
            }
            fresh.put(section, merged);
        }
        try {
            jdbc.update("UPDATE test_cases SET params=?, base_schema_hash=?, status='approved', scenario=? WHERE id=?",
                    mapper.writeValueAsString(fresh), card.get("schema_hash"),
                    String.valueOf(tc.get("scenario")).replaceAll("（schema 变更[^）]*）", ""), caseId);
        } catch (Exception e) {
            return new GenResult(0, false, List.of("修复失败: " + e.getMessage()));
        }
        List<String> notes = new ArrayList<>();
        notes.add(keptKeys.isEmpty()
                ? "已按当前 schema 补齐参数"
                : "已按当前 schema 补齐缺失参数，保留旧参数里人工写过的值（test/TEST 一类占位值按规则更新）："
                        + String.join("、", keptKeys));
        return new GenResult(1, false, notes);
    }

    private int saveTemplateCase(String targetType, long targetId, String name, String scenario, Object params) {
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM test_cases WHERE target_type=? AND target_id=? AND name=?",
                Integer.class, targetType, targetId, name);
        if (dup != null && dup > 0) return 0;
        try {
            // 模板用例默认带 L1 断言（2xx 即通过），可直接使用
            List<Map<String, Object>> assertions = List.of(
                    Map.of("level", 1, "type", "status", "op", "range", "expected", "200-299"));
            jdbc.update("INSERT INTO test_cases(target_type, target_id, name, scenario, params, expect, origin, assertions, base_schema_hash, status) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    targetType, targetId, name, scenario, mapper.writeValueAsString(params), "",
                    "template", mapper.writeValueAsString(assertions), currentSchemaHash(targetType, targetId), "approved");
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static Object extremeValue(String name, Map<String, Object> ps) {
        String t = String.valueOf(ps.getOrDefault("type", "string"));
        return switch (t) {
            case "integer" -> Integer.MAX_VALUE;
            case "number" -> Double.MAX_VALUE;
            case "boolean" -> true;
            default -> "A".repeat(1000);
        };
    }

    /** 空串 / 空对象 / 空数组都算"没填"，修复时用新骨架覆盖，不把老骨架的人工值当有效值保留 */
    private static boolean blankValue(Object v) {
        if (v == null) return true;
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        if (v instanceof List<?> l) return l.isEmpty();
        return String.valueOf(v).isEmpty();
    }

    /** 嵌套结构逐层合并：人工填过的叶子保留，人工没填的叶子用新样例补齐（整段覆盖会丢兄弟字段） */
    private static Object mergeKept(Object freshVal, Object oldVal) {
        if (freshVal instanceof Map<?, ?> fv && oldVal instanceof Map<?, ?> ov) {
            Map<String, Object> out = new LinkedHashMap<>();
            fv.forEach((k, v) -> out.put(String.valueOf(k), v));
            for (Map.Entry<?, ?> en : ov.entrySet()) {
                Object child = en.getValue();
                if (blankValue(child)) continue;
                String key = String.valueOf(en.getKey());
                out.put(key, mergeKept(out.get(key), child));
            }
            return out;
        }
        if (blankValue(oldVal)) return freshVal;
        // 老骨架里的机器占位值不是人工意图，规则/学习能给出更可用的值时按新值更新
        if (isPlaceholder(oldVal) && !isPlaceholder(freshVal) && !blankValue(freshVal)) return freshVal;
        return oldVal;
    }

    private static final Set<String> PLACEHOLDERS =
            Set.of("test", "TEST", "2026-01-01", "2026-01-01 00:00:00");

    private static boolean isPlaceholder(Object v) {
        return v instanceof String s && PLACEHOLDERS.contains(s);
    }

    /** 历史用例的 body 段按 Java 形参名嵌套（{"requestModel":{…}}），先拆一层再与摊平后的 DTO 字段对齐 */
    @SuppressWarnings("unchecked")
    private static Object unwrapLegacyBody(Object oldSec, Map<String, Object> props, Set<String> bodyKeys) {
        if (!(oldSec instanceof Map<?, ?> om)) return oldSec;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : om.entrySet()) {
            String key = String.valueOf(en.getKey());
            Object v = en.getValue();
            if (v instanceof Map<?, ?> inner && !bodyKeys.contains(key) && props.containsKey(key)) {
                inner.forEach((k2, v2) -> out.put(String.valueOf(k2), v2));
            } else {
                out.put(key, v);
            }
        }
        return out;
    }
}
