package com.preflight.learn;

import com.preflight.parser.SqlDynamicAssembler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 14 轮：把"按参数把 MyBatis 动态 SQL 拼成可执行语句"这件事收在一个地方。
 * 数据事实只有一个来源——db_objects.dynamic_body（学习时存的语句内层原文）；
 * 片段表与参数表都在读取期现算，不落库（所以重新学习/换参数不会留旧结论）。
 */
@Service
public class DynamicSqlService {

    private final JdbcTemplate jdbc;

    public DynamicSqlService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 拼装结果 + 这张卡的静态信息，供界面直接渲染。
     *  named 是"把模板里的 ? / DOLLAR 还原成源码原样的 #{x} / ${x}"（第 23 轮）：读取期现算的展示视图，
     *  不落库、也不参与执行——执行发的仍然是与之逐位等价的 sqlTemplate。 */
    public record Composition(SqlDynamicAssembler.Result result, String name, String sqlTemplate, boolean hasDynamic,
                              SqlDynamicAssembler.Named named) {}

    /** inputs=参数名→值（留空即不填）；overrides=片段 id→人工强制包含/排除 */
    public Composition compose(long dbObjectId, Map<String, String> inputs, Map<Integer, Boolean> overrides) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, sql_template, dynamic_body FROM db_objects WHERE id=?", dbObjectId);
        if (rows.isEmpty()) {
            return new Composition(new SqlDynamicAssembler.Result(List.of(), List.of(), null, List.of(),
                    List.of("数据对象卡 #" + dbObjectId + " 不存在"), List.of()), null, null, false,
                    new SqlDynamicAssembler.Named(null, List.of(), List.of(), null));
        }
        Map<String, Object> row = rows.get(0);
        String body = (String) row.get("dynamic_body");
        String template = (String) row.get("sql_template");
        SqlDynamicAssembler.Named named = SqlDynamicAssembler.restoreNames(template, body);
        if (body == null || body.isBlank()) {
            // 没有原文：仍给出拍平模板，界面据此说明"这张卡只能人工补条件"
            return new Composition(new SqlDynamicAssembler.Result(List.of(), List.of(), template, List.of(),
                    List.of(), List.of("这张卡没有动态 SQL 原文（老知识包或纯静态语句），只能按拍平模板人工补条件；重新学习后可按参数拼装")),
                    (String) row.get("name"), template, false, named);
        }
        return new Composition(SqlDynamicAssembler.compose(body, inputs, overrides),
                (String) row.get("name"), template, true, named);
    }

    /** 请求体里的松散 Map → 拼装引擎要的两张强类型表（非字符串值按字符串取，认不出的键忽略） */
    public static Map<String, String> inputsOf(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    public static Map<Integer, Boolean> overridesOf(Object raw) {
        Map<Integer, Boolean> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                try {
                    out.put(Integer.valueOf(String.valueOf(e.getKey())), Boolean.valueOf(String.valueOf(e.getValue())));
                } catch (NumberFormatException ignored) {
                    // 前端传了非片段 id 的键：忽略，不猜
                }
            }
        }
        return out;
    }
}
