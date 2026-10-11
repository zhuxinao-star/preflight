package com.preflight.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置模拟被测服务：用于验证 HTTP 执行器 / 自动登录链路，也方便演示。
 * 登录 /mock/login 返回 token；/mock/echo 校验 Authorization 头。
 */
@RestController
@RequestMapping("/mock")
public class MockController {

    private final ObjectMapper om;

    public MockController(ObjectMapper om) {
        this.om = om;
    }
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, Object> body) {
        return Map.of("code", 0, "data", Map.of("accessToken", "mock-token-" + System.currentTimeMillis()));
    }

    @GetMapping("/echo")
    public ResponseEntity<Map<String, Object>> echoGet(@RequestHeader(value = "Authorization", required = false) String auth) {
        return echo(auth, Map.of("msg", "echo-ok"));
    }

    @PostMapping("/echo")
    public ResponseEntity<Map<String, Object>> echoPost(@RequestHeader(value = "Authorization", required = false) String auth,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        return echo(auth, Map.of("msg", "echo-ok", "received", body == null ? Map.of() : body));
    }

    private ResponseEntity<Map<String, Object>> echo(String auth, Map<String, Object> payload) {
        if (auth == null || !auth.contains("mock-token")) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "msg", "未登录或 token 无效"));
        }
        return ResponseEntity.ok(payload);
    }

    // ---------- KJDP 线路自检（第 10 轮）----------
    // work_app 本机跑不起来，登录编码与分发报文这条链路只能由这个"服务端口径"的替身来证明：
    // 它按前端同款算法解密度报文、认 Cookie、按 text/xml 收 JSON 信封。

    private static final String MOCK_SESSION = "MOCK-KJDP-SESSION";
    private static final Pattern LOGIN_ROW = Pattern.compile(
            "\\{\\s*USER_CODE:\"([^\"]*)\"\\s*,\\s*TRD_PWD:\"([^\"]*)\"\\s*,\\s*USER_QRHM:\"([^\"]*)\"\\s*,\\s*validateCode:\"([^\"]*)\"\\s*\\}");

    @PostMapping("/redis_validate_login")
    public ResponseEntity<String> kjdpValidateLogin(@RequestBody String body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("msg", "0");
        resp.put("check", decodeLogin(body));
        return xml(toJson(resp));
    }

    @PostMapping("/redis_login")
    public ResponseEntity<String> kjdpLogin(@RequestBody String body) {
        Map<String, Object> chk = decodeLogin(body);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(chk.get("shapeOk"))) {
            resp.put("IRETCODE", "0");
            resp.put("USER_NAME", "模拟用户");
        } else {
            resp.put("IRETCODE", "-6");
            resp.put("ERRMSG", "报文解不开或字段顺序不对（应为 USER_CODE/TRD_PWD/USER_QRHM/validateCode）");
        }
        resp.put("check", chk);
        return ResponseEntity.ok()
                .header("Set-Cookie", "JSESSIONID=" + MOCK_SESSION + "; Path=/; HttpOnly")
                .contentType(MediaType.valueOf("text/xml;charset=UTF-8"))
                .body(toJson(resp));
    }

    @PostMapping("/redis_ajax")
    public ResponseEntity<String> kjdpAjax(@RequestHeader(value = "Cookie", required = false) String cookie,
                                           @RequestHeader(value = "Content-Type", required = false) String ct,
                                           @RequestBody String body) {
        if (cookie == null || !cookie.contains(MOCK_SESSION)) {
            return xml("{\"RESPONSES\":[{\"RESP_COMM_DATA\":[{\"IRETCODE\":\"-9\",\"ERRMSG\":\"缺少登录 Cookie\"}]}]}");
        }
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("contentType", ct);
        echo.put("cookieSeen", true);
        try {
            echo.put("envelope", om.readTree(body));
        } catch (Exception e) {
            echo.put("parseError", e.getMessage());
            echo.put("raw", body.length() > 400 ? body.substring(0, 400) : body);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("IRETCODE", "0");
        data.put("ECHO", echo);
        return xml("{\"RESPONSES\":[{\"RESP_COMM_DATA\":[" + toJson(data) + "]}]}");
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 按 des.js 的口径解开登录报文，核对是不是前端那一串字段 */
    private Map<String, Object> decodeLogin(String hex) {
        Map<String, Object> chk = new LinkedHashMap<>();
        chk.put("hexLen", hex == null ? 0 : hex.length());
        String xmlText = "";
        try {
            xmlText = KjdpWireCodec.desDecodeHex(hex == null ? "" : hex.trim(), KjdpWireCodec.DEFAULT_KEY);
        } catch (Exception e) {
            chk.put("decodeError", e.getMessage());
        }
        chk.put("xml", xmlText);
        Matcher m = LOGIN_ROW.matcher(xmlText);
        chk.put("shapeOk", m.find());
        return chk;
    }

    private ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().contentType(MediaType.valueOf("text/xml;charset=UTF-8")).body(body);
    }
}
