package com.preflight.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影响分析：确定性结论，不靠 LLM 猜。
 * 第 17 轮起，"哪些目标受影响"完全由 {@link CommitTargetService} 判定（改动行 → 语句），
 * 本类只负责把目标 id 翻译成人看得懂的名字与置信度，并缓存给界面用。
 */
@Service
public class ImpactService {

    private final JdbcTemplate jdbc;
    private final CommitTargetService targets;

    public ImpactService(JdbcTemplate jdbc, CommitTargetService targets) {
        this.jdbc = jdbc;
        this.targets = targets;
    }

    /** basis: line=按改动行命中具体语句 / file=只能到整文件 / chain=改的是这张卡调用链上的服务层文件；anchor=语句名或改动文件 */
    public record ImpactedTarget(String type, long id, String name, String detail, String confidence,
                                 String basis, String anchor) {
        public ImpactedTarget(String type, long id, String name, String detail, String confidence) {
            this(type, id, name, detail, confidence, "file", null);
        }
    }

    /**
     * 详情弹屏的按文件影响面：与提交级 {@link #forCommit} 同一套归因（第十七轮统一口径）。
     * 不再读 {@code file_changes.impacts_json} —— 那列是旧口径（改动文件 = 整文件所有卡）留下的缓存，
     * 口径一改就慢一轮：列表说「按改动行命中 1 条语句」，弹屏却还列着整文件 223 张卡。
     * 也不在读取路径里回写缓存（一个 791 文件的提交就是 791 次 UPDATE + fsync）。
     */
    public Map<Long, List<ImpactedTarget>> byFileChange(long commitId) {
        Map<Long, List<CommitTargetService.Target>> raw = targets.byFileChange(commitId);
        List<CommitTargetService.Target> all = new ArrayList<>();
        raw.values().forEach(all::addAll);
        CardIndex ix = indexCards(all);
        Map<Long, List<ImpactedTarget>> out = new LinkedHashMap<>();
        raw.forEach((fcId, ts) -> {
            List<ImpactedTarget> described = describe(ts, ix);
            if (described.isEmpty()) {
                // R3：无法映射的文件兜底展示（01 能力2：无法映射 → 仅摘要人工判断）
                described.add(new ImpactedTarget("unmapped", -1, "未映射到已知接口/SQL（请人工判断影响范围）", "none", "none", "none", null));
            }
            out.put(fcId, described);
        });
        return out;
    }

    /** 对整条提交计算影响面（聚合去重）——与「本提交用例」统计、一键回归同一份归因 */
    public List<ImpactedTarget> forCommit(long commitId) {
        return describe(targets.targets(commitId)).stream()
                .collect(java.util.stream.Collectors.toMap(t -> t.type() + ":" + t.id(), t -> t,
                        (a, b) -> a, LinkedHashMap::new))
                .values().stream().filter(t -> !"none".equals(t.type())).toList();
    }

    /** 目标 id → 卡片名/状态/置信度；整批取，不逐条发查询（真实项目一处就有 223 个目标） */
    private record CardIndex(Map<Long, Map<String, Object>> ifc, Map<Long, Map<String, Object>> dob) {
    }

    private CardIndex indexCards(List<CommitTargetService.Target> ts) {
        return new CardIndex(
                byId(ts, "interface",
                        "SELECT id, name, http_method, path, status, confidence FROM interfaces WHERE id IN (%s)"),
                byId(ts, "db_object",
                        "SELECT id, name, obj_type, status FROM db_objects WHERE id IN (%s)"));
    }

    private List<ImpactedTarget> describe(List<CommitTargetService.Target> ts) {
        return describe(ts, indexCards(ts));
    }

    private List<ImpactedTarget> describe(List<CommitTargetService.Target> ts, CardIndex ix) {
        List<ImpactedTarget> out = new ArrayList<>();
        for (CommitTargetService.Target t : ts) {
            Map<String, Object> row = "interface".equals(t.unitKind()) ? ix.ifc().get(t.unitId()) : ix.dob().get(t.unitId());
            if (row == null) continue; // 卡片已被重学删掉
            if ("interface".equals(t.unitKind())) {
                out.add(new ImpactedTarget("interface", t.unitId(),
                        row.get("name") + " (" + row.get("http_method") + " " + row.get("path") + ")",
                        String.valueOf(row.get("status")),
                        ((Number) row.get("confidence")).doubleValue() >= 0.9 ? "high" : "medium",
                        t.basis(), t.anchor()));
            } else {
                out.add(new ImpactedTarget("db_object", t.unitId(),
                        row.get("name") + " [" + row.get("obj_type") + "]",
                        String.valueOf(row.get("status")), "high", t.basis(), t.anchor()));
            }
        }
        return out;
    }

    private Map<Long, Map<String, Object>> byId(List<CommitTargetService.Target> ts, String kind, String sqlTpl) {
        String in = ts.stream().filter(t -> kind.equals(t.unitKind())).map(t -> String.valueOf(t.unitId()))
                .distinct().reduce((a, b) -> a + "," + b).orElse("0");
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(String.format(sqlTpl, in))) {
            out.put(((Number) r.get("id")).longValue(), r);
        }
        return out;
    }
}
