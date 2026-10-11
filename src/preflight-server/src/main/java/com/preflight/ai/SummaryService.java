package com.preflight.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.llm.LlmGatewayService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 改动摘要与风险：LLM 生成"人话摘要"，不可用/失败自动降级为结构化 diff 统计。
 * 风险为启发式分级（DDL/Java=高，mapper/SQL=中，其余=低），LLM 摘要不改变风险结论。
 */
@Service
public class SummaryService {

    private final JdbcTemplate jdbc;
    private final LlmGatewayService llm;
    private final ObjectMapper mapper;

    public SummaryService(JdbcTemplate jdbc, LlmGatewayService llm, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.mapper = mapper;
    }

    public record SummaryResult(boolean llmUsed, String summary, String risk) {}

    public SummaryResult summarizeCommit(long commitId) {
        Map<String, Object> commit = jdbc.queryForMap("SELECT * FROM commits WHERE id=?", commitId);
        List<Map<String, Object>> changes = jdbc.queryForList(
                "SELECT path, change_type, patch_file, risk_level FROM file_changes WHERE commit_id=?", commitId);

        String diffText = buildDiffText(changes);
        String fallback = buildFallback(commit, changes);

        String summary = fallback;
        boolean usedLlm = false;
        try {
            String prompt = "提交信息：" + commit.get("message") + "\n\n变更文件：\n" + fileList(changes)
                    + "\n\n代码差异（可能截断）：\n" + diffText
                    + "\n\n请输出 3~5 句中文摘要。";
            summary = llm.chat("summary", prompt).text().trim();
            usedLlm = true;
        } catch (Exception e) {
            // 降级：结构化统计
        }

        String commitRisk = "low";
        for (Map<String, Object> c : changes) {
            String r = String.valueOf(c.getOrDefault("risk_level", "low"));
            if (level(r) > level(commitRisk)) commitRisk = r;
        }
        jdbc.update("UPDATE commits SET summary=?, risk_level=? WHERE id=?", summary, commitRisk, commitId);
        return new SummaryResult(usedLlm, summary, commitRisk);
    }

    private String buildDiffText(List<Map<String, Object>> changes) {
        StringBuilder sb = new StringBuilder();
        int budget = 6000;
        for (Map<String, Object> c : changes) {
            String pf = (String) c.get("patch_file");
            if (pf == null) continue;
            try {
                String patch = Files.readString(Path.of(pf));
                if (patch.length() > budget) patch = patch.substring(0, budget) + "\n...[截断]";
                sb.append("--- ").append(c.get("path")).append(" ---\n").append(patch).append('\n');
                budget -= patch.length();
                if (budget <= 0) break;
            } catch (Exception ignore) {
            }
        }
        return sb.isEmpty() ? "（无 patch 快照，SVN 仓库仅提供变更清单）" : sb.toString();
    }

    private String fileList(List<Map<String, Object>> changes) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : changes) {
            sb.append(c.get("change_type")).append(" ").append(c.get("path")).append('\n');
        }
        return sb.toString();
    }

    private String buildFallback(Map<String, Object> commit, List<Map<String, Object>> changes) {
        long a = changes.stream().filter(c -> "A".equals(c.get("change_type"))).count();
        long m = changes.stream().filter(c -> "M".equals(c.get("change_type"))).count();
        long d = changes.stream().filter(c -> "D".equals(c.get("change_type"))).count();
        return "（降级摘要·LLM 不可用）提交「" + commit.get("message") + "」由 " + commit.get("author")
                + " 提交，共变更 " + changes.size() + " 个文件（新增 " + a + "、修改 " + m + "、删除 " + d
                + "）。主要文件：" + changes.stream().map(c -> String.valueOf(c.get("path")).substring(
                        String.valueOf(c.get("path")).lastIndexOf('/') + 1)).limit(5)
                .reduce((x, y) -> x + "、" + y).orElse("-");
    }

    private int level(String r) {
        return switch (r) {
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        };
    }
}
