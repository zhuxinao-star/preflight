package com.preflight.learn;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.preflight.parser.DispatchAdapter;
import com.preflight.parser.DtoSchemaExpander;
import com.preflight.parser.JavaSourceIndex;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「一个功能号一条真实路径」那类 Spring 仓的<b>只发现</b>索引（第四十二轮）。
 *
 * <p>为什么单独一本：这类仓的接口契约不在 XML 里，在 Java 源码里——功能号是控制器方法上的
 * {@code @PostMapping("/khdc100016")}，业务参数是处理器里 {@code jsonObj.getString("wjsyqds")}
 * 这样读出来的，出参是这条链最后落到的那条 MyBatis 语句选出的列。要按实现推，先得能把
 * 「码 → 哪个类的哪个方法」「这个方法调了谁的哪个方法」这两件事一次问出来。
 *
 * <p>这一本<b>只记盘上事实，不做任何推断</b>：谁是谁的下一跳、语句算高置信还是按返回类型算中置信，
 * 全部留在 {@code CodeChainService} 里——它才有「同一个键从两条路都走到过就说一遍」的记账口径。
 * 也因此这里刻意不带项目、不带库：给一个仓库工作副本就能建，建一次缓存五分钟。
 *
 * <p>只扫各模块的 {@code src/main/java}（模块由 {@link com.preflight.parser.RepoModules} 认，
 * 整份检出登记时模块在仓库根下面一层）：{@code target/} 里那份是编译副本，读它就是拿旧代码讲新接口；
 * 老工程（KJDP 那套）没有这个目录，对它而言这本索引天然是空的，不会污染另一条链。
 */
@Component
public class CallPathIndex {

    /** 一个功能号自己的入口：类级前缀拼方法级映射得到的真实路径，方法体是这条链的第一跳 */
    public record Endpoint(String code, String entryPath, String verb, String cls, String pkg,
                           String method, String file, int line) {}

    /**
     * 一个方法声明的盘上事实。
     *
     * @param jsonKeys 方法体里从对象上读出来的键名（{@code jsonObj.getString("wjsyqds")} 读出 wjsyqds）
     * @param calls    方法体里的调用（接收者写的是变量名／字段名，类型由 {@link Cls#fields()} 换出来）
     */
    public record Method(String cls, String name, String returnType, String javadoc, String file, int line,
                         int args, List<String> jsonKeys, List<Call> calls) {}

    /** 一次调用的形状：接收者（字段名或局部变量名）+ 方法名 + 实参个数 */
    public record Call(String recv, String name, int args) {}

    /** 一个类的声明：字段名→类型简名（{@code @Autowired} 就靠这个把接收者换成类），import 用于同名消歧 */
    public record Cls(String simpleName, String pkg, String file, boolean isInterface, List<String> imports,
                      Map<String, String> fields, Map<String, List<Method>> methods, String javadoc) {}

    public static class Repo {
        public Path root;
        public String name;
        /** 功能号（小写）→ 入口方法；同一码在两个控制器里都映射时全留着，由上层说清用了哪一条 */
        public final Map<String, List<Endpoint>> byCode = new LinkedHashMap<>();
        /** 类简名 → 候选声明（同名多类按包名/import 消歧在上层） */
        public final Map<String, List<Cls>> byName = new LinkedHashMap<>();
        /** 扫到的 .java 清单：上层要按类展开字段（含父类继承来的）时用它现建一份类索引 */
        public final List<Path> files = new ArrayList<>();
        /** 盘上扫到的 {@code service_id.<码>=参数清单} 声明：入参契约的那一份 */
        public final Map<String, com.preflight.parser.InterfaceCustomerDispatchAdapter.ServiceDecl> serviceDecls =
                new LinkedHashMap<>();
        /** 建索引时看到的口径事实：扫了几个文件、几个解析不动、目录到底有没有 */
        public final List<String> notes = new ArrayList<>();
        public long builtAt;
        private volatile com.preflight.parser.JavaSourceIndex java;
    }

    /** 一个功能号在框架声明里的契约原文：类型与语法都取自 {@code InterfaceCustomerDispatchAdapter}，两处共用一份 */
    public com.preflight.parser.InterfaceCustomerDispatchAdapter.ServiceDecl serviceDecl(Repo r, String code) {
        if (r == null || code == null) return null;
        return r.serviceDecls.get(code.toLowerCase(Locale.ROOT));
    }

    /**
     * 这个仓的类索引（懒建）：{@code DtoSchemaExpander} 靠它把返回类型的字段连父类一起展开。
     *
     * <p>为什么不把字段也记在 {@link Cls} 里：那份展开逻辑（泛型回填、集合元素、继承链、同名消歧）
     * 全仓只应该有一处，抄一份到这本索引里就迟早会和接口卡学习结果对不上。
     */
    public com.preflight.parser.JavaSourceIndex java(Repo r) {
        if (r == null) return null;
        com.preflight.parser.JavaSourceIndex hit = r.java;
        if (hit == null) {
            synchronized (r) {
                hit = r.java;
                if (hit == null) {
                    hit = new com.preflight.parser.JavaSourceIndex(r.files);
                    r.java = hit;
                }
            }
        }
        return hit;
    }

    private static final long TTL_MS = 5 * 60_000L;
    private static final int MAX_FILES = 8000;
    private static final int MAX_KEYS_PER_METHOD = 80;
    private static final int MAX_CALLS_PER_METHOD = 150;
    private static final int MAX_METHODS_PER_CLASS = 400;

    /** 读值就能定键的那些方法名——fastjson／JDBC／Map 三种写法都在这里 */
    private static final List<String> KEY_READS = List.of("getString", "getInteger", "getLong", "getDouble",
            "getBoolean", "getObject", "getJSONObject", "getJSONArray", "get", "remove", "containsKey");
    /** 映射注解：值可能写在裸字面量里，也可能写在 value= / path= 后面 */
    private static final Pattern MAPPING_ANNOTATIONS = Pattern.compile(
            "RequestMapping|PostMapping|GetMapping|PutMapping|DeleteMapping|PatchMapping");
    private static final Pattern REQUEST_METHOD_VALUE = Pattern.compile("RequestMethod\\s*\\.\\s*(\\w+)");

    private final Map<String, Repo> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Repo>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Repo> e) {
                    return size() > 6;
                }
            });

    /**
     * 这个仓的 Spring 布局能不能学（往下有没有一个目录带 {@code src/main/java}）——决定「按实现推」要不要试这一步。
     *
     * <p>只看 {@code root/src/main/java} 的话，整份检出登记的仓（国泰海通：模块在 {@code unifiedvisit-platform/}
     * 那一层）会被判成「不是 Spring 仓」，那条链一步都不走，界面就成了「入参出参都没学到」。
     *
     * <p>认模块要走盘（最多几千个目录），而这条判断在推链里每个仓库每个码都问一次，所以按 5 分钟缓存——
     * 与 {@link #forRepo} 同一口径：SVN 更新后不必重启也能学到，一趟推导之内不会重复走盘。
     */
    public boolean looksLikeSpringRepo(Path root) {
        if (root == null) return false;
        String key = root.toAbsolutePath().toString();
        synchronized (springish) {
            Flag hit = springish.get(key);
            if (hit != null && System.currentTimeMillis() - hit.at < TTL_MS) return hit.spring;
        }
        boolean spring = false;
        for (Path m : com.preflight.parser.RepoModules.of(root)) {
            if (com.preflight.parser.RepoModules.javaSrc(m) != null) {
                spring = true;
                break;
            }
        }
        synchronized (springish) {
            if (springish.size() > 64) springish.clear();
            springish.put(key, new Flag(spring, System.currentTimeMillis()));
        }
        return spring;
    }

    private record Flag(boolean spring, long at) {}

    private final Map<String, Flag> springish = new LinkedHashMap<>();

    public Repo forRepo(Path root, String repoName) {
        if (root == null || !Files.isDirectory(root)) return null;
        String key = root.toString();
        Repo hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.builtAt < TTL_MS) return hit;
        Repo r = build(root, repoName);
        cache.put(key, r);
        return r;
    }

    public List<Endpoint> endpoints(Repo r, String code) {
        if (r == null || code == null) return List.of();
        return r.byCode.getOrDefault(code.toLowerCase(Locale.ROOT), List.of());
    }

    /** 类简名 → 候选类声明（同名的多个都给出） */
    public List<Cls> classes(Repo r, String simpleName) {
        if (r == null || simpleName == null) return List.of();
        return r.byName.getOrDefault(simpleName, List.of());
    }

    /** 按「接收者字段类型」换出来的类 + 方法名找声明；重名方法全给，上层按实参个数挑 */
    public List<Method> methods(Cls cls, String name) {
        if (cls == null || name == null) return List.of();
        return cls.methods().getOrDefault(name, List.of());
    }

    /**
     * 报文头那一段的字段清单：从<b>自己声明了 {@code hdrKey} 这个字段的那个类</b>往下读一层。
     *
     * <p>为什么学而不写死（第四十二轮用户原话：「其它公共参数又没了，比方说 op_code 什么的，
     * 不同模块公共参数不一样啊」）：公共参数是每个框架自己的类定义的（统一访问是
     * {@code RequestModel.req_msg_hdr : RequestHead}），换一个仓就换一套键。写死任何一份清单，
     * 都是拿 A 模块的头去发 B 模块的请求。
     *
     * <p>只认盘上事实：没有哪个类声明这个键就返回空表；类里没写注释的字段 {@code label} 就是空串，
     * 由用户在 YApi 文档维护里补（那是第卅八轮为这件事建的出口）。
     *
     * @param hdrKey 请求体里报文头那一段的键名，必须与那个类里的字段名逐字一致（JSON 键就是字段名）
     */
    public List<DispatchAdapter.DispatchParam> headerParams(Repo r, String hdrKey) {
        List<DispatchAdapter.DispatchParam> out = new ArrayList<>();
        if (r == null || hdrKey == null || hdrKey.isBlank()) return out;
        JavaSourceIndex j = java(r);
        if (j == null) return out;
        DtoSchemaExpander ex = new DtoSchemaExpander(j);
        Set<String> seen = new LinkedHashSet<>();
        for (List<Cls> same : r.byName.values()) {
            for (Cls c : same) {
                String type = declaredType(c, hdrKey);
                if (type == null) continue;
                for (DtoSchemaExpander.FlatField f : ex.flatten(type, c.imports())) {
                    if (!seen.add(f.name().toLowerCase(Locale.ROOT))) continue;
                    out.add(new DispatchAdapter.DispatchParam(f.name(), JavaSourceIndex.jsonTypeOf(f.rawType()),
                            "", f.description() == null ? "" : f.description(), "hdr",
                            c.simpleName() + "." + hdrKey + " : " + type));
                }
            }
        }
        return out;
    }

    /**
     * 这个类里 {@code hdrKey} 那个字段的声明类型；没有这个字段返回 {@code null}。
     *
     * <p><b>只按源码里的字段名精确认，不忽略大小写</b>：JSON 的键就是 Java 字段名（Jackson 按字段名序列化），
     * 差一个大小写就是两套契约。探针实测过：按忽略大小写认时，KJDP 的 {@code REQ_MSG_HDR} 会认到转发中心的
     * {@code req_msg_hdr} 上，把 {@code opp_rogram} 这类键塞进金证那套大写信封里——换框架不等于借头字段。
     */
    private static String declaredType(Cls c, String hdrKey) {
        if (c == null || c.fields() == null) return null;
        return c.fields().get(hdrKey);
    }

    /** 这个类（或其 import）里说的某个简名对应哪个全限定名——判断「DAO 接口的全名＝mapper namespace」要用 */
    public static String qualified(Cls cls, String simpleName) {
        if (cls == null || simpleName == null) return null;
        if (cls.imports() != null) {
            for (String i : cls.imports()) {
                if (i.endsWith("." + simpleName)) return i;
            }
        }
        return cls.pkg() == null || cls.pkg().isEmpty() ? null : cls.pkg() + "." + simpleName;
    }

    // ---------- 建索引 ----------

    private Repo build(Path root, String repoName) {
        Repo r = new Repo();
        r.root = root;
        r.name = repoName;
        r.builtAt = System.currentTimeMillis();
        // 参数清单的语法只有一处解析出口：学习期建字典与这里必须给同一份清单，所以直接调适配器
        for (com.preflight.parser.InterfaceCustomerDispatchAdapter.ServiceDecl d
                : com.preflight.parser.InterfaceCustomerDispatchAdapter.serviceDecls(root)) {
            r.serviceDecls.putIfAbsent(d.code().toLowerCase(Locale.ROOT), d);
        }
        List<Path> mods = com.preflight.parser.RepoModules.of(root);
        if (mods.isEmpty()) {
            r.notes.add("盘上没认出 Maven 布局的模块（src/main/java 与 src/main/resources 都不在）——这本索引对它为空");
            return r;
        }
        int bad = 0;
        // 整份检出登记时（国泰海通）模块在 root 下面一层，按模块登记时（招商）root 自己就是模块：
        // 一律走 RepoModules，两处口径才不会和「参数清单从哪份 properties 读的」对不上（第四十五轮）
        List<Path> files = new ArrayList<>();
        for (Path m : mods) {
            Path src = com.preflight.parser.RepoModules.javaSrc(m);
            if (src == null) continue;
            if (files.size() >= MAX_FILES) break;
            files.addAll(javaFiles(src, MAX_FILES - files.size()));
        }
        r.files.addAll(files);
        for (Path f : files) {
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(f);
            } catch (Exception e) {
                bad++;
                continue;
            }
            if (cu == null) {
                bad++;
                continue;
            }
            try {
                indexUnit(r, root, cu, f);
            } catch (Exception e) {
                bad++;     // 一个文件里有解析不了的怪写法，不能把整本索引带崩
            }
        }
        r.notes.add("扫了 " + mods.size() + " 个模块的 src/main/java：" + r.files.size() + " 个 .java、"
                + r.byName.size() + " 个类型、"
                + r.byCode.size() + " 个功能号入口，properties 里 " + r.serviceDecls.size() + " 条参数声明"
                + (bad > 0 ? "，另有 " + bad + " 个文件解析不动" : ""));
        return r;
    }

    private List<Path> javaFiles(Path src, int cap) {
        List<Path> out = new ArrayList<>();
        try (var w = Files.walk(src)) {
            for (Path p : w.toList()) {
                if (out.size() >= cap) break;
                String s = p.toString().replace('\\', '/');
                if (s.contains("/target/") || !Files.isRegularFile(p)) continue;
                if (!s.endsWith(".java")) continue;
                out.add(p);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private void indexUnit(Repo r, Path root, CompilationUnit cu, Path file) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<String> imports = new ArrayList<>();
        for (var im : cu.getImports()) {
            if (im.isAsterisk() || im.isStatic()) continue;
            imports.add(im.getNameAsString());
        }
        String rel = rel(root, file);
        for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            Cls cls = indexClass(cd, pkg, imports, rel);
            if (cls == null) continue;
            r.byName.computeIfAbsent(cls.simpleName(), k -> new ArrayList<>()).add(cls);
            String base = classPath(cd);
            for (MethodDeclaration m : cd.getMethods()) {
                for (AnnotationExpr a : m.getAnnotations()) {
                    String lit = mappingLiteral(a);
                    if (lit == null) continue;
                    String code = lit.startsWith("/") ? lit.substring(1) : lit;
                    if (code.isBlank() || code.contains("/") || code.contains("{")) continue;
                    r.byCode.computeIfAbsent(code.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(new Endpoint(code, join(base, lit), verbOf(a), cd.getNameAsString(), pkg,
                                    m.getNameAsString(), rel, lineOf(m)));
                }
            }
        }
    }

    private Cls indexClass(ClassOrInterfaceDeclaration cd, String pkg, List<String> imports, String rel) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldDeclaration fd : cd.getFields()) {
            if (fd.isStatic()) continue;   // static 常量不是注入点
            for (VariableDeclarator v : fd.getVariables()) {
                if (fields.size() >= 200) break;
                if (v.getNameAsString().toUpperCase(Locale.ROOT).equals(v.getNameAsString())) continue;
                fields.put(v.getNameAsString(), v.getType().asString());
            }
        }
        Map<String, List<Method>> methods = new LinkedHashMap<>();
        for (MethodDeclaration m : cd.getMethods()) {
            if (methods.size() >= MAX_METHODS_PER_CLASS) break;
            List<String> keys = new ArrayList<>();
            List<Call> calls = new ArrayList<>();
            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                String lit = firstStringLiteral(call);
                if (lit != null && KEY_READS.contains(call.getNameAsString())) {
                    if (keys.size() < MAX_KEYS_PER_METHOD && !keys.contains(lit)) keys.add(lit);
                }
                if (call.getScope().filter(s -> s instanceof NameExpr).isPresent()
                        && calls.size() < MAX_CALLS_PER_METHOD) {
                    calls.add(new Call(call.getScope().get().toString(), call.getNameAsString(),
                            call.getArguments().size()));
                }
            }
            methods.computeIfAbsent(m.getNameAsString(), k -> new ArrayList<>())
                    .add(new Method(cd.getNameAsString(), m.getNameAsString(), m.getType().asString(),
                            javadoc(m), rel, lineOf(m), m.getParameters().size(), keys, calls));
        }
        // 一个文件里的匿名内部类／嵌套类也用同一个 cu 的 import，够了；类级注释留给上层当接口中文名
        return new Cls(cd.getNameAsString(), pkg, rel, cd.isInterface(), imports, fields, methods,
                cd.getJavadoc().map(j -> oneLine(j.getDescription().toText())).orElse(null));
    }

    /** 类级 {@code @RequestMapping} 前缀（一个类只取第一条，写多条 Spring 自己也不认） */
    private static String classPath(ClassOrInterfaceDeclaration cd) {
        for (AnnotationExpr a : cd.getAnnotations()) {
            String lit = mappingLiteral(a);
            if (lit != null) return lit;
        }
        return "";
    }

    /** 注解里的映射字面量：不是映射注解、或没写字面量（只写了 method= 那种）都返回 null */
    private static String mappingLiteral(AnnotationExpr a) {
        String n = a.getNameAsString();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(dot + 1);
        if (!MAPPING_ANNOTATIONS.matcher(n).matches()) return null;
        try {
            if (a.isSingleMemberAnnotationExpr()) {
                return literal(a.asSingleMemberAnnotationExpr().getMemberValue());
            }
            if (a.isNormalAnnotationExpr()) {
                for (var pair : a.asNormalAnnotationExpr().getPairs()) {
                    String p = pair.getNameAsString();
                    if (p.equals("value") || p.equals("path")) {
                        String lit = literal(pair.getValue());
                        if (lit != null) return lit;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String literal(Expression e) {
        if (e == null) return null;
        if (e.isStringLiteralExpr()) return e.asStringLiteralExpr().getValue();
        // {@code @RequestMapping({"/a"})} 这种数组写法：只有一项时才当它是路径，多项的不猜
        if (e.isArrayInitializerExpr() && e.asArrayInitializerExpr().getValues().size() == 1) {
            return literal(e.asArrayInitializerExpr().getValues().get(0));
        }
        return null;
    }

    /** 请求方法：注解名就是动词；{@code @RequestMapping} 再看 method= （没写就不编，界面上显示「未声明」） */
    private static String verbOf(AnnotationExpr a) {
        String n = a.getNameAsString();
        int dot = n.lastIndexOf('.');
        n = dot > 0 ? n.substring(dot + 1) : n;
        if (n.endsWith("Mapping")) {
            String v = n.replace("Mapping", "");
            if (!v.isEmpty()) return v.toUpperCase(Locale.ROOT);
        }
        try {
            if (a.isNormalAnnotationExpr()) {
                for (var pair : a.asNormalAnnotationExpr().getPairs()) {
                    if (!pair.getNameAsString().equals("method")) continue;
                    Matcher m = REQUEST_METHOD_VALUE.matcher(pair.getValue().toString());
                    if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
                }
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    /**
     * 类级 {@code /inside/question/wj} 拼方法级 {@code /khdc100016}。
     * 与 {@code InterfaceCustomerDispatchAdapter} 同一口径：两段各自可能带或不带斜杠，
     * 拼出来必须单斜杠、无尾斜杠，否则测试台真发出去会 404。
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

    /** 调用里的第一个字符串字面量参数＝键名；{@code getString(getXxx())} 这种嵌套写法不当键 */
    private static String firstStringLiteral(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) return null;
        Expression a = call.getArgument(0);
        return a.isStringLiteralExpr() ? a.asStringLiteralExpr().getValue() : null;
    }

    private static String javadoc(MethodDeclaration m) {
        String t = m.getJavadoc().map(j -> oneLine(j.getDescription().toText())).orElse(null);
        return t;
    }

    private static String oneLine(String s) {
        if (s == null) return null;
        String t = s.replaceAll("@\\w+.*", "").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    private static int lineOf(MethodDeclaration m) {
        return m.getRange().map(r -> r.begin.line).orElse(0);
    }

    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.getFileName() == null ? "" : p.getFileName().toString();
        }
    }

    /** 供诊断与判据用：这个仓一共学到几个类型（不暴露内部结构） */
    public int typeCount(Repo r) {
        return r == null ? 0 : r.byName.size();
    }
}
