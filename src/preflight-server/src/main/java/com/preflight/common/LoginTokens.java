package com.preflight.common;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 持久登录凭据（第 15 轮方案 A）：Tomcat 内存会话一重启就全丢，升级部署等于把所有人踢下线。
 * 登录时签发一枚随机 token（明文只进 HttpOnly cookie，库里只留 SHA-256），会话没了就拿 cookie 续期；
 * 续期是幂等的滑动续命（不轮换 token 值），登出才作废。
 */
@Component
public class LoginTokens {

    private static final Logger log = LoggerFactory.getLogger(LoginTokens.class);

    public static final String COOKIE = "PF_AUTH";
    private static final int VALID_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public LoginTokens(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登录成功后签发凭据并写 cookie */
    public void issue(HttpServletResponse response, String username, String role) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("DELETE FROM login_tokens WHERE expires_at < datetime('now','localtime')");
        jdbc.update("INSERT INTO login_tokens(token_hash, username, role, expires_at) "
                        + "VALUES (?,?,?, datetime('now','localtime','+7 days'))",
                sha(token), username, role);
        writeCookie(response, token);
    }

    /**
     * 会话丢失时按 cookie 换回身份；凭据无效或过期返回 null。
     * 必须幂等：同一枚 cookie 会被并发请求（多标签页、一次页面加载里的多个 fetch）同时拿来续期，
     * 任何"用掉即作废"的实现都等于随机把其中几个请求判成未登录 —— 这就是"还是要登录"的真因。
     */
    public UserContext.User redeem(HttpServletRequest request, HttpServletResponse response) {
        String token = readCookie(request);
        if (token == null || token.isBlank()) return null;
        String hash = sha(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT username, role FROM login_tokens WHERE token_hash=? "
                        + "AND expires_at >= datetime('now','localtime')", hash);
        if (rows.isEmpty()) {
            log.info("持久凭据续期失败（无此 token 或已过期）: {}", request.getRequestURI());
            return null;
        }
        // 滑动续期：剩余不足 3 天才改写，且值不变（不换 token），并发请求互不影响
        Integer slid = jdbc.update("UPDATE login_tokens SET expires_at=datetime('now','localtime','+7 days') "
                + "WHERE token_hash=? AND expires_at < datetime('now','localtime','+3 days')", hash);
        if (slid != null && slid > 0) writeCookie(response, token);
        Map<String, Object> row = rows.get(0);
        return new UserContext.User((String) row.get("username"), (String) row.get("role"));
    }

    /** 登出：作废本次凭据并清 cookie */
    public void revoke(HttpServletRequest request, HttpServletResponse response) {
        String token = readCookie(request);
        if (token != null && !token.isBlank()) {
            jdbc.update("DELETE FROM login_tokens WHERE token_hash=?", sha(token));
        }
        writeCookie(response, "");
    }

    private void writeCookie(HttpServletResponse response, String value) {
        Cookie c = new Cookie(COOKIE, value);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(value.isEmpty() ? 0 : VALID_DAYS * 86400);
        c.setAttribute("SameSite", "Lax");
        response.addCookie(c);
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static String sha(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
