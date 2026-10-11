package com.preflight.repo;

import com.preflight.ai.CaseService;
import com.preflight.ai.CommitTargetService;
import com.preflight.ai.ImpactService;
import com.preflight.ai.SummaryService;
import com.preflight.common.AuditService;
import com.preflight.exec.TestRunService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 改动流 API：同步、提交与文件改动查询、摘要、影响分析、用例生成入口、测试标记、一键回归。 */
@RestController
@RequestMapping("/api")
public class ChangeController {

    /** 提交人缺失的哨兵值（界面下拉里显示成「（未记录）」，不与任何真实人名撞车） */
    static final String NO_AUTHOR = "__none__";

    private final JdbcTemplate jdbc;
    private final SyncService sync;
    private final PatchFetchService patchFetch;
    private final SummaryService summary;
    private final ImpactService impact;
    private final CommitTargetService targets;
    private final CaseService caseService;
    private final TestRunService runService;
    private final AuditService audit;
    private final com.preflight.common.ProgressRegistry progress;
    private final com.preflight.learn.OutParamService outParams;

    public ChangeController(JdbcTemplate jdbc, SyncService sync, PatchFetchService patchFetch,
                            SummaryService summary,
                            ImpactService impact, CommitTargetService targets, CaseService caseService,
                            TestRunService runService, AuditService audit,
                            com.preflight.common.ProgressRegistry progress,
                            com.preflight.learn.OutParamService outParams) {
        this.jdbc = jdbc;
        this.sync = sync;
        this.patchFetch = patchFetch;
        this.summary = summary;
        this.impact = impact;
        this.targets = targets;
        this.caseService = caseService;
        this.runService = runService;
        this.audit = audit;
        this.progress = progress;
        this.outParams = outParams;
    }

    /** 手动同步（git fetch / svn update），并触发增量摘要与影响分析 */
    @PostMapping("/repos/{id}/sync")
    public Map<String, Object> sync(@PathVariable long id, @RequestParam(required = false) String pid) {
        try {
            progress.begin(pid, "同步仓库", 0);   // total=0：这一步只有一个动作，按钮上给阶段说明而不是 1/1
            progress.step(pid, "拉取 Git/SVN 更新");
            SyncService.SyncReport r = sync.syncRepo(id);
            // 对新提交做摘要与影响分析
            List<Map<String, Object>> pending = jdbc.queryForList(
                    "SELECT id FROM commits WHERE repo_id=? AND summary IS NULL ORDER BY id DESC LIMIT 10", id);
            progress.begin(pid, "补摘要与影响面", pending.size());
            for (Map<String, Object> c : pending) {
                summary.summarizeCommit(((Number) c.get("id")).longValue());
                impact.forCommit(((Number) c.get("id")).longValue());
                jdbc.update("UPDATE commits SET processed=1 WHERE id=?", c.get("id"));
                progress.step(pid, "提交 " + c.get("id"));
            }
            return Map.of("ok", true, "report", r);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        } finally {
            progress.end(pid);
        }
    }

    @PostMapping("/repos/sync-all")
    public Map<String, Object> syncAll() {
        return Map.of("ok", true, "reports", sync.syncAll());
    }

    /**
     * 「改动人」下拉的数据源，跟着仓库这一级收窄（几十人的大仓不收窄就没法用）。
     * 只报库里的原文，不做任何"同一个人"的合并猜测——实测同一人在 Git 里是姓名、在 SVN 里是账号
     * （zengguangzhou 与 zengguangzhou@netinfosz.com 是两条），要不要并成一条由界面按字符串关系提示、用户自己选。
     */
    @GetMapping("/commit-authors")
    public Map<String, Object> commitAuthors(@RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) Long repoId) {
        StringBuilder sql = new StringBuilder("SELECT IFNULL(c.author,'') AS author, COUNT(*) AS commit_count"
                + " FROM commits c JOIN repos r ON r.id=c.repo_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (projectId != null) {
            sql.append(" AND r.project_id=?");
            args.add(projectId);
        }
        if (repoId != null) {
            sql.append(" AND r.id=?");
            args.add(repoId);
        }
        sql.append(" GROUP BY IFNULL(c.author,'') ORDER BY commit_count DESC, author");
        return Map.of("ok", true, "authors", jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /**
     * 提交列表。第十六轮：加「仓库」这一级过滤并改成真分页（原先固定 LIMIT 100、状态在 Java 侧过滤，
     * 翻页时口径会错位）。三档状态互斥且穷尽：untested=一条文件都没测，partial=测了一部分，tested=全部测完。
     * 第 34 轮：加「改动人」过滤。默认按原文精确匹配；contains=1 时按包含匹配，
     * 用来把"同一人在 Git 写姓名、在 SVN 写账号"这种写法差异一次筛到——是查询方式，不是身份合并。
     */
    @GetMapping("/commits")
    public Map<String, Object> commits(@RequestParam(required = false) Long projectId,
                                       @RequestParam(required = false) Long repoId,
                                       @RequestParam(required = false) String testStatus,
                                       @RequestParam(required = false) String author,
                                       @RequestParam(defaultValue = "0") int contains,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        String st = testStatus == null ? "" : testStatus.trim();
        String outer = switch (st) {
            case "untested" -> " WHERE untested_count=change_count AND change_count>0";
            case "tested" -> " WHERE untested_count=0 AND change_count>0";
            case "partial" -> " WHERE untested_count>0 AND untested_count<change_count";
            default -> "";
        };
        StringBuilder inner = new StringBuilder("""
                SELECT c.*, r.name AS repo_name, r.project_id AS project_id,
                  (SELECT COUNT(*) FROM file_changes fc WHERE fc.commit_id=c.id) change_count,
                  (SELECT COUNT(*) FROM file_changes fc WHERE fc.commit_id=c.id AND fc.test_status='untested') untested_count
                FROM commits c JOIN repos r ON r.id=c.repo_id WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (projectId != null) {
            inner.append(" AND r.project_id=?");
            args.add(projectId);
        }
        if (repoId != null) {
            inner.append(" AND r.id=?");
            args.add(repoId);
        }
        String au = author == null ? "" : author.trim();
        if (!au.isEmpty()) {
            if (NO_AUTHOR.equals(au)) {
                // 提交人没记上的两种落库形态：Git 给 NULL、SVN 兜底写字面量问号
                inner.append(" AND IFNULL(c.author,'') IN ('','?')");
            } else if (contains == 1) {
                inner.append(" AND IFNULL(c.author,'') LIKE ?").append(com.preflight.common.SqlLikes.ESCAPE_CLAUSE);
                args.add(com.preflight.common.SqlLikes.contains(au));
            } else {
                inner.append(" AND c.author=?");
                args.add(au);
            }
        }
        String from = "SELECT * FROM (" + inner + ")" + outer;
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + inner + ")" + outer,
                Integer.class, args.toArray());
        int sz = Math.min(Math.max(size, 1), 200);   // 第 18 轮：前端每页条数最多可选到 200，这里要与它同级
        int p = Math.max(page, 1);
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(sz);
        listArgs.add(com.preflight.common.Paging.offset(p, sz));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM (" + inner + ")" + outer + " ORDER BY id DESC LIMIT ? OFFSET ?",
                listArgs.toArray());
        return Map.of("ok", true, "commits", attachCaseStats(rows),
                "total", total == null ? 0 : total, "page", p, "size", sz);
    }

    /**
     * 第卅四轮：这一次提交动没动接口的出入参。只在用户点【出入参影响】时现算——
     * 它要逐条读盘上的 diff 快照，整页自动算会把改动流主入口拖慢（§十六「进面板才查」同一口径）。
     *
     * <p>字段级变动由 {@link com.preflight.learn.OutParamService} 判；"涉及哪些接口"沿用第 17 轮那一份归因
     * （{@link ImpactService#forCommit}），不另起一套口径——老工程那边再叠一层 viewConf 的
     * service/bex 码，新工程（没有分发字典）就只能靠这份归因给出接口卡与 SQL 语句。
     */
    @GetMapping("/commits/{id}/param-impact")
    public Map<String, Object> paramImpact(@PathVariable long id) {
        List<Map<String, Object>> rs = jdbc.queryForList(
                "SELECT id, author FROM commits WHERE id=?", id);
        if (rs.isEmpty()) {
            return Map.of("ok", false, "error", "没有 #" + id + " 这条提交：可能所在仓库已被删除");
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(outParams.impact(id));
        // 归因清单也照实限量：一个 791 文件的整仓导入提交有 3700+ 张卡，全发下去响应就 1.4MB、界面渲染更慢。
        // 接口卡排在 SQL 卡前面（出入参归属更关心接口），总数与三种命中依据的个数一律按全量报。
        List<com.preflight.ai.ImpactService.ImpactedTarget> targets = impact.forCommit(id).stream()
                .filter(t -> "interface".equals(t.type()) || "db_object".equals(t.type()))
                .sorted(java.util.Comparator.comparingInt(t -> "interface".equals(t.type()) ? 0 : 1))
                .toList();
        out.put("involvedTotal", targets.size());
        out.put("involvedLineTotal", targets.stream().filter(t -> "line".equals(t.basis())).count());
        out.put("involvedChainTotal", targets.stream().filter(t -> "chain".equals(t.basis())).count());
        out.put("involvedTargets", targets.size() > 200 ? targets.subList(0, 200) : targets);
        return out;
    }

    /**
     * 第 15 轮：给提交列表附上「本提交用例」统计（总数 + 每个用例最近一次执行的通过/失败 + 最近执行时间）。
     * 第 17 轮：目标集合改由 {@link CommitTargetService} 归因（改动行落在哪条语句），
     * 与用例目录、影响分析、一键回归同一份口径；同时透出 basis，界面才能说清"按语句命中"还是"按整文件"。
     * 刻意整页批量算（固定几次查询），不逐条提交发请求——改动流是主入口，100 条提交 × 每条 3 查 = 卡死页面。
     */
    private List<Map<String, Object>> attachCaseStats(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows);
        for (Map<String, Object> r : out) {
            r.put("case_count", 0);
            r.put("case_pass", 0);
            r.put("case_fail", 0);
            r.put("case_run_at", "");
            r.put("case_basis", "");
            r.put("case_stmt_targets", 0);
            r.put("case_file_targets", 0);
            r.put("case_chain_targets", 0);
            r.put("case_anchors", new ArrayList<String>());
            r.put("case_direct", 0);
            r.put("case_direct_at", "");
        }
        if (out.isEmpty()) return out;
        List<Long> commitIds = out.stream()
                .map(r -> ((Number) r.get("id")).longValue()).toList();
        String commitIdList = commitIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        targets.ensure(commitIds);
        // 用例数仍按 join 算，但目标档位必须看 commit_targets 全量——按 join 算会「还没生成用例」时把
        // 三条归因档位一起报成 0，用户就看不到【生成用例】能不能命中（第四十四轮正是这个红条）。
        Map<Long, java.util.Set<Long>> commitCases = new LinkedHashMap<>();
        for (Map<String, Object> p : jdbc.queryForList("""
                SELECT DISTINCT ct.commit_id AS cid, tc.id AS case_id
                FROM commit_targets ct
                  JOIN test_cases tc ON tc.target_type=ct.unit_kind AND tc.target_id=ct.unit_id
                WHERE ct.commit_id IN (""" + commitIdList + ")")) {
            commitCases.computeIfAbsent(((Number) p.get("cid")).longValue(), k -> new java.util.LinkedHashSet<>())
                    .add(((Number) p.get("case_id")).longValue());
        }
        java.util.Set<Long> allCases = new java.util.LinkedHashSet<>();
        commitCases.values().forEach(allCases::addAll);
        Map<Long, Map<String, Object>> latestRun = new LinkedHashMap<>();
        if (!allCases.isEmpty()) {
            String caseIds = allCases.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT tr.case_id AS cid, tr.status, tr.executed_at FROM test_runs tr WHERE tr.id IN "
                            + "(SELECT MAX(id) FROM test_runs WHERE case_id IN (" + caseIds + ") GROUP BY case_id)")) {
                latestRun.put(((Number) r.get("cid")).longValue(), r);
            }
        }
        // 第 20 轮：直测行 case_id 为空，只能按 (target_type,target_id) 归回提交的目标集合。
        // 直测不计入通过/失败，只单独给"该目标被直测过几次/最近一次"，界面才能把"没跑过"和"验过但没挂用例"分开。
        Map<Long, java.util.Set<String>> commitUnits = new LinkedHashMap<>();
        Map<String, java.util.Set<Long>> unitIdsByType = new LinkedHashMap<>();
        // 目标档位按 unit 去重（一条语句挂 5 条用例时，join 会出 5 行，直接累加会把"命中 1 条语句"报成"命中 5 条"）
        Map<Long, java.util.Set<String>> stmtTargets = new LinkedHashMap<>();
        Map<Long, java.util.Set<String>> fileTargets = new LinkedHashMap<>();
        Map<Long, java.util.Set<String>> chainTargets = new LinkedHashMap<>();
        Map<Long, List<String>> anchors = new LinkedHashMap<>();
        for (Map<String, Object> p : jdbc.queryForList(
                "SELECT commit_id AS cid, unit_kind, unit_id, basis, anchor FROM commit_targets WHERE commit_id IN ("
                        + commitIdList + ")")) {
            long cid = ((Number) p.get("cid")).longValue();
            String kind = String.valueOf(p.get("unit_kind"));
            long uid = ((Number) p.get("unit_id")).longValue();
            commitUnits.computeIfAbsent(cid, k -> new java.util.LinkedHashSet<>()).add(kind + ":" + uid);
            unitIdsByType.computeIfAbsent(kind, k -> new java.util.LinkedHashSet<>()).add(uid);
            String basis = String.valueOf(p.get("basis"));
            if ("none".equals(basis)) continue;   // 没命中任何目标的哨兵行，不算档位
            String unit = kind + ":" + uid;
            if ("line".equals(basis)) {
                stmtTargets.computeIfAbsent(cid, k -> new java.util.LinkedHashSet<>()).add(unit);
                String a = (String) p.get("anchor");
                if (a != null && anchors.computeIfAbsent(cid, k -> new ArrayList<>()).size() < 6
                        && !anchors.get(cid).contains(a)) {
                    anchors.get(cid).add(a);
                }
            } else if ("chain".equals(basis)) {
                chainTargets.computeIfAbsent(cid, k -> new java.util.LinkedHashSet<>()).add(unit);
            } else {
                fileTargets.computeIfAbsent(cid, k -> new java.util.LinkedHashSet<>()).add(unit);
            }
        }
        Map<String, Map<String, Object>> directByUnit = new LinkedHashMap<>();
        if (!unitIdsByType.isEmpty()) {
            List<String> clauses = new ArrayList<>();
            List<Object> dargs = new ArrayList<>();
            for (Map.Entry<String, java.util.Set<Long>> e : unitIdsByType.entrySet()) {
                String ids = e.getValue().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
                clauses.add("(tr.target_type=? AND tr.target_id IN (" + ids + "))");
                dargs.add(e.getKey());
            }
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT tr.target_type AS t, tr.target_id AS i, COUNT(*) AS n, MAX(tr.executed_at) AS at "
                            + "FROM test_runs tr WHERE tr.case_id IS NULL AND ("
                            + String.join(" OR ", clauses) + ") GROUP BY tr.target_type, tr.target_id",
                    dargs.toArray())) {
                directByUnit.put(r.get("t") + ":" + r.get("i"), r);
            }
        }
        for (Map<String, Object> r : out) {
            long cid = ((Number) r.get("id")).longValue();
            java.util.Set<Long> cases = commitCases.get(cid);
            int stmt = stmtTargets.getOrDefault(cid, java.util.Set.of()).size();
            int file = fileTargets.getOrDefault(cid, java.util.Set.of()).size();
            int chain = chainTargets.getOrDefault(cid, java.util.Set.of()).size();
            r.put("case_basis", stmt > 0 ? "line" : (file > 0 ? "file" : (chain > 0 ? "chain" : "")));
            r.put("case_stmt_targets", stmt);
            r.put("case_file_targets", file);
            r.put("case_chain_targets", chain);
            r.put("case_anchors", anchors.getOrDefault(cid, List.of()));
            int direct = 0;
            String directAt = "";
            for (String unit : commitUnits.getOrDefault(cid, java.util.Set.of())) {
                Map<String, Object> d = directByUnit.get(unit);
                if (d == null) continue;
                direct += ((Number) d.get("n")).intValue();
                String t = d.get("at") == null ? "" : String.valueOf(d.get("at"));
                if (t.compareTo(directAt) > 0) directAt = t;
            }
            r.put("case_direct", direct);
            r.put("case_direct_at", directAt);
            if (cases == null || cases.isEmpty()) continue;
            int pass = 0;
            int fail = 0;
            String at = "";
            for (Long caseId : cases) {
                Map<String, Object> run = latestRun.get(caseId);
                if (run == null) continue;
                if ("pass".equals(run.get("status"))) pass++;
                else fail++;
                String t = run.get("executed_at") == null ? "" : String.valueOf(run.get("executed_at"));
                if (t.compareTo(at) > 0) at = t;
            }
            r.put("case_count", cases.size());
            r.put("case_pass", pass);
            r.put("case_fail", fail);
            r.put("case_run_at", at);
        }
        return out;
    }

    @GetMapping("/commits/{id}")
    public Map<String, Object> commitDetail(@PathVariable long id) {
        Map<String, Object> commit = jdbc.queryForMap(
                "SELECT c.*, r.name AS repo_name, r.vcs_type, r.url AS repo_url"
                        + " FROM commits c JOIN repos r ON r.id=c.repo_id WHERE c.id=?", id);
        List<Map<String, Object>> changes = jdbc.queryForList(
                "SELECT id, path, change_type, risk_level, test_status, tested_at, tested_by, summary, category, suggested_mode, patch_file FROM file_changes WHERE commit_id=?", id);
        boolean fetchable = "svn".equals(commit.get("vcs_type")) && commit.get("repo_url") != null
                && !String.valueOf(commit.get("repo_url")).isBlank();
        String fetchHint = "svn".equals(commit.get("vcs_type"))
                ? (fetchable ? null : "该 SVN 仓库没填远程 URL，取不到历史版本的文件内容")
                : "Git 仓库的改动快照在【同步】时就生成了，缺快照说明当时没有差异（比如首次导入）——重新点【同步】可补齐";
        for (Map<String, Object> ch : changes) {
            String pf = ch.get("patch_file") == null ? null : ch.get("patch_file").toString();
            if (pf != null) {
                try {
                    ch.put("patch", Files.readString(Path.of(pf)));
                } catch (Exception e) {
                    ch.put("patch", null);
                    ch.put("patch_stale", true); // 路径在、文件不在盘上：可以重新拉
                }
            }
            boolean hasPatch = ch.get("patch") != null;
            ch.put("can_fetch", !hasPatch && fetchable);
            if (!hasPatch && !fetchable) ch.put("fetch_hint", fetchHint);
        }
        // 影响面按当前口径现算：与列表「本提交用例」、用例目录、一键回归同一份归因，不读旧缓存列
        Map<Long, List<ImpactService.ImpactedTarget>> impacts = impact.byFileChange(id);
        for (Map<String, Object> ch : changes) {
            ch.put("impacts", impacts.getOrDefault(((Number) ch.get("id")).longValue(), List.of()));
        }
        commit.put("changes", changes);
        // 新鲜度契约（04-补齐 §3.7）：知识基线时间早于该提交同步时间 → 改动未被深度归纳
        try {
            Object learnedAt = commit.get("learned_at");
            Object syncedAt = commit.get("synced_at");
            commit.put("knowledge_fresh", learnedAt != null && syncedAt != null
                    && String.valueOf(learnedAt).compareTo(String.valueOf(syncedAt)) >= 0);
        } catch (Exception e) {
            commit.put("knowledge_fresh", true);
        }
        return Map.of("ok", true, "commit", commit);
    }

    /**
     * 按需补某个改动文件的差异快照（SVN 同步只登记变更清单，点开时才真去仓库取两个版本比对）。
     * 生成后落盘并回写 patch_file，下次直接读缓存；【上下文】换档时按那一档另存一份，
     * 只有默认档（3 行）会动 patch_file——那一列的语义有归因/摘要/出参/导出四个消费方。
     */
    @PostMapping("/file-changes/{id}/fetch-patch")
    public Map<String, Object> fetchPatch(@PathVariable long id,
                                         @RequestParam(required = false) Integer context) {
        if (context != null && !isAllowedContext(context)) {
            return Map.of("ok", false, "error", "上下文行数只认这几档：" + contextOptions() + "，收到的是 " + context);
        }
        int ctx = context == null ? UnifiedDiff.DEFAULT_CONTEXT : context;
        PatchFetchService.Fetched f = patchFetch.fetch(id, ctx);
        if (!f.ok()) return Map.of("ok", false, "error", String.valueOf(f.error()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("patch", f.patch());
        out.put("cached", f.cached());
        out.put("context", ctx);
        out.put("commit_id", f.commitId());
        // 快照一到，这条提交的归因就可能从"整文件"收窄成"按改动行命中语句"——缓存必须先作废
        if (ctx == UnifiedDiff.DEFAULT_CONTEXT) targets.invalidateCommit(f.commitId());
        return out;
    }

    /**
     * 改动文件的整份内容 + 行级改动标记：评审要看整体业务，diff 那几行上下文不够用。
     * 标记取自默认档快照（{@link PatchRanges} 只认 '+' 行，与上下文行数无关），所以这里不再收 context 参数。
     */
    @GetMapping("/file-changes/{id}/file")
    public Map<String, Object> changeFile(@PathVariable long id,
                                          @RequestParam(defaultValue = "rev") String at) {
        if (!"rev".equals(at) && !"work".equals(at)) {
            return Map.of("ok", false, "error", "at 只认 rev（本提交那一版）或 work（本地工作副本），收到的是 " + at);
        }
        PatchFetchService.FileView v = patchFetch.file(id, at);
        if (!v.ok()) return Map.of("ok", false, "error", String.valueOf(v.error()));
        List<Map<String, Object>> marked = v.marks().stream()
                .map(r -> Map.<String, Object>of("start", r[0], "end", r[1])).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("file_change_id", v.fileChangeId());
        out.put("commit_id", v.commitId());
        out.put("path", v.path());
        out.put("revision", v.revision());
        out.put("at", v.at());
        out.put("charset", v.charset());
        out.put("total_lines", v.totalLines());
        out.put("capped", v.capped());
        out.put("note", v.note());
        out.put("lines", v.lines());
        out.put("marks", marked);
        return out;
    }

    private static boolean isAllowedContext(int context) {
        for (int c : UnifiedDiff.ALLOWED_CONTEXTS) if (c == context) return true;
        return false;
    }

    private static String contextOptions() {
        return java.util.Arrays.stream(UnifiedDiff.ALLOWED_CONTEXTS)
                .mapToObj(c -> c == UnifiedDiff.FULL_CONTEXT ? "整文件" : String.valueOf(c))
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    @PostMapping("/commits/{id}/summarize")
    public Map<String, Object> summarize(@PathVariable long id) {
        try {
            SummaryService.SummaryResult r = summary.summarizeCommit(id);
            impact.forCommit(id);
            return Map.of("ok", true, "summary", r.summary(), "risk", r.risk(), "llmUsed", r.llmUsed());
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @PostMapping("/commits/{id}/generate-cases")
    public Map<String, Object> generateCases(@PathVariable long id,
                                             @RequestParam(defaultValue = "true") boolean useLlm,
                                             @RequestParam(required = false) String pid) {
        try {
            List<ImpactService.ImpactedTarget> targets = impact.forCommit(id);
            long workable = targets.stream().filter(t -> !"unmapped".equals(t.type())).count();
            progress.begin(pid, "生成用例", (int) workable);
            List<Map<String, Object>> results = new ArrayList<>();
            for (ImpactService.ImpactedTarget t : targets) {
                if ("unmapped".equals(t.type())) continue; // R3：未映射项不参与用例生成
                progress.step(pid, "正在处理 " + t.name());
                CaseService.GenResult g = caseService.generate(t.type(), t.id(), useLlm);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("target", t.name());
                r.put("type", t.type());
                r.put("created", g.created());
                r.put("llmUsed", g.llmUsed());
                r.put("notes", g.notes());
                r.put("basis", t.basis());
                r.put("anchor", t.anchor());
                results.add(r);
            }
            // 生成完如实报"这条提交现在挂着几条用例"——前端据此决定要不要给【点这里查看】的跳转，
            // 免得点了跳过去是空列表（第十六轮用户报的"啥也看不到"）
            Integer caseTotal = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT tc.id) FROM commit_targets ct
                      JOIN test_cases tc ON tc.target_type=ct.unit_kind AND tc.target_id=ct.unit_id
                    WHERE ct.commit_id=?""", Integer.class, id);
            return Map.of("ok", true, "targets", results, "caseTotal", caseTotal == null ? 0 : caseTotal);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        } finally {
            progress.end(pid);
        }
    }

    /** 影响面一键回归（04-补齐 §2.3）：对本次提交影响到的全部已启用用例批量执行 */
    @PostMapping("/commits/{id}/run-regression")
    public Map<String, Object> runRegression(@PathVariable long id,
                                             @RequestParam(required = false) Long envId,
                                             @RequestParam(required = false, defaultValue = "false") boolean repoDirect,
                                             @RequestParam(required = false) String pathMode,
                                             @RequestParam(required = false) String pid) {
        try {
            List<ImpactService.ImpactedTarget> targets = impact.forCommit(id);
            Integer willRun = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT tc.id FROM commit_targets ct "
                            + "JOIN test_cases tc ON tc.target_type=ct.unit_kind AND tc.unit_id=ct.unit_id "
                            + "WHERE ct.commit_id=? AND tc.enabled=1 AND tc.status IN ('approved','pending') LIMIT 30)",
                    Integer.class, id);
            progress.begin(pid, "一键回归", willRun == null ? 0 : willRun);
            List<Map<String, Object>> results = new ArrayList<>();
            int pass = 0, fail = 0, skipped = 0;
            for (ImpactService.ImpactedTarget t : targets) {
                if ("unmapped".equals(t.type())) continue; // R3：未映射项不参与回归
                List<Map<String, Object>> cases = jdbc.queryForList(
                        "SELECT * FROM test_cases WHERE target_type=? AND target_id=? AND enabled=1 " +
                                "AND status IN ('approved','pending') ORDER BY id LIMIT 10",
                        t.type(), t.id());
                for (Map<String, Object> tc : cases) {
                    if (results.size() >= 30) { skipped++; continue; }
                    long caseId = ((Number) tc.get("id")).longValue();
                    progress.step(pid, "执行用例 " + tc.get("name"));
                    TestRunService.RunResult r = runService.runCase(caseId, envId, id, null, null,
                            repoDirect, null, pathMode);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("case", tc.get("name"));
                    row.put("target", t.name());
                    row.put("status", r.ok() ? r.run().get("status") : "error");
                    row.put("error", r.ok() ? r.run().get("error") : r.error());
                    row.put("sent", sentLine(r));
                    results.add(row);
                    if (r.ok() && "pass".equals(r.run().get("status"))) pass++; else fail++;
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("pass", pass);
            summary.put("fail", fail);
            summary.put("skipped", skipped);
            audit.log("regression.run", "commit#" + id, "pass=" + pass + ", fail=" + fail + ", skipped=" + skipped);
            return Map.of("ok", true, "summary", summary, "results", results);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        } finally {
            progress.end(pid);
        }
    }

    /** 留痕快照的第一行（"POST http://…/ni-quality/qc/…"）：批量回归要把"到底发出去了哪条路径"摊开，
     *  否则 405/404 只看得到"失败"两个字，分不清是补前缀补错了还是本来就没这个接口（第五十轮）。 */
    private static String sentLine(TestRunService.RunResult r) {
        if (r == null || !r.ok() || r.run() == null) return "";
        Object req = r.run().get("request");
        if (req == null) return "";
        String s = String.valueOf(req);
        int nl = s.indexOf('\n');
        return nl > 0 ? s.substring(0, nl) : s;
    }

    /** 测试标记：untested / partial / tested / skipped */
    @PatchMapping("/commits/{id}/test-status")
    public Map<String, Object> markTestStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.get("status"));
        if (!List.of("untested", "partial", "tested", "skipped").contains(status)) {
            return Map.of("ok", false, "error", "非法状态: " + status);
        }
        // R4：tested_by 一律取会话身份；R23：可选按文件改动 id 精确标记
        List<Number> fcIds = body.get("fileChangeIds") == null ? List.of()
                : ((List<Number>) body.get("fileChangeIds"));
        if (fcIds.isEmpty()) {
            jdbc.update("UPDATE file_changes SET test_status=?, tested_at=datetime('now','localtime'), tested_by=? WHERE commit_id=?",
                    status, com.preflight.common.UserContext.username(), id);
        } else {
            for (Number fid : fcIds) {
                jdbc.update("UPDATE file_changes SET test_status=?, tested_at=datetime('now','localtime'), tested_by=? WHERE id=? AND commit_id=?",
                        status, com.preflight.common.UserContext.username(), fid.longValue(), id);
            }
        }
        return Map.of("ok", true);
    }
}
