package com.preflight.ai;

import com.preflight.parser.MybatisAnchors;
import com.preflight.repo.PatchRanges;
import com.preflight.repo.SourceAtRevision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提交 → 受影响目标（接口卡 / SQL 卡）的归因，是"本提交用例数""生成用例""影响分析""一键回归"
 * **唯一**的口径来源（第十七轮）。
 *
 * <p>原口径是"改动文件的 path 等值匹配 file_bindings"，而 MyBatis 一个 mapper 文件里的几百条语句
 * 各自成卡、共用同一个 path —— 改 1 行 SQL 就被算成命中整文件 223 张卡 / 938 条用例（真实项目实测）。
 * 现口径：先用 {@link PatchRanges} 从 diff 取"新文件侧真正被改的行"，再用 {@link MybatisAnchors}
 * 在**该提交自己的文件版本**里把这些行翻译成语句名，只归因到被命中的语句上。
 *
 * <p>收窄是有条件的（XML + 该文件确实绑到 SQL 卡 + Git 仓库可读 + patch 在盘上）；
 * 条件不满足时**退回整文件**并把 basis 标成 file，界面如实写出"按整文件"，不猜、也不藏。
 *
 * <p>结果缓存进 {@code commit_targets}（派生数据，可重建：重学 / 删仓库 / 删项目时按提交清掉）。
 */
@Service
public class CommitTargetService {

    private static final Logger log = LoggerFactory.getLogger(CommitTargetService.class);

    /**
     * basis=line：改动行落在该语句内；basis=file：只归因到整文件（含非 mapper 文件、读不到源码等）；
     * basis=chain：改的是这张卡**调用链上**的服务层 / DAO 文件——它自己不产卡片，只能按"代码可达"给出，
     * 按方法名匹配、可能偏宽，界面与结论单都要照实标成"按调用链"。
     */
    public record Target(long unitId, String unitKind, String basis, String anchor) {
    }

    private final JdbcTemplate jdbc;
    private final SourceAtRevision source;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public CommitTargetService(JdbcTemplate jdbc, SourceAtRevision source,
                               org.springframework.transaction.PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.source = source;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txm);
    }

    /** 一批提交的归因（改动流整页用，避免逐条提交发请求）。返回是否真的需要重算。 */
    public void ensure(Collection<Long> commitIds) {
        if (commitIds.isEmpty()) return;
        Set<Long> have = new LinkedHashSet<>();
        String in = ids(commitIds);
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT DISTINCT commit_id FROM commit_targets WHERE commit_id IN (" + in + ")")) {
            have.add(((Number) r.get("commit_id")).longValue());
        }
        for (Long id : commitIds) {
            if (have.contains(id)) continue;
            try {
                compute(id);
            } catch (Exception e) {
                log.warn("提交 #{} 影响面归因失败，退回整文件口径: {}", id, e.toString());
                computeFileOnly(id);
            }
        }
    }

    public List<Target> targets(long commitId) {
        ensure(List.of(commitId));
        List<Target> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT unit_kind, unit_id, basis, anchor FROM commit_targets WHERE commit_id=?", commitId)) {
            out.add(new Target(((Number) r.get("unit_id")).longValue(),
                    (String) r.get("unit_kind"), (String) r.get("basis"), (String) r.get("anchor")));
        }
        return out;
    }

    /** 缓存清掉，下次读取时按最新知识重算（重学、删仓库、删项目都走这里） */
    public void invalidateRepo(long repoId) {
        jdbc.update("DELETE FROM commit_targets WHERE commit_id IN (SELECT id FROM commits WHERE repo_id=?)", repoId);
    }

    /** 单条提交：改动快照是按需补的，补上之后归因口径会从"整文件"收窄成"按改动行"，必须让缓存失效 */
    public void invalidateCommit(long commitId) {
        jdbc.update("DELETE FROM commit_targets WHERE commit_id=?", commitId);
    }

    public void invalidateProject(long projectId) {
        jdbc.update("DELETE FROM commit_targets WHERE commit_id IN"
                + " (SELECT c.id FROM commits c JOIN repos r ON r.id=c.repo_id WHERE r.project_id=?)", projectId);
    }

    // ---------- 计算 ----------

    private List<Target> narrowFile(Map<String, Object> ch) {
        List<Map<String, Object>> bindings = jdbc.queryForList(
                "SELECT unit_kind, unit_id FROM file_bindings WHERE project_id=? AND file_path=?",
                ch.get("project_id"), ch.get("path"));
        List<Map<String, Object>> chain = jdbc.queryForList(
                "SELECT unit_kind, unit_id FROM chain_files WHERE project_id=? AND file_path=?",
                ch.get("project_id"), ch.get("path"));
        return narrow(ch, bindings, chain);
    }

    /**
     * 一条提交内「每个改动文件 → 受影响目标」，键是 file_changes.id。
     * 提交级归因（{@link #compute}）与详情弹屏的按文件展示都走这里，判定代码只写一遍。
     */
    public Map<Long, List<Target>> byFileChange(long commitId) {
        Map<Long, List<Target>> out = new LinkedHashMap<>();
        for (Map<String, Object> ch : changesOf(commitId)) {
            out.put(((Number) ch.get("id")).longValue(), narrowFile(ch));
        }
        return out;
    }

    private List<Map<String, Object>> changesOf(long commitId) {
        return jdbc.queryForList("""
                SELECT fc.id, fc.path, fc.patch_file, c.revision, r.project_id, r.local_path, r.vcs_type
                FROM file_changes fc
                  JOIN commits c ON c.id=fc.commit_id
                  JOIN repos r ON r.id=c.repo_id
                WHERE fc.commit_id=?""", commitId);
    }

    private void compute(long commitId) {
        List<Target> rows = new ArrayList<>();
        for (List<Target> perFile : byFileChange(commitId).values()) {
            rows.addAll(perFile);
        }
        save(commitId, rows);
    }

    /**
     * 一个改动文件 → 受影响目标。收窄只针对"绑到 SQL 卡的 MyBatis mapper XML"：
     * Java / 前端 / 配置文件的绑定本来就是少量卡片，按整文件给出即可（也不是本次的误差来源）。
     * 最后并上调用链一档：改了服务层代码，影响的是它服务的接口卡，而这些文件本来就不在 {@code file_bindings} 里。
     */
    private List<Target> narrow(Map<String, Object> change, List<Map<String, Object>> bindings,
                                List<Map<String, Object>> chain) {
        List<Target> out = new ArrayList<>();
        if (bindings.isEmpty() && chain.isEmpty()) return out;
        String path = (String) change.get("path");
        Set<String> matched = matchedStatements(change, bindings);
        Map<Long, String> nameOf = matched.isEmpty() ? Map.of() : namesOf(bindings);
        Set<String> once = new LinkedHashSet<>();
        for (Map<String, Object> b : bindings) {
            String kind = (String) b.get("unit_kind");
            long unitId = ((Number) b.get("unit_id")).longValue();
            if (!once.add(kind + ":" + unitId)) continue;
            if (!"db_object".equals(kind) || matched.isEmpty()) {
                out.add(new Target(unitId, kind, "file", path)); // 非 SQL 卡，或收窄条件不满足：整文件，与旧口径一致
                continue;
            }
            String name = nameOf.get(unitId);
            if (name == null) continue; // 卡片已不存在（重学后语句被删）
            if (matched.contains(name)) out.add(new Target(unitId, kind, "line", name));
        }
        for (Map<String, Object> b : chain) { // 同一张卡已被来源文件给出时不重复挂，档位取更强的那个
            String kind = (String) b.get("unit_kind");
            long unitId = ((Number) b.get("unit_id")).longValue();
            if (!once.add(kind + ":" + unitId)) continue;
            out.add(new Target(unitId, kind, "chain", path));
        }
        return out;
    }

    private Map<Long, String> namesOf(List<Map<String, Object>> bindings) {
        String in = bindings.stream()
                .filter(b -> "db_object".equals(b.get("unit_kind")))
                .map(b -> String.valueOf(((Number) b.get("unit_id")).longValue()))
                .distinct().reduce((a, b) -> a + "," + b).orElse("0");
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map<String, Object> d : jdbc.queryForList("SELECT id, name FROM db_objects WHERE id IN (" + in + ")")) {
            out.put(((Number) d.get("id")).longValue(), (String) d.get("name"));
        }
        return out;
    }

    /** 改动行落在哪些语句上；拿不到源码 / 不是 mapper / 解析不出锚点时返回空表 = 不收窄 */
    private Set<String> matchedStatements(Map<String, Object> change, List<Map<String, Object>> bindings) {
        boolean hasSqlCard = bindings.stream().anyMatch(b -> "db_object".equals(b.get("unit_kind")));
        String path = (String) change.get("path");
        if (!hasSqlCard || path == null || !path.toLowerCase().endsWith(".xml")) return Set.of();
        if (!"git".equals(change.get("vcs_type"))) return Set.of();
        Object patchFile = change.get("patch_file");
        if (patchFile == null || String.valueOf(patchFile).isBlank()) return Set.of();
        String patch = readText(String.valueOf(patchFile));
        if (patch == null) return Set.of();
        List<int[]> ranges = PatchRanges.newSideRanges(patch);
        if (ranges.isEmpty()) return Set.of();
        String blob = source.read((String) change.get("local_path"), (String) change.get("revision"), path).orElse(null);
        if (blob == null) return Set.of();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        Set<String> matched = new LinkedHashSet<>();
        for (MybatisAnchors.Anchor a : MybatisAnchors.scan(fileName, blob)) {
            for (int[] r : ranges) {
                if (MybatisAnchors.overlaps(a, r[0], r[1])) {
                    matched.add(a.name());
                    break;
                }
            }
        }
        return matched;
    }

    /** 归因失败（源码读不出等）时的兜底：整文件口径，行为与第十七轮之前一致，不会少给目标 */
    private void computeFileOnly(long commitId) {
        List<Target> rows = new ArrayList<>();
        for (Map<String, Object> ch : jdbc.queryForList("""
                SELECT fc.path, r.project_id FROM file_changes fc
                  JOIN commits c ON c.id=fc.commit_id JOIN repos r ON r.id=c.repo_id
                WHERE fc.commit_id=?""", commitId)) {
            for (Map<String, Object> b : jdbc.queryForList(
                    "SELECT unit_kind, unit_id FROM file_bindings WHERE project_id=? AND file_path=?",
                    ch.get("project_id"), ch.get("path"))) {
                rows.add(new Target(((Number) b.get("unit_id")).longValue(),
                        (String) b.get("unit_kind"), "file", (String) ch.get("path")));
            }
            for (Map<String, Object> b : jdbc.queryForList(
                    "SELECT unit_kind, unit_id FROM chain_files WHERE project_id=? AND file_path=?",
                    ch.get("project_id"), ch.get("path"))) {
                rows.add(new Target(((Number) b.get("unit_id")).longValue(),
                        (String) b.get("unit_kind"), "chain", (String) ch.get("path")));
            }
        }
        save(commitId, rows);
    }

    private void save(long commitId, List<Target> rows) {
        // 一个提交的归因结果一次事务写完、批量插入：真实项目一个文件就 223 个目标，
        // 逐条自动提交会让改动流首页花十几秒（SQLite 每条语句一次 fsync）
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM commit_targets WHERE commit_id=?", commitId);
            if (rows.isEmpty()) {
                // 哨兵行：区分"算过、确实没映射到任何卡"与"还没算"（否则会每次读请求都重算一遍）
                jdbc.update("INSERT OR IGNORE INTO commit_targets(commit_id, unit_kind, unit_id, basis) VALUES (?,?,?,?)",
                        commitId, "none", 0L, "none");
                return;
            }
            Map<String, Target> best = new LinkedHashMap<>();
            for (Target t : rows) {
                String key = t.unitKind() + ":" + t.unitId();
                Target kept = best.get(key);
                // 一张卡只留一档，且留最强的那份依据：改动流里"按调用链"归因不能盖掉同一条提交里
                // 另一个文件按行/按来源文件给出的归因，否则界面会把精确结论说成偏宽的。
                if (kept == null || strength(t.basis()) > strength(kept.basis())) best.put(key, t);
            }
            List<Object[]> args = new ArrayList<>();
            for (Target t : best.values()) {
                args.add(new Object[]{commitId, t.unitKind(), t.unitId(), t.basis(), t.anchor()});
            }
            jdbc.batchUpdate("INSERT OR IGNORE INTO commit_targets(commit_id, unit_kind, unit_id, basis, anchor) VALUES (?,?,?,?,?)", args);
        });
    }

    /** 归因档位强弱：按行命中 &gt; 来源文件（整文件）&gt; 调用链；一张卡同时被多档给出时留强的那档 */
    private static int strength(String basis) {
        return switch (String.valueOf(basis)) {
            case "line" -> 3;
            case "file" -> 2;
            case "chain" -> 1;
            default -> 0;
        };
    }

    private String readText(String relPath) {
        try {
            Path p = Path.of(relPath);
            if (!Files.exists(p)) return null;
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String ids(Collection<Long> commitIds) {
        return commitIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
    }
}
