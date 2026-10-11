package com.preflight.common;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * 凭据保险库：AES-GCM 加密。
 * 主密钥来自环境变量 PREFLIGHT_MASTER_KEY（Base64 的 32 字节），
 * 未设置时自动生成 data/master.key（部署文档要求对该文件做好权限与备份）。
 * 密文格式：enc:v1:base64(iv)+base64(ciphertext)
 */
@Service
public class CryptoService {

    private static final String PREFIX = "enc:v1:";
    private final SecretKeySpec key;

    public CryptoService() {
        this.key = new SecretKeySpec(loadOrCreateKey(), "AES");
    }

    private byte[] loadOrCreateKey() {
        String env = System.getenv("PREFLIGHT_MASTER_KEY");
        if (env != null && !env.isBlank()) {
            return Base64.getDecoder().decode(env.trim());
        }
        try {
            Path file = Path.of("data", "master.key");
            if (Files.exists(file)) {
                return Base64.getDecoder().decode(Files.readString(file).trim());
            }
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            String b64 = Base64.getEncoder().encodeToString(k);
            Files.createDirectories(file.getParent());
            Files.writeString(file, b64);
            try {
                Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignore) {
                // Windows 无 POSIX 权限模型
            }
            return k;
        } catch (Exception e) {
            throw new IllegalStateException("主密钥初始化失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return null;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String enc) {
        if (enc == null || !enc.startsWith(PREFIX)) return enc;
        try {
            String body = enc.substring(PREFIX.length());
            String[] parts = body.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（主密钥不匹配？）", e);
        }
    }
}
