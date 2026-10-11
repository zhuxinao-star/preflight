package com.preflight.llm;

import com.preflight.common.CryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** LLM 供应商配置：页面 CRUD、API Key 加密存储、按用途路由（purpose 精确匹配 → 默认供应商）。 */
@Service
public class LlmProviderService {

    public record Provider(long id, String name, String baseUrl, String apiKeyEnc, String model,
                           String purpose, boolean enabled, boolean isDefault) {}

    private final JdbcTemplate jdbc;
    private final CryptoService crypto;

    private static final RowMapper<Provider> MAPPER = (rs, i) -> new Provider(
            rs.getLong("id"), rs.getString("name"), rs.getString("base_url"),
            rs.getString("api_key_enc"), rs.getString("model"), rs.getString("purpose"),
            rs.getInt("enabled") == 1, rs.getInt("is_default") == 1);

    public LlmProviderService(JdbcTemplate jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public List<Map<String, Object>> list() {
        return jdbc.query("SELECT * FROM llm_providers ORDER BY id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("name", rs.getString("name"));
            m.put("baseUrl", rs.getString("base_url"));
            m.put("model", rs.getString("model"));
            m.put("purpose", rs.getString("purpose"));
            m.put("enabled", rs.getInt("enabled") == 1);
            m.put("isDefault", rs.getInt("is_default") == 1);
            m.put("hasApiKey", rs.getString("api_key_enc") != null);
            return m;
        });
    }

    public void save(Long id, String name, String baseUrl, String apiKey, String model,
                     String purpose, boolean enabled, boolean isDefault) {
        if (isDefault) {
            jdbc.update("UPDATE llm_providers SET is_default = 0");
        }
        if (id == null) {
            jdbc.update("INSERT INTO llm_providers(name, base_url, api_key_enc, model, purpose, enabled, is_default) VALUES (?,?,?,?,?,?,?)",
                    name, baseUrl, crypto.encrypt(apiKey), model, purpose, enabled ? 1 : 0, isDefault ? 1 : 0);
        } else {
            if (apiKey != null && !apiKey.isBlank()) {
                jdbc.update("UPDATE llm_providers SET name=?, base_url=?, api_key_enc=?, model=?, purpose=?, enabled=?, is_default=? WHERE id=?",
                        name, baseUrl, crypto.encrypt(apiKey), model, purpose, enabled ? 1 : 0, isDefault ? 1 : 0, id);
            } else {
                // 不更新 Key（页面留空 = 保留原值）
                jdbc.update("UPDATE llm_providers SET name=?, base_url=?, model=?, purpose=?, enabled=?, is_default=? WHERE id=?",
                        name, baseUrl, model, purpose, enabled ? 1 : 0, isDefault ? 1 : 0, id);
            }
        }
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM llm_providers WHERE id = ?", id);
    }

    public List<Provider> enabledProviders() {
        return jdbc.query("SELECT * FROM llm_providers WHERE enabled = 1 ORDER BY is_default DESC, id", MAPPER);
    }

    /** 按用途路由：优先 purpose 精确匹配的供应商，否则默认供应商 */
    public Provider resolve(String purpose) {
        List<Provider> all = enabledProviders();
        if (purpose != null && !purpose.isBlank()) {
            for (Provider p : all) {
                if (purpose.equals(p.purpose())) return p;
            }
        }
        for (Provider p : all) {
            if (p.isDefault()) return p;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    public String apiKeyOf(Provider p) {
        return crypto.decrypt(p.apiKeyEnc());
    }
}
