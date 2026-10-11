package com.preflight.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.AuditService;
import com.preflight.llm.LlmGatewayService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 知识包导出/导入（能力 6）：项目粒度的知识以 zip 落盘（manifest + 各表 JSON + 产物文件），
 * 拷贝即迁移；导入按自然键 upsert 并重建 id 映射。凭据与库密码默认不导出。
 */
@Service
public class ExportService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final LlmGatewayService llm;

    public ExportService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, LlmGatewayService llm) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.llm = llm;
    }

    /** 导出一个项目的知识包（不含环境库密码与凭据明文） */
    public byte[] exportProject(long projectId) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "preflight-knowledge-pack");
        manifest.put("version", 3);   // v3 = db_objects 段多 database_id + dynamic_body；旧包缺列照常导入（导入按列名取值，缺则 null）
        manifest.put("exportedAt", System.currentTimeMillis());
        manifest.put("project", jdbc.queryForMap("SELECT * FROM projects WHERE id=?", projectId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repos", jdbc.queryForList("SELECT * FROM repos WHERE project_id=?", projectId));
        data.put("interfaces", jdbc.queryForList("SELECT * FROM interfaces WHERE project_id=?", projectId));
        data.put("db_objects", jdbc.queryForList("SELECT * FROM db_objects WHERE project_id=?", projectId));
        data.put("file_bindings", jdbc.queryForList("SELECT * FROM file_bindings WHERE project_id=?", projectId));
        // 调用链记账（第 44 轮）：不带走的话，换环境后"改了服务层文件影响哪个接口"这一档会整档消失，
        // 而它只能靠重学恢复——与 file_bindings 同性质，属知识而非提交派生数据
        data.put("chain_files", jdbc.queryForList("SELECT * FROM chain_files WHERE project_id=?", projectId));
        // 服务字典（分发式老框架）：不带走的话，换环境后「参数值可直接发」这项会明显变差（第十二轮 N25）
        data.put("dispatch_targets", jdbc.queryForList(
                "SELECT * FROM dispatch_targets WHERE project_id=? ORDER BY kind, code", projectId));
        data.put("login_flows", jdbc.queryForList("SELECT * FROM login_flows WHERE project_id=?", projectId));
        data.put("environments", jdbc.queryForList(
                "SELECT id, project_id, name, base_url, db_url, db_user, vars, db_type, allow_ddl_commit FROM environments WHERE project_id=?", projectId));
        // 用例：跟随项目目标
        data.put("test_cases", jdbc.queryForList("""
                SELECT tc.* FROM test_cases tc WHERE
                 (tc.target_type='interface' AND tc.target_id IN (SELECT id FROM interfaces WHERE project_id=?))
                 OR (tc.target_type='db_object' AND tc.target_id IN (SELECT id FROM db_objects WHERE project_id=?))
                """, projectId, projectId));
        // LLM 缓存全局共享：全量带走，离线回放可用
        data.put("llm_cache", jdbc.queryForList("SELECT * FROM llm_cache"));
        manifest.put("data", data);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();

            // 提示词随包迁移（R5/R9：版本化落盘的第一步——当前生效中的提示词原样带走）
            try {
                for (String purpose : new String[]{"summary", "testcase", "extract", "chat"}) {
                    zip.putNextEntry(new ZipEntry("prompts/" + purpose + ".md"));
                    zip.write(llm.systemPrompt(purpose).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            } catch (Exception ignore) {
            }

            // 产物文件（patch 快照），同名只打包一份
            java.util.Set<String> usedNames = new java.util.HashSet<>();
            List<Map<String, Object>> changes = jdbc.queryForList("""
                    SELECT DISTINCT fc.patch_file FROM file_changes fc
                    JOIN commits c ON c.id=fc.commit_id JOIN repos r ON r.id=c.repo_id
                    WHERE r.project_id=? AND fc.patch_file IS NOT NULL""", projectId);
            for (Map<String, Object> c : changes) {
                Path p = Path.of(String.valueOf(c.get("patch_file")));
                if (Files.exists(p)) {
                    String entryName = "artifacts/" + p.getFileName();
                    if (!usedNames.add(entryName)) continue;
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(Files.readAllBytes(p));
                    zip.closeEntry();
                }
            }
        }
        audit.log("knowledge.export", "project#" + projectId, bos.size() + " bytes");
        return bos.toByteArray();
    }

    /** 导入知识包：项目按名称 upsert；接口/SQL 卡按自然键 upsert；用例按目标+名称 upsert */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importProject(MultipartFile file) throws Exception {
        Map<String, Object> manifest;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || !"manifest.json".equals(entry.getName())) {
                throw new IllegalArgumentException("不是有效的 PreFlight 知识包（缺少 manifest.json）");
            }
            manifest = mapper.readValue(zip.readAllBytes(), Map.class);
            // 其余产物与提示词文件落盘（R5：Zip Slip 校验——拒绝绝对路径/穿越条目）
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
                    throw new IllegalArgumentException("知识包含非法条目: " + name);
                }
                if (name.startsWith("artifacts/")) {
                    Path base = Path.of("data", "artifacts", "imported").toAbsolutePath().normalize();
                    Path p = base.resolve(name.substring("artifacts/".length())).normalize();
                    if (!p.startsWith(base)) throw new IllegalArgumentException("知识包含非法条目: " + name);
                    Files.createDirectories(p.getParent());
                    Files.write(p, zip.readAllBytes());
                } else if (name.startsWith("prompts/")) {
                    Path base = Path.of("data", "artifacts", "prompts").toAbsolutePath().normalize();
                    Path p = base.resolve(name.substring("prompts/".length())).normalize();
                    if (!p.startsWith(base)) throw new IllegalArgumentException("知识包含非法条目: " + name);
                    Files.createDirectories(p.getParent());
                    Files.write(p, zip.readAllBytes());
                }
            }
        }
        Map<String, Object> data = (Map<String, Object>) manifest.get("data");
        Map<String, Object> projectInfo = (Map<String, Object>) manifest.get("project");
        String projectName = (String) projectInfo.get("name") + "（导入 " + java.time.LocalDate.now() + "）";
        String desc = (String) projectInfo.getOrDefault("description", "");

        jdbc.update("INSERT OR IGNORE INTO projects(name, description) VALUES (?, ?)", projectName, desc);
        long projectId = jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, projectName);

        // R5/F7：仓库随包恢复（本机路径可能不同，导入后可在页面编辑；同步失败会诚实报错）
        Map<Long, Long> repoMap = new LinkedHashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("repos", List.of())) {
            long oldId = ((Number) r.get("id")).longValue();
            String rName = String.valueOf(r.get("name"));
            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM repos WHERE project_id=? AND name=?",
                    Integer.class, projectId, rName);
            if (dup == null || dup == 0) {
                jdbc.update("INSERT INTO repos(project_id, vcs_type, repo_type, name, url, local_path, branch, base_url) VALUES (?,?,?,?,?,?,?,?)",
                        projectId, r.getOrDefault("vcs_type", "git"), r.getOrDefault("repo_type", "backend"),
                        rName, r.get("url"), r.get("local_path"), r.get("branch"), r.get("base_url"));
            }
            repoMap.put(oldId, jdbc.queryForObject("SELECT id FROM repos WHERE project_id=? AND name=?", Long.class, projectId, rName));
        }

        Map<Long, Long> ifMap = new LinkedHashMap<>();  // 旧接口id → 新id
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("interfaces", List.of())) {
            long oldId = ((Number) r.get("id")).longValue();
            Object oldRepo = r.get("repo_id");
            Object newRepo = oldRepo == null ? null : repoMap.get(((Number) oldRepo).longValue());
            jdbc.update("""
                    INSERT INTO interfaces(project_id, repo_id, name, http_method, path, params_schema, auth_type, source, db_objects, confidence, status, human_edited)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(project_id, protocol, http_method, path) DO UPDATE SET
                      name=excluded.name, params_schema=excluded.params_schema, source=excluded.source, confidence=excluded.confidence, repo_id=excluded.repo_id
                    """,
                    projectId, newRepo, r.get("name"), r.getOrDefault("http_method", ""), r.getOrDefault("path", ""),
                    r.get("params_schema"), r.getOrDefault("auth_type", "login_flow"), r.get("source"),
                    r.get("db_objects"), r.get("confidence"), r.getOrDefault("status", "draft"), r.getOrDefault("human_edited", 0));
            long newId = jdbc.queryForObject(
                    "SELECT id FROM interfaces WHERE project_id=? AND http_method=? AND path=?",
                    Long.class, projectId, r.getOrDefault("http_method", ""), r.getOrDefault("path", ""));
            ifMap.put(oldId, newId);
        }

        Map<Long, Long> dboMap = new LinkedHashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("db_objects", List.of())) {
            long oldId = ((Number) r.get("id")).longValue();
            jdbc.update("""
                    INSERT INTO db_objects(project_id, name, obj_type, signature, sql_template, source_sql, tables_involved, source, confidence, status, human_edited, database_id, dynamic_body)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(project_id, name, obj_type) DO UPDATE SET
                      signature=excluded.signature, sql_template=excluded.sql_template, source_sql=excluded.source_sql,
                      database_id=excluded.database_id, dynamic_body=excluded.dynamic_body
                    """,
                    projectId, r.get("name"), r.get("obj_type"), r.get("signature"), r.get("sql_template"),
                    r.get("source_sql"), r.get("tables_involved"), r.get("source"), r.get("confidence"),
                    r.getOrDefault("status", "draft"), r.getOrDefault("human_edited", 0),
                    r.get("database_id"), r.get("dynamic_body"));
            long newId = jdbc.queryForObject("SELECT id FROM db_objects WHERE project_id=? AND name=? AND obj_type=?",
                    Long.class, projectId, r.get("name"), r.get("obj_type"));
            dboMap.put(oldId, newId);
        }

        int cases = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("test_cases", List.of())) {
            String tt = (String) r.get("target_type");
            Long newId = "interface".equals(tt) ? ifMap.get(((Number) r.get("target_id")).longValue())
                    : dboMap.get(((Number) r.get("target_id")).longValue());
            if (newId == null) continue;
            jdbc.update("INSERT OR IGNORE INTO test_cases(target_type, target_id, name, scenario, params, expect, origin) VALUES (?,?,?,?,?,?,?)",
                    tt, newId, r.get("name"), r.get("scenario"), r.get("params"), r.get("expect"), r.get("origin"));
            cases++;
        }

        int caches = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("llm_cache", List.of())) {
            caches += jdbc.update("INSERT OR IGNORE INTO llm_cache(fingerprint, purpose, model, response) VALUES (?,?,?,?)",
                    r.get("fingerprint"), r.get("purpose"), r.get("model"), r.get("response"));
        }

        // R5/F7：环境恢复（口令不随包迁移，导入后需重填）
        int envs = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("environments", List.of())) {
            envs += jdbc.update("INSERT OR IGNORE INTO environments(project_id, name, base_url, db_url, db_user, vars, db_type, allow_ddl_commit) VALUES (?,?,?,?,?,?,?,?)",
                    projectId, r.get("name"), r.get("base_url"), r.get("db_url"), r.get("db_user"),
                    r.get("vars"), r.get("db_type"), r.get("allow_ddl_commit"));
        }
        // R5/F7：登录流恢复（凭据不随包，cred_id 置空由用户重挂）
        int flows = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("login_flows", List.of())) {
            flows += jdbc.update("INSERT OR IGNORE INTO login_flows(project_id, name, steps, apply) VALUES (?,?,?,?)",
                    projectId, r.get("name"), r.get("steps"), r.get("apply"));
        }
        // R5/F7：文件绑定恢复（repo/unit 均重映射到新库 id）
        // OR IGNORE + uq_bindings_key（第 13 轮）：同一份包重复导入不再成倍涨行，此前它是唯一没有唯一键的知识表
        int bindings = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("file_bindings", List.of())) {
            Long newRepo = repoMap.get(((Number) r.get("repo_id")).longValue());
            if (newRepo == null) continue;
            String kind = String.valueOf(r.get("unit_kind"));
            long oldUnit = ((Number) r.get("unit_id")).longValue();
            Long newUnit = "interface".equals(kind) ? ifMap.get(oldUnit)
                    : "db_object".equals(kind) ? dboMap.get(oldUnit) : null;
            if (newUnit == null) continue;
            bindings += jdbc.update("INSERT OR IGNORE INTO file_bindings(project_id, repo_id, file_path, line_start, line_end, unit_kind, unit_id) VALUES (?,?,?,?,?,?,?)",
                    projectId, newRepo, r.get("file_path"), r.get("line_start"), r.get("line_end"), kind, newUnit);
        }

        // 调用链记账（第 44 轮）：与 file_bindings 同一套 id 重映射，只认接口卡
        int chain = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("chain_files", List.of())) {
            Long newRepo = repoMap.get(((Number) r.get("repo_id")).longValue());
            if (newRepo == null) continue;
            if (!"interface".equals(String.valueOf(r.get("unit_kind")))) continue;
            Long newUnit = ifMap.get(((Number) r.get("unit_id")).longValue());
            if (newUnit == null) continue;
            chain += jdbc.update("INSERT OR IGNORE INTO chain_files(project_id, repo_id, file_path, unit_kind, unit_id)"
                            + " VALUES (?,?,?,?,?)",
                    projectId, newRepo, r.get("file_path"), "interface", newUnit);
        }

        // 服务字典（第十二轮 N25）：repo_id 可空，重映射不到就留空（字典本身是项目级的，不影响选码与构建信封）
        // framework 是第 39 轮加的列，旧知识包里没有这一项，按空串导入＝"未知框架"，读取期照旧按调用点判
        int dict = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.getOrDefault("dispatch_targets", List.of())) {
            Object oldRepo = r.get("repo_id");
            Long newRepo = oldRepo == null ? null : repoMap.get(((Number) oldRepo).longValue());
            Object fw = r.get("framework");
            dict += jdbc.update("INSERT OR IGNORE INTO dispatch_targets(project_id, repo_id, kind, code, name, entry_path, params, evidence, sources, framework)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    projectId, newRepo, r.get("kind"), r.get("code"), r.get("name"),
                    r.get("entry_path"), r.get("params"), r.get("evidence"), r.get("sources"),
                    fw == null ? "" : fw);
        }

        audit.log("knowledge.import", projectName, "interfaces=" + ifMap.size() + ", dbObjects=" + dboMap.size()
                + ", cases=" + cases + ", repos=" + repoMap.size() + ", envs=" + envs + ", flows=" + flows
                + ", bindings=" + bindings + ", chainFiles=" + chain + ", dispatchTargets=" + dict);
        // 导入即换了一套知识基线：这条提交→哪条语句的缓存全部作废，下次读取按新卡集重算
        jdbc.update("DELETE FROM commit_targets WHERE commit_id IN"
                + " (SELECT c.id FROM commits c JOIN repos r ON r.id=c.repo_id WHERE r.project_id=?)", projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("projectId", projectId);
        out.put("projectName", projectName);
        out.put("interfaces", ifMap.size());
        out.put("dbObjects", dboMap.size());
        out.put("testCases", cases);
        out.put("repos", repoMap.size());
        out.put("environments", envs);
        out.put("loginFlows", flows);
        out.put("fileBindings", bindings);
        out.put("dispatchTargets", dict);
        out.put("llmCacheMerged", caches);
        if (envs > 0) out.put("notice", "环境的数据库口令不随知识包迁移，请到[系统设置→测试环境]重录口令");
        return out;
    }
}
