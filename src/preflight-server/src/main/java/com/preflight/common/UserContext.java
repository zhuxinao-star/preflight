package com.preflight.common;

/** 当前登录用户上下文（拦截器注入，审计与权限使用） */
public final class UserContext {

    public record User(String username, String role) {}

    private static final ThreadLocal<User> CURRENT = new ThreadLocal<>();

    private UserContext() {}

    public static void set(User u) { CURRENT.set(u); }

    public static User get() { return CURRENT.get(); }

    public static String username() {
        User u = CURRENT.get();
        return u == null ? "anonymous" : u.username();
    }

    public static String role() {
        User u = CURRENT.get();
        return u == null ? "viewer" : u.role();
    }

    public static boolean isAdmin() { return "admin".equals(role()); }

    public static void clear() { CURRENT.remove(); }
}
