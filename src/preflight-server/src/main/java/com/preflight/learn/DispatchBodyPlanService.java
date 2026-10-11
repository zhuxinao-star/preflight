package com.preflight.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.parser.DefaultValueRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「这条请求该发什么值」的读取期单点（第四十六轮）。
 *
 * <p>此前的缺口是两件事没接上：跨项目默认值规则表 {@link DefaultValueRules} 只喂给了字典里的
 * <b>业务参数</b>，报文头那一份清单（{@code hdrTemplate} / {@code hdrFields}）从学习期一路到界面
 * 谁也没拿它算过，于是 {@code opCode}／{@code opRole}／{@code serverId} 恒为空串；而测试台拼请求体
 * 时的业务键<b>只来自服务字典</b>，这张卡自己按 {@code @RequestBody} 学出来的入参（自由结构类根上的
 * 那些键）没有任何通路进骨架——用户看到的正是"参数学到了、请求体里却没有"。
 *
 * <p>判据只有一条，两种框架、所有项目共用：
 * <b>框架自己声明的值优先 → 规则表补空 → 都没有就是空串</b>。学习出来的字面量（金证信封里
 * {@code OP_CODE=1}、{@code OP_WAY=1} 那种是从前端 ka.js 逐字抄来的）不会被规则表覆盖；
 * {@code $code} 占位与"功能号类"键在读取期换成<b>本次</b>功能号，所以一张卡服务多少个码都不会串号。
 *
 * <p>刻意读取期现算、绝不落库：规则值写进 {@code params_schema} 会污染 schema 哈希、把历史用例
 * 刷成待复核（第 11 轮就定过这条），更重要的是<b>老仓库不必重学、点一次【测试台】就生效</b>。
 *
 * <p>只出请求要发的东西：出参（{@code CodeChainService} 里证据写「语句的列/别名」那一批）不是入参，
 * 这一层不认、也不往请求体里塞。
 */
@Service
public class DispatchBodyPlanService {

    /** 一次请求体最多铺多少个业务键（与界面「仅显示前 60 个」同量级，防止异常卡刷爆响应） */
    private static final int KEY_CAP = 80;
    private static final int DEPTH_CAP = 5;

    /**
     * 一格值的出处（界面与登录草稿的留空点名共用这套字符串）：
     * schema=学习/人工给的默认值，framework=框架自己声明的字面量，rule=跨项目规则表的常量，
     * rule-code / framework-code=这一格按规矩要等于<b>本次功能号</b>且这次拿得到，
     * rule-code-missing / framework-code-missing=<b>同样要求功能号，但这次没有</b>：
     * 值照空串发（第 46 轮口径），但绝不退成 none——"未配置"被当成"没这条规则"，
     * 下游就会按字段名现编一个值（func_no → 日期／TEST）发出去，那比留空更坏。
     */
    public static final String FROM_SCHEMA = "schema";
    public static final String FROM_FRAMEWORK = "framework";
    public static final String FROM_FRAMEWORK_CODE = "framework-code";
    public static final String FROM_FRAMEWORK_CODE_MISSING = "framework-code-missing";
    public static final String FROM_RULE = "rule";
    public static final String FROM_RULE_CODE = "rule-code";
    public static final String FROM_RULE_CODE_MISSING = "rule-code-missing";
    public static final String FROM_NONE = "none";

    /** 规则表的结论 → 出处标签：三种结论分得开，"要功能号但这次没有"那一种不是 none（服务字典参数表与请求体计划共用这一份标签） */
    public static String ruleFrom(DefaultValueRules.Rule r) {
        return switch (r.kind()) {
            case CONST -> FROM_RULE;
            case CODE -> r.filled() ? FROM_RULE_CODE : FROM_RULE_CODE_MISSING;
            case NOT_MATCHED -> FROM_NONE;
        };
    }

    /**
     * 这一格真的拿到值了吗。<b>只有 {@link #FROM_NONE} 和两种 {@code *-code-missing} 不算</b>——
     * 后面那两种是"按规矩要等于本次功能号，可这次没有功能号"，它的值是空串，但结论必须与"没这条规矩"分开摆。
     * 下游（登录草稿的记账、cURL、用例）判"有没有值"一律走这里，不许各自写一遍 {@code != "none"}。
     */
    public static boolean givesValue(String from) {
        return !FROM_NONE.equals(from)
                && !FROM_RULE_CODE_MISSING.equals(from)
                && !FROM_FRAMEWORK_CODE_MISSING.equals(from);
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DispatchBodyPlanService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 这张卡的请求体计划。
     *
     * @param path 卡片自己的路径（一码两条路径时各读各的声明，不共用一份；缺省退回字典的代表路径）
     * @param code 本次功能号／服务码——规则表里"等于本次业务代码"的那几个键拿它填。
     *             空白时从路径末段现推；推不出来就是「本次没有功能号」，这是一条要摆出来的结论，
     *             不是一个可以悄悄换成空串的值（{@link #FROM_RULE_CODE_MISSING}）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> plan(long projectId, String path, String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("headers", List.of());
        out.put("bodyKeys", List.of());
        Map<String, Object> d = dispatchOf(projectId, path);
        if (d.isEmpty()) {
            out.put("note", "这张卡上没有分发标记（还没学过、或被人工编辑过所以学习没覆盖），"
                    + "请求体照旧只按卡片自己的字段拼");
            return out;
        }
        String given = code == null ? "" : code.trim();
        Optional<String> fn = given.isEmpty() ? DefaultValueRules.functionNo(path) : Optional.of(given);
        String hdrKey = str(d.get("hdrKey"));
        String dataKey = str(d.get("dataKey"));
        out.put("found", true);
        out.put("mode", str(d.get("mode")));
        out.put("framework", str(d.get("framework")));
        out.put("hdrKey", hdrKey);
        out.put("dataKey", dataKey);
        out.put("functionNo", fn.orElse(""));
        out.put("functionNoKnown", fn.isPresent());
        out.put("headers", headers(d, fn));
        List<Map<String, Object>> keys = new ArrayList<>();
        // 只有「一码一条真实路径」那种框架，卡上学到的 body 键才等于这条请求自己的传输形状；
        // 信封式框架的卡是入口卡，它的字段属于别的服务码，塞进来就是假契约。
        // 点路径一律相对<b>请求体根</b>给（所以界面要往根上铺，不是往 dataKey 那个子对象里铺）。
        if ("call-path".equals(str(d.get("mode")))) {
            keys.addAll(bodyKeys(schemaOf(projectId, path), hdrKey, dataKey, fn));
        }
        out.put("bodyKeys", keys);
        return out;
    }

    /**
     * 一张卡的 schema 展开成业务键（点路径 + 值 + 出处），第 46 轮那一条判据只写在这里。
     *
     * <p>第四十八轮把这道题开放给登录草稿用：登录卡没有分发标记，但它的入参照样是
     * {@code @RequestBody} 学出来的那份形状，展开规则不必也不能再写第二遍。
     *
     * @param hdrKey 报文头那一段的键名（没有就传空串：登录卡的请求体里根本没有信封）
     * @param fn     本次功能号，规则表里"等于本次业务代码"的那些键拿它填；
     *               这个出口压根没有「本次」这个概念就传 {@link Optional#empty()}（登录草稿那样），
     *               空值会被逐格点名叫「本次没有功能号」，不会被当成"这条规则不存在"
     */
    public List<Map<String, Object>> bodyKeys(Map<String, Object> schema, String hdrKey, String dataKey,
                                              Optional<String> fn) {
        List<Map<String, Object>> keys = new ArrayList<>();
        Map<String, Object> body = bodyNode(schema);
        if (body != null) walk(propsOf(body), "", 0, true, hdrKey, dataKey, fn, keys);
        return keys;
    }

    /** 报文头：框架声明的值不动，空值才由规则表补，功能号类键在读取期换成本次码 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> headers(Map<String, Object> d, Optional<String> fn) {
        Map<String, Object> tpl = d.get("hdrTemplate") instanceof Map<?, ?> t
                ? (Map<String, Object>) t : Map.of();
        List<Map<String, Object>> learned = d.get("hdrFields") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        LinkedHashSet<String> order = new LinkedHashSet<>();
        Map<String, Map<String, Object>> meta = new LinkedHashMap<>();
        for (Map<String, Object> f : learned) {
            String k = str(f.get("key"));
            if (k.isEmpty()) continue;
            order.add(k);
            meta.put(k, f);
        }
        order.addAll(tpl.keySet());

        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : order) {
            Object declared = tpl.get(key);
            Map<String, Object> f = meta.getOrDefault(key, Map.of());
            String value;
            String from;
            if (DefaultValueRules.CODE_TEMPLATE.equals(declared)) {
                // 框架声明了这一格必须等于本次功能号：本次拿得到就换值，拿不到就说清拿不到，
                // 而不是留一个看着像"没这条规矩"的空串
                value = fn.orElse("");
                from = fn.isPresent() ? FROM_FRAMEWORK_CODE : FROM_FRAMEWORK_CODE_MISSING;
            } else if (declared != null && !String.valueOf(declared).isBlank()) {
                value = String.valueOf(declared);
                from = FROM_FRAMEWORK;         // 这个框架自己写死的值（比别家信封的默认值更有权威）
            } else {
                DefaultValueRules.Rule r = DefaultValueRules.rule(key, fn);
                value = r.value().orElse("");
                from = ruleFrom(r);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("type", f.getOrDefault("type", "string"));
            row.put("label", f.getOrDefault("label", ""));
            row.put("via", f.getOrDefault("via", ""));
            row.put("value", value);
            row.put("from", from);
            out.add(row);
        }
        return out;
    }

    /** 卡片自己学到的入参（DTO 展开 + 按键取值反推）逐叶展开成点路径，值走同一条判据 */
    @SuppressWarnings("unchecked")
    private void walk(Map<String, Object> props, String prefix, int depth, boolean top,
                      String hdrKey, String dataKey, Optional<String> fn, List<Map<String, Object>> out) {
        if (depth > DEPTH_CAP) return;
        for (Map.Entry<String, Object> en : props.entrySet()) {
            String key = en.getKey();
            if (out.size() >= KEY_CAP) return;
            if (key.startsWith("_") || !(en.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> child = (Map<String, Object>) raw;
            // 报文头那一段由 headers() 单独给（值与出处都要看得见），不在业务键里重复一遍
            if (top && key.equals(hdrKey)) continue;
            String path = prefix + key;
            String type = str(child.get("type"));
            Map<String, Object> sub = propsOf(child);
            Map<String, Object> items = child.get("items") instanceof Map<?, ?> im
                    ? (Map<String, Object>) im : null;
            if ("object".equals(type) && !sub.isEmpty()) {
                walk(sub, path + ".", depth + 1, false, hdrKey, dataKey, fn, out);
            } else if ("array".equals(type) && items != null && !propsOf(items).isEmpty()) {
                walk(propsOf(items), path + "[].", depth + 1, false, hdrKey, dataKey, fn, out);
            } else if (top && key.equals(dataKey)) {
                // 业务负载那一格本身（如 req_comm_data）不是一个入参：它没学到子键时也不能把自己铺成一个空键，
                // 否则请求体里就成了 req_comm_data.req_comm_data——容器套自己，服务端只读到一个空串。
            } else {
                out.add(leaf("array".equals(type) ? path + "[]" : path, child, fn));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> leaf(String path, Map<String, Object> node, Optional<String> fn) {
        String seg = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        if (seg.endsWith("[]")) seg = seg.substring(0, seg.length() - 2);
        Map<String, Object> row = new LinkedHashMap<>();
        String name = node.get("name") == null ? seg : String.valueOf(node.get("name"));
        row.put("path", path);
        row.put("key", name);
        row.put("name", name);
        row.put("type", node.getOrDefault("type", "string"));
        row.put("label", node.getOrDefault("description", ""));
        // 键是长在请求体上还是 URL 上（第 48 轮登录草稿要据此把 query 参数拼进路径，不能塞进 body）
        row.put("in", node.getOrDefault("in", ""));
        Object via = node.get("via");
        row.put("via", via == null ? ("DTO " + node.getOrDefault("javaType", "")) : via);
        String value;
        String from;
        if (DefaultValueRules.hasOwnValue(node)) {
            value = String.valueOf(node.get("default"));
            from = FROM_SCHEMA;                // 学习或人工已经给了值
        } else {
            // 先问规则表：命中功能号规则而这次没有码，是一种要摆出来的结论，不是"没规则"
            DefaultValueRules.Rule r = DefaultValueRules.rule(seg, fn);
            value = r.value().orElse("");
            from = ruleFrom(r);
        }
        row.put("def", value);
        row.put("defFrom", from);
        return row;
    }

    /** 卡片 schema 里代表请求体的那个节点（Spring 的 @RequestBody 参数名不在线上传输形状上） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyNode(Map<String, Object> schema) {
        Map<String, Object> props = propsOf(schema);
        for (Object v : props.values()) {
            if (v instanceof Map<?, ?> m && "body".equals(m.get("in"))
                    && "object".equals(String.valueOf(m.get("type")))) {
                return (Map<String, Object>) m;
            }
        }
        return "object".equals(String.valueOf(schema.get("type"))) ? schema : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propsOf(Map<String, Object> node) {
        if (node == null) return Map.of();
        return node.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatchOf(long projectId, String path) {
        Object schema = cardSchema(projectId, path);
        if (schema == null) return Map.of();
        try {
            Map<String, Object> m = mapper.readValue(String.valueOf(schema), Map.class);
            return m.get("dispatch") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private Object cardSchema(long projectId, String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) return null;
        for (Map<String, Object> c : jdbc.queryForList(
                "SELECT params_schema FROM interfaces WHERE project_id=? AND (path=? OR path LIKE ?) "
                        + "ORDER BY length(path)", projectId, path, "%/" + path.substring(1))) {
            if (c.get("params_schema") != null) return c.get("params_schema");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaOf(long projectId, String path) {
        Object schema = cardSchema(projectId, path);
        if (schema == null) return Map.of();
        try {
            return mapper.readValue(String.valueOf(schema), Map.class);
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
