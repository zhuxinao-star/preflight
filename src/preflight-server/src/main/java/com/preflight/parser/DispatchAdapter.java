package com.preflight.parser;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 分发式框架适配器：一个 URL 承载全部业务服务的老式后端（金证 KJDP 是首个实现）。
 * 这类工程的接口卡学不到参数是事实——参数契约不在 Java 方法签名里，而在框架元数据 XML
 * 与前端调用点里，所以单独抽一本"服务字典"供测试台按码查用，不生成接口卡。
 */
public interface DispatchAdapter {

    /** 框架名，透出给用户看的中文 */
    String displayName();

    /** 仓库是否属于该框架 */
    boolean detect(Path repoRoot);

    /** 入口 URL → 信封描述（key 为 web.xml 里已建卡的真实路径，如 /redis_ajax） */
    Map<String, DispatchSpec> entries(Path repoRoot);

    /** 框架元数据里声明的服务与入参（高置信） */
    List<DispatchTarget> declaredTargets(Path repoRoot);

    /** 前端调用点里实际使用的服务与字面量键（中置信） */
    List<DispatchTarget> callSiteTargets(List<Path> jsRepoRoots);

    /**
     * 沿框架自己的编排链推出来的入参（第 11 轮）：metaData 只报了服务名、前端调用点也没写字面量时，
     * 顺着"服务 → 业务 → bex 声明 / atom 实现"这条链去补齐。低置信，只补缺不覆盖。
     */
    default List<DispatchTarget> chainTargets(Path repoRoot) {
        return List.of();
    }

    /**
     * 这个框架"每条请求都必须带"的请求级字段（第 39 轮）。
     *
     * <p>KJDP 不用这里：它的信封整个写在 {@link #entries} 的模板里，因为所有服务码共用一个入口 URL。
     * 而"一个功能号一条真实路径"那种框架（统一访问转发中心）没有入口模板可言——但转发中心读
     * {@code func} 与 {@code func_url} 才能把请求转到目标仓，这两项是框架契约，不随调用点变，
     * 所以由适配器按码给。前端调用点扫得到证据时仍以调用点为准（{@code basis=call-site}），
     * 这里只在没有调用点证据时顶上，避免退回"按 kind 猜一个 service 字段"那种假信封。
     *
     * @param code      功能号
     * @param entryPath 这个码自己的真实请求路径（学不到时为空，此时不编）
     */
    default List<Map<String, Object>> requestFields(String code, String entryPath) {
        return List.of();
    }

    /** 框架名 → 是否"一个功能号一条真实路径"（决定 YApi 的请求路径能不能直接用 entry_path） */
    default boolean entryPathIsCallPath() {
        return false;
    }

    /**
     * "一个功能号一条真实路径"那种框架（{@link #entryPathIsCallPath()}）的请求形状（第四十一轮补 N57）。
     *
     * <p>这类码的接口卡本来就是它自己的控制器，路径是现成的，缺的是<b>请求体长什么样</b>：
     * 报文头哪几个键必填、功能号填在哪个键、业务参数落在哪个节点。这三件事都在框架源码里，
     * 不在调用点上，所以由适配器给——和 KJDP 的 {@link #entries} 是同一类事实，只是一个入口一份、这个一码一份。
     *
     * <p>给了它，测试台才会在这张卡上出现「分发调用」面板并一键拼出发得出去的身体；
     * 不给（返回 {@code null}）就照旧只当普通接口卡看待，不凭空造面板。
     *
     * <p>报文头那一段的<b>其余公共参数不在这里写死</b>（第四十二轮用户原话：「接口测试台，为什么
     * 其它公共参数又没了，比方说 op_code 什么的，不同模块公共参数不一样啊」）。公共参数就是框架请求体
     * 里那个头对象上声明的字段，每个仓的类各自一份，所以由 {@code DispatchDictionaryService} 在建卡时
     * 按 {@code hdrKey} 去那个仓的源码里学（{@code CallPathIndex#headerParams}），连同"这个键是什么"
     * 一起填进 {@link DispatchSpec#hdrFields()} 并进请求体。这里只给<b>必须由框架自己填值</b>的那几项
     * （如 {@code server_id=$code}：不填切面就查不到参数声明）。盘上学不到就没有，绝不按别家框架的习惯编。
     *
     * <p>形状<b>按这个仓自己的源码推</b>（第四十五轮）：同一族框架里，招商那份的请求体是
     * {@code {req_msg_hdr, req_comm_data}}（业务键在 {@code req_comm_data} 里），国泰海通那份是
     * {@code RequestModel extends JSONObject} + 一个 {@code head} 字段（业务键直接平铺在根上，
     * 功能号由 URL 最后一段决定、报文头里根本没有 {@code server_id}）。写死任何一份都是拿 A 仓的
     * 形状去发 B 仓的请求——键名对不上，服务端只会把业务参数当成没传。
     * 认不出来的仓（没有 {@code @RequestBody} 声明、报文头对象有并列多个）就返回 {@code null}：
     * 不给这张卡打分发标记，测试台照旧当普通接口卡发，比编一个发不出去的形状诚实。
     *
     * @param repoRoot  贡献这条码的仓库工作副本根（按模块发现见 {@link RepoModules}）
     * @param code      功能号
     * @param entryPath 这个码自己的真实请求路径
     */
    default DispatchSpec callSpec(Path repoRoot, String code, String entryPath) {
        return null;
    }

    /**
     * 信封形状：请求体根键、头/负载键、服务码键名、固定 query 与内容类型。
     *
     * <p>{@code hdrTemplate} 是"发出去的那份头"（键→值，值里的 {@code $code} 由界面换成当前功能号）。
     * {@code hdrFields} 是"头里每个键是什么"（类型/中文名/出处），由建卡方按该仓源码补齐后一起盖到卡上——
     * 适配器自己给空表就行，不要再手抄一份公共参数清单，两份清单早晚会差几个字段。
     */
    record DispatchSpec(String entryPath, String controllerClass, String rootKey, String hdrKey,
                        String dataKey, String serviceKey, String bexKey, String autoService,
                        String fixedQuery, String contentType, Map<String, Object> hdrTemplate,
                        List<DispatchParam> hdrFields) {}

    /** 一个服务码 = kind + code，带中文名、参数与证据 */
    record DispatchTarget(String kind, String code, String name, String entryPath,
                          List<DispatchParam> params, List<String> evidence) {}

    /** 一个入参：字段名、JSON 类型、默认值、中文名、来源（metaData|js|chain）、证据 */
    record DispatchParam(String key, String type, String def, String label, String source, String via) {}
}
