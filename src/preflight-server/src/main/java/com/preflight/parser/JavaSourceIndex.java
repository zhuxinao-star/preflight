package com.preflight.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 仓库级 Java 源码索引（第九轮，一次解析、多处复用）：
 * 类字段快照供 DtoSchemaExpander 展开请求体，方法位置与调用名供 BodyFieldInferer
 * 反推自由字段取值、供 CallChainLinker 追调用链。
 * 只保留轻量字符串/路径，不常驻 AST，避免大仓 OOM。
 */
public class JavaSourceIndex {

    /** 字段快照：类型保留原文（List<X>、JSONObject 等），说明取注解或 JavaDoc */
    public record FieldInfo(String name, String rawType, String description, boolean required) {}

    /** 类快照：字段 + 声明的泛型参数 + 同文件 import 简名（同名类消歧用）+ 父类简名（VO 常靠继承带字段） */
    public record ClassInfo(String simpleName, String packageName, Path file,
                            List<FieldInfo> fields, List<String> typeParams, String superClass) {}

    /** 方法定位：文件 + 签名要素，真正展开方法体时再按文件精解析一次 */
    public record MethodLoc(Path file, String className, List<String> paramTypes) {}

    private final Map<String, List<ClassInfo>> classes = new HashMap<>();
    private final Map<String, List<MethodLoc>> methods = new HashMap<>();
    private final Map<Path, List<String>> fileImports = new HashMap<>();
    private final Map<Path, Set<String>> invokedNames = new HashMap<>();
    private final Set<Path> javaFiles = new HashSet<>();
    private final Map<Path, CompilationUnit> parseCache = new HashMap<>();
    private final Map<String, Boolean> identityAccessors = new HashMap<>();
    private int maxMethodNameLen = 0;

    public JavaSourceIndex(List<Path> files) {
        for (Path f : files) {
            if (f == null || !f.getFileName().toString().endsWith(".java")) continue;
            javaFiles.add(f);
            CompilationUnit cu = tryParse(f);
            if (cu == null) continue;
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            List<String> imports = new ArrayList<>();
            for (var im : cu.getImports()) {
                String s = im.getNameAsString();
                imports.add(s.substring(s.lastIndexOf('.') + 1));
            }
            fileImports.put(f, imports);
            Set<String> invoked = new HashSet<>();
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                invoked.add(call.getNameAsString());
            }
            invokedNames.put(f, invoked);
            for (String n : invoked) maxMethodNameLen = Math.max(maxMethodNameLen, n.length());
            indexClasses(cu.getTypes(), pkg, f);
        }
    }

    private void indexClasses(List<?> types, String pkg, Path file) {
        for (Object o : types) {
            if (!(o instanceof ClassOrInterfaceDeclaration cd)) continue;
            List<FieldInfo> fields = new ArrayList<>();
            for (FieldDeclaration fd : cd.getFields()) {
                if (fd.isStatic() || fd.isTransient()) continue;
                String desc = describe(fd);
                boolean required = hasAnnotation(fd, "NotNull", "NotEmpty", "NotBlank");
                for (VariableDeclarator v : fd.getVariables()) {
                    if (fields.size() >= 80) break;
                    fields.add(new FieldInfo(v.getNameAsString(), v.getType().asString(), desc, required));
                }
            }
            List<String> typeParams = cd.getTypeParameters().stream().map(t -> t.getNameAsString()).toList();
            String sup = cd.getExtendedTypes().isEmpty() ? ""
                    : baseType(cd.getExtendedTypes().get(0).asString());
            classes.computeIfAbsent(cd.getNameAsString(), k -> new ArrayList<>())
                    .add(new ClassInfo(cd.getNameAsString(), pkg, file, fields, typeParams, sup));
            for (MethodDeclaration m : cd.getMethods()) {
                methods.computeIfAbsent(m.getNameAsString(), k -> new ArrayList<>())
                        .add(new MethodLoc(file, cd.getNameAsString(),
                                m.getParameters().stream().map(p -> p.getTypeAsString()).toList()));
            }
            indexClasses(cd.getMembers(), pkg, file);   // 内部类
        }
    }

    public boolean hasJavaFiles() {
        return !javaFiles.isEmpty();
    }

    public List<Path> files() {
        return List.copyOf(javaFiles);
    }

    public Set<String> invokedNames(Path file) {
        return invokedNames.getOrDefault(file, Set.of());
    }

    public int methodNameLenUpperBound() {
        return maxMethodNameLen;
    }

    public List<String> importsOf(Path file) {
        return fileImports.getOrDefault(file, List.of());
    }

    /** 类简名 → 候选声明（同名多类按包名/import 消歧在调用方处理） */
    public List<ClassInfo> classCandidates(String simpleName) {
        return classes.getOrDefault(simpleName, List.of());
    }

    public boolean knowsClass(String simpleName) {
        return classes.containsKey(simpleName);
    }

    /** 方法名 → 声明位置（按名找，重名方法都返回，调用方靠参数个数/类型过滤） */
    public List<MethodLoc> methodCandidates(String name) {
        return methods.getOrDefault(name, List.of());
    }

    /** 按文件精解析（带缓存，缓存只保留最近若干文件，避免全仓 AST 常驻） */
    public CompilationUnit parseFile(Path file) {
        CompilationUnit cu = parseCache.get(file);
        if (cu != null) return cu;
        cu = tryParse(file);
        if (cu != null) {
            if (parseCache.size() >= 200) parseCache.clear();
            parseCache.put(file, cu);
        }
        return cu;
    }

    /**
     * 这个方法是不是"原样把自己交出去"——方法体只有 {@code return this;}（允许强转与括号）。
     *
     * <p>为什么按声明判而不按名字判：{@code RequestModel extends JSONObject} 这一族里，
     * {@code getReq_comm_data()} 的函数体就是 {@code return this;}，业务键仍然落在请求体<b>根上</b>；
     * 另一族同名方法体是 {@code return req_comm_data;}，那是真的下一层。名字一样、语义相反，
     * 靠名字白名单必然猜错一边，所以来读它自己的声明。找不到的方法名一律否。
     */
    public boolean isIdentityAccessor(String methodName) {
        if (methodName == null || methodName.isEmpty()) return false;
        Boolean cached = identityAccessors.get(methodName);
        if (cached != null) return cached;
        boolean yes = false;
        for (MethodLoc loc : methodCandidates(methodName)) {
            if (!loc.paramTypes().isEmpty()) continue;
            Optional<MethodDeclaration> md = findMethod(loc.file(), loc.className(), methodName, 0);
            if (md.isPresent() && returnsThis(md.get())) {
                yes = true;
                break;
            }
        }
        identityAccessors.put(methodName, yes);
        return yes;
    }

    private boolean returnsThis(MethodDeclaration m) {
        if (m.getBody().isEmpty()) return false;
        var st = m.getBody().get().getStatements();
        if (st.size() != 1 || !(st.get(0) instanceof com.github.javaparser.ast.stmt.ReturnStmt ret)) return false;
        Optional<com.github.javaparser.ast.expr.Expression> e = ret.getExpression();
        if (e.isEmpty()) return false;
        com.github.javaparser.ast.expr.Expression cur = e.get();
        while (true) {
            if (cur instanceof com.github.javaparser.ast.expr.CastExpr c) {
                cur = c.getExpression();
                continue;
            }
            if (cur instanceof com.github.javaparser.ast.expr.EnclosedExpr en) {
                cur = en.getInner();
                continue;
            }
            return cur instanceof com.github.javaparser.ast.expr.ThisExpr;
        }
    }

    /** 在指定文件里找某个方法声明（优先类名+参数个数匹配） */
    public Optional<MethodDeclaration> findMethod(Path file, String className, String methodName, int argCount) {
        CompilationUnit cu = parseFile(file);
        if (cu == null) return Optional.empty();
        MethodDeclaration fallback = null;
        for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (className != null && !className.equals(cd.getNameAsString())) continue;
            for (MethodDeclaration m : cd.getMethods()) {
                if (!m.getNameAsString().equals(methodName)) continue;
                if (m.getParameters().size() == argCount) return Optional.of(m);
                if (fallback == null) fallback = m;
            }
        }
        if (fallback != null) return Optional.of(fallback);
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            if (m.getNameAsString().equals(methodName)
                    && (argCount < 0 || m.getParameters().size() == argCount)) return Optional.of(m);
        }
        return Optional.empty();
    }

    private CompilationUnit tryParse(Path f) {
        try {
            return StaticJavaParser.parse(f);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasAnnotation(FieldDeclaration fd, String... names) {
        for (AnnotationExpr a : fd.getAnnotations()) {
            String n = simple(a.getNameAsString());
            for (String want : names) if (n.equals(want)) return true;
        }
        return false;
    }

    private String describe(FieldDeclaration fd) {
        for (AnnotationExpr a : fd.getAnnotations()) {
            String n = simple(a.getNameAsString());
            if (!n.equals("ApiModelProperty") && !n.equals("ApiParam") && !n.equals("Schema")
                    && !n.equals("JsonProperty")) continue;
            String text = attrOf(a);
            if (text != null) return text;
        }
        return fd.getJavadoc().map(j -> j.getDescription().toText().replaceAll("\\s+", " ").trim())
                .filter(s -> !s.isEmpty()).orElse(null);
    }

    private String attrOf(AnnotationExpr a) {
        try {
            if (a.isSingleMemberAnnotationExpr()) {
                var v = a.asSingleMemberAnnotationExpr().getMemberValue();
                if (v.isStringLiteralExpr()) return v.asStringLiteralExpr().getValue();
                return null;
            }
            if (a.isNormalAnnotationExpr()) {
                for (var pair : a.asNormalAnnotationExpr().getPairs()) {
                    String p = pair.getNameAsString();
                    if ((p.equals("value") || p.equals("name") || p.equals("description"))
                            && pair.getValue() instanceof StringLiteralExpr sl) return sl.getValue();
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String simple(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot > 0 ? qualified.substring(dot + 1) : qualified;
    }

    /** 泛型实参回填：把 DTO 声明的类型参数名映射到调用处给的实参，返回 字段名 → 实际类型 */
    public static Map<String, String> typeArgBindings(ClassInfo target, String usageType) {
        Map<String, String> map = new LinkedHashMap<>();
        List<String> params = target.typeParams();
        if (params.isEmpty()) return map;
        int lt = usageType.indexOf('<');
        if (lt < 0 || !usageType.endsWith(">")) return map;
        List<String> args = splitGeneric(usageType.substring(lt + 1, usageType.length() - 1));
        for (int i = 0; i < Math.min(params.size(), args.size()); i++) {
            map.put(params.get(i).trim(), baseType(args.get(i)));
        }
        return map;
    }

    /** 泛型实参按顶层逗号切分（忽略嵌套 <> 内的逗号） */
    public static List<String> splitGeneric(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    public static String baseType(String t) {
        String s = t == null ? "" : t.trim();
        int lt = s.indexOf('<');
        if (lt > 0) s = s.substring(0, lt);
        if (s.endsWith("[]")) s = s.substring(0, s.length() - 2);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(dot + 1);
        return s.trim();
    }

    public static List<String> typeArgs(String t) {
        int lt = t == null ? -1 : t.indexOf('<');
        if (lt < 0 || !t.endsWith(">")) return List.of();
        return splitGeneric(t.substring(lt + 1, t.length() - 1));
    }

    /**
     * 一个 Java 声明类型 → JSON 类型（JSON 根上那一段的口径：集合就是 array，Map／JSONObject 是 object）。
     *
     * <p>放这里是因为盘上事实只有一本类索引，而"按源码推 JSON 形状"的三处（按实现推出入参、
     * 报文头字段清单、DTO 平铺）都得给同一条映射——各写一份，早晚会给同一个字段两种类型。
     */
    public static String jsonTypeOf(String rawType) {
        String s = rawType == null ? "" : rawType.toLowerCase(Locale.ROOT);
        if (s.startsWith("list") || s.startsWith("set") || s.endsWith("[]") || s.startsWith("collection")) {
            return "array";
        }
        if (s.startsWith("map") || s.contains("jsonobject")) return "object";
        return switch (baseType(s)) {
            case "int", "integer", "long", "short", "byte" -> "integer";
            case "double", "float", "bigdecimal", "biginteger" -> "number";
            case "boolean" -> "boolean";
            default -> "string";
        };
    }
}
