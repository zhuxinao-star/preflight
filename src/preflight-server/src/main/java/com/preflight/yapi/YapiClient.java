package com.preflight.yapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * YApi 的只读/写入 HTTP 通道（第卅四轮）。
 *
 * <p>鉴权走 Cookie：{@code _yapi_token=<JWT>; _yapi_uid=<uid>}（uid 从 JWT 载荷里解出，不让用户填两遍）。
 * <p>返回一律带 errcode/errmsg 语义：<b>报错只转述 YApi 原话</b>，不编造行号或原因；连不上时把传输层异常原样带出。
 * <p>token 绝不写日志、绝不进任何返回体。
 */
@Service
public class YapiClient {

    /** 一次 YApi 调用的结果：data 是响应里的 data 节点（可能是对象或数组） */
    public record Resp(boolean ok, int errcode, String errmsg, JsonNode data, String transportError) {
        public static Resp fail(String msg) {
            return new Resp(false, -1, msg, null, msg);
        }
    }

    /**
     * 账号密码登录的结果。token 只在内存里往上传，绝不进日志、绝不进任何返回体；
     * 界面要展示时只给 uid 与过期时间（见 {@code YapiService#checkAuth}）。
     *
     * <p>{@code problem} 是给人看的<b>分诊结论</b>，不是错误码：{@code address}＝这个地址不是 YApi 的
     * API 前缀（请求打到页面路由/静态页，凭据根本没送到校验）；{@code transport}＝连不上；
     * {@code rejected}＝YApi 亲口拒了这次登录（邮箱/密码问题）；{@code shape}＝说登录成功了却拿不到 token。
     * 把这四件混成一句"核对邮箱/密码"会把人往错方向带（第卅九轮用户就是这么被卡住的）。
     */
    public record Login(boolean ok, String token, String uid, long exp, int errcode, String errmsg,
                        String transportError, String problem) {
        public static Login fail(String problem, String msg) {
            return new Login(false, "", "", 0, -1, msg, msg, problem);
        }
    }

    /**
     * 实例地址体检（纯读、不带任何凭据）。YApi 的 API 根对未知路径回 JSON（哪怕 errcode 是"请登录"），
     * 而把浏览器地址栏里的页面地址当 API 前缀用时，回的是整页 HTML 或 {@code 404 Not Found} 纯文本
     * ——所以"回的是不是 JSON"就是地址对不对的判据。
     *
     * <p>{@code kind} 是给人分诊用的四档，不能只有"通/不通"一个布尔：把"连不上"也说成"这是页面地址"，
     * 就等于凭空指错方向（第四十一轮顺带修掉的正是这个）。
     * {@code notjson}＝回了 HTML/纯文本，<b>确知</b>不是 API 前缀；{@code transport}＝此刻连不上，判不出地址形态；
     * {@code shape}＝有响应但认不出是 YApi 接口；{@code json}＝这就是 API 根。
     */
    public record Probe(boolean apiRoot, String kind, String requestUrl, int status, String errcode, String note,
                       Suggest suggest) {
        Probe(boolean apiRoot, String kind, String requestUrl, int status, String errcode, String note) {
            this(apiRoot, kind, requestUrl, status, errcode, note, null);
        }
    }

    /**
     * 试出来的那个"应该就是这儿"：{@code baseUrl} 是可以直接填进那一格的地址，
     * {@code requestUrl}/{@code note} 是它自己的体检证据（试了哪个完整 URL、YApi 原话）。
     * 只是建议，绝不自动落库——用户点【↻ 用这个地址重测】也只改界面上那一格，保存仍是用户自己的动作。
     */
    public record Suggest(String baseUrl, String requestUrl, String note) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient hc = com.preflight.common.Http1.builder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NEVER)   // 跳到登录页 = token 失效，不能当成成功
            .build();

    /** 只读 GET（列项目、列分类、查已有接口都走这里） */
    public Resp get(String baseUrl, String token, String uid, String apiPath, Map<String, String> query) {
        StringBuilder url = new StringBuilder(trimBase(baseUrl)).append(apiPath);
        if (query != null && !query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(enc(e.getKey())).append('=').append(enc(e.getValue() == null ? "" : e.getValue()));
            }
        }
        return call(url.toString(), "GET", token, uid, null);
    }

    /** 写操作 POST：只有 /interface/add 与 /interface/up 会用，调用方必须先拿到用户逐条确认 */
    public Resp post(String baseUrl, String token, String uid, String apiPath, Map<String, Object> body) {
        try {
            return call(trimBase(baseUrl) + apiPath, "POST", token, uid, mapper.writeValueAsString(body));
        } catch (Exception e) {
            return Resp.fail("请求体序列化失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /**
     * 用账号密码换一次会话 token（第卅六轮）。协议照 YApi 官方源码：
     * {@code POST {base}/user/login} + {@code {"email":…,"password":…}}，服务端按每个用户自己的
     * passsalt 比对，成功后 JWT <b>只写在</b> {@code Set-Cookie: _yapi_token=}（httpOnly，约 7 天），
     * 响应体里没有 token——所以必须自己接 cookie（这也是 HttpClient 关掉自动重定向的原因）。
     *
     * <p>失败只转述 YApi 原话，不猜原因、不回显密码。
     */
    public Login login(String baseUrl, String email, String password) {
        String url = trimBase(baseUrl) + "/user/login";
        String body;
        try {
            body = mapper.writeValueAsString(Map.of(
                    "email", email == null ? "" : email,
                    "password", password == null ? "" : password));
        } catch (Exception e) {
            return Login.fail("transport", "登录请求体拼不出来：" + e.getClass().getSimpleName());
        }
        HttpResponse<String> r;
        try {
            r = hc.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Login.fail("transport", "连不上 YApi（" + e.getClass().getSimpleName() + "）：" + rootMsg(e)
                    + "（请求的完整地址 " + url + "）");
        }
        JsonNode node;
        try {
            node = mapper.readTree(r.body());
        } catch (Exception e) {
            // 回的不是 JSON＝这个地址后面拼不出 YApi 的接口：多半把浏览器地址栏里的页面地址当成了 API 前缀
            return Login.fail("address", "请求 " + url + " 回的不是 YApi 接口的 JSON（HTTP " + r.statusCode()
                    + "）：" + brief(r.body()));
        }
        int errcode = node.path("errcode").asInt(0);
        String errmsg = node.path("errmsg").asText("");
        if (errcode != 0 || (r.statusCode() < 200 || r.statusCode() >= 300)) {
            return Login.fail("rejected", "YApi 拒了这次登录（HTTP " + r.statusCode() + "，errcode " + errcode + "）："
                    + (errmsg.isBlank() ? brief(r.body()) : errmsg));
        }
        String token = "", uid = "";
        for (String c : r.headers().allValues("set-cookie")) {
            String[] pair = c.split(";", 2)[0].split("=", 2);
            if (pair.length != 2) continue;
            String k = pair[0].trim(), v = pair[1].trim();
            if ("_yapi_token".equals(k)) token = v;
            else if ("_yapi_uid".equals(k)) uid = v;
        }
        if (token.isBlank()) {
            return Login.fail("shape", "YApi 说登录成功了，但响应里没有 _yapi_token cookie——这台实例的登录返回形态"
                    + "跟官方不一样，认不出 token；先用「YApi token」那种凭据手动粘一条");
        }
        Map<String, Object> info = inspectToken(token);
        String jwtUid = String.valueOf(info.getOrDefault("uid", ""));
        if (uid.isBlank()) uid = jwtUid;
        long exp = info.get("exp") instanceof Number n ? n.longValue() : 0;
        return new Login(true, token, uid, exp, 0, null, null, "");
    }

    /**
     * 实例地址体检：GET {@code {base}/interface/list}（不带凭据，YApi 会回"请登录"的 JSON）。
     * 回 JSON＝这就是 API 根；回 HTML 或纯文本 404＝填的是页面地址，登录与列项目都不会通。
     *
     * <p>第四十三轮补：这一格最常见的错法只差一段 {@code /api}（浏览器地址栏复制过来必然如此）。
     * 与其让人去猜"是不是凭据问题"，这里就地把候选前缀<b>只读</b>试一遍——试通了就把那个地址随建议带出去，
     * 采不采用仍由人点。连不上（{@code transport}）时不试：换前缀敲的还是同一台机器，试了也是白等。
     */
    public Probe probeBase(String baseUrl) {
        String base;
        try {
            base = trimBase(baseUrl);
        } catch (Exception e) {
            // 地址一格都没填：这既不是"连不上"也不是"填成了页面地址"，别套那两种说法，更不许拿内置默认去替用户填
            return new Probe(false, "blank", "", -1, "", e.getMessage());
        }
        Probe p = probeOne(base + "/interface/list");
        if (p.apiRoot() || "transport".equals(p.kind())) return p;
        for (String cand : candidates(base)) {
            Probe s = probeOne(cand + "/interface/list");
            if (!s.apiRoot()) continue;
            return new Probe(p.apiRoot(), p.kind(), p.requestUrl(), p.status(), p.errcode(), p.note(),
                    new Suggest(cand, s.requestUrl(), s.note()));
        }
        return p;
    }

    /** 候选地址：最多两条，都只加 {@code /api}，不猜端口、不猜子路径（猜错了只会把人带更远）；与刚试过的那个相同的不重复问 */
    static List<String> candidates(String base) {
        List<String> out = new ArrayList<>();
        try {
            URI u = URI.create(base);
            if (u.getScheme() == null || u.getHost() == null) return out;
            String origin = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            add(out, base, origin + "/api");
            if (!base.equals(origin)) add(out, base, base + "/api");
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static void add(List<String> list, String base, String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.equals(base) || list.contains(s) || list.size() >= 2) return;
        list.add(s);
    }

    private Probe probeOne(String url) {
        HttpResponse<String> r;
        try {
            r = hc.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new Probe(false, "transport", url, -1, "", "连不上（" + e.getClass().getSimpleName() + "）：" + rootMsg(e));
        }
        try {
            JsonNode node = mapper.readTree(r.body());
            if (node.has("errcode")) {
                return new Probe(true, "json", url, r.statusCode(), String.valueOf(node.path("errcode").asInt()),
                        "YApi 接口有响应（errcode " + node.path("errcode").asInt()
                                + (node.path("errmsg").isMissingNode() ? "" : "，" + node.path("errmsg").asText()) + "）");
            }
            return new Probe(false, "shape", url, r.statusCode(), "", "回的是 JSON 但没有 errcode，认不出是 YApi 接口");
        } catch (Exception e) {
            String ct = r.headers().firstValue("content-type").orElse("未知类型");
            String body = r.body() == null ? "" : r.body().replaceAll("\\s+", " ").trim();
            // 页面地址回的正是一整个 HTML：markup 本身没有信息量，说清"这是个页面"比贴 200 字标签有用
            String what = body.startsWith("<") ? "是一个 HTML 页面（YApi 的前端路由，不是接口）" : "不是 JSON";
            return new Probe(false, "notjson", url, r.statusCode(), "",
                    "回的不是 JSON（" + ct + "）：" + what + "，开头「" + brief(body, 40) + "」");
        }
    }

    private Resp call(String url, String method, String token, String uid, String jsonBody) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Cookie", "_yapi_token=" + (token == null ? "" : token)
                        + "; _yapi_uid=" + (uid == null ? "" : uid))
                .header("Content-Type", "application/json; charset=utf-8");
        b.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        HttpResponse<String> r;
        try {
            r = hc.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 连不上/超时/被拦截：把底层原话带出来，界面才有可操作的信息
            return Resp.fail("连不上 YApi（" + e.getClass().getSimpleName() + "）：" + rootMsg(e));
        }
        int code = r.statusCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String loc = r.headers().firstValue("Location").orElse("");
            return Resp.fail("YApi 把请求重定向到 " + loc + "（状态 " + code + "）——通常是会话已失效");
        }
        if (code < 200 || code >= 300) {
            return Resp.fail("YApi 返回 HTTP " + code + "（" + url + "）：" + brief(r.body()));
        }
        JsonNode node;
        try {
            node = mapper.readTree(r.body());
        } catch (Exception e) {
            // 回 HTML/纯文本＝这个地址不是 API 前缀（多半填成了浏览器地址栏里的页面地址）
            return Resp.fail("这个地址回的不是 YApi 接口的 JSON（" + url + "，HTTP " + code + "）：" + brief(r.body())
                    + "。「YApi 实例地址」要填到 /api 为止，例 http://内网地址:3000/api");
        }
        int errcode = node.path("errcode").asInt(0);
        String errmsg = node.path("errmsg").asText("");
        // 老版 YApi 有些端点不包 errcode，直接给数据本身
        JsonNode data = node.has("data") ? node.get("data") : (errcode == 0 ? node : null);
        return new Resp(errcode == 0, errcode, errmsg.isEmpty() ? null : errmsg, data, null);
    }

    /**
     * 地址这一格的唯一收口：去掉尾巴斜杠，<b>不留内置默认</b>。
     * 空地址以前偷偷换成一台真实内网实例，等于把用户输入的凭据发往他从来没填过的机器——
     * 现在直说没填，由界面把那一格补上（默认值仍由 {@code YapiService.effectiveBase} 一层层给，不在这儿）。
     */
    private static String trimBase(String baseUrl) {
        String s = baseUrl == null ? "" : baseUrl.trim();
        if (s.isEmpty()) throw new IllegalStateException("YApi 实例地址还没填：要填到 API 前缀为止，例 http://内网地址:3000/api");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String brief(String body) {
        return brief(body, 200);
    }

    /** 截断是"看一眼原话"用的，不是取证用的，所以默认 200 字；页面地址那类只要开头 40 字 */
    private static String brief(String body, int cap) {
        String s = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return s.length() > cap ? s.substring(0, cap) + "…" : s;
    }

    private static String rootMsg(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }

    /** JWT 载荷解析（只读 uid/exp 两个字段），供凭据校验用；解不出就当凭据有问题 */
    public Map<String, Object> inspectToken(String token) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("uid", "");
        out.put("exp", 0L);
        out.put("error", "");
        if (token == null || token.isBlank()) {
            out.put("error", "凭据里没有内容");
            return out;
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length < 2) {
            out.put("error", "这不是 JWT 形式的 YApi token（应为 xxx.yyy.zzz）");
            return out;
        }
        try {
            String payload = new String(java.util.Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);
            JsonNode n = mapper.readTree(payload);
            out.put("uid", n.path("uid").asText(""));
            out.put("exp", n.path("exp").asLong(0));
            if (String.valueOf(out.get("uid")).isBlank()) {
                out.put("error", "token 载荷里没有 uid，YApi 侧的 Cookie 鉴权需要它");
            }
            return out;
        } catch (Exception e) {
            out.put("error", "token 载荷解不开：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return out;
        }
    }

    private static String pad(String s) {
        int r = s.length() % 4;
        return r == 0 ? s : s + "=".repeat(4 - r);
    }
}
