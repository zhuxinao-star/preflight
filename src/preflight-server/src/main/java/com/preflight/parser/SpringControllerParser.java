package com.preflight.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spring MVC Controller 解析（PoC-B）：
 * 提取类级 @RequestMapping 前缀 + 方法级映射注解 + 参数注解（@RequestParam/@PathVariable/@RequestBody）。
 */
@Component
public class SpringControllerParser {

    public List<ExtractedUnit> extract(Path file) throws Exception {
        List<ExtractedUnit> units = new ArrayList<>();
        CompilationUnit cu = StaticJavaParser.parse(file);

        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            boolean isController = clazz.getAnnotationByName("RestController").isPresent()
                    || clazz.getAnnotationByName("Controller").isPresent();
            if (!isController) {
                continue;
            }
            String base = firstAttr(clazz.getAnnotationByName("RequestMapping"), "value", "path");

            for (MethodDeclaration m : clazz.getMethods()) {
                MappingInfo info = mappingInfo(m);
                if (info == null) {
                    continue;
                }
                List<ExtractedUnit.ParamDef> params = new ArrayList<>();
                for (Parameter p : m.getParameters()) {
                    ExtractedUnit.ParamDef def = toParamDef(p);
                    if (def != null) {
                        params.add(def);
                    }
                }
                int lineStart = m.getBegin().map(pos -> pos.line).orElse(0);
                int lineEnd = m.getEnd().map(pos -> pos.line).orElse(0);
                extractAnnotationSql(clazz, m, file.getFileName().toString(), lineStart, lineEnd, units);
                units.add(new ExtractedUnit("HTTP_ENDPOINT",
                        clazz.getNameAsString() + "#" + m.getNameAsString(),
                        info.httpMethod(),
                        joinPath(base, info.path()),
                        params, List.of(),
                        file.getFileName().toString(), lineStart, lineEnd,
                        List.of(), snippet(m), null, null));
            }
        }
        return units;
    }

    private record MappingInfo(String httpMethod, String path) {}

    private MappingInfo mappingInfo(MethodDeclaration m) {
        Optional<AnnotationExpr> a;
        if ((a = m.getAnnotationByName("GetMapping")).isPresent()) return new MappingInfo("GET", firstAttr(a, "value", "path"));
        if ((a = m.getAnnotationByName("PostMapping")).isPresent()) return new MappingInfo("POST", firstAttr(a, "value", "path"));
        if ((a = m.getAnnotationByName("PutMapping")).isPresent()) return new MappingInfo("PUT", firstAttr(a, "value", "path"));
        if ((a = m.getAnnotationByName("DeleteMapping")).isPresent()) return new MappingInfo("DELETE", firstAttr(a, "value", "path"));
        if ((a = m.getAnnotationByName("PatchMapping")).isPresent()) return new MappingInfo("PATCH", firstAttr(a, "value", "path"));
        if ((a = m.getAnnotationByName("RequestMapping")).isPresent()) {
            String method = firstAttr(a, "method"); // 如 RequestMethod.POST / {RequestMethod.GET, RequestMethod.POST}
            String verb = "ANY";
            if (method != null && method.contains(".")) {
                verb = method.substring(method.lastIndexOf('.') + 1).replace("[", "").replace("]", "").split(",")[0].trim();
            }
            return new MappingInfo(verb, firstAttr(a, "value", "path"));
        }
        return null;
    }

    private ExtractedUnit.ParamDef toParamDef(Parameter p) {
        Optional<AnnotationExpr> rp = p.getAnnotationByName("RequestParam");
        if (rp.isPresent()) {
            String name = firstAttr(rp, "value", "name");
            String required = firstAttr(rp, "required");
            String def = firstAttr(rp, "defaultValue");
            return new ExtractedUnit.ParamDef(
                    name != null ? name : p.getNameAsString(), p.getTypeAsString(), "query",
                    required == null || Boolean.parseBoolean(required), def);
        }
        Optional<AnnotationExpr> pv = p.getAnnotationByName("PathVariable");
        if (pv.isPresent()) {
            String name = firstAttr(pv, "value", "name");
            return new ExtractedUnit.ParamDef(name != null ? name : p.getNameAsString(),
                    p.getTypeAsString(), "path", true, null);
        }
        Optional<AnnotationExpr> rb = p.getAnnotationByName("RequestBody");
        if (rb.isPresent()) {
            return new ExtractedUnit.ParamDef(p.getNameAsString(), p.getTypeAsString(), "body", true, null);
        }
        // 无注解的简单类型参数：Spring 默认按 query 处理
        if (p.getAnnotations().isEmpty() && isSimpleType(p.getTypeAsString())) {
            return new ExtractedUnit.ParamDef(p.getNameAsString(), p.getTypeAsString(), "query", false, null);
        }
        return null;
    }

    private boolean isSimpleType(String type) {
        return List.of("int", "long", "double", "float", "boolean", "String", "Integer", "Long",
                "Double", "Float", "Boolean", "java.lang.String").contains(type);
    }

    private String joinPath(String base, String path) {
        String b = base == null ? "" : base;
        String s = path == null ? "" : path;
        if (s.isEmpty()) return b.isEmpty() ? "/" : b;
        if (!b.isEmpty() && !b.startsWith("/")) b = "/" + b;
        if (!s.startsWith("/")) s = "/" + s;
        return (b + s).replaceAll("//+", "/");
    }

    /** 取注解指定属性值：Normal 注解按属性名查找；单值注解仅等价于 value/path/name，查其他属性返回 null */
    private String firstAttr(Optional<AnnotationExpr> anno, String... names) {
        if (anno.isEmpty()) return null;
        AnnotationExpr a = anno.get();
        if (a instanceof NormalAnnotationExpr n) {
            for (String name : names) {
                for (MemberValuePair pair : n.getPairs()) {
                    if (pair.getNameAsString().equals(name)) {
                        return stringOf(pair.getValue());
                    }
                }
            }
            return null;
        }
        if (a instanceof SingleMemberAnnotationExpr s) {
            for (String name : names) {
                if (name.equals("value") || name.equals("path") || name.equals("name")) {
                    return stringOf(s.getMemberValue());
                }
            }
            return null;
        }
        if (a instanceof MarkerAnnotationExpr) return null;   // 裸注解无属性值：返回 null 让调用方回退到形参名
        return null;
    }

    private String stringOf(Expression e) {
        if (e instanceof StringLiteralExpr s) return s.getValue();
        if (e instanceof ArrayInitializerExpr arr) {
            StringBuilder sb = new StringBuilder();
            arr.getValues().forEach(v -> {
                String s = stringOf(v);
                if (s != null) sb.append(sb.isEmpty() ? s : "," + s);
            });
            return sb.toString();
        }
        return e.toString();
    }

    /** R10：提取 @Select/@Update/@Insert/@Delete 注解 SQL（注解 SQL 项目的直测来源） */
    private static final List<String> SQL_ANNOTATIONS = List.of("Select", "Update", "Insert", "Delete");

    private void extractAnnotationSql(ClassOrInterfaceDeclaration clazz, MethodDeclaration m,
                                      String fileName, int lineStart, int lineEnd, List<ExtractedUnit> units) {
        for (String anno : SQL_ANNOTATIONS) {
            Optional<AnnotationExpr> a = m.getAnnotationByName(anno);
            if (a.isEmpty()) continue;
            String sql = stringOf(rawMember(a.get()));
            if (sql == null || sql.isBlank()) continue;
            StringBuilder exec = new StringBuilder();
            java.util.LinkedHashMap<String, String> params = new java.util.LinkedHashMap<>();
            java.util.regex.Matcher hash = java.util.regex.Pattern.compile("#\\{\\s*([^}]+?)\\s*}").matcher(sql);
            while (hash.find()) {
                String simple = hash.group(1).split(",")[0].trim();
                int dot = simple.lastIndexOf('.');
                params.putIfAbsent(dot >= 0 ? simple.substring(dot + 1) : simple, "string");
                hash.appendReplacement(exec, "?");
            }
            hash.appendTail(exec);
            List<String> warnings = new ArrayList<>();
            if (java.util.regex.Pattern.compile("\\$\\{[^}]+}").matcher(sql).find()) {
                warnings.add("注解 SQL 含 ${} 拼接占位符，存在注入风险");
            }
            var info = SqlAnalyzer.analyze(exec.toString());
            String sqlKind = switch (anno) {
                case "Select" -> "sql_select";
                case "Update" -> "sql_update";
                case "Insert" -> "sql_insert";
                default -> "sql_delete";
            };
            units.add(new ExtractedUnit("SQL_STATEMENT",
                    fileName + "#" + m.getNameAsString() + ":" + anno, null, null,
                    params.entrySet().stream()
                            .map(e -> new ExtractedUnit.ParamDef(e.getKey(), e.getValue(), "sql", true, null)).toList(),
                    info.tables() == null ? List.of() : info.tables(),
                    fileName, lineStart, lineEnd, warnings,
                    sql.length() > 200 ? sql.substring(0, 200) + "..." : sql, exec.toString(), null));
        }
    }

    private com.github.javaparser.ast.expr.Expression rawMember(AnnotationExpr a) {
        if (a instanceof SingleMemberAnnotationExpr s) return s.getMemberValue();
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) if (p.getNameAsString().equals("value")) return p.getValue();
        }
        return null;
    }

    private String snippet(MethodDeclaration m) {
        String s = m.toString();
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
