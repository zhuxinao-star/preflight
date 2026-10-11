package com.preflight.yapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.CredentialUsageService;
import com.preflight.common.CryptoService;
import com.preflight.learn.DispatchDictionaryService;
import com.preflight.learn.DispatchEnvelopeService;
import com.preflight.learn.OutParamService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YApi 文档同步（第卅四轮）。一条链路三段，各自都能单独解释：
 *
 * <p>① 配置：实例地址 + 凭据保险库里的 token + 目标 YApi 项目/目录（目录在界面里现拉现选，
 * 选定后按项目记在 settings，不新增表）。
 * <p>② 草稿：由系统已学到的事实拼装——固定入参按 YApi 基准（service/func/func_url/pageoffset/pagecount），
 * 业务入参来自分发字典，出参来自 viewConf 声明；缺哪块就照实说缺哪块，不编字段。
 * <p>③ 推送：<b>只有逐条勾选后的项才会打到 YApi</b>；已存在的走 /interface/up（主键字段名是 id），
 * 不存在才走 /interface/add。字段级差异在这里算出来，让用户看得见"这次会改到什么"。
 */
@Service
public class YapiService {

    /** 全站默认实例地址（每个项目还能单独覆盖） */
    private static final String DEFAULT_BASE = "http://172.16.4.193:3000/api";
    /** 现拉项目时最多问几个分组（一个分组一次只读 GET，不设上限就等于给内网打一趟扫描） */
    private static final int GROUP_CAP = 50;
    /** 每个分组最多拉多少个项目（YApi 侧 limit，超出的要靠界面搜索） */
    private static final int PROJECT_CAP = 100;
    /**
     * 老基准的固定入参（{@code envelopeMode=base5} 时才用）。默认不再无条件套它：
     * 普通 bex 直调只带 bex_codes、只带 service 的原子调用根本没有 func/func_url，
     * 一律塞五项等于给接口编造入参。判定见 {@link com.preflight.learn.DispatchEnvelopeService}。
     */
    private static final List<String[]> FIXED_INPUTS = List.of(
            new String[]{"service", "string", "转发服务名（按项目固定，见上方配置）"},
            new String[]{"func", "string", "功能号 / 业务代码"},
            new String[]{"func_url", "string", "完整请求路径"},
            new String[]{"pageoffset", "integer", "页码（从 1 开始）"},
            new String[]{"pagecount", "integer", "每页条数"});

    private final JdbcTemplate jdbc;
    private final CryptoService crypto;
    private final YapiClient client;
    private final OutParamService outParams;
    private final DispatchEnvelopeService envelope;
    /** 报文头撤出那句要点名几个字段名：再多就只点名前几个并报总数，界面上不放大段清单 */
    private static final int HDR_NAME_CAP = 8;

    /** 报文头公共参数的唯一出口就是接口卡上那份章，草稿读卡不重算（见 {@link DispatchDictionaryService#stampedHdrFields}） */
    private final DispatchDictionaryService dictionary;
    private final YapiDocOverrideService docs;
    private final com.preflight.common.AuditService audit;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 账号密码登录换来的会话：credId → token，只在内存（重启即失），用量封顶后按 LRU 淘汰 */
    private final Map<Long, Cached> loginCache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Cached> e) {
            return size() > 32;
        }
    };
    /** 地址体检通过的实例地址：base → 结果，同样只在内存、同样 LRU 封顶 */
    private final Map<String, CachedProbe> baseProbes = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedProbe> e) {
            return size() > 32;
        }
    };

    public YapiService(JdbcTemplate jdbc, CryptoService crypto, YapiClient client, OutParamService outParams,
                       DispatchEnvelopeService envelope, DispatchDictionaryService dictionary,
                       YapiDocOverrideService docs, com.preflight.common.AuditService audit) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.client = client;
        this.outParams = outParams;
        this.envelope = envelope;
        this.dictionary = dictionary;
        this.docs = docs;
        this.audit = audit;
    }

    // ---------- ① 配置 ----------

    /** 读某项目的 YApi 目标；没配过时给出可直接用的默认值（base 用全站值，其余留空） */
    public Map<String, Object> config(long projectId) {
        Map<String, Object> cfg = readSetting("yapi.target." + projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        // 这一格留空时会依次退回「全局默认 → 内置默认」。退回本身是有意的（换环境少填一遍），
        // 但**必须说清当前用的是哪一层**：否则地址体检报"这不是 API 前缀"，用户却不知道这个地址根本不是他填的。
        String own = str(cfg.get("baseUrl"));
        String global = setting("yapi.base_url", "");
        out.put("baseUrl", effectiveBase(own, global));
        out.put("baseUrlFrom", baseLayer(own, global));
        out.put("credId", cfg.get("credId") == null ? "" : String.valueOf(cfg.get("credId")));
        out.put("yapiProjectId", cfg.get("yapiProjectId") == null ? "" : String.valueOf(cfg.get("yapiProjectId")));
        out.put("yapiProjectName", str(cfg.get("yapiProjectName")));
        out.put("catId", cfg.get("catId") == null ? "" : String.valueOf(cfg.get("catId")));
        out.put("catName", str(cfg.get("catName")));
        out.put("serviceValue", str(cfg.get("serviceValue")));
        out.put("funcUrlPrefix", str(cfg.get("funcUrlPrefix")));
        // 信封口径：auto=按前端调用点认（默认）；base5=老基准，每个接口固定带那五项
        String mode = str(cfg.get("envelopeMode"));
        out.put("envelopeMode", mode.isBlank() ? "auto" : mode);
        // 「这一项在库里根本还没有」是一个独立事实：不报出来的话，界面只能回「还没选 YApi 凭据」，
        // 而那听起来像用户点错了下拉（第四十二轮的现拉项目就是这个现象）
        out.put("projectId", projectId);
        out.put("targetSaved", hasTargetRow(projectId));
        List<Map<String, Object>> creds = new ArrayList<>();
        // 只列能当 YApi 凭据用的那几种 kind（单点在 CredentialUsageService），不列 kind=password：
        // 那是 SVN/被测系统登录账号那一类，混进来就又回到第卅二轮用户抱怨过的「下拉里全是跟 YApi 无关的账号」
        String ph = String.join(",", java.util.Collections.nCopies(CredentialUsageService.YAPI_KINDS.size(), "?"));
        for (Map<String, Object> c : jdbc.queryForList(
                "SELECT id, name, kind, created_at FROM credentials WHERE kind IN (" + ph + ") ORDER BY id",
                CredentialUsageService.YAPI_KINDS.toArray())) {
            Map<String, Object> one = new LinkedHashMap<>(c);
            // 保险库那张表一张表多种用途，这里按 kind 认，不按名字猜
            one.put("purpose", "yapi");
            one.put("via", CredentialUsageService.yapiVia(str(c.get("kind"))));
            creds.add(one);
        }
        out.put("credentials", creds);
        out.put("ok", true);
        return out;
    }

    /**
     * 把界面上「当前这一格」的值并进入库配置（第四十一轮 N60 的真缺陷）：
     * 【现拉项目】【现拉目录】以前只读库裡那份，用户在格子里改了地址、选了凭据但还没点保存时，
     * 按钮却拿着旧值外呼，还说成「还没选 YApi 凭据」——界面承诺的是这一格，后端就得认这一格。
     * 与【🔑 检查这条凭据】同一个口径：空值/没传＝沿用库裡那份；覆盖只活在这一次请求里，不落库。
     */
    private Map<String, Object> withFormOverrides(Map<String, Object> cfg, String baseUrlOverride, Long credIdOverride) {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            cfg.put("baseUrl", baseUrlOverride.trim());
            cfg.put("baseUrlFrom", "这一格填的");
        }
        if (credIdOverride != null && credIdOverride > 0) cfg.put("credId", String.valueOf(credIdOverride));
        return cfg;
    }

    /** 存配置：只覆盖传上来的键，缺的保持原值（免得改目录把 token 冲掉） */
    public Map<String, Object> saveConfig(long projectId, Map<String, Object> body) {
        Map<String, Object> cur = readSetting("yapi.target." + projectId);
        for (String k : List.of("baseUrl", "credId", "yapiProjectId", "yapiProjectName", "catId", "catName",
                "serviceValue", "funcUrlPrefix", "envelopeMode")) {
            if (body.containsKey(k)) cur.put(k, str(body.get(k)));
        }
        // 保存前体检这一次要生效的地址（N59）：填成页面地址时，后面每个按钮都会失败，
        // 而失败会被说成"凭据不对"（第卅九轮就是这么卡住的）——所以拦在写库之前，而不是等人去撞。
        // 但<b>只有"确知它回的是页面/非 JSON"才拦死</b>：连不上多半只是此刻没连内网，认不出形态也还有救，
        // 这两种一律照存、只回一条 warn——不许把"提醒一句"做成"保存不了"，手动路径必须一直走得通。
        String own = str(cur.get("baseUrl"));
        String global = setting("yapi.base_url", "");
        String base = effectiveBase(own, global);
        YapiClient.Probe pb = client.probeBase(base);      // 不复用缓存：用户点保存就是要看这一格现在的结果
        String from = baseLayer(own, global);
        boolean changed = body.containsKey("baseUrl");
        if (!pb.apiRoot() && changed && "notjson".equals(pb.kind())) {
            Map<String, Object> rej = new LinkedHashMap<>();
            rej.put("ok", false);
            rej.put("problem", "address");
            rej.putAll(probeView(pb));
            rej.put("error", probeFailText(from, pb, "这次没落库：改对地址再点一次【💾 保存同步目标】就行，其它格子的内容不会丢。"));
            return rej;
        }
        try {
            jdbc.update("INSERT INTO settings(key, value) VALUES (?, ?) "
                            + "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    "yapi.target." + projectId, mapper.writeValueAsString(cur));
        } catch (Exception e) {
            return Map.of("ok", false, "error", "配置没存下：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>(config(projectId));
        out.putAll(probeView(pb));
        if (!pb.apiRoot()) out.put("baseWarn", probeFailText(from, pb, "配置已经存下了，这条只是提醒。"));
        return out;
    }

    /** 这一格留空时依次退回「全局默认 → 内置默认」；顺序只有这一处，config/saveConfig 都走它 */
    private static String effectiveBase(String own, String global) {
        return !own.isBlank() ? own : (!global.isBlank() ? global : DEFAULT_BASE);
    }

    /** 当前生效的地址是哪一层给的——报错时必须点名，否则用户以为是自己填的那格坏了 */
    private static String baseLayer(String own, String global) {
        return !own.isBlank() ? "这一格填的" : (!global.isBlank() ? "全局默认 yapi.base_url" : "系统内置默认");
    }

    /** 地址体检的三行事实（试了哪个完整 URL、通不通、原话）——四条出口共用这一处，不许各自再拼一遍 */
    private static Map<String, Object> probeView(YapiClient.Probe pb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseOk", pb.apiRoot());
        m.put("baseKind", pb.kind());
        m.put("baseChecked", pb.requestUrl());
        m.put("baseNote", pb.note());
        // 只差一段 /api 时把试出来的那个地址带出去（只读试的，不自动落库）：
        // 界面只说"不通"，用户还得自己想该填成什么样——而"想想"正是这次卡住的地方。
        if (pb.suggest() != null) {
            m.put("suggestBase", pb.suggest().baseUrl());
            m.put("suggestChecked", pb.suggest().requestUrl());
            m.put("suggestNote", pb.suggest().note());
        }
        return m;
    }

    /**
     * 地址体检没过时怎么说（第四十一轮收成四档）：只有 {@code notjson} 才敢断言"这是页面地址"，
     * 连不上、认不出形态各有各的说法——把三种原因说成一种，就是把人往错方向带。
     * {@code tail} 是各条出口自己的下一步指引（凭据有没有发出去 / 配置有没有落库），只有这里能不同。
     */
    private static String probeFailText(String from, YapiClient.Probe pb, String tail) {
        String head = switch (pb.kind()) {
            case "notjson" -> "实例地址体检没过：这个地址回的不是 YApi 接口的 JSON，「确知」它不是 API 前缀。"
                    + "要填到 /api 为止（例 http://内网地址:3000/api）；"
                    + "浏览器地址栏里的 http://地址/project/26/interface/api 是 YApi 的页面地址，不能当 API 前缀用。";
            case "transport" -> "实例地址体检没过：这个地址「此刻连不上」（不是判定它填错了）。"
                    + "核对端口、VPN/内网是否可达；地址本身要填到 /api 为止。";
            case "shape" -> "实例地址体检没过：这个地址有响应，但回的内容认不出是 YApi 接口（可能是别的服务占了这个端口）。"
                    + "核对地址要填到 /api 为止。";
            case "blank" -> "实例地址体检没过：这一格是空的，没有地址可试——"
                    + "填成 API 前缀（例 http://内网地址:3000/api）再试。";
            default -> "实例地址体检没过（" + pb.note() + "）。";
        };
        return head + "当前用的地址来自「" + from + "」，试过的完整 URL 与返回原话见上一行。"
                + (pb.suggest() == null ? "" : "本机另外只读试了一下：<b>" + pb.suggest().baseUrl() + "</b> 回的就是 YApi 接口的 JSON"
                        + "（只差这一段前缀），点【↻ 用这个地址重测】就能换上——换的只是界面上这一格，不点【💾 保存同步目标】不会落库。")
                + tail;
    }

    /**
     * 取一条凭据的会话（token + uid）。两种凭据都支持，且都照实说自己走的是哪条路：
     * <ul>
     *   <li>{@code kind=yapi_login}（账号密码）：内容格式 {@code 邮箱:密码}，由系统去 {@code /user/login} 换 token，
     *       换到的 token 只在内存里按凭据 id 缓存到快过期，过期或 YApi 说没登录就自动重登一次。</li>
     *   <li>其它（手填 token）：内容就是 {@code _yapi_token} 那一整串 JWT，过期只能用户自己换一条——
     *       这条能力刻意保留，不能因为有了自动登录就收走手填。</li>
     * </ul>
     * token 与密码一律不进返回体、不进日志。
     */
    private Map<String, Object> auth(long credId, String baseUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", "");
        out.put("uid", "");
        out.put("via", "token");
        if (credId <= 0) {
            out.put("error", "还没选 YApi 凭据：到「系统设置 → 凭据保险库」新建一条——"
                    + "想省事就选「YApi 账号密码」（内容填 邮箱:密码，系统自己登录取 token 并自动续），"
                    + "想手动就选「YApi token」（直接粘 _yapi_token 那一整串 JWT）");
            return out;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, kind, secret_enc FROM credentials WHERE id=?", credId);
        if (rows.isEmpty()) {
            out.put("error", "凭据 #" + credId + " 不在了（保险库里没有这条），重新选一条");
            return out;
        }
        String name = str(rows.get(0).get("name"));
        String kind = str(rows.get(0).get("kind"));
        // 只肯用 YApi 那几种 kind：其它凭据（SVN/被测系统账号）的内容是 user:password，
        // 当成 token 塞进 Cookie 等于把这个账号的密码发给 YApi，必须在发请求前拦下而不是"试试看"
        if (!CredentialUsageService.isYapiKind(kind)) {
            out.put("error", "凭据「" + name + "」的类型是 " + kind + "，不是 YApi 文档凭据："
                    + "到保险库选「YApi 账号密码」或「YApi token」类型的那条（类型不对时系统不会把内容发出去）");
            return out;
        }
        String secret;
        try {
            secret = crypto.decrypt(str(rows.get(0).get("secret_enc")));
        } catch (Exception e) {
            out.put("error", "这条凭据解不开密文，换一条重新录入");
            return out;
        }
        if ("login".equals(CredentialUsageService.yapiVia(kind))) {
            out.put("via", "login");
            return loginAuth(credId, name, baseUrl, secret);
        }
        Map<String, Object> info = client.inspectToken(secret);
        out.put("token", secret);
        out.put("uid", str(info.get("uid")));
        long exp = info.get("exp") instanceof Number n ? n.longValue() : 0;
        out.put("exp", exp);
        if (!str(info.get("error")).isBlank()) {
            out.put("error", str(info.get("error")));
            return out;
        }
        if (exp > 0 && exp * 1000L < System.currentTimeMillis()) {
            out.put("error", "这条 token 已过期（YApi 的 JWT 大约 7 天有效），请到保险库换一条再同步"
                    + "（不想每次手动换，可以改建成「YApi 账号密码」那条凭据）");
            return out;
        }
        return out;
    }

    /** 账号密码那条路：缓存命中就直接用，否则真登录一次。失败只转述 YApi 原话，绝不回显密码。 */
    private Map<String, Object> loginAuth(long credId, String name, String baseUrl, String secret) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("via", "login");
        out.put("token", "");
        out.put("uid", "");
        int i = secret.indexOf(':');
        if (i <= 0) {
            out.put("error", "「YApi 账号密码」这条凭据的内容里没有冒号，认不出邮箱（内容格式应为 邮箱:密码）");
            return out;
        }
        String email = secret.substring(0, i);
        String password = secret.substring(i + 1);
        if (email.isBlank() || password.isBlank()) {
            out.put("error", "「YApi 账号密码」这条凭据的邮箱或密码是空的，到保险库补全");
            return out;
        }
        Cached hit = cachedLogin(credId);
        if (hit != null) {
            out.put("token", hit.token);
            out.put("uid", hit.uid);
            out.put("exp", hit.exp);
            out.put("cached", true);
            return out;
        }
        YapiClient.Login lg = client.login(baseUrl, email, password);
        if (!lg.ok()) {
            out.put("error", "用账号密码登录 YApi 失败（凭据「" + name + "」）：" + loginFailText(lg));
            out.put("problem", lg.problem());
            return out;
        }
        putLogin(credId, new Cached(lg.token(), lg.uid(), lg.exp()));
        out.put("token", lg.token());
        out.put("uid", lg.uid());
        out.put("exp", lg.exp());
        out.put("cached", false);
        return out;
    }

    /**
     * 登录失败的四类分诊文案（第卅九轮：用户把浏览器地址栏里的页面地址填成了实例地址，
     * 请求 404，却被一句"核对邮箱/密码"引去反复改密码）。
     * <b>地址没对的时候绝不提邮箱/密码</b>——那条凭据根本没送到校验。
     * 文案是给界面转述用的<b>纯文本</b>（界面一律 esc 后显示），所以这里不许带 HTML 标记。
     */
    private static String loginFailText(YapiClient.Login lg) {
        return switch (lg.problem()) {
            case "address" -> "「YApi 实例地址」不是这台 YApi 的 API 前缀，请求没打到登录接口："
                    + lg.transportError() + "。这里要填到 /api 为止（例 http://内网地址:3000/api）；"
                    + "浏览器地址栏里的 http://地址/project/26/interface/api 是 YApi 的页面地址，不能当 API 前缀用。"
                    + "改完再点一次——这次失败「不能」说明邮箱或密码有问题。";
            case "transport" -> "连不上这台 YApi：" + lg.transportError() + "。核对地址、端口与网络（VPN/内网可达）。";
            case "rejected" -> lg.transportError() + "。这一次 YApi 接口是通的，是它亲口拒了登录——核对保险库里这条凭据的邮箱/密码"
                    + "（内容格式应为 邮箱:密码）。";
            default -> lg.transportError();
        };
    }

    /**
     * 界面【检查这条凭据能不能用】：两类凭据都会先做一次<b>不带凭据的地址体检</b>（一个只读 GET），
     * 地址不通就直接给"地址问题"的结论，不把账号密码发往一个不是 API 的地址；
     * 地址通了之后账号密码类真登录一次并回报 uid 与过期时间，手填 token 类不外呼换 token、
     * 只把 JWT 里已有的事实读出来——不假装有"远端验证"。
     * 界面上选了还没保存的凭据时，靠 credId/baseUrl 覆盖参数检查"当前这一格"，不会偷偷改用配置。
     */
    public Map<String, Object> checkAuth(long projectId, Long credIdOverride, String baseUrlOverride) {
        Map<String, Object> cfg = config(projectId);
        long credId = credIdOverride != null && credIdOverride > 0
                ? credIdOverride : Long.parseLong(orZero(str(cfg.get("credId"))));
        String base = baseUrlOverride == null || baseUrlOverride.isBlank()
                ? str(cfg.get("baseUrl")) : baseUrlOverride;
        String kind = "";
        String name = "";
        List<Map<String, Object>> rows = credId > 0
                ? jdbc.queryForList("SELECT name, kind FROM credentials WHERE id=?", credId) : List.of();
        if (!rows.isEmpty()) {
            kind = str(rows.get(0).get("kind"));
            name = str(rows.get(0).get("name"));
        }
        boolean isLogin = "login".equals(CredentialUsageService.yapiVia(kind));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credName", name);
        out.put("credKind", credId <= 0 ? "(这一格还没选凭据)"
                : (kind.isBlank() ? "(这条凭据不在了)" : kind));
        out.put("via", CredentialUsageService.isYapiKind(kind) ? CredentialUsageService.yapiVia(kind) : "other");

        // 先体检地址，再看凭据（第四十轮）：地址不对时请求根本到不了 YApi 的接口，
        // 这时候报"邮箱/密码不对"是假线索；而且**不把密码发往一个不是 API 的地址**。
        YapiClient.Probe pb = client.probeBase(base);   // 手点这一次就是要看此刻的结果，不走下面的短缓存
        out.putAll(probeView(pb));
        // 这一格留空时用的是默认值，必须在结论里点名——否则用户以为是自己填的地址坏了
        String from = base.equals(baseUrlOverride) ? "这一格填的" : str(cfg.get("baseUrlFrom"));
        if (!pb.apiRoot()) {
            out.put("ok", false);
            out.put("problem", "address");
            out.put("error", probeFailText(from, pb, "这条凭据本次没有发出去，所以还判不出邮箱/密码对不对。"));
            return out;
        }
        // 地址这一条结论已经给出来了。没选凭据时不能就这么早退——用户问的正是"地址到底怎么填"，
        // 而保险库里还没建 YApi 凭据是另一件事，分开说清（第四十四轮：早退会让人连地址都验不了）。
        if (credId <= 0) {
            out.put("ok", false);
            out.put("problem", "nocred");
            out.put("error", "地址这一条已经测过了（看上面那行）。凭据这条还没测：这一格没选凭据——"
                    + "到「系统设置 → 凭据保险库」新建一条 YApi 凭据（账号密码你自己填，系统不收对话里的密码），"
                    + "再回到这里选它。");
            return out;
        }
        if (isLogin) dropLogin(credId);        // 手点这一次就是要看真登录行不行，不许拿缓存糊过去
        Map<String, Object> a = auth(credId, base);
        out.put("uid", str(a.get("uid")));
        if (!str(a.get("problem")).isBlank()) out.put("problem", str(a.get("problem")));
        long exp = a.get("exp") instanceof Number n ? n.longValue() : 0;
        out.put("exp", exp);   // 只给 epoch 秒，格式化归界面 fmtTime 单一出口（第廿九轮定的口径）
        if (a.containsKey("error")) {
            out.put("ok", false);
            out.put("error", a.get("error"));
            return out;
        }
        out.put("ok", true);
        out.put("note", isLogin
                ? "刚用这条凭据的账号密码登录成功，取到的 token 会在内存里用到快过期，之后自动重登"
                : "这条是手填 token：系统不会自动换，过期后要自己到保险库换一条");
        return out;
    }

    /**
     * 一次带鉴权的外呼：账号密码类在会话失效时自动重登一次再试；手填 token 类不重试（重试也没用，只能换凭据）。
     *
     * <p>发凭据之前先过地址体检（N60，与【🔑 检查这条凭据】同一条判据）：地址没对时请求根本到不了 YApi 接口，
     * 这时候的失败说成"凭据不对"是假线索，而且等于把密码往一个不是 API 的地址上发。
     * 所以这里的返回<b>永远是两条结论</b>：{@code baseOk/baseChecked/baseNote} 说地址，{@code ok+error} 说这次动作。
     */
    private Map<String, Object> authed(Map<String, Object> cfg, java.util.function.BiFunction<String, String, YapiClient.Resp> fn) {
        String base = str(cfg.get("baseUrl"));
        Map<String, Object> out = new LinkedHashMap<>();
        YapiClient.Probe pb = probe(base);
        out.putAll(probeView(pb));
        if (!pb.apiRoot()) {
            out.put("ok", false);
            out.put("problem", "address");
            out.put("error", probeFailText(str(cfg.get("baseUrlFrom")), pb,
                    "这条凭据本次没有发出去，所以还判不出邮箱/密码对不对。"));
            return out;
        }
        long credId = Long.parseLong(orZero(str(cfg.get("credId"))));
        Map<String, Object> a = auth(credId, base);
        if (a.containsKey("error")) {
            out.put("ok", false);
            // 一个凭据都没选时，先说清"这一格和库里都没有"这个事实，再说该怎么补——
            // 只回「还没选凭据」会把"配置行不存在"讲成"你操作错了"
            out.put("error", (credId <= 0 ? noTargetNote(cfg) : "") + str(a.get("error")));
            if (!str(a.get("problem")).isBlank()) out.put("problem", str(a.get("problem")));
            return out;
        }
        YapiClient.Resp r = fn.apply(str(a.get("token")), str(a.get("uid")));
        boolean relogged = false;
        if (!r.ok() && "login".equals(a.get("via")) && isAuthLost(r)) {
            dropLogin(credId);
            Map<String, Object> b = auth(credId, base);
            if (b.containsKey("error")) {
                out.put("ok", false);
                out.put("error", "YApi 说会话失效，自动重登也没成功：" + b.get("error"));
                return out;
            }
            r = fn.apply(str(b.get("token")), str(b.get("uid")));
            relogged = true;
        }
        out.put("ok", true);
        out.put("resp", r);
        out.put("via", a.get("via"));
        out.put("relogged", relogged);
        return out;
    }

    /**
     * 同一条流水线里的地址体检只做一次：批量推送时每条都要"查远端现状 + 写入"，
     * 不做这层就会把同一个只读 GET 打上几十遍。<b>只缓存"通"</b>——地址改对了要立刻生效，
     * 而不通的那条路径本来一进来就返回，最多也只打一次。
     */
    private YapiClient.Probe probe(String base) {
        long now = System.currentTimeMillis();
        synchronized (baseProbes) {
            CachedProbe hit = baseProbes.get(base);
            if (hit != null && now - hit.at > 60_000L) baseProbes.remove(base);
            else if (hit != null) return hit.probe;
        }
        YapiClient.Probe pb = client.probeBase(base);
        if (pb.apiRoot()) {
            synchronized (baseProbes) {
                baseProbes.put(base, new CachedProbe(pb, now));
            }
        }
        return pb;
    }

    /** 体检通过的地址（只在内存，重启即失；60 秒内同一条流水线不重复外呼） */
    private record CachedProbe(YapiClient.Probe probe, long at) {}


    private static boolean isAuthLost(YapiClient.Resp r) {
        String msg = r.errmsg() == null ? r.transportError() : r.errmsg();
        return r.errcode() == 40011 || (msg != null && (msg.contains("登录") || msg.contains("会话")));
    }

    private Cached cachedLogin(long credId) {
        synchronized (loginCache) {
            Cached c = loginCache.get(credId);
            if (c == null) return null;
            // 留出 60 秒余量：宁可提前重登一次，也不要拿着快过期的 token 去撞 40011
            if (c.exp > 0 && c.exp * 1000L - System.currentTimeMillis() < 60_000L) {
                loginCache.remove(credId);
                return null;
            }
            return c;
        }
    }

    private void putLogin(long credId, Cached c) {
        synchronized (loginCache) {
            loginCache.put(credId, c);
        }
    }

    private void dropLogin(long credId) {
        synchronized (loginCache) {
            loginCache.remove(credId);
        }
    }

    /** 登录换来的会话（只在内存，重启即失；绝不落库、绝不进日志） */
    private record Cached(String token, String uid, long exp) {}

    /**
     * 现拉 YApi 项目列表（用户在界面里选目标项目，不用手抄 id）；带界面上未保存的这一格值。
     *
     * <p><b>为什么要先问分组</b>：第四十二轮实测，这台实例的 {@code /project/list} 少了 {@code group_id}
     * 直接回 {@code errcode 400「请求参数 data 应当有必需属性 group_id」}——它把"必填参数"的检查放在鉴权之后，
     * 所以这句 400 长得像"我们连上了但拿不到东西"，很容易被说成凭据问题（N60 要防的正是这个）。
     * 于是改成两步：{@code /group/list} → 按组 {@code /project/list?group_id=…} → 合并去重。
     *
     * <p>三条口径：① 分组接口本身不可用时<b>退回原来那一次调用</b>（有些实例不要求 group_id，旧路径不能收走）；
     * ② 某个分组单独失败不毁掉整趟，但必须写明"这不是全项目"并带上 YApi 原话；
     * ③ 地址/凭据级别的失败一律走 {@link #authFail}，两条结论照原样上抛。
     */
    public Map<String, Object> listProjects(long projectId, String baseUrlOverride, Long credIdOverride) {
        Map<String, Object> cfg = withFormOverrides(config(projectId), baseUrlOverride, credIdOverride);
        String base = str(cfg.get("baseUrl"));
        Map<String, Object> g = authed(cfg, (tk, uid) ->
                client.get(base, tk, uid, "/group/list", Map.of("limit", String.valueOf(GROUP_CAP), "page", "1")));
        if (!Boolean.TRUE.equals(g.get("ok"))) return authFail(g);
        YapiClient.Resp gr = (YapiClient.Resp) g.get("resp");
        List<Map<String, Object>> groups = new ArrayList<>();
        if (gr.ok()) {
            for (JsonNode n : nodes(gr.data(), "list")) {
                String gid = n.path("_id").asText(n.path("id").asText(""));
                if (gid.isBlank()) continue;
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", gid);
                one.put("name", n.path("name").asText(""));
                groups.add(one);
            }
        }
        if (groups.isEmpty()) {
            // 没列出组（实例没有该接口、报业务错、或一个组都没有）：照老路再问一次，
            // 并把这一步的结论原样交给用户——不许静默换路后把失败说成"你没选凭据"
            Map<String, Object> old = projectsNoGroup(cfg, base);
            if (gr.ok()) return old;
            Map<String, Object> merged = new LinkedHashMap<>(old);
            if (Boolean.TRUE.equals(old.get("ok"))) {
                merged.put("warn", "分组列表这一步 YApi 回：" + str(gr.errmsg() == null ? gr.transportError() : gr.errmsg())
                        + "；上面这份是「不带 group_id」直接问出来的项目，可能不是全部。");
                return merged;
            }
            // 两条路都没拿到：报后面那次（它才是真正取项目的那一步），但把前面那句原话留着
            merged.put("groupStepError", "YApi：" + (gr.errmsg() == null ? gr.transportError() : gr.errmsg()));
            return merged;
        }
        List<Map<String, Object>> projects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> failed = new ArrayList<>();
        for (Map<String, Object> gp : groups) {
            String gid = str(gp.get("id"));
            String gname = str(gp.get("name")).isBlank() ? "分组 " + gid : str(gp.get("name"));
            Map<String, Object> pg = authed(cfg, (tk, uid) -> client.get(base, tk, uid, "/project/list",
                    Map.of("group_id", gid, "limit", String.valueOf(PROJECT_CAP), "page", "1",
                            "status", "done", "mode", "mix", "with_services", "false")));
            if (!Boolean.TRUE.equals(pg.get("ok"))) return authFail(pg);   // 地址/凭据层面：整趟都没意义了
            YapiClient.Resp pr = (YapiClient.Resp) pg.get("resp");
            if (!pr.ok()) {
                failed.add("「" + gname + "」→ YApi：" + (pr.errmsg() == null ? pr.transportError() : pr.errmsg()));
                continue;
            }
            for (JsonNode n : nodes(pr.data(), "list")) {
                String pid = n.path("_id").asText(n.path("id").asText(""));
                if (pid.isBlank() || !seen.add(pid)) continue;            // 一个项目可能挂在多个组下
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", pid);
                one.put("name", n.path("name").asText(""));
                one.put("desc", n.path("desc").asText(""));
                one.put("group", gname);
                projects.add(one);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(baseView(g));
        out.put("projects", projects);
        out.put("count", projects.size());
        out.put("groups", groups.size());
        if (!failed.isEmpty()) {
            out.put("warn", "取了 " + groups.size() + " 个分组，其中 " + failed.size() + " 个没拿到项目："
                    + String.join("；", failed) + "。上面这份只含成功那几组，不是全部项目。");
        }
        if (groups.size() >= GROUP_CAP) {
            out.put("groupNote", "分组数达到本次上限 " + GROUP_CAP + " 个，列表可能被截断——找不到项目时先在界面里按名字搜。");
        }
        return out;
    }

    /** 老路子：不带 group_id 直接问项目列表（保留：不是所有 YApi 版本都要求这个参数） */
    private Map<String, Object> projectsNoGroup(Map<String, Object> cfg, String base) {
        Map<String, Object> g = authed(cfg, (tk, uid) -> client.get(base, tk, uid,
                "/project/list", Map.of("limit", String.valueOf(PROJECT_CAP), "page", "1",
                        "status", "done", "mode", "mix", "with_services", "false")));
        if (!Boolean.TRUE.equals(g.get("ok"))) return authFail(g);
        YapiClient.Resp r = (YapiClient.Resp) g.get("resp");
        if (!r.ok()) return fail(g, r);
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : nodes(r.data(), "list")) {
            String pid = n.path("_id").asText(n.path("id").asText(""));
            if (pid.isBlank() || !seen.add(pid)) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", pid);
            one.put("name", n.path("name").asText(""));
            one.put("desc", n.path("desc").asText(""));
            one.put("group", "");
            list.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(baseView(g));
        out.put("projects", list);
        out.put("count", list.size());
        out.put("groups", 0);
        return out;
    }


    /** 现拉所选项目下的分类（目录）：先试 project_cat/list，端点不在就退回 interface/list_menu；同样认界面上这一格 */
    public Map<String, Object> listCats(long projectId, String yapiProjectId, String baseUrlOverride, Long credIdOverride) {
        Map<String, Object> cfg = withFormOverrides(config(projectId), baseUrlOverride, credIdOverride);
        String pid = yapiProjectId == null || yapiProjectId.isBlank() ? str(cfg.get("yapiProjectId")) : yapiProjectId;
        String base = str(cfg.get("baseUrl"));
        List<Map<String, Object>> cats = new ArrayList<>();
        Map<String, Object> g = authed(cfg, (tk, uid) ->
                client.get(base, tk, uid, "/project_cat/list", Map.of("project_id", pid, "limit", "200", "page", "1")));
        if (!Boolean.TRUE.equals(g.get("ok"))) return authFail(g);
        YapiClient.Resp r = (YapiClient.Resp) g.get("resp");
        if (r.ok()) {
            for (JsonNode n : nodes(r.data(), null)) {
                cats.add(cat(n.path("_id").asText(n.path("id").asText("")), n.path("name").asText("")));
            }
        }
        if (cats.isEmpty()) {
            // 退回用户已验证可用的那条：列目录接口本身在部分 YApi 版本上没有，就整项目扫一遍
            Map<String, Object> g2 = authed(cfg, (tk, uid) ->
                    client.get(base, tk, uid, "/interface/list_menu", Map.of("project_id", pid)));
            if (!Boolean.TRUE.equals(g2.get("ok"))) return authFail(g2);
            YapiClient.Resp m = (YapiClient.Resp) g2.get("resp");
            if (!m.ok()) {
                // 两次外呼都可能带回业务错误：报带业务码的那条，且要配它自己那次的地址结论
                boolean first = r.errcode() != 0;
                return fail(first ? g : g2, first ? r : m);
            }
            for (JsonNode n : nodes(m.data(), null)) {
                cats.add(cat(n.path("_id").asText(n.path("id").asText("")), n.path("name").asText("")));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(baseView(g));
        out.put("cats", cats);
        out.put("count", cats.size());
        out.put("yapiProjectId", pid);
        return out;
    }

    /** 这个项目的 YApi 同步目标在库里有没有那一行（只看键在不在，不看内容空不空） */
    private boolean hasTargetRow(long projectId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM settings WHERE key=?",
                Integer.class, "yapi.target." + projectId);
        return n != null && n > 0;
    }

    /** 库里那一行不存在时先摆这个事实：否则用户只会看到「还没选凭据」，以为自己点错了下拉 */
    private static String noTargetNote(Map<String, Object> cfg) {
        if (Boolean.TRUE.equals(cfg.get("targetSaved"))) return "";
        return "项目 #" + str(cfg.get("projectId")) + " 的 YApi 同步目标在库里还没有这一条（不是选错了，是从没存过）："
                + "在上面那一格选好地址与凭据后点【💾 保存同步目标】，或者不保存、直接在这一格里选好后现拉也可以。";
    }

    /** authed() 已经算好的那几行地址事实，往上原样带——不许在中间层被压成一句 error（那正是两条结论丢失的地方） */
    private static Map<String, Object> baseView(Map<String, Object> g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseOk", Boolean.TRUE.equals(g.get("baseOk")));
        m.put("baseKind", str(g.get("baseKind")));
        m.put("baseChecked", str(g.get("baseChecked")));
        m.put("baseNote", str(g.get("baseNote")));
        // 只差一段 /api 时的候选地址也要原样带上：五个出口共用这一份，谁都不该自己再拼一遍
        for (String k : List.of("suggestBase", "suggestChecked", "suggestNote")) {
            if (g.get(k) != null) m.put(k, str(g.get(k)));
        }
        return m;
    }

    /** 外呼失败时的返回：地址那条 + 这次动作那条，两条都在；problem 让界面知道该说"凭据还没测" */
    private static Map<String, Object> authFail(Map<String, Object> g) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", str(g.get("error")));
        String problem = str(g.get("problem"));
        if (!problem.isBlank()) out.put("problem", problem);
        out.putAll(baseView(g));
        return out;
    }


    private static Map<String, Object> cat(String id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    // ---------- ② 草稿 + 差异 ----------

    /**
     * 候选清单：这个项目的分发字典条目（service / bex 都要）。
     * 带 keyword 时服务端过滤+分页，不在前端一次拉全量。
     */
    public Map<String, Object> candidates(long projectId, String kind, String q, int page, int size) {
        return candidates(projectId, kind, null, q, page, size);
    }

    /** 同上，多一个仓库一级过滤：一个项目常挂多个仓库（work_app / work_platform / 指令版），按仓库看才数得清 */
    public Map<String, Object> candidates(long projectId, String kind, Long repoId, String q, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT kind, code, name, entry_path, params FROM dispatch_targets WHERE project_id=?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (kind != null && !kind.isBlank() && !"all".equals(kind)) {
            sql.append(" AND kind=?");
            args.add(kind);
        }
        if (repoId != null) {
            sql.append(" AND repo_id=?");
            args.add(repoId);
        }
        if (q != null && !q.isBlank()) {
            // 用户打的 % 和 _ 是关键字不是通配符：转义后必须声明 ESCAPE，否则斜杠本身变成匹配字符
            String esc = com.preflight.common.SqlLikes.ESCAPE_CLAUSE;
            sql.append(" AND (code LIKE ?").append(esc).append(" OR name LIKE ?").append(esc).append(")");
            String like = com.preflight.common.SqlLikes.contains(q.trim());
            args.add(like);
            args.add(like);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + sql + ")" , Integer.class, args.toArray());
        int sz = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 1);
        List<Object> la = new ArrayList<>(args);
        la.add(sz);
        la.add(com.preflight.common.Paging.offset(p, sz));
        // params 只是拿来数入参个数，不整块发给列表页
        List<Map<String, Object>> raw = jdbc.queryForList(
                "SELECT kind, code, name, entry_path, params FROM (" + sql + ") t ORDER BY kind, code LIMIT ? OFFSET ?",
                la.toArray());
        List<Map<String, Object>> rows = new ArrayList<>();
        // 人工维护过的条数：一次查全项目，不在这里逐行发查询（覆盖表只装人改过的东西，本来就小）
        Map<String, Integer> manual = docs.manualCounts(projectId);
        for (Map<String, Object> r0 : raw) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("kind", r0.get("kind"));
            one.put("code", r0.get("code"));
            one.put("name", r0.get("name"));
            one.put("entryPath", r0.get("entry_path"));
            one.put("param_count", readList(r0.get("params")).size());
            one.put("manual", manual.getOrDefault(str(r0.get("kind")) + "|" + str(r0.get("code")), 0));
            rows.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", sz);
        out.put("items", rows);
        // 仓库下拉：一个项目常挂多个仓库，按仓库看才数得清。全列（含 0 个的）——
        // 0 也是事实，但光秃秃一个 0 会被读成"系统在骗我"（原话："为什么这两个仓接口数都是 0，
        // 不应该跟接口卡里面一样吗"）：所以每个仓都自述它为什么是这个数，功能号和接口卡是两套资产。
        List<Object> ra = new ArrayList<>();
        String rsql = "SELECT r.id, r.name, COUNT(t.id) targets, r.local_path,"
                + " GROUP_CONCAT(DISTINCT NULLIF(t.framework,'')) fw FROM repos r"
                + " LEFT JOIN dispatch_targets t ON t.repo_id=r.id AND t.project_id=r.project_id"
                + ("all".equals(kind) || kind == null || kind.isBlank() ? "" : " AND t.kind=?")
                + " WHERE r.project_id=? GROUP BY r.id, r.name, r.local_path ORDER BY r.id";
        if (!"all".equals(kind) && kind != null && !kind.isBlank()) ra.add(kind);
        ra.add(projectId);
        List<Map<String, Object>> repoRows = jdbc.queryForList(rsql, ra.toArray());
        int dictTotal = 0;
        List<String> owners = new ArrayList<>();
        for (Map<String, Object> x : repoRows) {
            int n = x.get("targets") == null ? 0 : ((Number) x.get("targets")).intValue();
            dictTotal += n;
            if (n > 0) owners.add(x.get("name") + " " + n + " 个"
                    + (str(x.get("fw")).isBlank() ? "" : "（" + str(x.get("fw")) + "）"));
        }
        for (Map<String, Object> x : repoRows) {
            int n = x.get("targets") == null ? 0 : ((Number) x.get("targets")).intValue();
            x.put("hint", n > 0
                    ? "这个仓库的声明里学到 " + n + " 个功能号（框架：" + (str(x.get("fw")).isBlank() ? "未记录" : str(x.get("fw"))) + "）"
                    : repoHasDispatch(x.get("local_path"))
                        ? "这个仓库有分发声明但还没学出条目：到【项目与仓库】重学该仓，或在【知识库 → 服务字典】点【↻ 重建服务字典】"
                        : "这个仓库里没有功能号声明——服务字典只认分发声明（KJDP 的 WEB-INF/conf/metaData、"
                            + "统一访问的 interface-customer.properties），前端仓和普通 Spring 仓本来就不进这份字典");
            // 各仓的功能号清单只写在 repo_note 一处：它和 hint 会拼成同一行恒显说明，
            // 两处都列一遍就成了三行重复文案，用户反而找不到重点
            x.remove("local_path");   // 路径不是给下拉看的
        }
        out.put("repos", repoRows);
        out.put("repo_note", "这里的「功能号」是服务字典（一个业务代码一条参数契约），和【知识库 → 接口】里的接口卡不是一回事："
                + "接口卡按 Controller 注解学，一个仓几十到几百张；功能号只从分发声明学。"
                + "本项目共 " + dictTotal + " 个功能号" + (owners.isEmpty() ? "" : "，在：" + String.join("、", owners)));
        if (rows.isEmpty()) {
            Map<String, Object> sel = repoId == null ? null
                    : repoRows.stream().filter(x -> String.valueOf(x.get("id")).equals(String.valueOf(repoId)))
                            .findFirst().orElse(null);
            if (sel != null && (sel.get("targets") == null || ((Number) sel.get("targets")).intValue() == 0)) {
                out.put("note", "仓库「" + str(sel.get("name")) + "」下没有功能号：" + str(sel.get("hint")));
            } else {
                out.put("note", "这个项目的服务字典里没有匹配的代码——分发字典是学习时建的，"
                        + "先到「项目与仓库」学习该仓库；新项目（没有统一分发框架）本来就没有这份字典");
            }
        }
        return out;
    }

    /**
     * 这个仓的工作副本里有没有分发声明。
     *
     * <p>KJDP 那条只看 {@code WEB-INF/conf/metaData} 这一个目录；统一访问那份 properties 是<b>相对模块根</b>的，
     * 整份检出登记的仓（国泰海通：模块在下一层）光看仓库根会判成「没有声明」，把「有声明但还没学」
     * 说成「这个仓本来不进字典」——那是把人往错方向带。所以走 {@link RepoModules} 的存在性判据，
     * 找到第一个就停；只有「字典里 0 条」的仓才会问到这里，不影响正常列表。
     */
    private static boolean repoHasDispatch(Object localPath) {
        String dir = localPath == null ? "" : String.valueOf(localPath);
        if (dir.isBlank()) return false;
        java.nio.file.Path root = java.nio.file.Path.of(dir);
        if (!java.nio.file.Files.isDirectory(root)) return false;
        return java.nio.file.Files.isDirectory(root.resolve("WEB-INF").resolve("conf").resolve("metaData"))
                || com.preflight.parser.RepoModules.hasModuleFile(root,
                        java.nio.file.Path.of("src", "main", "resources", "interface-customer.properties"));
    }

    /** 一个功能号自动算出来的那一份（一个字的人工覆盖都没加）：草稿与"存差异"都以它为基准 */
    private record AutoDoc(Map<String, Object> cfg, Map<String, Object> target, Map<String, Object> env,
                           Map<String, Object> op, boolean base5,
                           List<Map<String, Object>> inputs, List<Map<String, Object>> outputs,
                           String title, String path, String inputNote, String hdrNote) {
    }

    /** 服务字典里没有这个码时返回 null，由调用方说人话 */
    private AutoDoc autoDraft(long projectId, String kind, String code) {
        Map<String, Object> cfg = config(projectId);
        Map<String, Object> target = jdbc.queryForList(
                        "SELECT kind, code, name, entry_path, params FROM dispatch_targets WHERE project_id=? AND kind=? AND code=?",
                        projectId, kind, code).stream().findFirst().orElse(null);
        if (target == null) return null;
        List<Map<String, Object>> bizIn = readList(target.get("params"));
        Map<String, Object> op = outParams.byCode(projectId, kind, code);
        List<Map<String, Object>> outs = cast(op.get("outputs"));
        Map<String, Object> env = envelope.forTarget(projectId, kind, code);
        boolean base5 = "base5".equals(str(cfg.get("envelopeMode")));
        String prefix = str(cfg.get("funcUrlPrefix"));
        // 路径优先用调用点上 func_url 的字面量（那才是真实请求路径）；没有才退回配置前缀 + 代码
        String observed = env.get("funcUrl") == null ? "" : str(env.get("funcUrl"));
        String funcUrl = !observed.isBlank() ? (observed.startsWith("/") ? observed : "/" + observed)
                : prefix.isBlank() ? str(target.get("entry_path")) : prefix + code;
        String title = str(target.get("name")) + code;

        List<Map<String, Object>> inputs = new ArrayList<>();
        // 已经列过的键：一律按小写记。声明、报文头与链上认出的键大小写混着来（metaData 大写、
        // properties 小写），差一个大小写的重复行写进文档只会让 YApi 上出现两个一样的字段。
        Set<String> used = new LinkedHashSet<>();
        if (base5) {
            for (String[] f : FIXED_INPUTS) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", f[0]);
                m.put("type", f[1]);
                m.put("title", f[2]);
                m.put("fixed", true);
                m.put("value", "service".equals(f[0]) ? str(cfg.get("serviceValue"))
                        : "func".equals(f[0]) ? code : "func_url".equals(f[0]) ? funcUrl : "");
                inputs.add(m);
                used.add(low(f[0]));
            }
        } else {
            for (Map<String, Object> f : cast(env.get("envelope"))) {
                String name = str(f.get("field"));
                if (name.isBlank() || !used.add(low(name))) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", name);
                m.put("type", str(f.get("type")));
                m.put("title", str(f.get("title")));
                m.put("fixed", true);
                m.put("value", "service".equals(name) && str(f.get("value")).isBlank()
                        ? str(cfg.get("serviceValue")) : str(f.get("value")));
                m.put("at", str(f.get("at")));
                inputs.add(m);
            }
        }
        for (Map<String, Object> p : bizIn) {
            String name = str(p.get("key"));
            // 字典里如果本来就学到了信封键，以信封那一条为准，不重复列一遍
            if (name.isBlank() || !used.add(low(name))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", name);
            m.put("type", p.get("type") == null ? "string" : str(p.get("type")));
            m.put("title", str(p.get("label")));
            m.put("fixed", false);
            m.put("value", str(p.get("def")));
            inputs.add(m);
        }

        // 报文头公共参数（第四十三轮改口径：从这份文档里撤出去）：仍然从这个仓自己的类学、仍然照旧发，
        // 只是不再列进 YApi 的出入参清单。用户口径「yapi 接口预览的时候里面的公共参数就不要列这里了」。
        // 撤出不是抹掉事实：键名照样占位（used），免得同一个公共字段换个来源当"业务参数"混回文档；
        // 撤了几个、从哪个类学的、怎么手工收回来，由下面那句恒显的 hdrNote 说，界面不另拼一遍。
        DispatchDictionaryService.HdrStamp stamp =
                dictionary.stampedHdrFields(projectId, str(target.get("entry_path")));
        List<String> hdrNames = new ArrayList<>();
        String hdrVia = "";
        for (Map<String, Object> h : stamp.fields()) {
            String name = str(h.get("key"));
            if (name.isBlank() || !used.add(low(name))) continue;
            hdrNames.add(name);
            if (hdrVia.isBlank()) hdrVia = str(h.get("via"));
        }
        String hdrNote = hdrNames.isEmpty() ? "" : "报文头公共参数 " + hdrNames.size() + " 个没有写进这份文档（"
                + String.join("、", hdrNames.size() > HDR_NAME_CAP ? hdrNames.subList(0, HDR_NAME_CAP) : hdrNames)
                + (hdrNames.size() > HDR_NAME_CAP ? " 等 " + hdrNames.size() + " 个" : "") + "）——"
                + "它们是「" + stamp.framework() + "」这个仓在请求体里另起的一段（" + stamp.hdrKey()
                + (hdrVia.isBlank() ? "" : "，学自 " + hdrVia) + "），每个框架一套、接口实现本身不认这些名字，"
                + "写进文档只会变成一排没有中文说明的裸字段。"
                + "【接口测试台】发请求时照样按这一份带上，一处没少。"
                + "确实要收进文档：【＋ 加一列入参】手工加，人工加的行重学不冲掉。";

        // 按实现读出来的入参：用户口径「除了几个固定入参，其余参数你都没有根据接口实现去读取，
        // 这个功能的意义就在于学习接口实现、知道出入参与字段意思」。声明里有的排在前面（带默认值），
        // 链上认出、声明没有的补在后面，并把来源与置信度一起带出去。
        // 只在 ${} 拼接里出现过的名字不进文档：那可能是内部对象的属性而不是请求键（实测：一个查询接口
        // 会顺出同一条链上 update 语句的二十来个列名），写进去就是给接口编参数。数量与原因照实说。
        int sqlOnly = 0;
        for (Map<String, Object> f : cast(op.get("inputs"))) {
            String name = str(f.get("field"));
            if (name.isBlank() || used.contains(low(name))) continue;
            if (str(f.get("via")).contains("SQL 拼接")) {
                sqlOnly++;
                continue;
            }
            used.add(low(name));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", name);
            m.put("type", f.get("type") == null ? "string" : str(f.get("type")));
            m.put("title", str(f.get("title")));
            // 说明的出处与字段的出处是两件事：按键取值那条证据说得出「代码读了这个键」，
            // 说不出中文；中文是同一趟链后面那条语句里这一列的列注释给的（CodeChainService.fillTitles），
            // 由它自己说，界面分开显示，不许看着像「声明里就写了这个意思」。
            String titleVia = str(f.get("titleVia"));
            if (!titleVia.isBlank()) m.put("titleVia", titleVia);
            m.put("fixed", false);
            m.put("value", "");
            m.put("via", str(f.get("via")));
            m.put("conf", str(f.get("conf")));
            m.put("file", str(f.get("file")));
            if (f.get("line") != null) m.put("line", f.get("line"));
            inputs.add(m);
        }
        String inputNote = sqlOnly == 0 ? "" : "链上另有 " + sqlOnly + " 个名字只在 ${} 拼接里出现过，"
                + "分不清是请求键还是内部对象的属性，所以没有写进文档"
                + "（同一条链上的更新语句会带出自己实体的列名，收进来就是给这个接口编参数）。"
                + "确实要收进去：手工【＋ 加一列入参】，人工加的行重学不冲掉。";
        return new AutoDoc(cfg, target, env, op, base5, inputs, outs, title, funcUrl, inputNote, hdrNote);
    }

    /**
     * 一个功能号的 YApi 文档：自动基线 + 人工维护层合并后的那一份。
     * 预览、Schema、与 YApi 的差异、推送全部读这同一条链——界面上看到的就是会被写出去的内容。
     */
    public Map<String, Object> draft(long projectId, String kind, String code, boolean withRemote) {
        AutoDoc a = autoDraft(projectId, kind, code);
        if (a == null) {
            return Map.of("ok", false, "error", "服务字典里没有 " + kind + "「" + code + "」：先学习该仓库，或换个代码");
        }
        Map<String, Object> cfg = a.cfg();
        Map<String, Object> env = a.env();
        List<Map<String, Object>> inputs = a.inputs();
        List<Map<String, Object>> outs = a.outputs();
        String title = a.title();
        String funcUrl = a.path();

        Map<String, Object> ovIn = docs.apply(projectId, kind, code, "in", inputs);
        Map<String, Object> ovOut = docs.apply(projectId, kind, code, "out", outs);
        inputs = cast(ovIn.get("rows"));
        outs = cast(ovOut.get("rows"));
        Map<String, Object> meta = docs.meta(projectId, kind, code);
        String autoTitle = title;
        String autoPath = funcUrl;
        if (meta != null) {
            if (!str(meta.get("ov_title")).isBlank()) title = str(meta.get("ov_title"));
            if (!str(meta.get("ov_path")).isBlank()) funcUrl = str(meta.get("ov_path"));
        }
        String editedAt = maxStr(str(ovIn.get("latestEdit")), str(ovOut.get("latestEdit")),
                meta == null ? "" : str(meta.get("edited_at")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("projectId", projectId);
        out.put("kind", kind);
        out.put("code", code);
        out.put("name", str(a.target().get("name")));
        out.put("title", title);
        out.put("path", funcUrl);
        out.put("method", "POST");
        out.put("inputs", inputs);
        out.put("outputs", outs);
        out.put("envelopeMode", a.base5() ? "base5" : "auto");
        out.put("envelope", env);
        out.put("envelopeNote", a.base5()
                ? "本项目在上方选了「按老基准固定带 5 项」，所以文档里无条件带 service/func/func_url/pageoffset/pagecount——"
                + "调用点是否真这么带，以 YApi 上的实际请求为准。"
                : str(env.get("note")));
        out.put("outputNote", outs.isEmpty() ? str(a.op().get("note")) : "");
        out.put("inputNote", a.inputNote());
        // 报文头撤出这件事必须由文档自己说，而不是靠"行数变少了"让人猜（第四十三轮口径）
        out.put("hdrNote", a.hdrNote());
        out.put("outputEvidence", evidenceOf(a.op()));
        // 「这一行为什么没有中文说明」要常驻：说明的唯一出处是库里的列注释，本地没学过那张表就是取不到，
        // 这既不是缺陷也不是漏填——照实说出处，并给补说明的两条路（元数据反哺 / 在这里手写一次）。
        long noTitleIn = 0, noTitleOut = 0;
        for (Map<String, Object> r : inputs) if (str(r.get("title")).isBlank()) noTitleIn++;
        for (Map<String, Object> r : outs) if (str(r.get("title")).isBlank()) noTitleOut++;
        out.put("titleNote", (noTitleIn + noTitleOut) == 0 ? "" : titleNote(a.op(), noTitleIn, noTitleOut));
        // 人工维护层：界面据此渲染徽章与折叠区；"自动那份是什么"也一并给出去，恢复自动才是可点的事实而不是猜
        out.put("manual", Map.of(
                "rows", docs.countFor(projectId, kind, code),
                "in", ovIn.get("manualCount"), "out", ovOut.get("manualCount"),
                "inHidden", cast(ovIn.get("hidden")).size(), "outHidden", cast(ovOut.get("hidden")).size(),
                "inOrphan", ovIn.get("orphanCount"), "outOrphan", ovOut.get("orphanCount"),
                "title", !title.equals(autoTitle), "path", !funcUrl.equals(autoPath),
                "at", editedAt));
        out.put("hiddenInputs", ovIn.get("hidden"));
        out.put("hiddenOutputs", ovOut.get("hidden"));
        out.put("autoTitle", autoTitle);
        out.put("autoPath", autoPath);
        out.put("schema", buildPayload(title, funcUrl, code, inputs, outs, !a.hdrNote().isEmpty()));
        if (withRemote) {
            Map<String, Object> remote = matchRemote(cfg, code, funcUrl, title);
            out.put("remote", remote);
            if (Boolean.TRUE.equals(remote.get("found"))) {
                out.put("diff", diff(remote, inputs, outs));
            }
        }
        return out;
    }

    private static String maxStr(String a, String b, String c) {
        String m = a.compareTo(b) >= 0 ? a : b;
        return m.compareTo(c) >= 0 ? m : c;
    }

    /**
     * 没有中文说明的那些行的解释。依据链在推得出列名、取不到注释时已经留了一句带表名的话
     * （{@code CodeChainService} 的「这几张表本地知识库没有列注释」那步），这里复用它，不另编一套原因；
     * 链上没这一步（说明本来就该有、或这条链压根没走到表）时只说总数与补法。
     * 入参／出参分开计数：一句"这 N 行"横跨两张表，人只会去数其中一张，必然对不上。
     */
    private String titleNote(Map<String, Object> op, long inMissing, long outMissing) {
        String why = "";
        for (Map<String, Object> s : cast(op.get("chain"))) {
            String t = str(s.get("text"));
            if (t.contains("列注释")) {
                why = t;
                break;
            }
        }
        List<String> who = new ArrayList<>();
        if (inMissing > 0) who.add("入参 " + inMissing + " 行");
        if (outMissing > 0) who.add("出参 " + outMissing + " 行");
        return String.join("、", who) + "没有中文说明——字段含义的唯一出处是库里的列注释，"
                + (why.isBlank() ? "这条链没走到带注释的列，所以只能给字段名。" : why + "。")
                + "补法两条：【知识库 → 元数据反哺】连库学过那几张表，或手工写一次中文说明（人工改过的行重学不冲掉）。";
    }

    /**
     * 保存人工维护的那一份：服务端先按当前代码算一遍自动基线，再只把差异落库（推导值不落库这条铁律不变）。
     * 传 null 的那一侧表示"这次没动它"，绝不能当成"这一侧全删了"。
     */
    public Map<String, Object> saveDoc(long projectId, String kind, String code, String title, String path,
                                       List<Map<String, Object>> inputs, List<Map<String, Object>> outputs) {
        AutoDoc a = autoDraft(projectId, kind, code);
        if (a == null) {
            return Map.of("ok", false, "error", "服务字典里没有 " + kind + "「" + code + "」：这条已经不在字典里了，"
                    + "先到「知识库 → 服务字典」确认代码，人工维护的行不会凭空造文档");
        }
        String user = com.preflight.common.UserContext.username();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        List<String> errors = new ArrayList<>();
        if (inputs != null) {
            Map<String, Object> r = docs.save(projectId, kind, code, "in", a.inputs(), inputs, user);
            out.put("in", r);
            if (!Boolean.TRUE.equals(r.get("ok"))) errors.add("入参：" + str(r.get("error")));
        }
        if (outputs != null) {
            Map<String, Object> r = docs.save(projectId, kind, code, "out", a.outputs(), outputs, user);
            out.put("out", r);
            if (!Boolean.TRUE.equals(r.get("ok"))) errors.add("出参：" + str(r.get("error")));
        }
        if (!errors.isEmpty()) {
            out.put("error", String.join("；", errors));
            out.put("ok", false);
            return out;
        }
        if (title != null || path != null) {
            out.put("meta", docs.saveMeta(projectId, kind, code, a.title(), a.path(), title, path, user));
        }
        audit.log("yapi.override-save", kind + "/" + code,
                "入参 " + brief(out.get("in")) + "；出参 " + brief(out.get("out"))
                        + "；标题路径 " + (Boolean.TRUE.equals(cast1(out.get("meta")).get("manual")) ? "已人工改" : "与自动一致"));
        // 保存之后回一个权威的"共几处"：界面刷新计数时不必自己加，也不会和列表里那个数打架
        out.put("rows", docs.countFor(projectId, kind, code));
        return out;
    }

    private static String brief(Object o) {
        Map<String, Object> m = o instanceof Map<?, ?> ? cast1(o) : Map.of();
        if (m.isEmpty()) return "未改";
        return "改 " + num(m.get("changed")) + " 增 " + num(m.get("added"))
                + " 删 " + num(m.get("hidden")) + " 复原 " + num(m.get("cleared"));
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast1(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 按代码/路径/标题在所选目录里找已有文档（YApi 的 up 需要它的 _id） */
    private Map<String, Object> matchRemote(Map<String, Object> cfg, String code, String funcUrl, String title) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        String pid = str(cfg.get("yapiProjectId"));
        String catid = str(cfg.get("catId"));
        if (pid.isBlank() || catid.isBlank()) {
            out.put("error", "还没选 YApi 项目或目录：先在上方选好，才能判断这条是新增还是更新");
            return out;
        }
        Map<String, Object> g = authed(cfg, (tk, uid) -> client.get(str(cfg.get("baseUrl")), tk, uid,
                "/interface/list", Map.of("project_id", pid, "catid", catid, "page", "1", "limit", "300")));
        // 会话在这次"查现状"里失效并自动重登过：这个事实要往下传给推送，
        // 否则界面看到的是一次成功的写入，却看不出中间其实续过一次期
        if (Boolean.TRUE.equals(g.get("relogged"))) out.put("relogged", true);
        if (!Boolean.TRUE.equals(g.get("ok"))) {
            // 地址那条结论要一路带到文档预览屏：在这里压成一句 error，界面就只剩"YApi 连不上"一个猜测
            out.put("error", str(g.get("error")));
            out.put("problem", str(g.get("problem")));
            out.putAll(baseView(g));
            return out;
        }
        YapiClient.Resp r = (YapiClient.Resp) g.get("resp");
        if (!r.ok()) {
            out.putAll(fail(g, r));
            return out;
        }
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode n : nodes(r.data(), "list")) all.add(n);
        JsonNode hit = null;
        for (JsonNode n : all) {
            String path = n.path("path").asText("");
            String t = n.path("title").asText("");
            if (path.equals(funcUrl) || (!t.isBlank() && t.equals(title))) {
                hit = n;
                break;
            }
        }
        if (hit == null) {
            for (JsonNode n : all) {
                if (n.path("title").asText("").contains(code) || n.path("path").asText("").contains(code)) {
                    hit = n;
                    break;
                }
            }
        }
        out.put("scanned", all.size());
        if (hit == null) {
            out.put("mode", "add");
            out.put("note", "所选目录下没有匹配的功能号（比对 " + all.size() + " 条），推送将走新增");
            return out;
        }
        out.put("found", true);
        out.put("mode", "up");
        out.put("id", hit.path("_id").asText(hit.path("id").asText("")));
        out.put("title", hit.path("title").asText(""));
        out.put("path", hit.path("path").asText(""));
        out.put("reqBody", hit.path("req_body_other").asText(""));
        out.put("resBody", hit.path("res_body").asText(""));
        out.put("reqIsSchema", hit.path("req_body_is_json_schema").asBoolean(false));
        out.put("resIsSchema", hit.path("res_body_is_json_schema").asBoolean(false));
        out.put("updAt", hit.path("up_time").asText(""));
        return out;
    }

    /** 字段级差异：YApi 现有 schema 与本次草稿的入参/出参逐个字段比 */
    private Map<String, Object> diff(Map<String, Object> remote, List<Map<String, Object>> inputs,
                                     List<Map<String, Object>> outs) {
        Set<String> remoteIn = schemaFields(remote.get("reqBody"), Boolean.TRUE.equals(remote.get("reqIsSchema")));
        Set<String> remoteOut = schemaFields(remote.get("resBody"), Boolean.TRUE.equals(remote.get("resIsSchema")));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("inputAdded", minus(fields(inputs), remoteIn));
        d.put("inputRemoved", minus(remoteIn, fields(inputs)));
        d.put("outputAdded", minus(fields(outs), remoteOut));
        d.put("outputRemoved", minus(remoteOut, fields(outs)));
        d.put("remoteIsSchema", Boolean.TRUE.equals(remote.get("reqIsSchema")) && Boolean.TRUE.equals(remote.get("resIsSchema")));
        d.put("note", Boolean.FALSE.equals(d.get("remoteIsSchema"))
                ? "YApi 上现有文档的请求/响应不是 JSON Schema 形态，推送会整块换成 Schema（原内容 YApi 会保留在版本历史里）" : "");
        return d;
    }

    private static Set<String> fields(List<Map<String, Object>> list) {
        Set<String> s = new LinkedHashSet<>();
        for (Map<String, Object> m : list) {
            String f = str(m.get("field"));
            if (!f.isBlank()) s.add(f.toLowerCase(java.util.Locale.ROOT));
        }
        return s;
    }

    private static List<String> minus(Set<String> a, Set<String> b) {
        List<String> out = new ArrayList<>();
        for (String s : a) if (!b.contains(s)) out.add(s);
        return out;
    }

    /** 已有文档的字段名集合：Schema 取 properties 键，非 Schema 只当"没可比字段" */
    private Set<String> schemaFields(Object rawBody, boolean isSchema) {
        Set<String> out = new LinkedHashSet<>();
        if (!isSchema || rawBody == null) return out;
        String s = String.valueOf(rawBody);
        if (s.isBlank()) return out;
        try {
            JsonNode n = mapper.readTree(s);
            JsonNode props = n.path("properties");
            if (props.isMissingNode()) props = n.path("ans_msg_hdr").path("properties");
            props.fieldNames().forEachRemaining(f -> out.add(f.toLowerCase(java.util.Locale.ROOT)));
        } catch (Exception ignore) {
        }
        return out;
    }

    // ---------- ③ 推送（逐条确认后才走到这里） ----------

    /** 把草稿组装成 YApi 的 add/up 请求体：Schema 放 req_body_other 与 res_body（这是 YApi 的存储坑位） */
    private Map<String, Object> buildPayload(String title, String funcUrl, String code,
                                             List<Map<String, Object>> inputs, List<Map<String, Object>> outs,
                                             boolean hdrStripped) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("method", "POST");
        body.put("path", funcUrl);
        body.put("status", "done");
        body.put("type", "static");
        body.put("req_body_type", "json");
        body.put("req_body_is_json_schema", true);
        body.put("req_body_other", json(schemaOf(inputs)));
        body.put("res_body_type", "json");
        body.put("res_body_is_json_schema", true);
        body.put("res_body", json(schemaOf(outs)));
        List<String> fixed = new ArrayList<>();
        for (Map<String, Object> f : inputs) if (Boolean.TRUE.equals(f.get("fixed"))) fixed.add(str(f.get("field")));
        body.put("markdown", "由 PreFlight 生成：功能号 " + code + "。"
                + "请求级字段（调用点认出的信封 / 框架契约）："
                + (fixed.isEmpty() ? "无——这个码的调用点没带任何信封键" : String.join("、", fixed)) + "；"
                + "其余为业务入参，出参见响应 Schema。"
                // 报文头撤出后，照抄这份文档的人会缺那一段——必须点名说它在哪儿，不能只靠"字段少了"暗示
                + (hdrStripped ? "报文头公共参数没有列进下面的字段清单：它是这个仓的框架在请求体里另起的一段（键名与清单见 PreFlight 的接口测试台），"
                        + "照抄时按框架自己补那一段。" : ""));
        return body;
    }

    /** 平铺成一层 properties：出参不带 errCode/errMsg、不带 ans_msg_hdr 信封（用户的 YApi 基准格式） */
    private Map<String, Object> schemaOf(List<Map<String, Object>> fields) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map<String, Object> f : fields) {
            String name = str(f.get("field"));
            if (name.isBlank()) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("type", jsonType(f.get("type")));
            one.put("description", str(f.get("title")));
            props.put(name, one);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", "empty object");
        schema.put("properties", props);
        return schema;
    }

    private static String jsonType(Object raw) {
        String t = String.valueOf(raw == null ? "" : raw).toLowerCase(java.util.Locale.ROOT);
        if (t.contains("int") || t.contains("number") || t.contains("num") || t.contains("double") || t.contains("float")) {
            return "integer";
        }
        return "string";
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 真正写 YApi：只处理界面逐条勾选后传来的项，服务端再核一遍"这条到底走 add 还是 up"——
     * 用户在预览里看到的 mode 与这里落地的 mode 必须是同一份判据。
     */
    public Map<String, Object> push(long projectId, List<Map<String, Object>> items) {
        Map<String, Object> cfg = config(projectId);
        String pid = str(cfg.get("yapiProjectId"));
        String catid = str(cfg.get("catId"));
        if (pid.isBlank() || catid.isBlank()) {
            return Map.of("ok", false, "error", "还没选 YApi 项目或目录，没有推送目标");
        }
        String base = str(cfg.get("baseUrl"));
        // 推送前先看地址、再看会话：地址没对时一条都不发（连密码也不发），而不是推到一半才断（N60）
        YapiClient.Probe pb = probe(base);
        if (!pb.apiRoot()) {
            Map<String, Object> rej = new LinkedHashMap<>();
            rej.put("ok", false);
            rej.put("problem", "address");
            rej.putAll(probeView(pb));
            rej.put("error", probeFailText(str(cfg.get("baseUrlFrom")), pb,
                    "一条都没发出去，YApi 那边没有任何改动；这条凭据也还没测。"));
            return rej;
        }
        // 先核一次会话：凭据不对就在第一条写入前停下，而不是推到一半才断
        Map<String, Object> a = auth(Long.parseLong(orZero(str(cfg.get("credId")))), base);
        if (a.containsKey("error")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", str(a.get("error")));
            if (!str(a.get("problem")).isBlank()) out.put("problem", str(a.get("problem")));
            out.putAll(probeView(pb));
            return out;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int done = 0, failed = 0;
        for (Map<String, Object> item : items == null ? List.<Map<String, Object>>of() : items) {
            String kind = str(item.get("kind"));
            String code = str(item.get("code"));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", kind);
            r.put("code", code);
            if (kind.isBlank() || code.isBlank()) {
                r.put("ok", false);
                r.put("error", "这一项缺 kind 或 code，没发请求");
                failed++;
                results.add(r);
                continue;
            }
            Map<String, Object> d = draft(projectId, kind, code, true);
            if (!Boolean.TRUE.equals(d.get("ok"))) {
                r.put("ok", false);
                r.put("error", str(d.get("error")));
                failed++;
                results.add(r);
                continue;
            }
            Map<String, Object> remote = mapOf(d.get("remote"));
            Map<String, Object> payload = new LinkedHashMap<>(mapOf(d.get("schema")));
            boolean up = Boolean.TRUE.equals(remote.get("found"));
            String apiPath = up ? "/interface/up" : "/interface/add";
            if (up) {
                payload.put("id", remote.get("id"));   // 主键字段名是 id，不是 _id
            } else {
                payload.put("project_id", Long.parseLong(orZero(pid)));
                payload.put("catid", Long.parseLong(orZero(catid)));
            }
            r.put("mode", up ? "up" : "add");
            r.put("title", d.get("title"));
            Map<String, Object> gw = authed(cfg, (tk, uid) -> client.post(base, tk, uid, apiPath, payload));
            if (!Boolean.TRUE.equals(gw.get("ok"))) {
                r.put("ok", false);
                r.put("error", str(gw.get("error")));
                failed++;
                results.add(r);
                continue;
            }
            YapiClient.Resp resp = (YapiClient.Resp) gw.get("resp");
            if (Boolean.TRUE.equals(gw.get("relogged")) || Boolean.TRUE.equals(remote.get("relogged"))) {
                r.put("relogged", true);   // 查现状时续过期也算：这条写入全程自动恢复过会话
            }
            r.put("ok", resp.ok());
            if (resp.ok()) {
                done++;
                r.put("yapiId", resp.data() == null ? "" : resp.data().path("_id").asText(""));
            } else {
                failed++;
                r.put("error", resp.errmsg() == null ? resp.transportError() : resp.errmsg());
            }
            results.add(r);
        }
        jdbc.update("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                "yapi.last_push." + projectId, json(Map.of("pushed", done, "failed", failed,
                        "at", java.time.LocalDateTime.now().toString())));
        // 推外部系统是"影响别人"的动作，留痕只记条数与目标位置，绝不记 token
        audit.log("yapi.push", "project " + projectId + " → yapi " + pid + "/" + catid,
                "逐条确认后推送 " + (items == null ? 0 : items.size()) + " 项：成功 " + done + "，失败 " + failed);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        // 失败的那几条也要有人说"地址是通的"——结果屏只写 errmsg 时，人还是会去翻密码（N60）
        res.putAll(probeView(pb));
        res.put("results", results);
        res.put("pushed", done);
        res.put("failed", failed);
        return res;
    }

    public Map<String, Object> lastPush(long projectId) {
        Map<String, Object> m = readSetting("yapi.last_push." + projectId);
        m.put("ok", true);
        return m;
    }

    // ---------- 小工具（本类内的读配置/转述错误都走这一处） ----------

    /**
     * YApi 侧回了业务错误（会话失效、权限不足那类）。这里必须把地址那条一起带上（N60）：
     * {@code authed()} 已经说过"地址是通的"，压成一句 error 就又是"只知道失败、不知道为什么"。
     * {@code g} 是那次外呼的 authed() 返回，两条结论都在里面。
     */
    private Map<String, Object> fail(Map<String, Object> g, YapiClient.Resp r) {
        String msg = r.errmsg() == null || r.errmsg().isBlank() ? r.transportError() : r.errmsg();
        String hint = r.errcode() == 40011 || (msg != null && (msg.contains("登录") || msg.contains("会话")))
                ? "（YApi 说会话失效：手填 token 的凭据约 7 天要自己换一条，"
                  + "用「YApi 账号密码」的凭据系统会自动重登）" : "";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", "YApi：" + (msg == null ? "未知错误" : msg) + " " + hint);
        out.put("errcode", r.errcode());
        out.putAll(baseView(g));
        return out;
    }

    private String setting(String key, String def) {
        try {
            String v = jdbc.queryForObject("SELECT value FROM settings WHERE key=?", String.class, key);
            return v == null || v.isBlank() ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSetting(String key) {
        try {
            String v = jdbc.queryForObject("SELECT value FROM settings WHERE key=?", String.class, key);
            if (v == null || v.isBlank()) return new LinkedHashMap<>();
            return mapper.readValue(v, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 文档草稿的证据行：先列**代码链**（bexConf → mapper 语句 → 盘上存储过程源码，带行号），
     * 再补 viewConf 的声明视图。第卅五轮口径：出入参主要靠代码，viewConf 只是补源，
     * 证据顺序就是可信度顺序——推送前人工核对要先看到真正算出这些字段的地方。
     */
    private List<Map<String, Object>> evidenceOf(Map<String, Object> op) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : cast(op.get("chain"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("via", "代码链 · " + str(s.get("text")));
            m.put("file", str(s.get("file")));
            m.put("line", s.get("line") == null ? 0 : s.get("line"));
            m.put("conf", str(s.get("conf")));
            out.add(m);
            if (out.size() >= EVIDENCE_CAP) return out;
        }
        for (Map<String, Object> v : cast(op.get("views"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("via", "viewConf 声明 · 视图 " + str(v.get("viewId")));
            m.put("file", str(v.get("file")));
            m.put("view", str(v.get("viewId")));
            out.add(m);
            if (out.size() >= EVIDENCE_CAP) return out;
        }
        return out;
    }

    /** 证据行上限：草稿预览是给人扫一眼的，不是依据链全量转储（全量在【出参入参】面板里看） */
    private static final int EVIDENCE_CAP = 12;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        if (o instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : l) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readList(Object raw) {
        if (raw == null) return new ArrayList<>();
        try {
            List<Object> l = new ObjectMapper().readValue(String.valueOf(raw), List.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<JsonNode> nodes(JsonNode data, String arrayKey) {
        List<JsonNode> out = new ArrayList<>();
        if (data == null || data.isMissingNode() || data.isNull()) return out;
        JsonNode arr = arrayKey == null ? data : data.path(arrayKey);
        if (arr.isArray()) {
            for (JsonNode n : arr) out.add(n);
        } else if (data.isArray()) {
            for (JsonNode n : data) out.add(n);
        }
        return out;
    }

    private static String orZero(String s) {
        return s == null || s.isBlank() ? "0" : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 键名归一（与人工覆盖层同一口径，见 {@link YapiDocOverrideService}）：大小写差一个的两个重复行写进 YApi 只会添乱 */
    private static String low(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
