package com.preflight.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.AuditService;
import com.preflight.parser.DispatchAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 分发框架服务字典（第 10 轮）：一个入口 URL 承载全部业务服务的老工程，
 * 参数契约不在 Java 方法签名里，所以单独建一本"按服务码查参数"的字典。
 *
 * 刻意不生成接口卡：这类工程动辄数千服务码，全建卡会把知识库和选卡弹屏灌满噪音；
 * 字典只在测试台选中分发入口卡时按码查询、用来构建通用信封与回填业务参数。
 */
@Service
public class DispatchDictionaryService {

    private static final Logger log = LoggerFactory.getLogger(DispatchDictionaryService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final List<DispatchAdapter> adapters;
    private final CallPathIndex callPath;

    public DispatchDictionaryService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit,
                                     List<DispatchAdapter> adapters, CallPathIndex callPath) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.callPath = callPath;
        // Spring 把所有适配器都注进来：加第三种分发风格只写一个 @Component，不必再改这里
        // （第 39 轮的根因之一就是这里曾经写死 List.of(kjdp)，第二种风格的码连学都学不进来）。
        // 按框架名排序，保证 defaultEntry 与证据顺序在不同机器上都一样。
        this.adapters = adapters.stream()
                .sorted(java.util.Comparator.comparing(DispatchAdapter::displayName))
                .toList();
    }

    /** loginFlowNote：本次新生成了登录流时的操作提示，null 表示没动登录流 */
    public record BuildResult(boolean detected, String framework, int entries, int targets,
                              int withParams, int fromCallSites, int fromChain,
                              List<String> warnings, String loginFlowNote) {}

    /** 该目录是否是分发式框架入口仓 */
    public boolean isDispatchRepo(Path root) {
        if (root == null || !Files.isDirectory(root)) return false;
        return adapters.stream().anyMatch(a -> a.detect(root));
    }

    /**
     * 「这张接口卡为什么一个入参都没学到」——只有真判定出原因才给话，判不出来返回 {@code null}。
     *
     * <p>为什么要有这一句（第四十五轮）：一码一路径那种框架，接口的入参清单<b>不在 Java 里</b>，
     * 写在每个模块自己的分发声明里（{@code service_id.<码>=参数…}）。盘上没给某个码写声明时，
     * 卡上就是只有报文头、业务参数一条都没有——看上去和「系统没学」一模一样。
     * 第卅九轮定下的口径是「0 要分诊」：这种卡必须说清是<b>盘上没声明</b>，
     * 并给出基数（这个仓一共声明了几个码）与补法，不能让人去重学一遍白费工夫。
     *
     * @param code 这条路径末段取出的功能号（由 {@code DefaultValueRules.functionNo} 认，形态不符它给 null）
     * @return 一句人话；这条码其实有声明、或这个项目根本没有这种框架时返回 {@code null}
     */
    public String whyNoParams(long projectId, String code) {
        if (code == null || code.isBlank()) return null;
        for (DispatchAdapter a : adapters) {
            if (!a.entryPathIsCallPath()) continue;
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_targets"
                    + " WHERE project_id=? AND framework=?", Integer.class, projectId, a.displayName());
            if (total == null || total == 0) continue;      // 这个项目没学到这种框架的码：这句不归它
            Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_targets"
                    + " WHERE project_id=? AND framework=? AND code=?", Integer.class,
                    projectId, a.displayName(), code);
            if (mine != null && mine > 0) return null;      // 有声明：面板自己会列参数，不在这里再说一遍
            return "末段「" + code + "」是功能号的形态，但「" + a.displayName() + "」在这个仓里声明的 "
                    + total + " 个功能号中没有它——这类框架的业务参数只写在声明里（service_id.<码>=参数清单），"
                    + "盘上没写就学不到，<b>不是漏学</b>：重学一遍还是这一句。报文头那种公共参数仍按这个仓自己的类学，"
                    + "业务参数请按需求手填，或让开发补一条声明后再重学。";
        }
        return null;
    }

    /**
     * 学完一个仓库后要不要重算字典：入口仓本身要；项目已有字典且刚学的是带 JS 的仓也要
     * （前端调用点与入口常常分处两个仓库，只在一侧学完会漏掉另一侧的证据）。
     */
    public boolean shouldRebuild(long projectId, Path learnedRoot) {
        if (isDispatchRepo(learnedRoot)) return true;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_targets WHERE project_id=?",
                Integer.class, projectId);
        return n != null && n > 0 && hasJs(learnedRoot);
    }

    /**
     * 重建整个项目的服务字典：字典是项目级的（入口在 server 仓、调用点在前端仓），故按项目全量算。
     *
     * <p>第 39 轮把归属从"整本项目一个仓"改成"按贡献仓库"：一个项目可以同时挂两种分发风格
     * （work_app 的 KJDP metaData + 统一访问的 interface-customer.properties），旧写法把全部条目盖在
     * 第一个通过 detect 的入口仓上，YApi 页按仓库数条目时其余仓库就永远是 0。
     * 同一个 kind|code 被两个仓库都声明时取先者（仓库 id 升序）并把两条证据都留下，不静默合并参数。
     */
    public BuildResult rebuild(long projectId) {
        List<Map<String, Object>> repos = jdbc.queryForList(
                "SELECT id, name, local_path FROM repos WHERE project_id=? ORDER BY id", projectId);
        List<String> warnings = new ArrayList<>();
        Map<String, DispatchAdapter.DispatchSpec> entrySpecs = new LinkedHashMap<>();
        Map<String, DispatchAdapter.DispatchTarget> merged = new LinkedHashMap<>();
        Map<String, Integer> origin = new LinkedHashMap<>(); // kind|code → 证据源位标记：1=metaData 2=js 4=调用链
        Map<String, Long> repoOf = new LinkedHashMap<>();    // kind|code → 贡献这条的仓库（先到先得）
        Map<String, DispatchAdapter> fwOf = new LinkedHashMap<>();
        // 入口 URL → 是哪个框架给的：入口卡上的「分发调用」面板要写框架名，不许再写死成某一家
        Map<String, String> specFw = new LinkedHashMap<>();
        // 仓库 id → 盘上位置：报文头公共参数要按"贡献这个码的那个仓"的源码学（不同模块公共参数不一样，
        // 第四十二轮用户原话「其它公共参数又没了，比方说 op_code 什么的」）
        Map<Long, RepoRef> repoLoc = new LinkedHashMap<>();
        Map<String, Long> entryRepoOf = new LinkedHashMap<>();
        List<Path> jsRoots = new ArrayList<>();
        List<Path> chainRoots = new ArrayList<>();
        List<String> frameworks = new ArrayList<>();
        Long entryRepoId = null;

        for (Map<String, Object> r : repos) {
            String dir = (String) r.get("local_path");
            if (dir == null || !Files.isDirectory(Path.of(dir))) continue;
            Path root = Path.of(dir).toAbsolutePath().normalize();
            long repoId = ((Number) r.get("id")).longValue();
            repoLoc.put(repoId, new RepoRef(root, String.valueOf(r.get("name"))));
            for (DispatchAdapter a : adapters) {
                if (!a.detect(root)) continue;
                Map<String, DispatchAdapter.DispatchSpec> es = a.entries(root);
                for (String p : es.keySet()) {
                    specFw.put(p, a.displayName());
                    entryRepoOf.put(p, repoId);
                }
                entrySpecs.putAll(es);
                // "入口仓"只用来给没有自述路径的条目兜底（KJDP 那种一个 URL 承载全部服务的风格），
                // 所以只认真的产出了入口信封的那个仓
                if (entryRepoId == null && !es.isEmpty()) entryRepoId = repoId;
                if (!chainRoots.contains(root)) chainRoots.add(root);
                if (!frameworks.contains(a.displayName())) frameworks.add(a.displayName());
                for (DispatchAdapter.DispatchTarget t : a.declaredTargets(root)) {
                    String k = t.kind() + "|" + t.code();
                    DispatchAdapter.DispatchTarget old = merged.get(k);
                    // 沿用既有口径：不带参数的那条声明会被后面的声明顶掉（第 39 轮实测过：
                    // 改成"先到先得"会让 534 个码的证据从 cc-bex.xml 漂到同目录的备份文件
                    // cc-bex-0903bak_oldxml 上——同一个码在多个文件里重复声明时，后出现的那份才是现行的）
                    if (old == null || old.params().isEmpty()) {
                        merged.put(k, t);
                        repoOf.put(k, repoId);
                        fwOf.put(k, a);
                    }
                    origin.merge(k, 1, this::combine);
                }
                if (hasJs(root)) jsRoots.add(root);
            }
        }
        // 前端仓库可能不满足 detect()（它没有 WEB-INF），单独按调用点扫
        for (Map<String, Object> r : repos) {
            String dir = (String) r.get("local_path");
            if (dir == null) continue;
            Path root = Path.of(dir).toAbsolutePath().normalize();
            if (Files.isDirectory(root) && hasJs(root) && !jsRoots.contains(root)) jsRoots.add(root);
        }
        String framework = String.join("、", frameworks);
        // 调用点扫描只在真的有公共入口 URL 时做：它补的是"前端往那个入口带了哪些字段"，
        // 没有入口仓的项目扫出来的键无处可归，反而会把 JS 里的字面量冒充成业务服务
        if (!entrySpecs.isEmpty()) {
            for (DispatchAdapter a : adapters) {
                List<DispatchAdapter.DispatchTarget> calls;
                try {
                    calls = a.callSiteTargets(jsRoots);
                } catch (Exception e) {
                    warnings.add("前端调用点扫描失败（字典只含元数据声明的服务）: " + e.getMessage());
                    log.warn("dispatch call-site scan failed for {}", a.displayName(), e);
                    continue;
                }
                for (DispatchAdapter.DispatchTarget t : calls) {
                    String k = t.kind() + "|" + t.code();
                    DispatchAdapter.DispatchTarget base = merged.get(k);
                    if (base == null) {
                        merged.put(k, t);
                        repoOf.putIfAbsent(k, entryRepoId);
                        fwOf.put(k, a);
                        origin.merge(k, 2, this::combine);
                    } else if (!t.params().isEmpty() || base.params().isEmpty()) {
                        merged.put(k, mergeParams(base, t));
                        origin.merge(k, 2, this::combine);
                    }
                }
            }
        }

        String defaultEntry = entrySpecs.isEmpty() ? "/redis_ajax" : entrySpecs.keySet().iterator().next();
        // 调用链补齐（第 11 轮）：metaData 的 <service> 只报名字、前端也没写字面量的服务，
        // 顺 服务→业务→bex 声明 / atom 实现 这条链把入参找回来；只补缺，已有键一概不动。
        int fromChain = 0;
        for (Path root : chainRoots) {
            for (DispatchAdapter a : adapters) {
                if (!a.detect(root)) continue;
                List<DispatchAdapter.DispatchTarget> chain;
                try {
                    chain = a.chainTargets(root);
                } catch (Exception e) {
                    warnings.add("调用链入参学习失败（字典保留元数据与前端调用点证据）: " + e.getMessage());
                    log.warn("dispatch chain learning failed", e);
                    continue;
                }
                for (DispatchAdapter.DispatchTarget t : chain) {
                    String k = t.kind() + "|" + t.code();
                    DispatchAdapter.DispatchTarget base = merged.get(k);
                    if (base == null) continue;
                    DispatchAdapter.DispatchTarget after = mergeParams(base, t);
                    if (after.params().size() > base.params().size()) fromChain++;
                    merged.put(k, after);
                    origin.merge(k, 4, this::combine);
                }
            }
        }
        int written = 0;
        int withParams = 0;
        int fromCalls = 0;
        jdbc.update("DELETE FROM dispatch_targets WHERE project_id=?", projectId);
        for (Map.Entry<String, DispatchAdapter.DispatchTarget> en : merged.entrySet()) {
            DispatchAdapter.DispatchTarget t = en.getValue();
            int org = origin.getOrDefault(en.getKey(), 0);
            if ((org & 2) != 0) fromCalls++;
            if (!t.params().isEmpty()) withParams++;
            DispatchAdapter a = fwOf.get(en.getKey());
            // 一个码一条真实路径的那种框架（转发中心）没学到路径时就是"没学到"，
            // 不能套 KJDP 的公共入口兜底——那等于给它编一个发不出去的 URL
            String path = t.entryPath() == null || t.entryPath().isBlank()
                    ? (a != null && a.entryPathIsCallPath() ? "" : defaultEntry) : t.entryPath();
            try {
                jdbc.update("""
                        INSERT INTO dispatch_targets(project_id, repo_id, kind, code, name, entry_path, params, evidence,
                                                    sources, framework)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(project_id, kind, code) DO UPDATE SET
                          name=excluded.name, entry_path=excluded.entry_path, params=excluded.params,
                          evidence=excluded.evidence, sources=excluded.sources, framework=excluded.framework,
                          repo_id=excluded.repo_id, updated_at=datetime('now','localtime')
                        """,
                        projectId, repoOf.get(en.getKey()), t.kind(), t.code(), t.name(), path,
                        mapper.writeValueAsString(t.params()), mapper.writeValueAsString(t.evidence()),
                        sourcesOf(org), a == null ? "" : a.displayName());
                written++;
            } catch (Exception e) {
                warnings.add(t.kind() + ":" + t.code() + " 入库失败: " + e.getMessage());
            }
        }
        // 报文头公共参数：一个仓一份清单（同一批卡共用），memo 键＝仓库 id + 头键名
        Map<String, List<DispatchAdapter.DispatchParam>> hdrMemo = new LinkedHashMap<>();
        int patched = patchEntryCards(projectId, entrySpecs, specFw, repoLoc, entryRepoOf, hdrMemo, written);
        int callCards = patchCallPathCards(projectId, merged, fwOf, repoOf, repoLoc, hdrMemo, written);
        String loginFlowNote = ensureLoginFlow(projectId, framework.isEmpty() ? null : framework, entrySpecs.isEmpty());
        if (!warnings.isEmpty()) {
            warnings.replaceAll(w -> w.length() > 200 ? w.substring(0, 200) + "…" : w);
        }
        audit.log("learn.dispatch", "project#" + projectId,
                (framework.isEmpty() ? "未识别到分发框架" : "分发框架 " + framework) + "：入口 " + entrySpecs.size()
                        + " 个 · 服务字典 " + written + " 条（含参数 " + withParams + " · 来自前端调用点 " + fromCalls
                        + " · 由调用链补齐 " + fromChain + "）"
                        + (patched > 0 ? " · 标注入口卡 " + patched + " 张" : "")
                        + (callCards > 0 ? " · 一码一路径的卡标注 " + callCards + " 张" : "")
                        + (loginFlowNote == null ? "" : " · 生成登录流"));
        return new BuildResult(!framework.isEmpty(), framework.isEmpty() ? null : framework, entrySpecs.size(),
                written, withParams, fromCalls, fromChain, warnings, loginFlowNote);
    }

    private static final String KJDP_FLOW_NAME = "KJDP 自动登录（redis_login）";

    /**
     * 分发框架的登录流自动生成（第 10 轮）：报文编码（ka.encrypt + DES 信封）后端已内置，
     * 用户只要建一条凭据绑上去，测试时就能自动登录。
     * 第三十一轮收窄：只在项目一条登录流都没有时才生成——已经有自己流程的人，
     * 每学一次就多一条同名"未绑定"的流，只会让人以为系统在偷偷往库里加数据（原话："为什么总是加了数据，
     * 我自己有配置真实登录流"）。有流程时一句都不提，交给他的那条用。
     */
    private String ensureLoginFlow(long projectId, String framework, boolean noEntry) {
        if (framework == null || noEntry) return null;
        try {
            Integer any = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM login_flows WHERE project_id=?", Integer.class, projectId);
            if (any != null && any > 0) return null;
            List<Map<String, Object>> steps = List.of(
                    kjdpStep("校验登录", "/redis_validate_login", false),
                    kjdpStep("登录并取回会话 Cookie", "/redis_login", true));
            jdbc.update("INSERT INTO login_flows(project_id, name, steps, apply, cred_id) VALUES (?,?,?,?,NULL)",
                    projectId, KJDP_FLOW_NAME, mapper.writeValueAsString(steps), "{}");
            return "已生成登录流「" + KJDP_FLOW_NAME + "」：请到 [系统设置 → 登录流] 给它绑定一条 账号:密码 凭据，"
                    + "并把环境的 Base URL 填到含 context path 的应用根（如 http://ip:port/work_platform）";
        } catch (Exception e) {
            log.warn("ensure KJDP login flow failed", e);
            return "登录流自动生成失败（可手工添加）: " + e.getMessage();
        }
    }

    /** 一步：同一份 fields 顺序编码（USER_CODE/TRD_PWD/USER_QRHM/validateCode），最后一步取 Cookie 并校验业务返回码 */
    private Map<String, Object> kjdpStep(String name, String path, boolean finish) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("method", "POST");
        step.put("path", path);
        step.put("encode", "kjdp-login");
        step.put("password_field", "TRD_PWD");
        step.put("encrypt_factor", "888888");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("USER_CODE", "{{cred.username}}");
        fields.put("TRD_PWD", "{{cred.password}}");
        fields.put("USER_QRHM", "");
        fields.put("validateCode", "");
        step.put("fields", fields);
        if (finish) {
            step.put("success_field", "IRETCODE");
            step.put("success_value", "0");
            step.put("extract_cookie", "pf_cookie");
            Map<String, Object> codes = new LinkedHashMap<>();
            codes.put("-1", "密码不正确（响应里的 FAILCOUNT/FAILSUM 是已错次数与上限，注意别锁号）");
            codes.put("-2", "系统使用已过期限");
            codes.put("-3", "验证码错误：本工具不识别验证码，请在环境里粘贴登录后的 Cookie 走人工兜底");
            codes.put("-4", "用户状态不合法，请联系管理员");
            codes.put("-5", "登录人数已达上限");
            codes.put("-6", "用户名不存在");
            codes.put("-7", "该账号没有可登录的角色");
            codes.put("-8", "登录失败次数过多，账号已锁定，请联系管理员");
            codes.put("-10", "分机号码已被占用");
            step.put("code_messages", codes);
        }
        return step;
    }

    /** 元数据声明优先，前端调用点只补它没有的键 */
    private DispatchAdapter.DispatchTarget mergeParams(DispatchAdapter.DispatchTarget base,
                                                       DispatchAdapter.DispatchTarget calls) {
        List<DispatchAdapter.DispatchParam> ps = new ArrayList<>(base.params());
        for (DispatchAdapter.DispatchParam p : calls.params()) {
            if (ps.stream().noneMatch(x -> x.key().equalsIgnoreCase(p.key()))) ps.add(p);
        }
        List<String> ev = new ArrayList<>(base.evidence());
        for (String s : calls.evidence()) {
            if (ev.size() < 5 && !ev.contains(s)) ev.add(s);
        }
        return new DispatchAdapter.DispatchTarget(base.kind(), base.code(),
                base.name() == null ? calls.name() : base.name(), base.entryPath(), ps, ev);
    }

    private int combine(int a, int b) {
        return a | b;
    }

    /** 证据源位标记 → 落库字符串：三源可任意组合，不再写死两两组合的三元表达式 */
    private static String sourcesOf(int org) {
        List<String> parts = new ArrayList<>();
        if ((org & 1) != 0) parts.add("metaData");
        if ((org & 2) != 0) parts.add("js");
        if ((org & 4) != 0) parts.add("chain");
        return parts.isEmpty() ? "metaData" : String.join("+", parts);
    }

    /**
     * 给分发入口卡打标记：params_schema 里写 dispatch 段（信封形状 + 字典条数），
     * 测试台据此显示"分发调用"面板。人工编辑过的卡不动。
     *
     * <p>这一份只管<b>一个 URL 承载全部服务</b>的那种框架（KJDP 的 {@code /redis_ajax}）；
     * 一个功能号一条真实路径的那种见 {@link #patchCallPathCards}。
     */
    @SuppressWarnings("unchecked")
    private int patchEntryCards(long projectId, Map<String, DispatchAdapter.DispatchSpec> specs,
                                Map<String, String> specFw, Map<Long, RepoRef> repoLoc,
                                Map<String, Long> entryRepoOf,
                                Map<String, List<DispatchAdapter.DispatchParam>> hdrMemo, int total) {
        int n = 0;
        for (String path : specs.keySet()) {
            try {
                // 入口卡可能带 context path 前缀（如 /work_platform/redis_ajax），故两端都匹配
                List<Map<String, Object>> cards = jdbc.queryForList(
                        "SELECT id, params_schema, human_edited FROM interfaces WHERE project_id=? "
                                + "AND (path=? OR path LIKE ?)",
                        projectId, path, "%/" + path.substring(1));
                for (Map<String, Object> c : cards) {
                    if (c.get("human_edited") instanceof Number hn && hn.intValue() == 1) continue;
                    DispatchAdapter.DispatchSpec s = specs.get(path);
                    Map<String, Object> d = new LinkedHashMap<>();
                    List<DispatchAdapter.DispatchParam> hdr =
                            learnHdrFields(entryRepoOf.get(path), s.hdrKey(), repoLoc, hdrMemo);
                    d.put("framework", specFw.getOrDefault(path, "金证 KJDP"));
                    d.put("entry", s.entryPath());
                    d.put("rootKey", s.rootKey());
                    d.put("hdrKey", s.hdrKey());
                    d.put("dataKey", s.dataKey());
                    d.put("serviceKey", s.serviceKey());
                    d.put("bexKey", s.bexKey());
                    d.put("autoService", s.autoService());
                    d.put("fixedQuery", s.fixedQuery());
                    d.put("contentType", s.contentType());
                    d.put("hdrTemplate", withHdrFields(s.hdrTemplate(), hdr));
                    d.put("hdrFields", hdr);
                    d.put("targets", total);
                    if (writeDispatchStamp(c, d)) n++;
                }
            } catch (Exception e) {
                log.warn("patch dispatch card failed: {}", path, e);
            }
        }
        return n;
    }

    /**
     * 给"一个功能号一条真实路径"那种框架（统一访问转发中心）的接口卡打标记（第四十一轮 N57）。
     *
     * <p>第卅九轮学到这批码时只落了字典：卡能打开、文档能预览，但测试台上<b>没有任何入口</b>知道
     * 这条路径的请求要长什么样（{@code req_msg_hdr.server_id} 必须是这个码、业务参数要落在
     * {@code req_comm_data}），所以"看得学不到、发也发不出"。这里把适配器按码给的请求形状
     * 盖到对应的卡上，面板就会出现，勾上环境就能真发。
     *
     * <p>三道门槛照实说：没学到路径的码不标（标了就是给它编一个 URL）、没有卡的码不标
     * （路径是控制器自己声明的，卡没学出来说明这个仓还没扫到）、人工编辑过的卡不动。
     *
     * <p>一个码的<b>每一条真实路径</b>都要各自盖一次：盘上的事实是同一个功能号可以被两个控制器
     * 各映射一条路径（统一回访平台的 {@code hf100146} 同时挂在 {@code hfrwlx} 与 {@code khmddy} 下），
     * 只按字典里那条代表路径盖卡，另一张卡就永远没有形状。每条路径的形状各读各的
     * {@code @RequestBody} 声明，不共用一份。
     */
    @SuppressWarnings("unchecked")
    private int patchCallPathCards(long projectId, Map<String, DispatchAdapter.DispatchTarget> merged,
                                   Map<String, DispatchAdapter> fwOf, Map<String, Long> repoOf,
                                   Map<Long, RepoRef> repoLoc,
                                   Map<String, List<DispatchAdapter.DispatchParam>> hdrMemo, int total) {
        int n = 0;
        for (Map.Entry<String, DispatchAdapter.DispatchTarget> en : merged.entrySet()) {
            DispatchAdapter a = fwOf.get(en.getKey());
            if (a == null || !a.entryPathIsCallPath()) continue;
            DispatchAdapter.DispatchTarget t = en.getValue();
            // 请求形状是「读这个仓自己的 @RequestBody 声明」推出来的（第四十五轮），所以先要认出
            // 这条码是哪个仓贡献的；认不出仓就不盖章——拿别家的信封发这家的问题是假契约。
            Long rid = repoOf.get(en.getKey());
            RepoRef loc = rid == null ? null : repoLoc.get(rid);
            if (loc == null) continue;
            for (String path : realPaths(a, loc, t)) {
                try {
                    DispatchAdapter.DispatchSpec s = a.callSpec(loc.root(), t.code(), path);
                    if (s == null) continue;
                    List<DispatchAdapter.DispatchParam> hdr =
                            learnHdrFields(rid, s.hdrKey(), repoLoc, hdrMemo);
                    // 这批码的路径是控制器原样声明的，不带 context path 前缀那种歧义，故只按整等匹配
                    List<Map<String, Object>> cards = jdbc.queryForList(
                            "SELECT id, params_schema, human_edited FROM interfaces WHERE project_id=? AND path=?",
                            projectId, path);
                    for (Map<String, Object> c : cards) {
                        if (c.get("human_edited") instanceof Number hn && hn.intValue() == 1) continue;
                        Map<String, Object> d = new LinkedHashMap<>();
                        // mode 是面板分岔的那一格：envelope＝往一个公共入口塞信封，call-path＝这条路径就是这个码自己
                        d.put("mode", "call-path");
                        d.put("framework", a.displayName());
                        d.put("kind", t.kind());
                        d.put("code", t.code());
                        d.put("entry", path);
                        d.put("rootKey", s.rootKey());
                        d.put("hdrKey", s.hdrKey());
                        d.put("dataKey", s.dataKey());
                        d.put("contentType", s.contentType());
                        d.put("hdrTemplate", withHdrFields(s.hdrTemplate(), hdr));
                        d.put("hdrFields", hdr);
                        d.put("targets", total);
                        if (writeDispatchStamp(c, d)) n++;
                    }
                } catch (Exception e) {
                    log.warn("patch call-path dispatch card failed: {}", path, e);
                }
            }
        }
        return n;
    }

    /**
     * 这个码在盘上真实映射出来的所有路径：字典里那条代表路径 + 这个仓的控制器里所有同名映射。
     * 去重、按声明顺序，丢掉空串与不以 / 开头的（那种不是路径，是别的东西）。
     */
    private List<String> realPaths(DispatchAdapter a, RepoRef loc, DispatchAdapter.DispatchTarget t) {
        Set<String> out = new LinkedHashSet<>();
        String rep = t.entryPath() == null ? "" : t.entryPath().trim();
        if (!rep.isBlank()) out.add(rep);
        try {
            CallPathIndex.Repo cr = callPath.forRepo(loc.root(), loc.name());
            for (CallPathIndex.Endpoint ep : callPath.endpoints(cr, t.code())) {
                String p = ep.entryPath() == null ? "" : ep.entryPath().trim();
                if (!p.isBlank()) out.add(p);
            }
        } catch (Exception e) {
            log.warn("call-path endpoints of {} failed", t.code(), e);   // 拿不到额外路径不算失败：代表路径照旧盖
        }
        out.removeIf(p -> !p.startsWith("/"));
        return new ArrayList<>(out);
    }

    /** 仓库 id → 盘上位置（学报文头公共参数要用：CallPathIndex 按目录认仓） */
    private record RepoRef(Path root, String name) {}

    /**
     * 报文头公共参数：<b>从贡献这张卡的仓库自己的源码里学</b>，不写死清单（第四十二轮）。
     *
     * <p>判据只有一条盘上事实：这个仓里有哪个类<b>声明了</b> {@code hdrKey} 那个字段（转发中心是
     * {@code RequestModel.req_msg_hdr : RequestHead}），就把那个类的成员当成公共参数。换第三个框架
     * 不用加分支——它的头类自己会说话；它的仓里没有这个字段，这里就是空表。
     *
     * <p>一个仓只算一次（{@code hdrMemo}）：同一批 174 张卡共用一份头清单，逐张重扫源码会把学习拖死。
     */
    private List<DispatchAdapter.DispatchParam> learnHdrFields(
            Long repoId, String hdrKey, Map<Long, RepoRef> repoLoc,
            Map<String, List<DispatchAdapter.DispatchParam>> memo) {
        RepoRef loc = repoId == null ? null : repoLoc.get(repoId);
        if (loc == null || hdrKey == null || hdrKey.isBlank()) return List.of();
        String mk = repoId + "|" + hdrKey.toLowerCase(Locale.ROOT);
        List<DispatchAdapter.DispatchParam> hit = memo.get(mk);
        if (hit != null) return hit;
        List<DispatchAdapter.DispatchParam> got;
        try {
            got = callPath.headerParams(callPath.forRepo(loc.root(), loc.name()), hdrKey);
        } catch (Exception e) {
            // 学不到不影响建卡：请求体照旧按适配器给的模板发，只是界面少一段说明
            log.warn("learn header fields failed for {} / {}", loc.root(), hdrKey, e);
            got = List.of();
        }
        memo.put(mk, got);
        return got;
    }

    /**
     * 学到的头字段并进要发出去的那份头模板：值以模板为准（框架必填项如 {@code server_id=$code}），
     * 顺序以源码声明为准，模板里独有的键留在最后。只差大小写的同名列不重复塞（请求体里出现两个
     * 一样的键，服务端按哪个算就成了运气）。
     */
    private static Map<String, Object> withHdrFields(Map<String, Object> template,
                                                     List<DispatchAdapter.DispatchParam> fields) {
        Map<String, Object> base = template == null ? Map.of() : template;
        if (fields.isEmpty()) return base;
        Map<String, String> lower = new LinkedHashMap<>();
        for (String k : base.keySet()) lower.put(k.toLowerCase(Locale.ROOT), k);
        Map<String, Object> m = new LinkedHashMap<>();
        for (DispatchAdapter.DispatchParam f : fields) {
            String key = lower.getOrDefault(f.key().toLowerCase(Locale.ROOT), f.key());
            Object v = base.get(key);
            m.put(key, v != null ? v : (f.def() == null ? "" : f.def()));
        }
        for (Map.Entry<String, Object> e : base.entrySet()) m.putIfAbsent(e.getKey(), e.getValue());
        return m;
    }

    /** 接口卡上盖好的报文头那一段：框架名、头键名、学到的公共参数清单，与发出去的那份头模板 */
    public record HdrStamp(String framework, String hdrKey, List<Map<String, Object>> fields,
                           Map<String, Object> template) {
        static HdrStamp none() {
            return new HdrStamp("", "", List.of(), Map.of());
        }

        boolean isEmpty() {
            return fields.isEmpty();
        }

        /** 模板里这个键要发什么值（学来的键可能与模板大小写不同，与 {@link #withHdrFields} 同一口径） */
        public String templateValue(String key) {
            if (key == null || template.isEmpty()) return "";
            Object v = template.get(key);
            if (v == null) {
                for (Map.Entry<String, Object> e : template.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(key)) {
                        v = e.getValue();
                        break;
                    }
                }
            }
            return v == null ? "" : String.valueOf(v);
        }
    }

    /**
     * 这条请求路径上的报文头公共参数——<b>直接读接口卡上盖好的那一份</b>，不在这里再算一遍。
     *
     * <p>为什么读章不重算（第四十二轮接线）：测试台「一码一路径」面板显示的就是卡上这一份
     * （{@link #patchCallPathCards} 按那个仓自己的头类学的）。YApi 草稿要是自己去学，同一个事实
     * 就变成两个出口——学习口径改一处，面板和文档各说各话。
     *
     * <p>{@code template} 是卡上那份「发出去的头模板」（值以模板为准，{@code $code} 由调用方换成
     * 当前业务代码）：面板拼请求体读的就是它，文档要给出示例值也只能读它，否则两边预填会不一致。
     *
     * <p>空清单的三种原因由调用方照实说：这个框架的头是手抄模板（金证那种大写信封，Java 里没有
     * 逐字同名的字段可认）、这个仓还没按新口径重学过、这张卡被人工编辑过所以学习不覆盖它。
     */
    @SuppressWarnings("unchecked")
    public HdrStamp stampedHdrFields(long projectId, String entryPath) {
        if (entryPath == null || entryPath.isBlank()) return HdrStamp.none();
        for (Map<String, Object> c : jdbc.queryForList(
                "SELECT params_schema FROM interfaces WHERE project_id=? AND path=?", projectId, entryPath)) {
            if (c.get("params_schema") == null) continue;
            Map<String, Object> schema;
            try {
                schema = mapper.readValue(String.valueOf(c.get("params_schema")), Map.class);
            } catch (Exception ignore) {
                continue;
            }
            if (!(schema.get("dispatch") instanceof Map<?, ?> d)) continue;
            if (!(d.get("hdrFields") instanceof List<?> l) || l.isEmpty()) continue;
            Map<String, Object> tpl = d.get("hdrTemplate") instanceof Map<?, ?> t
                    ? (Map<String, Object>) t : Map.of();
            return new HdrStamp(String.valueOf(d.get("framework")), String.valueOf(d.get("hdrKey")),
                    (List<Map<String, Object>>) l, tpl);
        }
        return HdrStamp.none();
    }

    /**
     * 把 dispatch 段并回卡片的 params_schema：只加/换这一段，其余（DTO 学出来的字段）原样留着。
     * 返回"这张卡现在带没带标记"——内容一致时不重写（每次重学都刷一遍 updated_at，会让
     * 「这张卡什么时候变过」这条留痕失去意义），但审计计数照旧算它一张。
     */
    @SuppressWarnings("unchecked")
    private boolean writeDispatchStamp(Map<String, Object> card, Map<String, Object> d) throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        boolean same = false;
        if (card.get("params_schema") != null) {
            try {
                schema = mapper.readValue(String.valueOf(card.get("params_schema")), Map.class);
                same = mapper.valueToTree(schema.get("dispatch")).equals(mapper.valueToTree(d));
            } catch (Exception ignore) {
                schema = new LinkedHashMap<>();
            }
        }
        if (same) return true;
        schema.put("dispatch", d);
        schema.putIfAbsent("type", "object");
        schema.putIfAbsent("properties", new LinkedHashMap<>());
        schema.putIfAbsent("required", List.of());
        jdbc.update("UPDATE interfaces SET params_schema=?, updated_at=datetime('now','localtime') WHERE id=?",
                mapper.writeValueAsString(schema), card.get("id"));
        return true;
    }

    private boolean hasJs(Path root) {
        try (Stream<Path> w = Files.walk(root, 6)) {
            return w.anyMatch(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".js"));
        } catch (Exception e) {
            return false;
        }
    }
}
