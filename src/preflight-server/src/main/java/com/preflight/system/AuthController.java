package com.preflight.system;

import com.preflight.common.LoginTokens;
import com.preflight.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证与用户管理（D1）：PBKDF2 口令哈希、会话 Cookie。
 * 首次启动 users 为空时进入 setup 流程创建 admin。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final LoginTokens tokens;

    public AuthController(JdbcTemplate jdbc, LoginTokens tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    public record UserRow(long id, String username, String passwordHash, String role) {}

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request, HttpServletResponse response) {
        long users = count("SELECT COUNT(*) FROM users");
        HttpSession session = request.getSession(false);
        Object u = session == null ? null : session.getAttribute("pf_user");
        if (u == null) {
            // 开放路由也要认持久凭据，否则刷新页面时先被判成未登录
            UserContext.User renewed = tokens.redeem(request, response);
            if (renewed != null) {
                request.getSession(true).setAttribute("pf_user", renewed);
                u = renewed;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needsSetup", users == 0);
        out.put("loggedIn", u != null);
        if (u != null) {
            UserContext.User uc = (UserContext.User) u;
            out.put("username", uc.username());
            out.put("role", uc.role());
        }
        out.put("ok", true);
        return out;
    }

    /** 首次初始化：仅 users 为空时可用，创建管理员 */
    @PostMapping("/setup")
    public Map<String, Object> setup(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        if (count("SELECT COUNT(*) FROM users") > 0) {
            return Map.of("ok", false, "error", "系统已初始化，请直接登录");
        }
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            return Map.of("ok", false, "error", "用户名不能为空且密码至少 6 位");
        }
        jdbc.update("INSERT INTO users(username, password_hash, role) VALUES (?,?, 'admin')",
                username, hash(password));
        return login(body, request, response);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM users WHERE username=?", body.getOrDefault("username", ""));
        if (rows.isEmpty()) return Map.of("ok", false, "error", "用户或密码错误");
        Map<String, Object> row = rows.get(0);
        if (!verify(body.getOrDefault("password", ""), (String) row.get("password_hash"))) {
            return Map.of("ok", false, "error", "用户或密码错误");
        }
        UserContext.User u = new UserContext.User((String) row.get("username"), (String) row.get("role"));
        HttpSession session = request.getSession(true);
        session.setAttribute("pf_user", u);
        tokens.issue(response, u.username(), u.role());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("username", u.username());
        out.put("role", u.role());
        return out;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        tokens.revoke(request, response);
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return Map.of("ok", true);
    }

    // ---------- 用户管理（admin） ----------
    @GetMapping("/users")
    public Map<String, Object> users() {
        return Map.of("ok", true, "users", jdbc.queryForList(
                "SELECT id, username, role, created_at FROM users ORDER BY id"));
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, String> body) {
        if (!UserContext.isAdmin()) return Map.of("ok", false, "error", "需要管理员权限");
        String role = body.getOrDefault("role", "tester");
        if (!List.of("admin", "tester", "viewer").contains(role)) return Map.of("ok", false, "error", "非法角色");
        String password = body.get("password");
        if (password == null || password.length() < 6) return Map.of("ok", false, "error", "密码至少 6 位");
        jdbc.update("INSERT INTO users(username, password_hash, role) VALUES (?,?,?)",
                body.get("username"), hash(password), role);
        return Map.of("ok", true);
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable long id) {
        if (!UserContext.isAdmin()) return Map.of("ok", false, "error", "需要管理员权限");
        jdbc.update("DELETE FROM users WHERE id=?", id);
        return Map.of("ok", true);
    }

    // ---------- PBKDF2 ----------
    static String hash(String password) {
        try {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            byte[] dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return "pbkdf2$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(dk);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            byte[] dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(dk).equals(parts[2]);
        } catch (Exception e) {
            return false;
        }
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }
}
