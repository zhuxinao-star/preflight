package com.preflight.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 请求体形状（第九轮）：接口卡的 body 段按"实际发出去的 JSON"扁平存放，
 * 而不是按 Java 形参名嵌套——后端 @RequestBody 只认 DTO 的字段名。
 *
 * 用例生成、测试台预填、cURL 复制三处都从这里取同一份骨架，避免"页面显示一套、发出去另一套"。
 */
public final class BodyShape {

    private BodyShape() {}

    /** 从入参 schema 的 properties 生成 body 段的 JSON 形状（单一 DTO 形参 → 摊平到顶层） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> flatten(Map<String, Object> props,
                                              BiFunction<String, Map<String, Object>, Object> leaf, boolean blank) {
        List<Map.Entry<String, Map<String, Object>>> bodies = new ArrayList<>();
        for (Map.Entry<String, Object> en : props.entrySet()) {
            if (!(en.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> ps = (Map<String, Object>) raw;
            if ("body".equals(String.valueOf(ps.getOrDefault("in", "query")))) {
                bodies.add(Map.entry(en.getKey(), ps));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (bodies.isEmpty()) return out;
        if (bodies.size() == 1) {
            Object sk = skeletonOf(bodies.get(0).getKey(), bodies.get(0).getValue(), leaf, blank);
            if (sk instanceof Map<?, ?> m) {
                ((Map<String, Object>) m).forEach(out::put);
                return out;
            }
            out.put(bodies.get(0).getKey(), sk);
            return out;
        }
        for (Map.Entry<String, Map<String, Object>> b : bodies) {
            out.put(b.getKey(), skeletonOf(b.getKey(), b.getValue(), leaf, blank));
        }
        return out;
    }

    /** body 段合法的顶层键名集合（用例修复时判断"人工值还在不在 schema 里"） */
    public static Set<String> topLevelKeys(Map<String, Object> props) {
        return flatten(props, (n, ps) -> "", false).keySet();
    }

    /** 单个属性节点的递归骨架：对象展开字段、数组给一个代表元素、自由结构留空对象待补填 */
    @SuppressWarnings("unchecked")
    public static Object skeletonOf(String name, Map<String, Object> ps, BiFunction<String, Map<String, Object>, Object> leaf, boolean blank) {
        String type = String.valueOf(ps.getOrDefault("type", "string"));
        if ("array".equals(type)) {
            Object items = ps.get("items");
            Object one = items instanceof Map<?, ?> im
                    ? skeletonOf(name, (Map<String, Object>) im, leaf, false)
                    : "test";
            List<Object> list = new ArrayList<>();
            list.add(one);
            return list;
        }
        if (ps.get("properties") instanceof Map<?, ?> raw) {
            Map<String, Object> props = (Map<String, Object>) raw;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> en : props.entrySet()) {
                if (!(en.getValue() instanceof Map<?, ?> sub)) continue;
                out.put(en.getKey(), skeletonOf(en.getKey(), (Map<String, Object>) sub, leaf, blank));
            }
            return out;
        }
        if ("object".equals(type)) return new LinkedHashMap<String, Object>();
        if (blank && !Boolean.TRUE.equals(ps.get("required"))) return "string".equals(type) ? "" : null;
        return leaf.apply(name, ps);
    }

    /** 绑定功能号的取值器：用例生成、测试台预填、cURL 三处都从这里拿，保证页面显示与真实发包一致 */
    public static BiFunction<String, Map<String, Object>, Object> sampler(Optional<String> functionNo) {
        return (name, ps) -> sampleValue(name, ps, functionNo);
    }

    /**
     * 带上下文的取值：先认人工/学习给的 default，再认跨项目通用的报文头规则（op_code、功能号类字段），
     * <b>只有一条规则都没命中的字段才交给按名字兜底的采样器</b>。
     *
     * <p>命中"这一格要等于本次功能号"而这次没有功能号时，答案是<b>空串</b>，不是采样器按名字编出来的
     * "TEST"／当天日期（第 48 轮口径：未配置是一种结论，不是"可以现编一个值"的许可）。
     */
    public static Object sampleValue(String name, Map<String, Object> ps, Optional<String> functionNo) {
        if (ps != null && ps.get("default") != null) return ps.get("default");
        DefaultValueRules.Rule r = DefaultValueRules.rule(name, functionNo);
        return switch (r.kind()) {
            case NOT_MATCHED -> sampleValue(name, ps);
            // 命中规则：有码给码，这次没码给空串——绝不落到下面那个按名字编值的采样器
            default -> r.value().orElse("");
        };
    }

    /** 按字段名给一个像样的默认值：编号/编码类填 TEST，页码类填 1，时间类填当天，布尔填 false */
    public static Object sampleValue(String name, Map<String, Object> ps) {
        if (ps.get("default") != null) return ps.get("default");
        String n = name == null ? "" : name.toLowerCase();
        String t = String.valueOf(ps.getOrDefault("type", "string"));
        if (n.startsWith("page") || n.contains("offset") || n.contains("pageindex") || n.contains("pageno")) return "1";
        if (n.endsWith("time") || n.endsWith("date") || n.endsWith("_bh") || n.endsWith("no")) {
            if (!"integer".equals(t) && !"number".equals(t)) {
                return n.endsWith("time") ? "2026-01-01 00:00:00" : "2026-01-01";
            }
        }
        if (t.equals("integer")) return 1;
        if (t.equals("number")) return 1.0;
        if (t.equals("boolean")) return false;
        if (n.endsWith("code") || n.endsWith("id") || n.endsWith("type") || n.contains("code")) return "TEST";
        return "test";
    }
}
