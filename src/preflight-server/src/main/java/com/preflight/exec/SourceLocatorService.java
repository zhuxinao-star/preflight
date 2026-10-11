package com.preflight.exec;

import com.preflight.common.CryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报错定位（第 26 轮）：存储过程把异常吞进 WHEN OTHERS 后，库回"执行成功"，行号根本不产生。
 * 这里做的是另一条路——拿过程回传的错误文本，去库里的**源码视图**和数据字典做只读核对。
 *
 * 硬约束（用户原话：不能在我的目标库生成任何数据、改任何结构、动任何表和字段）：
 * 连接 setReadOnly(true)、全部语句是写死的 SELECT、标识符先过白名单正则再作为绑定参数下发、
 * 不重跑存储过程、不建任何表（所以 DBMS_PROFILER 那一档直接放弃，它要往三张表里写）。
 * 拿不到"库回传的行号"时，只给"按报错文本匹配的候选行"，并且必须明说这是候选。
 */
@Service
public class SourceLocatorService {

    /** 标识符白名单：不过这个正则的一律不发给库，避免把错误文本里的东西拼进语句 */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,63}");
    private static final Pattern ORA_CODE = Pattern.compile("(?i)\\b(ORA-\\d{5}|DM-\\d{5}|SQL\\d{5})\\b");
    private static final Pattern QUOTED = Pattern.compile("\"([A-Za-z][A-Za-z0-9_$#]{1,63})\"");
    private static final Pattern BRACKETED = Pattern.compile("\\[([A-Za-z][A-Za-z0-9_$.]{1,63})]");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([A-Za-z][A-Za-z0-9_$.]{1,63})'");
    /** SQL Server 的报错自带对象名与行号：这一档的行号是库给的，不是我们猜的 */
    private static final Pattern MSSQL_AT = Pattern.compile("(?i)\\b(?:Procedure|Trigger|Function)\\s+([A-Za-z][\\w$.]{0,127}),\\s*Line\\s+(\\d{1,7})");
    /** 调用语句里的过程名：{call A.B(?,?)} / CALL A.B(...) / exec A.B */
    private static final Pattern CALL_PATH = Pattern.compile(
            "(?i)(?:\\{\\s*call\\s+|\\bcall\\s+|\\bexec(?:ute)?\\s+)([A-Za-z][\\w$.]{1,127})");

    private static final int MAX_SOURCE_LINES = 4000;
    private static final int MAX_CANDIDATES = 12;
    private static final int MAX_IDENTIFIERS = 4;
    private static final int MAX_LINE_TEXT = 400;

    private final CryptoService crypto;
    private final JdbcTemplate jdbc;

    public SourceLocatorService(CryptoService crypto, javax.sql.DataSource dataSource) {
        this.crypto = crypto;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** frames 来自驱动回传的栈帧（有就优先用它，那是库自己给的对象路径）。 */
    public Map<String, Object> locate(long envId, String callSql, Collection<String> errorTexts,
                                      Collection<DbExecutorService.Frame> frames) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> texts = new ArrayList<>();
        if (errorTexts != null) for (String t : errorTexts) if (t != null && !t.isBlank()) texts.add(t);
        String joined = String.join("\n", texts);

        List<String> codes = collect(ORA_CODE, joined, m -> m.group(1).toUpperCase(Locale.ROOT));
        List<String> idents = identifiers(joined);
        out.put("codes", codes);
        out.put("identifiers", idents);
        out.put("evidenceTexts", texts.stream().map(t -> t.length() > 400 ? t.substring(0, 400) + "…" : t).toList());

        String path = objectPath(frames, callSql, joined);
        out.put("objectPath", path);
        out.put("pathSource", frames != null && !frames.isEmpty() ? "驱动回传的调用栈"
                : path != null ? "调用语句里的对象名" : null);

        Map<String, Object> env;
        try {
            env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
        } catch (Exception e) {
            return fail(out, "环境 #" + envId + " 不存在，没查库");
        }
        String dbUrl = (String) env.get("db_url");
        String dbType = (String) env.get("db_type");
        if (dbType == null || dbType.isBlank()) dbType = com.preflight.exec.DbExecutorService.typeOf(dbUrl);
        out.put("dbType", dbType);
        if (dbUrl == null || dbUrl.isBlank()) return fail(out, "环境未配置 db_url，定位只能停在错误文本这一层");
        if (texts.isEmpty()) return fail(out, "库里没回错误文本（既没异常也没错误码列），没有可定位的线索");
        String name = objectName(path);
        try (Connection conn = DriverManager.getConnection(dbUrl, (String) env.get("db_user"),
                crypto.decrypt((String) env.get("db_password_enc")))) {
            // 字典核对与取源码都走这一个只读连接。原先"认不出对象名"这一档没有连接就直接走本地 JdbcTemplate，
            // 等于把 Oracle 的语句发到 PreFlight 自己的元数据库上——查出来的"查不了"是句谎话，得连回目标库。
            conn.setReadOnly(true);
            addLineNumbers(out, dbType, joined);
            out.put("diagnosticSql", suggestedSql(dbType, idents));
            if (name == null) {
                out.put("tier", "text-only");
                out.put("tierLabel", "只有错误文本：报不出对象名，取不了源码原文，但字典核对仍然照查");
                out.put("candidates", List.of());
                out.put("dictionary", dictionary(conn, dbType, idents, out));
                return hint(out, "错误文本里没有可辨认的对象名（" + (path == null ? "调用语句也没写出 CALL 的过程名" : "路径不合规")
                        + "）。要么在 SQL 框里用 {call 包名.过程名(?)} 的形式调用，要么等驱动回传调用栈。");
            }
            List<Map<String, Object>> lines = sourceLines(conn, dbType, path, name, out);
            List<Map<String, Object>> cand = candidates(lines, idents, out);
            out.put("candidates", cand);
            out.put("dictionary", dictionary(conn, dbType, idents, out));
            if (cand.isEmpty()) {
                out.put("lineNote", "源码里没匹配到 " + idents + " 这些标识符——错误码是过程自己拼的字符串，"
                        + "报错的那条语句多半在被调用的下层对象里；下面给了字典核对结果和可复制的诊断语句。");
            } else {
                out.put("lineNote", "以上只是按报错文本匹配出的候选行，不是库回传的行号："
                        + "过程把异常吞进 WHEN OTHERS 后自己写了错误码，库就没产生过行号（哪套库都一样）。");
            }
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            out.put("tier", "failed");
            return fail(out, "只读查询失败：" + firstMessage(e)
                    + "（如果是权限不够，用当前账号手工跑一遍上面【可复制的诊断语句】就能确认）");
        }
    }

    // ---------- 线索解析 ----------

    private List<String> identifiers(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher q = QUOTED.matcher(text);
        while (q.find()) out.add(q.group(1));
        Matcher s = SINGLE_QUOTED.matcher(text);
        while (s.find()) out.add(s.group(1));
        Matcher b = BRACKETED.matcher(text);
        while (b.find()) out.add(b.group(1));
        List<String> filtered = new ArrayList<>();
        for (String t : out) {
            if (t.indexOf('.') >= 0) continue;               // 带点的是对象/表路径，另说
            if (!IDENT.matcher(t).matches()) continue;
            if (isNoise(t)) continue;
            filtered.add(t);
            if (filtered.size() >= MAX_IDENTIFIERS) break;
        }
        return filtered;
    }

    /** 错误文本里常见但不是"找不到的列"的词：ORA-00904: "AVC" 里的 ORA/标识符关键字等 */
    private boolean isNoise(String t) {
        String u = t.toUpperCase(Locale.ROOT);
        return Set.of("ORA", "SQL", "SQLCODE", "ERRORCODE", "ERRORMSG", "ERRMSG", "SELECT", "FROM", "WHERE",
                "NULL", "TRUE", "FALSE", "IDENTIFIER", "COLUMN", "PROCEDURE", "FUNCTION", "UNKNOWN",
                "COLUMNS", "EXIST", "NOT", "DOES", "INVALID").contains(u);
    }

    private String objectPath(Collection<DbExecutorService.Frame> frames, String callSql, String text) {
        if (frames != null) for (var f : frames) {
            if (f != null && f.path() != null && !f.path().isBlank()) return f.path();
        }
        if (callSql != null) {
            Matcher m = CALL_PATH.matcher(callSql);
            if (m.find()) return m.group(1);
        }
        Matcher m = Pattern.compile("(?i)\\b(?:in\\s+|at\\s+)([A-Za-z][\\w$]{1,63}\\.[A-Za-z][\\w$]{1,63})").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** 路径末段当对象名；三段时首段当 owner。两段则 owner 留空（包名.过程名里到底哪一段是 NAME，交给查询用 IN 兜住）。 */
    private String objectName(String path) {
        if (path == null) return null;
        String p = path.replaceAll("[();]+$", "");
        if (!IDENT.matcher(p.replace(".", "")).matches()) return null;
        String[] parts = p.split("\\.");
        return parts[parts.length - 1];
    }

    private String ownerOf(String path) {
        if (path == null) return null;
        String[] parts = path.split("\\.");
        return parts.length >= 3 ? parts[0] : null;
    }

    // ---------- 源码档位 ----------

    /** 按库能力自动选档，并把档位与"这一档能拿到什么"写进结果，界面原样转述。 */
    private List<Map<String, Object>> sourceLines(Connection conn, String dbType, String path, String name,
                                                  Map<String, Object> out) throws Exception {
        String owner = ownerOf(path);
        switch (dbType == null ? "" : dbType) {
            case "oracle", "dm" -> {
                out.put("tier", "all_source");
                out.put("tierLabel", (dbType.equals("oracle") ? "Oracle" : "达梦")
                        + " 的 ALL_SOURCE：能取到包体/过程的原文行");
                // NAME = ? 走等值，别套 UPPER()——ALL_SOURCE 上是普通列，函数包住就用不上索引，大库上一发就是几分钟
                String sql = "SELECT LINE, TEXT FROM ALL_SOURCE WHERE NAME = ?"
                        + (owner == null ? "" : " AND OWNER = ?")
                        + " AND TYPE IN ('PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION','TYPE','TRIGGER')"
                        + " ORDER BY TYPE, LINE";
                String upper = name.toUpperCase(Locale.ROOT);
                List<Map<String, Object>> got = queryLines(conn, sql, upper,
                        owner == null ? null : owner.toUpperCase(Locale.ROOT));
                if (got.isEmpty() && !upper.equals(name)) {
                    got = queryLines(conn, sql, name, owner);   // 建对象时带过引号的小写名，只有这一步才再发一条
                }
                return got;
            }
            case "mysql", "tidb", "gauss-mysql" -> {
                out.put("tier", "routines");
                out.put("tierLabel", "MySQL 系的 INFORMATION_SCHEMA.ROUTINES：整段过程体一列文本，按换行拆行号");
                return splitByNewline(queryText(conn,
                        "SELECT ROUTINE_DEFINITION FROM INFORMATION_SCHEMA.ROUTINES"
                                + " WHERE UPPER(ROUTINE_NAME) = ? AND ROUTINE_DEFINITION IS NOT NULL",
                        name.toUpperCase(Locale.ROOT), null));
            }
            case "sqlserver" -> {
                out.put("tier", "routines");
                out.put("tierLabel", "SQL Server 的 INFORMATION_SCHEMA.ROUTINES（该视图上新版本标记为弃用，能读到就用，读不到以库回传行号为准）");
                return splitByNewline(queryText(conn,
                        "SELECT ROUTINE_DEFINITION FROM INFORMATION_SCHEMA.ROUTINES"
                                + " WHERE UPPER(ROUTINE_NAME) = ? AND ROUTINE_DEFINITION IS NOT NULL",
                        name.toUpperCase(Locale.ROOT), null));
            }
            case "opengauss" -> {
                out.put("tier", "pg_proc");
                out.put("tierLabel", "openGauss/PG 的 pg_proc.prosrc：过程体原文，按换行拆行号");
                return splitByNewline(queryText(conn,
                        "SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                                + " WHERE p.proname = ? OR lower(p.proname) = ?", name, name.toLowerCase(Locale.ROOT)));
            }
            default -> {
                out.put("tier", "unsupported");
                out.put("tierLabel", "认不出的库类型，取不了库内源码");
                out.put("missing", "这一档缺的是能力不是权限：当前只接 Oracle/达梦/MySQL/TiDB/GaussDB(for MySQL)/SQL Server/openGauss-PG");
                return List.of();
            }
        }
    }

    /** 库回传的行号（SQL Server 的报错自带）——有它就单列出来，和候选行严格分开。 */
    private void addLineNumbers(Map<String, Object> out, String dbType, String text) {
        Matcher m = MSSQL_AT.matcher(text);
        List<Map<String, Object>> driver = new ArrayList<>();
        while (m.find() && driver.size() < 5) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("path", m.group(1));
            d.put("line", Integer.parseInt(m.group(2)));
            driver.add(d);
        }
        if (dbType != null && List.of("oracle", "dm").contains(dbType)) {
            Matcher o = Pattern.compile("(?i)\\b(?:package|procedure|function)\\s+([\\w$.]+)\\s+.*?line\\s+(\\d{1,7})")
                    .matcher(text);
            while (o.find() && driver.size() < 5) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("path", o.group(1));
                d.put("line", Integer.parseInt(o.group(2)));
                driver.add(d);
            }
        }
        out.put("driverLines", driver);
    }

    /** 一行的原文能长到几千字符（Oracle 的 wrapped 包体整行就是一大块密文），只截显示、并明说截了多少，
     *  绝不把截断后的内容冒充成整行。 */
    private String clip(String text) {
        if (text.length() <= MAX_LINE_TEXT) return text;
        return text.substring(0, MAX_LINE_TEXT)
                + "…（这一行原文共 " + text.length() + " 字符，只截前 " + MAX_LINE_TEXT + " 个显示）";
    }

    private List<Map<String, Object>> queryLines(Connection conn, String sql, String a, String b) throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(200);
            ps.setString(1, a);
            if (b != null) ps.setString(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && lines.size() < MAX_SOURCE_LINES) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("line", rs.getInt(1));
                    m.put("text", rs.getString(2) == null ? "" : rs.getString(2).stripTrailing());
                    lines.add(m);
                }
            }
        }
        return lines;
    }

    private List<String> queryText(Connection conn, String sql, String a, String b) throws Exception {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a);
            if (b != null) ps.setString(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < 20) {
                    String t = rs.getString(1);
                    if (t != null) out.add(t);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> splitByNewline(List<String> blobs) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String blob : blobs) {
            String[] arr = blob.split("\r?\n", -1);
            for (int i = 0; i < arr.length && lines.size() < MAX_SOURCE_LINES; i++) {
                if (arr[i].isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("line", i + 1);
                m.put("text", arr[i].stripTrailing());
                lines.add(m);
            }
        }
        return lines;
    }

    /** 候选行：源码里出现该标识符的行（忽略大小写），带上下文行号。这是匹配结果，不是库给的行号。 */
    private List<Map<String, Object>> candidates(List<Map<String, Object>> lines, List<String> idents,
                                                 Map<String, Object> out) {
        List<Map<String, Object>> cand = new ArrayList<>();
        if (lines.isEmpty() || idents.isEmpty()) {
            if (lines.isEmpty()) {
                out.put("missing", "库里没读到 " + out.get("objectPath") + " 的源码原文——可能是当前账号看不到它的 OWNER（能执行 ≠ 数据字典里可见），或它是中间层转调的对象");
            }
            return cand;
        }
        Set<Integer> taken = new LinkedHashSet<>();
        for (Map<String, Object> l : lines) {
            int no = ((Number) l.get("line")).intValue();
            String text = String.valueOf(l.get("text")).toUpperCase(Locale.ROOT);
            for (String id : idents) {
                if (text.contains(id.toUpperCase(Locale.ROOT))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("line", no);
                    m.put("text", clip(String.valueOf(l.get("text"))));
                    m.put("matchedOn", id);
                    cand.add(m);
                    taken.add(no);
                    break;
                }
            }
            if (cand.size() >= MAX_CANDIDATES) break;
        }
        out.put("scannedLines", lines.size());
        if (cand.size() >= MAX_CANDIDATES) {
            out.put("candidateNote", "命中 " + cand.size() + "+ 行，只列前 " + MAX_CANDIDATES + " 行（已扫 " + lines.size() + " 行原文）");
        }
        return cand;
    }

    // ---------- 数据字典核对 ----------

    /** 标识符到底是谁的列 / 存不存在 / 是不是同义词。每词一条等值查询，绑定参数，行数硬上限。 */
    private List<Map<String, Object>> dictionary(Connection conn, String dbType, List<String> idents, Map<String, Object> out) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : idents) {
            List<Object[]> probes = dictProbes(dbType, id);
            for (Object[] p : probes) {
                try {
                    @SuppressWarnings("unchecked")
                    List<Object> args = (List<Object>) p[1];
                    List<Map<String, Object>> got = exec(conn, (String) p[2], args);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("identifier", id);
                    m.put("check", p[0]);
                    m.put("found", got.size());
                    m.put("where", got.stream().limit(8).map(this::flatten).toList());
                    rows.add(m);
                } catch (Exception e) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("identifier", id);
                    m.put("check", p[0]);
                    m.put("error", "查不了：" + firstMessage(e));
                    rows.add(m);
                }
            }
        }
        if (!rows.isEmpty() && out.get("dictionaryNote") == null) {
            out.put("dictionaryNote", "字典核对只说明「这个标识符在当前账号看得到的范围里归谁」，不能替你断定报错就在这一行。");
        }
        return rows;
    }

    private List<Object[]> dictProbes(String dbType, String id) {
        String upper = id.toUpperCase(Locale.ROOT);
        String lower = id.toLowerCase(Locale.ROOT);
        return switch (dbType == null ? "" : dbType) {
            case "oracle", "dm" -> List.of(
                    new Object[]{"它是不是某个表的列", List.of(upper),
                            "SELECT OWNER, TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM ("
                                    + " SELECT OWNER, TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLUMNS WHERE COLUMN_NAME = ?) WHERE ROWNUM <= 20"},
                    new Object[]{"它是不是对象（表/视图/序列/包）", List.of(upper),
                            "SELECT OWNER, OBJECT_NAME, OBJECT_TYPE, STATUS FROM ("
                                    + " SELECT OWNER, OBJECT_NAME, OBJECT_TYPE, STATUS FROM ALL_OBJECTS WHERE OBJECT_NAME = ? ORDER BY OWNER) WHERE ROWNUM <= 20"},
                    new Object[]{"它是不是同义词、指向哪", List.of(upper),
                            "SELECT OWNER, SYNONYM_NAME, TABLE_OWNER, TABLE_NAME FROM ("
                                    + " SELECT OWNER, SYNONYM_NAME, TABLE_OWNER, TABLE_NAME FROM ALL_SYNONYMS WHERE SYNONYM_NAME = ?) WHERE ROWNUM <= 20"});
            case "mysql", "tidb", "gauss-mysql", "sqlserver" -> List.of(
                    new Object[]{"它是不是某个表的列", List.of(upper),
                            "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                                    + " WHERE UPPER(COLUMN_NAME) = ? LIMIT 20"},
                    new Object[]{"它是不是对象", List.of(upper),
                            "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES"
                                    + " WHERE UPPER(TABLE_NAME) = ? LIMIT 20"});
            case "opengauss" -> List.of(
                    new Object[]{"它是不是某个表的列", List.of(lower),
                            "SELECT table_schema, table_name, column_name, data_type FROM information_schema.columns"
                                    + " WHERE column_name = ? LIMIT 20"},
                    new Object[]{"它是不是表/视图", List.of(lower),
                            "SELECT n.nspname, c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                                    + " WHERE lower(c.relname) = ? LIMIT 20"});
            default -> List.of();
        };
    }

    private List<Map<String, Object>> exec(Connection conn, String sql, List<Object> args) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next() && out.size() < 20) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    out.add(m);
                }
            }
        }
        return out;
    }

    private String flatten(Map<String, Object> row) {
        List<String> vals = new ArrayList<>();
        for (var v : row.values()) vals.add(String.valueOf(v));
        return String.join(".", vals);
    }

    /** 拿不到的档：把语句原样给用户，让他用自己的账号在库里跑一遍确认是权限还是真没有。 */
    private List<String> suggestedSql(String dbType, List<String> idents) {
        List<String> sql = new ArrayList<>();
        for (String id : idents) {
            if (!IDENT.matcher(id).matches()) continue;
            switch (dbType == null ? "" : dbType) {
                case "oracle", "dm" -> {
                    sql.add("SELECT owner, table_name, column_name, data_type FROM all_tab_columns WHERE column_name = '"
                            + id.toUpperCase(Locale.ROOT) + "'");
                    sql.add("SELECT owner, object_name, object_type, status FROM all_objects WHERE object_name = '"
                            + id.toUpperCase(Locale.ROOT) + "'");
                    sql.add("SELECT owner, synonym_name, table_owner, table_name FROM all_synonyms WHERE synonym_name = '"
                            + id.toUpperCase(Locale.ROOT) + "'");
                }
                case "mysql", "tidb", "gauss-mysql", "sqlserver" -> sql.add(
                        "SELECT table_schema, table_name, column_name, data_type FROM information_schema.columns"
                                + " WHERE upper(column_name) = '" + id.toUpperCase(Locale.ROOT) + "' LIMIT 20");
                case "opengauss" -> sql.add(
                        "SELECT table_schema, table_name, column_name, data_type FROM information_schema.columns"
                                + " WHERE column_name = '" + id.toLowerCase(Locale.ROOT) + "' LIMIT 20");
                default -> {
                }
            }
        }
        return sql;
    }

    private Map<String, Object> hint(Map<String, Object> out, String why) {
        out.put("ok", true);
        out.put("note", why);
        return out;
    }

    private Map<String, Object> fail(Map<String, Object> out, String why) {
        out.put("ok", false);
        out.put("note", why);
        out.putIfAbsent("candidates", List.of());
        out.putIfAbsent("dictionary", List.of());
        out.putIfAbsent("diagnosticSql", List.of());
        return out;
    }

    private String firstMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private <T> List<String> collect(Pattern p, String text, java.util.function.Function<Matcher, String> f) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = p.matcher(text == null ? "" : text);
        while (m.find() && out.size() < 8) out.add(f.apply(m));
        return new ArrayList<>(out);
    }
}
