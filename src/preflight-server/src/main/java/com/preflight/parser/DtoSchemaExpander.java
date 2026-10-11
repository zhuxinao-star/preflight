package com.preflight.parser;

import com.preflight.parser.JavaSourceIndex.ClassInfo;
import com.preflight.parser.JavaSourceIndex.FieldInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请求体结构学习（第九轮）：把 @RequestBody 的 DTO 递归展开成嵌套 JSON Schema。
 *
 * 学习前只记下形参名（{"requestModel":{"type":"string"}}），测试台只能给一个空 {} 框；
 * 展开后能报出字段名、类型与 JavaDoc/@ApiModelProperty 说明。自由结构字段（JSONObject/Map）
 * 静态展开不出来，标 freeForm=true 交给 BodyFieldInferer 从取值代码反推。
 */
public class DtoSchemaExpander {

    private static final int MAX_DEPTH = 3;
    private static final int MAX_FIELDS_PER_CLASS = 60;
    private static final int FLATTEN_CAP = 200;
    private static final List<String> SIMPLE_JSON_TYPES = List.of(
            "byte", "short", "int", "integer", "long", "float", "double", "bigdecimal", "biginteger",
            "boolean", "char", "character", "string", "date", "timestamp", "localdate", "localdatetime", "localtime");
    private static final Set<String> FREE_FORM_TYPES = Set.of(
            "jsonobject", "jsonarray", "json", "jsonelement", "map", "linkedhashmap", "concurrenthashmap",
            "treemap", "hashtable", "object", "jsonnode", "gson");
    private static final Set<String> IGNORED_SUPERCLASSES = Set.of(
            "object", "serializable", "cloneable");

    private final JavaSourceIndex index;

    public DtoSchemaExpander(JavaSourceIndex index) {
        this.index = index;
    }

    /** 展开结果：properties + 自由字段名清单（交给取值反推）+ 未能定位的字段名 */
    public record BodySchema(Map<String, Object> properties, List<String> freeFormFields) {}

    public boolean knows(String className) {
        return index.knowsClass(className);
    }

    /** 形参类型整体就是自由结构（Map/JSONObject）：键名只能靠取值反推 */
    public boolean looksFreeForm(String rawType) {
        return isFreeForm(rawType == null ? "" : rawType);
    }

    /**
     * 形参类型自己不是 Map/JSONObject，但<b>继承了</b>一个自由结构类（{@code RequestModel extends JSONObject}）：
     * 这种 DTO 的根上照样能挂任意键，声明里那几条字段只是"顺带定了几个键的形状"。
     *
     * <p>只按名判会漏掉这一族——漏掉的后果不是"少几个字段"，而是根层压根不进取值反推的种子清单，
     * 于是整张卡的业务参数一条都学不出来（国泰海通 195 张卡正是这样）。
     * 父类查不到源码就停：不靠"名字像不像 DTO"猜，只认盘上写得出来的那条 {@code extends}。
     */
    public boolean inheritsFreeForm(String rawType, List<String> importHints) {
        String cur = JavaSourceIndex.baseType(rawType == null ? "" : rawType);
        Set<String> walked = new HashSet<>();
        List<String> hints = importHints == null ? List.of() : importHints;
        while (!cur.isBlank() && walked.add(cur)) {
            if (isFreeForm(cur)) return true;
            ClassInfo ci = resolve(cur, hints);
            if (ci == null) return false;
            String sup = ci.superClass() == null ? "" : JavaSourceIndex.baseType(ci.superClass());
            if (sup.isEmpty() || IGNORED_SUPERCLASSES.contains(sup.toLowerCase(java.util.Locale.ROOT))) return false;
            List<String> next = new ArrayList<>(hints);
            if (ci.packageName() != null && !ci.packageName().isEmpty()) next.add(ci.packageName() + "." + sup);
            hints = next;
            cur = sup;
        }
        return false;
    }

    /** 展开 DTO 为 properties（字段名 → 子 schema）。类未在本仓库找到定义时返回带 hint 的空壳。 */
    public BodySchema expand(String dtoClassName, List<String> importHints) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> free = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(JavaSourceIndex.baseType(dtoClassName));
        expandInto(dtoClassName, importHints, props, free, 1, visited);
        return new BodySchema(props, free);
    }

    /** 平铺后的一个字段：rawType 保留声明原文，owner 记这个字段来自哪个类（含父类） */
    public record FlatField(String name, String rawType, String description, String owner) {}

    /**
     * 沿继承链把字段平铺出来（第卅九轮补：出参 VO 常靠父类带字段）。
     *
     * <p>{@link #expand} 只遍历 {@code target.fields()}，所以 {@code XxxListVo extends Xxx}
     * 这种"子类只加两个字段、业务列全在父类"的返回类型会几乎展开成空的——而它正是接口出参。
     * 这里给一条只要字段名／类型／说明的平铺路径：子类字段先说，父类重名不再计（同 Java 字段遮蔽），
     * 遇到 {@link #isSimple} 的父类（Object、BasePojo 之类无源码的基类）就停。
     *
     * @param className   返回类型（泛型／数组会先剥成基础类型）
     * @param importHints 调用处的全限定 import 清单，同名多类消歧用；父类按「子类包名.父类名」再补一条候选
     */
    public List<FlatField> flatten(String className, List<String> importHints) {
        List<FlatField> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> walked = new HashSet<>();
        List<String> hints = importHints == null ? List.of() : importHints;
        String cur = JavaSourceIndex.baseType(className == null ? "" : className);
        while (!cur.isBlank() && out.size() < FLATTEN_CAP && walked.add(cur)) {
            ClassInfo ci = resolve(cur, hints);
            if (ci == null) break;
            for (FieldInfo fi : ci.fields()) {
                if (out.size() >= FLATTEN_CAP) break;
                if (!seen.add(fi.name().toLowerCase(java.util.Locale.ROOT))) continue;
                out.add(new FlatField(fi.name(), fi.rawType(), fi.description(), ci.simpleName()));
            }
            String sup = ci.superClass() == null ? "" : JavaSourceIndex.baseType(ci.superClass());
            if (sup.isEmpty() || isSimple(sup) || IGNORED_SUPERCLASSES.contains(sup.toLowerCase(java.util.Locale.ROOT))) {
                break;
            }
            // 下一层的消歧线索：原 import 清单 + 「子类同包找父类」这条最常见的写法
            List<String> next = new ArrayList<>(hints);
            if (ci.packageName() != null && !ci.packageName().isEmpty()) next.add(ci.packageName() + "." + sup);
            hints = next;
            cur = sup;
        }
        return out;
    }

    private void expandInto(String usageType, List<String> importHints, Map<String, Object> out,
                            List<String> freeFields, int depth, Set<String> visited) {
        String className = JavaSourceIndex.baseType(usageType);
        ClassInfo target = resolve(className, importHints);
        if (target == null) {
            out.put("_unresolved", Map.of("type", "string",
                    "hint", "类 " + className + " 未在本仓库找到定义，字段需手工补填"));
            return;
        }
        Map<String, String> bindings = JavaSourceIndex.typeArgBindings(target, usageType);
        for (FieldInfo fi : target.fields()) {
            if (out.size() >= MAX_FIELDS_PER_CLASS) return;
            String rawType = applyBindings(fi.rawType(), bindings);
            Map<String, Object> fs = new LinkedHashMap<>();
            String lower = JavaSourceIndex.baseType(rawType).toLowerCase();

            if (isFreeForm(rawType)) {
                fs.put("type", "object");
                fs.put("freeForm", true);
                fs.put("javaType", rawType);
                freeFields.add(fi.name());
            } else if (isCollection(rawType)) {
                fillArray(rawType, importHints, fs, freeFields, depth, visited);
            } else if (isSimple(lower)) {
                fs.put("type", jsonType(lower));
            } else if (index.knowsClass(JavaSourceIndex.baseType(rawType))) {
                fillNested(rawType, importHints, fs, freeFields, depth, visited);
            } else {
                fs.put("type", "string");
                fs.put("javaType", rawType);
                fs.put("hint", "类型 " + rawType + " 未在本仓库找到定义，按字符串处理");
            }
            if (fi.description() != null) fs.put("description", fi.description());
            if (fi.required()) fs.put("required", true);
            out.put(fi.name(), fs);
        }
        if (out.isEmpty()) {
            out.put("_empty", Map.of("type", "string", "hint", "类 " + className + " 没有可展开的实例字段"));
        }
    }

    private void fillNested(String rawType, List<String> importHints, Map<String, Object> fs,
                            List<String> freeFields, int depth, Set<String> visited) {
        String base = JavaSourceIndex.baseType(rawType);
        if (depth >= MAX_DEPTH || visited.contains(base)) {
            fs.put("type", "object");
            fs.put("hint", (depth >= MAX_DEPTH ? "嵌套超过 " + MAX_DEPTH + " 层" : "循环引用") + "，未展开：" + rawType);
            return;
        }
        Map<String, Object> nested = new LinkedHashMap<>();
        Set<String> sub = new HashSet<>(visited);
        sub.add(base);
        fs.put("type", "object");
        fs.put("javaType", rawType);
        expandInto(rawType, importHints, nested, freeFields, depth + 1, sub);
        fs.put("properties", nested);
    }

    private void fillArray(String rawType, List<String> importHints, Map<String, Object> fs,
                           List<String> freeFields, int depth, Set<String> visited) {
        fs.put("type", "array");
        List<String> args = JavaSourceIndex.typeArgs(rawType);
        String elem = rawType.endsWith("[]")
                ? JavaSourceIndex.baseType(rawType.substring(0, rawType.length() - 2))
                : (args.isEmpty() ? null : JavaSourceIndex.baseType(args.get(args.size() - 1)));
        if (elem == null || elem.isEmpty()) {
            fs.put("items", Map.of("type", "string"));
            fs.put("hint", "集合元素类型未标注泛型，元素结构需手工补填");
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        if (isFreeForm(elem)) {
            item.put("type", "object");
            item.put("freeForm", true);
            item.put("javaType", elem);
            freeFields.add("*[]" + elem);
        } else if (isSimple(elem)) {
            item.put("type", jsonType(elem));
        } else if (index.knowsClass(elem)) {
            Map<String, Object> nested = new LinkedHashMap<>();
            Set<String> sub = new HashSet<>(visited);
            sub.add(elem);
            item.put("type", "object");
            if (depth < MAX_DEPTH && !visited.contains(elem)) {
                expandInto(elem, importHints, nested, freeFields, depth + 1, sub);
                item.put("properties", nested);
            } else {
                item.put("hint", "元素类型 " + elem + " 递归或超深，未展开");
            }
        } else {
            item.put("type", "string");
            item.put("javaType", elem);
        }
        fs.put("items", item);
    }

    /** 字段声明里的泛型参数名（T/E/K/V）替换为调用处给的实参 */
    private String applyBindings(String rawType, Map<String, String> bindings) {
        if (bindings.isEmpty()) return rawType;
        String out = rawType;
        for (Map.Entry<String, String> en : bindings.entrySet()) {
            out = out.replaceAll("\\b" + java.util.regex.Pattern.quote(en.getKey()) + "\\b", en.getValue());
        }
        return out;
    }

    /** 同名多类消歧：import 命中 → 包名含 request/vo/dto 优先 → 首个候选 */
    private ClassInfo resolve(String className, List<String> importHints) {
        List<ClassInfo> cands = index.classCandidates(className);
        if (cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.get(0);
        for (ClassInfo c : cands) {
            if (importHints != null && importHints.contains(c.packageName() + "." + className)) return c;
        }
        for (ClassInfo c : cands) {
            String pkg = c.packageName() == null ? "" : c.packageName().toLowerCase();
            if (pkg.contains("request") || pkg.contains("vo") || pkg.contains("dto") || pkg.contains("param")) return c;
        }
        return cands.get(0);
    }

    /** 集合类型：元素结构靠泛型／数组推，不是"根上能挂任意键"那种负载 */
    public static boolean isCollection(String rawType) {
        String b = JavaSourceIndex.baseType(rawType).toLowerCase();
        return (rawType.contains("<") && !rawType.toLowerCase().contains("map"))
                || rawType.endsWith("[]")
                || Set.of("list", "arraylist", "linkedlist", "set", "hashset", "treeset", "collection").contains(b);
    }

    /**
     * "根上能挂任意键"的那一族（Map／JSONObject 及其同类）：静态展开只能给出已知的几个键。
     * 公开给 {@code InterfaceCustomerDispatchAdapter} 用——它判请求体形状时必须是同一套口径，
     * 两套"什么算自由结构"迟早会给出对不上的信封。
     */
    public static boolean isFreeForm(String rawType) {
        String s = rawType.toLowerCase();
        if (s.contains("json")) return true;
        String b = JavaSourceIndex.baseType(rawType).toLowerCase();
        if (FREE_FORM_TYPES.contains(b)) return true;
        // Map<K,V> 的键名来自运行期，静态展开无意义
        return s.startsWith("map") || s.startsWith("java.util.map") || s.contains("<map") || b.endsWith("map");
    }

    /** 值类型（String／int／Date…）：这种字段不可能是报文头对象，也不可能是业务负载 */
    public static boolean isSimple(String type) {
        String b = JavaSourceIndex.baseType(type).toLowerCase();
        return SIMPLE_JSON_TYPES.contains(b) || IGNORED_SUPERCLASSES.contains(b);
    }

    private String jsonType(String javaType) {
        return switch (JavaSourceIndex.baseType(javaType).toLowerCase()) {
            case "int", "integer", "long", "short", "byte" -> "integer";
            case "double", "float", "bigdecimal", "biginteger" -> "number";
            case "boolean" -> "boolean";
            default -> "string";
        };
    }
}
