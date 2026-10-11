package com.preflight.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.CryptoService;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 执行器：按接口卡构造请求；执行前运行登录流获取会话（Token/Cookie 注入）；
 * 验证码等复杂登录提供"手动 Cookie 接管"兜底。请求/响应全程快照留痕。
 */
@Service
public class HttpExecutorService {

    private final JdbcTemplateLike jdbc;
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    /**
     * 对外路径的唯一回答（第五十轮）。字段注入是为了不打散这个类的构造签名——
     * 它同时被非 Spring 的夹具直接 new，取不到 bean 时照旧按卡片路径发，并说明"没接上单点"。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.preflight.learn.GatewayRoutingService routing;

    /** 免依赖内联查询接口（避免与 JdbcTemplate 循环耦合） */
    public interface JdbcTemplateLike {
        Map<String, Object> queryForMap(String sql, Object... args);

        List<Map<String, Object>> queryForList(String sql, Object... args);
    }

    public HttpExecutorService(javax.sql.DataSource dataSource, CryptoService crypto, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplateLike() {
            private final org.springframework.jdbc.core.JdbcTemplate t = new org.springframework.jdbc.core.JdbcTemplate(dataSource);

            @Override
            public Map<String, Object> queryForMap(String sql, Object... args) {
                return t.queryForMap(sql, args);
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return t.queryForList(sql, args);
            }
        };
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public record HttpResult(boolean ok, int statusCode, String body, long latencyMs,
                             String requestSnapshot, String error) {}

    /** 会话缓存（R2）：key = envId:flowId，TTL 25 分钟；401 时失效重登一次 */
    private record SessionEntry(Map<String, Object> vars, long at) {}

    private static final java.util.concurrent.ConcurrentHashMap<String, SessionEntry> SESSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS = 25 * 60 * 1000L;

    /** 执行一次接口调用。params 结构：{path:{}, query:{}, body:{}} */
    public HttpResult run(long interfaceId, long envId, Long loginFlowId, Map<String, Object> params) {
        return run(interfaceId, envId, loginFlowId, params, null, null);
    }

    public HttpResult run(long interfaceId, long envId, Long loginFlowId, Map<String, Object> params,
                          String directBaseUrl) {
        return run(interfaceId, envId, loginFlowId, params, directBaseUrl, null);
    }

    /**
     * directBaseUrl 非空 = 仓库直连模式（第八轮）：请求打到仓库服务地址，跳过登录流与会话注入。
     * pathMode="internal" = 本次请求显式要按卡片学到的<b>内部</b>路径发（第五十轮测试台的开关，
     * 只对这一次生效，<b>不落库</b>；默认按 {@code GatewayRoutingService} 的对外路径回答）。
     */
    @SuppressWarnings("unchecked")
    public HttpResult run(long interfaceId, long envId, Long loginFlowId, Map<String, Object> params,
                          String directBaseUrl, String pathMode) {
        Map<String, Object> itf = jdbc.queryForMap("SELECT * FROM interfaces WHERE id=?", interfaceId);
        Map<String, Object> env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
        boolean direct = directBaseUrl != null && !directBaseUrl.isBlank();
        String baseUrl = direct ? directBaseUrl.trim() : (String) env.get("base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            return new HttpResult(false, 0, null, 0, null, "环境未配置 base_url");
        }
        // 对外路径在这一处定，之后真发、快照与复制 cURL 都只能用它（三处各拼一遍必然对不上）
        com.preflight.learn.GatewayRoutingService.Answer route = routeFor(itf, baseUrl, direct, pathMode);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("base_url", baseUrl); // 登录流与请求同源时使用
        String envVars = (String) env.get("vars");
        if (envVars != null) {
            try {
                vars.putAll(mapper.readValue(envVars, Map.class));
            } catch (Exception ignore) {
            }
        }

        // 1. 登录流 → 会话变量（带缓存；401 时由下方重试逻辑失效并重登）；仓库直连模式跳过
        Map<String, Object> apply = Map.of();
        String sessionKey = null;
        if (!direct)
        try {
            Long flowId = loginFlowId; // null=自动取项目首条；-1=显式不登录（第 10 轮测试台可选）
            if (flowId == null) {
                List<Map<String, Object>> flows = jdbc.queryForList(
                        "SELECT id FROM login_flows WHERE project_id=? ORDER BY id LIMIT 1", itf.get("project_id"));
                flowId = flows.isEmpty() ? null : ((Number) flows.get(0).get("id")).longValue();
            }
            if (flowId != null && flowId > 0) {
                sessionKey = envId + ":" + flowId;
                Map<String, Object> flow = jdbc.queryForMap("SELECT * FROM login_flows WHERE id=?", flowId);
                SessionEntry se = SESSIONS.get(sessionKey);
                if (se == null || System.currentTimeMillis() - se.at() > SESSION_TTL_MS) {
                    se = new SessionEntry(runLoginFlow(flow, vars), System.currentTimeMillis());
                    SESSIONS.put(sessionKey, se);
                }
                vars.putAll(se.vars());
                String applyJson = (String) flow.get("apply");
                if (applyJson != null) apply = mapper.readValue(applyJson, Map.class);
            }
        } catch (Exception e) {
            return new HttpResult(false, 0, null, 0, null, "自动登录失败：" + e.getMessage()
                    + "（验证码类登录可在环境 Cookie 接管框粘贴登录后的 Cookie）");
        }

        // 2. 组装 + 发送（401 且有登录流 → 失效会话重登一次再试）
        String method = String.valueOf(itf.getOrDefault("http_method", "GET"));
        Map<String, Object> p = params == null ? Map.of() : params;
        HttpResult result = attempt(itf, baseUrl, method, p, apply, vars, env, route);
        if (result.statusCode() == 401 && sessionKey != null) {
            try {
                Long flowId = Long.valueOf(sessionKey.split(":")[1]);
                Map<String, Object> flow = jdbc.queryForMap("SELECT * FROM login_flows WHERE id=?", flowId);
                SESSIONS.remove(sessionKey);
                Map<String, Object> fresh = runLoginFlow(flow, vars);
                SESSIONS.put(sessionKey, new SessionEntry(fresh, System.currentTimeMillis()));
                vars.putAll(fresh);
                result = attempt(itf, baseUrl, method, p, apply, vars, env, route);
            } catch (Exception e) {
                return new HttpResult(false, 401, result.body(), 0, result.requestSnapshot(),
                        "401 后自动重登失败：" + e.getMessage());
            }
        }
        return result;
    }

    /** 试跑登录流：绕过会话缓存真实执行一遍，返回提取到的会话变量（值打码），供设置页诊断 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> trialLoginFlow(long flowId, long envId) {
        Map<String, Object> env;
        try {
            env = jdbc.queryForMap("SELECT * FROM environments WHERE id=?", envId);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "环境不存在: " + envId);
        }
        String baseUrl = (String) env.get("base_url");
        if (baseUrl == null || baseUrl.isBlank()) return Map.of("ok", false, "error", "环境未配置 base_url");
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("base_url", baseUrl);
        String envVars = (String) env.get("vars");
        if (envVars != null) {
            try {
                vars.putAll(mapper.readValue(envVars, Map.class));
            } catch (Exception ignore) {
            }
        }
        try {
            Map<String, Object> flow = jdbc.queryForMap("SELECT * FROM login_flows WHERE id=?", flowId);
            String blank = emptyFlowNotice(flow.get("steps"));
            if (blank != null) return Map.of("ok", false, "error", blank);
            Map<String, Object> session = runLoginFlow(flow, vars);
            Map<String, Object> extracted = new LinkedHashMap<>();
            session.forEach((k, v) -> {
                // 覆盖同名环境变量（如 pf_cookie）也算登录流的产出，只要值变了就显示出来
                if (!java.util.Objects.equals(vars.get(k), v)) extracted.put(k, maskValue(String.valueOf(v)));
            });
            return Map.of("ok", true, "extracted", extracted);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    private static String maskValue(String s) {
        if (s == null || s.length() <= 8) return "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    /**
     * 第 47 轮：一条步骤都没有的流去【试跑】，一个包也不会发，不能报「试跑成功」。
     * 只拦试跑这一条口：自动登录那条口保持原样（空流等于「不登录」，会话仍可由环境的 Cookie 接管提供），
     * 在这里抛错会把不需要登录的调用一并拦死。
     */
    String emptyFlowNotice(Object stepsRaw) {
        String s = stepsRaw == null ? "" : String.valueOf(stepsRaw).trim();
        if (s.startsWith("\"")) {
            try {
                s = mapper.readValue(s, String.class).trim();   // 历史双重编码（R2）
            } catch (Exception e) {
                return null;   // 解不开就交给 runLoginFlow 报它自己的原文错
            }
        }
        if (s.isEmpty()) return blankStepsNotice();
        try {
            if (mapper.readValue(s, List.class).isEmpty()) return blankStepsNotice();
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static String blankStepsNotice() {
        return "这条流一条步骤都没有：试跑不会发出任何请求，也就无从判断能不能登录。"
                + "请先到 [登录流] 编辑它，按「这个项目学到的登录线索」把步骤填上（账号密码用 {{cred.username}} / {{cred.password}} 引用）。";
    }

    /**
     * 拼"地址 + 路径"的<b>唯一</b>出口（第五十轮）。
     *
     * <p>以前真发、复制 cURL、登录步骤三处各拼一遍，规则还不一致（登录那处连斜杠都不规范）：
     * 补前缀这种改动只要漏掉一处，界面看到的和发出去的就分家。规则：剥掉 base 尾部斜杠、
     * path 缺前导斜杠就补，其余原样接——重复段由调用方给规范路径。
     */
    public static String composeCallUrl(String baseUrl, String path) {
        String b = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String p = path == null ? "" : path.trim();
        if (p.isEmpty()) return b;
        if (!p.startsWith("/")) p = "/" + p;
        return b + p;
    }

    /** 这一张卡本次要发的路径由单点回答；仓库直连模式一律照卡片路径（直连本来就不经过网关） */
    private com.preflight.learn.GatewayRoutingService.Answer routeFor(Map<String, Object> itf, String baseUrl,
                                                                     boolean direct, String pathMode) {
        String internal = String.valueOf(itf.get("path"));
        if (routing == null) return null;
        if (direct) {
            return new com.preflight.learn.GatewayRoutingService.Answer(
                    com.preflight.learn.GatewayRoutingService.NOT_APPLICABLE, internal, internal,
                    List.of(), List.of(), "仓库直连：打到仓库自己的服务地址，不经网关", "");
        }
        try {
            long projectId = itf.get("project_id") instanceof Number n ? n.longValue() : 0L;
            Long repoId = itf.get("repo_id") instanceof Number n ? n.longValue() : null;
            String source = itf.get("source") == null ? "" : String.valueOf(itf.get("source"));
            return routing.forCard(projectId, repoId, internal, source, baseUrl,
                    "internal".equals(pathMode),
                    com.preflight.learn.GatewayRoutingService.callPathDeclared(
                            itf.get("params_schema") == null ? "" : String.valueOf(itf.get("params_schema"))));
        } catch (Exception e) {
            return new com.preflight.learn.GatewayRoutingService.Answer(
                    com.preflight.learn.GatewayRoutingService.MISSING_INPUT, internal, internal,
                    List.of(), List.of(), "这一趟没能算出对外路径（" + e + "），照卡片路径发", "");
        }
    }

    private static String routeLine(com.preflight.learn.GatewayRoutingService.Answer route) {
        if (route == null || com.preflight.learn.GatewayRoutingService.NOT_APPLICABLE.equals(route.state())) {
            return "";
        }
        return "\n路径依据: " + route.summary()
                + (route.evidence().isEmpty() ? "" : "｜" + String.join("；", route.evidence()));
    }

    /** 单次请求组装与发送（R2：真实状态码、环境占位符替换、Cookie 接管） */
    @SuppressWarnings("unchecked")
    private HttpResult attempt(Map<String, Object> itf, String baseUrl, String method, Map<String, Object> p,
                               Map<String, Object> apply, Map<String, Object> vars, Map<String, Object> env,
                               com.preflight.learn.GatewayRoutingService.Answer route) {
        // 路径只从 route 拿（第五十轮）：卡片学的是模块内部路径，经网关的项目要发对外那一份
        String pathTpl = route == null ? String.valueOf(itf.get("path")) : route.path();
        Map<String, Object> pathParams = (Map<String, Object>) p.getOrDefault("path", Map.of());
        for (Map.Entry<String, Object> en : pathParams.entrySet()) {
            pathTpl = pathTpl.replace("{" + en.getKey() + "}", String.valueOf(en.getValue()));
        }
        // 执行前校验：仍有未填充的 {placeholder} → 直接拒绝，不发注定失败的请求
        var missing = new java.util.LinkedHashSet<String>();
        var mt = java.util.regex.Pattern.compile("\\{(\\w+)\\}").matcher(pathTpl);
        while (mt.find()) missing.add(mt.group(1));
        if (!missing.isEmpty()) {
            return new HttpResult(false, 0, null, 0,
                    method + " " + pathTpl + "（未发送）",
                    "缺少路径参数: " + String.join("、", missing) + "。请在测试台填写后执行");
        }
        StringBuilder url = new StringBuilder(composeCallUrl(baseUrl, pathTpl));

        Map<String, Object> query = (Map<String, Object>) p.getOrDefault("query", Map.of());
        if (!query.isEmpty()) {
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, Object> en : query.entrySet()) {
                if (en.getValue() == null || String.valueOf(en.getValue()).isEmpty()) continue;
                qs.append(qs.isEmpty() ? "?" : "&").append(URLEncoder.encode(en.getKey(), StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(String.valueOf(en.getValue()), StandardCharsets.UTF_8));
            }
            url.append(qs);
        }
        Object body = p.get("body");
        // 环境变量占位符替换（R14：path/query/body 中的 {{var}} 现已生效）
        body = substituteDeep(body, vars);
        // 按卡片声明的线路序列化（第 10 轮：KJDP 分发入口要 text/xml，报文本身仍是 JSON 文本）
        String wire = declaredWire(itf);
        Object sendBody = body;
        if (wire != null && body != null && !(body instanceof String)) {
            try {
                sendBody = mapper.writeValueAsString(body);
            } catch (Exception e) {
                return new HttpResult(false, 0, null, 0, method + " " + url + "（未发送）",
                        "按卡片声明的 " + wire + " 序列化请求体失败: " + e.getMessage());
            }
        }
        // 卡片上的动词可能学自 Servlet 通配映射（ANY），真实发包要落到一个具体动词上
        String verb = usableMethod(method, sendBody);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", wire == null
                ? "application/json; charset=utf-8"
                : wire + (wire.toLowerCase().contains("charset") ? "" : "; charset=utf-8"));
        Object headerObj = apply.get("header");
        if (headerObj instanceof Map) {
            for (Map.Entry<String, Object> en : ((Map<String, Object>) headerObj).entrySet()) {
                headers.put(en.getKey(), substitute(String.valueOf(en.getValue()), vars));
            }
        }
        // Cookie 接管：环境 vars.pf_cookie 直接作为 Cookie 头（验证码/SSO 的人工兜底；自动登录成功也写到这里）
        Object cookie = vars.get("pf_cookie");
        if (cookie != null && !String.valueOf(cookie).isBlank()) {
            headers.put("Cookie", String.valueOf(cookie));
        }

        // 留痕要说清发出去的是哪一份路径：卡片学的是内部路径，经网关的项目补了哪几段、依据是什么，
        // 事后只看这一条快照就得能复盘（第廿三轮口径：写出去的东西必须与界面看到的同源）
        String requestSnapshot = verb + " " + url + routeLine(route)
                + "\nHeaders: " + redact(headers)
                + (sendBody == null || (sendBody instanceof Map && ((Map<?, ?>) sendBody).isEmpty()) ? "" : "\nBody: " + sendBody);
        try {
            HttpClient hc = com.preflight.common.Http1.builder().connectTimeout(Duration.ofSeconds(8)).build();
            JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(hc);
            rf.setReadTimeout(Duration.ofSeconds(30));
            RestClient client = RestClient.builder().requestFactory(rf).build();

            RestClient.RequestBodySpec spec = client.method(
                    org.springframework.http.HttpMethod.valueOf(verb))
                    .uri(url.toString());
            for (Map.Entry<String, String> h : headers.entrySet()) spec = spec.header(h.getKey(), h.getValue());
            if (sendBody != null && !(sendBody instanceof Map && ((Map<?, ?>) sendBody).isEmpty())
                    && !verb.equals("GET")) {
                spec = spec.body(sendBody);
            }
            long begin = System.currentTimeMillis();
            org.springframework.http.ResponseEntity<String> resp =
                    spec.retrieve().toEntity(String.class);
            long latency = System.currentTimeMillis() - begin;
            int status = resp.getStatusCode().value();
            return new HttpResult(status >= 200 && status < 400, status, truncate(resp.getBody()),
                    latency, requestSnapshot, null);
        } catch (org.springframework.web.client.HttpClientErrorException | org.springframework.web.client.HttpServerErrorException e) {
            return new HttpResult(false, e.getStatusCode().value(), truncate(e.getResponseBodyAsString()),
                    0, requestSnapshot, "HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            return new HttpResult(false, 0, null, 0, requestSnapshot, cause.getMessage());
        }
    }

    /** 递归替换 body 里的 {{var}}（字符串值逐个替换，保留结构） */
    @SuppressWarnings("unchecked")
    private Object substituteDeep(Object node, Map<String, Object> vars) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) out.put(String.valueOf(en.getKey()), substituteDeep(en.getValue(), vars));
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(substituteDeep(o, vars));
            return out;
        }
        if (node instanceof String s && s.contains("{{")) return substitute(s, vars);
        return node;
    }

    /**
     * 执行登录流：steps 顺序执行，extract 按取值来源提取变量；凭据注入 {{cred.*}}；验证码步骤明确报错。
     * 步骤可选字段（全部向后兼容）：
     * content_type 覆盖默认 JSON、raw_body 字符串模板、
     * encode=kjdp-login 走金证前端同款线路编码 / encode=sm4-ecb 整块请求体国密加密（配 encode_key），
     * encode_fields={"字段路径":"ka"} 字段级混淆（可与 sm4-ecb 叠加：密码先 ka、整包再 SM4），
     * extract 的值支持三种来源：$.a.b（响应体 JSON）、header:名字（响应头）、cookie:名字（Cookie 罐），
     * success_field/success_value/code_messages 把业务失败码转成明确报错、extract_cookie 收集整串会话 Cookie。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runLoginFlow(Map<String, Object> flow, Map<String, Object> vars) throws Exception {
        String stepsJson = String.valueOf(flow.get("steps"));
        String applyJson = flow.get("apply") == null ? "{}" : String.valueOf(flow.get("apply"));
        // 历史双重编码兜底（R2）：存量数据形如 "{\"steps\":...}" 时先解一层
        if (stepsJson.trim().startsWith("\"")) stepsJson = mapper.readValue(stepsJson, String.class);
        if (applyJson.trim().startsWith("\"")) applyJson = mapper.readValue(applyJson, String.class);
        List<Map<String, Object>> steps = mapper.readValue(stepsJson, List.class);
        Map<String, Object> session = new LinkedHashMap<>(vars);

        // 凭据注入（R2）：{{cred.username}} / {{cred.password}} / {{cred.secret}}
        Object credId = flow.get("cred_id");
        if (credId != null) {
            try {
                Map<String, Object> cred = jdbc.queryForMap("SELECT secret_enc FROM credentials WHERE id=?",
                        ((Number) credId).longValue());
                String secret = crypto.decrypt((String) cred.get("secret_enc"));
                int i = secret.indexOf(':');
                if (i > 0) {
                    session.put("cred.username", secret.substring(0, i));
                    session.put("cred.password", secret.substring(i + 1));
                }
                session.put("cred.secret", secret);
            } catch (Exception ignore) {
            }
        }

        // 第四十八轮：引用了凭据却没有可用凭据时，整串 {{cred.password}} 会被当成密码发出去，
        // 目标系统只回一句"账号或密码错误"，人查不到是自己没绑。开跑前一次性拦下（一个事实一个出口）。
        String credRefsJson = stepsJson + applyJson;
        for (String k : List.of("cred.username", "cred.password", "cred.secret")) {
            if (credRefsJson.contains("{{" + k + "}}") && !session.containsKey(k)) {
                throw new IllegalStateException("这条登录流用了 {{" + k + "}}，但没有可用的登录凭据："
                        + "点【编辑】在「登录凭据」里选一条（内容要写成 user:password 两段），"
                        + "否则这一格会把占位符本身发出去，被测系统只会回一句账号或密码错误");
            }
        }

        // 整条登录流共用一个 CookieJar：老工程的 validate_login 与 login 常常要串同一颗 JSESSIONID
        java.net.CookieManager jar = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
        HttpClient hc = com.preflight.common.Http1.builder().connectTimeout(Duration.ofSeconds(8)).cookieHandler(jar).build();
        int idx = 0;
        for (Map<String, Object> step : steps) {
            idx++;
            if ("manual".equals(step.get("captcha"))) {
                throw new IllegalStateException("该登录流包含验证码步骤（暂不支持自动识别）：请在环境的 Cookie 接管框粘贴登录后的 Cookie，实现人工兜底");
            }
            String url = substitute(String.valueOf(step.get("path")), session);
            String base = String.valueOf(session.getOrDefault("login_base_url", ""));
            if (base.isEmpty()) {
                // 登录地址相对被测服务同源：由调用方把 base_url 放入 vars
                base = String.valueOf(session.getOrDefault("base_url", ""));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            Object rawBody = step.get("body");
            if (rawBody instanceof Map<?, ?> bm) {
                // 嵌套结构照原样带下去（第 47 轮）：以前逐个 String.valueOf，{"a":{"b":"{{x}}"}} 会被打成字符串
                for (Map.Entry<?, ?> en : bm.entrySet()) {
                    body.put(String.valueOf(en.getKey()), substituteDeep(en.getValue(), session));
                }
            }
            String wire = rawBody instanceof String s ? substitute(s, session) : null;
            String contentType = step.get("content_type") == null
                    ? null : substitute(String.valueOf(step.get("content_type")), session);
            Object encode = step.get("encode");
            String enc = encode == null ? "" : String.valueOf(encode).trim();
            applyFieldEncoders(step, body, enc, idx);
            if ("kjdp-login".equals(enc)) {
                Map<String, String> fields = new LinkedHashMap<>();
                if (step.get("fields") instanceof Map<?, ?> fm) {
                    for (Map.Entry<?, ?> en : fm.entrySet()) {
                        fields.put(String.valueOf(en.getKey()), substitute(String.valueOf(en.getValue()), session));
                    }
                }
                wire = KjdpWireCodec.loginBody(fields, String.valueOf(step.getOrDefault("password_field", "")),
                        String.valueOf(step.getOrDefault("encrypt_factor", KjdpWireCodec.DEFAULT_KEY)));
                if (contentType == null) contentType = "text/xml; charset=utf-8";
            } else if ("sm4-ecb".equals(enc)) {
                // 整块请求体加密（第 47 轮，通用）：被测工程的前端就是把登录 JSON 整串交给 encrypt_ECB，
                // 密钥由上一步 extract 出来的变量给（encode_key 里写 {{var}}），密文按文本发出去
                String plain = wire != null ? wire : mapper.writeValueAsString(body);
                String key = substitute(String.valueOf(step.getOrDefault("encode_key", "")), session);
                if (key.isBlank()) {
                    throw new IllegalStateException("登录流第 " + idx + " 步 encode=sm4-ecb 但 encode_key 是空的："
                            + "要写成引用前面步骤 extract 出来的变量，如 \"encode_key\":\"{{randomCode}}\"");
                }
                wire = Sm4Ecb.encrypt(plain, key);
                if (contentType == null) contentType = "text/plain; charset=utf-8";
            } else if (!enc.isEmpty()) {
                throw new IllegalStateException("登录流第 " + idx + " 步的编码方式暂不支持：" + enc
                        + "（目前支持 kjdp-login、sm4-ecb）");
            }
            RestClient client = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(hc)).build();
            RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod
                    .valueOf(String.valueOf(step.getOrDefault("method", "POST")).toUpperCase()))
                    .uri(composeCallUrl(base, url));
            spec = spec.header("Content-Type", contentType == null ? "application/json; charset=utf-8" : contentType);
            if (wire != null) spec = spec.body(wire);
            else if (!body.isEmpty()) spec = spec.body(body);
            org.springframework.http.ResponseEntity<String> ent = spec.retrieve().toEntity(String.class);
            String resp = ent.getBody();

            Object extract = step.get("extract");
            if (extract instanceof Map<?, ?> em) {
                JsonNode[] node = new JsonNode[1];
                for (Map.Entry<?, ?> en : em.entrySet()) {
                    String varName = String.valueOf(en.getKey());
                    String expr = String.valueOf(en.getValue());
                    // 一个变量可以有多个来源（前端自己就写 response.data?.randomCode || response.data）：
                    // 按<b>它自己写的顺序</b>试，取到第一个非空为止；全取不到才报错，并把试过的来源都列出来
                    List<String> alts = extractAlts(expr);
                    Object val = null;
                    String where = "";
                    for (String alt : alts) {
                        Object[] hit = readSource(alt, ent, resp, jar, node);
                        if (hit == null) continue;
                        val = hit[0];
                        where = String.valueOf(hit[1]);
                        break;
                    }
                    // 取不到就当场说清，别把 null 带进后面的 "Bearer {{token}}"（拼出 "Bearer null" 更难查）
                    if (val == null || String.valueOf(val).isBlank()) {
                        throw new IllegalStateException("登录流第 " + idx + " 步没有取到 " + varName + "：试过 "
                                + alts.size() + " 个来源（" + String.join("、", alts) + "）都是空的"
                                + "：请确认这一步真的成功返回了它（账号密码/服务地址先看上面的报错）");
                    }
                    session.put(varName, val);
                }
            }
            checkLoginStep(step, idx, resp);
            Object cookieVar = step.get("extract_cookie");
            if (cookieVar != null) {
                StringBuilder ck = new StringBuilder();
                for (java.net.HttpCookie c : jar.getCookieStore().getCookies()) {
                    ck.append(ck.isEmpty() ? "" : "; ").append(c.getName()).append('=').append(c.getValue());
                }
                if (ck.isEmpty()) {
                    throw new IllegalStateException("登录流第 " + idx + " 步没有返回 Cookie：请确认服务地址可访问、账号密码正确");
                }
                // 与人工 Cookie 接管同一个变量：自动登录成功后 pf_cookie 被真实会话覆盖，兜底值不再生效
                session.put(String.valueOf(cookieVar), ck.toString());
            }
        }
        return session;
    }

    /**
     * 字段级编码（第四十八轮，通用）：{@code encode_fields:{"password":"ka"}} 只改请求体里的某几格，
     * 改完还可以再走整块 {@code encode}（金证那一系的前端做法是"密码先 ka 混淆、整包再加密"两段叠在
     * 一起，两个整块 encode 值互斥就表达不了这种链条）。
     *
     * <p>密码混淆用的就是 {@link KjdpWireCodec#kaEncrypt}——它复刻的是金证前端 ka.js，盘上别的仓库
     * utils 里那份同名 encrypt 是同一套逐位变换，所以这条能力不属于某一个项目。
     */
    private static void applyFieldEncoders(Map<String, Object> step, Map<String, Object> body, String enc, int idx) {
        Object efObj = step.get("encode_fields");
        if (efObj == null) return;
        if ("kjdp-login".equals(enc)) {
            throw new IllegalStateException("登录流第 " + idx + " 步同时写了 encode=kjdp-login 与 encode_fields："
                    + "整条金证线路编码自己会混淆 password_field，请求体根本不会被发出去，两种写法只能留一种");
        }
        if (!(efObj instanceof Map<?, ?> efm) || efm.isEmpty()) {
            throw new IllegalStateException("登录流第 " + idx + " 步的 encode_fields 要写成对象，"
                    + "如 \"encode_fields\":{\"password\":\"ka\"}（键＝请求体里的位置，值＝可用的编码器：ka）");
        }
        if (body.isEmpty()) {
            throw new IllegalStateException("登录流第 " + idx + " 步写了 encode_fields 但这一步没有 body 对象："
                    + "字段级编码改的是 JSON 请求体里的某几格（raw_body 是一整串文本，改不了单个字段）");
        }
        String factor = String.valueOf(step.getOrDefault("encrypt_factor", KjdpWireCodec.DEFAULT_KEY));
        for (Map.Entry<?, ?> en : efm.entrySet()) {
            String where = String.valueOf(en.getKey());
            String how = String.valueOf(en.getValue()).trim();
            String plain = String.valueOf(orEmpty(readByPath(body, where, idx)));
            // 只在这一步真要发出去的时候才改，且改完的密文不回写任何留痕（明文密码不落地）
            String coded = switch (how) {
                case "ka", "ka.encrypt" -> KjdpWireCodec.kaEncrypt(plain, factor);
                case "plain", "none" -> plain;
                default -> throw new IllegalStateException("登录流第 " + idx + " 步的字段编码器暂不支持：" + how
                        + "（目前支持 ka＝金证前端 ka.encrypt 同款混淆、plain/none＝不改）");
            };
            setByPath(body, where, coded, idx);
        }
    }

    private static Object orEmpty(Object v) {
        return v == null ? "" : v;
    }

    /** 按点路径读请求体里的格子（数组位一律落到第 0 个样例元素，与界面铺出来的骨架形状一致） */
    private static Object readByPath(Map<String, Object> body, String dotted, int idx) {
        Object cur = body;
        for (String seg : dotted.split("\\.")) cur = childOf(cur, seg, idx, dotted);
        return cur;
    }

    /** 走一段路径：段尾的 [] 表示"这个数组的第 0 个元素"（骨架里数组只给一个样例位） */
    private static Object childOf(Object cur, String seg, int idx, String dotted) {
        Object node = cur;
        if (seg.endsWith("[]")) {
            node = pick(node, seg.substring(0, seg.length() - 2), idx, dotted);
            node = pick(node, "0", idx, dotted);
            return node;
        }
        return pick(node, seg, idx, dotted);
    }

    private static Object pick(Object cur, String key, int idx, String dotted) {
        if (cur instanceof Map<?, ?> m && m.containsKey(key)) {
            return m.get(key);
        }
        if (cur instanceof List<?> l && key.matches("\\d+") && l.size() > Integer.parseInt(key)) {
            return l.get(Integer.parseInt(key));
        }
        throw new IllegalStateException("登录流第 " + idx + " 步的 encode_fields 位置 " + dotted + " 在请求体里找不到："
                + key + "（这一层现有的键：" + (cur instanceof Map<?, ?> m
                ? String.join("、", m.keySet().stream().map(String::valueOf).toList())
                : cur instanceof List<?> l ? "数组，" + l.size() + " 个元素" : cur == null ? "无" : "不是对象") + "）");
    }

    /** 按点路径写回；路径上任何一段缺失都报错，不静默建出新键——那等于替用户编了一个字段 */
    @SuppressWarnings("unchecked")
    private static void setByPath(Map<String, Object> body, String dotted, Object value, int idx) {
        String[] segs = dotted.split("\\.");
        Object cur = body;
        for (int i = 0; i < segs.length - 1; i++) cur = childOf(cur, segs[i], idx, dotted);
        String last = segs[segs.length - 1];
        if (last.endsWith("[]")) {
            Object list = pick(cur, last.substring(0, last.length() - 2), idx, dotted);
            if (!(list instanceof List<?> l) || l.isEmpty()) {
                throw new IllegalStateException("登录流第 " + idx + " 步的 encode_fields 位置 " + dotted
                        + " 指向的数组是空的：骨架里没有可改的第 0 个元素");
            }
            ((List<Object>) l).set(0, value);
        } else {
            pick(cur, last, idx, dotted);
            ((Map<String, Object>) cur).put(last, value);
        }
    }

    /**
     * 一个取值式拆成候选（按 {@code ||} 分，保持前端自己写的顺序）。
     *
     * <p>第四十九轮：前端取会话值最常写成 {@code response.data?.randomCode || response.data?.body}
     * 这种"好几个地方都试一下"的形状。学习通路把它整串抄下来的时候，引擎只认一条式子就等于
     * 把学到的东西丢了一半——所以引擎按顺序试，而不是让学习层去猜哪一个对。
     */
    private static List<String> extractAlts(String expr) {
        List<String> out = new ArrayList<>();
        if (expr == null) return out;
        for (String p : expr.split("\\|\\|")) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 试一个来源：命中返回 {值, 从哪取的}，取不到返回 null（不报错，交给调用方试下一个） */
    @SuppressWarnings("unchecked")
    private Object[] readSource(String expr, org.springframework.http.ResponseEntity<String> ent, String resp,
                                java.net.CookieManager jar, JsonNode[] nodeCache) throws Exception {
        if (expr.startsWith("header:")) {
            String hn = expr.substring(7).trim();
            String v = ent.getHeaders().getFirst(hn);
            return v == null || v.isBlank() ? null : new Object[]{v, "响应头 " + hn};
        }
        if (expr.startsWith("cookie:")) {
            String cn = expr.substring(7).trim();
            String v = cookieValue(jar, cn);
            return v == null || v.isBlank() ? null : new Object[]{v, "Cookie " + cn};
        }
        if (nodeCache[0] == null) nodeCache[0] = mapper.readTree(resp);
        String path = expr.startsWith("$.") ? expr : "$." + expr;
        String v = jsonPath(nodeCache[0], path);
        return v == null || v.isBlank() ? null : new Object[]{v, "响应体 " + path};
    }

    /** 按名字取当前 Cookie 罐里的值（第 47 轮：有的系统把会话密钥放在 Cookie 里，如 randomCode） */
    private static String cookieValue(java.net.CookieManager jar, String name) {
        for (java.net.HttpCookie c : jar.getCookieStore().getCookies()) {
            if (c.getName().equals(name)) return c.getValue();
        }
        return null;
    }

    /** 步骤声明 success_field 时，把业务失败码（如 KJDP 的 IRETCODE）翻译成明确报错，而不是让后面请求静默 401 */
    @SuppressWarnings("unchecked")
    private void checkLoginStep(Map<String, Object> step, int idx, String resp) {
        Object sf = step.get("success_field");
        if (sf == null) return;
        JsonNode node;
        try {
            node = mapper.readTree(resp);
        } catch (Exception e) {
            throw new IllegalStateException("登录流第 " + idx + " 步响应不是 JSON（服务地址不对或未启动？）："
                    + (resp == null ? "空响应" : resp.substring(0, Math.min(200, resp.length()))));
        }
        String field = String.valueOf(sf);
        String got = node.path(field).asText(null);
        String want = String.valueOf(step.getOrDefault("success_value", "0"));
        if (got == null) {
            throw new IllegalStateException("登录流第 " + idx + " 步响应里没有 " + field + " 字段，无法判定登录结果");
        }
        if (want.equals(got)) return;
        String detail = "";
        for (String mk : List.of("ERRMSG", "errmsg", "RETMSG", "retmsg", "MSG", "msg", "ERROR")) {
            String v = node.path(mk).asText("");
            if (!v.isBlank()) {
                detail = "：" + v;
                break;
            }
        }
        String hint = step.get("code_messages") instanceof Map<?, ?> cm
                ? String.valueOf(((Map<String, Object>) cm).getOrDefault(got, "")) : "";
        throw new IllegalStateException("登录流第 " + idx + " 步失败（" + field + "=" + got + detail
                + (hint.isBlank() ? "" : "）· " + hint));
    }

    /** 生成 cURL 命令（不执行），便于贴给开发排查（04-补齐 §2.1） */
    public String buildCurl(long interfaceId, String baseUrl, Map<String, Object> params) {
        return buildCurl(interfaceId, baseUrl, params, null);
    }

    /** 生成 cURL 命令（不执行）。路径与真发同源：同一张卡在同一个环境下，复制出来的必须就是会发出去的那个 */
    @SuppressWarnings("unchecked")
    public String buildCurl(long interfaceId, String baseUrl, Map<String, Object> params, String pathMode) {
        try {
            Map<String, Object> itf = jdbc.queryForMap("SELECT * FROM interfaces WHERE id=?", interfaceId);
            String method = String.valueOf(itf.getOrDefault("http_method", "GET"));
            Map<String, Object> p = params == null ? Map.of() : params;
            Map<String, Object> pathParams = (Map<String, Object>) p.getOrDefault("path", Map.of());
            com.preflight.learn.GatewayRoutingService.Answer route = routeFor(itf, baseUrl, false, pathMode);
            String pathTpl = route == null ? String.valueOf(itf.get("path")) : route.path();
            for (Map.Entry<String, Object> en : pathParams.entrySet()) {
                pathTpl = pathTpl.replace("{" + en.getKey() + "}", String.valueOf(en.getValue()));
            }
            String url = composeCallUrl(baseUrl, pathTpl);
            Map<String, Object> query = (Map<String, Object>) p.getOrDefault("query", Map.of());
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, Object> en : query.entrySet()) {
                if (en.getValue() == null || String.valueOf(en.getValue()).isEmpty()) continue;
                qs.append(qs.isEmpty() ? "?" : "&").append(URLEncoder.encode(en.getKey(), StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(String.valueOf(en.getValue()), StandardCharsets.UTF_8));
            }
            url = url + qs;
            Object body = p.get("body");
            String wire = declaredWire(itf);
            Object sendBody = wire == null || body == null || body instanceof String
                    ? body : mapper.writeValueAsString(body);
            String verb = usableMethod(method, sendBody);
            boolean hasBody = sendBody != null && !(sendBody instanceof Map && ((Map<?, ?>) sendBody).isEmpty())
                    && !verb.equals("GET");
            StringBuilder curl = new StringBuilder();
            String rl = routeLine(route);
            if (!rl.isBlank()) curl.append("# 发出去的地址与卡片学到的内部路径不是同一个").append(rl.substring(1)).append('\n');
            curl.append("curl -X ").append(verb).append(" '").append(url).append("'");
            curl.append(" -H 'Content-Type: ")
                    .append(wire == null ? "application/json; charset=utf-8"
                            : wire + (wire.toLowerCase().contains("charset") ? "" : "; charset=utf-8"))
                    .append("'");
            if (hasBody) {
                curl.append(" --data '").append(String.valueOf(sendBody).replace("'", "'\\''")).append("'");
            }
            return curl.toString();
        } catch (Exception e) {
            return "# 生成失败: " + e.getMessage();
        }
    }

    /** 简化 JSON 路径：$.data.token */
    private String jsonPath(JsonNode node, String path) {
        String p = path.startsWith("$.") ? path.substring(2) : path;
        JsonNode cur = node;
        for (String seg : p.split("\\.")) {
            if (cur == null) return null;
            cur = cur.path(seg);
        }
        return cur.isMissingNode() || cur.isNull() ? null : cur.asText();
    }

    private String substitute(String tpl, Map<String, Object> vars) {
        String out = tpl;
        for (Map.Entry<String, Object> en : vars.entrySet()) {
            out = out.replace("{{" + en.getKey() + "}}", en.getValue() == null ? "" : String.valueOf(en.getValue()));
        }
        return out;
    }

    /** 卡片声明的线路 Content-Type（来自 params_schema.dispatch.contentType，学习时由分发适配器写入） */
    @SuppressWarnings("unchecked")
    private String declaredWire(Map<String, Object> itf) {
        Object ps = itf.get("params_schema");
        if (ps == null) return null;
        try {
            Object d = mapper.readValue(String.valueOf(ps), Map.class).get("dispatch");
            if (d instanceof Map<?, ?> dm && dm.get("contentType") != null) {
                String ct = String.valueOf(dm.get("contentType")).trim();
                if (!ct.isEmpty()) return ct;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** 卡片动词可能学自 Servlet 通配映射（ANY）或手写非标准值：发包要落到具体动词，带体走 POST，空体走 GET */
    private String usableMethod(String declared, Object body) {
        String m = declared == null ? "GET" : declared.trim().toUpperCase();
        switch (m) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE" -> {
                return m;
            }
            default -> {
            }
        }
        boolean hasBody = body instanceof String s ? !s.isBlank()
                : body != null && !(body instanceof Map<?, ?> mm && mm.isEmpty());
        return hasBody ? "POST" : "GET";
    }

    private String redact(Map<String, String> headers) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        if (copy.containsKey("Authorization")) copy.put("Authorization", "***");
        if (copy.containsKey("Cookie")) copy.put("Cookie", "***");
        return copy.toString();
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 20000 ? s.substring(0, 20000) + "...[截断]" : s;
    }
}
