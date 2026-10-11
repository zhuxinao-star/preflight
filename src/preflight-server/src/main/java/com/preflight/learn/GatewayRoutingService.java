package com.preflight.learn;

import com.preflight.common.FileTexts;
import com.preflight.parser.RepoModules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「这张卡经网关要发什么路径」的唯一回答（第五十轮）。
 *
 * <p><b>根因</b>：注解式学到的是<b>模块内部</b>的 Controller 路径（{@code /qc/see/workOrder/push}），
 * 而微服务项目的环境地址是 nginx。nginx 上没有 {@code /qc/**} 这个 location，于是它按静态资源处理，
 * 回一句 {@code 405 Not Allowed}＋一段 HTML——页面看到的就是那条 HTML，261 张卡基本一张也发不出去。
 * 真实形状是三层拼出来的：
 *
 * <pre>
 *   环境地址 + 部署前缀(/ni-gateway) + 服务名(/ni-quality) + 卡片路径(/qc/see/workOrder/push)
 * </pre>
 *
 * <p>这三层<b>全都在盘上写着</b>，只是以前没人学：网关 {@code application.yml} 里
 * {@code spring.cloud.gateway.discovery.locator.enabled: true}（或
 * {@code GatewayConfig} 那种 {@code path("/" + service + "/**").stripPrefix(1)} 的编程式路由）
 * 说明"第一段就是注册中心里的服务名"；每个服务自己的 {@code bootstrap.yml} 里
 * {@code spring.application.name} 说明"服务名叫什么"；前端 axios 实例的 {@code baseURL}
 * 与它写全的那些调用点说明"部署前缀是什么、哪个接口对外长什么样"。
 *
 * <p><b>三态，不是 null</b>（第卅七轮口径：未配置不等于 null）：
 * <ul>
 *   <li>{@link #EVIDENCED}——前端调用点<b>真写了</b>这个对外地址，给出文件与行号；</li>
 *   <li>{@link #INFERRED}——前端没写这一个，但网关路由表＋这个仓的服务名＋部署前缀都在盘上，按规则推；
 *       推的每一段各自点名依据；</li>
 *   <li>{@link #NOT_APPLICABLE}——这个项目盘上没有网关证据（单体/直连），照卡片路径发就是对的；</li>
 *   <li>{@link #MISSING_INPUT}——这项目确实是网关转发的，但<b>这一段推不出来</b>（服务名没学到/仓库目录读不到），
 *       要说清缺的是哪一段、去哪儿补，不许塌回"照内部路径发"还装作没事。</li>
 * </ul>
 *
 * <p>一个出口喂所有下游（真发、复制 cURL、留痕快照、④ 测试台顶部那行、分发面板、可测性体检）：
 * 拼 URL 的三处重复实现收在 {@code HttpExecutorService.composeCallUrl}，本服务只回答"路径应该是什么"。
 * 读取期现算，<b>绝不落库</b>；改仓库目录或重学后 TTL 内自然失效，也可 {@link #invalidate} 立刻清。
 */
@Service
public class GatewayRoutingService {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutingService.class);

    public static final String EVIDENCED = "evidenced";
    public static final String INFERRED = "inferred";
    public static final String NOT_APPLICABLE = "not_applicable";
    public static final String MISSING_INPUT = "missing_input";
    /** 用户本次手动选了「按内部路径发」：这是一档结论，不是"没证据"，不许与 NOT_APPLICABLE 混用 */
    public static final String MANUAL = "manual";

    /** 中文档位名单点供给（界面与脚本共用，不在两处各写一遍） */
    public static String stateLabel(String state) {
        switch (state == null ? "" : state) {
            case EVIDENCED: return "前端实证";
            case INFERRED: return "按网关路由表推";
            case NOT_APPLICABLE: return "不适用（盘上无网关证据）";
            case MISSING_INPUT: return "缺输入（推不出）";
            case MANUAL: return "本次手动按内部路径发";
            default: return "未判定";
        }
    }

    private static final Pattern APP_NAME = Pattern.compile(
            "^\\s*(?:spring\\s*:\\s*)?(?:application\\s*:\\s*)?\\s*name\\s*[:=]\\s*['\"]?([\\w.-]{2,64})['\"]?\\s*$",
            Pattern.MULTILINE);
    private static final Pattern APP_NAME_FLAT = Pattern.compile(
            "^\\s*spring\\.application\\.name\\s*=\\s*['\"]?([\\w.-]{2,64})['\"]?\\s*$", Pattern.MULTILINE);
    private static final Pattern SERVER_PORT = Pattern.compile(
            "^\\s*(?:server\\s*:\\s*)?port\\s*[:=]\\s*(\\d{2,5})\\s*$", Pattern.MULTILINE);
    private static final Pattern SERVER_PORT_FLAT = Pattern.compile(
            "^\\s*server\\.port\\s*=\\s*(\\d{2,5})\\s*$", Pattern.MULTILINE);
    /** 平铺一行（properties）与 YAML 流式（{@code locator: {enabled: true}}）两种挤在一行的写法 */
    private static final Pattern LOCATOR_ENABLED_INLINE = Pattern.compile(
            "locator[^\\n]{0,60}enabled\\s*[:=]\\s*['\"]?true");
    private static final Pattern GATEWAY_YAML_HINT = Pattern.compile("gateway");
    /**
     * 依赖即证据：构建文件的<b>依赖坐标</b>里挂了名字独立成词的网关 starter（
     * {@code spring-cloud-starter-gateway}、各家包装的 {@code xxx-cloud-starter-gateway} 都算）。
     *
     * <p>为什么不去扫 java 源码找编程式 RouteLocator：一份检出有几万个 .java，逐个读原文明显撑不住；
     * 而 {@code discovery.locator.enabled} 常常由配置中心下发，本地 yml 里压根没那一句。
     * 网关这个词在任何一家技术栈里都是同一个架构角色，认它不算项目字面量。
     *
     * <p>只看 {@code <dependency>} 块内的坐标：一个网关工程自己的 {@code <artifactId>xxx-gateway</artifactId>}
     * 写在文件开头，扫全文会把它的<b>名字</b>当成"挂了网关依赖"——而依赖它的普通业务服务又被漏掉。
     */
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile(
            "<dependency\\b[^>]*>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY_ARTIFACT = Pattern.compile("<artifactId>\\s*([^<]+?)\\s*</artifactId>");
    private static final Pattern GRADLE_COORD = Pattern.compile(
            "\\b(?:implementation|api|compile|runtimeOnly|compileOnly|testImplementation)\\b\\s*[(\\s]\\s*['\"]([^'\"]+)['\"]");

    /**
     * 网关有没有打开「按注册中心服务名自动路由」。打开了返回那一条键路径（给依据用），没打开返回空串。
     *
     * <p>判据按<b>键路径</b>走缩进栈，不数中间层叫什么名字：Spring Cloud Gateway 4.x 把
     * {@code discovery.locator} 挪到了 {@code server.webflux} 底下，一行一层、行尾还常跟注释——
     * 按"locator 和 enabled 在同一行"去认，每升一次网关版本就瞎一次（第五十轮就是这么把 261 张
     * 全判成"不适用"的）。中间层随版本改名字，{@code gateway → … → locator → enabled=true}
     * 这三层关系才是"这套路由生效"这件事本身。
     */
    static String locatorEnabledPath(String text) {
        if (text == null || text.isBlank()) return "";
        boolean mentionsGateway = GATEWAY_YAML_HINT.matcher(text).find();
        String[] keys = new String[24];
        int[] indents = new int[24];
        int depth = 0;
        for (String raw : text.split("\\R")) {
            String line = stripComment(raw);
            if (line.isBlank()) continue;
            // 挤在同一行的两种写法（properties 平铺 / YAML 流式）：整行回给人看，
            // 平铺那一行本身就是完整键路径，只截 "locator…" 半截等于把依据说短了一截
            if (mentionsGateway && LOCATOR_ENABLED_INLINE.matcher(line).find()) return line.trim();
            int indent = leading(line);
            String body = line.trim();
            int colon = body.indexOf(':');
            if (colon < 0) continue;
            String key = body.substring(0, colon).trim().replaceAll("[\"']", "");
            if (key.isEmpty() || key.startsWith("-")) continue;   // 数组元素不是配置键
            String value = body.substring(colon + 1).trim();
            while (depth > 0 && indents[depth - 1] >= indent) depth--;
            if (depth >= keys.length) break;
            indents[depth] = indent;
            keys[depth] = key;
            depth++;
            if (value.isEmpty() || !"enabled".equalsIgnoreCase(key)) continue;
            if (!"true".equalsIgnoreCase(value.replaceAll("^['\"]|['\"]$", ""))) continue;
            if (depth < 2 || !"locator".equalsIgnoreCase(keys[depth - 2])) continue;
            if (!hasGatewayAncestor(keys, depth)) continue;
            StringBuilder pj = new StringBuilder();
            for (int i = 0; i < depth; i++) pj.append(i == 0 ? "" : ".").append(keys[i]);
            return pj.toString();
        }
        return "";
    }

    /** 去掉 YAML 行尾注释（引号内的 # 不算注释）；整行注释返回空 */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int leading(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return n;
    }

    private static boolean hasGatewayAncestor(String[] keys, int depth) {
        for (int i = 0; i < depth - 2; i++) {
            if (isGatewayWord(keys[i])) return true;
        }
        return false;
    }

    /** "gateway" 要独立成词：{@code gateway}、{@code koca-gateway} 算，{@code aggregation} 不算 */
    private static boolean isGatewayWord(String name) {
        for (String part : name.split("[-_.:]")) {
            if ("gateway".equalsIgnoreCase(part)) return true;
        }
        return false;
    }

    /**
     * 这个构建文件依赖了哪个网关构件（没有则空串）。
     *
     * <p>Maven 只取 {@code <dependency>} 块里的第一个 {@code <artifactId>}（那就是这条依赖自己，
     * {@code <exclusions>} 排在它后面）；Gradle 只取依赖配置那几行的坐标中段。
     */
    static String gatewayDependency(String text, boolean gradle) {
        if (text == null || text.isBlank()) return "";
        if (gradle) {
            Matcher gm = GRADLE_COORD.matcher(text);
            while (gm.find()) {
                String[] coord = gm.group(1).split(":");
                String artifact = coord.length >= 2 ? coord[1] : coord[0];
                if (isGatewayWord(artifact)) return artifact;
            }
            return "";
        }
        Matcher blocks = DEPENDENCY_BLOCK.matcher(text);
        while (blocks.find()) {
            Matcher a = DEPENDENCY_ARTIFACT.matcher(blocks.group(1));
            if (a.find() && isGatewayWord(a.group(1).trim())) return a.group(1).trim();
        }
        return "";
    }

    private final JdbcTemplate jdbc;
    private final FrontCallSiteIndex callSites;

    public GatewayRoutingService(JdbcTemplate jdbc, @Autowired(required = false) FrontCallSiteIndex callSites) {
        this.jdbc = jdbc;
        this.callSites = callSites;
    }

    // ---------------------------------------------------------------- 出口形状

    /** 一段前缀及其依据（界面按段摆，用户能看出这段从哪来） */
    public record Segment(String value, String evidence) {}

    /**
     * 一张卡的对外调用路径。
     *
     * @param state    四态之一，永不为空
     * @param path     要发出去的那个路径（缺输入时＝卡片路径，并靠 {@link #note} 说明为什么没换）
     * @param internal 卡片学到的内部路径（界面上"内部/对外"两行要同时摆）
     * @param segments 补上去的每一段及其依据（实证时是一段整体，推导时可能两段）
     * @param evidence 文件:行号级依据清单
     */
    public record Answer(String state, String path, String internal, List<Segment> segments,
                         List<String> evidence, String note, String basis) {
        public boolean changed() {
            return (EVIDENCED.equals(state) || INFERRED.equals(state)) && !path.equals(internal);
        }

        public String stateLabel() {
            return GatewayRoutingService.stateLabel(state);
        }

        /** 给留痕与界面用的一行话 */
        public String summary() {
            if (NOT_APPLICABLE.equals(state)) return "照卡片路径发（这项目没有网关证据）";
            if (MISSING_INPUT.equals(state)) return "推不出对外路径：" + note;
            return stateLabel() + "：" + path + (segments.isEmpty() ? "" : "（补 "
                    + String.join(" + ", segments.stream().map(Segment::value).toList()) + "）");
        }

        static Answer of(String state, String internal, String path, List<Segment> seg,
                         List<String> ev, String note, String basis) {
            return new Answer(state, path == null ? internal : path, internal,
                    seg == null ? List.of() : seg, ev == null ? List.of() : ev,
                    note == null ? "" : note, basis == null ? "" : basis);
        }
    }

    /**
     * 这张卡是不是「一码一条真实路径」框架自己声明的调用路径（第卅九/四十一轮那套）。
     *
     * <p>这一类卡学的<b>就是</b>对外地址（功能号自带路径，第四十一轮由框架自述盖进
     * {@code params_schema.dispatch.mode="call-path"}），再补一层服务名前缀就是把对的地址改错。
     * 判据留在本服务，两个调用方（真发与界面那行依据）都问这里，不在两处各写一遍正则。
     */
    public static boolean callPathDeclared(String paramsSchemaJson) {
        if (paramsSchemaJson == null || paramsSchemaJson.isBlank()) return false;
        return CALL_PATH_MODE.matcher(paramsSchemaJson).find();
    }

    private static final Pattern CALL_PATH_MODE = Pattern.compile("\"mode\"\\s*:\\s*\"call-path\"");

    // ---------------------------------------------------------------- 单点入口

    /**
     * @param projectId 卡片所属项目（决定用哪一批仓库的证据）
     * @param repoId    卡片所属仓库（可能为空——空就只能按前端实证那条通路）
     * @param cardPath  卡片学到的路径
     * @param sourceJson 卡片的 source 列（推服务名时要按它定位所属模块）
     * @param baseUrl   本次要打的地址（用来判"环境地址是不是网关自己的端口"）
     * @param preferInternal true＝用户本次显式选了"按内部路径发"，只回答不覆盖
     */
    public Answer forCard(long projectId, Long repoId, String cardPath, String sourceJson,
                          String baseUrl, boolean preferInternal) {
        return forCard(projectId, repoId, cardPath, sourceJson, baseUrl, preferInternal, false);
    }

    /** @param callPathDeclared 卡片自己是「一码一路径」框架的调用路径：那一条路径不外露，只挡住按路由表推这一档 */
    public Answer forCard(long projectId, Long repoId, String cardPath, String sourceJson, String baseUrl,
                          boolean preferInternal, boolean callPathDeclared) {
        String internal = cardPath == null ? "" : cardPath.trim();
        if (internal.isEmpty() || !internal.startsWith("/")) {
            return new Answer(MISSING_INPUT, internal, internal, List.of(), List.of(),
                    "卡片路径为空或不是以 / 开头的路径，无从判断对外写法", "");
        }
        Facts f = facts(projectId);
        if (preferInternal) {
            return Answer.of(MANUAL, internal, internal, List.of(), List.of(),
                    "本次请求选了「按内部路径发」，不套任何前缀", f.basis);
        }
        if (!f.hasAnyEvidence()) {
            return Answer.of(NOT_APPLICABLE, internal, internal, List.of(), f.evidence(),
                    f.noEvidenceNote, f.basis);
        }
        // ① 前端实证：调用点真写了这个地址
        List<FrontCallSiteIndex.Site> hits = f.sitesOf(internal);
        if (!hits.isEmpty()) {
            FrontCallSiteIndex.Site top = hits.get(0);
            List<String> ev = new ArrayList<>();
            ev.add("前端调用点 " + top.evidence() + "（" + top.path + "，同址 " + hits.size() + " 处）");
            String path = stripDeploy(top.absPath, f, baseUrl, ev);
            // 段要按<b>这次真正发出去的那条</b>切：摘掉 nginx 挂载点之后剩下的前缀才是真发出去的
            List<Segment> segs = prefixSegments(stripTail(path, internal), top, f);
            return Answer.of(EVIDENCED, internal, path, segs, ev, "", f.basis);
        }
        // ② 按网关路由表推：服务名（这个仓/模块的 spring.application.name）＋部署前缀（前端实例的 baseURL）
        if (callPathDeclared) {
            return Answer.of(NOT_APPLICABLE, internal, internal, List.of(), f.evidence(),
                    "这张卡的路径是「一码一条真实路径」框架自己声明的调用路径（第四十一轮由框架自述盖进卡片），"
                            + "它本来就是对外那一份，再补服务名前缀就是把对的地址改错",
                    f.basis);
        }
        if (!f.gatewayFronted) {
            return Answer.of(NOT_APPLICABLE, internal, internal, List.of(), f.evidence(),
                    "盘上有前端调用点，但没有网关把第一段当服务名的证据（既没开 discovery.locator，"
                            + "也没有 path(\"/\" + 服务名 + \"/**\") 那种编程式路由），照卡片路径发",
                    f.basis);
        }
        // 网关自己那个模块的卡不套服务名：它自己就是入口（下面 ev 里点名是哪一条证据让它成为网关）
        String gatewayOwnModule = f.gatewayModuleOf(repoId, sourceJson);
        String service = gatewayOwnModule == null ? f.serviceName(repoId, sourceJson) : "";
        if (service.isEmpty() && gatewayOwnModule == null) {
            return Answer.of(MISSING_INPUT, internal, internal, List.of(), f.evidence(),
                    f.serviceMissingNote(repoId), f.basis);
        }
        List<Segment> segs = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        StringBuilder path = new StringBuilder();
        String deploy = f.deployPrefix();
        boolean onGatewayPort = f.baseUrlHitsGatewayPort(baseUrl);
        if (!deploy.isEmpty() && !onGatewayPort) {
            path.append(deploy);
            segs.add(new Segment(deploy, "前端请求封装实例的 baseURL（" + f.baseEvidence(deploy) + "）"));
            ev.add("部署前缀 " + deploy + "：" + f.baseEvidence(deploy));
        } else if (!deploy.isEmpty() && onGatewayPort) {
            ev.add("环境地址打的就是网关自己的端口 " + f.gatewayPort + "，"
                    + deploy + " 是 nginx 那层的挂载点，这里不带");
        }
        if (!service.isEmpty()) {
            path.append('/').append(service);
            segs.add(new Segment("/" + service, "该服务的 spring.application.name（" + f.serviceEvidence(repoId, sourceJson) + "）"));
            ev.add("服务名 " + service + "：" + f.serviceEvidence(repoId, sourceJson));
        } else {
            // 盘上的写法是 /ni-gateway/cc/getToken，没人调过 /ni-gateway/<网关名>/cc/getToken：
            // 前面那一段是 nginx 挂载点而不是"按服务名找下游"，套上服务名就把对的地址改错
            ev.add("这张卡属于网关那个模块本身（" + f.gatewayModuleNote(gatewayOwnModule) + "），"
                    + "它的 Controller 直接挂在入口下，不套服务名");
        }
        path.append(internal);
        return Answer.of(INFERRED, internal, path.toString(), segs, ev, "", f.basis);
    }

    /**
     * 实证档补上去的那一截前缀，逐段自述来源。
     *
     * <p>整截一起标成"该实例的 baseURL"是假话：baseURL 只管住开头那一段（{@code /ni-gateway}），
     * 后面那一段（{@code /ni-unifiedvisit}）是调用点自己写在路径里的，两者依据不是同一个。
     */
    private static List<Segment> prefixSegments(String prefix, FrontCallSiteIndex.Site top, Facts f) {
        if (prefix.isEmpty()) return List.of();
        if (top.base.isEmpty()) return List.of(new Segment(prefix, "调用点自己写全了路径（" + top.evidence() + "）"));
        List<Segment> out = new ArrayList<>();
        String rest = prefix;
        List<String> bases = new ArrayList<>(f.deployBases());
        bases.sort((x, y) -> y.length() - x.length());   // 前缀套前缀时（/a 与 /a/b）先认长的那个
        for (String b : bases) {
            if (rest.startsWith(b) && (rest.length() == b.length() || rest.charAt(b.length()) == '/')) {
                out.add(new Segment(b, "前端请求封装实例的 baseURL（" + f.baseEvidence(b) + "）"));
                rest = rest.substring(b.length());
                break;
            }
        }
        if (!rest.isEmpty()) out.add(new Segment(rest, "这条调用点写在路径前面的前缀（" + top.evidence() + "）"));
        return out;
    }

    private static String stripTail(String abs, String internal) {
        String a = abs.replaceAll("/+$", "");
        String i = internal.replaceAll("/+$", "");
        if (a.length() <= i.length() || !a.endsWith(i)) return a;
        return a.substring(0, a.length() - i.length());
    }

    /** 实证地址里若挂着部署前缀，而本次环境地址是网关自己的端口，就把那一段摘掉 */
    private static String stripDeploy(String abs, Facts f, String baseUrl, List<String> ev) {
        String a = abs.replaceAll("/+$", "");
        if (f.baseUrlHitsGatewayPort(baseUrl)) {
            for (String b : f.deployBases()) {
                if (a.startsWith(b + "/")) {
                    ev.add("环境地址是网关自己的端口 " + f.gatewayPort + "，摘掉 nginx 那层的挂载点 " + b);
                    return a.substring(b.length());
                }
            }
        }
        return a;
    }

    // ---------------------------------------------------------------- 体检口径

    /**
     * 这个项目里普通接口卡「对外路径算不算得出来」的分档统计（可测性体检用）。
     *
     * <p>逐卡问的就是 {@link #forCard} 那一个出口，不在体检里另写一套判据——体检说能发、
     * 测试台却发不出去，是这类双判据最典型的坏法。分发入口卡（一 URL 多服务）不在此列：
     * 它们的路径由服务字典给。
     *
     * @param baseUrl 本项目环境地址（只影响"要不要带 nginx 挂载点"那一段的判法）
     */
    public Map<String, Integer> pathCensus(long projectId, String baseUrl) {
        Map<String, Integer> by = new LinkedHashMap<>();
        by.put(EVIDENCED, 0);
        by.put(INFERRED, 0);
        by.put(NOT_APPLICABLE, 0);
        by.put(MISSING_INPUT, 0);
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT path,repo_id,source,params_schema FROM interfaces WHERE project_id=? AND path IS NOT NULL",
                projectId)) {
            String ps = row.get("params_schema") == null ? "" : String.valueOf(row.get("params_schema"));
            if (ps.contains("\"dispatch\"")) continue;   // 分发入口卡：路径来自服务字典，不参与这一档
            Answer a = forCard(projectId, row.get("repo_id") instanceof Number n ? n.longValue() : null,
                    String.valueOf(row.get("path")), row.get("source") == null ? "" : String.valueOf(row.get("source")),
                    baseUrl, false, callPathDeclared(ps));
            by.merge(a.state(), 1, Integer::sum);
        }
        return by;
    }

    /** 这个项目盘上到底有没有网关证据（体检要说"不适用"还是"该补"） */
    public boolean gatewayEvidence(long projectId) {
        return facts(projectId).gatewayFronted;
    }

    // ---------------------------------------------------------------- 没选环境时打哪个地址

    /** 一个环境的三样最小信息（id 给下游查库，name 给界面点名"按哪个环境算的"） */
    public record EnvRef(long id, String name, String baseUrl) {}

    /**
     * 「这张卡没显式选环境时打哪个地址」的唯一规则：项目里 id 最小的那一个环境。
     *
     * <p>为什么收成单点（第五十轮）：执行内核、复制 cURL、路径依据端点各自兜过一次，
     * 知识库列表再兜一次就会变成"列表按 A 环境算、点执行按 B 环境发"——补前缀这件事上
     * 两个答案比没有答案更坏。
     */
    public EnvRef firstEnv(long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,name,base_url FROM environments WHERE project_id=? ORDER BY id LIMIT 1", projectId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        return new EnvRef(((Number) r.get("id")).longValue(), String.valueOf(r.get("name")),
                r.get("base_url") == null ? "" : String.valueOf(r.get("base_url")));
    }

    // ---------------------------------------------------------------- 项目级事实（读盘，TTL 内共用）

    private static final long TTL_MS = 10 * 60 * 1000L;
    private final Map<Long, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private static class CacheEntry {
        final Facts facts;
        final long at;
        CacheEntry(Facts f, long at) { facts = f; this.at = at; }
    }

    /** 学习或改仓库目录后调用：下一个问题就重新读盘 */
    public void invalidate() {
        cache.clear();
        if (callSites != null) callSites.invalidate();
    }

    public void invalidate(long projectId) {
        cache.remove(projectId);
    }

    private Facts facts(long projectId) {
        CacheEntry ce = cache.get(projectId);
        if (ce != null && System.currentTimeMillis() - ce.at < TTL_MS) return ce.facts;
        Facts f = load(projectId);
        cache.put(projectId, new CacheEntry(f, System.currentTimeMillis()));
        return f;
    }

    private Facts load(long projectId) {
        Facts f = new Facts();
        List<Map<String, Object>> repos;
        try {
            repos = jdbc.queryForList("SELECT id,name,repo_type,local_path FROM repos WHERE project_id=? ORDER BY id",
                    projectId);
        } catch (Exception e) {
            f.error = "读不到这个项目的仓库清单（" + e + "）";
            return f;
        }
        List<String> frontRoots = new ArrayList<>();
        for (Map<String, Object> rp : repos) {
            String dir = rp.get("local_path") == null ? "" : String.valueOf(rp.get("local_path"));
            String name = String.valueOf(rp.getOrDefault("name", "未命名"));
            long id = ((Number) rp.get("id")).longValue();
            if (dir.isBlank() || !Files.isDirectory(Path.of(dir))) {
                f.unreadable.add(name);
                continue;
            }
            f.repoDirs.put(id, dir);
            f.repoNames.put(id, name);
            if ("frontend".equals(rp.get("repo_type"))) frontRoots.add(dir);
            else readServiceNames(id, Path.of(dir), f);
        }
        if (callSites != null && !frontRoots.isEmpty()) {
            f.index = callSites.scan(frontRoots);
        }
        assemble(f);
        return f;
    }

    /** 一个后端仓里每个模块的服务名与端口（仓登记的可能是整份检出，模块在下一层） */
    private void readServiceNames(long repoId, Path repoRoot, Facts f) {
        List<Path> modules = RepoModules.of(repoRoot);
        f.modulesOf.put(repoId, modules);
        for (Path m : modules) {
            for (String fn : List.of("bootstrap.yml", "bootstrap.yaml", "application.yml", "application.yaml",
                    "bootstrap.properties", "application.properties")) {
                Path p = m.resolve("src").resolve("main").resolve("resources").resolve(fn);
                String text = small(p);
                if (text == null) continue;
                String rel = RepoModules.rel(repoRoot, p);
                Matcher an = APP_NAME_FLAT.matcher(text);
                String name = an.find() ? an.group(1) : "";
                if (name.isEmpty()) {
                    // spring: / application: / name: xxx 那种分行 YAML；只看开头那一段里的第一处，
                    // 免得把 datasource 下的 name 当成服务名
                    String head = text.substring(0, Math.min(text.length(), 1200));
                    Matcher am = APP_NAME.matcher(head);
                    name = am.find() ? am.group(1) : "";
                }
                String port = intProp(text);
                if (!name.isEmpty()) {
                    // bootstrap 先读、赢了就不许被 application.yml 覆盖（同名键在两份里含义不同）
                    f.moduleName.putIfAbsent(relOf(m), name);
                    f.moduleNameEvidence.putIfAbsent(name, rel);
                    if (!port.isEmpty()) f.modulePort.putIfAbsent(relOf(m), port);
                }
                String locator = locatorEnabledPath(text);
                if (!locator.isEmpty()) {
                    f.gatewayFronted = true;
                    String ev = rel + " 里 " + locator + "=true（网关按注册中心服务名自动路由）";
                    f.gatewayModules.add(relOf(m));
                    // 一个模块一条网关证据：两个仓登记到同一份检出时同一文件会有两种相对写法，
                    // 逐仓再打一遍就是把同一条事实说两遍（界面那行依据会被挤掉别的模块）
                    if (f.gatewayModuleEvidence.putIfAbsent(relOf(m), ev) == null) f.gatewayEvidence.add(ev);
                    if (!port.isEmpty() && f.gatewayPort.isEmpty()) f.gatewayPort = port;
                }
            }
            // 依赖即证据：这一个模块挂了网关 starter，就说明"第一段是服务名"这套路由在它身上生效。
            // 为什么不走"扫 java 源码找 RouteLocator"：一份检出有几万个 .java，逐个读原文明显撑不住，
            // 而 locator.enabled 常常是 Nacos 下发的，本地 yml 里根本没有那一句。
            for (String build : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
                String text = small(m.resolve(build));
                String artifact = gatewayDependency(text, !build.equals("pom.xml"));
                if (!artifact.isEmpty()) {
                    f.gatewayFronted = true;
                    String ev = RepoModules.rel(repoRoot, m.resolve(build)) + " 依赖网关构件 " + artifact;
                    f.gatewayModules.add(relOf(m));
                    if (f.gatewayModuleEvidence.putIfAbsent(relOf(m), ev) == null) f.gatewayEvidence.add(ev);
                    String port = intProp(small(m.resolve("src").resolve("main").resolve("resources")
                            .resolve("application.yml")));
                    if (!port.isEmpty() && f.gatewayPort.isEmpty()) f.gatewayPort = port;
                    break;
                }
            }
        }
        f.modulesPerRepo.merge(repoId, modules.size(), Integer::sum);
    }

    /** server.port（YAML 分行与 properties 平铺两种形状都认），取第一处 */
    private static String intProp(String text) {
        if (text == null) return "";
        Matcher sp = SERVER_PORT_FLAT.matcher(text);
        if (sp.find()) return sp.group(1);
        String head = text.substring(0, Math.min(text.length(), 1500));
        Matcher pm = SERVER_PORT.matcher(head);
        return pm.find() ? pm.group(1) : "";
    }

    private static String relOf(Path m) {
        return m.toString().replace('\\', '/');
    }

    private static String small(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > 200_000L) return null;
            return FileTexts.decode(Files.readAllBytes(p)).text();
        } catch (Exception e) {
            return null;
        }
    }

    private static void assemble(Facts f) {
        if (f.index != null) {
            for (String b : f.index.bases.keySet()) f.deployBases.add(b);
        }
        f.basis = (f.index == null ? "这个项目没登记前端仓，前端调用点那条通路没跑" : f.index.basisNote())
                + "；网关证据：" + (f.gatewayEvidence.isEmpty() ? "无"
                        : String.join("、", f.gatewayEvidence.subList(0, Math.min(4, f.gatewayEvidence.size()))))
                + (f.unreadable.isEmpty() ? "" : "；读不到的仓：" + String.join("、", f.unreadable));
        if (f.gatewayEvidence.isEmpty() && f.deployBases.isEmpty() && f.allNames().isEmpty()) {
            f.noEvidenceNote = "盘上没有网关证据：既没找到 spring.application.name/网关路由配置，"
                    + "前端也没写 baseURL 实例，所以照卡片学到的路径发。"
                    + "若实际是微服务部署，到 [项目与仓库] 把网关仓与前端仓登记上再重学";
        }
    }

    // ---------------------------------------------------------------- 事实容器

    static class Facts {
        FrontCallSiteIndex.Index index;
        final Map<Long, String> repoDirs = new LinkedHashMap<>();
        final Map<Long, String> repoNames = new LinkedHashMap<>();
        final Map<Long, Integer> modulesPerRepo = new LinkedHashMap<>();
        /**
         * 仓 id → 这个仓的模块清单。{@code RepoModules.of} 一次要走上千个目录，
         * 而可测性体检会对全部卡片逐张问一次路径——不缓存就是每张卡重走一遍目录树。
         */
        final Map<Long, List<Path>> modulesOf = new LinkedHashMap<>();
        /** 模块目录 → spring.application.name */
        final Map<String, String> moduleName = new LinkedHashMap<>();
        final Map<String, String> moduleNameEvidence = new LinkedHashMap<>();
        final Map<String, String> modulePort = new LinkedHashMap<>();
        final Set<String> deployBases = new LinkedHashSet<>();
        final List<String> gatewayEvidence = new ArrayList<>();
        /**
         * 哪一个模块自己就是网关（它开着 locator / 依赖网关构件）。
         * 用途只有一个：网关<b>自己</b>那些 Controller 的卡不套服务名——它自己就是入口，
         * 前面那一段是 nginx 的挂载点，不是"按服务名找下游"。盘上的实证写法就是这个形状
         * （{@code /ni-gateway/cc/getToken}，而 {@code /ni-gateway/ni-cc-gateway/cc/getToken} 没人调过）。
         */
        final Set<String> gatewayModules = new LinkedHashSet<>();
        /** 模块目录 → 那一条让它成为网关的证据（缺输入/不适用时要能点名） */
        final Map<String, String> gatewayModuleEvidence = new LinkedHashMap<>();
        final List<String> unreadable = new ArrayList<>();
        boolean gatewayFronted;
        String gatewayPort = "";
        String basis = "";
        String noEvidenceNote = "";
        String error = "";

        boolean hasAnyEvidence() {
            return index != null || gatewayFronted || !moduleName.isEmpty();
        }

        /**
         * 这一张卡没有"自己的"依据时（不适用／缺输入），摆出来的就是盘上找到的那几项目级事实，
         * 让用户能核对"到底是没网关，还是这个仓没登记/没读到"。
         */
        List<String> evidence() {
            List<String> out = new ArrayList<>(gatewayEvidence);
            for (String b : deployBases) {
                if (b.contains("?")) continue;
                out.add("前端请求封装实例的 baseURL " + b + "（" + baseEvidence(b) + "）");
            }
            return out.size() <= 8 ? out : out.subList(0, 8);
        }

        /**
         * 卡片 source 里那个文件落在本仓的哪一个模块。
         *
         * <p>{@code source.file} 的坐标系是<b>仓库根目录</b>（{@code LearnService.relToRepo}，与 git/svn
         * 变更路径同一套），不是模块根。把整份检出登记成一个仓时（第五十轮 国泰海通 那个
         * 「统一回访平台服务」＝{@code D:/git/guotaihaitong/}），路径自己就带着模块那一段
         * （{@code unifiedvisit-platform/src/main/java/…}）；按模块目录去拼会拼出两段模块名，
         * 于是 38 张注解卡全停在"缺输入"。这里按坐标拼一次、再取<b>最深</b>的那个模块：
         * 聚合工程会在模块里再套模块，浅的那一个不是卡片真正所属的服务。
         */
        String moduleOwning(Long repoId, String relToRepo) {
            if (repoId == null || relToRepo == null || relToRepo.isBlank()) return null;
            String dir = repoDirs.getOrDefault(repoId, "");
            if (dir.isEmpty()) return null;
            String abs;
            try {
                abs = relOf(Path.of(dir).resolve(relToRepo.replace('/', java.io.File.separatorChar)).normalize());
            } catch (Exception e) {
                return null;   // 路径里混了非法字符：按"定位不到模块"回答，不猜
            }
            String best = null;
            for (Path m : modulesOf.getOrDefault(repoId, List.of())) {
                String d = relOf(m);
                if (abs.equals(d) || abs.startsWith(d + "/")) {
                    if (best == null || d.length() > best.length()) best = d;
                }
            }
            return best;
        }

        /** 这张卡所属模块自己是不是网关（是就返回那个模块目录，不套服务名；否则 null） */
        String gatewayModuleOf(Long repoId, String sourceJson) {
            if (gatewayModules.isEmpty() || repoId == null || sourceJson == null || sourceJson.isBlank()) {
                return null;
            }
            Matcher m = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]{4,200})\"").matcher(sourceJson);
            while (m.find()) {
                String owner = moduleOwning(repoId, m.group(1).replace('\\', '/'));
                if (owner != null && gatewayModules.contains(owner)) return owner;
            }
            return null;
        }

        /** 网关那一层自己的依据（说清"为什么不套服务名"） */
        String gatewayModuleNote(String dir) {
            String note = gatewayModuleEvidence.get(dir);
            return note == null ? "该模块自己带网关证据" : note;
        }

        List<FrontCallSiteIndex.Site> sitesOf(String cardPath) {
            if (index == null) return List.of();
            List<FrontCallSiteIndex.Site> hits = index.calledAs(cardPath);
            if (hits.size() <= 1) return hits;
            // 同一个对外地址的多处调用点算一处；不同对外地址（同一接口在两个实例下都调过）按出现次数排，
            // 次数相同再按补上的段数少者优先——补得越少越可能是那个规范写法
            Map<String, Integer> count = new LinkedHashMap<>();
            for (FrontCallSiteIndex.Site s : hits) count.merge(s.absPath, 1, Integer::sum);
            return count.entrySet().stream()
                    .sorted((a, b) -> {
                        int c = Integer.compare(b.getValue(), a.getValue());
                        if (c != 0) return c;
                        return Integer.compare(segments(a.getKey()), segments(b.getKey()));
                    })
                    .map(en -> hitsForPath(hits, en.getKey()))
                    .toList();
        }

        private static FrontCallSiteIndex.Site hitsForPath(List<FrontCallSiteIndex.Site> hits, String abs) {
            for (FrontCallSiteIndex.Site s : hits) if (s.absPath.equals(abs)) return s;
            return hits.get(0);
        }

        private static int segments(String path) {
            int n = 0;
            for (String s : path.split("/")) if (!s.isBlank()) n++;
            return n;
        }

        Set<String> allNames() {
            return new LinkedHashSet<>(moduleName.values());
        }

        List<String> deployBases() {
            return new ArrayList<>(deployBases);
        }

        /** 这段前缀在盘上哪一行写的（实证与推断路径都要能点名依据） */
        String baseEvidence(String base) {
            if (index != null) {
                String via = index.bases.get(base);
                if (via != null) return via;
            }
            return "未在任何前端实例里读到（按调用点归纳）";
        }

        String deployPrefix() {
            // 只认「真被某个调用点当前缀用过」的那些 baseURL，避免把 /njdf_http?returnType=json 那种
            // 非网关实例的地址当成部署前缀
            String best = "";
            int bestCount = 0;
            if (index == null) return "";
            for (String b : deployBases) {
                if (b.contains("?")) continue;
                int n = 0;
                for (FrontCallSiteIndex.Site s : index.sites) {
                    if (s.base.equals(b) && !s.absPath.equals(s.path)) n++;
                }
                if (n > bestCount) {
                    bestCount = n;
                    best = b;
                }
            }
            return best;
        }

        boolean baseUrlHitsGatewayPort(String baseUrl) {
            if (gatewayPort.isEmpty() || baseUrl == null || baseUrl.isBlank()) return false;
            try {
                URI u = URI.create(baseUrl.trim());
                int p = u.getPort() > 0 ? u.getPort() : (u.getScheme() != null && u.getScheme().equals("https") ? 443 : 80);
                return String.valueOf(p).equals(gatewayPort);
            } catch (Exception e) {
                return false;
            }
        }

        /** 卡片所属模块的服务名：先按 source 文件定位模块，再退到"这个仓只有一个模块/一个名字" */
        String serviceName(Long repoId, String sourceJson) {
            String dir = repoId == null ? "" : repoDirs.getOrDefault(repoId, "");
            if (dir.isEmpty()) return "";
            if (sourceJson != null && !sourceJson.isBlank()) {
                Matcher m = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]{4,200})\"").matcher(sourceJson);
                while (m.find()) {
                    String name = moduleName.get(moduleOwning(repoId, m.group(1).replace('\\', '/')));
                    if (name != null && !name.isEmpty()) return name;
                }
            }
            // 仓名与模块名同源时（登记的仓就是一个服务）按名字对上；只有一个服务名可用时也用那一个
            List<String> names = namesOfRepo(dir);
            if (names.size() == 1) return names.get(0);
            String rn = repoNames.getOrDefault(repoId, "");
            for (String n : names) if (n.equalsIgnoreCase(rn)) return n;
            return "";
        }

        String serviceEvidence(Long repoId, String sourceJson) {
            String dir = repoId == null ? "" : repoDirs.getOrDefault(repoId, "");
            if (dir.isEmpty()) return "卡片没归属仓库，也无从找服务名";
            if (sourceJson != null && !sourceJson.isBlank()) {
                Matcher m = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]{4,200})\"").matcher(sourceJson);
                while (m.find()) {
                    String hit = moduleOwning(repoId, m.group(1).replace('\\', '/'));
                    if (hit != null) {
                        String via = moduleNameEvidence.get(moduleName.get(hit));
                        return (via == null ? hit : via);
                    }
                }
            }
            List<String> names = namesOfRepo(dir);
            if (names.size() == 1) return moduleNameEvidence.getOrDefault(names.get(0), "该仓唯一一个服务名");
            return "该仓里有 " + names.size() + " 个服务名，卡片没带能定位模块的 source";        }

        private List<String> namesOfRepo(String dir) {
            List<String> out = new ArrayList<>();
            String prefix = dir.replace('\\', '/');
            for (Map.Entry<String, String> en : moduleName.entrySet()) {
                if (en.getKey().startsWith(prefix)) out.add(en.getValue());
            }
            return new ArrayList<>(new LinkedHashSet<>(out));
        }

        String serviceMissingNote(Long repoId) {
            String name = repoId == null ? "未归属仓库" : repoNames.getOrDefault(repoId, "未归属仓库");
            int mods = repoId == null ? 0 : modulesPerRepo.getOrDefault(repoId, 0);
            List<String> names = repoId == null ? List.of() : namesOfRepo(repoDirs.getOrDefault(repoId, ""));
            if (names.isEmpty()) {
                return "仓库「" + name + "」的 " + mods + " 个模块里没读到 spring.application.name："
                        + "要么这层的配置文件不在盘上（只登记了编译产物/部分目录），"
                        + "要么这个服务是靠 Nacos 下发的配置起的名。补法：把带 bootstrap.yml 的那层目录登记进仓库再重学";
            }
            return "卡片定位不到所属模块（这仓有 " + names.size() + " 个服务名："
                    + String.join("、", names.subList(0, Math.min(6, names.size())))
                    + "），而卡片的 source 又对不上其中任何一个模块目录。"
                    + "可在 [接口卡] 人工改这一张的路径，或按 [前端调用点] 那一条实证来";
        }
    }
}
