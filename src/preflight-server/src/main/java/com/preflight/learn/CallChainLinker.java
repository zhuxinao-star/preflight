package com.preflight.learn;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 轻量调用链追踪（04-补齐 §3.2 读写标注的底座）：
 * 控制器方法体内的方法调用名 → 找声明该方法的 Java 文件 → 其调用名 → 与 mapper 语句 id 求交。
 * 典型链路 Controller#hf100092 → Service.selectHomeMyTaskList → Mapper.selectHomeMyTaskList，
 * 两跳即可命中 SQL。BFS 限深 2、限文件数，保证大仓可扫描。
 *
 * <p>按方法名找声明、不解析类型，所以可达集**偏宽**（同名方法会多挂）——因此它只用于
 * {@code chain_files} 的「按调用链」档，界面必须照实写明，不许冒充按行命中。
 */
public class CallChainLinker {

    private static final int MAX_FILES = 15;
    private static final int MAX_DEPTH = 2;

    private final Map<String, Set<Path>> declaredMethodIndex = new HashMap<>();
    private final Map<Path, Set<String>> invokedCache = new HashMap<>();
    private final com.preflight.parser.JavaSourceIndex sharedIndex;
    private final Set<String> statementIds;
    private final int maxStatementNameLen;

    public CallChainLinker(List<Path> javaFiles, Set<String> statementIds) {
        this((com.preflight.parser.JavaSourceIndex) null, statementIds);
        for (Path f : javaFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(f);
                for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                    declaredMethodIndex.computeIfAbsent(m.getNameAsString(), k -> new HashSet<>()).add(f);
                }
                invokedCache.put(f, invokedNames(cu));
            } catch (Exception ignore) {
                // 解析失败的文件跳过
            }
        }
    }

    /** 复用整仓 Java 源码索引（学习主流程走这条，避免同一批文件解析两遍） */
    public CallChainLinker(com.preflight.parser.JavaSourceIndex index, Set<String> statementIds) {
        this.statementIds = statementIds;
        int maxLen = 0;
        for (String s : statementIds) maxLen = Math.max(maxLen, s.length());
        this.maxStatementNameLen = maxLen;
        this.sharedIndex = index;
    }

    private Set<String> invokedSet(Path f) {
        if (sharedIndex == null) return invokedCache.getOrDefault(f, Set.of());
        Set<String> out = new HashSet<>();
        for (String n : sharedIndex.invokedNames(f)) {
            if (n.length() >= 4 && n.length() <= maxStatementNameLen + 20) out.add(n);
        }
        return out;
    }

    private Set<Path> filesDeclaring(String methodName) {
        if (sharedIndex == null) return declaredMethodIndex.getOrDefault(methodName, Set.of());
        Set<Path> out = new HashSet<>();
        for (com.preflight.parser.JavaSourceIndex.MethodLoc loc : sharedIndex.methodCandidates(methodName)) {
            out.add(loc.file());
        }
        return out;
    }

    /** 一次追踪的两样产出：命中的语句 id（判读写用）+ 可达的中间层文件（归因改动用） */
    public record Link(Set<String> statementNames, List<Path> chainFiles) {}

    /**
     * 从入口（控制器文件 + 方法名）出发，返回可达的调用名集合与 statementIds 的交集，
     * 以及沿途经过的中间层文件（Service / DAO 实现）。后者是"改了服务层代码影响哪个接口"的唯一依据：
     * 这些文件自己不产出卡片，改动落在它们上时若没有这份可达集，归因就只能给 0 个目标。
     */
    public Link link(Path entryFile, String entryMethod) {
        Set<String> matched = new HashSet<>();
        Set<Path> visited = new HashSet<>();
        List<Path> frontier = new ArrayList<>();
        Set<String> entryInvoked = invokedSet(entryFile);
        for (String inv : entryInvoked) {
            if (statementIds.contains(inv.toLowerCase())) matched.add(inv.toLowerCase());
            frontier.addAll(filesDeclaring(inv));
        }
        if (entryMethod != null && statementIds.contains(entryMethod.toLowerCase())) matched.add(entryMethod.toLowerCase());

        // 完整探索（限深限文件），收集全部可达语句，读写属性判定才完整
        int depth = 1;
        while (depth < MAX_DEPTH && !frontier.isEmpty() && visited.size() < MAX_FILES) {
            List<Path> next = new ArrayList<>();
            for (Path f : frontier) {
                if (!visited.add(f) || visited.size() > MAX_FILES) continue;
                for (String inv : invokedSet(f)) {
                    String lower = inv.toLowerCase();
                    if (statementIds.contains(lower)) matched.add(lower);
                    next.addAll(filesDeclaring(inv));
                }
            }
            frontier = next;
            depth++;
        }
        visited.remove(entryFile);
        return new Link(matched, new ArrayList<>(visited));
    }

    private Set<String> invokedNames(CompilationUnit cu) {
        Set<String> out = new HashSet<>();
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String n = call.getNameAsString();
            if (n.length() >= 4 && n.length() <= maxStatementNameLen + 20) out.add(n);
        }
        return out;
    }

    public static List<Path> collectJavaFiles(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
