package com.preflight.common;

import java.net.http.HttpClient;

/**
 * 进程内所有出站 HTTP 请求的<b>唯一</b> HttpClient 起点（第四十四轮）。
 *
 * <p>JDK 的 HttpClient 即使发的是 HTTP/1.1 请求，也会附带 {@code Connection: Upgrade, HTTP2-Settings}
 * 与 {@code Upgrade: h2c}。老服务端（老版 Node、TongWeb、老 Tomcat、WebLogic）的报文解析器认不下这对头，
 * 请求根本进不了应用就直接回 400 的 HTML 页——现象是"地址不通/回的不是 JSON"，
 * 把人往"你地址填错了"的方向带（真实事故：YApi 地址填对了却被判成页面地址）。
 *
 * <p>钉死 HTTP/1.1 后发出的报文与 curl 逐字一致，可复现、可抓包对照；服务端真要 h2 由它自己协商。
 */
public final class Http1 {

    private Http1() {}

    /** 从这里起步，再按调用点加超时/重定向/Cookie 策略 */
    public static HttpClient.Builder builder() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
    }
}
