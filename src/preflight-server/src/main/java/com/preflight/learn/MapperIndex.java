package com.preflight.learn;

import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 * 一个仓库工作副本的「代码侧接口事实」索引（第卅五轮，第四十二轮扩到 Maven 布局）：
 * bexConf 的 {@code attr1}、serviceConf 的业务码、atomConf 的实现类，以及 mybatis 目录里的语句原文
 * ——老工程放在 {@code WEB-INF/classes/mybatis}，Spring 工程放在 {@code src/main/resources/mybatis}，
 * 两边都扫（{@code target/} 里那份是编译副本，扫它就会拿旧语句讲新代码）。
 *
 * <p>这些凑在一起才是真正的出入参来源：{@code bex_code → attr1} 指到某条 MyBatis 语句，
 * 语句里的列名与占位符就是出参与入参；{@code service_code → service_business} 再落到 bex 上。
 * Spring 那条路是 {@code DAO 接口全限定名 = mapper namespace}、{@code 方法名 = 语句 id}，
 * 所以这里连 {@code namespace} 与 {@code <resultMap>}（列→属性、带 XML 注释里的中文）一起记下来。
 * 学习期建的字典里没有 attr1（它只收 {@code <param>}），所以这份索引单独现读。
 *
 * <p>按仓库根缓存五分钟：一次预览要读几百个 XML，不缓存会把面板拖到秒级；缓存过期就重读盘，
 * 用户改了工作副本最迟五分钟后能看到新内容。
 */
@Component
public class MapperIndex {

    /**
     * 一条 MyBatis 语句：tag + 属性原文 + 内部原文（动态标签与 CDATA 都保留，占位符要从这里认）。
     * {@code namespace} 是所在 mapper 文件头上的那个键——Maven 布局的仓（Spring ＋ DAO 接口）里，
     * 「namespace.方法名」才是这条语句的真名，只按 id 找会撞上别的文件里的同名语句。
     */
    public record Stmt(String id, String tag, String attrs, String body, String file, String namespace) {}

    /**
     * 一个 {@code <resultMap>}：列 → 属性。老工程的 VO 字段没有中文注释，出参的键名倒是全在这里，
     * 而且 {@code type} 指向的那个类能顺着 Java 源码索引继续展开（第四十二轮）。
     */
    public record ResultMap(String id, String type, List<String[]> cols, String comment,
                            String namespace, String file, int line) {}

    /**
     * 一段 {@code <sql id="…">} 公共列清单：Spring 工程的语句多数写成
     * {@code select <include refid="xxBaseColumnList"/> from …}，不内联就认不出列，
     * 出参会被误报成「一条都没有」——所以片段和语句一起记。
     */
    public record SqlFrag(String id, String body, String namespace, String file) {}

    /** bexConf 里的一个 bex：attr1 指向语句 id，params 是它自己声明的入参 */
    public record Bex(String code, String name, String attr1, String pageMode, String bexType,
                      List<Param> params, String file) {}

    public record Param(String code, String label, String dataType, String def, String file) {}

    public record Service(String code, String name, List<String> business, String file) {}

    public record Atom(String code, String cls, String method, String file) {}

    public static class Repo {
        public Path root;
        public String name;
        public final Map<String, List<Stmt>> stmts = new LinkedHashMap<>();
        public final Map<String, Bex> bex = new LinkedHashMap<>();
        public final Map<String, Service> services = new LinkedHashMap<>();
        public final Map<String, List<Atom>> atoms = new LinkedHashMap<>();
        /** 反向①：文件尾键 → 这个 mybatis 文件里的语句（改动流按文件问「牵动哪些码」） */
        public final Map<String, List<Stmt>> stmtsByFile = new LinkedHashMap<>();
        /** 「namespace.id」（小写）→ 语句：Maven 布局的仓只能按这个全名找，光按 id 会撞同名 */
        public final Map<String, List<Stmt>> stmtsByNs = new LinkedHashMap<>();
        /** 「namespace.resultMap 名」（小写）→ resultMap 定义 */
        public final Map<String, ResultMap> resultMaps = new LinkedHashMap<>();
        /** {@code <sql id>} 片段：键同时登记「namespace.id」与裸 id，因为 refid 两种写法都有 */
        public final Map<String, List<SqlFrag>> sqlFrags = new LinkedHashMap<>();
        /** 反向②：语句 id → 用它的 bex 码（attr1 指过来的，或同名） */
        public final Map<String, Set<String>> codesByStmtId = new LinkedHashMap<>();
        /** 反向③：过程名 / 包名（小写）→ 调用它的 bex 码 */
        public final Map<String, Set<String>> codesByProc = new LinkedHashMap<>();
        /** 反向④：bex 码 → 业务码里含它的 service 码 */
        public final Map<String, Set<String>> servicesOfBex = new LinkedHashMap<>();
        /** 盘上「没有」或「空着」的那几类目录：hasDir 靠这份清单反证，别当普通日志用 */
        public final List<String> dirs = new ArrayList<>();
        public long builtAt;
    }

    private static final Charset GBK = Charset.forName("GBK");
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final long TTL_MS = 5 * 60_000L;
    private static final int MAX_FILES_PER_DIR = 4000;

    private static final Pattern STMT = Pattern.compile(
            "(?is)<(select|insert|update|delete)\\b([^>]*)\\bid\\s*=\\s*[\"']([^\"']+)[\"']([^>]*)>(.*?)</\\1>");
    /** mapper 文件头上的 namespace＝DAO 接口的全限定名（Spring 工程就这么对应） */
    private static final Pattern MAPPER_NS = Pattern.compile(
            "(?is)<mapper\\b[^>]*\\bnamespace\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern RESULT_MAP = Pattern.compile("(?is)<resultMap\\b([^>]*?)>(.*?)</resultMap>");
    private static final Pattern RESULT_COL = Pattern.compile("(?is)<(id|result)\\b([^>]*?)/?>");
    /** 公共列清单片段（{@code <sql id="…">}），给 {@code <include refid="…">} 内联用 */
    private static final Pattern SQL_FRAG = Pattern.compile(
            "(?is)<sql\\b([^>]*?)\\bid\\s*=\\s*[\"']([^\"']+)[\"']([^>]*)>(.*?)</sql>");
    /** 语句体里的引用：自闭合、带 {@code <property>} 的成对写法、以及裸开标签三种都要吃掉 */
    private static final Pattern SQL_INCLUDE = Pattern.compile(
            "(?is)<include\\b[^>]*?\\brefid\\s*=\\s*[\"']([^\"']+)[\"']\\s*(?:/>|>(?:.*?)</include>|>)");

    private static final Pattern BEX = Pattern.compile("(?is)<bex\\b([^>]*?)/?>(.*?)(?=<bex\\b|</bexConf>|\\z)");
    private static final Pattern SVC = Pattern.compile("(?is)<service\\b([^>]*?)>(.*?)(?=</service>|<service\\b|\\z)");
    private static final Pattern ATOM = Pattern.compile("(?is)<atom\\b([^>]*?)/?>");
    private static final Pattern PARAM = Pattern.compile("(?is)<param\\b([^>]*?)/?>");
    private static final Pattern BUSI = Pattern.compile("(?is)<service_business\\b([^>]*?)/?>");
    private static final Pattern ATTR = Pattern.compile("(?i)\\b([a-z_][a-z0-9_]*)\\s*=\\s*\"([^\"]*)\"");

    private final Map<String, Repo> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Repo>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Repo> e) {
                    return size() > 8;
                }
            });

    public Repo forRepo(Path root, String repoName) {
        if (root == null || !Files.isDirectory(root)) return null;
        String key = root.toString();
        Repo hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.builtAt < TTL_MS) return hit;
        Repo r = build(root, repoName);
        cache.put(key, r);
        return r;
    }

    /** 该仓库里 id 命中的语句（语句 id 在不同文件重名时全给，上层按出现顺序用第一个并说明） */
    public List<Stmt> statements(Repo r, String id) {
        if (r == null || id == null) return List.of();
        return r.stmts.getOrDefault(id.toLowerCase(Locale.ROOT), List.of());
    }

    /** 按「namespace + 语句 id」找语句：Spring/DAO 那套就是这么对应的，光按 id 会撞上别的文件里的同名语句 */
    public List<Stmt> statements(Repo r, String namespace, String id) {
        if (r == null || id == null || id.isBlank()) return List.of();
        List<Stmt> hit = r.stmtsByNs.get(nsKey(namespace, id));
        if (hit != null) return hit;
        // namespace 常带在调用处写的简名（POJO 型 mapper 的 namespace 就是个短名），或只给了全名的一段
        if (namespace != null && !namespace.isBlank()) {
            String plain = namespace.substring(namespace.lastIndexOf('.') + 1);
            hit = r.stmtsByNs.get(nsKey(plain, id));
            if (hit != null) return hit;
        }
        return List.of();
    }

    /** resultMap 定义（键同上，允许「短名.名」）；找不到返回 null，由上层说清是哪一种 */
    public ResultMap resultMap(Repo r, String namespace, String id) {
        if (r == null || id == null || id.isBlank()) return null;
        ResultMap hit = r.resultMaps.get(nsKey(namespace, id));
        if (hit == null && namespace != null && !namespace.isBlank()) {
            hit = r.resultMaps.get(nsKey(namespace.substring(namespace.lastIndexOf('.') + 1), id));
        }
        return hit;
    }

    /** 这个仓库里有没有任何 MyBatis 语句（Maven 布局与 WEB-INF 两种都算）——决定「按实现推」这条路要不要试 */
    public boolean hasStatements(Repo r) {
        return r != null && !r.stmts.isEmpty();
    }

    /** 语句属性上的一个键（{@code resultType} / {@code parameterType} / {@code resultMap} 等） */
    public static String attrOf(Stmt s, String name) {
        return s == null ? null : attr(s.attrs(), name);
    }

    /** 返回类型／resultMap 指向的类名（MyBatis 的 {@code resultType} 可以是别名，别名解析在调用方按类索引做） */
    public String resultType(Stmt s) {
        return attrOf(s, "resultType");
    }

    /**
     * 把语句体里的 {@code <include refid="…"/>} 换成片段原文，让列名能在语句里被认出来。
     *
     * <p>为什么必须在建索引这一层做：Spring 工程的 {@code select} 有相当一部分写成
     * {@code select <include refid="xxBaseColumnList"/> from …}，光看语句体一个列名都没有，
     * 按实现推就会假报「这个接口没有出参」。认不到的 refid 不静默吞掉，原样留着并由
     * {@code missing} 告诉调用方是哪一个——它决定了界面上那句「列名在别的文件里，没找到」。
     *
     * @param missing 出参：没解析到的 refid（可为 null 表示不关心）
     */
    public String expanded(Repo r, Stmt s, List<String> missing) {
        if (r == null || s == null) return s == null ? "" : s.body();
        return expand(r, s.body(), s.namespace(), missing, 0);
    }

    private String expand(Repo r, String body, String ns, List<String> missing, int depth) {
        if (body == null || !body.contains("<include") || depth >= 3) return body;
        Matcher m = SQL_INCLUDE.matcher(body);
        StringBuilder sb = new StringBuilder();
        int hit = 0;
        while (m.find()) {
            String ref = m.group(1).trim();
            SqlFrag f = frag(r, ns, ref);
            m.appendReplacement(sb, Matcher.quoteReplacement(f == null ? m.group() : f.body()));
            if (f == null) {
                hit++;
                if (missing != null && !missing.contains(ref)) missing.add(ref);
            }
        }
        m.appendTail(sb);
        if (hit == 0 && depth == 0) return sb.toString();
        return expand(r, sb.toString(), ns, missing, depth + 1);
    }

    /** refid 可能是「全限定名.片段 id」，也可能就是本文件里的短名 */
    private SqlFrag frag(Repo r, String ns, String ref) {
        List<SqlFrag> direct = r.sqlFrags.get(ref.toLowerCase(Locale.ROOT));
        if (direct != null && !direct.isEmpty()) return direct.get(0);
        if (ns != null && !ns.isEmpty()) {
            List<SqlFrag> scoped = r.sqlFrags.get(nsKey(ns, ref));
            if (scoped != null && !scoped.isEmpty()) return scoped.get(0);
        }
        String plain = ref.substring(ref.lastIndexOf('.') + 1);
        List<SqlFrag> tail = ref.equalsIgnoreCase(plain) ? null : r.sqlFrags.get(plain.toLowerCase(Locale.ROOT));
        return tail == null || tail.isEmpty() ? null : tail.get(0);
    }

    public Bex bex(Repo r, String code) {
        return r == null ? null : r.bex.get(code.toLowerCase(Locale.ROOT));
    }

    public Service service(Repo r, String code) {
        return r == null ? null : r.services.get(code.toLowerCase(Locale.ROOT));
    }

    public List<Atom> atoms(Repo r, String code) {
        return r == null ? List.of() : r.atoms.getOrDefault(code.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * 这一族目录在该仓库的工作副本里到底有没有（空目录按没有算）。
     * 只用来决定「查不到」要不要报出来：DB 仓库根本没有 metaData/mybatis 目录，
     * 对它报「bexConf 里没有这个码」是假消息。
     */
    public boolean hasDir(Repo r, String name) {
        return r != null && r.dirs.stream().noneMatch(d -> d.contains(name));
    }

    /** 反向①：改动落在某个文件上，这个文件里有哪些 MyBatis 语句（键＝{@code FileKeys.tail} 归一的「目录/文件名」） */
    public List<Stmt> stmtsInFile(Repo r, String fileKey) {
        if (r == null || fileKey == null) return List.of();
        return r.stmtsByFile.getOrDefault(fileKey, List.of());
    }

    /** 反向②：语句 id → 用到它的 bex 码 */
    public Set<String> codesOfStmt(Repo r, String stmtId) {
        if (r == null || stmtId == null) return Set.of();
        return r.codesByStmtId.getOrDefault(stmtId.toLowerCase(Locale.ROOT), Set.of());
    }

    /** 反向③：盘上某个过程 / 包被改动，哪些 bex 码的语句在调用它 */
    public Set<String> codesOfProc(Repo r, String procName) {
        if (r == null || procName == null) return Set.of();
        return r.codesByProc.getOrDefault(procName.toLowerCase(Locale.ROOT), Set.of());
    }

    /** 反向④：这个 bex 码挂在哪些 service 码的业务码下（回指时两种接口码都要列出来） */
    public Set<String> servicesOfBex(Repo r, String bexCode) {
        if (r == null || bexCode == null) return Set.of();
        return r.servicesOfBex.getOrDefault(bexCode.toLowerCase(Locale.ROOT), Set.of());
    }

    // ---------- 建索引 ----------

    private Repo build(Path root, String repoName) {
        Repo r = new Repo();
        r.root = root;
        r.name = repoName;
        r.builtAt = System.currentTimeMillis();
        Path meta = root.resolve("WEB-INF/conf/metaData");
        List<Path> dirs = new ArrayList<>(List.of(meta.resolve("bexConf"), meta.resolve("serviceConf"),
                meta.resolve("atomConf"), root.resolve("WEB-INF/classes/mybatis")));
        // Maven 布局的 Spring 仓（unifiedvisit 这类）把语句放在 src/main/resources/mybatis，
        // 而 target/classes/mybatis 是上一次构建留下的编译副本——扫到它就是拿旧语句讲新代码。
        dirs.addAll(mavenMybatis(root));
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                r.dirs.add(rel(root, dir) + "（盘上没有这个目录）");
                continue;
            }
            List<Path> xmls = xmls(dir);
            if (xmls.isEmpty()) r.dirs.add(rel(root, dir) + "（目录是空的）");
            for (Path p : xmls) {
                String text = read(p);
                if (text == null) continue;
                String reldir = rel(root, dir);
                if (reldir.contains("bexConf")) bexes(text, rel(root, p), r);
                else if (reldir.contains("serviceConf")) services(text, rel(root, p), r);
                else if (reldir.contains("atomConf")) atoms(text, rel(root, p), r);
                else statements(text, rel(root, p), r);
            }
        }
        link(r);
        return r;
    }

    /** 工作副本里所有 {@code …/src/main/resources/**}{@code /mybatis} 目录（最多两层模块，跳过 target） */
    private List<Path> mavenMybatis(Path root) {
        List<Path> out = new ArrayList<>();
        try (var w = Files.walk(root, 7)) {
            for (Path p : w.toList()) {
                if (!Files.isDirectory(p)) continue;
                String s = p.toString().replace('\\', '/');
                if (s.contains("/target/") || !s.endsWith("/mybatis") || !s.contains("/src/main/resources/")) continue;
                if (!out.contains(p)) out.add(p);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * 建反向索引：正向那条链（bex→attr1→语句→过程）在这里反过来记一遍，
     * 改动流才能拿"改了哪个文件"问出"影响哪些功能号"——不反过来就只能靠 viewConf 声明行，
     * 而老工程多数接口根本不写 viewConf。
     */
    private void link(Repo r) {
        for (List<Stmt> ss : r.stmts.values()) {
            for (Stmt one : ss) {
                r.stmtsByFile.computeIfAbsent(com.preflight.common.FileKeys.tail(one.file()),
                        k -> new ArrayList<>()).add(one);
            }
        }
        for (Bex b : r.bex.values()) {
            String key = b.code().toLowerCase(Locale.ROOT);
            List<Stmt> ss = stmtList(r, b.attr1());
            if (ss.isEmpty()) ss = stmtList(r, b.code());
            for (Stmt s : ss) {
                r.codesByStmtId.computeIfAbsent(s.id().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                        .add(b.code());
                String ref = com.preflight.parser.SqlColumns.callRef(s.body());
                if (ref == null) continue;
                String plain = ref.replace("\"", "");
                int dot = plain.lastIndexOf('.');
                add(r.codesByProc, (dot < 0 ? plain : plain.substring(dot + 1)).toLowerCase(Locale.ROOT), b.code());
                if (dot > 0) add(r.codesByProc, plain.substring(0, dot).toLowerCase(Locale.ROOT), b.code());
            }
            if (ss.isEmpty()) add(r.codesByProc, key, b.code());   // 没有 attr1 也没有语句：按码本身认一次
        }
        for (Service sv : r.services.values()) {
            for (String b : sv.business()) {
                r.servicesOfBex.computeIfAbsent(b.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                        .add(sv.code());
            }
        }
    }

    private static List<Stmt> stmtList(Repo r, String id) {
        return r == null || id == null || id.isBlank()
                ? List.of() : r.stmts.getOrDefault(id.toLowerCase(Locale.ROOT), List.of());
    }

    private static void add(Map<String, Set<String>> m, String key, String code) {
        if (key == null || key.isEmpty()) return;
        m.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(code);
    }

    private void statements(String text, String file, Repo r) {
        Matcher ns = MAPPER_NS.matcher(text);
        String nsId = ns.find() ? ns.group(1).trim() : "";
        Matcher fr = SQL_FRAG.matcher(text);
        while (fr.find()) {
            String id = fr.group(2).trim();
            if (id.isEmpty()) continue;
            SqlFrag frag = new SqlFrag(id, fr.group(4), nsId, file);
            // 裸 id 也登记一份：refid 写全名的能直接命中，只写短名的靠这条
            r.sqlFrags.computeIfAbsent(id.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(frag);
            if (!nsId.isEmpty()) {
                r.sqlFrags.computeIfAbsent(nsKey(nsId, id), k -> new ArrayList<>()).add(frag);
            }
        }
        Matcher rm = RESULT_MAP.matcher(text);
        while (rm.find()) {
            String id = attr(rm.group(1), "id");
            if (id == null || id.isBlank()) continue;
            List<String[]> cols = new ArrayList<>();
            Matcher rc = RESULT_COL.matcher(rm.group(2));
            while (rc.find()) {
                String col = attr(rc.group(2), "column"), prop = attr(rc.group(2), "property");
                if (col != null && prop != null) cols.add(new String[] {col, prop});
                if (cols.size() >= 200) break;
            }
            r.resultMaps.putIfAbsent(nsKey(nsId, id), new ResultMap(id, attr(rm.group(1), "type"), cols,
                    commentBefore(text, rm.start()), nsId, file, lineOf(text, rm.start())));
        }
        Matcher m = STMT.matcher(text);
        while (m.find()) {
            String id = m.group(3);
            Stmt s = new Stmt(id, m.group(1).toLowerCase(Locale.ROOT),
                    (m.group(2) + " " + m.group(4)).trim(), m.group(5), file, nsId);
            r.stmts.computeIfAbsent(id.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s);
            r.stmtsByNs.computeIfAbsent(nsKey(nsId, id), k -> new ArrayList<>()).add(s);
        }
    }

    private static String nsKey(String ns, String id) {
        return (ns == null ? "" : ns.toLowerCase(Locale.ROOT)) + "." + (id == null ? "" : id.toLowerCase(Locale.ROOT));
    }

    /** 定义前面最近那条 XML 注释＝老工程写中文说明的地方（{@code <!-- 问题变量对象 -->}）；隔太远的不算 */
    private static String commentBefore(String text, int at) {
        int close = text.lastIndexOf("-->", at);
        if (close < 0) return null;
        int open = text.lastIndexOf("<!--", close);
        if (open < 0 || at - close > 8) return null;   // 注释和定义之间只允许换行与缩进
        String c = text.substring(open + 4, close).replaceAll("\\s+", " ").trim();
        return c.isEmpty() ? null : (c.length() > 80 ? c.substring(0, 80) + "…" : c);
    }

    private static int lineOf(String text, int at) {
        int n = 1;
        for (int i = 0; i < at && i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
    }

    private void bexes(String text, String file, Repo r) {
        Matcher m = BEX.matcher(text);
        while (m.find()) {
            String code = attr(m.group(1), "bex_code");
            if (code == null || code.isBlank()) continue;
            List<Param> ps = new ArrayList<>();
            Matcher pm = PARAM.matcher(m.group(2));
            while (pm.find()) {
                String key = attr(pm.group(1), "para_code");
                if (key == null || key.isBlank()) continue;
                ps.add(new Param(key, attr(pm.group(1), "para_name"), attr(pm.group(1), "data_type"),
                        attr(pm.group(1), "def_val"), file));
            }
            r.bex.putIfAbsent(code.toLowerCase(Locale.ROOT), new Bex(code, attr(m.group(1), "bex_name"),
                    attr(m.group(1), "attr1"), attr(m.group(1), "page_mode"),
                    attr(m.group(1), "bex_type"), ps, file));
        }
    }

    private void services(String text, String file, Repo r) {
        Matcher m = SVC.matcher(text);
        while (m.find()) {
            String code = attr(m.group(1), "service_code");
            if (code == null || code.isBlank()) continue;
            List<String> busi = new ArrayList<>();
            Matcher bm = BUSI.matcher(m.group(2));
            while (bm.find()) {
                String b = attr(bm.group(1), "business_code");
                if (b != null && !b.isBlank()) busi.add(b);
            }
            r.services.putIfAbsent(code.toLowerCase(Locale.ROOT),
                    new Service(code, attr(m.group(1), "service_name"), busi, file));
        }
    }

    private void atoms(String text, String file, Repo r) {
        Matcher m = ATOM.matcher(text);
        while (m.find()) {
            String code = attr(m.group(1), "atom_code");
            String cls = attr(m.group(1), "atom_class");
            String ms = attr(m.group(1), "atom_method");
            if (code == null || cls == null || ms == null) continue;
            r.atoms.computeIfAbsent(code.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(new Atom(code, cls, ms, file));
        }
    }

    // ---------- 读盘小工具 ----------

    private List<Path> xmls(Path dir) {
        List<Path> out = new ArrayList<>();
        try (var w = Files.walk(dir)) {
            for (Path p : w.toList()) {
                if (out.size() >= MAX_FILES_PER_DIR) break;
                if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    out.add(p);
                }
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** UTF-8 严格解，失败退 GBK（与 ViewConfParser、KjdpDispatchAdapter 同一容错口径） */
    private String read(Path p) {
        try {
            if (Files.size(p) > MAX_FILE_BYTES) return null;
            byte[] bytes = Files.readAllBytes(p);
            try {
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception notUtf8) {
                return new String(bytes, GBK);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.getFileName() == null ? "." : p.getFileName().toString();
        }
    }

    private static String attr(String tagAttrs, String name) {
        Matcher m = ATTR.matcher(tagAttrs);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(name)) return m.group(2).trim();
        }
        return null;
    }
}
