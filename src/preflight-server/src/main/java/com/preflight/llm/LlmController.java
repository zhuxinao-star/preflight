package com.preflight.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** LLM 供应商页面配置 API（多供应商、用途路由、连通测试、手动对话）。 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmProviderService providerService;
    private final LlmGatewayService gateway;
    private final JdbcTemplate jdbc;

    private static final long PING_TTL_MS = 60_000;
    private volatile long pingedAt = 0;
    private volatile boolean online = false;

    public LlmController(LlmProviderService providerService, LlmGatewayService gateway, JdbcTemplate jdbc) {
        this.providerService = providerService;
        this.gateway = gateway;
        this.jdbc = jdbc;
    }

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        return Map.of("ok", true, "providers", providerService.list());
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/providers")
    public Map<String, Object> saveProvider(@RequestBody Map<String, Object> body) {
        try {
            Long id = body.get("id") == null ? null : ((Number) body.get("id")).longValue();
            providerService.save(id, (String) body.get("name"), (String) body.get("baseUrl"),
                    (String) body.get("apiKey"), (String) body.get("model"), (String) body.get("purpose"),
                    !Boolean.FALSE.equals(body.get("enabled")), Boolean.TRUE.equals(body.get("isDefault")));
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @DeleteMapping("/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable long id) {
        providerService.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/providers/{id}/ping")
    public Map<String, Object> ping(@PathVariable long id) {
        return gateway.ping(id);
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        try {
            LlmGatewayService.ChatResult r = gateway.chat(
                    body.getOrDefault("purpose", "chat"), body.getOrDefault("prompt", ""));
            return Map.of("ok", true, "provider", r.provider(), "model", r.model(),
                    "text", r.text(), "cached", r.cached(), "latencyMs", r.latencyMs());
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Integer enabled = jdbc.queryForObject("SELECT COUNT(*) FROM llm_providers WHERE enabled=1", Integer.class);
        Integer cached = jdbc.queryForObject("SELECT COUNT(*) FROM llm_cache", Integer.class);
        // available 以真实连通性为准（60s TTL 缓存），不再只看启用数量
        long now = System.currentTimeMillis();
        if (pingedAt == 0 || now - pingedAt > PING_TTL_MS) {
            online = false;
            for (Map<String, Object> p : providerService.list()) {
                if (!isTruthy(p.get("enabled"))) continue;
                Object pid = p.get("id");
                if (!(pid instanceof Number n)) continue;
                Map<String, Object> r = gateway.ping(n.longValue());
                if (Boolean.TRUE.equals(r.get("ok"))) { online = true; break; }
            }
            pingedAt = now;
        }
        return Map.of("ok", true, "enabledProviders", enabled, "cacheRows", cached,
                "available", online);
    }

    private static boolean isTruthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() == 1;
        return "true".equalsIgnoreCase(String.valueOf(v));
    }
}
