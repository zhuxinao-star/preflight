package com.preflight.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.exec.DbExecutorService;
import com.preflight.parser.BodyShape;
import com.preflight.parser.DefaultValueRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 可测性体检（第 11 轮）：把"学完就能测"从主观判断变成机器能答的硬标准。
 *
 * 学习只回答"代码里有什么"；这张表回答"现在点【执行】能不能拿到有效响应"——
 * 固定全列（不做条件隐藏，不适用的也列出来标清楚为什么），每项给「事实 / 怎么补 / 直达入口」。
 * 项数会随轮次增加（第 11 轮十项 → 第五十轮补「对外调用路径」第十一项），所以界面与文案都不按项数写死。
 *
 * 刻意不落库：全部是派生结论，存下来必然与仓库、环境、字典的改动不同步。
 * 项目页实时计算；学习完成后自动跑一次仅写审计（见 KnowledgeController 学习任务收尾）。
 */
@Service
public class TestabilityService {

    /** level：ok=🟢 就绪 · warn=🟡 能测但要先补 · fail=🔴 现在测不了 · skip=⚪ 本项目不适用 */
    public record Item(String key, String label, String level, String fact, String fix, String link) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DbExecutorService dbExecutor;
    private final com.preflight.common.CredentialUsageService credUsage;
    private final GatewayRoutingService routing;

    /** 深度体检的探测线程：与 HTTP 线程隔离，便于超时后放弃等待而不拖住页面 */
    private static final ExecutorService PROBE_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pf-testability-probe");
        t.setDaemon(true);
        return t;
    });

    public TestabilityService(JdbcTemplate jdbc, ObjectMapper mapper, DbExecutorService dbExecutor,
                              com.preflight.common.CredentialUsageService credUsage,
                              GatewayRoutingService routing) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.dbExecutor = dbExecutor;
        this.credUsage = credUsage;
        this.routing = routing;
    }

    public Map<String, Object> check(long projectId, boolean deep) {
        List<Item> items = new ArrayList<>();
        Cards cards = readCards(projectId);
        items.add(repoItem(projectId));
        items.add(ifaceItem(cards));
        items.add(skeletonItem(cards));
        items.add(sendableItem(cards));
        Map<String, Object> env = findEnv(projectId);
        items.add(envItem(env, deep));
        items.add(directItem(projectId, env));
        items.add(pathItem(projectId, env, cards));
        items.add(loginItem(projectId, cards));
        items.add(dispatchItem(projectId, cards));
        items.add(dbItem(env, projectId, deep));
        items.add(caseItem(projectId, cards));

        int fail = count(items, "fail"), warn = count(items, "warn");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("projectId", projectId);
        out.put("deep", deep);
        out.put("items", items);
        out.put("fail", fail);
        out.put("warn", warn);
        out.put("ready", fail == 0);
        out.put("verdict", fail > 0 ? "还不能测" : warn > 0 ? "能测，但有 " + warn + " 项要先补" : "学完即可测");
        out.put("summary", "🔴 " + fail + " · 🟡 " + warn + " · 🟢 " + count(items, "ok")
                + " · ⚪ " + count(items, "skip"));
        return out;
    }

    /** 学习完成后的一句话结论，写进审计留痕用 */
    public String brief(long projectId) {
        Map<String, Object> r = check(projectId, false);
        return "🔴" + r.get("fail") + " 🟡" + r.get("warn") + " 🟢" + count(items(r), "ok")
                + " · " + r.get("verdict");
    }

    // ---------- 各项 ----------

    private Item repoItem(long projectId) {
        List<Map<String, Object>> repos = jdbc.queryForList(
                "SELECT name, repo_type, learned_at FROM repos WHERE project_id=?", projectId);
        if (repos.isEmpty()) {
            return new Item("repo", "仓库与学习", "fail", "项目下还没有任何仓库目录",
                    "【项目与仓库】→ 添加仓库目录（前端/后台/中台/数据库可分别增挂），然后逐个点【学习】", "projects");
        }
        long learned = repos.stream().filter(r -> r.get("learned_at") != null).count();
        if (learned == 0) {
            return new Item("repo", "仓库与学习", "fail", repos.size() + " 个仓库全部未学习",
                    "在仓库行点【学习】（后台异步，进度看该行徽标）", "projects");
        }
        if (learned < repos.size()) {
            List<String> pending = repos.stream().filter(r -> r.get("learned_at") == null)
                    .map(r -> String.valueOf(r.get("name"))).toList();
            return new Item("repo", "仓库与学习", "warn",
                    "已学习 " + learned + "/" + repos.size() + "，未学习：" + String.join("、", pending),
                    "未学习的目录不会有接口卡，需要时在仓库行点【学习】", "projects");
        }
        return new Item("repo", "仓库与学习", "ok", repos.size() + " 个仓库均已学习", "", "projects");
    }

    private Item ifaceItem(Cards cards) {
        if (cards.total == 0) {
            return new Item("iface", "接口卡", "fail", "项目下没有学到任何接口卡",
                    "多半是仓库没学习或该目录不含服务端代码：先【学习】后台仓库；SQL/存储过程在【SQL 直测】选卡", "projects");
        }
        if (cards.draft > cards.total * 0.5) {
            return new Item("iface", "接口卡", "warn",
                    "共 " + cards.total + " 张，其中 " + cards.draft + " 张仍是待确认（draft）状态",
                    "【知识库】点【✔ 按置信度批量确认】（可先抽查再确认）；批量确认只改状态，卡仍会被下次重学刷新，只有人工编辑过的卡才受保护", "knowledge");
        }
        return new Item("iface", "接口卡", "ok", "共 " + cards.total + " 张（待确认 " + cards.draft + "）", "", "knowledge");
    }

    private Item skeletonItem(Cards cards) {
        if (cards.entryCards > 0 && cards.plainCards == 0) {
            return new Item("skeleton", "入参骨架", "ok",
                    "本项目接口都是分发入口（参数按业务代码存在服务字典里），不需要逐卡骨架", "", "lab");
        }
        if (cards.plainCards == 0) {
            return new Item("skeleton", "入参骨架", "warn", "没有可统计的普通接口卡", "", "lab");
        }
        double ratio = cards.withSkeleton * 100.0 / cards.plainCards;
        String fact = cards.withSkeleton + "/" + cards.plainCards + " 张普通接口卡学到入参字段（"
                + Math.round(ratio) + "%）" + (cards.entryCards > 0 ? "；另有 " + cards.entryCards + " 张分发入口卡" : "");
        if (cards.withSkeleton == 0) {
            return new Item("skeleton", "入参骨架", "fail", fact,
                    "升级到能展开 DTO 的版本后，对后台仓库点【学习】并等全量重扫完成（一次性）", "projects");
        }
        if (ratio < 60) {
            return new Item("skeleton", "入参骨架", "warn", fact,
                    "空骨架的卡多为形参类型不在本仓库（跨 jar 的 DTO）：在测试台选中该卡后手工补字段，或把定义该类的目录增挂为仓库再学习", "lab");
        }
        return new Item("skeleton", "入参骨架", "ok", fact, "", "lab");
    }

    private Item sendableItem(Cards cards) {
        if (cards.entryCards > 0 && cards.plainCards == 0) {
            return new Item("sendable", "参数值可直接发", "skip",
                    "分发入口卡的入参由服务字典 + 默认值规则表给出，见「分发字典」一项", "", "lab");
        }
        if (cards.leaves == 0) {
            return new Item("sendable", "参数值可直接发", "warn", "没有入参字段可评估",
                    "先在测试台选中一张接口卡确认骨架", "lab");
        }
        double real = cards.realLeaves * 100.0 / cards.leaves;
        String fact = cards.leaves + " 个入参叶子字段里 " + cards.realLeaves + " 个（" + Math.round(real)
                + "%）已有可直接发的值（学习到的默认值或跨项目规则值：op_code=1、功能号字段=本次功能号），"
                + (cards.leaves - cards.realLeaves) + " 个仍是占位值需按业务补填";
        if (cards.realLeaves == 0) {
            return new Item("sendable", "参数值可直接发", "fail", cards.leaves
                    + " 个入参字段全部只有 test/TEST 之类占位值，直接发过去服务端必然按无效参数处理",
                    "这类字段代码里学不到具体值（如业务编号）：在测试台字段说明清单里按【需手填】标红的项补值后【存为用例】", "lab");
        }
        if (cards.fullyPlaceholder > 0) {
            return new Item("sendable", "参数值可直接发", "warn", fact + "；其中 " + cards.fullyPlaceholder
                    + " 张卡仍全是占位值",
                    "全是占位值的卡直接执行只会拿到业务报错：按字段说明清单补值，或先用【一键生成用例】再【修复】", "lab");
        }
        return new Item("sendable", "参数值可直接发", "ok", fact, "", "lab");
    }

    private Item envItem(Map<String, Object> env, boolean deep) {
        if (env == null) {
            return new Item("env", "项目环境地址", "fail", "还没有任何环境（项目级 Base URL 未配置）",
                    "【系统设置】→ 环境 → 新建，填 Base URL（走全流程登录测试时必需）", "settings");
        }
        String baseUrl = str(env.get("base_url"));
        if (baseUrl.isBlank()) {
            return new Item("env", "项目环境地址", "warn",
                    "环境「" + env.get("name") + "」没有 Base URL，只能靠仓库直连地址测试",
                    "【系统设置】→ 环境 → 编辑，补 Base URL", "settings");
        }
        if (!deep) {
            return new Item("env", "项目环境地址", "ok",
                    "环境「" + env.get("name") + "」Base URL = " + baseUrl + "（未做连通性探测）",
                    "勾上方【深度体检】可真实打一次该地址", "settings");
        }
        String probe = probeUrl(baseUrl);
        return probe == null
                ? new Item("env", "项目环境地址", "fail", "Base URL " + baseUrl + " 连不上（超时或拒绝连接）",
                        "确认服务已启动、端口没写错、本机可访问；只测单个服务时可用仓库直连地址绕开", "settings")
                : new Item("env", "项目环境地址", "ok", "Base URL " + baseUrl + " 可达（HTTP " + probe + "）", "", "settings");
    }

    private Item directItem(long projectId, Map<String, Object> env) {
        List<Map<String, Object>> repos = jdbc.queryForList(
                "SELECT name, base_url, repo_type FROM repos WHERE project_id=? AND repo_type IN ('backend','middle')",
                projectId);
        List<String> withUrl = repos.stream().filter(r -> !str(r.get("base_url")).isBlank())
                .map(r -> String.valueOf(r.get("name"))).toList();
        if (withUrl.isEmpty()) {
            return new Item("direct", "仓库直连地址", "warn",
                    "没有服务仓库配置自己的 Base URL，只能走项目环境全流程（需登录）",
                    "【项目与仓库】→ 编辑仓库 → 填「服务基础地址」：该服务单独起进程时可不跑登录流直接打，接口测试台会出现 🟩 仓库直连", "projects");
        }
        return new Item("direct", "仓库直连地址", "ok",
                withUrl.size() + " 个服务仓库可直连：" + String.join("、", withUrl), "", "projects");
    }

    /**
     * 「学到的路径 ≠ 要发的路径」这一档（第五十轮）。
     *
     * <p>微服务项目里注解学到的是<b>模块内部</b>路径，环境地址是 nginx／网关，照卡片路径发会被
     * 原样退回（405 一段 HTML 就是这样）。判据不在这里重写一遍——逐卡问 {@link GatewayRoutingService}
     * 那一个出口，与测试台、真发、复制 cURL 看到的是同一份答案。
     */
    private Item pathItem(long projectId, Map<String, Object> env, Cards cards) {
        if (cards.total == 0) {
            return new Item("path", "对外调用路径", "skip", "还没有接口卡，谈不上路径", "", "knowledge");
        }
        if (cards.plainCards == 0) {
            return new Item("path", "对外调用路径", "skip",
                    "本项目只有分发入口卡（路径由服务字典给），没有需要补网关前缀的普通卡", "", "lab");
        }
        String baseUrl = env == null ? "" : str(env.get("base_url"));
        Map<String, Integer> c = routing.pathCensus(projectId, baseUrl);
        int ev = nz(c.get(GatewayRoutingService.EVIDENCED));
        int inf = nz(c.get(GatewayRoutingService.INFERRED));
        int miss = nz(c.get(GatewayRoutingService.MISSING_INPUT));
        int na = nz(c.get(GatewayRoutingService.NOT_APPLICABLE));
        String fact = cards.plainCards + " 张普通卡里 " + (ev + inf) + " 张能算出对外路径（前端实证 " + ev
                + " · 按网关路由表推 " + inf + "）、" + na + " 张盘上无网关证据（照卡片路径发）、"
                + miss + " 张推不出";
        if (ev + inf == 0 && miss == 0) {
            return new Item("path", "对外调用路径", "ok",
                    cards.plainCards + " 张普通卡都按卡片路径发（盘上没找到网关证据：单体或直连部署就是这样）",
                    "", "projects");
        }
        if (miss > 0 && ev + inf == 0) {
            return new Item("path", "对外调用路径", "fail", fact,
                    "这项目是网关转发的，但一张都算不出对外路径：到 [项目与仓库] 把网关仓与前端仓登记上并重学"
                            + "（服务名来自各模块的 bootstrap.yml，部署前缀来自前端请求封装实例的 baseURL）", "projects");
        }
        if (miss > 0) {
            return new Item("path", "对外调用路径", "warn", fact,
                    "推不出的那 " + miss + " 张多半是所属模块的 spring.application.name 没在盘上（配置由 Nacos 下发）"
                            + "：把带 bootstrap.yml 的那层目录登记进仓库再重学，或在接口卡上人工改这一张的路径", "projects");
        }
        return new Item("path", "对外调用路径", "ok", fact + "（测试台顶部会逐张点名依据是哪个文件哪一行）", "", "lab");
    }

    private Item loginItem(long projectId, Cards cards) {
        if (cards.needLogin == 0) {
            return new Item("login", "登录与凭据", "skip", "接口卡均未标记需要登录（auth_type 不是 login_flow）",
                    "若实际服务需要登录，在接口卡上把访问方式改成 login_flow", "knowledge");
        }
        List<Map<String, Object>> flows = jdbc.queryForList(
                "SELECT id, name, cred_id FROM login_flows WHERE project_id=?", projectId);
        if (flows.isEmpty()) {
            return new Item("login", "登录与凭据", "fail",
                    cards.needLogin + " 张卡标为需登录，但项目里没有任何登录流",
                    "【系统设置】→ 登录流 → 新建（分发框架项目可在仓库【学习】后自动得到 KJDP 登录流）", "settings");
        }
        List<String> bound = new ArrayList<>();
        List<String> svnBound = new ArrayList<>();
        Map<Long, String> svnCreds = credUsage.svnRepoByCredId();
        for (Map<String, Object> f : flows) {
            Object credId = f.get("cred_id");
            if (credId == null || String.valueOf(credId).isBlank() || "0".equals(String.valueOf(credId))) continue;
            Integer ok = jdbc.queryForObject("SELECT COUNT(*) FROM credentials WHERE id=?", Integer.class, credId);
            if (ok == null || ok == 0) continue;
            String flowName = String.valueOf(f.get("name"));
            bound.add(flowName);
            // 第三十二轮：SVN 拉代码账号和被测系统的登录账号同库不同用途，绑错了体检不能跟着说"就绪"
            String svnRepo = svnCreds.get(((Number) credId).longValue());
            if (svnRepo != null) svnBound.add(flowName + "（绑的是 " + svnRepo + " 的 SVN 账号）");
        }
        if (bound.isEmpty()) {
            return new Item("login", "登录与凭据", "fail",
                    "有 " + flows.size() + " 条登录流，但没有一条绑定了有效凭据（执行时会以未登录状态发出，业务返回码通常是 -9 缺少登录 Cookie）",
                    "【系统设置】→ 凭据 → 新建账号密码，再到登录流编辑里选该凭据，并用【试跑】验证能拿到会话", "settings");
        }
        if (svnBound.size() == bound.size()) {
            return new Item("login", "登录与凭据", "warn",
                    "已绑定凭据的登录流绑的全是 SVN 拉代码账号：" + String.join("、", svnBound)
                            + "。除非被测系统的登录账号与 SVN 完全相同，否则登录步骤里 {{cred.username}} / {{cred.password}} 填进去的是仓库账号",
                    "【系统设置】→ 凭据保险库 → 另建一条业务登录账号（名字别带 -svn，内容 user:password），再到登录流里改绑并【试跑】", "settings");
        }
        if (!svnBound.isEmpty()) {
            return new Item("login", "登录与凭据", "warn",
                    "已绑定凭据的登录流：" + String.join("、", bound) + "；其中绑成 SVN 拉代码账号的：" + String.join("、", svnBound),
                    "确认这些流要用的确实是仓库账号，否则在【凭据保险库】另建业务登录账号再改绑", "settings");
        }
        return new Item("login", "登录与凭据", "ok",
                "已绑定凭据的登录流：" + String.join("、", bound), "", "settings");
    }

    private Item dispatchItem(long projectId, Cards cards) {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_targets WHERE project_id=?", Integer.class, projectId);
        int n = total == null ? 0 : total;
        if (cards.entryCards == 0) {
            if (n == 0) {
                return new Item("dispatch", "分发字典", "skip",
                        "该项目没有分发式框架（既没有 /redis_ajax 这类一 URL 多服务的入口卡，"
                                + "也没有 interface-customer.properties 那种功能号声明）", "", "lab");
            }
            // 「一个功能号一条真实路径」的框架没有公共入口可言：有字典条目就是它，不该报成"没有分发框架"
            return new Item("dispatch", "分发字典", "ok",
                    "字典 " + n + " 个功能号" + frameworkBreakdown(projectId)
                            + "；这类框架每个功能号自带请求路径，不需要入口卡", "", "lab");
        }
        if (n == 0) {
            return new Item("dispatch", "分发字典", "fail",
                    "有 " + cards.entryCards + " 张分发入口卡，但服务字典是空的（选不到业务代码，也就发不出请求）",
                    "在测试台分发面板点【↻ 重建服务字典】，或【学习】前端仓库（参数声明与调用点分处两仓时两边都要学）", "lab");
        }
        int withParams = nz(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_targets WHERE project_id=? "
                + "AND params NOT IN ('[]','null')", Integer.class, projectId));
        int fromChain = nz(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_targets WHERE project_id=? "
                + "AND params LIKE '%\"source\":\"chain%'", Integer.class, projectId));
        int stale = staleEnvelope(cards);
        String fact = "字典 " + n + " 个业务代码（service " + kindCount(projectId, "service")
                + " / bex " + kindCount(projectId, "bex") + "）" + frameworkBreakdown(projectId) + " · " + withParams
                + " 个带入参 · " + fromChain + " 个入参来自调用链";
        if (stale > 0) {
            return new Item("dispatch", "分发字典", "warn", fact + "；但 " + stale
                    + " 张入口卡上的信封模板还是旧版学习结果（报文头里功能号字段没绑当前业务代码、op_code 没给值）",
                    "在测试台分发面板点【↻ 重建服务字典】刷新入口卡（不必重学后端），或在【项目与仓库】重学该仓", "lab");
        }
        if (withParams == 0) {
            return new Item("dispatch", "分发字典", "warn", fact,
                    "字典里没有任何入参声明：检查 metaData 目录是否在学习范围内、前端仓库是否也学习过（调用链是第三证据源）", "lab");
        }
        return new Item("dispatch", "分发字典", "ok", fact, "", "lab");
    }

    /**
     * 字典按框架的分摊（第 39 轮）：一个项目可以同时挂两种分发风格，只报总数就看不出
     * "转发中心那 174 个到底学没学到"。只有一种框架时不占这句（默认口径不加噪音）。
     */
    private String frameworkBreakdown(long projectId) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT CASE WHEN framework='' THEN '未记录框架' ELSE framework END fw, COUNT(*) n"
                        + " FROM dispatch_targets WHERE project_id=? GROUP BY framework ORDER BY framework",
                projectId)) {
            parts.add(r.get("fw") + " " + ((Number) r.get("n")).intValue() + " 个");
        }
        return parts.size() > 1 ? "（按框架：" + String.join(" · ", parts) + "）" : "";
    }

    /**
     * 深度体检里唯一可能长时间阻塞的一步：JDBC 连不通时 OS 层 TCP 超时可长达几十秒，
     * 而这是常驻卡，不能把项目页拖住。故限时 12 秒，超时按"探测超时"给结论而不是挂住请求。
     */
    private Map<String, Object> checkDbWithTimeout(long envId) {
        Future<Map<String, Object>> f = PROBE_POOL.submit(() -> dbExecutor.check(envId));
        try {
            return f.get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return Map.of("ok", false, "error", "连接探测超过 12 秒未返回（数据库大概率不可达），已中止本次探测");
        }
    }

    private Item dbItem(Map<String, Object> env, long projectId, boolean deep) {
        int meta = nz(jdbc.queryForObject("SELECT COUNT(*) FROM table_meta WHERE project_id=?",
                Integer.class, projectId));
        if (env == null || str(env.get("db_url")).isBlank()) {
            return new Item("db", "数据库环境", "warn",
                    "环境未配置数据库连接，SQL / 存储过程直测与 L4 数据库断言不可用（接口测试不受影响）",
                    "【系统设置】→ 环境 → 填 db_url / 账号，再点该环境的【体检】确认可达", "settings");
        }
        if (!deep) {
            return dbMetaItem(meta, "（未做连通性探测，勾【深度体检】可真实连一次）");
        }
        Map<String, Object> c = checkDbWithTimeout(((Number) env.get("id")).longValue());
        if (!Boolean.TRUE.equals(c.get("ok"))) {
            return new Item("db", "数据库环境", "fail", "数据库连不上：" + c.get("error"),
                    "核对 db_url / 端口 / 服务名 / 账号密码，确认网络与白名单；驱动缺失时把 jdbc 驱动放进 preflight/lib/drivers 后重启", "settings");
        }
        Item base = dbMetaItem(meta, "");
        return new Item(base.key(), base.label(), base.level(),
                "连通正常：" + c.get("product") + " · " + c.get("note") + "；" + base.fact().replace("（未做连通性探测，勾【深度体检】可真实连一次）", ""),
                base.fix(), base.link());
    }

    private Item dbMetaItem(int meta, String suffix) {
        if (meta == 0) {
            return new Item("db", "数据库环境", "warn",
                    "数据库已配置但还没做元数据反哺（0 张表结构），表名校验与列名提示会缺依据" + suffix,
                    "【系统设置】→ 环境 → 该环境点【体检】连通后，到【SQL 直测/知识库】用【拉取表结构】反哺", "settings");
        }
        return new Item("db", "数据库环境", "ok", "已反哺 " + meta + " 张表结构" + suffix, "", "settings");
    }

    /** test_cases 上没有 project_id，只能按目标卡反查归属，否则多项目会互相污染统计 */
    private Item caseItem(long projectId, Cards cards) {
        int ifaceCases = nz(jdbc.queryForObject("SELECT COUNT(DISTINCT target_id) FROM test_cases "
                + "WHERE target_type='interface' AND target_id IN (SELECT id FROM interfaces WHERE project_id=?)",
                Integer.class, projectId));
        int total = nz(jdbc.queryForObject("SELECT COUNT(*) FROM test_cases WHERE "
                + "(target_type='interface' AND target_id IN (SELECT id FROM interfaces WHERE project_id=?)) "
                + "OR (target_type='db_object' AND target_id IN (SELECT id FROM db_objects WHERE project_id=?))",
                Integer.class, projectId, projectId));
        int suspect = nz(jdbc.queryForObject("SELECT COUNT(*) FROM test_cases WHERE status='suspect' AND ("
                + "(target_type='interface' AND target_id IN (SELECT id FROM interfaces WHERE project_id=?)) "
                + "OR (target_type='db_object' AND target_id IN (SELECT id FROM db_objects WHERE project_id=?)))",
                Integer.class, projectId, projectId));
        if (total == 0) {
            return new Item("case", "用例沉淀", "warn", "一条用例都没有：能现场发请求，但没有可回归的基线",
                    "测试台选中接口卡 →【存为用例】（或【一键生成用例】批量铺），改动流里才能自动判定影响", "lab");
        }
        String fact = ifaceCases + " 张接口卡有接口用例（共 " + total + " 条）";
        if (cards.plainCards > 0 && ifaceCases == 0) {
            fact += "，但普通接口一张都没覆盖";
            return new Item("case", "用例沉淀", "warn", fact + " · 待复核 " + suspect,
                    "优先给改动流涉及到的接口建用例", "lab");
        }
        if (suspect > 0) {
            return new Item("case", "用例沉淀", "warn", fact + " · " + suspect + " 条因 schema 变更处于待复核",
                    "用例列表里逐条点【修复】，人工填过的值会按字段保留", "lab");
        }
        return new Item("case", "用例沉淀", "ok", fact, "", "lab");
    }

    // ---------- 支撑 ----------

    /** 接口卡与入参骨架的一次性统计（分发入口卡的参数在字典里，不参与逐卡骨架统计） */
    private static class Cards {
        int total, draft, plainCards, withSkeleton, entryCards, needLogin, leaves, realLeaves, fullyPlaceholder;
        final List<Map<String, Object>> dispatchSpecs = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Cards readCards(long projectId) {
        Cards c = new Cards();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, path, params_schema, auth_type, status FROM interfaces WHERE project_id=?", projectId)) {
            c.total++;
            if ("draft".equals(row.get("status"))) c.draft++;
            if ("login_flow".equals(row.get("auth_type"))) c.needLogin++;
            Map<String, Object> schema = readJson(row.get("params_schema"));
            Object dispatch = schema.get("dispatch");
            if (dispatch instanceof Map<?, ?> d) {
                c.entryCards++;
                c.dispatchSpecs.add((Map<String, Object>) d);
                continue;
            }
            c.plainCards++;
            Map<String, Object> props = asMap(schema.get("properties"));
            if (!props.isEmpty()) c.withSkeleton++;
            Optional<String> functionNo = DefaultValueRules.functionNo(str(row.get("path")));
            int[] leaf = countLeaves(props, functionNo);
            c.leaves += leaf[0];
            c.realLeaves += leaf[1];
            if (leaf[0] > 0 && leaf[1] == 0) c.fullyPlaceholder++;
        }
        return c;
    }

    /**
     * 逐卡数入参叶子字段：[0]=总数，[1]=已有可直接发的值的数量。
     * 取值口径与测试台预填完全一致（BodyShape + DefaultValueRules），否则体检说能发、页面却发不出去。
     */
    private int[] countLeaves(Map<String, Object> props, Optional<String> functionNo) {
        int[] n = new int[2];
        Map<String, Object> body = BodyShape.flatten(props, BodyShape.sampler(functionNo), false);
        walk(body, n);
        for (Map.Entry<String, Object> en : props.entrySet()) {
            Map<String, Object> ps = asMap(en.getValue());
            if ("body".equals(String.valueOf(ps.getOrDefault("in", "query")))) continue;
            n[0]++;
            if (!placeholder(BodyShape.sampleValue(en.getKey(), ps, functionNo))) n[1]++;
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private void walk(Object node, int[] n) {
        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> m = (Map<String, Object>) raw;
            if (m.isEmpty()) {
                n[0]++;            // 自由结构学不到字段，等价于一个待补填的空位
                return;
            }
            m.values().forEach(v -> walk(v, n));
            return;
        }
        if (node instanceof List<?> l) {
            if (l.isEmpty()) {
                n[0]++;
                return;
            }
            l.forEach(v -> walk(v, n));
            return;
        }
        n[0]++;
        if (!placeholder(node)) n[1]++;
    }

    /** 与前端 sampleAt 同一口径：test/TEST/当天占位日期、空值、空结构都算"没填上" */
    private static boolean placeholder(Object v) {
        if (v == null) return true;
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        if (v instanceof List<?> l) return l.isEmpty();
        String s = String.valueOf(v);
        return s.isEmpty() || s.matches("(?i)^(test|2026-01-01( 00:00:00)?)$");
    }

    private Map<String, Object> findEnv(long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM environments WHERE project_id=? ORDER BY id", projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 入口卡信封模板待刷新的卡数：报文头里功能号类字段应写成 $code（测试台换成所选业务代码）、
     * op_code 类字段应有值。旧版学习结果留的是空串或字面量，构建出来的信封会被服务端判无效参数。
     *
     * <p>只判<b>手抄模板</b>那种框架（金证 KJDP 的 {@code REQ_MSG_HDR}）。第四十二轮起，报文头清单也可以
     * 是从那个仓自己的类学出来的（{@code hdrFields} 非空）——那份清单里 {@code op_code}／{@code msg_id}
     * 留空是"按业务在请求体里补"的盘上事实，不是旧版残留；拿 KJDP 那套规矩判它，一百多张正常的卡
     * 会被报成"信封待刷新"（这条判据本身也没读过任何转发中心的源码）。
     */
    private int staleEnvelope(Cards cards) {
        int n = 0;
        for (Map<String, Object> spec : cards.dispatchSpecs) {
            if (spec.get("hdrFields") instanceof List<?> learned && !learned.isEmpty()) continue;
            Map<String, Object> hdr = asMap(spec.get("hdrTemplate"));
            boolean stale = hdr.entrySet().stream().anyMatch(en -> {
                // 这里只问"这条键归哪类规则"，不问"本次功能号是多少"——所以码一律给 empty，
                // 常量类要的值仍由规则表给出，功能号类要的则是模板里那个 $code 占位。
                DefaultValueRules.Rule r = DefaultValueRules.rule(en.getKey(), Optional.empty());
                return switch (r.kind()) {
                    case CONST -> !r.value().orElse("").equals(str(en.getValue()));
                    case CODE -> !DefaultValueRules.CODE_TEMPLATE.equals(str(en.getValue()));
                    case NOT_MATCHED -> false;
                };
            });
            if (stale) n++;
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Object json) {
        if (json == null) return Map.of();
        try {
            return mapper.readValue(String.valueOf(json), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private int kindCount(long projectId, String kind) {
        return nz(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_targets WHERE project_id=? AND kind=?",
                Integer.class, projectId, kind));
    }

    /** 只做可达性：任何 HTTP 响应都算通，返回状态码；连不上返回 null */
    private String probeUrl(String baseUrl) {
        try {
            HttpClient client = com.preflight.common.Http1.builder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            return String.valueOf(client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode());
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Item> items(Map<String, Object> report) {
        return (List<Item>) report.get("items");
    }

    private static int count(List<Item> items, String level) {
        return (int) items.stream().filter(i -> level.equals(i.level())).count();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
