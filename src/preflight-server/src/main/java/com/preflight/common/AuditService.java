package com.preflight.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 审计留痕：谁在何时对什么执行了什么。M0 先统一 actor=system。 */
@Component
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String action, String target, String detail) {
        jdbc.update("INSERT INTO audit_logs(actor, action, target, detail) VALUES (?, ?, ?, ?)",
                UserContext.username(), action, target, detail);
    }
}
