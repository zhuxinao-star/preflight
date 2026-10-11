package com.preflight.parser;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import com.preflight.parser.JavaSourceIndex.MethodLoc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自由结构字段反推（第九轮）：JSONObject / Map 这类字段的键名静态展开不出来，
 * 但代码里一定会"取值"——getString("pageoffset")、paramsMap.get("hcsjbh")、SQL 里的 #{hcsjbh}。
 * 本类顺着 @RequestBody 表达式的传播路径（局部变量、转换调用、实参传递）收集这些字面量键，
 * 按可信度分三档写进接口卡，测试台据此预填请求体骨架。
 *
 * 只认"读"不认"写"：put("orderby", ...) 是服务端自己塞的，不是调用方要传的参。
 * 传播只认精确表达式文本，不做类型推断——宁可少收也不把标量变量误当成负载。
 *
 * <p>第四十四轮补：老工程的真实写法是<b>三层往下取</b>——
 * {@code list = (List) body.get("REQUESTS"); Map d = (Map) list.get(0).get("REQ_COMM_DATA"); d.get("USER_ID");}。
 * 原来只在"表达式文本完全相等"的那一层传播，强转一包、或链式取个下标就断链，结果只学到最外层一个键。
 * 现在传播认三件事：剥掉强转与括号、{@code x.get("K")} 与 {@code x.get(0)} 都算负载的下一层、
 * 以及被这种表达式初始化/赋值的变量名。每条键因此带得上<b>键路径</b>，接口卡能落成嵌套结构而不是一层平铺。
 */
public class BodyFieldInferer {

    /** 读值方法 → 该键的 JSON 类型猜测（有声明类型时以声明类型为准） */
    private static final Map<String, String> READ_METHODS = Map.ofEntries(
            Map.entry("getstring", "string"), Map.entry("getstringvalue", "string"),
            Map.entry("getinteger", "integer"), Map.entry("getint", "integer"),
            Map.entry("getlong", "integer"), Map.entry("getdouble", "number"),
            Map.entry("getfloat", "number"), Map.entry("getbigdecimal", "number"),
            Map.entry("getboolean", "boolean"), Map.entry("getdate", "string"),
            Map.entry("get", "string"), Map.entry("getjsonobject", "object"),
            Map.entry("getjsonarray", "array"), Map.entry("optstring", "string"),
            Map.entry("optint", "integer"), Map.entry("optlong", "integer"),
            Map.entry("optboolean", "boolean"), Map.entry("optjsonobject", "object"),
            Map.entry("containskey", "string"), Map.entry("getordefault", "string"),
            Map.entry("getpath", "object"));
    /** 把负载换一层皮后继续用的转换调用（结果仍是同一份数据） */
    private static final Set<String> CONVERSIONS = Set.of(
            "jsontomap", "tojavamap", "jsontomaplist", "tojavaobject", "parseobject", "parsearray",
            "getinnermap", "tomap", "tostringmap", "jsonstringtomap", "totype", "deepclone");
    private static final Set<String> WRITE_METHODS = Set.of("put", "putall", "putifabsent", "set");
    private static final Pattern SQL_KEY = Pattern.compile("#\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)");
    private static final int MAX_HOPS = 2;
    private static final int MAX_METHODS = 40;
    private static final int MAX_KEYS = 40;
    /** 键路径最多几层：再深就不是调用方手填的入参了，而且容易把内部结构整个抄进卡片 */
    private static final int MAX_PATH = 4;

    private final JavaSourceIndex index;

    public BodyFieldInferer(JavaSourceIndex index) {
        this.index = index;
    }

    /**
     * 一个推断出的键：名字 + JSON 类型 + 来源说明 + 置信度（high/medium/low）+ 键路径。
     * {@code path} 是它在负载里的完整位置（如 {@code [REQUESTS, REQ_COMM_DATA, USER_ID]}），
     * 接口卡据此建嵌套结构；只有一层时与 {@code key} 相同。
     */
    public record InferredKey(String key, String jsonType, String via, String confidence, List<String> path) {}

    private static final class Ctx {
        final Map<String, InferredKey> found = new LinkedHashMap<>();
        final Set<String> written = new HashSet<>();
        final Set<String> scanned = new HashSet<>();
    }

    /**
     * 反推一个自由字段的键集合。
     *
     * @param entryFile  控制器文件（绝对路径）
     * @param methodName 控制器方法名
     * @param seeds      该字段在代码里的取值表达式（requestModel.getReq_comm_data() / 直接字段访问 / 形参名本身）
     * @param linkedSqls 该接口已关联的 SQL 原文（最低档兜底）
     */
    public List<InferredKey> infer(Path entryFile, String methodName, List<String> seeds, List<String> linkedSqls) {
        Ctx ctx = new Ctx();
        if (entryFile != null && methodName != null && !seeds.isEmpty()) {
            String cls = JavaSourceIndex.baseType(entryFile.getFileName().toString());
            Map<String, List<String>> seedPaths = new LinkedHashMap<>();
            for (String s : seeds) seedPaths.put(s, List.of());
            visit(entryFile, cls, methodName, seedPaths, 0, ctx);
        }
        for (String sql : linkedSqls == null ? List.<String>of() : linkedSqls) {
            Matcher m = SQL_KEY.matcher(sql == null ? "" : sql);
            while (m.find()) {
                String raw = m.group(1);
                String key = raw.contains(".") ? raw.substring(0, raw.indexOf('.')) : raw;
                if (key.isEmpty() || key.length() > 40 || ctx.written.contains(key)) continue;
                ctx.found.putIfAbsent(key.toLowerCase(Locale.ROOT),
                        new InferredKey(key, "string", "关联 SQL 占位符 #{" + raw + "}", "low", List.of(key)));
            }
        }
        // 父层先落：浅路径的节点自己就是证据（类型来自它的声明），深处的键才不用替它猜类型
        List<InferredKey> out = new ArrayList<>(ctx.found.values());
        out.sort(Comparator.comparingInt(k -> k.path().size()));
        return out;
    }

    /** 在一个方法体内做流动传播 + 取值收集，必要时向下跳一层（服务方法 / DAO 方法） */
    private void visit(Path file, String className, String methodName, Map<String, List<String>> seeds,
                       int depth, Ctx ctx) {
        if (depth > MAX_HOPS || ctx.scanned.size() >= MAX_METHODS || seeds.isEmpty()) return;
        String slot = className + "#" + methodName + "#" + depth;
        if (!ctx.scanned.add(slot)) return;
        Optional<MethodDeclaration> md = index.findMethod(file, className, methodName, -1);
        if (md.isEmpty()) return;
        BlockStmt body = md.get().getBody().orElse(null);
        if (body == null) return;

        Map<String, List<String>> flow = new LinkedHashMap<>(seeds);
        for (int round = 0; round < 4; round++) {
            // 迭代展开别名链：jsonObj → ObjectConverUtil.jsonToMap(jsonObj) → paramsMap
            if (!grow(flow, body)) break;
            if (flow.size() > 200) return;   // 传播失控（大方法/连环赋值），放弃这个方法
        }

        Map<String, String> declared = declaredTypes(body);
        List<Hop> hops = new ArrayList<>();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            String lower = call.getNameAsString().toLowerCase(Locale.ROOT);
            List<Expression> args = call.getArguments();

            if (WRITE_METHODS.contains(lower)) {
                // 服务端自己塞的键，不算调用方入参：按完整路径记账，别的层里同名的键不受影响
                List<String> p = pathOf(call, flow);
                if (p != null && !p.isEmpty()) ctx.written.add(join(p));
                continue;
            }
            if (READ_METHODS.containsKey(lower)) {
                if (args.isEmpty() || !(unwrap(args.get(0)) instanceof StringLiteralExpr key)) continue;
                List<String> p = pathOf(call, flow);
                if (p == null || p.isEmpty()) continue;
                String k = key.getValue();
                if (k.isEmpty() || k.length() > 40 || k.contains(" ")) continue;
                if (ctx.written.contains(join(p))) continue;
                if (ctx.found.size() >= MAX_KEYS) continue;
                String via = (depth == 0 ? "控制器 " : "调用链 ") + simpleClass(className) + "#" + methodName
                        + " 用 " + call.getNameAsString() + "(\"" + k + "\") 取值";
                InferredKey ik = new InferredKey(k, jsonType(lower, call, declared), via,
                        depth == 0 ? "high" : "medium", p);
                ctx.found.merge(join(p).toLowerCase(Locale.ROOT), ik,
                        (a, b) -> rank(a.confidence()) <= rank(b.confidence()) ? a : b);
                continue;   // 读值的调用不把负载传给别处
            }
            if (CONVERSIONS.contains(lower)) continue;
            // 负载被当实参传进别的方法 → 下一跳（服务方法 / DAO 方法）
            for (int i = 0; i < args.size(); i++) {
                List<String> p = pathOf(args.get(i), flow);
                if (p != null) {
                    hops.add(new Hop(call.getNameAsString(), i, args.size(), p));
                    break;
                }
            }
        }
        if (depth >= MAX_HOPS) return;
        for (Hop h : hops) follow(h, ctx, depth);
    }

    private record Hop(String calleeName, int argIndex, int argCount, List<String> path) {}

    /** 解析被调方法声明位置：参数个数匹配 + 该位置参数不是 JDK 标量类型 */
    private void follow(Hop h, Ctx ctx, int depth) {
        for (MethodLoc loc : index.methodCandidates(h.calleeName())) {
            if (loc.paramTypes().size() != h.argCount()) continue;
            String paramType = loc.paramTypes().get(h.argIndex());
            if (isScalar(paramType)) continue;
            Optional<MethodDeclaration> md = index.findMethod(loc.file(), loc.className(), h.calleeName(), h.argCount());
            if (md.isEmpty()) continue;
            List<Parameter> ps = md.get().getParameters();
            if (h.argIndex() >= ps.size()) continue;
            visit(loc.file(), loc.className(), h.calleeName(),
                    Map.of(ps.get(h.argIndex()).getNameAsString(), h.path()), depth + 1, ctx);
            if (ctx.scanned.size() >= MAX_METHODS) return;
        }
    }

    /** 流动集合扩张：声明初始化、赋值、转换调用的结果都算同一份数据（连同它取到的那一层路径） */
    private boolean grow(Map<String, List<String>> flow, BlockStmt body) {
        boolean changed = false;
        for (VariableDeclarator v : body.findAll(VariableDeclarator.class)) {
            Optional<Expression> init = v.getInitializer();
            if (init.isEmpty() || flow.containsKey(v.getNameAsString())) continue;
            List<String> p = pathOf(init.get(), flow);
            if (p != null) {
                flow.put(v.getNameAsString(), p);
                changed = true;
            }
        }
        for (AssignExpr a : body.findAll(AssignExpr.class)) {
            if (!a.getTarget().isNameExpr() || flow.containsKey(a.getTarget().toString())) continue;
            List<String> p = pathOf(a.getValue(), flow);
            if (p != null) {
                flow.put(a.getTarget().toString(), p);
                changed = true;
            }
        }
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (!CONVERSIONS.contains(call.getNameAsString().toLowerCase(Locale.ROOT))) continue;
            List<String> p = pathOf(call, flow);
            if (p != null && !flow.containsKey(call.toString())) {
                flow.put(call.toString(), p);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 这个表达式指的是负载的哪一层；不是负载返回 {@code null}。
     * 认四种形态：变量名／字段访问本身、{@code 负载.get("字面键")}（下一层）、
     * {@code 负载.get(整数)}（数组下标，同一层）、转换调用（换皮仍是同一层）。强转与括号先剥。
     */
    private List<String> pathOf(Expression e, Map<String, List<String>> flow) {
        if (e == null) return null;
        Expression cur = unwrap(e);
        if (cur instanceof MethodCallExpr call) {
            List<String> base = pathOf(call.getScope().orElse(null), flow);
            if (base == null) return null;
            String lower = call.getNameAsString().toLowerCase(Locale.ROOT);
            if (WRITE_METHODS.contains(lower)) return null;   // 写出去的那份不再当调用方的负载
            List<Expression> args = call.getArguments();
            if (args.size() == 1) {
                Expression a0 = unwrap(args.get(0));
                if (READ_METHODS.containsKey(lower) && a0 instanceof StringLiteralExpr lit) {
                    if (base.size() >= MAX_PATH) return null;
                    List<String> p = new ArrayList<>(base);
                    p.add(lit.getValue());
                    return p;
                }
                if (a0 instanceof IntegerLiteralExpr) return base;   // 下标不加键
            }
            if (READ_METHODS.containsKey(lower) || CONVERSIONS.contains(lower)) return base;
            // 恒等访问器（{@code return this;}）＝同一层换种叫法：招商那份 RequestModel 有真字段走不到这里，
            // 国泰海通那份 extends JSONObject 的写法业务键就在根上，断在这一跳就等于整张卡学不到入参
            if (args.isEmpty() && index.isIdentityAccessor(call.getNameAsString())) return base;
            return null;
        }
        if (cur instanceof NameExpr n) return flow.get(n.getNameAsString());
        if (cur instanceof FieldAccessExpr) return flow.get(cur.toString());
        return flow.get(cur.toString());
    }

    private Expression unwrap(Expression e) {
        Expression cur = e;
        while (true) {
            if (cur instanceof com.github.javaparser.ast.expr.CastExpr c) {
                cur = c.getExpression();
                continue;
            }
            if (cur instanceof EnclosedExpr en) {
                cur = en.getInner();
                continue;
            }
            return cur;
        }
    }

    /** 取值表达式 → 接收它的局部变量声明类型（类型比方法名可信：get() 返回的可能是 List 也可能是 Map） */
    private Map<String, String> declaredTypes(BlockStmt body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (VariableDeclarator v : body.findAll(VariableDeclarator.class)) {
            Optional<Expression> init = v.getInitializer();
            if (init.isEmpty() || !(unwrap(init.get()) instanceof MethodCallExpr)) continue;
            Type t = v.getType();
            out.putIfAbsent(unwrap(init.get()).toString(), t.asString());
        }
        return out;
    }

    private String jsonType(String readMethod, MethodCallExpr call, Map<String, String> declared) {
        String decl = declared.get(call.toString());
        if (decl != null) {
            String b = JavaSourceIndex.baseType(decl).toLowerCase(Locale.ROOT);
            if (b.endsWith("list") || b.endsWith("set") || b.endsWith("collection") || b.endsWith("[]")) return "array";
            if (b.endsWith("map") || b.equals("jsonobject") || b.equals("json")) return "object";
            if (b.equals("string") || b.equals("charsequence") || b.equals("date")) return "string";
            if (b.equals("int") || b.equals("integer") || b.equals("long") || b.equals("short")) return "integer";
            if (b.equals("double") || b.equals("float") || b.equals("bigdecimal")) return "number";
            if (b.equals("boolean")) return "boolean";
        }
        return READ_METHODS.getOrDefault(readMethod, "string");
    }

    private static String join(List<String> path) {
        return String.join(".", path);
    }

    private boolean isScalar(String type) {
        String b = JavaSourceIndex.baseType(type).toLowerCase(Locale.ROOT);
        return Set.of("int", "integer", "long", "short", "byte", "double", "float", "boolean", "string",
                "char", "character", "bigdecimal", "biginteger", "date", "localdate", "localdatetime").contains(b);
    }

    private String simpleClass(String className) {
        if (className == null || className.isEmpty()) return "?";
        int dot = className.lastIndexOf('.');
        return dot > 0 ? className.substring(dot + 1) : className;
    }

    private int rank(String confidence) {
        return switch (confidence) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    /** 自由字段在代码里可能的取表达式：getter 命名各框架不一（getReq_comm_data / getReqCommData / 直接字段） */
    public static List<String> accessors(String paramName, String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return List.of(paramName);
        List<String> out = new ArrayList<>();
        String cap = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        out.add(paramName + ".get" + cap + "()");
        String camel = toCamel(fieldName);
        if (!camel.equals(cap)) {
            out.add(paramName + ".get" + Character.toUpperCase(camel.charAt(0)) + camel.substring(1) + "()");
        }
        out.add(paramName + "." + fieldName);
        return out;
    }

    private static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }
}
