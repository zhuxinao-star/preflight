package com.preflight.parser;

import java.util.List;

/** 解析产物：一条 = 一个接口/SQL/数据对象候选。M0 为 PoC 结构，M1 扩展为接口卡。 */
public record ExtractedUnit(
        String kind,          // HTTP_ENDPOINT | SQL_STATEMENT | DDL | PROC_CALL | FE_API_CALL
        String name,
        String httpMethod,    // GET/POST/... 或 null
        String path,
        List<ParamDef> params,
        List<String> tables,
        String sourceFile,
        int lineStart,
        int lineEnd,
        List<String> warnings,
        String snippet,       // 原文片段（展示用）
        String executableSql, // 可执行 SQL 模板（#{x}→? 绑定占位；DB 直测落库用，非 SQL 语句为 null）
        String databaseId,    // MyBatis databaseId（多库兼容变体，无则 null）
        String dynamicBody) {  // MyBatis 动态 SQL 原文（保留 <if>/<choose> 等标签与 CDATA，第 14 轮；非动态语句为 null）

    public record ParamDef(String name, String type, String in, boolean required, String defaultValue) {}

    /** 无动态原文的解析器（HTTP 端点、DDL、其它 SQL 源）沿用旧 13 参构造 */
    public ExtractedUnit(String kind, String name, String httpMethod, String path, List<ParamDef> params,
                         List<String> tables, String sourceFile, int lineStart, int lineEnd, List<String> warnings,
                         String snippet, String executableSql, String databaseId) {
        this(kind, name, httpMethod, path, params, tables, sourceFile, lineStart, lineEnd, warnings,
                snippet, executableSql, databaseId, null);
    }
}
