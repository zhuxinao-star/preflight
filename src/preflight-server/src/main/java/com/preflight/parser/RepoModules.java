package com.preflight.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 一个「仓库工作副本」里真正带 Maven 布局的那些目录（{@code src/main/java} 或 {@code src/main/resources} 在的层）。
 *
 * <p>为什么要有这一本（第四十五轮）：登记进系统的仓库根有两种填法——按模块登记（招商
 * {@code D:/git/cms-zhaoshang/unifiedvisit}）和按整份检出登记（国泰海通 {@code D:/git/guotaihaitong/}，
 * 真正的模块在它下面一层 {@code unifiedvisit-platform/}）。按单模块硬路径去找
 * {@code src/main/resources/interface-customer.properties} 时，前者学得到 4939 条、后者一条都学不到，
 * 界面就成了「这个项目参数都没学到」。发现模块这件事只能有一个出口：适配器建服务字典、
 * {@code CallPathIndex} 建只发现索引、建卡时推请求形状，三处必须认同一份模块清单，
 * 否则同一份检出会给出对不上的契约。
 *
 * <p>口径：
 * <ul>
 *   <li>仓库根本身若是模块，就是唯一模块——招商那种按模块登记的结果与改造前逐字一致（不是近似，是不变量）。</li>
 *   <li>往下最多走 {@value #MAX_DEPTH} 层，{@code target/}、{@code node_modules/} 这类编译/依赖目录不进去；
 *       已经是模块的目录仍会继续往下走一层，因为 Maven 的聚合工程会在模块里再套模块。</li>
 *   <li>读不到目录（权限、网络盘掉线）就给空表：调用方本来就有「学不到就说学不到」的分支，不抛。</li>
 * </ul>
 */
public final class RepoModules {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_DIRS = 4000;
    private static final int MAX_MODULES = 16;
    /** 编译产物与依赖目录：里面的 {@code src/main/…} 是副本，读它就是拿旧代码讲新接口 */
    private static final Set<String> PRUNE = Set.of(
            "target", "build", "out", "dist", "node_modules", ".git", ".svn", ".hg", ".idea", "logs", ".tmp");

    private RepoModules() {}

    public static List<Path> of(Path repoRoot) {
        List<Path> out = new ArrayList<>();
        if (repoRoot == null || !Files.isDirectory(repoRoot)) return out;
        List<Path> frontier = new ArrayList<>();
        frontier.add(repoRoot);
        int walked = 0;
        for (int depth = 0; depth <= MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<Path> next = new ArrayList<>();
            for (Path d : frontier) {
                if (++walked > MAX_DIRS || out.size() >= MAX_MODULES) return out;
                if (isModule(d)) out.add(d);
                if (depth == MAX_DEPTH) continue;
                try (Stream<Path> kids = Files.list(d)) {
                    for (Path k : kids.toList()) {
                        if (!Files.isDirectory(k)) continue;
                        String n = k.getFileName().toString();
                        // src 不进去：模块的内部结构不是模块；点开头目录（.svn/.idea）也不进
                        if (n.equals("src") || n.startsWith(".") || PRUNE.contains(n.toLowerCase(Locale.ROOT))) continue;
                        next.add(k);
                    }
                } catch (Exception ignore) {
                }
            }
            frontier = next;
        }
        return out;
    }

    /** 这个目录自己有没有 Maven 那套源码/资源目录 */
    public static boolean isModule(Path d) {
        if (d == null) return false;
        return Files.isDirectory(d.resolve("src").resolve("main").resolve("java"))
                || Files.isDirectory(d.resolve("src").resolve("main").resolve("resources"));
    }

    /** 模块里那份 Java 源码根；没有就返回 null（调用方按「学不到」处理） */
    public static Path javaSrc(Path module) {
        if (module == null) return null;
        Path p = module.resolve("src").resolve("main").resolve("java");
        return Files.isDirectory(p) ? p : null;
    }

    /** 界面与证据里要说清扫了哪几个模块：给相对仓库根的路径，绝对路径会把列表撑开 */
    public static String rel(Path repoRoot, Path p) {
        if (p == null) return "";
        try {
            return repoRoot.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.getFileName() == null ? "" : p.getFileName().toString();
        }
    }

    /**
     * 这份「相对模块根的文件」在这份检出的某一个模块里存在吗（找到第一个就停，不建模块清单）。
     *
     * <p>给的是存在性判据，不是内容：调用方要说「这个仓有声明但没学」还是「本来就没有声明」，
     * 只问在不在，别为此把整份 properties 读一遍。
     */
    public static boolean hasModuleFile(Path repoRoot, Path relToModule) {
        if (repoRoot == null || relToModule == null || !Files.isDirectory(repoRoot)) return false;
        List<Path> frontier = new ArrayList<>();
        frontier.add(repoRoot);
        int walked = 0;
        for (int depth = 0; depth <= MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<Path> next = new ArrayList<>();
            for (Path d : frontier) {
                if (++walked > MAX_DIRS) return false;
                if (Files.isRegularFile(d.resolve(relToModule))) return true;
                if (depth == MAX_DEPTH) continue;
                try (Stream<Path> kids = Files.list(d)) {
                    for (Path k : kids.toList()) {
                        if (!Files.isDirectory(k)) continue;
                        String n = k.getFileName().toString();
                        if (n.equals("src") || n.startsWith(".") || PRUNE.contains(n.toLowerCase(Locale.ROOT))) continue;
                        next.add(k);
                    }
                } catch (Exception ignore) {
                }
            }
            frontier = next;
        }
        return false;
    }
}
