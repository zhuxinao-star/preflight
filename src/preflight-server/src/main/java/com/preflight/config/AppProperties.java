package com.preflight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * preflight.* 配置项：少量部署级参数走配置文件；业务配置（LLM/环境/凭据等）一律页面 + 数据库管理。
 */
@ConfigurationProperties(prefix = "preflight")
public class AppProperties {

    /** 内置样例目录（学习演示用） */
    private String samplesDir = "./samples";

    /** 仓库自动同步轮询间隔（毫秒），0 = 关闭定时同步 */
    private long syncIntervalMs = 300000;

    public String getSamplesDir() { return samplesDir; }
    public void setSamplesDir(String samplesDir) { this.samplesDir = samplesDir; }
    public long getSyncIntervalMs() { return syncIntervalMs; }
    public void setSyncIntervalMs(long syncIntervalMs) { this.syncIntervalMs = syncIntervalMs; }
}
