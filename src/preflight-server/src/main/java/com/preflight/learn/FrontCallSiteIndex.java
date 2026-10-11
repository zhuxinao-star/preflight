package com.preflight.learn;

import com.preflight.common.FileTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前端调用点索引（第五十轮）：<b>「这张卡在对外是怎么被调的」</b>这一条事实的唯一读法。
 *
 * <p>第卅五、四十二、四十九轮都各自在前端里找过地址：固定入参按调用点证据、登录那一步按调用点补前缀。
 * 每一次都只收<b>登录语义</b>那几个文件（{@link FrontLoginChainService} 里那道 loginMatter 门槛），
 * 所以国泰海通 261 张卡里，前端明明写着 {@code /ni-quality/…}、{@code /cc-app/…} 的那 1400 多处调用点
 * 一条也没被用上——卡片学的是<b>模块内部</b>的 Controller 路径，发出去却是打到 nginx，
 * nginx 上没有 {@code /qc/**} 这个 location，直接回 405（页面看到的就是那条 HTML）。
 *
 * <p>这一条不再按"像不像登录"筛文件，收<b>所有</b>调用点，并提供两件通用的事：
 * <ol>
 *   <li>后缀实证：卡片路径 {@code /qc/see/workOrder/push} 若在前端写成 {@code /ni-quality/qc/…}，
 *       就多出来的那一段带文件与行号原样交出去；</li>
 *   <li>整套部署前缀：各个 axios 实例自己写的 {@code baseURL}（含 {@code prefix + '/x'} 这种
 *       运行时拼出来的形状）收成一份清单，供推不出实证的卡按服务名补。</li>
 * </ol>
 *
 * <p>{@link FrontLoginChainService} 用的那三条正则与 {@link #joinPrefix} 也搬到这里来了：
 * "怎么读一个前端调用点"只许有一处定义，否则两条通路会对同一个文件读出两个地址。
 * 登录那一条的行为（门槛、上限、并链）一字未改，第卅九轮的 16 条线索是回归判据。
 */
@Service
public class FrontCallSiteIndex {

    private static final Logger log = LoggerFactory.getLogger(FrontCallSiteIndex.class);

    // ---- 共用形状：调用点、url 属性、要配的 baseURL 前缀 ------------------------------------
    public static final Pattern VERB_CALL = Pattern.compile(
            "\\.(get|post|put|delete|patch|head)\\s*\\(\\s*(?:[\\w$.]+\\s*\\+\\s*)?['\"`](/[^'\"`?#\\s]{3,180})['\"`]");
    public static final Pattern URL_PROP = Pattern.compile(
            "\\burl\\s*[:=]\\s*(?:[\\w$.]+\\s*\\+\\s*)?['\"`](/[^'\"`?#\\s]{3,180})['\"`]");
    /**
     * axios 实例的 baseURL。{@code (?:[\\w$.]+\\s*\\+\\s*)?} 那一段是必须的：
     * 真实工程写成 {@code baseURL: prefix + '/ni-gateway'}，prefix 是运行时
     * {@code window.config.appKey} 那种外部值——字面量那一段才是地址的一部分，
     * 前面挂什么变量名不影响"这一层实例带 /ni-gateway"这个事实。
     */
    public static final Pattern BASE_URL = Pattern.compile(
            "\\bbaseURL\\s*[:=]\\s*(?:[\\w$.]+\\s*\\+\\s*)?['\"`](/[^'\"`?#\\s]{1,120})['\"`]");

    public static final Set<String> PRUNE = Set.of(
            "node_modules", ".git", ".svn", "dist", "build", "target", "out", "coverage", ".idea",
            ".vscode", "vendor", "web_modules", ".next", ".nuxt", "applets", "webjars");
    public static final Set<String> EXT = Set.of(".js", ".ts", ".mts", ".cts", ".jsx", ".tsx", ".vue");

    private static final int WALK_DEPTH = 12;
    private static final int MAX_FILES = 4000;
    private static final long MAX_FILE_BYTES = 600_000L;
    private static final double MINIFIED_AVG_LINE = 300d;
    /** 收下的调用点总数上限（比登录那一条宽得多：这里要覆盖整项目的卡） */
    private static final int MAX_SITES = 40_000;

    /** 前缀拼接不许造出双斜杠，也不许把已经带前缀的路径再叠一遍 */
    public static String joinPrefix(String base, String path) {
        String b = base.replaceAll("/+$", "");
        String p = path.startsWith("/") ? path : "/" + path;
        return p.startsWith(b + "/") ? p : b + p;
    }

    public static boolean wanted(Path root, Path p) {
        for (Path seg : root.relativize(p)) {
            if (PRUNE.contains(seg.toString().toLowerCase(Locale.ROOT))) return false;
        }
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".min.js") || name.endsWith(".min.ts") || name.endsWith(".d.ts")) return false;
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXT.contains(name.substring(dot));
    }

    /** 证据上摆成"从 src/ 起"的样子：整条盘路径既长又没信息量 */
    public static String shownAs(Path f) {
        String s = f.toString().replace('\\', '/');
        int src = s.indexOf("/src/");
        if (src >= 0) return s.substring(src + 1);
        int idx = s.lastIndexOf('/');
        return idx >= 0 ? s.substring(idx + 1) : s;
    }

    public static String lastSegment(String rel) {
        String s = rel;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    // ---------------------------------------------------------------- 索引出口

    /** 一处前端调用点：内部写的 path ＋ 真发出去的 absPath（带这一层实例的 baseURL） */
    public static class Site {
        public final String path;
        public final String absPath;
        public final String base;
        public final String file;
        public final int line;

        Site(String path, String absPath, String base, String file, int line) {
            this.path = path;
            this.absPath = absPath;
            this.base = base;
            this.file = file;
            this.line = line;
        }

        public String evidence() {
            return file + ":" + line;
        }
    }

    /** 一个项目的全部前端调用点＋读到的 baseURL 清单 */
    public static class Index {
        public final List<Site> sites = new ArrayList<>();
        /** 这一趟真读到的部署前缀（各 axios 实例的 baseURL），键到出现的文件，便于点名依据 */
        public final Map<String, String> bases = new LinkedHashMap<>();
        /** 末段 → 调用点（后缀匹配用，避免每个问题全表扫） */
        final Map<String, List<Site>> byTail = new LinkedHashMap<>();
        public final Map<String, Integer> repoFiles = new LinkedHashMap<>();
        public final List<String> unreadable = new ArrayList<>();
        public int filesScanned;
        public boolean filesCapped;
        public int sitesCapped;

        void add(Site s) {
            if (sites.size() >= MAX_SITES) {
                sitesCapped++;
                return;
            }
            sites.add(s);
            byTail.computeIfAbsent(tailOf(trim(s.absPath)), k -> new ArrayList<>()).add(s);
        }

        /** 卡片路径的对外写法：谁以 "… ＋ 卡片路径" 结尾，且确实<b>多出来</b>一段前缀 */
        public List<Site> calledAs(String cardPath) {
            List<Site> out = new ArrayList<>();
            if (cardPath == null || cardPath.isBlank()) return out;
            String cp = trim(cardPath);
            if (!cp.startsWith("/")) cp = "/" + cp;
            for (Site s : byTail.getOrDefault(tailOf(cp), List.of())) {
                String ap = trim(s.absPath);
                // 卡片路径自带前导 /，endsWith 天然对齐到段首（再要求"前一个字符也是 /"就变成要双斜杠，永不命中）
                if (ap.length() > cp.length() && ap.endsWith(cp)) out.add(s);
            }
            return out;
        }

        public int siteCount() {
            return sites.size();
        }

        public String basisNote() {
            StringBuilder sb = new StringBuilder("扫了 ").append(repoFiles.size()).append(" 个前端仓里 ")
                    .append(sum(repoFiles)).append(" 个脚本文件，读到 ").append(sites.size()).append(" 处调用点");
            if (filesCapped) sb.append("（候选文件到上限 ").append(MAX_FILES).append(" 就停了，后面的没看）");
            if (sitesCapped > 0) sb.append("（另有 ").append(sitesCapped).append(" 处超出收下的上限，没算）");
            if (!bases.isEmpty()) sb.append("，读到的部署前缀：").append(String.join("、", bases.keySet()));
            if (!unreadable.isEmpty()) sb.append("；读不到的仓：").append(String.join("、", unreadable));
            return sb.toString();
        }

        private static int sum(Map<String, Integer> m) {
            int n = 0;
            for (int v : m.values()) n += v;
            return n;
        }
    }

    private static String tailOf(String path) {
        String p = trim(path);
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** 去掉尾部斜杠与查询段，比较用；不改原始值（发出去的还得是原始的那个） */
    private static String trim(String p) {
        String s = p;
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        return s.replaceAll("/+$", "");
    }

    // ---------------------------------------------------------------- 扫描

    private static final long TTL_MS = 10 * 60 * 1000L;
    private final Map<String, Cache> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static class Cache {
        final Index index;
        final long at;
        Cache(Index i, long at) { index = i; this.at = at; }
    }

    /**
     * 按登记的目录扫一遍；同样的目录串在 TTL 内共用一份（同一项目的几百张卡不能各扫各的盘）。
     */
    public Index scan(List<String> roots) {
        String key = roots == null ? "" : String.join("|", roots);
        Cache c = CACHE.get(key);
        if (c != null && System.currentTimeMillis() - c.at < TTL_MS) return c.index;
        Index idx = walk(roots);
        CACHE.put(key, new Cache(idx, System.currentTimeMillis()));
        return idx;
    }

    public void invalidate() {
        CACHE.clear();
    }

    private Index walk(List<String> roots) {
        Index idx = new Index();
        if (roots == null) return idx;
        Set<Path> files = new LinkedHashSet<>();
        for (String dir : roots) {
            if (dir == null || dir.isBlank()) continue;
            Path root = Path.of(dir).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                idx.unreadable.add(root.getFileName().toString());
                continue;
            }
            int before = files.size();
            try (var stream = Files.walk(root, WALK_DEPTH)) {
                stream.filter(Files::isRegularFile).filter(p -> wanted(root, p)).sorted().forEach(p -> {
                    if (files.size() < MAX_FILES) files.add(p); else idx.filesCapped = true;
                });
            } catch (IOException e) {
                log.debug("调用点索引跳过 {}: {}", root, e.toString());
                idx.unreadable.add(root.getFileName().toString());
            }
            idx.repoFiles.put(root.getFileName().toString(), files.size() - before);
        }
        // 第一趟：哪个文件自己写着 baseURL（模块名 → 前缀），给"从别处 import 来的封装"用
        Map<String, String> moduleBase = new LinkedHashMap<>();
        List<String> texts = new ArrayList<>();
        List<String> rels = new ArrayList<>();
        for (Path f : files) {
            String text = read(f);
            texts.add(text);
            rels.add(text == null ? "" : shownAs(f));
            if (text == null) continue;
            Matcher b = BASE_URL.matcher(text);
            if (b.find()) {
                moduleBase.putIfAbsent(lastSegment(shownAs(f)), b.group(1));
                idx.bases.putIfAbsent(b.group(1), shownAs(f));
            }
        }
        // 第二趟：收调用点（每个文件读到的原文只用这一趟，不留存）
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null) continue;
            String rel = rels.get(i);
            Matcher b = BASE_URL.matcher(text);
            String base = b.find() ? b.group(1) : importedBase(text, moduleBase);
            collect(text, rel, base, idx);
        }
        idx.filesScanned = files.size();
        return idx;
    }

    private static String read(Path f) {
        try {
            if (Files.size(f) > MAX_FILE_BYTES) return null;
            String text = FileTexts.decode(Files.readAllBytes(f)).text();
            if (text == null || text.isBlank()) return null;
            String[] lines = FileTexts.lines(text);
            if (lines.length == 0 || (double) text.length() / lines.length > MINIFIED_AVG_LINE) return null;
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    private static void collect(String text, String rel, String base, Index idx) {
        String[] lines = FileTexts.lines(text);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 注释掉的调用点不算实证：那是"曾经这么调"，不是"现在这么调"
            if (isCommentLine(line)) continue;
            Matcher m = VERB_CALL.matcher(line);
            while (m.find()) add(idx, rel, base, m.group(2), i + 1);
            m = URL_PROP.matcher(line);
            while (m.find()) add(idx, rel, base, m.group(1), i + 1);
        }
    }

    /** 只看行首的注释标记：压缩过的 js 整份就一行，行中有注释不能整行丢 */
    private static boolean isCommentLine(String line) {
        String t = line.stripLeading();
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*");
    }

    private static void add(Index idx, String rel, String base, String path, int line) {
        if (path == null || !path.startsWith("/")) return;
        String abs = base == null || base.isEmpty() || path.startsWith(base + "/")
                ? path : joinPrefix(base, path);
        idx.add(new Site(path, abs, base == null ? "" : base, rel, line));
    }

    /** import 回来的请求封装（{@code import service from '@/utils/service'}）带着它自己的 baseURL */
    private static String importedBase(String text, Map<String, String> moduleBase) {
        Matcher imp = IMPORT_FROM.matcher(text);
        while (imp.find()) {
            String base = moduleBase.get(imp.group(1));
            if (base != null) return base;
        }
        return "";
    }

    private static final Pattern IMPORT_FROM = Pattern.compile("\\bfrom\\s+['\"][^'\"]*?/([\\w.-]{2,40})['\"]");
}
