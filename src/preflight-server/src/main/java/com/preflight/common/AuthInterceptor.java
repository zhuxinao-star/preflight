package com.preflight.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 轻量 RBAC（04 §3.6 / R4 返工）：全路由角色矩阵，不再只靠 admin 前缀黑名单。
 * viewer=只读（仅 GET；例外：/api/db-test 的单条 SELECT，见 Q-E）；tester=执行/标记/学习确认/生成（白名单写路由）；admin=全部。
 * /api/auth/login|setup|status|logout 开放；其余 /api/** 一律需登录（含 /api/auth/users）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 开放路由（未登录可访问） */
    private static final List<String> OPEN = List.of(
            "/api/auth/login", "/api/auth/setup", "/api/auth/status", "/api/auth/logout");

    /** tester 允许的非 GET 路由（admin 天然全量）。viewer 的非 GET 一律拒绝，仅 /api/db-test 例外（控制器限制为单条 SELECT） */
    private static final List<Pattern> TESTER_WRITE = List.of(
            Pattern.compile("^/api/learn$"),
            Pattern.compile("^/api/dispatch/rebuild$"),
            Pattern.compile("^/api/repos/\\d+/sync$"),
            Pattern.compile("^/api/repos/sync-all$"),
            Pattern.compile("^/api/commits/\\d+/(summarize|generate-cases|test-status|run-regression)$"),
            // 按需补差异快照：只读仓库 + 写派生缓存，测试员每天都要点
            Pattern.compile("^/api/file-changes/\\d+/fetch-patch$"),
            Pattern.compile("^/api/cases(/.*)?$"),
            Pattern.compile("^/api/test-runs.*$"),
            Pattern.compile("^/api/db-test$"),
            Pattern.compile("^/api/interfaces$"),
            Pattern.compile("^/api/interfaces/\\d+$"),
            Pattern.compile("^/api/interfaces/confirm$"),
            Pattern.compile("^/api/interfaces/confirm-by-confidence$"),
            Pattern.compile("^/api/interfaces/\\d+/(hooks|curl)$"),
            Pattern.compile("^/api/db-metadata(/pull)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^/api/db-objects/\\d+$"),
            Pattern.compile("^/api/db-objects/\\d+/compose$"),
            // 候选值：只读 SELECT 取值（连接 setReadOnly + 写死语句 + 行数上限），tester/viewer 都要能看
            Pattern.compile("^/api/db-objects/\\d+/candidates$"),
            Pattern.compile("^/api/db-objects/confirm$"),
            Pattern.compile("^/api/findings.*$"),
            Pattern.compile("^/api/search.*$"));

    private final LoginTokens tokens;

    public AuthInterceptor(LoginTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String uri = request.getRequestURI();
        if (OPEN.stream().anyMatch(uri::startsWith)) return true;

        HttpSession session = request.getSession(false);
        Object u = session == null ? null : session.getAttribute("pf_user");
        if (u == null) {
            // 方案 A：内存会话没了（重启/超时）先拿持久 cookie 续期，续不上才算未登录
            UserContext.User renewed = tokens.redeem(request, response);
            if (renewed != null) {
                request.getSession(true).setAttribute("pf_user", renewed);
                u = renewed;
            }
        }
        if (u == null) {
            return deny(response, 401, "未登录");
        }
        UserContext.set((UserContext.User) u);

        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            // /api/auth/users 列表仅 admin（其余 GET 全员可看，敏感值控制器层掩码）
            if (uri.equals("/api/auth/users") && !UserContext.isAdmin()) {
                return deny(response, 403, "需要管理员权限");
            }
            // 知识包含凭据选项 → 导出仅 admin（04 §3.6）
            if (uri.startsWith("/api/knowledge/export") && !UserContext.isAdmin()) {
                return deny(response, 403, "知识包导出需要管理员权限");
            }
            return true;
        }

        if (UserContext.isAdmin()) return true;
        if ("tester".equals(UserContext.role())) {
            boolean allowed = TESTER_WRITE.stream().anyMatch(p -> p.matcher(uri).matches());
            if (allowed) return true;
            return deny(response, 403, "当前角色无权执行该操作");
        }
        // viewer：仅放行 SQL 直测通道（Q-E 拍板）+ 它的纯推导前置（按参数拼装只算不写，执行仍受 db-test 只读约束）
        if ("/api/db-test".equals(uri) && "POST".equals(method)) return true;
        if (uri.matches("^/api/db-objects/\\d+/compose$") && "POST".equals(method)) return true;
        // 候选值同样是只读通道：服务端写死 SELECT DISTINCT + 连接 setReadOnly，翻不出别的动作
        if (uri.matches("^/api/db-objects/\\d+/candidates$") && "POST".equals(method)) return true;
        return deny(response, 403, "只读账号无权执行该操作");
    }

    private boolean deny(HttpServletResponse response, int status, String msg) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"ok\":false,\"error\":\"" + msg + "\"}");
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
