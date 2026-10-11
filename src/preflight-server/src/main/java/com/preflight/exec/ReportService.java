package com.preflight.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.ai.CommitTargetService;
import com.preflight.common.AuditService;
import com.preflight.common.UserContext;
import com.preflight.learn.OutParamService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 提测结论单的取数与固化（第卅三轮第一批，设计见 docs/08 §3~§5）。
 *
 * <p>与 {@link ReleaseController} 的分工：老检查单按<b>文件</b>出行、条目里没有执行证据；
 * 这里按<b>目标</b>（接口卡 / SQL 卡）出行，每行带回 run_id。两者共用 release_checklists 一张表，
 * 靠 kind 列分流，老页面与老数据完全不受影响。
 *
 * <p>三处口径不许另起炉灶：区间＝commit id BETWEEN + 项目过滤（与老检查单一致）；
 * 归因＝{@link CommitTargetService} 单点（行级命中优先，收窄不了才退回整文件并照实标出）；
 * 指纹＝只读 PreFlight 自己的元数据，一个字节都不碰目标库。
 *
 * <p>第一批<b>不做</b>自动定性（通过/失败/无关三分类），只把"有没有证据、证据在哪一行留痕"摆出来。
 */
@Service
public class ReportService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CommitTargetService commitTargets;
    private final OutParamService outParams;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public ReportService(JdbcTemplate jdbc, ObjectMapper mapper, CommitTargetService commitTargets,
                         OutParamService outParams, AuditService audit,
                         org.springframework.transaction.PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.commitTargets = commitTargets;
        this.outParams = outParams;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * 归因档位的中文说法，一处定义三个出口（结论单网页、行内表格、过滤下拉）。
     * chain 档是第 44 轮加的：改的是这张卡调用链上的服务层 / DAO 文件，按代码可达给出、可能偏宽。
     * 有序（LinkedHashMap）：过滤下拉直接按这个顺序渲染，免得前端再自己编一遍档位与话术。
     */
    public static final Map<String, String> BASIS_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("line", "按改动行命中语句");
        m.put("file", "只能到整文件");
        m.put("chain", "按调用链（服务层文件）");
        BASIS_LABELS = java.util.Collections.unmodifiableMap(m);
    }

    /** 一个受影响目标在结论单上的一行 */
    private static class Row {
        String unitKind;
        long unitId;
        String unitName;
        String basis = "file";
        /** 这一行有没有"卡片自己的来源文件"归因；只有调用链归因的行，档位要落成 chain（第 44 轮） */
        boolean fromCardFile;
        boolean fromChainOnly;
        Set<Long> commits = new TreeSet<>();
        Set<String> vcsTypes = new TreeSet<>();
        /** 这一行的归因落在哪些文件上（从 commit_targets 的 anchor 还原），出参证据按它收窄 */
        Set<String> files = new LinkedHashSet<>();
        /** 这个目标被区间改动碰到的出入参字段；null＝还没算（草稿期读取时现算） */
        List<Map<String, Object>> outParam;
        /** 出参这一列<b>没核对上</b>的原因；null＝核过了（没核过必须在缺口区挂在脸上，不许留个空格让人以为"没变动"） */
        String outParamSkip;
        int caseCount;
        Long caseId;
        String caseName;
        Long runId;
        String runStatus;
        Long runEnvId;
        Long runCommitId;
        String runAt;
        String note;
    }

    // ---------- 生成 ----------

    public Map<String, Object> generate(long projectId, long fromCommitId, long toCommitId,
                                        Long envId, String title, Long supersedesId) {
        long lo = Math.min(fromCommitId, toCommitId);
        long hi = Math.max(fromCommitId, toCommitId);

        Map<String, Object> env = null;
        if (envId != null) {
            List<Map<String, Object>> es = jdbc.queryForList(
                    "SELECT id, name, db_type FROM environments WHERE id=? AND project_id=?", envId, projectId);
            if (es.isEmpty()) return undetermined("所选环境 #" + envId + " 不属于这个项目");
            env = es.get(0);
        }

        List<Map<String, Object>> commits = jdbc.queryForList("""
                SELECT c.id, c.revision, r.id AS repo_id, r.name AS repo_name, r.learned_revision, r.vcs_type
                FROM commits c JOIN repos r ON r.id=c.repo_id
                WHERE r.project_id=? AND c.id BETWEEN ? AND ? ORDER BY c.id""", projectId, lo, hi);
        if (commits.isEmpty()) {
            return undetermined("区间里没有提交（起止选错，或这个项目的仓库还没学习）");
        }
        List<Long> commitIds = commits.stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        Set<Long> commitSet = new LinkedHashSet<>(commitIds);
        // 提交 → 仓库版本类型：粗归因要说清"为什么只到整文件"，git/SVN 的出路完全不同
        Map<Long, String> commitVcs = new LinkedHashMap<>();
        for (Map<String, Object> c : commits) {
            Object v = c.get("vcs_type");
            commitVcs.put(((Number) c.get("id")).longValue(), v == null ? "未记录" : String.valueOf(v));
        }

        commitTargets.ensure(commitIds);   // 算过的直接读缓存，没算过的按"改动行落在哪条语句"收窄
        List<Map<String, Object>> attrs = jdbc.queryForList("""
                SELECT unit_kind, unit_id, basis, anchor, commit_id FROM commit_targets
                WHERE commit_id IN (""" + join(commitIds) + ") AND unit_kind<>'none' ORDER BY unit_kind, unit_id");
        if (attrs.isEmpty()) {
            return undetermined("区间内改动的文件一个都没绑到接口卡或 SQL 卡（可能没学习，或改动都落在没建卡的文件里），"
                    + "按目标出不出结论单。要按文件看范围，用「起飞检查单」");
        }

        Map<String, Row> rows = new LinkedHashMap<>();
        for (Map<String, Object> a : attrs) {
            String kind = (String) a.get("unit_kind");
            long uid = ((Number) a.get("unit_id")).longValue();
            Row r = rows.computeIfAbsent(kind + ":" + uid, k -> {
                Row x = new Row();
                x.unitKind = kind;
                x.unitId = uid;
                return x;
            });
            long cid = ((Number) a.get("commit_id")).longValue();
            r.commits.add(cid);
            r.vcsTypes.add(commitVcs.get(cid));
            String file = anchorFile(a.get("anchor"));
            if (!file.isEmpty()) r.files.add(file);
            if ("line".equals(a.get("basis"))) r.basis = "line";
            else if ("chain".equals(a.get("basis"))) r.fromChainOnly = true;
            else r.fromCardFile = true;
        }
        // 一张卡只被调用链上的文件碰到过（没有来源文件、也没有按行命中）→ 档位单独标 chain，
        // 结论单和缺口区都能说清"这一行的范围是按代码可达圈出来的，比实际改动宽"
        rows.values().forEach(r -> {
            if (!"line".equals(r.basis) && r.fromChainOnly && !r.fromCardFile) r.basis = "chain";
        });

        fillNames(rows);
        fillEvidence(rows, commitSet);
        fillOutParams(rows, commits.size());
        List<Map<String, Object>> gaps = collectGaps(rows);
        Map<String, Object> fingerprint = fingerprint(projectId, env, rows, commits);
        Map<String, Object> scope = scopeMeta(commits, rows, gaps, env);

        final Long supersedes = supersedesId;
        final Object envPk = env == null ? null : env.get("id");
        // 生成完必须把这张单的 id 回给调用方：界面要能直接跳进这份草稿，验证脚本也不能靠"猜最新一条"
        java.util.concurrent.atomic.AtomicReference<Long> newId = new java.util.concurrent.atomic.AtomicReference<>();
        tx.executeWithoutResult(status -> {
            org.springframework.jdbc.support.KeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO release_checklists(project_id, title, from_revision, to_revision, "
                                + "snapshot_json, signoffs_json, created_by, kind, status, env_id, fingerprint, supersedes_id) "
                                + "VALUES (?,?,?,?,?,?,?,?,'draft',?,?,?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, projectId);
                ps.setString(2, blankTo(title, "提测结论单"));
                ps.setString(3, String.valueOf(lo));
                ps.setString(4, String.valueOf(hi));
                ps.setString(5, json(scope));
                ps.setString(6, "[]");
                ps.setString(7, UserContext.username());
                ps.setString(8, "report");
                if (envPk == null) ps.setNull(9, java.sql.Types.INTEGER); else ps.setObject(9, envPk);
                ps.setString(10, json(fingerprint));
                if (supersedes == null) ps.setNull(11, java.sql.Types.INTEGER); else ps.setLong(11, supersedes);
                return ps;
            }, kh);
            Long reportId = kh.getKey() == null ? null : kh.getKey().longValue();
            newId.set(reportId);
            List<Object[]> rowArgs = new ArrayList<>();
            for (Row r : rows.values()) {
                rowArgs.add(new Object[]{reportId, r.unitKind, r.unitId, r.unitName, r.basis,
                        r.commits.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""),
                        r.caseId, r.caseName, r.runId, r.runStatus, r.runEnvId, r.runCommitId, r.runAt, r.note});
            }
            jdbc.batchUpdate("INSERT OR IGNORE INTO report_rows(report_id, unit_kind, unit_id, unit_name, basis, "
                    + "commit_ids, case_id, case_name, run_id, run_status, run_env_id, run_commit_id, run_at, note) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rowArgs);
            List<Object[]> gapArgs = new ArrayList<>();
            for (Map<String, Object> g : gaps) {
                gapArgs.add(new Object[]{reportId, g.get("unit_kind"), g.get("unit_id"), g.get("unit_name"),
                        g.get("gap_type"), g.get("detail")});
            }
            if (!gapArgs.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO report_gaps(report_id, unit_kind, unit_id, unit_name, gap_type, detail) "
                        + "VALUES (?,?,?,?,?,?)", gapArgs);
            }
            audit.log("report.generate", "report#" + reportId,
                    "rows=" + rows.size() + " gaps=" + gaps.size() + " scope=#" + lo + "..#" + hi);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", newId.get());
        out.put("rows", rows.size());
        out.put("gaps", gaps.size());
        out.put("fingerprint", fingerprint);
        return out;
    }

    private Map<String, Object> undetermined(String why) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("undetermined", true);
        out.put("error", "区间不可判定：" + why);
        return out;
    }

    private Map<String, Object> scopeMeta(List<Map<String, Object>> commits, Map<String, Row> rows,
                                          List<Map<String, Object>> gaps, Map<String, Object> env) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("commitCount", commits.size());
        meta.put("fromRevision", commits.get(0).get("revision"));
        meta.put("toRevision", commits.get(commits.size() - 1).get("revision"));
        meta.put("targets", rows.size());
        meta.put("line_basis", rows.values().stream().filter(r -> "line".equals(r.basis)).count());
        meta.put("chain_basis", rows.values().stream().filter(r -> "chain".equals(r.basis)).count());
        meta.put("with_evidence", rows.values().stream().filter(r -> r.runId != null).count());
        meta.put("without_evidence", rows.values().stream().filter(r -> r.runId == null).count());
        meta.put("out_param_changed", rows.values().stream()
                .filter(r -> !ownOf(r.outParam, "out", r.basis).isEmpty()).count());
        meta.put("gaps", gaps.size());
        meta.put("env", env == null ? null : env.get("name"));
        return meta;
    }

    /** 目标名一次批量取，禁逐行相关子查询 */
    private void fillNames(Map<String, Row> rows) {
        for (String kind : List.of("interface", "db_object")) {
            String ids = idsOf(rows, kind);
            if ("0".equals(ids)) continue;
            String table = "interface".equals(kind) ? "interfaces" : "db_objects";
            for (Map<String, Object> u : jdbc.queryForList("SELECT id, name FROM " + table + " WHERE id IN (" + ids + ")")) {
                Row r = rows.get(kind + ":" + ((Number) u.get("id")).longValue());
                if (r != null) r.unitName = (String) u.get("name");
            }
        }
    }

    /** 每行带回落点：启用用例数 + 该目标<b>最近一次</b>执行（含它挂的用例、环境、提交） */
    private void fillEvidence(Map<String, Row> rows, Set<Long> commitSet) {
        for (String kind : List.of("interface", "db_object")) {
            List<Row> part = rows.values().stream().filter(r -> r.unitKind.equals(kind)).toList();
            if (part.isEmpty()) continue;
            String ids = idsOf(rows, kind);

            Map<Long, Map<String, Object>> latestRun = new LinkedHashMap<>();
            for (Map<String, Object> run : jdbc.queryForList("""
                    SELECT r.target_id, r.id, r.case_id, r.status, r.env_id, r.commit_id, r.executed_at
                    FROM test_runs r JOIN (
                        SELECT target_id, MAX(id) AS mid FROM test_runs
                        WHERE target_type=? AND target_id IN (""" + ids + ") GROUP BY target_id) m ON m.mid=r.id", kind)) {
                latestRun.put(((Number) run.get("target_id")).longValue(), run);
            }

            Map<Long, Integer> caseCount = new LinkedHashMap<>();
            for (Map<String, Object> c : jdbc.queryForList("""
                    SELECT target_id, COUNT(*) AS n FROM test_cases
                    WHERE target_type=? AND target_id IN (""" + ids + ") AND enabled=1 GROUP BY target_id", kind)) {
                caseCount.put(((Number) c.get("target_id")).longValue(), ((Number) c.get("n")).intValue());
            }

            Map<Long, String> caseName = new LinkedHashMap<>();
            Set<Long> runCaseIds = new TreeSet<>();
            latestRun.values().forEach(r -> {
                if (r.get("case_id") != null) runCaseIds.add(((Number) r.get("case_id")).longValue());
            });
            if (!runCaseIds.isEmpty()) {
                for (Map<String, Object> c : jdbc.queryForList("SELECT id, name FROM test_cases WHERE id IN ("
                        + runCaseIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")")) {
                    caseName.put(((Number) c.get("id")).longValue(), (String) c.get("name"));
                }
            }

            for (Row r : part) {
                r.caseCount = caseCount.getOrDefault(r.unitId, 0);
                Map<String, Object> run = latestRun.get(r.unitId);
                if (run == null) continue;
                r.runId = ((Number) run.get("id")).longValue();
                r.runStatus = (String) run.get("status");
                r.runEnvId = run.get("env_id") == null ? null : ((Number) run.get("env_id")).longValue();
                r.runCommitId = run.get("commit_id") == null ? null : ((Number) run.get("commit_id")).longValue();
                r.runAt = run.get("executed_at") == null ? null : String.valueOf(run.get("executed_at"));
                if (run.get("case_id") == null) {
                    r.note = "run_direct";   // 直测留痕：没挂用例，不计入用例通过率
                } else {
                    long cid = ((Number) run.get("case_id")).longValue();
                    r.caseId = cid;
                    r.caseName = caseName.get(cid);
                    if (r.caseName == null) r.note = "run_case_missing";
                }
                if (r.runCommitId != null && !commitSet.contains(r.runCommitId)) r.note = "run_out_of_scope";
            }
        }
    }

    // ---------- 出参证据 ----------

    /** 草稿期现算出参证据时，一个区间最多扫多少个提交的 diff（超了照实说"这一列没核对"，不许装作扫全了） */
    static final int OUT_SCAN_CAP = 500;

    /**
     * commit_targets 的 anchor 还原成"哪个文件"：文件级是整条仓库相对路径（取文件名），
     * 行级是「文件名.语句id」（去掉末段语句 id）。只按文件名比对——同一目标在同一提交里
     * 被多个文件命中时，归因本来就已经并成一行，出参证据跟着这一行一起给。
     */
    static String anchorFile(Object anchor) {
        String s = anchor == null ? "" : String.valueOf(anchor).trim().replace('\\', '/');
        if (s.isEmpty()) return "";
        int slash = s.lastIndexOf('/');
        if (slash >= 0) return s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static String basename(String path) {
        String s = path.replace('\\', '/');
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** 同一个提交的 diff 只读一遍盘上快照：一张单几十个提交、几十个行共用这份缓存 */
    private OutParamService.Scan scanCached(long commitId, Map<Long, OutParamService.Scan> cache) {
        return cache.computeIfAbsent(commitId, outParams::scan);
    }

    /**
     * 这个目标被区间改动碰到的出入参字段。
     *
     * <p>只认「这一行归因到的文件」里的改动：同一提交改了别文件的字段不算到这行头上（宁窄不宽）。
     * {@code io="block"} 是"整块声明增删"——它说的是视图/服务本身动了，不是某个字段，
     * 那句话归因行自己会说，这里不收，免得界面出现一个叫"(整块声明)"的假字段。
     *
     * <p>{@code ownSql} 是这一行（SQL 卡）自己的语句文本：同一文件里第七条语句改了 {@code xxnr}，
     * 不能让第一条语句的行也说"我改了 xxnr"。落得进自己语句的写 {@code scope=statement}，
     * 落不进但确实在这台文件里的写 {@code scope=file}，界面照实分开显示。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> outParamEvidence(Set<Long> commits, Set<String> files,
                                                       Map<Long, OutParamService.Scan> cache, String ownSql) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (long cid : new TreeSet<>(commits)) {
            for (OutParamService.Touched t : scanCached(cid, cache).touched()) {
                if ("block".equals(t.io()) || t.file() == null) continue;
                String file = basename(t.file());
                if (!files.contains(file)) continue;
                String key = t.field().toLowerCase(java.util.Locale.ROOT) + "|" + t.io() + "|" + t.change();
                Map<String, Object> one = seen.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("field", t.field());
                    m.put("io", t.io());
                    m.put("change", t.change());
                    m.put("via", t.via());
                    m.put("file", file);
                    m.put("scope", scopeOf(ownSql, t.field()));
                    m.put("commit_ids", new ArrayList<Long>());
                    return m;
                });
                if ("statement".equals(scopeOf(ownSql, t.field()))) one.put("scope", "statement");
                List<Long> cs = (List<Long>) one.get("commit_ids");
                if (!cs.contains(cid)) cs.add(cid);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** 字段名 → 整词正则：一张单几十行反复用同一批字段名，别每次重新编译 */
    private static final java.util.Map<String, java.util.regex.Pattern> WORD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 这个字段算不算"写在这条语句自己里"：按整词认（免得 id 命中 select）。
     * 卡上没有语句体可比时给 unknown——不猜窄也不猜宽，由界面照实说。
     */
    static String scopeOf(String ownSql, String field) {
        if (ownSql == null || ownSql.isBlank() || field == null || field.isBlank()) return "unknown";
        java.util.regex.Pattern p = WORD_CACHE.computeIfAbsent(field, f -> java.util.regex.Pattern.compile(
                "(?<![A-Za-z0-9_$])" + java.util.regex.Pattern.quote(f) + "(?![A-Za-z0-9_$])",
                java.util.regex.Pattern.CASE_INSENSITIVE));
        return p.matcher(ownSql).find() ? "statement" : "file";
    }

    /**
     * 这一行的证据里哪些算"这一行自己变了"：非行级归因（整文件 / 调用链）的行本来就宽（另有 coarse 缺口在说），
     * 行级归因只认自己语句里的字段；unknown 按"算在这一行头上"——宁可让人多看一眼，不藏变动。
     */
    static boolean ownScope(String basis, Map<String, Object> item) {
        return !"line".equals(basis) || !"file".equals(item.get("scope"));
    }

    /**
     * 一次批量取 SQL 卡自己的语句文本，禁逐行相关子查询。
     * 接口卡没有语句体（它的出入参在 bexConf / 代码里），返回表里没有它，界面按"整文件口径"给。
     */
    private Map<String, String> ownSqlOf(java.util.Collection<String> unitKeys) {
        Map<String, String> out = new LinkedHashMap<>();
        String in = unitKeys.stream().filter(k -> k.startsWith("db_object:"))
                .map(k -> k.substring(k.indexOf(':') + 1)).distinct().reduce((a, b) -> a + "," + b).orElse("");
        if (in.isEmpty()) return out;
        for (Map<String, Object> d : jdbc.queryForList(
                "SELECT id, source_sql, dynamic_body FROM db_objects WHERE id IN (" + in + ")")) {
            String key = "db_object:" + ((Number) d.get("id")).longValue();
            String sql = String.valueOf(d.get("source_sql") == null ? "" : d.get("source_sql"))
                    + "\n" + String.valueOf(d.get("dynamic_body") == null ? "" : d.get("dynamic_body"));
            out.put(key, sql.isBlank() ? null : sql);
        }
        return out;
    }

    /**
     * 生成期扫一遍出参变动：缺口 {@code out_param_changed} 要靠它，所以必须在 collectGaps 之前算。
     * 算不上的两种情况（没锚点、区间过大）都不留空，写进行上的原因，由缺口区照实挂出来。
     */
    private void fillOutParams(Map<String, Row> rows, int commitsInRange) {
        if (commitsInRange > OUT_SCAN_CAP) {
            rows.values().forEach(r -> r.outParamSkip = "区间 " + commitsInRange + " 个提交超过出参扫描上限 "
                    + OUT_SCAN_CAP + "：这一列没核对，把区间拆小再出一份就有");
            return;
        }
        Map<Long, OutParamService.Scan> cache = new LinkedHashMap<>();
        Map<String, String> ownSql = ownSqlOf(rows.keySet());
        for (Row r : rows.values()) {
            if (r.files.isEmpty()) {
                r.outParamSkip = "这一行没有文件锚点（老归因数据）：出参变动没核对上，重新学习后重出一份就有";
                continue;
            }
            r.outParam = outParamEvidence(r.commits, r.files, cache, ownSql.get(r.unitKind + ":" + r.unitId));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outOf(Object fields, String io) {
        if (!(fields instanceof List<?> list)) return List.of();
        return (List<Map<String, Object>>) list.stream().filter(m -> m instanceof Map
                && io.equals(((Map<String, Object>) m).get("io"))).toList();
    }

    /** 只算"这一行自己"的变动：行级归因不把同文件别的语句的字段算在自己头上 */
    private static List<Map<String, Object>> ownOf(Object fields, String io, String basis) {
        return outOf(fields, io).stream().filter(m -> ownScope(basis, m)).toList();
    }

    /** 字段清单的恒显说法：变动的说"增/删/改"，界面和结论单共用一份，免得两处各自编话 */
    private static String fieldListCn(List<Map<String, Object>> fs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, fs.size()); i++) {
            Map<String, Object> f = fs.get(i);
            if (i > 0) sb.append("、");
            sb.append(f.get("field")).append(changeCn(String.valueOf(f.get("change"))));
        }
        if (fs.size() > 6) sb.append("…");
        return sb.toString();
    }

    static String changeCn(String change) {
        return switch (change) {
            case "added" -> "（新增）";
            case "removed" -> "（删除）";
            case "modified" -> "（就地改）";
            default -> "";
        };
    }

    /**
     * 给行挂上出参证据：issued 读 {@code report_rows.out_param_json}（出单那刻固化的留痕，frozen=true），
     * draft 读取期现算（锚点从 commit_targets 还原，不在结论单里另存一份）。
     *
     * @return 是否"这一列没核对"（区间提交数超上限）
     */
    @SuppressWarnings("unchecked")
    private boolean decorateOutParams(List<Map<String, Object>> rows, String status) {
        boolean draft = "draft".equals(status);
        if (!draft) {
            for (Map<String, Object> r : rows) {
                r.put("out_params", parseList(r.get("out_param_json")));
                r.put("out_params_frozen", true);
                r.remove("out_param_json");
            }
            return false;
        }
        Set<Long> commits = new TreeSet<>();
        Map<Long, List<Long>> rowCommits = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            List<Long> ids = parseIds(r.get("commit_ids"));
            rowCommits.put(((Number) r.get("id")).longValue(), ids);
            commits.addAll(ids);
        }
        if (commits.size() > OUT_SCAN_CAP) {
            rows.forEach(r -> {
                r.put("out_params", null);
                r.put("out_params_frozen", false);
                r.remove("out_param_json");
            });
            return true;
        }
        Map<String, Set<String>> anchors = anchorsOf(commits);
        Map<Long, OutParamService.Scan> cache = new LinkedHashMap<>();
        Map<String, String> ownSql = ownSqlOf(rows.stream()
                .map(r -> r.get("unit_kind") + ":" + r.get("unit_id")).toList());
        for (Map<String, Object> r : rows) {
            String unit = r.get("unit_kind") + ":" + r.get("unit_id");
            Set<String> files = anchors.getOrDefault(unit, new LinkedHashSet<>());
            List<Long> ids = rowCommits.get(((Number) r.get("id")).longValue());
            r.put("out_params", files.isEmpty() ? null
                    : outParamEvidence(new LinkedHashSet<>(ids), files, cache, ownSql.get(unit)));
            r.put("out_params_frozen", false);
            r.remove("out_param_json");
        }
        return false;
    }

    /** 目标 → 归因文件：锚点只有 commit_targets 一份事实，读取期现还原，不落第二份 */
    private Map<String, Set<String>> anchorsOf(Set<Long> commits) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (commits.isEmpty()) return out;
        for (Map<String, Object> a : jdbc.queryForList("SELECT unit_kind, unit_id, anchor FROM commit_targets "
                + "WHERE commit_id IN (" + join(new ArrayList<>(commits)) + ") AND unit_kind<>'none'")) {
            String f = anchorFile(a.get("anchor"));
            if (!f.isEmpty()) {
                out.computeIfAbsent(a.get("unit_kind") + ":" + a.get("unit_id"), k -> new LinkedHashSet<>()).add(f);
            }
        }
        return out;
    }

    /** 缺口＝这一行的证据不足。系统不猜，只把"缺什么、会怎样"写在脸上 */
    private List<Map<String, Object>> collectGaps(Map<String, Row> rows) {
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (Row r : rows.values()) {
            if (r.caseCount == 0) {
                gaps.add(gap(r, "no_case", "一条启用用例都没有：这个目标改了什么没法测"));
            }
            if (r.runId == null) {
                gaps.add(gap(r, "no_run", "有 " + r.caseCount + " 条启用用例，但一次都没执行过：没有留痕可指"));
            } else if (r.caseId == null && "run_direct".equals(r.note)) {
                gaps.add(gap(r, "direct_run", "最近一次是「随手 SQL/直测」留痕，没挂用例：不算进用例通过率与覆盖率"));
            }
            if (!"line".equals(r.basis)) {
                gaps.add(gap(r, "coarse_attribution", coarseReason(r)));
            }
            if (r.outParamSkip != null) {
                gaps.add(gap(r, "out_param_unanchored", r.outParamSkip));
            } else {
                List<Map<String, Object>> outs = ownOf(r.outParam, "out", r.basis);
                List<Map<String, Object>> ins = ownOf(r.outParam, "in", r.basis);
                if (!outs.isEmpty()) {
                    long sibling = outOf(r.outParam, "out").size() - outs.size();
                    gaps.add(gap(r, "out_param_changed", "出参在本区间被改动 " + outs.size() + " 个字段："
                            + fieldListCn(outs) + "。接口文档和使用方都得跟着改，这一行要看一眼再确认"
                            + (ins.isEmpty() ? "" : "（入参另有 " + ins.size() + " 个字段被改动）")
                            + (sibling == 0 ? "" : "；另有 " + sibling + " 个字段改在同一文件的别的语句里，没算在这一行头上")));
                }
            }
        }
        return gaps;
    }

    /** 粗归因的成因按仓库事实说：SVN 只学到文件级，git 才谈得上「改动行落在哪条语句」 */
    private String coarseReason(Row r) {
        if ("chain".equals(r.basis)) {
            return "只能归因到调用链：改的是这张卡调用链上的服务层 / DAO 文件（" + String.join("、", r.files)
                    + "），它自己不产出卡片——学习时按方法名追踪代码可达，这一行圈的范围比实际改动宽";
        }
        if (!r.vcsTypes.contains("git")) {
            String t = r.vcsTypes.isEmpty() ? "未知" : String.join("/", r.vcsTypes);
            return "只能归因到整文件：这些提交来自 " + t + " 仓库，而只有 git 仓库的 mapper 改动才收得窄到语句——这一行圈的范围比实际改动宽";
        }
        return "只能归因到整文件：改动的行没落在任何已识别语句里（非 mapper 文件，或这个提交的 diff 读不出来）——这一行圈的范围比实际改动宽";
    }

    private Map<String, Object> gap(Row r, String type, String detail) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("unit_kind", r.unitKind);
        g.put("unit_id", r.unitId);
        g.put("unit_name", r.unitName);
        g.put("gap_type", type);
        g.put("detail", detail);
        return g;
    }

    /**
     * 指纹四要素：代码版本 / 库结构版本 / 通道版本 / 人。
     * 拿不到的部分存 null 并配一条 *_missing，由界面恒显"缺什么"——缺指纹本身就是要交代的事实。
     */
    private Map<String, Object> fingerprint(long projectId, Map<String, Object> env,
                                            Map<String, Row> rows, List<Map<String, Object>> commits) {
        Map<String, Object> fp = new LinkedHashMap<>();

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("from", commits.get(0).get("revision"));
        code.put("to", commits.get(commits.size() - 1).get("revision"));
        code.put("commits", commits.size());
        code.put("digest", sha1(commits.stream().map(c -> String.valueOf(c.get("revision")))
                .reduce((a, b) -> a + "\n" + b).orElse("")));
        Set<String> learned = new TreeSet<>();
        for (Map<String, Object> c : commits) {
            learned.add(c.get("repo_name") + "@" + (c.get("learned_revision") == null
                    ? "未记录学习版本" : c.get("learned_revision")));
        }
        code.put("learned", List.copyOf(learned));
        fp.put("code", code);

        if (env == null) {
            fp.put("schema", null);
            fp.put("schema_missing", "没选环境：这一版结论单没核对目标库结构");
        } else {
            long sqlTargets = rows.values().stream().filter(r -> "db_object".equals(r.unitKind)).count();
            long withTables = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM db_objects WHERE id IN (""" + idsOf(rows, "db_object") + ")"
                    + " AND tables_involved IS NOT NULL AND tables_involved<>''", Long.class);
            Map<String, Object> meta = jdbc.queryForMap(
                    "SELECT COUNT(*) AS n, MAX(pulled_at) AS at FROM table_meta WHERE project_id=?", projectId);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("env_id", env.get("id"));
            schema.put("env_name", env.get("name"));
            schema.put("db_type", env.get("db_type"));
            schema.put("sql_targets", sqlTargets);
            schema.put("sql_targets_with_tables", withTables);
            schema.put("meta_rows", meta.get("n"));
            schema.put("meta_pulled_at", meta.get("at"));
            fp.put("schema", schema);
            if (((Number) meta.get("n")).intValue() == 0) {
                fp.put("schema_missing", "这个项目还没拉过列元数据：结构版本只到「有哪些 SQL 卡提到了表」，没核对字段");
            }
        }

        String ifcIds = idsOf(rows, "interface");
        if ("0".equals(ifcIds)) {
            fp.put("channel", null);
            fp.put("channel_missing", "这份结论单里没有接口卡目标，没有通道版本可记");
        } else {
            Map<String, Object> ch = jdbc.queryForMap("""
                    SELECT COUNT(*) AS n, COUNT(DISTINCT schema_hash) AS kinds, GROUP_CONCAT(DISTINCT schema_hash) AS all_hash
                    FROM interfaces WHERE id IN (""" + ifcIds + ")");
            Map<String, Object> channel = new LinkedHashMap<>();
            channel.put("interfaces", ch.get("n"));
            channel.put("distinct_schema_hash", ch.get("kinds"));
            channel.put("digest", ch.get("all_hash") == null ? null : sha1(String.valueOf(ch.get("all_hash"))));
            fp.put("channel", channel);
            if (ch.get("all_hash") == null) {
                fp.put("channel_missing", "报告里的接口卡都没有 schema_hash（老数据或没重学）：通道版本无从计算");
            }
        }

        fp.put("by", UserContext.username());
        fp.put("tool", "report-v1");
        return fp;
    }

    private String idsOf(Map<String, Row> rows, String kind) {
        return rows.values().stream().filter(r -> r.unitKind.equals(kind))
                .map(r -> String.valueOf(r.unitId)).reduce((a, b) -> a + "," + b).orElse("0");
    }

    private String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 状态机与读取 ----------

    /**
     * 结论单存在性单点读取：所有按 id 取单的出口都走这里。
     * 缺行时照实说清"没有这份单"，绝不许把 JDBC 的管道话（Incorrect result size: expected 1, actual 0）当结论透给用户。
     */
    private Map<String, Object> requireReport(long reportId) {
        List<Map<String, Object>> rs = jdbc.queryForList(
                "SELECT * FROM release_checklists WHERE id=? AND kind='report'", reportId);
        if (rs.isEmpty()) {
            throw new IllegalStateException("没有 #" + reportId + " 这份提测结论单：可能已被删除，"
                    + "或者它是【起飞检查】页面下的检查单（那不是提测结论单）");
        }
        return rs.get(0);
    }

    /**
     * 逐行确认缺口（草稿期专用）：一条签字盖住这一行的全部缺口，理由随决定一起留。
     *
     * <p>为什么按行不按缺口：一个目标往往同时挂"没用例 + 只能归因到整文件 + 出参变了"三条，
     * 逐条点是三遍同样的判断；而按文件点又是老检查单的口径，正是本次要升级掉的粒度。
     *
     * <p>为什么只认 draft：出单（issued）之后这张单就是上线依据，依据不能事后被悄悄改掉——
     * 老检查单在出单后仍回写条目状态，那是同一个洞（设计 docs/08 §2）。要改结论走【再出一份 v2】。
     */
    public Map<String, Object> sign(long reportId, String unitKind, long unitId, String decision, String reason) {
        Map<String, Object> rep = requireReport(reportId);
        if (!"draft".equals(rep.get("status"))) {
            return Map.of("ok", false, "error", "这份已经出过了（" + rep.get("status") + "），签字只在草稿期有效："
                    + "结论单是上线依据，不能事后改。要改结论请重新生成一份并指向它");
        }
        if (!List.of("confirmed", "skipped").contains(decision)) {
            return Map.of("ok", false, "error", "决定只能是 confirmed（已核对）或 skipped（这一行本次不测），收到的是 "
                    + decision);
        }
        if ("skipped".equals(decision) && (reason == null || reason.isBlank())) {
            return Map.of("ok", false, "error", "跳过必须写理由：这条理由会跟着结论单一起导出");
        }
        List<Map<String, Object>> unit = jdbc.queryForList("""
                SELECT unit_kind, unit_id, unit_name FROM report_rows
                WHERE report_id=? AND unit_kind=? AND unit_id=?""", reportId, unitKind, unitId);
        if (unit.isEmpty()) {
            return Map.of("ok", false, "error", "这一行不在 #" + reportId + " 这份单里："
                    + "可能是别的单的详情还开着，刷新页面再看一次");
        }
        List<Map<String, Object>> signoffs = parseList(rep.get("signoffs_json"));
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("unit_kind", unitKind);
        one.put("unit_id", unitId);
        one.put("unit_name", unit.get(0).get("unit_name"));
        one.put("decision", decision);
        one.put("reason", reason == null ? "" : reason.trim());
        one.put("by", UserContext.username());
        one.put("at", java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' '));
        signoffs.add(one);   // 只追加，不覆盖：改过几次判、每次谁判的，都是要交代的事实
        jdbc.update("UPDATE release_checklists SET signoffs_json=? WHERE id=? AND kind='report'",
                json(signoffs), reportId);
        audit.log("report.sign", "report#" + reportId,
                unitKind + "#" + unitId + " " + decision + (one.get("reason").equals("") ? "" : " reason=" + one.get("reason")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("signoffs", signoffs);
        return out;
    }

    /**
     * 给行盖上"这一行被谁判过"：一个目标的几条缺口共用最后一条签字。
     *
     * @return 最后签字的索引（按 kind:id），调用方还要拿它数"几处没确认"
     */
    private Map<String, Map<String, Object>> markSigns(List<Map<String, Object>> rows, Object signoffsJson) {
        Map<String, Map<String, Object>> signed = lastSignoff(signoffsJson);
        for (Map<String, Object> r : rows) {
            Map<String, Object> s = signed.get(r.get("unit_kind") + ":" + r.get("unit_id"));
            r.put("sign_decision", s == null ? null : s.get("decision"));
            r.put("sign_by", s == null ? null : s.get("by"));
            r.put("sign_at", s == null ? null : s.get("at"));
            r.put("sign_reason", s == null ? null : s.get("reason"));
        }
        return signed;
    }

    /** 每行最后一条签字（决定以最新为准，历史仍在 signoffs_json 里） */
    private Map<String, Map<String, Object>> lastSignoff(Object signoffsJson) {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> s : parseList(signoffsJson)) {
            Object kind = s.get("unit_kind");
            Object id = s.get("unit_id");
            if (kind != null && id != null) latest.put(kind + ":" + id, s);
        }
        return latest;
    }

    /** 出单：draft → issued。缺口必须先逐行确认，出参证据此刻固化成留痕；出过的单永不修改。 */
    public Map<String, Object> issue(long reportId) {
        Map<String, Object> rep = requireReport(reportId);
        if (!"draft".equals(rep.get("status"))) {
            return Map.of("ok", false, "error", "只有草稿能出单：这份当前是 " + rep.get("status")
                    + "。出过的单不可修改，要改结论请重新生成一份并指向它");
        }
        List<Map<String, Object>> gaps = jdbc.queryForList(
                "SELECT unit_kind, unit_id, unit_name, gap_type FROM report_gaps WHERE report_id=?", reportId);
        Map<String, Map<String, Object>> signed = lastSignoff(rep.get("signoffs_json"));
        List<Map<String, Object>> uncovered = gaps.stream()
                .filter(g -> !signed.containsKey(g.get("unit_kind") + ":" + g.get("unit_id")))
                .toList();
        if (!uncovered.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "还有 " + uncovered.size() + " 处缺口没确认（一张单里同一个目标的几条缺口算一处）："
                    + "系统不替你点头。到【缺口与风险声明】里逐行点【已核对】，或【跳过并留理由】再出单");
            out.put("uncovered", uncovered.size());
            out.put("rows", uncovered.stream().limit(30).toList());
            return out;
        }

        // 出单那刻把草稿期现算的出参证据固化：之后重学、重扫都不许改动这份留痕
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM report_rows WHERE report_id=? ORDER BY id", reportId);
        decorateOutParams(rows, "draft");
        List<Object[]> freeze = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            freeze.add(new Object[]{json(r.get("out_params")), r.get("id")});
        }
        Object supersedes = rep.get("supersedes_id");
        tx.executeWithoutResult(status -> {
            if (!freeze.isEmpty()) {
                jdbc.batchUpdate("UPDATE report_rows SET out_param_json=? WHERE id=?", freeze);
            }
            jdbc.update("UPDATE release_checklists SET status='issued', issued_at=datetime('now','localtime') WHERE id=?", reportId);
            if (supersedes != null) {
                jdbc.update("UPDATE release_checklists SET status='superseded' WHERE id=? AND status='issued'", supersedes);
            }
        });
        audit.log("report.issue", "report#" + reportId, String.valueOf(rep.get("title"))
                + " gaps=" + gaps.size() + " signoffs=" + signed.size());
        return Map.of("ok", true, "id", reportId);
    }

    /** 读一份结论单：页面 / JSON / HTML 三个出口都从这里渲染，口径只有一份 */
    public Map<String, Object> detail(long reportId) {
        Map<String, Object> rep = requireReport(reportId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM report_rows WHERE report_id=? ORDER BY unit_kind, unit_name", reportId);
        List<Map<String, Object>> gaps = jdbc.queryForList(
                "SELECT * FROM report_gaps WHERE report_id=? ORDER BY unit_kind, unit_name, gap_type", reportId);
        boolean partial = decorateOutParams(rows, String.valueOf(rep.get("status")));
        Map<String, Map<String, Object>> signed = markSigns(rows, rep.get("signoffs_json"));
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("targets", rows.size());
        totals.put("with_evidence", rows.stream().filter(r -> r.get("run_id") != null).count());
        totals.put("without_evidence", rows.stream().filter(r -> r.get("run_id") == null).count());
        totals.put("coarse_basis", rows.stream().filter(r -> !"line".equals(r.get("basis"))).count());
        totals.put("chain_basis", rows.stream().filter(r -> "chain".equals(r.get("basis"))).count());
        totals.put("out_param_changed", rows.stream()
                .filter(r -> !ownOf(r.get("out_params"), "out", String.valueOf(r.get("basis"))).isEmpty()).count());
        totals.put("gaps", gaps.size());
        // 「几处没确认」按行计，与出单时的拦截口径同一条：一个目标的三条缺口算一处
        totals.put("gaps_uncovered", gaps.stream()
                .map(g -> g.get("unit_kind") + ":" + g.get("unit_id"))
                .filter(k -> !signed.containsKey(k)).distinct().count());
        totals.put("rows_to_sign", gaps.stream().map(g -> g.get("unit_kind") + ":" + g.get("unit_id"))
                .distinct().count());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("report", rep);
        out.put("rows", rows);
        out.put("gaps", gaps);
        out.put("signoffs", parseList(rep.get("signoffs_json")));
        out.put("totals", totals);
        out.put("basis_labels", BASIS_LABELS); // 档位话术只这一份：详情页下拉、行内色标、导出网页都读它
        out.put("out_params_partial", partial);
        out.put("fingerprint", parse(rep.get("fingerprint")));
        out.put("scope", parse(rep.get("snapshot_json")));
        return out;
    }

    /** 项目级清单（只显示结论单，老检查单仍归老页面） */
    public List<Map<String, Object>> list(long projectId) {
        return jdbc.queryForList("""
                SELECT id, title, from_revision, to_revision, status, env_id, issued_at, created_by, created_at,
                       supersedes_id, snapshot_json
                FROM release_checklists WHERE project_id=? AND kind='report' ORDER BY id DESC""", projectId);
    }

    /**
     * 结论单明细行的服务端分页 + 过滤（第十轮标准：列表不许把全量拉到前端再切）。
     * 三个过滤参数各自独立生效，验证脚本会逐个裸发一次确认真的改条数——假过滤比没过滤更坏。
     */
    public Map<String, Object> rows(long reportId, int page, int size, String basis, String evidence, String q) {
        Map<String, Object> rep = requireReport(reportId);   // 单据不在就照实说，不许把"删掉了"渲染成"这张单没有行"
        StringBuilder where = new StringBuilder(" WHERE report_id=?");
        List<Object> args = new ArrayList<>();
        args.add(reportId);
        if (basis != null && BASIS_LABELS.containsKey(basis)) {
            where.append(" AND basis=?");
            args.add(basis);
        }
        if ("with".equals(evidence)) where.append(" AND run_id IS NOT NULL");
        else if ("without".equals(evidence)) where.append(" AND run_id IS NULL");
        if (q != null && !q.isBlank()) {
            // ESCAPE 只管得住紧挨着它的那一个 LIKE，所以每个 ? 后面都要各带一次
            where.append(" AND (unit_name LIKE ?").append(com.preflight.common.SqlLikes.ESCAPE_CLAUSE)
                    .append(" OR case_name LIKE ?").append(com.preflight.common.SqlLikes.ESCAPE_CLAUSE)
                    .append(")");
            String like = com.preflight.common.SqlLikes.contains(q.trim());
            args.add(like);
            args.add(like);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM report_rows" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Math.max(1, size));
        pageArgs.add(Math.max(0, (page - 1) * size));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM report_rows" + where + " ORDER BY unit_kind, unit_name LIMIT ? OFFSET ?",
                pageArgs.toArray());
        boolean partial = decorateOutParams(rows, String.valueOf(rep.get("status")));
        Map<String, Map<String, Object>> signed = markSigns(rows, rep.get("signoffs_json"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", total == null ? 0 : total);
        out.put("page", page);
        out.put("size", size);
        out.put("rows", rows);
        out.put("basis_labels", BASIS_LABELS); // 档位话术只有这一份，过滤下拉与行内色标都读它
        out.put("out_params_partial", partial);
        return out;
    }

    private Object parse(Object text) {
        if (text == null) return Map.of();
        try {
            return mapper.readValue(String.valueOf(text), Map.class);
        } catch (Exception e) {
            return Map.of("raw", String.valueOf(text));
        }
    }

    /** 数组列的解析：没有值就给空表，不给 null 让界面各自判空 */
    private List<Map<String, Object>> parseList(Object text) {
        if (text == null || String.valueOf(text).isBlank()) return List.of();
        try {
            return mapper.readValue(String.valueOf(text), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** report_rows.commit_ids 是「12,13,14」这种CSV：解成数，脏值跳过而不是整列报错 */
    private List<Long> parseIds(Object text) {
        List<Long> out = new ArrayList<>();
        if (text == null) return out;
        for (String p : String.valueOf(text).split(",")) {
            try {
                out.add(Long.parseLong(p.trim()));
            } catch (NumberFormatException ignored) {
                // 脏片段照实跳过：这一列只是用来找 diff，不是结论
            }
        }
        return out;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String blankTo(String s, String dft) {
        return s == null || s.isBlank() ? dft : s;
    }

    private String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
    }
}
