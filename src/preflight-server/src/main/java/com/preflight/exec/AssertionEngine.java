package com.preflight.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 断言引擎（04-补齐设计 §3.3）：
 * L1 状态码/耗时；L2 JSONPath 等值/包含/正则；L4 DB 回读断言（HTTP+DB 双引擎组合拳，
 * SQL 支持引用响应上下文 {{ret.data.id}}，以回滚方式只读执行）。
 * 任一断言 fail 即本次执行 fail。
 */
public final class AssertionEngine {

    public record AssertionResult(int level, String type, String expr, String expected,
                                  String actual, boolean pass, String error) {}

    private AssertionEngine() {}

    /** 断言上下文：HTTP 响应 + 可用的 DB 执行器（L4 用） */
    public record Context(int statusCode, long latencyMs, JsonNode body, DbExecutorService db, Long envId) {}

    @SuppressWarnings("unchecked")
    public static List<AssertionResult> evaluate(List<Map<String, Object>> assertions, Context ctx, ObjectMapper mapper) {
        List<AssertionResult> results = new ArrayList<>();
        if (assertions == null) return results;
        for (Map<String, Object> a : assertions) {
            int level = a.get("level") == null ? 1 : ((Number) a.get("level")).intValue();
            String type = String.valueOf(a.getOrDefault("type", "status"));
            String expr = a.get("expr") == null ? null : String.valueOf(a.get("expr"));
            String expected = a.get("expected") == null ? null : String.valueOf(a.get("expected"));
            String op = String.valueOf(a.getOrDefault("op", "eq"));
            try {
                switch (type) {
                    case "status" -> results.add(new AssertionResult(level, type, null, expected,
                            String.valueOf(ctx.statusCode()), compare(String.valueOf(ctx.statusCode()), op, expected), null));
                    case "latency" -> results.add(new AssertionResult(level, type, null, expected,
                            ctx.latencyMs() + "ms", compare(String.valueOf(ctx.latencyMs()), op, expected), null));
                    case "jsonpath" -> {
                        String actual = jsonPath(ctx.body(), expr);
                        results.add(new AssertionResult(level, type, expr, expected, actual,
                                compare(actual, op, expected), actual == null ? "路径无值" : null));
                    }
                    case "db" -> {
                        if (ctx.db() == null || ctx.envId() == null) {
                            results.add(new AssertionResult(level, type, expr, expected, null, false, "无可用数据库环境"));
                        } else {
                            // R6：{{ret.x}} 引号外 → ? 绑定参数（防注入）；引号内 → 转义内联（语义直观）
                            AssertionEngine.SqlBinds conv = convertBinds(expr, ctx.body());
                            DbExecutorService.DbResult r = ctx.db().runSql(ctx.envId(), conv.sql(), conv.binds(), false, false);
                            if (!r.ok()) {
                                results.add(new AssertionResult(level, type, expr, expected, null, false, r.error()));
                            } else {
                                String actual = (r.rows() == null || r.rows().isEmpty()
                                        || r.rows().get(0).isEmpty()) ? null : String.valueOf(r.rows().get(0).get(0));
                                results.add(new AssertionResult(level, type, expr, expected, actual,
                                        compare(actual, op, expected), null));
                            }
                        }
                    }
                    default -> results.add(new AssertionResult(level, type, expr, expected, null, false, "未知断言类型"));
                }
            } catch (Exception e) {
                StackTraceElement st = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
                results.add(new AssertionResult(level, type, expr, expected, null, false,
                        e.getClass().getSimpleName() + ": " + e.getMessage() + " @ " + st));
            }
        }
        return results;
    }

    /** {{ret.x}} 转换结果：SQL（引号外为 ? 占位）+ 绑定参数 */
    public record SqlBinds(String sql, List<Object> binds) {}

    /**
     * 逐字符扫描：引号外的 {{ret.x}} 转为 ? 占位并入参（防注入）；
     * 引号内的 {{ret.x}} 转义后内联（' → ''），保持测试员书写直觉。
     */
    public static SqlBinds convertBinds(String expr, JsonNode body) {
        StringBuilder sb = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{\\{ret\\.([^}]+)}}");
        boolean inQuote = false;
        int i = 0;
        while (i < expr.length()) {
            char ch = expr.charAt(i);
            if (ch == '\'') {
                inQuote = !inQuote;
                sb.append(ch);
                i++;
                continue;
            }
            if (ch == '{' && i + 1 < expr.length() && expr.charAt(i + 1) == '{') {
                java.util.regex.Matcher m = p.matcher(expr).region(i, expr.length());
                if (m.lookingAt()) {
                    String v = jsonPath(body, "$." + m.group(1));
                    if (v == null) v = "";
                    if (inQuote) {
                        sb.append(v.replace("'", "''")); // 引号内：转义内联
                    } else {
                        sb.append('?');
                        binds.add(v);
                    }
                    i = m.end();
                    continue;
                }
            }
            sb.append(ch);
            i++;
        }
        return new SqlBinds(sb.toString(), binds);
    }

    /** 断言/SQL 中的 {{ret.xxx}} 引用响应 JSON 上下文 */
    public static String substitute(String tpl, JsonNode body) {
        if (tpl == null || body == null || !tpl.contains("{{")) return tpl;
        String out = tpl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{ret\\.([^}]+)}}").matcher(tpl);
        while (m.find()) {
            String v = jsonPath(body, "$." + m.group(1));
            out = out.replace(m.group(0), v == null ? "" : v);
        }
        return out;
    }

    /** 简化 JSON 路径：$.data.list */
    public static String jsonPath(JsonNode node, String path) {
        if (node == null || path == null) return null;
        String p = path.startsWith("$.") ? path.substring(2) : path;
        if (p.isBlank()) return node.isValueNode() ? node.asText() : node.toString();
        JsonNode cur = node;
        for (String seg : p.split("\\.")) {
            if (cur == null) return null;
            // 数组下标：list[0]
            java.util.regex.Matcher am = Pattern.compile("^(\\w+)\\[(\\d+)]$").matcher(seg);
            if (am.matches()) {
                cur = cur.path(am.group(1)).path(Integer.parseInt(am.group(2)));
            } else {
                cur = cur.path(seg);
            }
        }
        if (cur.isMissingNode() || cur.isNull()) return null;
        return cur.isValueNode() ? cur.asText() : cur.toString();
    }

    private static boolean compare(String actual, String op, String expected) {
        if ("notempty".equals(op)) return actual != null && !actual.isBlank();
        if (actual == null) return false;
        return switch (op) {
            case "eq" -> eq(actual, expected);
            case "ne" -> !eq(actual, expected);
            case "contains" -> actual.contains(expected == null ? "" : expected);
            case "regex" -> Pattern.compile(expected == null ? "" : expected).matcher(actual).find();
            case "gt", "lt", "ge", "le" -> numCompare(actual, op, expected);
            case "range" -> {
                if (expected != null && expected.contains("-")) {
                    String[] parts = expected.split("-", 2);
                    try {
                        int v = Integer.parseInt(actual.trim());
                        yield v >= Integer.parseInt(parts[0].trim()) && v <= Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException e) {
                        yield false;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    private static boolean eq(String actual, String expected) {
        if (actual.equals(expected)) return true;
        try {
            return Double.parseDouble(actual.trim()) == Double.parseDouble(String.valueOf(expected).trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean numCompare(String actual, String op, String expected) {
        try {
            double a = Double.parseDouble(actual.trim());
            double e = Double.parseDouble(String.valueOf(expected).replaceAll("[^0-9.\\-]", "").trim());
            return switch (op) {
                case "gt" -> a > e;
                case "lt" -> a < e;
                case "ge" -> a >= e;
                case "le" -> a <= e;
                default -> false;
            };
        } catch (Exception ex) {
            return false;
        }
    }

    /* ==================== 基线对比（04-补齐 §2.4，纯工程零 LLM） ==================== */

    /** JSON 深度 diff，ignore 中的字段名（按段匹配）被跳过 */
    public static List<Map<String, String>> diffJson(JsonNode base, JsonNode cur, Set<String> ignore, String path) {
        List<Map<String, String>> out = new ArrayList<>();
        if (base == null || base.isMissingNode() || base.isNull()) {
            if (cur != null && !cur.isMissingNode() && !cur.isNull()) out.add(entry(path, "(无)", textOf(cur)));
            return out;
        }
        if (cur == null || cur.isMissingNode() || cur.isNull()) {
            out.add(entry(path, textOf(base), "(无)"));
            return out;
        }
        if (base.isObject() && cur.isObject()) {
            Set<String> fields = new java.util.LinkedHashSet<>();
            base.fieldNames().forEachRemaining(fields::add);
            cur.fieldNames().forEachRemaining(fields::add);
            for (String f : fields) {
                if (ignore.contains(f)) continue;
                out.addAll(diffJson(base.path(f), cur.path(f), ignore, path.isEmpty() ? f : path + "." + f));
            }
        } else if (base.isArray() && cur.isArray()) {
            int n = Math.max(base.size(), cur.size());
            for (int i = 0; i < n; i++) {
                out.addAll(diffJson(base.path(i), cur.path(i), ignore, path + "[" + i + "]"));
            }
        } else {
            String b = textOf(base), c = textOf(cur);
            if (!b.equals(c)) out.add(entry(path, b, c));
        }
        return out;
    }

    private static String textOf(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? "(无)" : (n.isValueNode() ? n.asText() : n.toString());
    }

    private static Map<String, String> entry(String path, String base, String cur) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("baseline", base);
        m.put("current", cur);
        return m;
    }
}
