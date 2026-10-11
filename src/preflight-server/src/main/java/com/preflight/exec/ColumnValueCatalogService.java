package com.preflight.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.CryptoService;
import com.preflight.parser.SqlParamClause;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ③ 面板候选值的只读取值服务（第 27 轮）。
 *
 * 只做两件在源码里做不到的事：
 * 1) {@code 表.列} 的**当前实际取值**（去重后列举，有上限）
 * 2) 数据字典子查询点名的那张字典表里 {@code 键=某键} 的 {@code 值 → 中文含义}
 *
 * 硬约束与第 26 轮报错定位同源（用户原话：不能在我的目标库生成任何数据、改任何结构、动任何表和字段）：
 * 连接 {@code setReadOnly(true)}、语句全部是写死的 {@code SELECT DISTINCT}、标识符先过白名单正则才准拼进语句、
 * 字典键作为绑定参数下发、行数硬截、每条查询带超时、整轮有查询条数与时间预算。
 * 上限之外不猜：值太多就直说"不列"，查不到就直说为什么，界面据此退回手工输入。
 *
 * 缓存只存"库里回过什么"（{@code column_value_cache}），推导结论不落库；键里带 {@link #CAP_VERSION}，
 * 查询能力一升级旧缓存就不冒充新结论。查失败也照实缓存，否则会每次进面板都去撞同一个死连接。
 */
@Service
public class ColumnValueCatalogService {

    /** 取值能力版本：语句形状 / 上限 / 标签口径任一变化就 +1（与 learned_files.cap 同一条铁律） */
    public static final int CAP_VERSION = 1;

    /** 一次最多列这么多值。超了就直说"值太多不列"——把 3000 个客户号列进候选条不是帮忙，是拖死页面 */
    static final int LIST_LIMIT = 200;
    /** 单条查询超时（秒）：大表上 SELECT DISTINCT 没有索引时会一直跑，到点让库自己掐掉 */
    static final int QUERY_TIMEOUT_SEC = 8;
    /** 一轮（一张卡）最多发几条、最多花多久：进面板就查，这两条上限是"不拖死"的保险 */
    static final int MAX_QUERIES = 12;
    static final long BATCH_BUDGET_MS = 20_000L;

    /** 列名 / 表名白名单：过不了的直接拒，绝不拼进语句 */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]{0,63}");
    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}");

    /**
     * 运行期才知道的值：源码这一位写的是 <code>${...}</code> / <code>#{...}</code>，进面板时人还没填。
     * 字典的键长这样时**不能发给库**——发出去就是拿字面量 "${OP_CODE}" 去比一个数字列，
     * 库回一句"字符串转换出错"，看着像表和列的问题，其实是我们自己把占位符当成了值。
     */
    private static final Pattern RUNTIME_VALUE = Pattern.compile("[#$]\\{");

    private final CryptoService crypto;
    private final JdbcTemplate jdbc;
    /** 列注释的第二条通路（盘上建表脚本）；没有它时 {@link #columnsOf} 只认库内那一份 */
    private final com.preflight.learn.DdlCommentIndex ddl;
    private final ObjectMapper mapper = new ObjectMapper();

    public ColumnValueCatalogService(CryptoService crypto, javax.sql.DataSource dataSource,
                                     com.preflight.learn.DdlCommentIndex ddl) {
        this.crypto = crypto;
        this.jdbc = new JdbcTemplate(dataSource);
        this.ddl = ddl;
    }

    /** 一个取值目标：字段真值（column）或字典项（dict）。table/column 都是**去别名后**的真实标识符。 */
    public record Target(String kind, String table, String column, String dictKey,
                         String keyColumn, String valueColumn, String labelColumn) {

        public static Target ofColumn(String table, String column) {
            String bare = bare(column);
            return new Target("column", table, bare, "", null, null, null);
        }

        public static Target ofDict(SqlParamClause.DictRef ref) {
            if (ref == null) return null;
            return new Target("dict", ref.table(), bare(ref.valueColumn()),
                    ref.keyValue() == null ? "" : ref.keyValue(),
                    ref.keyColumn(), ref.valueColumn(), ref.labelColumn());
        }

        /** 缓存与报错都要说的"这一项是什么" */
        public String describe() {
            return "dict".equals(kind)
                    ? "字典 " + table + "." + valueColumn + "（" + keyColumn + "='" + dictKey + "'）"
                    : table + "." + column;
        }

        private static String bare(String col) {
            return bareColumn(col);
        }
    }

    /**
     * 源码里这一列常写成 {@code c.dept_province}（带表别名），本地元数据与真实字典视图里都只有裸列名。
     * 所有"拿列名去查东西"的地方都从这里过一遍——别名归 table 管，不该夹在列名里。
     */
    public static String bareColumn(String col) {
        if (col == null) return null;
        String s = col.trim().replaceAll("\\.\\s*", ".");
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /**
     * 一项的结论。status：ok / too-many / empty / failed / invalid（标识符不合规，压根没发给库）。
     *
     * 这里刻意不带"列的中文含义"：结论按目标去重，而一个字典目标（{@code t_sjzd} 的一条 zdmc）会被好几张表
     * 的好几列共用，说明却属于**被过滤的那一列**。所以说明由调用方按 参数→表.列 现取（{@link #columnComment}）。
     */
    public record Outcome(Target target, String status, List<SqlParamClause.Labeled> values,
                          String note, String queriedAt, Integer rowsSeen, boolean fromCache) {}

    // ==================== 对外入口 ====================

    /**
     * 一批目标 → 候选值。缓存优先；{@code force=true} 才回库重查。
     * 一个环境只开一条只读连接，逐条查，撞到条数或时间预算就停在那里并把原因带回去。
     */
    public Map<String, Object> load(long projectId, long envId, List<Target> targets, boolean force) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("envId", envId);
        out.put("capVersion", CAP_VERSION);
        out.put("listLimit", LIST_LIMIT);
        List<Outcome> outcomes = new ArrayList<>();
        out.put("outcomes", outcomes);
        if (targets == null || targets.isEmpty()) {
            out.put("ok", true);
            out.put("note", "这张卡没有可查的字段（要么没定到表.列，要么不是多选/字典过滤，按约定不查库）");
            return out;
        }
        Map<String, Object> env;
        try {
            env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("note", "环境 #" + envId + " 不存在，没查库");
            return out;
        }
        Long envProject = env.get("project_id") == null ? null : ((Number) env.get("project_id")).longValue();
        if (envProject != null && envProject != projectId) {
            out.put("ok", false);
            out.put("note", "环境 #" + envId + " 不属于项目 #" + projectId + "，没查库");
            return out;
        }
        int cached = 0;
        List<Target> todo = new ArrayList<>();
        for (Target t : targets) {
            String why = invalid(t);
            if (why != null) {
                outcomes.add(new Outcome(t, "invalid", List.of(), why, null, null, false));
                continue;
            }
            Outcome hit = readCache(projectId, envId, t);
            if (hit != null && !force) { outcomes.add(hit); cached++; } else todo.add(t);
        }
        out.put("cached", cached);
        out.put("targets", targets.size());
        if (todo.isEmpty()) { out.put("ok", true); out.put("queried", 0); return out; }

        String dbUrl = (String) env.get("db_url");
        if (dbUrl == null || dbUrl.isBlank()) {
            out.put("ok", false);
            out.put("queried", 0);
            out.put("note", "环境未配置 db_url，取不到真实值");
            for (Target t : todo) outcomes.add(new Outcome(t, "failed", List.of(),
                    "环境未配置 db_url，这一项没查", null, null, false));
            return out;
        }
        long deadline = System.currentTimeMillis() + BATCH_BUDGET_MS;
        String ts = now();
        int ran = 0;
        int skipped = 0;
        boolean ok = true;
        String connError = null;
        List<Outcome> results = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl, (String) env.get("db_user"),
                crypto.decrypt((String) env.get("db_password_enc")))) {
            conn.setReadOnly(true);                      // 硬约束：这条连接发不出任何写操作
            for (Target t : todo) {
                if (ran >= MAX_QUERIES) { skipped++; results.add(skipped(t, ts,
                        "本轮查询条数预算（" + MAX_QUERIES + " 条）已用完，这一项没查——点【↻ 只读查库取候选值】接着查")); continue; }
                if (System.currentTimeMillis() > deadline) { skipped++; results.add(skipped(t, ts,
                        "本轮查询时间预算（" + BATCH_BUDGET_MS / 1000 + " 秒）已用完，这一项没查——点【↻ 只读查库取候选值】接着查")); continue; }
                ran++;
                results.add(query(conn, t, ts));
            }
        } catch (Exception e) {
            ok = false;
            connError = firstMessage(e);
            // 连接都没建起来：前面没轮到的每一项都是"没查"，不能写成"库里没有"
            Set<String> done = new HashSet<>();
            for (Outcome o : results) done.add(o.target().describe());
            for (Target t : todo) if (!done.contains(t.describe())) results.add(new Outcome(t, "failed", List.of(),
                    "只读连接没建起来：" + connError + "（这一项没查，不是库里没有值）", null, null, false));
        }
        int failed = 0;
        for (Outcome o : results) {
            outcomes.add(o);
            if ("skipped".equals(o.status())) continue;   // 预算没用掉的查询不是库的结论，不配进缓存
            writeCache(projectId, envId, o, ts);
            if ("failed".equals(o.status())) failed++;
        }
        out.put("queried", ran);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("ok", ok);
        if (!ok) out.put("note", "只读查询失败：" + connError);
        else if (skipped > 0) out.put("note", "有 " + skipped + " 项因为预算用完没查，见各条说明");
        return out;
    }

    private Outcome skipped(Target t, String ts, String note) {
        return new Outcome(t, "skipped", List.of(), note, null, null, false);
    }

    // ==================== 单条查询 ====================

    private Outcome query(Connection conn, Target t, String ts) {
        String sql = "dict".equals(t.kind())
                ? "SELECT DISTINCT " + t.valueColumn() + ", " + t.labelColumn() + " FROM " + t.table()
                  + " WHERE " + t.keyColumn() + " = ? AND " + t.valueColumn() + " IS NOT NULL ORDER BY 1"
                : "SELECT DISTINCT " + t.column() + " FROM " + t.table()
                  + " WHERE " + t.column() + " IS NOT NULL ORDER BY 1";
        List<SqlParamClause.Labeled> vals = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);       // 到点由库自己掐：大表无索引的 DISTINCT 不能让它挂着
            ps.setMaxRows(LIST_LIMIT + 1);               // 多取一行只用来判"是不是超上限"
            ps.setFetchSize(50);
            if ("dict".equals(t.kind())) ps.setString(1, t.dictKey());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && vals.size() <= LIST_LIMIT) {
                    String v = rs.getString(1);
                    if (v == null) continue;
                    vals.add(new SqlParamClause.Labeled(v, "dict".equals(t.kind()) ? rs.getString(2) : null));
                }
            }
        } catch (Exception e) {
            // 失败也照实带回去并缓存：不缓存的话每次进面板都重撞同一个错误
            String why = firstMessage(e);
            return new Outcome(t, "failed", List.of(),
                    "查不了（" + t.describe() + "）：" + why + " " + guess(t, why),
                    null, null, false);
        }
        if (vals.size() > LIST_LIMIT) {
            return new Outcome(t, "too-many", List.of(),
                    "这一列去重后至少 " + (LIST_LIMIT + 1) + " 个不同值，超过 " + LIST_LIMIT + " 个就不列了"
                    + "（列出来既没意义又拖页面）——请手工输入", ts, LIST_LIMIT + 1, false);
        }
        if (vals.isEmpty()) {
            return new Outcome(t, "empty", List.of(),
                    "库里这一列现在一个非空值都没有（" + t.describe() + "）——可能是空表，也可能这张表在当前账号下是另一张同义词",
                    ts, 0, false);
        }
        return new Outcome(t, "ok", List.copyOf(vals), null, ts, vals.size(), false);
    }

    /** 标识符合规性：过不了白名单就不进语句，连库都不碰 */
    private String invalid(Target t) {
        if (t == null || t.table() == null || t.table().isBlank()) return "没定到表名，不查库";
        if (!TABLE.matcher(t.table()).matches()) return "表名「" + t.table() + "」不合标识符规则，没发给库";
        if ("dict".equals(t.kind())) {
            for (String s : new String[]{t.keyColumn(), t.valueColumn(), t.labelColumn()}) {
                if (s == null || !IDENT.matcher(s).matches()) return "字典列名「" + s + "」不合标识符规则，没发给库";
            }
            if (t.dictKey() == null) return "字典键是 null，没法定这一项，没发给库";
            if (RUNTIME_VALUE.matcher(t.dictKey()).find())
                return "字典的键「" + t.dictKey() + "」本身是运行期传进来的（源码里就是个占位符），"
                        + "进面板时还不知道要按哪个键翻译，不查库";
            return null;
        }
        if (t.column() == null || t.column().isBlank()) return "没定到列名，不查库";
        if (!IDENT.matcher(t.column()).matches()) return "列名「" + t.column() + "」不合标识符规则，没发给库";
        return null;
    }

    // ==================== 缓存读写 ====================

    private Outcome readCache(long projectId, long envId, Target t) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, values_json, note, rows_seen, queried_at FROM column_value_cache "
                        + "WHERE project_id=? AND env_id=? AND kind=? AND table_name=? AND column_name=? "
                        + "AND dict_key=? AND cap_ver=?",
                projectId, envId, t.kind(), lower(t.table()), lower(nvl(t.column())), nvl(t.dictKey()), CAP_VERSION);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        List<SqlParamClause.Labeled> vals = "ok".equals(status) ? readValues(row.get("values_json")) : List.of();
        Integer seen = row.get("rows_seen") == null ? null : ((Number) row.get("rows_seen")).intValue();
        return new Outcome(t, status, vals, (String) row.get("note"), (String) row.get("queried_at"), seen, true);
    }

    /** 写路径幂等：同键反复查只更新那一行，不堆历史（历史值不是事实，只会让人误读成"库里有过这个值"） */
    private void writeCache(long projectId, long envId, Outcome o, String ts) {
        Target t = o.target();
        jdbc.update("INSERT INTO column_value_cache(project_id, env_id, kind, table_name, column_name, dict_key, "
                        + "cap_ver, status, values_json, rows_seen, note, queried_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(project_id, env_id, kind, table_name, column_name, dict_key, cap_ver) DO UPDATE "
                        + "SET status=excluded.status, values_json=excluded.values_json, rows_seen=excluded.rows_seen, "
                        + "note=excluded.note, queried_at=excluded.queried_at",
                projectId, envId, t.kind(), lower(t.table()), lower(nvl(t.column())), nvl(t.dictKey()), CAP_VERSION,
                o.status(), toJson(o.values()), o.rowsSeen(), o.note(), ts);
    }

    /** 一列的库内元数据：中文说明 + 声明类型 + 定长（全部来自本地学到的 {@code table_meta}，没学到就不给） */
    public record ColMeta(String comment, String dataType, int charLength) {}

    /**
     * 一张表的列元数据（列名小写 → 元数据），一次读回。
     *
     * <p>列说明与列类型只有这一个出口，但它有<b>两条通路</b>：
     * ① 本地库 {@code table_meta}（【元数据反哺】连库拉回来的，那是当前真实结构，优先）；
     * ② 盘上 DB 仓库的建表脚本 {@code comment on column}（第四十三轮补的第二条永久通路——
     * 实测这台库只学过 12 张表，而 {@code db_prod} 工作副本里 1113 个脚本写着列注释，
     * 与第卅五轮"过程源码在盘上"是同一件事：库停了也照样取得到语义）。
     * 盘上那份只补空缺，不覆盖库内已学到的值。
     *
     * <p>原来一列查一次，一条 30 列的语句要把同一份 {@code columns_json} 读 30 遍——追调用链时这点往返
     * 会被计时抓出来。表名两边写法常不一致（{@code kdcc.t_x} / {@code T_X}），所以整名不中时按裸表名兜底；
     * 两条通路一个键都没取到才返回空表，由调用方决定「不给假说明」。
     */
    public Map<String, ColMeta> columnsOf(long projectId, String table) {
        Map<String, ColMeta> out = new LinkedHashMap<>();
        if (table == null || table.isBlank()) return out;
        List<Map<String, Object>> rows;
        try {
            // 只按表名取行，列名一律在 Java 里 equalsIgnoreCase 比：
            // 库里的 columns_json 大小写跟着建表语句走（Oracle/达梦是大写，MySQL/Gauss 常见小写），
            // 拿 LIKE 当预过滤等于把"匹配口径"交给 SQLite 的 case_sensitive_like 开关——一份 JSON 里几十列，
            // 本来就不该靠字符串子串去猜。
            rows = jdbc.queryForList("SELECT columns_json FROM table_meta WHERE project_id=? "
                            + "AND (LOWER(table_name)=? OR LOWER(SUBSTR(table_name, INSTR(table_name,'.')+1))=?) LIMIT 8",
                    projectId, lower(table), lower(table.contains(".")
                            ? table.substring(table.lastIndexOf('.') + 1) : table));
        } catch (Exception ignored) {
            rows = List.of();     // 说明栏是锦上添花，读不到就不给，不能因为它把候选值整轮带崩
        }
        for (Map<String, Object> row : rows) {
            for (Map<String, Object> col : readColumns(row.get("columns_json"))) {
                Object name = col.get("name");
                if (name == null) continue;
                String key = lower(String.valueOf(name));
                Object c = col.get("comment");
                ColMeta add = new ColMeta(c == null || String.valueOf(c).isBlank() ? null : String.valueOf(c),
                        col.get("data_type") == null ? null : String.valueOf(col.get("data_type")),
                        col.get("char_length") instanceof Number n ? n.intValue() : 0);
                out.put(key, best(out.get(key), add));
            }
        }
        // 第二条通路：盘上建表脚本。逐列去问一次会把整条链拖成几十次目录扫描，所以一张表只问一次
        if (ddl != null) {
            for (Map.Entry<String, com.preflight.learn.DdlCommentIndex.DdlCol> e
                    : ddl.columnsOf(projectId, table).entrySet()) {
                com.preflight.learn.DdlCommentIndex.DdlCol d = e.getValue();
                out.put(e.getKey(), best(out.get(e.getKey()),
                        new ColMeta(d.comment().isBlank() ? null : d.comment(), d.dataType(), 0)));
            }
        }
        return out;
    }

    /** 同一列的两个出处：说明与类型各取「先见到的那个非空值」，不互相覆盖成空（库内那份先，所以它赢） */
    private static ColMeta best(ColMeta old, ColMeta add) {
        return old == null ? add : new ColMeta(old.comment() != null ? old.comment() : add.comment(),
                old.dataType() != null ? old.dataType() : add.dataType(),
                old.charLength() > 0 ? old.charLength() : add.charLength());
    }

    /**
     * 列的中文含义：{@link #columnsOf} 的薄封装，口径与它完全一致。
     *
     * 入参是**被过滤的那一列**（业务表.业务列），不是字典表的取值列——字典目标在缓存里是多张表共用的，
     * 谁的业务列都不能挂到字典头上。
     */
    public String columnComment(long projectId, String table, String column) {
        if (table == null || column == null || column.isBlank()) return null;
        ColMeta m = columnsOf(projectId, table).get(lower(bareColumn(column)));
        return m == null ? null : m.comment();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readColumns(Object json) {
        if (json == null) return List.of();
        try {
            Object v = mapper.readValue(json.toString(), List.class);
            return v == null ? List.of() : (List<Map<String, Object>>) v;
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<SqlParamClause.Labeled> readValues(Object json) {
        if (json == null) return List.of();
        try {
            List<Map<String, Object>> raw = mapper.readValue(json.toString(), List.class);
            List<SqlParamClause.Labeled> out = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                out.add(new SqlParamClause.Labeled(String.valueOf(m.get("value")),
                        m.get("label") == null ? null : String.valueOf(m.get("label"))));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String now() {
        return jdbc.queryForObject("SELECT datetime('now','localtime')", String.class);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private String firstMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /**
     * 失败原因只转述库回的原话（铁律：不编行号、不编原因）。这里只在**原话自己点名了**表/列/超时时补一句人话，
     * 原话没指向的东西一律不说——曾经无条件写着"可能是账号看不到这张表，也可能是这一列不在表上"，
     * 结果一个"字符串转换出错"也被安上两个假原因。
     */
    private static String guess(Target t, String why) {
        String m = why == null ? "" : why.toLowerCase(Locale.ROOT);
        if (m.contains("不存在") || m.contains("does not exist") || m.contains("doesn't exist")
                || m.contains("no such table")) {
            return "库自己说这张表找不到（" + t.table() + "）——当前账号看不到它，或者它在这一库里根本不叫这个名。";
        }
        if (m.contains("列名") || m.contains("标识符无效") || m.contains("invalid identifier")
                || m.contains("unknown column") || m.contains("invalid column")) {
            return "库自己说这一列不在表上（" + t.describe() + "）。";
        }
        if (m.contains("timeout") || m.contains("超时") || m.contains("cancel")) {
            return "这一列去重取值超过 " + QUERY_TIMEOUT_SEC + " 秒被掐了，不算库里没有值。";
        }
        return "原因照库回的原话记着，不加猜测——界面按手工输入处理。";
    }
}
