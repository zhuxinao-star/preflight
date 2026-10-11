package com.preflight.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 统一访问转发中心的功能号声明（unifiedvisit / unifiedvisit-instruct 这类 Spring 仓）。
 *
 * <p>事实依据（均在真实仓库核对过）：
 * <ul>
 *   <li>功能号与入参契约写在<b>每个模块自己的</b> {@code src/main/resources/interface-customer.properties}：
 *       {@code service_id.<功能号>=参数[,参数:默认值]…[||规则]}。文件头那八行注释就是这套规则的说明书
 *       （裸参数名＝必填、{@code 名:值}＝默认值、{@code ||} 分隔多条规则，{@code >>} {@code <<}
 *       {@code &&} {@code >} {@code =} {@code !=} 是条件校验）。运行时由
 *       {@code UVControlUtil.checkParameIsNull(serviceId, req_comm_data)} 按这份声明补默认值并拦空。
 *   <li>这份 {@code src/main/resources} 在哪一层，取决于仓库登记的是模块还是整份检出（第四十五轮）：
 *       招商按模块登记（{@code cms-zhaoshang/unifiedvisit}），国泰海通按整份检出登记
 *       （{@code guotaihaitong/}，模块在它下面一层的 {@code unifiedvisit-platform/}）。所以一律先按
 *       {@link RepoModules} 认出模块再找文件——按单模块硬路径找的话，国泰那 222 条声明一条都读不到，
 *       界面就成了「这个项目参数都没学到」。
 *   <li>每个功能号自己就有真实请求路径：类上 {@code @RequestMapping("/inside/question/bl")}
 *       拼方法上 {@code @PostMapping("/khdc100001")}。所以这里<b>不</b>产出 {@link #entries} 的入口信封
 *       ——这不是"一个 URL 承载全部服务"，而是"一个码一条路径"，套信封模板等于给每个码编同一个路径。
 *   <li>请求确实要带 {@code func}（＝功能号）和 {@code func_url}（＝上面那条路径）：work_app 的
 *       {@code ZSForwardCenterService#zsForwardCenter} 拿 {@code params.get("func_url")} 拼到
 *       unifiedvisit 的 base 上转发（国泰那份检出里也有读 {@code func_url} 的
 *       {@code controll/trans/UnifiedVisitTransCenter}）。这两项是框架契约，故由
 *       {@link #requestFields} 给，而不是退回"按 kind 猜一个 service 字段"。
 *   <li>但<b>请求体的形状各家不一样</b>：招商是 {@code {req_msg_hdr, req_comm_data}} 两个字段、功能号填在
 *       {@code req_msg_hdr.server_id}（切面拿它查这份 properties）；国泰是
 *       {@code RequestModel extends JSONObject} + 一个 {@code head} 字段，业务键平铺在请求体根上，功能号由
 *       切面从 URL 最后一段切出来（{@code serviceId = requestUrl.substring(...)} 那一行），它的报文头类里
 *       根本没有 {@code server_id} 这个字段。所以 {@link #callSpec} 改成读这个仓自己的 {@code @RequestBody}
 *       声明来推形状，写死一份等于拿 A 仓的信封发 B 仓的请求。
 * </ul>
 *
 * <p>学不到的也不假装有：这份声明里没有参数中文名（KJDP 的 bexConf 才有 {@code para_name}），
 * 所以字典条目上 {@code label} 一律留空；{@code type} 只能按默认值的形状给
 * （整数默认值算 number，其余 string），证据文本里照实写了这个依据。
 * 读取期按实现推出入参时另说——那条链会落到 mapper 语句上，字段的中文说明取自本地已反哺的库注释
 * （{@code CodeChainService} 的 {@code Meta}），本地没学过那张表就说"没有列注释"，不编。
 */
@Component
public class InterfaceCustomerDispatchAdapter implements DispatchAdapter {

    private static final String FRAMEWORK = "统一访问转发中心";
    /** 相对<b>模块根</b>的位置（模块由 {@link RepoModules} 认，见类注释第二条） */
    private static final Path PROPS = Path.of("src", "main", "resources", "interface-customer.properties");

    /**
     * {@code service_id.khdc100001=pageoffset:1,pagecount:10}
     *
     * <p>这条语法是全仓唯一的出口：学习期建服务字典（{@link #declaredTargets}）和读取期按实现推出入参
     * （{@code CodeChainService} 的「一码一路径」那条链）都调 {@link #parseDecl}，两边必须给同一份参数清单。
     */
    private static final Pattern ENTRY = Pattern.compile("^service_id\\.([^\\s=]+)=(.*)$");

    /** 一个功能号在这一族框架声明里的原文：参数清单写法与它所在的文件、行号 */
    public record ServiceDecl(String code, String spec, String file, int line) {}

    /**
     * 盘上的 {@code service_id.…} 声明清单（读不到文件就给空表，不抛）。
     *
     * <p>学习期建服务字典与读取期按实现推出入参都必须从这里拿，不要再各自开一遍文件：
     * 两处解析同一份语法、两份口径，早晚会给出对不上的参数清单。
     */
    public static List<ServiceDecl> serviceDecls(Path repoRoot) {
        List<ServiceDecl> out = new ArrayList<>();
        for (Path module : RepoModules.of(repoRoot)) {
            Path f = module.resolve(PROPS);
            if (!Files.isRegularFile(f)) continue;
            List<String> lines = propLines(f);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = ENTRY.matcher(lines.get(i).trim());
                if (!m.matches()) continue;
                // 证据里的路径相对仓库根：整份检出登记时它带模块名，按模块登记时不带——与改造前逐字一致
                out.add(new ServiceDecl(m.group(1).trim(), m.group(2).trim(),
                        RepoModules.rel(repoRoot, f), i + 1));
            }
        }
        return out;
    }    /** 一段"参数清单"＝标识符（可带 :默认值）用逗号分开；含运算符的段一律不当清单 */
    private static final Pattern PARAM_SEG = Pattern.compile(
            "[A-Za-z_]\\w*(?::[\\w.]+)?(?:,[A-Za-z_]\\w*(?::[\\w.]+)?)*");
    private static final Pattern PARAM = Pattern.compile("^([A-Za-z_]\\w*)(?::([\\w.]+))?$");
    /** 条件规则里把参数名削出来：先按长运算符切，剩下的单个字符运算符由字符类兜 */
    private static final Pattern RULE_SPLIT = Pattern.compile(">>|<<|->|!=|>=|<=|[=&<>|]");
    private static final Pattern MAPPING = Pattern.compile(
            "@(?:RequestMapping|PostMapping|GetMapping|PutMapping|DeleteMapping|PatchMapping)"
                    + "\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"]+)\"");
    /** {@code @RequestBody RequestModel requestModel}／带 final、带泛型的写法都认，取类型简名 */
    private static final Pattern BODY_PARAM = Pattern.compile(
            "@RequestBody\\s+(?:final\\s+)?([A-Za-z_]\\w*(?:<[^<>]*>)?(?:\\[\\])?)\\s+[A-Za-z_]\\w*");
    private static final int MAX_EVIDENCE = 5;
    private static final int MAX_JAVA_FILES = 20000;

    @Override
    public String displayName() {
        return FRAMEWORK;
    }

    @Override
    public boolean detect(Path repoRoot) {
        for (Path module : RepoModules.of(repoRoot)) {
            for (String l : propLines(module.resolve(PROPS))) {
                if (ENTRY.matcher(l.trim()).matches()) return true;
            }
        }
        return false;
    }

    /** 这个框架没有"一个 URL 承载全部服务"的入口口，所以不给信封模板 */
    @Override
    public Map<String, DispatchSpec> entries(Path repoRoot) {
        return Map.of();
    }

    /**
     * 前端调用点的信封证据由 KJDP 适配器统一扫：这些功能号在 JS 里是 {@code func:'khdc100001'}，
     * 而 {@code service} 那一栏填的是转发中心自己的服务名（zsForwardCenter / zszlForwardCenter）。
     * 这里再扫一遍只会被当成另一种码，所以不重复扫。
     */
    @Override
    public List<DispatchTarget> callSiteTargets(List<Path> jsRepoRoots) {
        return List.of();
    }

    @Override
    public List<Map<String, Object>> requestFields(String code, String entryPath) {
        if (code == null || code.isBlank()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(field("func", code, "功能号（转发中心按它选实现）"));
        if (entryPath != null && !entryPath.isBlank()) {
            out.add(field("func_url", entryPath, "完整请求路径（控制器 @RequestMapping + @PostMapping 学出来的）"));
        }
        return out;
    }

    @Override
    public boolean entryPathIsCallPath() {
        return true;
    }

    /**
     * 这条路径的请求形状（第四十一轮 N57 建，第四十五轮改为<b>按这个仓自己的声明推</b>）。
     *
     * <p>三件事都从盘上读，不再写死招商那一份（写死的后果：国泰海通的请求体被按
     * {@code req_msg_hdr} + {@code req_comm_data} 拼出去，而它自己的切面只认 {@code head}、业务键在根上，
     * 于是"看着发得出去、服务端一律当没传"）：
     * <ul>
     *   <li>请求体根上那个类＝控制器方法上 {@code @RequestBody} 那个形参的类型；</li>
     *   <li>报文头那一段＝这个类字段里<b>唯一一个</b>"非值类型、非集合、且本仓有源码"的 POJO 字段
     *       （招商 {@code req_msg_hdr : RequestHead}、国泰 {@code head : RequestHead}）；</li>
     *   <li>业务负载那一段＝唯一一个 Map/JSONObject 字段（招商 {@code req_comm_data}）；没有这种字段、
     *       但这个类 {@code extends} 自由结构＝业务键平铺在请求体根上（国泰，{@code dataKey} 给空串）；</li>
     *   <li>功能号要不要塞进报文头：看这个仓自己把什么交给
     *       {@code UVControlUtil.checkParameIsNull(serviceId, …)}——招商传
     *       {@code requestModel.getReq_msg_hdr().getServer_id()}（所以那个键必须等于功能号），国泰传
     *       {@code requestUrl.substring(...)}（功能号由 URL 最后一段决定，报文头里不该编出这一格）。
     *       读不出来就不给：少一个键顶多被服务端拦下并明说，多一个键是替这个仓编契约。</li>
     * </ul>
     * 认不出来的仓（没有 {@code @RequestBody} 声明、报文头候选并列多个）返回 {@code null}：这张卡不打分发
     * 标记，测试台照旧按普通接口卡发。{@code serviceKey} / {@code bexKey} / {@code autoService} /
     * {@code fixedQuery} 一律留空：这个框架没有"公共入口 + 固定 query"那套东西，填了就是给它编字段。
     */
    @Override
    public DispatchSpec callSpec(Path repoRoot, String code, String entryPath) {
        Scan s = scan(repoRoot);
        String dto = dtoOf(s, code, entryPath);
        if (dto.isEmpty()) return null;
        Shape sh = shapeOf(s, dto);
        if (sh == null) return null;
        Map<String, Object> hdr = new LinkedHashMap<>();
        if (!sh.codeKey().isEmpty()) hdr.put(sh.codeKey(), DefaultValueRules.CODE_TEMPLATE);
        // 其余公共参数（op_code / op_role / …）不抄清单：建卡时按这个仓自己的报文头类学出来，
        // 见 DispatchAdapter#callSpec 的说明与本类的 hdrFields 口径
        return new DispatchSpec(entryPath, "", "", sh.hdrKey(), sh.dataKey(),
                "", "", "", "", "application/json", hdr, List.of());
    }

    /**
     * 请求体形状：报文头键、业务负载键（空串＝业务键平铺在根上）、必须等于功能号的那个报文头键（没有则空串）。
     *
     * <p>这是<b>模块级</b>事实（同一份检出的这批码共用一个 {@code @RequestBody} 类），所以按类型名缓存在
     * {@link Scan} 里——建卡时 174 个码各扫一遍盘会把学习拖死。
     */
    private record Shape(String hdrKey, String dataKey, String codeKey) {}

    /**
     * 这条请求路径自己的 {@code @RequestBody} 类型名。
     *
     * <p>先按路径认（一个功能号在同仓的两个控制器里各有一条路径时，两张卡要按各自控制器的声明推形状），
     * 路径没学到就退到同码的其它调用点，再退到本仓控制器上出现过的第一个（同仓同形状是常态）。
     */
    private String dtoOf(Scan s, String code, String entryPath) {
        List<CallSite> hits = code == null ? null : s.paths.get(code);
        if (hits != null) {
            if (entryPath != null && !entryPath.isBlank()) {
                for (CallSite c : hits) if (!c.bodyType().isEmpty() && entryPath.equals(c.path())) return c.bodyType();
            }
            for (CallSite c : hits) if (!c.bodyType().isEmpty()) return c.bodyType();
        }
        for (List<CallSite> l : s.paths.values()) {
            for (CallSite c : l) if (!c.bodyType().isEmpty()) return c.bodyType();
        }
        return "";
    }

    private Shape shapeOf(Scan s, String dto) {
        synchronized (s) {
            if (s.shapes.containsKey(dto)) return s.shapes.get(dto);
            Shape sh = deriveShape(s, dto);
            s.shapes.put(dto, sh);
            return sh;
        }
    }

    private Shape deriveShape(Scan s, String dtoName) {
        ClassOrInterfaceDeclaration cd = typeOf(s.classFiles.get(dtoName), dtoName);
        if (cd == null) return null;
        List<String[]> pojos = new ArrayList<>();       // 报文头候选：{字段名, 类型名}
        String data = null;
        for (FieldDeclaration fd : cd.getFields()) {
            if (fd.isStatic()) continue;
            for (VariableDeclarator v : fd.getVariables()) {
                String t = JavaSourceIndex.baseType(v.getType().asString());
                if (t.isEmpty() || t.equals(v.getNameAsString())) continue;
                if (DtoSchemaExpander.isFreeForm(t)) {
                    if (data == null) data = v.getNameAsString();
                    continue;
                }
                if (DtoSchemaExpander.isSimple(t) || DtoSchemaExpander.isCollection(t)) continue;
                if (s.classFiles.containsKey(t)) pojos.add(new String[] {v.getNameAsString(), t});
            }
        }
        if (pojos.size() != 1) return null;             // 报文头认不出（一个都没有或并列多个）：不编形状
        String hdrKey = pojos.get(0)[0];
        if (data == null) {
            String sup = cd.getExtendedTypes().isEmpty() ? ""
                    : JavaSourceIndex.baseType(cd.getExtendedTypes().get(0).asString());
            if (!DtoSchemaExpander.isFreeForm(sup)) return null;   // 业务键落在哪儿说不清：不编
            data = "";
        }
        if (data.equals(hdrKey)) return null;
        return new Shape(hdrKey, data, codeKeyOf(s, pojos.get(0)[1]));
    }

    /**
     * 功能号要填在报文头哪个键：只认这个仓自己交给 {@code checkParameIsNull} 的第一个实参。
     * 实参是报文头类上某个字段读出来的（{@code getXxx()} 与该字段名去下划线后对得上）就给那个字段的本名，
     * 是从 URL 切出来的、或读不出来的就给空串。
     */
    private String codeKeyOf(Scan s, String hdrType) {
        ClassOrInterfaceDeclaration hdr = typeOf(s.classFiles.get(hdrType), hdrType);
        if (hdr == null) return "";
        Map<String, String> byNorm = new LinkedHashMap<>();
        for (FieldDeclaration fd : hdr.getFields()) {
            if (fd.isStatic()) continue;
            for (VariableDeclarator v : fd.getVariables()) {
                byNorm.putIfAbsent(normalize(v.getNameAsString()), v.getNameAsString());
            }
        }
        for (Path f : s.utilFiles) {
            CompilationUnit cu = parse(f);
            if (cu == null) continue;
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!"checkParameIsNull".equals(call.getNameAsString()) || call.getArguments().isEmpty()) continue;
                for (String getter : getterChain(call.getArgument(0), cu)) {
                    String hit = byNorm.get(normalize(getter));
                    if (hit != null) return hit;
                }
            }
        }
        return "";
    }

    /**
     * 这个实参读的是哪几个属性（{@code get Xxx} 的名字，按出现的先后）。
     *
     * <p>实参常常是个局部变量（两家仓写的都是 {@code checkParameIsNull(serviceId, jsonObj)}），
     * 所以先在同一文件里把 {@code serviceId = …} 那条赋值读出来；读不到就按实参本身看。
     * 只在这一层做替换：不做跨方法数据流，读不出来就返回空表，由调用方落回"不编这一格"。
     */
    private List<String> getterChain(Expression arg, CompilationUnit cu) {
        List<Expression> exprs = new ArrayList<>();
        if (arg instanceof NameExpr var) {
            String name = var.getNameAsString();
            // 声明时的初值与后面重新赋的值都算候选，但 {@code String serviceId = null;} 那种空初值不算——
            // 两家仓写的都是"先给 null、进方法再按各自的方式赋值"，认了空初值就等于永远读不出形状
            for (VariableDeclarator d : cu.findAll(VariableDeclarator.class)) {
                if (!d.getNameAsString().equals(name) || d.getInitializer().isEmpty()) continue;
                Expression init = d.getInitializer().get();
                if (init.isNullLiteralExpr()) continue;
                exprs.add(init);
            }
            for (AssignExpr a : cu.findAll(AssignExpr.class)) {
                if (a.getTarget().isNameExpr() && a.getTarget().asNameExpr().getNameAsString().equals(name)) {
                    exprs.add(a.getValue());
                }
            }
        } else {
            exprs.add(arg);
        }
        for (Expression e : exprs) {
            List<String> getters = gettersOf(e);
            if (!getters.isEmpty()) return getters;
        }
        return List.of();
    }

    private List<String> gettersOf(Expression e) {
        List<String> names = new ArrayList<>();
        if (e instanceof MethodCallExpr) {
            for (MethodCallExpr c : e.findAll(MethodCallExpr.class)) names.add(c.getNameAsString());
        }
        List<String> getters = new ArrayList<>();
        for (String n : names) {
            if (n.startsWith("get") && n.length() > 3) getters.add(n.substring(3));
        }
        // 最里层的属性名先说：getReq_msg_hdr().getServer_id() 里功能号是 server_id，不是 Req_msg_hdr
        Collections.reverse(getters);
        return getters;
    }

    /** JSON 的键就是 Java 字段名，但 getter 的大小写与下划线写法各家不同，只对这一点做归一 */
    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("_", "");
    }

    private ClassOrInterfaceDeclaration typeOf(Path file, String simpleName) {
        if (file == null || simpleName == null) return null;
        CompilationUnit cu = parse(file);
        if (cu == null) return null;
        for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cd.getNameAsString().equals(simpleName)) return cd;
        }
        return null;
    }

    private CompilationUnit parse(Path file) {
        try {
            return StaticJavaParser.parse(new String(Files.readAllBytes(file), charsetOf(file)));
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> field(String key, String value, String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", key);
        m.put("type", "string");
        m.put("title", title);
        m.put("value", value);
        m.put("literal", true);
        m.put("at", FRAMEWORK + " 框架契约");
        return m;
    }

    @Override
    public List<DispatchTarget> declaredTargets(Path repoRoot) {
        List<ServiceDecl> decls = serviceDecls(repoRoot);
        List<DispatchTarget> out = new ArrayList<>();
        if (decls.isEmpty()) return out;
        Map<String, List<CallSite>> paths = callPaths(repoRoot);
        for (ServiceDecl one : decls) {
            // 证据里的文件名沿用学习期原来那份「仓库名/…/interface-customer.properties」写法，
            // 换了它老服务的证据行会整片漂，界面比对会误报成「参数变了」。
            // 一份检出里可能有多个模块各带一份这个文件，所以按这条声明自己所在的那个文件算。
            ServiceDecl d = new ServiceDecl(one.code(), one.spec(),
                    relOf(repoRoot.resolve(one.file())), one.line());
            Decl parsed = parseDecl(d);
            List<DispatchParam> params = parsed.params();
            List<String> ev = new ArrayList<>(parsed.evidence());
            List<CallSite> hits = paths.get(d.code());
            String entry = "";
            if (hits != null && !hits.isEmpty()) {
                entry = hits.get(0).path();
                if (hits.size() > 1) {
                    addEvidence(ev, d.code() + " 在 " + hits.size() + " 个控制器里都映射了，取第一条："
                            + hits.get(0).file());
                }
                addEvidence(ev, "请求路径出自 " + hits.get(0).file() + " 的类级 @RequestMapping 拼方法级映射");
            } else {
                ev.add(d.file() + ":" + d.line() + " 本仓没找到映射到 \"" + d.code() + "\" 的方法，请求路径没学到");
            }
            out.add(new DispatchTarget("service", d.code(), null, entry, params, ev));
        }
        return out;
    }

    /** 一份 {@code service_id} 声明读出来的东西：参数清单 + 给界面看的证据行（含条件规则） */
    public record Decl(List<DispatchParam> params, List<String> evidence) {}

    /**
     * 声明原文 → 参数清单。这是这套写法（裸名＝必填、{@code 名:值}＝默认值、{@code ||} 分隔规则）
     * 在全仓唯一的解析出口：学习期建字典与读取期「按实现推」的链都用它，两边给不出同一份参数就是缺陷。
     *
     * <p>{@code source} 一律 {@code metaData}（这一族框架的声明文件就是它的元数据），
     * {@code label} 留空——这份声明里没有中文名，编一个就是假数据。
     */
    public static Decl parseDecl(ServiceDecl d) {
        List<DispatchParam> params = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        ev.add(d.file() + ":" + d.line() + " service_id." + d.code());
        for (String seg : d.spec().split("\\|\\|")) {
            String s = seg.trim();
            if (s.isEmpty()) continue;
            if (PARAM_SEG.matcher(s).matches()) {
                for (String part : s.split(",")) {
                    Matcher pm = PARAM.matcher(part.trim());
                    if (!pm.matches()) continue;
                    addParam(params, new DispatchParam(pm.group(1), typeOf(pm.group(2)), pm.group(2), null,
                            "metaData", d.file() + ":" + d.line() + " 参数清单里的"
                                    + (pm.group(2) == null ? "必填项（没给默认值）" : "项")));
                }
            } else {
                addEvidence(ev, d.file() + ":" + d.line() + " 条件规则 " + cut(s));
                for (String tok : RULE_SPLIT.split(s)) {
                    Matcher pm = PARAM.matcher(tok.trim());
                    // 只认"光秃秃的名字"：带默认值的那个是触发条件（如 sfty:0），已经在清单里
                    if (!pm.matches() || pm.group(2) != null) continue;
                    addParam(params, new DispatchParam(pm.group(1), "string", null, null, "metaData",
                            d.file() + ":" + d.line() + " 条件规则 " + cut(s) + " 里的参数"));
                }
            }
        }
        return new Decl(params, ev);
    }

    /** 这份声明没有类型信息（KJDP 的 data_type 才有），只能按默认值的形状给 */
    private static String typeOf(String def) {
        return def != null && def.matches("-?\\d+(\\.\\d+)?") ? "number" : "string";
    }

    // ---------- 控制器路径索引 ----------

    /**
     * @param bodyType 这个方法 {@code @RequestBody} 那个形参的类型简名（没有则空串）——
     *                 请求体形状要从它推，见 {@link #callSpec}
     * @param absFile  盘上位置，认模块用（证据里给的是 {@code file} 那个短名）
     */
    private record CallSite(String path, String file, Path absFile, String bodyType) {}

    /**
     * 一个仓库根的盘上事实。一次扫描，服务字典（{@link #declaredTargets}）与请求形状（{@link #callSpec}）共用：
     * 建卡时这两个入口都要读同一批控制器文件，各读一遍就是十来秒的白等。
     *
     * <p>缓存只活 {@value #SCAN_TTL_MS} 毫秒，与 {@code CallPathIndex} 同口径：SVN 更新后不必重启也能学到新码，
     * 学习一趟之内不会重复走盘。
     */
    private static final class Scan {
        final long at = System.currentTimeMillis();
        final Map<String, List<CallSite>> paths = new LinkedHashMap<>();
        /** 类简名 → 源文件（{@code @RequestBody RequestModel} 里的 RequestModel 就靠它落到盘上） */
        final Map<String, Path> classFiles = new LinkedHashMap<>();
        /** 读到 {@code checkParameIsNull} 的那几个文件：判功能号填不填进报文头要用 */
        final List<Path> utilFiles = new ArrayList<>();
        final Map<String, Shape> shapes = new LinkedHashMap<>();
    }

    private static final long SCAN_TTL_MS = 5 * 60_000L;
    private static final int MAX_UTIL_FILES = 8;
    private final Map<String, Scan> scans = new LinkedHashMap<String, Scan>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Scan> e) {
            return size() > 4;
        }
    };

    private Scan scan(Path repoRoot) {
        String key = repoRoot == null ? "" : repoRoot.toAbsolutePath().toString();
        synchronized (scans) {
            Scan s = scans.get(key);
            if (s != null && System.currentTimeMillis() - s.at < SCAN_TTL_MS) return s;
            s = walk(repoRoot);
            scans.put(key, s);
            return s;
        }
    }

    /**
     * 逐模块扫 {@code src/main/java}（天然不会读到 target/classes 的编译副本）：类上的
     * {@code @RequestMapping} 前缀拼方法上的映射字面量。类/方法的切分按"与文件名同名的那个类型声明"的位置来——
     * 在它之前的映射是前缀，之后的才是每个功能号自己的路径。
     */
    private Map<String, List<CallSite>> callPaths(Path repoRoot) {
        return scan(repoRoot).paths;
    }

    private Scan walk(Path repoRoot) {
        Scan s = new Scan();
        int budget = MAX_JAVA_FILES;
        for (Path module : RepoModules.of(repoRoot)) {
            Path src = RepoModules.javaSrc(module);
            if (src == null || budget <= 0) continue;
            List<Path> files = new ArrayList<>();
            try (Stream<Path> w = Files.walk(src)) {
                w.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .limit(budget)
                        .forEach(files::add);
            } catch (Exception ignore) {
                continue;
            }
            budget -= files.size();
            for (Path f : files) {
                String cls = f.getFileName().toString().replace(".java", "");
                s.classFiles.putIfAbsent(cls, f);
                try {
                    indexFile(f, cls, s);
                } catch (Exception ignore) {
                }
            }
        }
        return s;
    }

    private void indexFile(Path file, String cls, Scan s) throws Exception {
        String text = readTolerant(file);
        boolean util = text.contains("checkParameIsNull");
        if (util && s.utilFiles.size() < MAX_UTIL_FILES) s.utilFiles.add(file);
        Matcher self = Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(cls) + "\\b")
                .matcher(text);
        int classAt = self.find() ? self.start() : -1;
        if (classAt < 0) return;
        String base = "";
        List<String> methods = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        Matcher m = MAPPING.matcher(text);
        while (m.find()) {
            if (m.start() < classAt) {
                if (base.isEmpty()) base = m.group(1);
            } else {
                methods.add(m.group(1));
                bodies.add(bodyType(text, m.end()));
            }
        }
        if (methods.isEmpty()) return;
        String rel = cls + ".java";
        for (int i = 0; i < methods.size(); i++) {
            String lit = methods.get(i);
            String key = lit.startsWith("/") ? lit.substring(1) : lit;
            if (key.isBlank() || key.contains("/") || key.contains("{")) continue;
            s.paths.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new CallSite(join(base, lit), rel, file, bodies.get(i)));
        }
    }

    /**
     * 映射注解后面那一段方法签名里的 {@code @RequestBody} 形参类型＝这个功能号真正的请求体类。
     * 取到方法体左括号为止，避免把方法体里另一个 {@code @RequestBody} 字样（写在注释里的那种）当成签名。
     */
    private static String bodyType(String text, int afterAnnotation) {
        int stop = Math.min(text.length(), afterAnnotation + 800);
        String seg = text.substring(afterAnnotation, stop);
        int brace = seg.indexOf('{');
        if (brace > 0) seg = seg.substring(0, brace);
        Matcher b = BODY_PARAM.matcher(seg);
        if (!b.find()) return "";
        String t = JavaSourceIndex.baseType(b.group(1));
        return t;
    }

    /**
     * 类级 {@code /inside/question/bl} 拼方法级 {@code /khdc100001} → {@code /inside/question/bl/khdc100001}。
     * 两段各自可能带或不带斜杠（Spring 都认），拼出来必须是单斜杠、无尾斜杠，否则测试台真发出去会 404。
     */
    private static String join(String clsPath, String methodPath) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {clsPath, methodPath}) {
            String s = part == null ? "" : part.trim();
            while (s.startsWith("/")) s = s.substring(1);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (s.isEmpty()) continue;
            sb.append('/').append(s.replace("//", "/"));
        }
        return sb.toString();
    }

    // ---------- 读盘小工具 ----------

    /** 读一份 properties；文件不在（那个模块没有这套声明）或读坏了都给空表，不抛 */
    private static List<String> propLines(Path propsFile) {
        if (propsFile == null || !Files.isRegularFile(propsFile)) return List.of();
        try {
            return Files.readAllLines(propsFile, charsetOf(propsFile));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 这批工程的注释是中文而编码不统一：先按 UTF-8 严格解，失败退回 GBK */
    private static Charset charsetOf(Path f) throws Exception {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(f)));
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            return Charset.forName("GBK");
        }
    }

    private static String readTolerant(Path f) throws Exception {
        byte[] raw = Files.readAllBytes(f);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (Exception e) {
            return new String(raw, Charset.forName("GBK"));
        }
    }

    private static void addParam(List<DispatchParam> ps, DispatchParam cand) {
        for (DispatchParam p : ps) {
            if (p.key().equalsIgnoreCase(cand.key())) return;   // 先出现的说话：清单段在条件规则段之前
        }
        ps.add(cand);
    }

    private static void addEvidence(List<String> ev, String s) {
        if (ev.size() < MAX_EVIDENCE && !ev.contains(s)) ev.add(s);
    }

    private static String cut(String s) {
        return s.length() > 48 ? s.substring(0, 48) + "…" : s;
    }

    /** 证据给到"仓库名/路径"就够定位了，整段绝对路径会把列表撑开 */
    private String relOf(Path p) {
        Path name = p.getFileName();
        Path parent = p.getParent();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 5 && parent != null; i++) {
            parts.add(0, parent.getFileName().toString());
            parent = parent.getParent();
        }
        parts.add(name.toString());
        return String.join("/", parts);
    }
}
