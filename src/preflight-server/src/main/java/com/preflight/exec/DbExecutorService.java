package com.preflight.exec;

import com.preflight.common.CryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB 执行器（免启动直测）：参数绑定执行 SQL 模板 / 存储过程。
 * 保护机制：语句黑名单、超时、行数上限、默认事务回滚（提交需显式确认 + 审计）。
 */
@Service
public class DbExecutorService {

    private static final Pattern BLACKLIST = Pattern.compile(
            "(?is)\\b(DROP|TRUNCATE|GRANT|REVOKE)\\b");

    private static final int MAX_ROWS = 500;

    private final CryptoService crypto;
    private final JdbcTemplate jdbc;

    public DbExecutorService(CryptoService crypto, DataSource dataSource) {
        this.crypto = crypto;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record DbResult(boolean ok, String error, List<String> columns, List<List<Object>> rows,
                           int updateCount, boolean committed, long latencyMs, String notice,
                           List<Cursor> outCursors, Diag diag) {
        /** 没有出参游标的常规通道（钩子 SQL、用例执行、断言）仍按 8 参调用，不必各自补一个空列表 */
        public DbResult(boolean ok, String error, List<String> columns, List<List<Object>> rows,
                        int updateCount, boolean committed, long latencyMs, String notice) {
            this(ok, error, columns, rows, updateCount, committed, latencyMs, notice, List.of(), Diag.NONE);
        }
    }

    /**
     * 报错诊断（第 23b 轮）：驱动愿意给多少就收多少，一条都不编。
     * chain 是完整消息链（主错 + 后续每条），frames 是从里解析出的库内栈帧。
     * 成因：Oracle/达梦把 PL/SQL 调用栈放在 {@code SQLException.getNextException()} 里，
     * 老代码只顺 Java 的 cause 链走，行号全被丢了，界面上就只剩一句"存储过程返回的报错原因"。
     */
    public record Diag(List<String> chain, List<Frame> frames) {
        public static final Diag NONE = new Diag(List.of(), List.of());
    }

    /** 库内栈帧：path 形如 {@code SCOTT.PKG_X.FUNC_Y}（顶层匿名调用解析不出对象），line 是该对象源码的行号。 */
    public record Frame(String path, int line, String raw) {}

    /** 存储过程回传的游标出参：一个出参一张表（主结果集放不下多份列名，硬塞成一列字符串就是上一轮那个报错的成因） */
    public record Cursor(String name, int slot, List<String> columns, List<List<Object>> rows) {}

    /** 出参声明：第 slot 个 {@code ?} 是出参，type 为占位符自己写的 jdbcType 名（CURSOR / VARCHAR / …）。
     *  来源是 db_objects.dynamic_body 读取期派生，不是让人在界面上手填类型。 */
    public record OutParam(int slot, String name, String type) {}

    /** 认得出的 jdbcType → java.sql.Types。认不出的直接拒绝执行并说清是哪一位——不猜类型。 */
    private static final Map<String, Integer> JDBC_TYPES = Map.ofEntries(
            Map.entry("CURSOR", Types.REF_CURSOR), Map.entry("REF_CURSOR", Types.REF_CURSOR),
            Map.entry("VARCHAR", Types.VARCHAR), Map.entry("NVARCHAR", Types.NVARCHAR),
            Map.entry("CHAR", Types.CHAR), Map.entry("CLOB", Types.CLOB),
            Map.entry("INTEGER", Types.INTEGER), Map.entry("INT", Types.INTEGER),
            Map.entry("BIGINT", Types.BIGINT), Map.entry("SMALLINT", Types.SMALLINT),
            Map.entry("DECIMAL", Types.DECIMAL), Map.entry("NUMERIC", Types.NUMERIC),
            Map.entry("FLOAT", Types.FLOAT), Map.entry("DOUBLE", Types.DOUBLE),
            Map.entry("DATE", Types.DATE), Map.entry("TIME", Types.TIME), Map.entry("TIMESTAMP", Types.TIMESTAMP),
            Map.entry("BOOLEAN", Types.BOOLEAN), Map.entry("BINARY", Types.BINARY),
            Map.entry("VARBINARY", Types.VARBINARY), Map.entry("BLOB", Types.BLOB));

    /** 六库 DDL 行为矩阵（04-补齐 §3.1）：DDL 是否可回滚；TiDB 无存储过程 */
    private static final Map<String, Boolean> DDL_ROLLBACKABLE = Map.of(
            "mysql", false, "tidb", false, "oracle", false, "dm", false,
            "sqlserver", true, "opengauss", true, "gauss-mysql", false);

    /** 库类型识别（定位器与执行器共用一份，两处各写一遍迟早会分叉） */
    public static String typeOf(String url) {
        if (url == null) return null;
        String u = url.toLowerCase();
        if (u.startsWith("jdbc:dm")) return "dm";
        if (u.startsWith("jdbc:oracle")) return "oracle";
        if (u.startsWith("jdbc:sqlserver")) return "sqlserver";
        if (u.startsWith("jdbc:opengauss") || u.startsWith("jdbc:postgresql")) return "opengauss";
        if (u.startsWith("jdbc:mysql")) return "mysql";
        return null;
    }

    /** 环境连接直测 SQL。params 为按顺序的绑定值；allowCommit=true 时不回滚（需页面二次确认）。 */
    public DbResult runSql(long envId, String sql, List<Object> params, boolean allowCommit, boolean isProcedure) {
        return runSql(envId, sql, params, allowCommit, isProcedure, List.of());
    }

    /** outParams：这一批 ? 里哪几位是出参（存储过程的 REF CURSOR 等）。不声明就全按入参绑定——那正是达梦
     *  在游标位上 setObject(null) 抛 {@code paramDesc.cursorStmt is null} 的成因。 */
    public DbResult runSql(long envId, String sql, List<Object> params, boolean allowCommit, boolean isProcedure,
                           List<OutParam> outParams) {
        if (sql == null || sql.isBlank()) {
            return new DbResult(false, "SQL 为空", null, null, 0, false, 0, null);
        }
        if (BLACKLIST.matcher(sql).find()) {
            return new DbResult(false, "语句命中黑名单（DROP/TRUNCATE/GRANT/REVOKE 禁止直测执行）", null, null, 0, false, 0, null);
        }
        // P1-10 全表护栏：无 WHERE 的 UPDATE/DELETE 属灾难级语句
        String trimmedLower = sql.trim().toLowerCase();
        boolean fullTableDml = !isProcedure
                && (trimmedLower.startsWith("update") || trimmedLower.startsWith("delete"))
                && !trimmedLower.matches("(?s).*\\bwhere\\b.*");
        if (fullTableDml && allowCommit) {
            return new DbResult(false, "全表护栏：无 WHERE 的 UPDATE/DELETE 禁止在提交通道执行（含前后置钩子）。"
                    + "请补 WHERE 条件；想确认影响范围，用默认回滚模式跑一次即可", null, null, 0, false, 0, null);
        }
        Map<String, Object> env;
        try {
            env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
        } catch (Exception e) {
            return new DbResult(false, "环境不存在", null, null, 0, false, 0, null);
        }
        String dbUrl = (String) env.get("db_url");
        String user = (String) env.get("db_user");
        String pass = crypto.decrypt((String) env.get("db_password_enc"));
        if (dbUrl == null || dbUrl.isBlank()) {
            return new DbResult(false, "环境未配置数据库连接（db_url）", null, null, 0, false, 0, null);
        }

        long begin = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(dbUrl, user, pass)) {
            boolean isDdl = sql.trim().toUpperCase().startsWith("CREATE")
                    || sql.trim().toUpperCase().startsWith("ALTER");
            String dbType = (String) env.get("db_type");
            if (dbType == null || dbType.isBlank()) dbType = typeOf(dbUrl);
            boolean rollbackable = dbType == null || DDL_ROLLBACKABLE.getOrDefault(dbType, false);
            boolean envDisposable = env.get("allow_ddl_commit") != null
                    && ((Number) env.get("allow_ddl_commit")).intValue() == 1;
            String notice = null;

            if (isDdl) {
                if (allowCommit) {
                    // 提交通道（仅 admin，TestController 已拦）：仍需环境标记可弃库
                    if (!rollbackable && !envDisposable) {
                        return new DbResult(false, "DDL 闸门：库类型 " + (dbType == null ? "未知" : dbType)
                                + " 的 DDL 会隐式提交且不可回滚。仅当环境被显式标记为可弃库/专用 schema 时才允许提交",
                                null, null, 0, false, 0, null);
                    }
                    conn.setAutoCommit(true);
                    notice = "DDL 已提交，永久生效（不可回滚）";
                } else {
                    // 不勾提交：可回滚库走事务真回滚；不可回滚库直接拒绝——绝不静默永久落库
                    if (!rollbackable && !envDisposable) {
                        return new DbResult(false, "DDL 闸门：库类型 " + (dbType == null ? "未知" : dbType)
                                + " 的 DDL 无法在事务中回滚（会隐式提交永久生效）。如确需执行，请由管理员使用提交模式，并将环境标记为可弃库/专用 schema",
                                null, null, 0, false, 0, null);
                    }
                    conn.setAutoCommit(false);
                    notice = "DDL 在事务中执行，结束时回滚";
                }
            } else {
                conn.setAutoCommit(allowCommit);
            }

            List<String> columns = new ArrayList<>();
            List<List<Object>> rows = new ArrayList<>();
            int updateCount = -1;
            List<Cursor> outCursors = new ArrayList<>();
            List<String> unread = new ArrayList<>();

            if (isProcedure) {
                Map<Integer, OutParam> outs = new TreeMap<>();
                String outErr = collectOuts(outParams, params, outs);
                if (outErr != null) {
                    return new DbResult(false, outErr, null, null, 0, false,
                            System.currentTimeMillis() - begin, null);
                }
                try (CallableStatement cs = conn.prepareCall(sql)) {
                    int n = 1;
                    for (Object p : params) {
                        OutParam o = outs.get(n);
                        if (o == null) {
                            cs.setObject(n, p);
                        } else {
                            if (isCursor(o.type())) registerCursor(cs, n);
                            else cs.registerOutParameter(n, JDBC_TYPES.get(o.type()));
                            // 出参位还带了标量值 = INOUT，注册之后仍要把值送进去
                            if (p != null && !(p instanceof Map<?, ?>) && !isCursor(o.type())) cs.setObject(n, p);
                        }
                        n++;
                    }
                    cs.setQueryTimeout(10);
                    boolean hasRs = cs.execute();
                    if (hasRs) {
                        try (ResultSet rs = cs.getResultSet()) {
                            readResultSet(rs, columns, rows);
                        }
                    } else {
                        updateCount = cs.getUpdateCount();
                    }
                    for (OutParam o : outs.values()) {
                        if (isCursor(o.type())) {
                            List<String> cc = new ArrayList<>();
                            List<List<Object>> rr = new ArrayList<>();
                            try (ResultSet rs = cursorOf(cs, o.slot())) {
                                if (rs == null) unread.add(o.name());
                                else readResultSet(rs, cc, rr);
                            }
                            outCursors.add(new Cursor(o.name(), o.slot(), cc, rr));
                        } else {
                            columns.add("OUT#" + o.slot());
                            List<Object> row = new ArrayList<>();
                            row.add(String.valueOf(cs.getObject(o.slot())));
                            rows.add(row);
                        }
                    }
                    if (!outCursors.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (Cursor c0 : outCursors) {
                            if (sb.length() > 0) sb.append("、");
                            sb.append(c0.name()).append(" ").append(c0.rows().size()).append(" 行");
                        }
                        notice = (notice == null ? "" : notice + "；") + "出参游标已回读：" + sb;
                        // 0 行有两种可能：库里真没数据、驱动压根没给回结果集。分不清就必须说出来。
                        if (!unread.isEmpty()) {
                            notice += "；其中 " + String.join("、", unread)
                                    + " 没回读到结果集（该驱动的游标回读入口不可用），不代表库里没有数据";
                        }
                    }
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(10);
                    ps.setMaxRows(MAX_ROWS);
                    int n = 1;
                    for (Object p : params) {
                        ps.setObject(n++, p);
                    }
                    boolean hasRs = ps.execute();
                    if (hasRs) {
                        try (ResultSet rs = ps.getResultSet()) {
                            readResultSet(rs, columns, rows);
                        }
                    } else {
                        updateCount = ps.getUpdateCount();
                    }
                }
            }

            // 仅在事务内做回滚/提交（DDL 自动提交模式下无需收尾）
            if (!conn.getAutoCommit()) {
                if (allowCommit) {
                    conn.commit();
                } else {
                    conn.rollback();
                }
                conn.setAutoCommit(true);
            }
            long latency = System.currentTimeMillis() - begin;
            if (fullTableDml) {
                notice = (notice == null ? "" : notice + "；")
                        + "⚠ 全表语句（无 WHERE 的 UPDATE/DELETE），本次已回滚未落库；提交通道一律拒绝";
            }
            return new DbResult(true, null, columns, rows, updateCount, allowCommit, latency, notice, outCursors, Diag.NONE);
        } catch (Exception e) {
            Diag diag = collectDiag(e);
            String msg = rootMessage(e);
            String dbUrlLower = dbUrl == null ? "" : dbUrl.toLowerCase();
            if (msg != null && msg.contains("No suitable driver")) {
                if (dbUrlLower.startsWith("jdbc:dm")) {
                    msg = "达梦驱动未安装：请将 DmJdbcDriver18 jar 放入部署包 lib/ 目录后重启（驱动不在 Maven 中央仓库）";
                } else {
                    msg = "未找到对应数据库驱动，请确认部署包 lib/ 中包含该库的 JDBC 驱动";
                }
            }
            return new DbResult(false, readableDriverError(msg, e), null, null, 0, false,
                    System.currentTimeMillis() - begin, null, List.of(), diag);
        }
    }

    /** 报错链的两个独立上限：最多翻 60 个异常对象、界面最多铺 40 行原话。 */
    private static final int MAX_WALK = 60;
    private static final int MAX_LINES = 40;

    /** {@code ORA-06512: at "SCOTT.PKG_X.FUNC_Y", line 12}：带引号与裸标识符两种写法都认。 */
    private static final Pattern FRAME = Pattern.compile(
            "(?i)\\bat\\s+(?:\"([^\"]+)\"|([A-Za-z][\\w$#.]*))\\s*,?\\s*line\\s+(\\d+)");

    /** 异常对象自身带行号：{@code ORA-06550: line 1, column 7}，指的是本次调用这一句。 */
    private static final Pattern CALL_SITE = Pattern.compile(
            "(?i)^ORA-06550:\\s*line\\s+(\\d+)");

    /**
     * 收集完整报错链：Java cause 与 {@code SQLException.getNextException()} 一起走（后者才是 PL/SQL 栈帧的住处），
     * 逐行拆开、去掉驱动的堆栈行与重复消息，再从消息里解析库内行号。
     * 包级可见只为一次性验证脚本能直接喂异常，业务调用方仍然只有 runSql。
     */
    static Diag collectDiag(Exception e) {
        List<String> chain = new ArrayList<>();
        List<Frame> frames = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        // 按对象身份记账：next 链偶尔成环（A→B→A），靠消息去重挡不住，会一直转下去
        java.util.Set<Throwable> queued = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Throwable> todo = new java.util.ArrayDeque<>();
        queued.add(e);
        todo.add(e);
        int walked = 0;
        // 两个上限各管各的：MAX_WALK 管"最多翻多少个异常对象"，MAX_LINES 管"界面最多铺几行"。
        // 早先拿同一个计数器管两件事，结果 300 条的 next 链刚走完入队就被判定超预算，一条都没收上。
        while (!todo.isEmpty() && walked < MAX_WALK && chain.size() < MAX_LINES) {
            Throwable t = todo.poll();
            walked++;
            if (t.getCause() != null && todo.size() < MAX_WALK && queued.add(t.getCause())) todo.add(t.getCause());
            if (t instanceof java.sql.SQLException sq) {
                for (java.sql.SQLException nx = sq.getNextException(); nx != null && todo.size() < MAX_WALK;
                        nx = nx.getNextException()) {
                    if (!queued.add(nx)) break;   // 已经见过 = 成环，就地停
                    todo.add(nx);
                }
            }
            String msg = t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
            for (String rawLine : msg.split("\\R")) {
                String s = rawLine.trim();
                // 驱动有时把整个 Java 栈打进来，那些行对定位库里的报错没用，留着只会盖住消息
                if (s.isEmpty() || seen.contains(s) || s.startsWith("at ") || s.startsWith("Caused by:")) continue;
                seen.add(s);
                if (chain.size() < MAX_LINES) chain.add(s.length() > 600 ? s.substring(0, 600) + "…" : s);
                Matcher m = FRAME.matcher(s);
                while (m.find() && frames.size() < 20) {
                    String path = m.group(1) != null ? m.group(1) : m.group(2);
                    frames.add(new Frame(path, number(m.group(3)), s));
                }
                Matcher c = CALL_SITE.matcher(s);
                if (c.find() && frames.size() < 20) frames.add(new Frame(null, number(c.group(1)), s));
            }
        }
        return new Diag(chain, frames);
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final Pattern PATH_OK = Pattern.compile("[A-Za-z][\\w$.#]{0,199}");

    /**
     * 按需取报错行的源码：只读、单条固定 SELECT、参数全部绑定，由用户在结果区点按钮显式发起。
     * 只有 Oracle 与达梦有 {@code ALL_SOURCE}；其它库直接说不支持，不猜。
     */
    public Map<String, Object> sourceLines(long envId, String path, int line) {
        if (path == null || !PATH_OK.matcher(path).matches()) {
            return Map.of("ok", false, "error", "对象名不合规格（只接受 OWNER.NAME[.子程序] 形式），未查询");
        }
        if (line < 1 || line > 1_000_000) return Map.of("ok", false, "error", "行号超出范围，未查询");
        Map<String, Object> env;
        try {
            env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "环境不存在");
        }
        String dbUrl = (String) env.get("db_url");
        String dbType = (String) env.get("db_type");
        if (dbType == null || dbType.isBlank()) dbType = typeOf(dbUrl);
        if (!"oracle".equals(dbType) && !"dm".equals(dbType)) {
            return Map.of("ok", false, "error", "库类型 " + (dbType == null ? "未知" : dbType)
                    + " 没有 ALL_SOURCE 这类源码视图，取不了行内原文（目前只有 Oracle 与达梦支持）");
        }
        if (dbUrl == null || dbUrl.isBlank()) return Map.of("ok", false, "error", "环境未配置 db_url");
        String[] parts = path.split("\\.");
        String owner = parts.length >= 2 ? parts[0] : null;
        String name = parts.length >= 2 ? parts[1] : parts[0];
        try (Connection conn = DriverManager.getConnection(dbUrl, (String) env.get("db_user"),
                crypto.decrypt((String) env.get("db_password_enc")))) {
            conn.setReadOnly(true);
            List<Map<String, Object>> lines = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TYPE, LINE, TEXT FROM ALL_SOURCE WHERE NAME = ?"
                            + (owner == null ? "" : " AND OWNER = ?")
                            + " AND TYPE IN ('PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION','TYPE','TRIGGER')"
                            + " AND LINE BETWEEN ? AND ? ORDER BY TYPE, LINE")) {
                int n = 1;
                ps.setString(n++, name);
                if (owner != null) ps.setString(n++, owner);
                ps.setInt(n++, Math.max(1, line - 2));
                ps.setInt(n, line + 2);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && lines.size() < 60) {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("type", rs.getString(1));
                        m.put("line", rs.getInt(2));
                        m.put("text", rs.getString(3) == null ? "" : rs.getString(3).stripTrailing());
                        m.put("hit", rs.getInt(2) == line);
                        lines.add(m);
                    }
                }
            }
            List<Map<String, Object>> errs = new ArrayList<>();
            if (lines.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT LINE, POSITION, ATTRIBUTE, TEXT FROM ALL_ERRORS WHERE NAME = ?"
                                + (owner == null ? "" : " AND OWNER = ?")
                                + " ORDER BY SEQUENCE")) {
                    int n = 1;
                    ps.setString(n++, name);
                    if (owner != null) ps.setString(n++, owner);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next() && errs.size() < 30) {
                            Map<String, Object> m = new java.util.LinkedHashMap<>();
                            m.put("line", rs.getInt(1));
                            m.put("position", rs.getInt(2));
                            m.put("attribute", rs.getString(3));
                            m.put("text", rs.getString(4));
                            errs.add(m);
                        }
                    }
                }
            }
            String note = lines.isEmpty()
                    ? (errs.isEmpty()
                        ? "ALL_SOURCE 里没有 " + name + " 的第 " + line + " 行原文——可能是当前账号看不到它的 OWNER，或对象已不在库里"
                        : "取不到源码，但 ALL_ERRORS 有 " + errs.size() + " 条编译错误：这个对象本身就编译不过，先修它")
                    : null;
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ok", true);
            out.put("dbType", dbType);
            out.put("owner", owner);
            out.put("name", name);
            out.put("line", line);
            out.put("lines", lines);
            out.put("compileErrors", errs);
            out.put("note", note);
            return out;
        } catch (Exception e) {
            Diag d = collectDiag(e);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "查 ALL_SOURCE 失败：" + (d.chain().isEmpty() ? rootMessage(e) : d.chain().get(0)));
            out.put("chain", d.chain());
            return out;
        }
    }

    /** 出参声明归一：槽位 → 声明。老通道（values 里塞 {"dir":"out","type":"varchar"}）继续可用。
     *  返回中文说明即拒绝执行：位序越界或类型认不出都不猜。 */
    private static String collectOuts(List<OutParam> outParams, List<Object> params, Map<Integer, OutParam> outs) {
        for (OutParam o : outParams == null ? List.<OutParam>of() : outParams) {
            if (o == null) continue;
            if (o.slot() < 1 || o.slot() > params.size()) {
                return "出参声明越界：第 " + o.slot() + " 位，但这条语句只有 " + params.size()
                        + " 个绑定值——SQL 被改过就不再带出参，请回 ③ 面板重新【🧩 按参数拼装】";
            }
            String t = o.type() == null ? "" : o.type().trim().toUpperCase();
            if (!JDBC_TYPES.containsKey(t)) return unknownOutType(o.name(), o.slot(), t);
            outs.put(o.slot(), new OutParam(o.slot(), o.name() == null || o.name().isBlank() ? "#" + o.slot() : o.name(), t));
        }
        for (int i = 0; i < params.size(); i++) {
            if (!(params.get(i) instanceof Map<?, ?> m) || !"out".equalsIgnoreCase(String.valueOf(m.get("dir")))) continue;
            int slot = i + 1;
            if (outs.containsKey(slot)) continue;
            String t = m.get("type") == null ? "VARCHAR" : String.valueOf(m.get("type")).trim().toUpperCase();
            if (!JDBC_TYPES.containsKey(t)) return unknownOutType("第 " + slot + " 位", slot, t);
            outs.put(slot, new OutParam(slot, m.get("name") == null ? "#" + slot : String.valueOf(m.get("name")), t));
        }
        return null;
    }

    private static String unknownOutType(String name, int slot, String type) {
        return "出参「" + name + "」（第 " + slot + " 位）声明的类型 " + (type.isEmpty() ? "（空）" : type)
                + " 无法识别，未执行——不猜类型。可识别：CURSOR / VARCHAR / CHAR / CLOB / INTEGER / INT / BIGINT /"
                + " SMALLINT / DECIMAL / NUMERIC / FLOAT / DOUBLE / DATE / TIME / TIMESTAMP / BOOLEAN / BINARY /"
                + " VARBINARY / BLOB";
    }

    private static boolean isCursor(String type) {
        return type.startsWith("CURSOR") || type.startsWith("REF");
    }

    /** 哪个驱动认哪种游标注册码不一样：JDBC 标准是 {@code Types.REF_CURSOR}(2012)，Oracle 系的老写法是
     *  {@code OracleTypes.CURSOR}(-10)。先按标准注册，驱动不认再按 -10 试一次；成功的值按驱动类名记住，
     *  同一驱动不再反复试错。这是能力探测，不是猜数据。 */
    private static final Map<String, Integer> CURSOR_CODES = new java.util.concurrent.ConcurrentHashMap<>();

    private static void registerCursor(CallableStatement cs, int n) throws java.sql.SQLException {
        String key = cs.getClass().getName();
        Integer known = CURSOR_CODES.get(key);
        if (known != null) {
            cs.registerOutParameter(n, known);
            return;
        }
        try {
            cs.registerOutParameter(n, Types.REF_CURSOR);
            CURSOR_CODES.put(key, Types.REF_CURSOR);
        } catch (java.sql.SQLException first) {
            try {
                cs.registerOutParameter(n, -10);
                CURSOR_CODES.put(key, -10);
            } catch (java.sql.SQLException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    /** 游标出参回读：Oracle 系与达门的 getObject 直接给 ResultSet；个别驱动只有 getCursor(int)，反射兜一次 */
    /** 游标出参回读：Oracle 系与达梦的 getObject 直接给 ResultSet；个别驱动只有 getCursor(int)（JDBC 接口上没有），反射兜一次 */
    private static ResultSet cursorOf(CallableStatement cs, int idx) throws java.sql.SQLException {
        try {
            Object o = cs.getObject(idx);
            if (o instanceof ResultSet rs) return rs;
            if (o != null) return null;
        } catch (java.sql.SQLException ignored) {
            // 有的驱动不支持对 CURSOR 位 getObject，转下面的 getCursor
        }
        try {
            Object r = cs.getClass().getMethod("getCursor", int.class).invoke(cs, idx);
            return r instanceof ResultSet rs ? rs : null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 驱动在 getCursor 里抛的 SQL 错误要如实传出去，别当成"没有游标"
            if (e.getCause() instanceof java.sql.SQLException se) throw se;
            return null;
        } catch (ReflectiveOperationException e) {
            return null; // 这个驱动根本没有 getCursor 方法
        }
    }

    /** 库驱动的内部话（空指针字段名之类）翻成人话，原文仍带在括号里不藏。 */
    private static String readableDriverError(String msg, Exception e) {
        String raw = msg == null ? (e.getClass().getSimpleName()) : msg;
        boolean internal = e instanceof NullPointerException
                || raw.contains("paramDesc") || raw.contains("cursorStmt")
                || raw.contains("Cannot read field") || raw.contains("null pointer");
        if (!internal) return raw;
        return "库驱动在这条语句的参数位上出了内部错误（驱动原文：" + raw + "）。"
                + "最常见的原因：存储过程的出参（REF CURSOR）被当成普通入参发送了。"
                + "这条语句没带出参声明——选一张有 dynamic_body 的 SQL 卡，在 ③ 面板点【🧩 按参数拼装】后执行，"
                + "出参会自动按游标注册并把返回的数据集单独列表；手写/改过的语句识别不出出参，只能自己按库的要求补。";
    }

    /** 连接体检：连通性 + 只读权限提示 */
    public Map<String, Object> check(long envId) {
        Map<String, Object> env;
        try {
            env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "环境不存在");
        }
        String dbUrl = (String) env.get("db_url");
        if (dbUrl == null || dbUrl.isBlank()) return Map.of("ok", false, "error", "未配置 db_url");
        try (Connection conn = DriverManager.getConnection(dbUrl,
                (String) env.get("db_user"), crypto.decrypt((String) env.get("db_password_enc")))) {
            var md = conn.getMetaData();
            String productName = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
            boolean readOnly = conn.isReadOnly();
            return Map.of("ok", true, "product", productName, "readOnly", readOnly,
                    "note", readOnly ? "连接为只读，可安全直测" : "连接可写：直测默认回滚，提交需显式确认");
        } catch (Exception e) {
            return Map.of("ok", false, "error", rootMessage(e));
        }
    }

    private void readResultSet(ResultSet rs, List<String> columns, List<List<Object>> rows) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        for (int i = 1; i <= n; i++) columns.add(md.getColumnLabel(i));
        while (rs.next() && rows.size() < MAX_ROWS) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                Object v = rs.getObject(i);
                row.add(v instanceof byte[] ? "<binary>" : v);
            }
            rows.add(row);
        }
    }

    private String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage();
    }

}
