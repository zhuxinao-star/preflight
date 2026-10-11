package com.preflight.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 网关：统一 OpenAI 兼容协议入口。
 * 多供应商（数据库配置）、按用途路由、请求指纹缓存（离线回放基石）、失败重试与降级。
 */
@Service
public class LlmGatewayService {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayService.class);

    private final LlmProviderService providers;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public LlmGatewayService(LlmProviderService providers, JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit) {
        this.providers = providers;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** 推理模型（如 deepseek-r1 类）会输出 <think>...</think> 思考段，统一剥离后再使用 */
    public static String stripThink(String text) {
        if (text == null) return null;
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    public record ChatResult(String provider, String model, String text, boolean cached,
                             Map<String, Object> usage, long latencyMs) {}

    /** 各用途系统提示词（R9：版本化文件 prompts/*.md，随知识包导出；文件缺失时退回内置文本） */
    private final Map<String, String> promptFiles = new LinkedHashMap<>();

    @jakarta.annotation.PostConstruct
    void loadPrompts() {
        for (String purpose : new String[]{"summary", "testcase", "extract", "chat"}) {
            String fallback = builtinPrompt(purpose);
            try (var in = getClass().getResourceAsStream("/prompts/" + purpose + ".md")) {
                promptFiles.put(purpose, in == null ? fallback
                        : new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                promptFiles.put(purpose, fallback);
            }
        }
    }

    public String systemPrompt(String purpose) {
        return promptFiles.get(purpose == null ? "chat" : purpose);
    }

    private String builtinPrompt(String purpose) {
        return switch (purpose == null ? "chat" : purpose) {
            case "summary" -> "你是资深测试工程师。根据代码 diff 与变更文件清单，用简洁中文总结：改了什么、为什么可能改、有什么测试风险。不要复述代码。";
            case "testcase" -> "你是资深测试工程师。根据接口定义(JSON)和可选的改动上下文，设计测试用例。只输出 JSON 数组，每项含 name/scenario/params/expect 字段，params 为参数对象。不要输出其它文字。";
            case "extract" -> "你是代码分析专家。根据代码片段提取接口定义信息，只输出 JSON。若片段含 @RequestBody/参数注解，请同时给出 params 的中文名称与说明。";
            default -> "You are a helpful assistant.";
        };
    }

    public Map<String, String> promptsVersioned() {
        return promptFiles;
    }

    public boolean isAvailable() {
        return providers.resolve(null) != null;
    }

    /** LLM 调用；不可用或失败抛出 IllegalStateException，由调用方降级 */
    public ChatResult chat(String purpose, String prompt) {
        LlmProviderService.Provider p = providers.resolve(purpose);
        if (p == null) {
            throw new IllegalStateException("没有已启用的 LLM 供应商（系统将以降级模式运行）");
        }
        String fingerprint = fingerprint(purpose, p.model(), prompt);

        // 1. 缓存优先：相同请求不出网（省钱 + 离线回放）
        Map<String, Object> cached = null;
        try {
            cached = jdbc.queryForMap("SELECT response, model FROM llm_cache WHERE fingerprint = ?", fingerprint);
        } catch (org.springframework.dao.EmptyResultDataAccessException miss) {
            // 未命中
        }
        if (cached != null) {
            audit.log("llm.chat", p.name(), "cache hit: " + fingerprint);
            try {
                JsonNode node = mapper.readTree((String) cached.get("response"));
                return new ChatResult(p.name(), (String) cached.get("model"),
                        node.path("choices").path(0).path("message").path("content").asText(),
                        true, mapper.convertValue(node.path("usage"), Map.class), 0);
            } catch (Exception corrupt) {
                log.warn("缓存行损坏，走正常调用: {}", fingerprint);
            }
        }

        // 2. 调用（带重试）
        long begin = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 0; attempt <= 1; attempt++) {
            try {
                String raw = callChatCompletion(p, purpose, prompt);
                long latency = System.currentTimeMillis() - begin;
                JsonNode node = mapper.readTree(raw);
                String text = stripThink(node.path("choices").path(0).path("message").path("content").asText());
                Map<String, Object> usage = mapper.convertValue(node.path("usage"), Map.class);
                jdbc.update("INSERT OR REPLACE INTO llm_cache(fingerprint, purpose, model, response) VALUES (?, ?, ?, ?)",
                        fingerprint, purpose, p.model(), raw);
                audit.log("llm.chat", p.name(), "ok, latency=" + latency + "ms, fp=" + fingerprint);
                return new ChatResult(p.name(), p.model(), text, false, usage, latency);
            } catch (Exception e) {
                lastError = e;
                log.warn("LLM 调用失败（第 {} 次）: {}", attempt + 1, e.getMessage());
                if (attempt < 1) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw new IllegalStateException("LLM 调用失败（provider=" + p.name() + "）："
                + (lastError == null ? "unknown" : lastError.getMessage()), lastError);
    }

    public Map<String, Object> ping(long providerId) {
        LlmProviderService.Provider p = providers.enabledProviders().stream()
                .filter(x -> x.id() == providerId).findFirst().orElse(null);
        if (p == null) {
            // 未启用的供应商也允许 ping：单独构造
            var rows = providers.list().stream().filter(m -> ((Number) m.get("id")).longValue() == providerId).toList();
            if (rows.isEmpty()) return Map.of("ok", false, "error", "供应商不存在");
            p = new LlmProviderService.Provider(providerId, (String) rows.get(0).get("name"),
                    (String) rows.get(0).get("baseUrl"), null, (String) rows.get(0).get("model"),
                    (String) rows.get(0).get("purpose"), true, false);
        }
        try {
            ChatResult r = chatWith(p, "ping，请只回复两个字母：ok");
            return Map.of("ok", true, "provider", p.name(), "model", r.model(), "reply", r.text());
        } catch (Exception e) {
            return Map.of("ok", false, "provider", p.name(), "model", p.model(),
                    "error", String.valueOf(e.getMessage()));
        }
    }

    private ChatResult chatWith(LlmProviderService.Provider p, String prompt) {
        long begin = System.currentTimeMillis();
        try {
            String raw = callChatCompletion(p, "chat", prompt);
            JsonNode node = mapper.readTree(raw);
            return new ChatResult(p.name(), p.model(),
                    node.path("choices").path(0).path("message").path("content").asText(),
                    false, null, System.currentTimeMillis() - begin);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 调用失败（provider=" + p.name() + "）：" + e.getMessage(), e);
        }
    }

    private String callChatCompletion(LlmProviderService.Provider p, String purpose, String prompt) {
        RestClient client = RestClient.builder()
                .baseUrl(p.baseUrl())
                .requestFactory(requestFactory())
                .build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", p.model());
        body.put("temperature", 0.2);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(purpose)));
        messages.add(Map.of("role", "user", "content", prompt));
        body.put("messages", messages);

        RestClient.RequestBodySpec spec = client.post()
                .uri("/chat/completions")
                .header("Content-Type", "application/json");
        String apiKey = providers.apiKeyOf(p);
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey);
        }
        return spec.body(body).retrieve().body(String.class);
    }

    private JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = com.preflight.common.Http1.builder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return factory;
    }

    private String fingerprint(String purpose, String model, String prompt) {
        String material = (purpose == null ? "chat" : purpose) + "|" + model + "|" + prompt;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
