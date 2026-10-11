package com.preflight.system;

import com.preflight.common.AuditService;
import com.preflight.exec.DbExecutorService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 真实库元数据反哺（04-补齐 §4.2）：按库类型拉取表/列注释、类型长度、可空性，
 * 缓存到 table_meta，供动态表单占位与用例边界生成使用（六库字典视图差异在此收敛）。
 *
 * 第廿八轮多了一个入口：{@code /pull-batch} 一次拉多张表。③ 面板要说"这一列是什么意思"就得有列注释，
 * 而注释只有拉过元数据才有——一张一张点【表结构】不算解决，同一趟连接一条字典视图 SELECT 才算。
 * 语句形状与单表完全一致（只是过滤条件换成 IN），依旧只读连接、白名单表名、写死的 SELECT。
 */
@RestController
@RequestMapping("/api/db-metadata")
public class DbMetadataController {

    /** 一趟最多拉这么多张表：一张卡涉及的表本来就少，多了说明调用方在批量灌库 */
    static final int MAX_TABLES_PER_BATCH = 16;
    /** 字典视图上的查询超时（秒）：不该慢，但别让它挂着不返回 */
    private static final int META_TIMEOUT_SEC = 15;

    /**
     * 表名白名单（拼进语句前必须过这一关）：{@code 表名} / {@code 属主.表名} / {@code 库.属主.表名}，
     * 每段以字母或下划线开头。比旧的 {@code [A-Za-z0-9_.$]+} 更严——旧的那条放过 {@code 1abc} 和连点。
     */
    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}");

    private final JdbcTemplate jdbc;
    private final DbExecutorService dbExecutor;
    private final com.preflight.common.CryptoService crypto;
    private final AuditService audit;

    public DbMetadataController(JdbcTemplate jdbc, DbExecutorService dbExecutor,
                                com.preflight.common.CryptoService crypto, AuditService audit) {
        this.jdbc = jdbc;
        this.dbExecutor = dbExecutor;
        this.crypto = crypto;
        this.audit = audit;
    }

    /** 拉取一张表的真实元数据（列名/类型/长度/可空/注释） */
    @PostMapping("/pull")
    public Map<String, Object> pull(@RequestBody Map<String, Object> body) {
        long envId = ((Number) body.get("envId")).longValue();
        long projectId = ((Number) body.get("projectId")).longValue();
        String table = String.valueOf(body.get("table"));
        String bad = illegal(table);
        if (bad != null) return Map.of("ok", false, "error", bad);
        try {
            Map<String, Object> env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
            List<Map<String, Object>> columns = pullColumns(env, List.of(table)).getOrDefault(bare(table), List.of());
            cache(projectId, table, columns);
            audit.log("metadata.pull", dbType(env) + "." + table, "columns=" + columns.size());
            return Map.of("ok", true, "columns", columns);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 一次拉多张表：一个环境一条只读连接、一条字典视图 SELECT，各表各自落 {@code table_meta}、各自报结果。
     * 拉不到列的表原样回报 {@code missing}（表名带错属主、或这个账号在字典视图里看不见它），不猜、不掩盖。
     */
    @PostMapping("/pull-batch")
    public Map<String, Object> pullBatch(@RequestBody Map<String, Object> body) {
        if (!(body.get("envId") instanceof Number en)) return Map.of("ok", false, "error", "缺少 envId");
        if (!(body.get("projectId") instanceof Number pn)) return Map.of("ok", false, "error", "缺少 projectId");
        long envId = en.longValue(), projectId = pn.longValue();
        List<String> tables = new ArrayList<>(), rejected = new ArrayList<>();
        for (Object o : (body.get("tables") instanceof List<?> l) ? l : List.of()) {
            String t = o == null ? "" : String.valueOf(o).trim();
            if (t.isEmpty() || t.equalsIgnoreCase("null")) continue;
            if (illegal(t) != null) { rejected.add(t); continue; }
            if (tables.size() >= MAX_TABLES_PER_BATCH) { rejected.add(t); continue; }
            if (!tables.contains(t)) tables.add(t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asked", tables.size());
        out.put("rejected", rejected);
        if (tables.isEmpty()) {
            out.put("ok", false);
            out.put("error", rejected.isEmpty() ? "没有要拉的表名"
                    : "这些表名不合标识符规则，没发给库：" + String.join("、", rejected));
            return out;
        }
        List<Map<String, Object>> per = new ArrayList<>();
        out.put("tables", per);
        try {
            Map<String, Object> env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
            Long envProject = env.get("project_id") == null ? null : ((Number) env.get("project_id")).longValue();
            if (envProject != null && envProject != projectId) {
                return Map.of("ok", false, "error", "环境 #" + envId + " 不属于项目 #" + projectId + "，没拉元数据");
            }
            Map<String, List<Map<String, Object>>> got = pullColumns(env, tables);
            int pulled = 0, missing = 0, commented = 0;
            for (String t : tables) {
                List<Map<String, Object>> cols = got.getOrDefault(bare(t), List.of());
                long withComment = cols.stream().filter(c -> c.get("comment") != null
                        && !String.valueOf(c.get("comment")).isBlank()).count();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table", t);
                row.put("columns", cols.size());
                row.put("comments", withComment);
                row.put("status", cols.isEmpty() ? "missing" : "ok");
                per.add(row);
                if (cols.isEmpty()) { missing++; continue; }
                pulled++;
                commented += (int) withComment;
                cache(projectId, t, cols);
            }
            audit.log("metadata.pull-batch", dbType(env) + " · " + tables.size() + " 张表",
                    "拉到列的表 " + pulled + " 张、空表 " + missing + " 张、带注释的列 " + commented + " 个");
            out.put("ok", true);
            out.put("pulled", pulled);
            out.put("missing", missing);
            out.put("comments", commented);
            out.put("note", !rejected.isEmpty() ? "另有 " + rejected.size() + " 个名字不合标识符规则或超出单趟 "
                    + MAX_TABLES_PER_BATCH + " 张的上限，没发给库：" + String.join("、", rejected)
                    : missing > 0 ? "有 " + missing + " 张表在该库里查不到列：表名可能带错了属主，或这个账号在字典视图里看不见它"
                    : commented == 0 ? "列结构已缓存，但这些列在库里没写注释——说明只能靠源码里的枚举与别名，界面不会编"
                    : "列注释已缓存到项目元数据（table_meta），重新拼一次就能看到");
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
            return out;
        }
    }

    @GetMapping
    public Map<String, Object> get(@RequestParam long projectId, @RequestParam String table) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT columns_json, pulled_at FROM table_meta WHERE project_id=? AND table_name=?",
                    projectId, table.toLowerCase(Locale.ROOT));
            return Map.of("ok", true, "columns", mapperRead(row.get("columns_json")), "pulledAt", row.get("pulled_at"));
        } catch (Exception e) {
            return Map.of("ok", false, "error", "尚未拉取该表元数据");
        }
    }

    /** 落库幂等：同一张表反复拉只覆盖那一行（列注释是现状，不是历史） */
    private void cache(long projectId, String table, List<Map<String, Object>> columns) {
        jdbc.update("INSERT INTO table_meta(project_id, table_name, columns_json) VALUES (?,?,?) " +
                        "ON CONFLICT(project_id, table_name) DO UPDATE SET columns_json=excluded.columns_json, " +
                        "pulled_at=datetime('now','localtime')",
                projectId, bare(table), toJson(columns));
    }

    private String illegal(String table) {
        if (table == null || table.isBlank()) return "表名不能为空";
        if (!TABLE.matcher(table.trim()).matches()) return "表名「" + table + "」含非法字符或形状不对";
        return null;
    }

    private String dbType(Map<String, Object> env) {
        String t = (String) env.get("db_type");
        return t == null || t.isBlank() ? detectType((String) env.get("db_url")) : t;
    }

    /**
     * 一批表 → 各自的列清单，键为**小写裸表名**（不带属主前缀：字典视图回什么形状各家不同，只认裸名）。
     * 一个环境一条连接一趟查完；{@code conn.setReadOnly(true)} 是硬约束，这条连接发不出写操作。
     */
    private Map<String, List<Map<String, Object>>> pullColumns(Map<String, Object> env, List<String> tables)
            throws Exception {
        String dbUrl = (String) env.get("db_url");
        if (dbUrl == null || dbUrl.isBlank()) throw new IllegalStateException("环境未配置 db_url，拉不了元数据");
        String dbType = dbType(env);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(dbUrl, (String) env.get("db_user"),
                crypto.decrypt((String) env.get("db_password_enc")))) {
            conn.setReadOnly(true);
            if ("sqlite".equals(dbType)) {           // pragma 天生一表一查，就按表循环
                for (String t : tables) {
                    out.put(bare(t), run(conn, "SELECT name AS column_name, type AS data_type,"
                            + " '' AS character_maximum_length,"
                            + " CASE WHEN notnull=1 THEN 'NO' ELSE 'YES' END AS is_nullable,"
                            + " '' AS column_comment FROM pragma_table_info('" + bare(t) + "')", false));
                }
                return out;
            }
            Map<String, List<Map<String, Object>>> byTable = group(run(conn, metaSql(dbType, tables), true));
            for (String t : tables) out.put(bare(t), byTable.getOrDefault(bare(t), List.of()));
            return out;
        }
    }

    /** 按字典视图自己回的表名分桶（比按入参猜准：入参可能大小写、属主都写错）；单表语句没这一列时全进 "" */
    private Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> by = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object t = r.remove("table_of");
            by.computeIfAbsent(t == null ? "" : String.valueOf(t), k -> new ArrayList<>()).add(r);
        }
        return by;
    }

    /** 一条字典视图 SELECT：多张表用 IN 一次取回，并带回 pf_table 供分桶 */
    private String metaSql(String dbType, List<String> tables) {
        return switch (dbType == null ? "" : dbType) {
            case "mysql", "tidb", "gauss-mysql" ->      // Q1 拍板：GaussDB(for MySQL) 走 MySQL 协议，字典视图同款
                    "SELECT TABLE_NAME AS pf_table, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE,"
                    + " COLUMN_COMMENT FROM information_schema.COLUMNS WHERE "
                    + cond(tables, "TABLE_SCHEMA", "TABLE_NAME", false)
                    + " ORDER BY TABLE_NAME, ORDINAL_POSITION";
            case "oracle", "dm" ->
                    // 注释一律 LEFT JOIN 取，绝不写"逐列相关子查询"：真实 Oracle 上同一张表实测
                    // 子查询 9.9s / JOIN 0.1s（三张表 38.8s，正好被 15 秒超时掐死 —— ORA-01013）。
                    // ALL_COL_COMMENTS 与 ALL_TAB_COLUMNS 同属主同名，JOIN 键就是字典视图自己的三件套。
                    "SELECT c.TABLE_NAME AS pf_table, c.COLUMN_NAME, c.DATA_TYPE, c.CHAR_LENGTH, c.NULLABLE,"
                    + " cc.COMMENTS AS COLUMN_COMMENT"
                    + " FROM ALL_TAB_COLUMNS c LEFT JOIN ALL_COL_COMMENTS cc"
                    + " ON cc.OWNER=c.OWNER AND cc.TABLE_NAME=c.TABLE_NAME AND cc.COLUMN_NAME=c.COLUMN_NAME"
                    + " WHERE " + cond(tables, "c.OWNER", "c.TABLE_NAME", true)
                    + " ORDER BY c.TABLE_NAME, c.COLUMN_ID";
            case "sqlserver" ->
                    // 同款改造：扩展属性用 JOIN 拿，别在 SELECT 列表里逐行 OBJECT_ID 子查询
                    "SELECT c.TABLE_NAME AS pf_table, c.COLUMN_NAME, c.DATA_TYPE, c.CHARACTER_MAXIMUM_LENGTH,"
                    + " c.IS_NULLABLE, CAST(ep.value AS VARCHAR(200)) AS COLUMN_COMMENT"
                    + " FROM INFORMATION_SCHEMA.COLUMNS c"
                    + " LEFT JOIN sys.columns sc ON sc.object_id=OBJECT_ID(c.TABLE_SCHEMA + '.' + c.TABLE_NAME)"
                    + " AND sc.name=c.COLUMN_NAME"
                    + " LEFT JOIN sys.extended_properties ep ON ep.major_id=sc.object_id"
                    + " AND ep.minor_id=sc.column_id AND ep.name='MS_Description'"
                    + " WHERE " + cond(tables, "c.TABLE_SCHEMA", "c.TABLE_NAME", true)
                    + " ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION";
            case "opengauss" ->                         // Q1 拍板：openGauss / GaussDB(中心化,PG系) 统一按 PG 系适配
                    "SELECT c.table_name AS pf_table, c.column_name, c.data_type,"
                    + " COALESCE(c.character_maximum_length::text, '') AS character_maximum_length, c.is_nullable,"
                    + " obj_description(d.coid, 'pg_class') AS table_comment,"
                    + " col_description(d.coid, d.attnum) AS column_comment"
                    + " FROM information_schema.columns c"
                    + " LEFT JOIN (SELECT a.attrelid AS coid, a.attnum, n.nspname, cl.relname"
                    + " FROM pg_attribute a JOIN pg_class cl ON cl.oid=a.attrelid"
                    + " JOIN pg_namespace n ON n.oid=cl.relnamespace)"
                    + " d ON d.nspname=c.table_schema AND d.relname=c.table_name AND d.attnum=c.ordinal_position"
                    + " WHERE " + cond(tables, "c.table_schema", "c.table_name", false)
                    + " ORDER BY c.table_name, c.ordinal_position";
            default -> throw new IllegalArgumentException(
                    "暂不支持该库类型的元数据拉取（" + dbType + "），可先按代码侧解析使用");
        };
    }

    /**
     * 过滤条件：带属主/库前缀的表按 {@code (属主='X' AND 表名 IN (…))} 分组、组间 OR、整块加括号。
     * {@code upper} 那一家（oracle／dm／sqlserver 的字典视图存大写）把名字统一成大写再拼。
     * 每个名字都过了 {@link #TABLE} 白名单，单引号在 {@link #bare} 之后仍会被再摘一次，拼不出逃逸。
     */
    private String cond(List<String> tables, String ownerCol, String nameCol, boolean upper) {
        Map<String, List<String>> byOwner = new LinkedHashMap<>();
        for (String t : tables) {
            String s = t.trim();
            int dot = s.lastIndexOf('.');
            String owner = dot > 0 ? s.substring(0, dot).replace("'", "") : "";
            byOwner.computeIfAbsent(upper ? owner.toUpperCase(Locale.ROOT) : owner, k -> new ArrayList<>())
                    .add("'" + clean(s, upper) + "'");
        }
        List<String> groups = new ArrayList<>();
        for (var e : byOwner.entrySet()) {
            String names = "(" + String.join(",", e.getValue()) + ")";
            groups.add(e.getKey().isEmpty() ? nameCol + " IN " + names
                    : "(" + ownerCol + "='" + e.getKey() + "' AND " + nameCol + " IN " + names + ")");
        }
        return "(" + String.join(" OR ", groups) + ")";
    }

    /** 进 IN 列表的那一项：只留裸表名（属主单列过滤），并按这家库的大小写口径归一 */
    private String clean(String table, boolean upper) {
        String b = bare(table).replace("'", "");
        return upper ? b.toUpperCase(Locale.ROOT) : b;
    }

    /** 裸表名（小写）：{@code table_meta} 的键、返回值的分组键都只用这一个口径 */
    private static String bare(String table) {
        String t = table.trim().replace("'", "").toLowerCase(Locale.ROOT);
        int dot = t.lastIndexOf('.');
        return dot > 0 ? t.substring(dot + 1) : t;
    }

    /** 跑一条元数据 SELECT；带 pf_table 时把它另存成 table_of，交给 {@link #group} 分桶 */
    private List<Map<String, Object>> run(Connection conn, String sql, boolean withTable) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(META_TIMEOUT_SEC);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    String comment = null;
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String label = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                        Object v = rs.getObject(i);
                        if (label.equals("pf_table")) {
                            if (withTable) col.put("table_of", v == null ? "" : String.valueOf(v).toLowerCase(Locale.ROOT));
                            continue;
                        }
                        if (label.equals("table_comment")) continue;      // 表注释别盖掉列注释（旧口径同样是列注释优先）
                        if (label.contains("comment")) { comment = v == null ? null : String.valueOf(v); continue; }
                        col.put(label.contains("name") ? "name" : label, v);
                    }
                    col.put("comment", comment);
                    out.add(col);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapperRead(Object json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json.toString(), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String detectType(String url) {
        if (url == null) return null;
        String u = url.toLowerCase();
        if (u.startsWith("jdbc:dm")) return "dm";
        if (u.startsWith("jdbc:oracle")) return "oracle";
        if (u.startsWith("jdbc:sqlserver")) return "sqlserver";
        if (u.startsWith("jdbc:opengauss") || u.startsWith("jdbc:postgresql")) return "opengauss";
        if (u.startsWith("jdbc:mysql")) return "mysql";
        if (u.startsWith("jdbc:sqlite")) return "sqlite";
        return null;
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
