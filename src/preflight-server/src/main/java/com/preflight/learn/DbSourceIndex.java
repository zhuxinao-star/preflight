package com.preflight.learn;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 盘上数据库对象源码索引（第卅五轮）：给定 {@code P_KDCC.UP_MID_28102044} 这种引用，
 * 找到它的源码在哪个文件、哪一行，并把那一段取出来。
 *
 * <p>为什么需要它：接口的出参形状写在过程的 {@code OPEN 游标 FOR SELECT …} 里，而过程代码在
 * 老工程基本不入库（库停了也照样能读），只存在 DB 仓库的工作副本里——实测形态有三种：
 * 独立过程 {@code 08_procedure/up_mid_x.prc}、包体 {@code 07_pakage/p_kdcc.bdy} 里的
 * {@code PROCEDURE up_mid_x(…)}、以及整库导出 {@code 数据库对象/kdbase_20260713.sql}。
 *
 * <p>查找按便宜的先来：先按文件名（包名 / 过程名）命中，命中不了才扫内容；文件内容按
 * 路径+大小+修改时间缓存，改过就重读。全程只读，什么都不写库。
 */
@Service
public class DbSourceIndex {

    /** 一处定义：所在仓库 + 相对文件 + 类型 + 起始行 + 那一段源码 */
    public record Def(String repoName, String file, String kind, String name, int line, String block) {}

    private static final List<String> EXTS = List.of("prc", "fnc", "bdy", "spc", "pck", "sql", "vw", "trg");
    private static final Charset GBK = Charset.forName("GBK");
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** 整仓只列这么多文件：老工程的 DB 仓库有上万个对象文件，扫不完时界面上要照实说 */
    private static final int MAX_LISTED_FILES = 20_000;
    private static final int MAX_BLOCK_LINES = 4000;

    private static final Pattern DEF = Pattern.compile(
            "(?im)\\bcreate\\s+(?:or\\s+replace\\s+)?(?:editionable\\s+)?(?:force\\s+|noschema\\s+)*"
                    + "(procedure|function|package\\s+body|package|trigger|type\\s+body)\\s+"
                    + "[\\w$.\"']*?\\.?\"?([\\w$]{2,80})\"?\\s*[\\(\\s]");
    /** 包体 / 整库导出里的成员过程：行首 PROCEDURE name( */
    private static final Pattern MEMBER = Pattern.compile(
            "(?im)^\\s*(procedure|function)\\s+[\\w$.\"']*?\\.?\"?([\\w$]{2,80})\"?\\s*[\\(\\s;]");

    private final JdbcTemplate jdbc;
    private final Map<Long, RepoFiles> byRepo = lru(8);
    private final Map<String, String> texts = lru(48);

    public DbSourceIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 找到就返回定义段；找不到返回 null，由上层照实说「盘上没有这份过程代码」 */
    public Def find(long projectId, String ref) {
        String name = procName(ref), pkg = pkgName(ref);
        if (name == null) return null;
        List<Def> found = new ArrayList<>();
        for (Map<String, Object> r : repos(projectId)) {
            RepoFiles rf = files(((Number) r.get("id")).longValue(),
                    String.valueOf(r.get("local_path")), String.valueOf(r.get("name")));
            if (rf == null) continue;
            List<Path> cands = new ArrayList<>();
            if (!pkg.isEmpty()) cands.addAll(rf.byName.getOrDefault(pkg, List.of()));
            cands.addAll(rf.byName.getOrDefault(name, List.of()));
            for (Path p : cands) {
                Def d = defIn(rf, p, name);
                if (d != null) found.add(d);
            }
            if (!found.isEmpty()) return best(found, pkg);
            for (List<Path> ps : rf.byExt.values()) {
                for (Path p : ps) {
                    Def d = defIn(rf, p, name);
                    if (d != null) found.add(d);
                    if (found.size() >= 4) return best(found, pkg);
                }
            }
        }
        return found.isEmpty() ? null : best(found, pkg);
    }

    /** 扫过但没建过索引的仓库数——界面上「盘上没有」和「还没扫到」是两件事 */
    public boolean capped(long projectId) {
        for (Map<String, Object> r : repos(projectId)) {
            RepoFiles rf = byRepo.get(((Number) r.get("id")).longValue());
            if (rf != null && rf.capped) return true;
        }
        return false;
    }

    /**
     * 反向（第卅五轮）：某个改动文件里定义了哪些过程 / 函数 / 包名。
     * 改动流问的是「改了 {@code up_mid_28102044.prc} 影响哪些功能号」，先得知道这个文件里定义了谁——
     * 包体与整库导出里一个文件定义几十个过程是常态，只按文件名认会漏掉绝大部分。
     *
     * <p>只受理 SQL 源码后缀：改动流一次提交可能有几百个 java/js 文件，逐个去比对两万个索引路径是白工。
     */
    public List<String> namesDefinedIn(long projectId, String fileTail) {
        List<String> out = new ArrayList<>();
        if (fileTail == null || fileTail.isBlank() || !isDbSource(fileTail)) return out;
        for (Map<String, Object> r : repos(projectId)) {
            RepoFiles rf = files(((Number) r.get("id")).longValue(),
                    String.valueOf(r.get("local_path")), String.valueOf(r.get("name")));
            if (rf == null) continue;
            for (List<Path> ps : rf.byName.values()) {
                for (Path p : ps) {
                    if (!com.preflight.common.FileKeys.tail(p.toString()).equals(fileTail)) continue;
                    String text = text(p);
                    if (text == null) continue;
                    for (Mark m : marks(text)) if (!out.contains(m.name)) out.add(m.name);
                }
            }
        }
        return out;
    }

    private static String procName(String ref) {
        if (ref == null || ref.isBlank()) return null;
        String plain = ref.replace("\"", "");
        String name = plain.contains(".") ? plain.substring(plain.lastIndexOf('.') + 1) : plain;
        name = name.toLowerCase(Locale.ROOT);
        return name.matches("[a-z0-9_$.]{2,80}") ? name : null;
    }

    private static String pkgName(String ref) {
        if (ref == null) return "";
        String plain = ref.replace("\"", "");
        if (!plain.contains(".")) return "";
        return plain.substring(0, plain.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    }

    /** 这个路径是不是库对象源码文件（按扩展名，与建索引同一份白名单） */
    public static boolean isDbSource(String path) {
        if (path == null) return false;
        String s = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        int dot = s.lastIndexOf('.');
        return dot >= 0 && EXTS.contains(s.substring(dot + 1));
    }

    /** 同名的多处定义里，优先给包名 / 文件名对得上的那个（老库里过程重名是真实存在的） */
    private Def best(List<Def> found, String pkg) {
        if (pkg.isEmpty()) return found.get(0);
        for (Def d : found) {
            String base = d.file().substring(d.file().lastIndexOf('/') + 1);
            if (base.toLowerCase(Locale.ROOT).startsWith(pkg)) return d;
        }
        return found.get(0);
    }

    /** 在一个文件里找 name 的定义；命中就返回从本定义到下一个定义（或文件尾）的那一段 */
    private Def defIn(RepoFiles rf, Path file, String name) {
        String text = text(file);
        if (text == null) return null;
        List<Mark> marks = marks(text);
        if (marks.isEmpty()) return null;
        for (int i = 0; i < marks.size(); i++) {
            if (!marks.get(i).name.equals(name)) continue;
            int start = marks.get(i).at;
            int end = i + 1 < marks.size() ? marks.get(i + 1).at : text.length();
            int line = (int) text.substring(0, start).chars().filter(c -> c == '\n').count() + 1;
            return new Def(rf.repoName, rf.rel(file), marks.get(i).kind, name, line, cut(text, start, end));
        }
        return null;
    }

    private record Mark(int at, String kind, String name) {}

    private static List<Mark> marks(String text) {
        List<Mark> out = new ArrayList<>();
        add(out, DEF, text);
        add(out, MEMBER, text);
        out.sort(Comparator.comparingInt(m -> m.at));
        return out;
    }

    private static void add(List<Mark> out, Pattern p, String text) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(new Mark(m.start(), m.group(1).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT),
                    m.group(2).toLowerCase(Locale.ROOT)));
        }
    }

    private static String cut(String text, int from, int to) {
        int nl = 0;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == '\n' && ++nl > MAX_BLOCK_LINES) return text.substring(from, i);
        }
        return text.substring(from, Math.min(to, text.length()));
    }

    // ---------- 仓库文件清单与文本缓存 ----------

    private static class RepoFiles {
        String repoName;
        Path root;
        final Map<String, List<Path>> byName = new LinkedHashMap<>();
        final Map<String, List<Path>> byExt = new LinkedHashMap<>();
        int listed;
        boolean capped;

        String rel(Path p) {
            try {
                return root.relativize(p).toString().replace('\\', '/');
            } catch (Exception e) {
                return p.getFileName() == null ? "?" : p.getFileName().toString();
            }
        }
    }

    /** 测试缝：驱动脚本可以覆盖这份清单，生产走 repos 表 */
    protected List<Map<String, Object>> repos(long projectId) {
        try {
            return jdbc.queryForList("SELECT id, name, local_path FROM repos WHERE project_id=? ORDER BY id", projectId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private RepoFiles files(long repoId, String localPath, String repoName) {
        Path root = Path.of(localPath);
        RepoFiles cached = byRepo.get(repoId);
        if (cached != null && cached.root.equals(root)) return cached;
        if (!Files.isDirectory(root)) return null;
        RepoFiles rf = new RepoFiles();
        rf.repoName = repoName;
        rf.root = root;
        try (var w = Files.walk(root)) {
            for (Path p : w.toList()) {
                String s = p.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
                if (s.contains("/.git/") || s.contains("/node_modules/")) continue;
                if (!Files.isRegularFile(p)) continue;
                int dot = s.lastIndexOf('.');
                if (dot < 0 || !EXTS.contains(s.substring(dot + 1))) continue;
                if (++rf.listed > MAX_LISTED_FILES) {
                    rf.capped = true;
                    break;
                }
                String base = s.substring(s.lastIndexOf('/') + 1, dot);
                rf.byExt.computeIfAbsent(s.substring(dot + 1), k -> new ArrayList<>()).add(p);
                rf.byName.computeIfAbsent(base, k -> new ArrayList<>()).add(p);
            }
        } catch (Exception e) {
            return null;
        }
        byRepo.put(repoId, rf);
        return rf;
    }

    /** 工作副本里 SQL 源码以 GBK 为主、少数 UTF-8：先按 UTF-8 严格解，失败退 GBK */
    private String text(Path p) {
        String key;
        try {
            long size = Files.size(p);
            if (size > MAX_FILE_BYTES) return null;
            key = p + "|" + size + "|" + Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return null;
        }
        String hit = texts.get(key);
        if (hit != null) return hit;
        try {
            byte[] bytes = Files.readAllBytes(p);
            String text;
            try {
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
                text = new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception notUtf8) {
                text = new String(bytes, GBK);
            }
            texts.put(key, text);
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    private static <V, K> Map<K, V> lru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> e) {
                return size() > max;
            }
        });
    }
}
