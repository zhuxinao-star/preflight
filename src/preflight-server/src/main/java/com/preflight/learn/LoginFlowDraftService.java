package com.preflight.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.parser.DefaultValueRules;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 「这一张登录卡能自动铺成什么样」的读取期单点（第四十八轮）。
 *
 * <p>第 47 轮把建登录流那一屏的假模板删掉了，代价是新建时每个键都要人敲。本轮补的是同一件事的另一半：
 * <b>凡是盘上真学到的，就替人填进去；学不到的，逐格点名说为什么空着</b>。判据跟第 46 轮那条完全一致
 * （学到的默认值 / 规则值 → 否则空串），请求体键的展开直接复用 {@link DispatchBodyPlanService#bodyKeys}，
 * 所以"卡片入参怎么变成传输形状"这道题全站只有一个出口。
 *
 * <p>三条硬约束，都是前几轮定过的口径：
 * <ul>
 *   <li><b>不编值</b>。响应里哪个字段是会话，HTTP JSON 卡没有学到出参的通路（出参目前只从 KJDP 信封、
 *       Mapper 列名和存储过程形参学回来），所以这里<b>不</b>给 {@code $.data.token} 这种漂亮却凭空来的取值式；
 *       能给的是 Cookie 那条名字无关的通路（{@code extract_cookie} 把整罐收进 {@code pf_cookie}，
 *       执行层本来就认这个变量）。</li>
 *   <li><b>账号密码永远是引用</b>。认出来的账号/密码格填 {@code {{cred.username}}} / {@code {{cred.password}}}，
 *       而且<b>排在学到的默认值之前</b>：DTO 里写死的初始值当账号发出去必然登不进去，
 *       引擎那道"没绑凭据就拦下"的前置闸门也会形同虚设。被换掉的那份初始值照样要点名（{@code cred_note}），
 *       不许悄悄丢一条学到的证据。明文只待在凭据保险库里。</li>
 *   <li><b>读取期现算、绝不落库</b>。线索跟着重学走，写进任何表都会立刻过期。</li>
 * </ul>
 *
 * <p>没有项目名、没有框架名分支：认的全是跨项目通行的键名与路径语义。
 */
@Service
public class LoginFlowDraftService {

    /** 留空点名最多摆这么多条（基数保护：异常卡学出几百个键时不许把响应刷爆，条数照实说） */
    private static final int BLANK_CAP = 12;

    /** 会话变量名：执行层认它（人工 Cookie 接管与自动登录共用，见 HttpExecutorService 的 pf_cookie 分支） */
    private static final String COOKIE_VAR = "pf_cookie";

    private final DispatchBodyPlanService planService;
    private final ObjectMapper mapper;

    public LoginFlowDraftService(DispatchBodyPlanService planService, ObjectMapper mapper) {
        this.planService = planService;
        this.mapper = mapper;
    }

    /**
     * 一张登录卡的草稿，带上两条"这一步实际怎么发"的证据源（第四十九轮前端调用点、第五十一轮对外路径单点）。
     *
     * <p>注解学到的路径是<b>应用内部</b>的（{@code /cc/getToken}），前端发出去的却是
     * {@code /ni-gateway/cc/getToken}——网关那一层前缀不在注解里。测试台与登录流要发的必须是对外那份写法，
     * 所以这里按改写铺地址，并把"改写是从哪句代码来的"原样说出来；改写与学到的路径并存时
     * 两条都留（学的是这张卡，发的是那个地址）。
     *
     * <p>那个调用点还带着卡片这一侧压根没有的两条事实：<b>这一步的返回里留下哪个字段</b>
     * （HTTP JSON 卡没有出参通路，取值式只在前端那一行写着）与<b>这一步算不算成</b>
     * （前端响应拦截器里那句 {@code data.code == 0}）。卡片既然被认成"就是那一步"，就照前端那份接上。
     *
     * <p>前端仓没登记、但盘上有网关证据的项目：地址那一段改问 {@link GatewayRoutingService}（第五十轮那个
     * <b>唯一出口</b>），草稿铺的与测试台真发的、复制的 cURL、留痕里那条就是同一个答案。这里不另算一套前缀——
     * 第二套判据的坏法是"弹屏里看着对、点执行 405"。
     *
     * @param asCalled 这张卡在前端的实际写法＋那个调用点（null＝没改写出地址）
     * @param facts    这一趟前端扫描的整体事实（只用"这一步算不算成"那一条）；null＝没扫到前端
     * @param route    对外路径单点对这一张卡的回答（{@code null}＝没得问）；只在没有前端改写时才用它
     * @param envName  这一份对外路径是按哪个环境的地址算的（不说出来，人就会把它当全局事实）
     */
    public Map<String, Object> cardDraft(String method, String path, String schemaJson,
                                         FrontLoginChainService.Rewrite asCalled,
                                         FrontLoginChainService.Result facts,
                                         GatewayRoutingService.Answer route, String envName) {
        Map<String, Object> out = draftOf(method, path, schemaJson);
        out.put("from_card", true);
        out.put("basis", "学到的接口卡：" + path);
        // 链的键要跟前一条通路用同一个出口算（这一趟读到的部署前缀先摘掉），否则同一模块的卡与
        // 调用点各算各的键，一次登录被切成两条链，两条都"没有登录那一步"
        if (facts != null) out.put("chain", facts.chainKey(path));
        if (asCalled == null) {
            applyRoute(out, path, route, envName);
            return out;
        }
        FrontLoginChainService.CallSite site = asCalled.site;
        // 只有"这个调用点发的就是这个地址"才算这一张卡的前端事实。改写还有第二种来源：卡片压根没有调用点
        // （验证码图那种），前缀是<b>借</b>同目录另一步的 baseURL 补的——那时那个调用点是邻居，
        // 把它的取值式抄过来等于给两张卡铺了同一条出参，界面上还看不出来。
        boolean sameCall = site != null && site.absPath != null && site.absPath.equals(asCalled.path);
        if (sameCall) {
            out.put("basis", "学到的接口卡 " + path + "；前端调用点 " + site.evidence() + "（" + site.via + "）");
            if (site.extract != null && !site.extract.var.isEmpty() && !site.extract.path.isEmpty()) {
                Map<String, Object> step = asMap(out.get("step"));
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put(site.extract.var, site.extract.path);
                step.put("extract", ex);
                out.put("session_note", "这一步的返回里留下 " + site.extract.var + "：" + site.extract.path
                        + "（取值式是从前端那一行读回来的，" + site.extract.evidence + "）；"
                        + "多条候选按前端自己写的顺序试，取到第一个非空就用。");
            }
        } else if (site != null && site.extract != null && !site.extract.var.isEmpty()) {
            out.put("step_note", "这张卡自己没有前端调用点，地址前缀是借同目录那一步的 baseURL 补的："
                    + "那一步的出参取值式不适用于它，extract 这一格要自己指。");
        }
        if (facts != null && !facts.successField.isEmpty()) {
            Map<String, Object> step = asMap(out.get("step"));
            step.put("success_field", facts.successField);
            step.put("success_value", facts.successValue);
        }
        String learned = path == null ? "" : path;
        if (asCalled.path != null && !asCalled.path.isBlank() && !asCalled.path.equals(learned)) {
            Map<String, Object> step = asMap(out.get("step"));
            String stepPath = String.valueOf(step.get("path"));
            // 只换前缀那一段：查询串是这张卡的入参铺出来的，跟地址怎么被叫法无关
            step.put("path", asCalled.path + stepPath.substring(learned.length()));
            // 链的键不动：它认的是"这一步属于本项目哪个模块"，两边都按项目内部的那个路径算。
            // 发出去的地址带不带网关前缀是每个调用点各自的事，按它算就把一次登录切成两条链。
            out.put("path_rewritten", "这张卡学到的是 " + learned + "，草稿按前端实际的写法发 " + asCalled.path
                    + "（依据：" + asCalled.via + "）");
        }
        return out;
    }

    /**
     * 地址那一段改用对外那一份（第五十一轮）：登录流的每一步是 {@code HttpExecutorService} 拿环境地址
     * 直接接步骤里的路径发出去的，所以草稿铺内部路径＝这条流一跑就 405。路径从哪来只问
     * {@link GatewayRoutingService}（第五十轮那个唯一出口），这里只负责把它的结论并进草稿并说清依据；
     * 算不出（{@code NOT_APPLICABLE}／{@code MISSING_INPUT}）就照学到的内部路径铺，并把"为什么没换"写进去。
     *
     * <p>链的键仍按项目内部路径算：它认的是"这一步属于哪个模块"，前缀是每个调用点各自的事。
     */
    private void applyRoute(Map<String, Object> out, String learned,
                            GatewayRoutingService.Answer route, String envName) {
        if (route == null) return;
        String internal = learned == null ? "" : learned;
        String where = envName == null || envName.isBlank() ? "项目里那个环境（没登记地址）" : "环境「" + envName + "」的地址";
        if (GatewayRoutingService.MISSING_INPUT.equals(route.state())) {
            // 这一档是"差一条盘上证据"：不换路径，但要点名缺哪一行，否则人只会看到一条内部路径
            out.put("path_rewritten", "这一步照学到的内部路径铺（按" + where + "现算的结论是「"
                    + route.stateLabel() + "」：" + route.summary() + "）");
            return;
        }
        if (!route.changed() || route.path() == null || route.path().isBlank()
                || !route.path().startsWith("/") || !route.path().endsWith(internal)) {
            return;     // 不适用／本来就一样：路径没换就别在每一步后面再打一句
        }
        Map<String, Object> step = asMap(out.get("step"));
        String stepPath = String.valueOf(step.get("path"));
        // 只换前缀那一段：查询串是这张卡的入参铺出来的，跟地址怎么被叫法无关
        if (!stepPath.startsWith(internal)) return;
        step.put("path", route.path() + stepPath.substring(internal.length()));
        out.put("path_rewritten", "这张卡学到的是 " + internal + "，草稿按对外写法铺 " + route.path()
                + "（按" + where + "现算：" + route.stateLabel() + "："
                + String.join("；", route.evidence().isEmpty() ? List.of(route.summary()) : route.evidence()) + "）");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /**
     * 注解扫不到的那一步（登录入口本身）：<b>前端调用点</b>这一条通路的草稿，形状与卡片草稿完全一致，
     * 所以 {@link #flowDrafts} 一份逻辑同时铺两种来源。
     *
     * <p>只有盘上真写着的东西才进草稿：请求体的格来自前端那份<b>登录负载字面量</b>（一格一格按名字认语义），
     * 加密因子是那一行右半边写着的字面量，整包算法是"调用名或它 import 自哪个模块"，
     * 会话是响应拦截器里那一句。验证码只有<b>位数</b>这一条规则学得动（{@code {len: 4}}），
     * 所以摆一个自述为"占位串"的等长假值并把原因点名——这跟第 48 轮那条"不编值"不冲突：
     * 长度是盘上写的事实，码值不是，界面上必须让人看见这一格是假的。
     *
     * @param site  前端调用点（absPath 已经是真打出去的那个地址）
     * @param facts 这一趟扫描的整体事实（登录负载、加密、会话、成败判据）
     */
    public Map<String, Object> callSiteDraft(FrontLoginChainService.CallSite site,
                                             FrontLoginChainService.Result facts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", site.role);
        out.put("chain", site.chain);
        out.put("from_card", false);
        out.put("basis", "前端调用点 " + site.evidenceAll() + "（" + site.via + "）");
        out.put("needs_cred", false);

        List<Map<String, Object>> blanks = new ArrayList<>();
        Map<String, Object> step = new LinkedHashMap<>();
        List<FrontLoginChainService.Key> keys = "login".equals(site.role) ? facts.loginBody() : List.of();
        String verb = site.verbOrGuess(!keys.isEmpty());
        step.put("method", verb);
        if (site.verbGuessed()) {
            out.put("verb_note", "这一行的动词是前端代码里没写出来的：带请求体所以按 " + verb + " 摆，不对就改这一格");
        }
        step.put("path", site.absPath == null ? site.path : site.absPath);
        if (!site.contentType.isEmpty()) step.put("content_type", site.contentType);

        int keyCount = 0;
        int filled = 0;
        int blankCount = 0;
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> encodeFields = new LinkedHashMap<>();
        List<String> factors = new ArrayList<>();
        Map<String, String> fieldCodec = facts.fieldCodec();
        for (FrontLoginChainService.Key k : keys) {
            keyCount++;
            String n = DefaultValueRules.normalize(k.name);
            String value;
            String why = null;
            if (com.preflight.parser.LoginSemantics.isAccount(n)) {
                value = "{{cred.username}}";
                out.put("needs_cred", true);
            } else if (com.preflight.parser.LoginSemantics.isPassword(n)) {
                value = "{{cred.password}}";                  // 明文只进凭据保险库，那一行的右半边再长也不抄进草稿
                out.put("needs_cred", true);
                String factor = fieldCodec.get(n);
                if (factor != null) {
                    encodeFields.put(k.name, "ka");
                    if (!factors.contains(factor)) factors.add(factor);
                }
            } else {
                FrontLoginChainService.CallSite prod = facts.producerOf(k.name, site.chain);
                String[] len = codeLen(facts);
                if (prod != null && prod != site) {
                    value = "{{" + prod.extract.var + "}}";   // 这一格用的是这条链上一步留下的那个值
                } else if (com.preflight.parser.LoginSemantics.isCode(n) && len != null) {
                    value = placeholder(len[0]);
                    why = "键名 " + k.name + " 是验证码：只学得动位数（前端自己写着 len: " + len[0] + "，"
                            + len[1] + "），所以摆了一位数控够的占位串——这不是学到的码值，"
                            + "测试环境按不校验放行才能这么发，真环境要人工读图或走环境里的 Cookie 接管";
                } else {
                    value = "";
                    why = "键名 " + k.name + " 在 " + facts.loginBodyEvidence() + " 的右半边是 " + k.valueExpr
                            + "：那是页面上另一个输入框，运行时才有值，照空串发";
                }
            }
            body.put(k.name, value);
            if (why == null) {
                filled++;
            } else {
                blankCount++;
                if (blanks.size() < BLANK_CAP) blanks.add(blank(k.name, why));
            }
        }
        if (!body.isEmpty()) step.put("body", body);
        if (!encodeFields.isEmpty()) {
            step.put("encode_fields", encodeFields);
            if (!factors.isEmpty()) {
                step.put("encrypt_factor", factors.get(0));
                out.put("factor_note", "这几格发之前要先做一次逐位变换：" + encodeFields.keySet()
                        + "，加密因子用的是盘上那个字面量 \"" + factors.get(0) + "\"（"
                        + facts.loginBodyEvidence() + " 那一行右半边写的）");
                if (factors.size() > 1) {
                    out.put("factor_conflict", "盘上有 " + factors.size() + " 个不同的加密因子（"
                            + String.join("、", factors.subList(0, Math.min(4, factors.size())))
                            + "），引擎只认一个：草稿用了第一个，其余自己核对");
                }
            }
        }
        FrontLoginChainService.Codec codec = wholeCodecFor(facts, site);
        if (codec != null) {
            step.put("encode", codec.engineAlgo());
            step.put("encode_key", "{{" + codec.keyVar + "}}");
            out.put("codec_note", "整个请求体发之前要整包加密：" + codec.algoNote()
                    + "；密钥是 " + codec.keyVar + "，它正是上一步（" + facts.producerOf(codec.keyVar, site.chain).absPath
                    + "）返回里取出来的那个值");
        } else {
            FrontLoginChainService.Codec half = halfLearnedCodecFor(facts, site);
            if (half != null) {
                blanks.add(blank("整包加密", half.algoNote() + "：这一步要加密，蒙一个算法出去就是把值编给引擎发，"
                        + "所以 encode 这一格留空，要发自己补"));
            }
        }
        if ("login".equals(site.role)) {
            FrontLoginChainService.Session s = facts.session();
            if (s == null) {
                out.put("session_note", "这一步的返回里会话在哪（响应头？响应体哪个字段？）前端代码没读到：要自己加 "
                        + "extract 指来源，否则后面的步骤带不上会话");
            } else {
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put(s.var, "header:" + s.header);
                step.put("extract", ex);
                Map<String, Object> hdr = new LinkedHashMap<>();
                hdr.put(s.header, (s.scheme.isEmpty() ? "" : s.scheme + " ") + "{{" + s.var + "}}");
                Map<String, Object> apply = new LinkedHashMap<>();
                apply.put("header", hdr);
                out.put("apply", apply);
                out.put("session_note", "会话在响应头 " + s.header + " 里（依据 " + s.file + ":" + s.line
                        + " 那句赋值），带出去要加 \"" + (s.scheme.isEmpty() ? "" : s.scheme + " ")
                        + "\" 这一串前缀：后面的步骤自动带上这个头");
            }
        }
        if (!facts.successField.isEmpty()) {
            step.put("success_field", facts.successField);
            step.put("success_value", facts.successValue);
        }
        out.put("step", step);
        truncateNote(blanks, blankCount);
        out.put("blanks", blanks);
        out.put("key_count", keyCount);
        out.put("filled", filled);
        out.put("blank_count", blankCount);
        if ("logout".equals(site.role)) {
            out.put("step_note", "这是登出语义：铺进登录流会立刻把刚拿到的会话打没，所以整条草稿不含它。");
        }
        if ("other".equals(site.role)) {
            out.put("step_note", "这一跳既不是取码也不是提交账号密码（路径末段没写出登录语义）：不铺进流。");
        }
        return out;
    }

    /**
     * 这一步的整包加密：只认<b>密钥对得上号</b>的那一次——密钥是某一步返回里留下的那个值，
     * 才说明"先取码、再拿它加密登录包"这条链真的存在。算法或模式没学全的不算学到（交空，由出口点名）。
     */
    private static FrontLoginChainService.Codec wholeCodecFor(FrontLoginChainService.Result facts,
                                                              FrontLoginChainService.CallSite site) {
        FrontLoginChainService.Codec half = null;
        for (FrontLoginChainService.Codec c : facts.codecs) {
            FrontLoginChainService.CallSite prod = facts.producerOf(c.keyVar, site.chain);
            if (prod == null) continue;
            if (!c.engineAlgo().isEmpty()) return c;
            if (half == null) half = c;
        }
        return null;
    }

    /** 学到了一半（有算法没模式，或有模式没算法）的那一次：只为点名用，不许拿它铺 encode */
    private static FrontLoginChainService.Codec halfLearnedCodecFor(FrontLoginChainService.Result facts,
                                                                    FrontLoginChainService.CallSite site) {
        for (FrontLoginChainService.Codec c : facts.codecs) {
            FrontLoginChainService.CallSite prod = facts.producerOf(c.keyVar, site.chain);
            if (prod == null) continue;
            if (c.engineAlgo().isEmpty()) return c;
        }
        return null;
    }

    /** 前端自己写的验证码位数规则（"4@文件:行号"）；没读到就返回 null，出口不许蒙一个长度 */
    private static String[] codeLen(FrontLoginChainService.Result facts) {
        String raw = facts.captchaLen();
        int at = raw.indexOf('@');
        if (at <= 0) return null;
        String n = raw.substring(0, at);
        if (!n.matches("\\d{1,2}")) return null;
        int len = Integer.parseInt(n);
        if (len < 3 || len > 8) return null;               // 超出这个区间就不是"验证码位数"了，别拿它铺值
        return new String[]{n, raw.substring(at + 1)};
    }

    /** 位数控够的占位串：一眼是假的（1234…），不冒充真码值 */
    private static String placeholder(String len) {
        String digits = "123456789";
        int n = Integer.parseInt(len);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(digits.charAt(i % digits.length()));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> draftOf(String method, String path, String schemaJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        String role = roleOf(path);
        out.put("role", role);
        out.put("chain", chainOf(path));

        List<Map<String, Object>> blanks = new ArrayList<>();
        Map<String, Object> step = new LinkedHashMap<>();
        String verb = method == null || method.isBlank() ? "POST" : method;
        step.put("method", verb);
        out.put("needs_cred", false);

        Map<String, Object> schema = parse(schemaJson);
        if (schema.isEmpty()) {
            if (schemaJson != null && !schemaJson.isBlank()) {
                // 这不是"某个键留空"，整张卡都没有键，所以走 step_note 这个出口，别混进 blanks 的计数
                out.put("step_note", "这张卡的入参 schema 解析不动，只铺得下地址：一个键都没学到");
            }
            step.put("path", path);
            out.put("step", step);
            out.put("blanks", blanks);
            out.put("key_count", 0);
            out.put("filled", 0);
            out.put("blank_count", 0);
            return out;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        List<String> query = new ArrayList<>();
        List<String> over = new ArrayList<>();
        Set<String> slots = new HashSet<>();
        int keyCount = 0;
        int filled = 0;
        int blankCount = 0;
        // 动词是 GET/DELETE 时引擎不给挂请求体：学到的键「有值也落不下」，所以按没给到值记账
        boolean bodyFits = !verb.equalsIgnoreCase("GET") && !verb.equalsIgnoreCase("DELETE");
        int dropped = 0;
        for (Map<String, Object> k : planService.bodyKeys(schema, "", "", Optional.empty())) {
            keyCount++;
            String leaf = leafOf(String.valueOf(k.get("path")));
            String value;
            String why = null;
            String from = String.valueOf(k.getOrDefault("defFrom", "none"));
            String def = String.valueOf(k.getOrDefault("def", ""));
            String n = DefaultValueRules.normalize(leaf);
            if (com.preflight.parser.LoginSemantics.isAccount(n)) {
                value = "{{cred.username}}";
                out.put("needs_cred", true);
                overridden(over, n, from, def);
            } else if (com.preflight.parser.LoginSemantics.isPassword(n)) {
                value = "{{cred.password}}";
                out.put("needs_cred", true);
                overridden(over, n, from, def);
            } else if (DispatchBodyPlanService.givesValue(from) && !def.isEmpty()) {
                value = def;                        // 学到的默认值或跨项目规则值（op_code=1 那一类）
            } else {
                value = "";
                why = whyBlank(n, leaf, k);
            }
            String in = String.valueOf(k.getOrDefault("in", ""));
            boolean isQuery = "query".equals(in) || "param".equals(in);
            boolean landed;
            if (isQuery) {
                String q = leaf + "=" + value;
                landed = slots.add("q:" + q);
                if (landed) query.add(q);
            } else if (bodyFits) {
                putPath(body, String.valueOf(k.get("path")), value);
                landed = slots.add("b:" + k.get("path"));
            } else {
                landed = false;
                dropped++;
            }
            // 记账口径只有一条：这一步的草稿里真带上了值 = filled，其余（值留空 / 落不下 / 位置重了）= blank_count
            if (why == null && landed) {
                filled++;
            } else {
                blankCount++;
                if (why != null) {
                    if (blanks.size() < BLANK_CAP) blanks.add(blank(leaf, why));
                } else if (bodyFits && blanks.size() < BLANK_CAP) {
                    blanks.add(blank(leaf, "键名 " + leaf + " 学到了值，但草稿里这个位置已经有了一条"
                            + "（同名键只落得下一个）"));
                }
                // !bodyFits 的键不逐格刷屏，统一由下面那条「请求体」整批点名
            }
        }
        if (dropped > 0) {
            blanks.add(blank("请求体", "学到 " + dropped + " 个请求体键，但这一张卡的动词是 " + verb
                    + "：这些键没地方放，要自己确认这一步到底怎么传"));
        }
        if (!over.isEmpty()) {
            // 盘上确实写了初始值，这一处不拿它当账号——登录流要跑的是凭据保险库里那一条，
            // 但被换掉的值要点名，否则等于悄悄丢了一条学到的证据。
            out.put("cred_note", "这几个格盘上有初始值（" + String.join("、", over)
                    + "），仍填成凭据引用：登录流要跑的是你在「登录凭据」里选的那条账号，"
                    + "不是代码里写死的初始值。要按代码初始值发就把这一格改回去。");
        }
        StringBuilder p = new StringBuilder(path == null ? "" : path);
        if (!query.isEmpty()) p.append(p.indexOf("?") >= 0 ? "&" : "?").append(String.join("&", query));
        step.put("path", p.toString());
        if (!body.isEmpty()) step.put("body", body);
        if ("login".equals(role)) {
            step.put("extract_cookie", COOKIE_VAR);
            out.put("session_note", "会话若在 Cookie 里已经接好：整罐收进 " + COOKIE_VAR
                    + "，测试台会自动当 Cookie 头带上。若这个系统的会话其实在响应头或响应体的某个字段里，"
                    + "这一步的返回我们没有学到（HTTP JSON 卡没有出参通路），要自己加 extract 指来源。");
        }
        out.put("step", step);
        truncateNote(blanks, blankCount);
        out.put("blanks", blanks);
        out.put("key_count", keyCount);
        out.put("filled", filled);
        out.put("blank_count", blankCount);
        if (!"login".equals(role) && !"logout".equals(role)) {
            out.put("step_note", "这一步不是登录本身：它的返回里哪个字段要留给后面用，学习没学到出参，要自己指。");
        }
        if ("logout".equals(role)) {
            out.put("step_note", "这是登出语义：铺进登录流会立刻把刚拿到的会话打没，所以整条草稿不含它。");
        }
        return out;
    }

    /**
     * 按"哪条链"铺成整条流：只有真学到登录那一步的链才铺得出，其余点名说铺不出来的原因。
     *
     * @param drafts 每张卡的草稿，必须带 id/method/path/role/chain/step/blanks/needs_cred
     */
    public Map<String, Object> flowDrafts(List<Map<String, Object>> drafts) {
        Map<String, List<Map<String, Object>>> byChain = new LinkedHashMap<>();
        int notLoginish = 0;
        for (Map<String, Object> d : drafts) {
            // 末段看不出是登录链哪一步的那些（查用户资料那种）：不当步骤铺，但仍留在链里当"这条链是真学到的"证据
            if ("other".equals(d.get("role"))) notLoginish++;
            byChain.computeIfAbsent(String.valueOf(d.get("chain")), x -> new ArrayList<>()).add(d);
        }
        List<Map<String, Object>> flows = new ArrayList<>();
        List<Map<String, Object>> noLogin = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> en : byChain.entrySet()) {
            List<Map<String, Object>> all = en.getValue();
            List<Map<String, Object>> cards = all.stream()
                    .filter(c -> !"other".equals(c.get("role"))).toList();
            // 锚点认的是"这条链上盘上真有一张学到的接口卡"，不要求那张卡自己就能看出是登录链的哪一步：
            // 末段写得含不含糊是那个 Controller 的命名习惯，不是"这个模块属不属于本项目"的证据。
            boolean anyCard = all.stream().anyMatch(c -> Boolean.TRUE.equals(c.get("from_card")));
            boolean hasLogout = cards.stream().anyMatch(c -> "logout".equals(c.get("role")));
            List<Map<String, Object>> logins = cards.stream().filter(c -> "login".equals(c.get("role"))).toList();
            if (!anyCard) {
                // 全是前端调用点读来的：这条链在盘上没有任何一张对得上的接口卡，等于"某个页面调了几个
                // 带登录字样的地址"——坐席签到、聊天会话那类模块长这样，铺成流就是拿别人的登录入口冒充本项目
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chain", en.getKey());
                row.put("cards", cards.size());
                row.put("reason", "这条链的 " + cards.size() + " 步全是前端调用点读来的，盘上没有学到任何一张"
                        + "对得上的接口卡：无法确认它就是本项目的登录入口（客服签到、会话管理那一类模块自己也带"
                        + "signIn/getSession 这种地址），所以不铺成流。确认要用它，手动【新增】一条自己填");
                noLogin.add(row);
                continue;
            }
            if (cards.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chain", en.getKey());
                row.put("cards", 0);
                row.put("reason", "这条链上学到的那 " + all.size() + " 张卡，路径末段都没写出它是取码还是提交账号密码"
                        + "（查用户资料那种），认不出登录那一步，铺不出一条流");
                noLogin.add(row);
                continue;
            }
            if (logins.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chain", en.getKey());
                row.put("cards", cards.size());
                row.put("reason", hasLogout
                        ? "这条链只学到登出/取数语义的卡，没学到登录那一步（登录入口常在依赖包里，注解扫不到），铺不出一条流"
                        : "这条链学到 " + cards.size() + " 张登录语义的卡，但里面没有真正提交账号密码的那一步，铺不出一条流");
                noLogin.add(row);
                continue;
            }
            List<Map<String, Object>> steps = new ArrayList<>();
            List<String> bases = new ArrayList<>();
            List<Map<String, Object>> blanks = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            Map<String, Object> apply = null;
            boolean cred = false;
            int keyCount = 0;
            int filled = 0;
            int blankCount = 0;
            int fromCard = 0;
            int fromFront = 0;
            Map<String, Integer> perRole = new LinkedHashMap<>();
            // 先取码 / 取号，后登录：登录那一步放最后，否则前面几步白跑（步骤对象里不留 _role 这类别名键，
            // 铺进文本框的就是引擎真认识的字段）
            List<Map<String, Object>> ordered = new ArrayList<>(cards);
            ordered.sort((a, b) -> Integer.compare(orderOf(a), orderOf(b)));
            for (Map<String, Object> c : ordered) {
                if ("logout".equals(c.get("role"))) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> step = new LinkedHashMap<>((Map<String, Object>) c.get("step"));
                step.putIfAbsent("name", stepName(String.valueOf(step.get("path")), c));
                steps.add(step);
                bases.add(String.valueOf(c.getOrDefault("basis", "")));
                if (Boolean.TRUE.equals(c.get("from_card"))) fromCard++; else fromFront++;
                keyCount += asInt(c.get("key_count"));
                filled += asInt(c.get("filled"));
                blankCount += asInt(c.get("blank_count"));
                perRole.merge(String.valueOf(c.get("role")), 1, Integer::sum);
                if (Boolean.TRUE.equals(c.get("needs_cred"))) cred = true;
                if (apply == null && c.get("apply") instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> a = (Map<String, Object>) c.get("apply");
                    apply = a;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cb = (List<Map<String, Object>>) c.getOrDefault("blanks", List.of());
                for (Map<String, Object> b : cb) {
                    if (blanks.size() >= BLANK_CAP) break;
                    Map<String, Object> row = new LinkedHashMap<>(b);
                    row.put("path", step.get("path"));
                    blanks.add(row);
                }
                for (String kk : new String[]{"session_note", "codec_note", "factor_note", "verb_note",
                        "path_rewritten", "cred_note", "step_note"}) {
                    Object note = c.get(kk);
                    if (note != null && !notes.contains(String.valueOf(note))) notes.add(String.valueOf(note));
                }
            }
            for (Map.Entry<String, Integer> re : perRole.entrySet()) {
                if (re.getValue() > 1) {
                    notes.add("这条链有 " + re.getValue() + " 步都是" + roleLabel(re.getKey())
                            + "语义的（真实登录只走一条）：多出来的那几步自己删");
                }
            }
            if (perRole.containsKey("captcha")) {
                notes.add("这条链要验证码：图片得人工读，系统不认图。填完值可以【试跑】，读不出码就走环境里的 Cookie 接管");
            }
            if (cred) {
                notes.add("有账号密码格用的是 {{cred.username}} / {{cred.password}}：到上面绑定一条登录凭据，明文不进库");
            }
            if (apply != null) {
                // 打成 JSON 而不是 Map.toString（后者是 {header={x=y}} 那种没人认得的形状）
                notes.add("登录那一步取到的会话，后面的步骤要带上它：" + jsonOf(apply)
                        + "（新建登录流时这一份跟着整条链一起铺进「会话注入」那一格；那一格已经写了东西会先问过再覆盖）");
            }
            // 留空点名的基数由 blanks 自己收尾（一张卡/一条流只有一处说这件事）
            truncateNote(blanks, blankCount);
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("chain", en.getKey());
            flow.put("steps", steps);
            flow.put("bases", bases);
            if (apply != null) flow.put("apply", apply);
            flow.put("blanks", blanks);
            flow.put("notes", notes);
            flow.put("step_count", steps.size());
            flow.put("key_count", keyCount);
            flow.put("filled", filled);
            flow.put("blank_count", blankCount);
            // 只数真铺进这一步的那几张（登出语义在上面的循环里就跳过了）：
            // 探针抓出 learned_cards 3 / step_count 2 的红闪——把没铺的登出也算成"学到的卡"是假数
            flow.put("learned_cards", fromCard);
            flow.put("front_sites", fromFront);
            flow.put("evidenced", (int) bases.stream().filter(x -> !x.isBlank()).count());
            // 占位串单独数一类：它是"有值但那值是假的"（验证码位数盘上写着、码值学不动），
            // 与照空串摆的键不是一回事，界面上要分开说
            flow.put("placeholder_count", (int) blanks.stream()
                    .filter(b -> String.valueOf(b.getOrDefault("why", "")).contains("占位串")).count());
            flows.add(flow);
        }
        // 默认铺哪一条在这里定（第五十一轮）：界面上【＋ 添加登录流】打开就铺，铺的是这条排序的第一名。
        // 判据只此一处——前端再算一套就会出现"屏上推荐 A、框里铺的是 B"。
        flows.sort(LoginFlowDraftService::compareAuto);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drafts", flows);
        out.put("auto", flows.isEmpty() ? "" : String.valueOf(flows.get(0).get("chain")));
        out.put("auto_why", flows.isEmpty() ? autoNoneReason(noLogin) : autoWhy(flows.get(0), flows.size()));
        out.put("chains_without_login", noLogin);
        if (notLoginish > 0) {
            out.put("not_login_note", "另有 " + notLoginish + " 张卡/调用点只是名字里带登录语义那个词，"
                    + "路径末段没写出它是取码还是提交账号密码（查用户资料那种）：没铺进任何一条流");
        }
        return out;
    }

    /**
     * 「这条比那条更像能跑的登录流」的比较：按<b>盘上证据的多少</b>排，不是按名字好听排。
     * 依次比：有盘上来源的入参键多 → 每步写了依据的多 → 会话注入学到没 → 留空的键少 → 步数多 → 链名（定序，
     * 免得同一份盘两次打开铺出不同的一条）。
     */
    private static int compareAuto(Map<String, Object> a, Map<String, Object> b) {
        for (String k : new String[]{"filled", "evidenced"}) {
            int c = Integer.compare(asInt(b.get(k)), asInt(a.get(k)));
            if (c != 0) return c;
        }
        int ca = a.get("apply") instanceof Map<?, ?> ? 1 : 0;
        int cb = b.get("apply") instanceof Map<?, ?> ? 1 : 0;
        if (ca != cb) return cb - ca;
        int d = Integer.compare(asInt(a.get("blank_count")), asInt(b.get("blank_count")));
        if (d != 0) return d;
        d = Integer.compare(asInt(b.get("step_count")), asInt(a.get("step_count")));
        if (d != 0) return d;
        return String.valueOf(a.get("chain")).compareTo(String.valueOf(b.get("chain")));
    }

    /** 推荐行：把"为什么铺这条"用同一些数说出来，界面照摆，不另编一句 */
    private static String autoWhy(Map<String, Object> top, int total) {
        int keys = asInt(top.get("key_count"));
        StringBuilder s = new StringBuilder("默认铺这条：整条链 " + top.get("step_count") + " 步，"
                + (keys == 0 ? "盘上没学到要填的入参键（老工程把参数收在信封里，卡上没有字段）"
                    : "入参 " + keys + " 个键里 " + top.get("filled") + " 个有盘上来源、"
                        + top.get("blank_count") + " 个照空串摆并逐条点名")
                + "，" + top.get("evidenced") + "/" + top.get("step_count") + " 步写了依据（"
                + "学到的接口卡 " + top.get("learned_cards") + " 张＋前端调用点 " + top.get("front_sites") + " 处）");
        if (top.get("apply") instanceof Map<?, ?>) s.append("，会话注入也照盘上那份铺好");
        if (total > 1) {
            s.append("；盘上还能铺出另 ").append(total - 1).append(" 条链，要换点下面另一条的【＋ 铺这条链】");
        }
        return s.append("。").toString();
    }

    /** 一条链都铺不出来时说的是"为什么铺不出"，不是"这里该有东西但没做" */
    private static String autoNoneReason(List<Map<String, Object>> noLogin) {
        if (noLogin.isEmpty()) {
            return "这个项目盘上没有学到任何一条登录语义的链：弹屏里的步骤不会替你填（系统不编示例值），"
                    + "要手工填时逐步照 [知识库 → 接口] 搜 login / captcha 那几张卡的依据来";
        }
        return "这个项目铺不出一条整链（原因逐条写在上面「铺不出一条流的链」）：弹屏里的步骤不替你填，"
                + "但每张卡的【＋ 填成一步】仍可用，学不到的格照空串摆并点名原因";
    }

    /** 打成 JSON 再说话：说明里不许出现 {header={x=y}} 这种只有 Java 认得的形状 */
    private String jsonOf(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);        // 格式化失败也不许把这条事实吞掉
        }
    }

    /** 步骤名：学到的卡用它自己的名字，前端调用点只能拿地址末段——盘上写了什么就用什么，不现编 */
    private static String stepName(String path, Map<String, Object> draft) {
        Object learned = draft.get("card_name");
        if (Boolean.TRUE.equals(draft.get("from_card")) && learned != null && !String.valueOf(learned).isBlank()) {
            return String.valueOf(learned).trim() + "（学到的卡）";
        }
        String p = path == null ? "" : path;
        int q = p.indexOf('?');
        if (q > 0) p = p.substring(0, q);
        String tail = com.preflight.parser.LoginSemantics
                .tail(DefaultValueRules.normalize(p.replaceAll("/+$", "")));
        int slash = tail.lastIndexOf('/');
        if (slash >= 0) tail = tail.substring(slash + 1);
        return tail + (Boolean.TRUE.equals(draft.get("from_card")) ? "（学到的卡）" : "（前端调用点）");
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "login" -> "提交账号密码";
            case "captcha" -> "取验证码";
            case "token" -> "取会话";
            case "logout" -> "登出";
            default -> "登录相关";
        };
    }

    /**
     * 盘上给账号/密码格写了初始值时点名：草稿里仍填凭据引用（登录流要跑的是用户自己那条账号），
     * 但被换掉的那份学到的证据必须说出来，不许悄悄丢。
     */
    private static void overridden(List<String> over, String key, String from, String def) {
        if (DispatchBodyPlanService.givesValue(from) && !def.isEmpty() && over.size() < 6) {
            over.add(key + " 盘上初始值是 \"" + def + "\"");
        }
    }

    /** 留空点名有条数上限；超出时由 blanks 自己收尾报基数，界面不再另起一处说这件事 */
    private static void truncateNote(List<Map<String, Object>> blanks, int blankCount) {
        if (blankCount > blanks.size()) {
            blanks.add(blank("其余留空的键", "一共 " + blankCount + " 个格没给到值，这里点名到第 "
                    + blanks.size() + " 个：剩下的同一条规律（值没有可推导来源，照空串发或按盘上代码/抓包补真值）"));
        }
    }

    /** 铺进流的先后：取号/取码一类的步骤在前，登录那一步在最后（登出不参与，由调用方滤掉） */
    private static int orderOf(Map<String, Object> cardDraft) {
        return "login".equals(cardDraft.get("role")) ? 2 : 1;
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** 判据在 {@link com.preflight.parser.LoginSemantics}：线索端点、卡片草稿、前端调用点三处共用同一份 */
    private static String roleOf(String path) {
        return com.preflight.parser.LoginSemantics.roleOf(path);
    }

    private static String chainOf(String path) {
        return com.preflight.parser.LoginSemantics.chainOf(path);
    }

    private static String leafOf(String dotPath) {
        return com.preflight.parser.LoginSemantics.leafOf(dotPath);
    }

    /** 为什么这一格只能空着：说清"学到的是哪一半、缺的是哪一半"，不许把人往"填个示例值"上带 */
    private static String whyBlank(String normalizedLeaf, String leaf, Map<String, Object> key) {
        String via = String.valueOf(key.getOrDefault("via", ""));
        String tail = via.isEmpty() ? "" : "（证据：" + via + "）";
        String from = String.valueOf(key.getOrDefault("defFrom", "none"));
        if (DispatchBodyPlanService.FROM_RULE_CODE_MISSING.equals(from)
                || DispatchBodyPlanService.FROM_FRAMEWORK_CODE_MISSING.equals(from)) {
            // 这一种不是"没学到值"，是"学到的规矩要本次功能号、而登录流这一步压根没有功能号"：
            // 说清是哪一条规矩，人才知道该去填什么，而不是以为系统漏学了
            return "键名 " + leaf + " 按规矩要等于「本次功能号」，而登录流这一步没有功能号可取："
                    + "照空串发，要发真值就自己填一个" + tail;
        }
        if (com.preflight.parser.LoginSemantics.isCode(normalizedLeaf)) {
            return "键名是 " + leaf + "，形似校验码/验证码：值只能从验证码图上读，系统给不出来源" + tail;
        }
        if (normalizedLeaf.contains("token") || normalizedLeaf.contains("session")) {
            return "键名是 " + leaf + "：通常是上一步响应里取出来的会话值，要写成 "
                    + "{{上一步 extract 的变量名}}；这一步返回里到底有哪个字段我们没学到" + tail;
        }
        return "键名 " + leaf + " 学到了、值没有可推导来源" + tail + "（照空串发，或按盘上代码/抓包补一个真值）";
    }

    private static Map<String, Object> blank(String what, String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", what);
        m.put("why", why);
        return m;
    }

    /** 点路径写回嵌套结构：中间段建对象，段尾 [] 建「一个元素」的数组（骨架里数组只给一个样例位） */
    @SuppressWarnings("unchecked")
    private static void putPath(Map<String, Object> root, String dotPath, Object value) {
        String[] segs = dotPath.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            String seg = segs[i];
            if (seg.endsWith("[]")) {
                String name = seg.substring(0, seg.length() - 2);
                Object got = cur.get(name);
                if (got instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
                    cur = (Map<String, Object>) l.get(0);
                } else {
                    Map<String, Object> item = new LinkedHashMap<>();
                    cur.put(name, new ArrayList<>(List.of((Object) item)));
                    cur = item;
                }
            } else {
                Object got = cur.get(seg);
                if (got instanceof Map<?, ?> m) {
                    cur = (Map<String, Object>) m;
                } else {
                    Map<String, Object> child = new LinkedHashMap<>();
                    cur.put(seg, child);
                    cur = child;
                }
            }
        }
        String last = segs[segs.length - 1];
        if (last.endsWith("[]")) {
            // 标量数组：骨架给一个元素位就够（键名已经在路径里，不该再造一个 "_item" 这种假字段）
            cur.put(last.substring(0, last.length() - 2), new ArrayList<>(List.of(value)));
        } else {
            cur.put(last, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Object v = mapper.readValue(json, Object.class);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }
}
