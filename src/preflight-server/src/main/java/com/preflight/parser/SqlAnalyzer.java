package com.preflight.parser;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;

/** SQL 分析：JSqlParser 解析校验 + 提取涉及表名。解析失败不抛出，返回 error 供上游标红。 */
public final class SqlAnalyzer {

    public record SqlInfo(List<String> tables, String statementType, String error) {}

    private SqlAnalyzer() {}

    public static SqlInfo analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return new SqlInfo(List.of(), null, "空 SQL");
        }
        try {
            Statement st = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder finder = new TablesNamesFinder();
            List<String> tables = new ArrayList<>(finder.getTables(st));
            return new SqlInfo(tables, st.getClass().getSimpleName(), null);
        } catch (Exception e) {
            return new SqlInfo(List.of(), null, e.getMessage());
        }
    }
}
