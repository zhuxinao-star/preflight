package com.preflight.learn;

import com.preflight.parser.ViewConfParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接口出参 / 入参的读取期派生（第卅四轮起，第卅五轮改口径）。两件事：
 *
 * <p>① 按 service / bex 码给出**整个接口的出参字段清单**：主源是按真实代码推算的
 * {@link CodeChainService}（bexConf 的 attr1 → mapper 语句 → 盘上的存储过程源码），
 * 补源是老工程 {@code viewConf} 的 {@code <col_item field title>}（入参侧同一视图上的 {@code <qry_item>}）。
 * 纯声明的 viewConf 只覆盖一部分接口，所以它排在代码后面、只用来补中文名与漏网字段。读期现算、不加列不落库。
 *
 * <p>② 按一次提交给出**这次改动是否碰到出入参**：从已经在盘上的 diff 快照里逐行认字段声明，
 * 分新增 / 删除 / 就地改，再回指到该文件里声明这些字段的 service/bex 码。
 * 命中粒度一律如实标：能落到声明行的写 {@code decl}，只能到整文件的写 {@code file}，不猜。
 */
@Service
public class OutParamService {

    private static final Pattern P_COL = Pattern.compile("<col_item\\b[^>]*\\bfield\\s*=\\s*\"([^\"]{1,64})\"");
    private static final Pattern P_QRY = Pattern.compile("<qry_item\\b[^>]*\\bfield\\s*=\\s*\"([^\"]{1,64})\"");
    private static final Pattern P_PARAM = Pattern.compile("<param\\b[^>]*\\bpara_code\\s*=\\s*\"([^\"]{1,64})\"");
    private static final Pattern P_BIND = Pattern.compile("#\\{\\s*([A-Za-z_][A-Za-z0-9_.]{0,63})");
    private static final Pattern P_ALIAS = Pattern.compile("\\b[aA][sS]\\s+\"([A-Za-z_][A-Za-z0-9_$.]{0,63})\"");
    /**
     * 不带引号的 SQL 别名：真仓里出参几乎全写成这一形（{@code a.call_type as call_type}、
     * 过程体里的 {@code ... as errorcode}），带引号那一形在两个仓里只出现过一次。
     * 只认 SQL 系文件（见 {@link #SQL_SOURCE}）里的 {@code as }，并且挡掉类型转换与关键字。
     */
    private static final Pattern P_ALIAS_PLAIN = Pattern.compile("\\b[aA][sS]\\s+([A-Za-z_][A-Za-z0-9_$]{1,63})\\b");
    /** 哪些扩展算 SQL 系：仓里实际出现的 DB 源码形（mapper / 过程 / 包 / 视图 / 表体），.js/.java 不进这一路 */
    private static final Pattern SQL_SOURCE = Pattern.compile("(?i)[.](xml|sql|prc|fnc|pck|bdy|tab|vw)$");
    /** as 后面接这些词说的是类型或语法，不是出参字段名 */
    private static final Set<String> ALIAS_STOP = Set.of("varchar2", "varchar", "nvarchar2", "number", "numeric",
            "decimal", "char", "date", "timestamp", "clob", "blob", "integer", "int", "float", "boolean",
            "cursor", "table", "rowtype", "type", "select", "from", "where", "begin", "end", "is", "as",
            "null", "not", "and", "or", "desc", "asc", "count", "sum", "max", "min", "dual");
    private static final Pattern P_CAST = Pattern.compile("\\bcast\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RESULT_COL = Pattern.compile("<(?:id|result)\\b[^>]*\\bcolumn\\s*=\\s*\"([^\"]{1,64})\"");
    private static final Pattern P_PUT = Pattern.compile("\\.put\\(\\s*\"([^\"]{1,64})\"");
    private static final Pattern P_GET = Pattern.compile("\\.get(?:String|Integer|Long|Double|Boolean|Object|Array)?\\(\\s*\"([^\"]{1,64})\"");
    /** 视图/服务码的声明增删：整条视图被加或被删，字段清单跟着整块变 */
    private static final Pattern P_BLOCK = Pattern.compile("<(view|service|bex)\\b");

    /** 一个被改动碰到的参数：名字 + 出参还是入参 + 这次是加、删还是就地改 */
    public record Touched(String field, String io, String change, String via, String file) {}

    private final JdbcTemplate jdbc;
    private final CodeChainService codeChain;
    private final ViewConfParser views = new ViewConfParser();

    public OutParamService(JdbcTemplate jdbc, CodeChainService codeChain) {
        this.jdbc = jdbc;
        this.codeChain = codeChain;
    }

    // ---------- ① 按码看整个接口的出参 ----------

    /**
     * 某个 service/bex 码的出参与入参：先按**真实代码**推算（bexConf 的 attr1 → mapper 语句 →
     * 盘上的存储过程源码），再补 {@code viewConf} 里的声明。
     *
     * <p>为什么代码排在声明前面：用户口径「几乎没有几个接口会把出入参配置到 xml 里面，需要结合真实代码的逻辑」
     * ——viewConf 只覆盖页面上有表格的那部分接口，语句与过程体才是全都有的事实。两边都认得出名字时
     * 取代码那条，并把 viewConf 的中文名补上去（声明里有中文说明、代码里没有）。
     *
     * <p>读期现算、不加列不落库；没认出来时明确说"没认出来 + 为什么"，不用空表冒充"没有出参"。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> byCode(long projectId, String kind, String code) {
        Map<String, Object> ch = codeChain.forCode(projectId, kind, code);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) ch.getOrDefault("steps", List.of());
        List<Map<String, Object>> unresolved =
                (List<Map<String, Object>>) ch.getOrDefault("unresolved", List.of());

        List<Map<String, Object>> hits = new ArrayList<>();
        List<String> missingRepo = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, name, local_path FROM repos WHERE project_id=? ORDER BY id", projectId)) {
            Path root = repoRoot(r.get("local_path"));
            if (root == null) {
                missingRepo.add(String.valueOf(r.get("name")));
                continue;
            }
            for (ViewConfParser.ViewDef v : views.parseRepo(root)) {
                if (!kind.equals(v.kind()) || !code.equalsIgnoreCase(v.code())) continue;
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("viewId", v.viewId());
                one.put("viewTitle", v.title());
                one.put("file", v.source());
                one.put("outputs", fieldList(v.cols(), "out", "viewConf 出参列", v.source()));
                one.put("inputs", fieldList(v.qrys(), "in", "viewConf 查询条件", v.source()));
                hits.add(one);
            }
        }
        List<Map<String, Object>> declaredOut = mergeFields(hits, "outputs");
        List<Map<String, Object>> outputs = combine(
                (List<Map<String, Object>>) ch.getOrDefault("outputs", List.of()), declaredOut);
        List<Map<String, Object>> inputs = combine(
                (List<Map<String, Object>>) ch.getOrDefault("inputs", List.of()), mergeFields(hits, "inputs"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("kind", kind);
        out.put("code", code);
        out.put("views", hits);
        out.put("outputs", outputs);
        out.put("inputs", inputs);
        out.put("chain", steps);
        out.put("unresolved", unresolved);
        out.put("repos", ch.getOrDefault("repos", List.of()));
        out.put("from", from(ch.getOrDefault("outputs", List.of()), declaredOut));
        out.put("note", note(ch, unresolved, declaredOut, outputs, missingRepo, kind, code));
        return out;
    }

    /** 代码推出来的在前，只在 viewConf 里声明过的补在后面；同名字段把中文说明带到代码那条上 */
    private static List<Map<String, Object>> combine(List<Map<String, Object>> derived,
                                                    List<Map<String, Object>> declared) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> f : derived) {
            seen.put(String.valueOf(f.get("field")).toLowerCase(java.util.Locale.ROOT), new LinkedHashMap<>(f));
        }
        for (Map<String, Object> f : declared) {
            String k = String.valueOf(f.get("field")).toLowerCase(java.util.Locale.ROOT);
            Map<String, Object> cur = seen.get(k);
            if (cur == null) {
                seen.put(k, new LinkedHashMap<>(f));
                continue;
            }
            if (String.valueOf(cur.getOrDefault("title", "")).isEmpty()
                    && !String.valueOf(f.getOrDefault("title", "")).isEmpty()) {
                cur.put("title", f.get("title"));
                cur.put("titleFrom", "viewConf 声明");
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** 各来源各认出多少个：界面上要说清这份清单是谁给的，不能只报一个总数 */
    private static Map<String, Object> from(Object derivedRaw, List<Map<String, Object>> declared) {
        List<?> derived = derivedRaw instanceof List<?> l ? l : List.of();
        long onlyDeclared = declared.stream().filter(f -> {
            Object f1 = String.valueOf(f.get("field")).toLowerCase(java.util.Locale.ROOT);
            return derived.stream().noneMatch(d -> String.valueOf(((Map<?, ?>) d).get("field"))
                    .toLowerCase(java.util.Locale.ROOT).equals(f1));
        }).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", derived.size());
        m.put("viewConf", declared.size());
        m.put("onlyViewConf", onlyDeclared);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String note(Map<String, Object> ch, List<Map<String, Object>> unresolved,
                               List<Map<String, Object>> declared, List<Map<String, Object>> outputs,
                               List<String> missingRepo, String kind, String code) {
        String where = missingRepo.isEmpty() ? "" : "；这些仓库的本地工作副本不在盘上：" + String.join("、", missingRepo);
        Object derived = ch.getOrDefault("outputs", List.of());
        boolean hasDerived = derived instanceof List<?> l && !l.isEmpty();
        if (hasDerived) {
            StringBuilder sb = new StringBuilder("出参按真实代码认出 " + ((List<?>) derived).size() + " 个");
            if (!declared.isEmpty()) sb.append("，viewConf 的声明里另有 ").append(declared.size()).append(" 个字段");
            if (!unresolved.isEmpty()) sb.append("；还有 ").append(unresolved.size()).append(" 处认不出来，原因逐条列在下面");
            return sb.append(where).toString();
        }
        if (!outputs.isEmpty()) {
            return "代码侧没能认出列（原因见下），下面是 viewConf 里声明的 " + outputs.size()
                    + " 个字段——只有页面上有表格的接口才写 viewConf" + where;
        }
        if (ch.get("note") != null) {
            return ch.get("note") + (unresolved.isEmpty() ? "" : "（认不出的原因逐条列在下面）") + where;
        }
        return "代码与 viewConf 都没给出 " + kind + "「" + code + "」的出参字段" + where
                + "。这不是「没有出参」：得真跑一次看响应快照才知道形状。";
    }

    private static List<Map<String, Object>> fieldList(List<ViewConfParser.OutField> fs, String io,
                                                       String via, String file) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ViewConfParser.OutField f : fs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", f.field());
            m.put("title", f.title() == null ? "" : f.title());
            m.put("type", f.type() == null ? "" : f.type());
            m.put("io", io);
            m.put("via", via);
            m.put("file", file);
            m.put("conf", "declared");
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mergeFields(List<Map<String, Object>> hits, String key) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> h : hits) {
            for (Map<String, Object> f : (List<Map<String, Object>>) h.get(key)) {
                String k = String.valueOf(f.get("field")).toLowerCase(java.util.Locale.ROOT);
                Map<String, Object> cur = seen.get(k);
                if (cur == null) {
                    seen.put(k, new LinkedHashMap<>(f));
                } else if (String.valueOf(cur.getOrDefault("title", "")).isEmpty()
                        && !String.valueOf(f.getOrDefault("title", "")).isEmpty()) {
                    cur.put("title", f.get("title"));   // 多个视图绑同一码时，有中文名的那个补上来
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    // ---------- ② 一次改动是否碰到出入参 ----------

    /**
     * 按提交现算出入参影响。只用盘上已有的 diff 快照（{@code file_changes.patch_file}）——
     * 列表页顺手去连 SVN/Git 会让改动流变慢且难解释，缺快照时照实说，让用户点【详情 / Diff】补。
     */
    public Map<String, Object> impact(long commitId) {
        Scan scan = scan(commitId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("commitId", commitId);
        out.put("outputs", scan.touched().stream().filter(t -> "out".equals(t.io())).toList());
        out.put("inputs", scan.touched().stream().filter(t -> "in".equals(t.io())).toList());
        out.put("unknown", scan.touched().stream().filter(t -> !"out".equals(t.io()) && !"in".equals(t.io())).toList());
        List<Map<String, Object>> codes = (scan.repoId() == null || scan.projectId() == null)
                ? List.of() : codesOf(scan.projectId(), scan.repoId(), scan.touched());
        out.put("codesTotal", codes.size());
        // 整仓导入型提交能回指到几百个功能号，全发下去界面卡、响应也拖到 MB 级；限量但把总数照实报出来
        out.put("codes", codes.size() > CODE_CAP ? codes.subList(0, CODE_CAP) : codes);
        out.put("noPatch", scan.noPatch());
        out.put("scanned", scan.fileCount());
        return out;
    }

    /** 一次提交的 diff 扫描结果：字段清单 + 读不到快照的文件 + 这一提交属于哪个项目／仓库 */
    public record Scan(List<Touched> touched, List<String> noPatch, Long projectId, Long repoId, int fileCount) {}

    /**
     * 只扫盘上快照、不回指功能号。
     *
     * <p>单独开这一层是给结论单用的：它要的是"这个区间把哪些出入参字段改了"，
     * 而 {@link #codesOf} 那一步要把整个仓库的 viewConf / bexConf 重解析一遍并按代码链反查，
     * 一张单几十个提交跑下来就是几十次全仓解析。改动流要回指，所以那边照旧走 {@link #impact}。
     */
    public Scan scan(long commitId) {
        List<Map<String, Object>> files = jdbc.queryForList("""
                SELECT fc.id, fc.path, fc.change_type, fc.patch_file, c.repo_id, r.project_id
                FROM file_changes fc JOIN commits c ON c.id=fc.commit_id
                JOIN repos r ON r.id=c.repo_id WHERE fc.commit_id=? ORDER BY fc.id""", commitId);
        List<Touched> touched = new ArrayList<>();
        List<String> noPatch = new ArrayList<>();
        Long repoId = null;
        Long projectId = null;
        for (Map<String, Object> f : files) {
            repoId = f.get("repo_id") == null ? null : ((Number) f.get("repo_id")).longValue();
            projectId = f.get("project_id") == null ? null : ((Number) f.get("project_id")).longValue();
            String rel = (String) f.get("patch_file");
            String patch = rel == null || rel.isBlank() ? null : readPatch(rel);
            if (patch == null) {
                noPatch.add(String.valueOf(f.get("path")));
                continue;
            }
            touched.addAll(scanPatch(String.valueOf(f.get("path")), patch));
        }
        return new Scan(touched, noPatch, projectId, repoId, files.size());
    }

    /** 回指的接口码上限：与前端「只列前 N 个」的口径对齐，总数走 codesTotal */
    static final int CODE_CAP = 300;

    /** diff 快照按相对路径存在 data/artifacts 下；读不到（没拉过、被清过）就返回 null 让界面照实说 */
    private String readPatch(String rel) {
        try {
            Path p = Path.of(rel);
            if (!Files.isRegularFile(p)) return null;
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 逐行扫 unified diff：'-' 行是删掉、'+' 行是新加；同名字两边都出现就是就地改（换类型、换说明） */
    static List<Touched> scanPatch(String file, String patch) {
        List<Touched> added = new ArrayList<>();
        List<Touched> removed = new ArrayList<>();
        for (String line : patch.split("\r?\n")) {
            if (line.startsWith("+++") || line.startsWith("---")) continue;
            String change;
            if (line.startsWith("+")) change = "added";
            else if (line.startsWith("-")) change = "removed";
            else continue;
            String body = line.substring(1);
            List<Touched> sink = "added".equals(change) ? added : removed;
            collect(sink, P_COL, body, "out", "viewConf 出参列", file);
            collect(sink, P_QRY, body, "in", "viewConf 查询条件", file);
            collect(sink, P_PARAM, body, "in", "bexConf 入参声明", file);
            collect(sink, P_BIND, body, "in", "SQL 占位符 #{}", file);
            collect(sink, P_ALIAS, body, "out", "SQL 别名", file);
            collectAlias(sink, body, file);
            collect(sink, P_RESULT_COL, body, "out", "resultMap 列映射", file);
            collect(sink, P_PUT, body, "out", "代码 put(\"键\")", file);
            collect(sink, P_GET, body, "in", "代码取值 get(\"键\")", file);
            if (P_BLOCK.matcher(body).find()) {
                sink.add(new Touched("(整块声明)", "block", change, "视图/服务声明增删", file));
            }
        }
        // 同一次提交里既删又加 = 那一行被改写，界面要能跟"纯新增/纯删除"区分开
        List<Touched> out = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (Touched t : removed) {
            String k = t.field() + "|" + t.io() + "|" + t.file();
            if (!emitted.add(k)) continue;
            boolean rewritten = added.stream().anyMatch(a ->
                    a.field().equals(t.field()) && a.io().equals(t.io()) && a.file().equals(t.file()));
            out.add(new Touched(t.field(), t.io(), rewritten ? "modified" : "removed", t.via(), t.file()));
        }
        for (Touched t : added) {
            String k = t.field() + "|" + t.io() + "|" + t.file();
            if (!emitted.add(k)) continue;
            out.add(new Touched(t.field(), t.io(), "added", t.via(), t.file()));
        }
        return out;
    }

    /**
     * 不带引号的别名要单独过两道闸：只在 SQL 系文件里认（Java / JS 的 {@code as} 是类型转换），
     * 并且 {@code as} 后跟类型名或语法关键字时不算字段（{@code cast(x as number)}）。
     */
    private static void collectAlias(List<Touched> sink, String body, String file) {
        if (file == null || !SQL_SOURCE.matcher(file).find() || P_CAST.matcher(body).find()) return;
        Matcher m = P_ALIAS_PLAIN.matcher(body);
        while (m.find()) {
            String name = m.group(1);
            if (ALIAS_STOP.contains(name.toLowerCase(java.util.Locale.ROOT))) continue;
            sink.add(new Touched(name, "out", null, "SQL 别名（不带引号）", file));
        }
    }

    private static void collect(List<Touched> sink, Pattern p, String body, String io, String via, String file) {
        Matcher m = p.matcher(body);
        while (m.find()) {
            String name = m.group(1);
            if (name.contains(".")) name = name.substring(name.lastIndexOf('.') + 1);
            sink.add(new Touched(name, io, null, via, file));   // change 由上层按 +/- 与是否两边都出现来定
        }
    }

    /**
     * 被碰到的字段回指到接口码，两条判据合并：
     * ① 声明行——同一文件里 viewConf 的 col_item/qry_item、bexConf 的 param 写着这个字段；
     * ② 代码链——改动文件里有某码用的那条语句（列名／绑定键正好命中），或改动文件定义了某码调用的过程。
     *
     * <p>②是第卅五轮补的：只认①的话，改 mapper 语句、改存储过程的提交基本数不出接口，
     * 而用户口径正是「几乎没有几个接口会把出入参配置到 xml 里面」。两条各自的判据都随条目透出（basis），
     * 界面要说清这一条是按哪一种算的，不混成一个数。
     */
    private List<Map<String, Object>> codesOf(long projectId, long repoId, List<Touched> touched) {
        if (touched.isEmpty()) return List.of();
        Map<String, Object> repo = jdbc.queryForMap(
                "SELECT r.local_path FROM repos r WHERE r.id=?", repoId);
        Path root = repoRoot(repo.get("local_path"));
        if (root == null) return List.of();
        List<ViewConfParser.ViewDef> all = new ArrayList<>(views.parseRepo(root));
        all.addAll(extraBexViews(projectId, repoId));
        Map<String, Set<String>> byField = new LinkedHashMap<>();
        for (Touched t : touched) {
            if ("block".equals(t.io())) continue;
            byField.computeIfAbsent(t.field().toLowerCase(java.util.Locale.ROOT), k -> new LinkedHashSet<>())
                    .add(fileKey(t.file()));
        }
        Map<String, Map<String, Object>> hits = new LinkedHashMap<>();
        for (ViewConfParser.ViewDef v : all) {
            List<Map<String, Object>> fs = new ArrayList<>();
            for (ViewConfParser.OutField f : v.cols()) match(fs, byField, f, "out", v);
            for (ViewConfParser.OutField f : v.qrys()) match(fs, byField, f, "in", v);
            if (fs.isEmpty()) continue;
            Map<String, Object> one = enter(hits, v.kind(), v.code(), "同一文件里的声明行");
            ((List<String>) one.get("views")).add(v.viewId() == null ? v.title() : v.viewId());
            ((List<Map<String, Object>>) one.get("fields")).addAll(fs);
        }
        for (CodeChainService.CodeHit h : codeChain.codesForFields(projectId, touchedByFile(touched))) {
            Map<String, Object> one = enter(hits, h.kind(), h.code(), "代码链（语句／过程调用关系）");
            List<Map<String, Object>> fs = (List<Map<String, Object>>) one.get("fields");
            String k = h.field().toLowerCase(java.util.Locale.ROOT) + "|" + h.io();
            boolean seen = fs.stream().anyMatch(f -> (String.valueOf(f.get("field"))
                    .toLowerCase(java.util.Locale.ROOT) + "|" + f.get("io")).equals(k));
            if (seen) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", h.field());
            m.put("title", "");
            m.put("io", h.io());
            m.put("via", h.basis());
            m.put("repo", h.repo());
            fs.add(m);
        }
        return new ArrayList<>(hits.values());
    }

    private static Map<String, Object> enter(Map<String, Map<String, Object>> hits, String kind,
                                             String code, String basis) {
        String k = kind + "|" + code.toLowerCase(java.util.Locale.ROOT);
        return hits.computeIfAbsent(k, x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("code", code);
            m.put("views", new ArrayList<String>());
            m.put("fields", new ArrayList<Map<String, Object>>());
            m.put("basis", basis);
            return m;
        });
    }

    /** 被碰到的字段按文件归一键分组：文件尾键 → 字段（小写）→ 出参还是入参 */
    private static Map<String, Map<String, String>> touchedByFile(List<Touched> touched) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Touched t : touched) {
            if ("block".equals(t.io()) || t.file() == null) continue;
            out.computeIfAbsent(fileKey(t.file()), k -> new LinkedHashMap<>())
                    .putIfAbsent(t.field().toLowerCase(java.util.Locale.ROOT), t.io());
        }
        return out;
    }

    private static void match(List<Map<String, Object>> fs, Map<String, Set<String>> byField,
                              ViewConfParser.OutField f, String io, ViewConfParser.ViewDef v) {
        Set<String> files = byField.get(f.field().toLowerCase(java.util.Locale.ROOT));
        if (files == null) return;
        String key = fileKey(v.source());
        // 只认同一个文件：commit 里存的是仓库相对路径、证据里可能是绝对路径，所以按「目录/文件名」这一段比对；
        // 不同文件里的同名字段不算被这次改动碰到。
        if (key.isEmpty() || !files.contains(key)) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", f.field());
        m.put("title", f.title() == null ? "" : f.title());
        m.put("io", io);
        fs.add(m);
    }

    /** 文件归一键：判据在 {@link com.preflight.common.FileKeys#tail}——声明行那条路与代码链那条路必须用同一个键 */
    static String fileKey(String path) {
        return com.preflight.common.FileKeys.tail(path);
    }

    /**
     * viewConf 之外的第二条绑法：bexConf 里 {@code <bex bex_code>} 自己声明入参。
     * 出参侧老工程只有 viewConf 写，所以这里只补"码 → 入参字段"，让改了 bexConf 的提交也能回指到码。
     */
    private List<ViewConfParser.ViewDef> extraBexViews(long projectId, long repoId) {
        List<ViewConfParser.ViewDef> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT kind, code, name, params, evidence FROM dispatch_targets WHERE project_id=? AND repo_id=?",
                projectId, repoId)) {
            String ev = String.valueOf(row.get("evidence"));
            if (ev.indexOf("viewConf") >= 0) continue;   // 已由 viewConf 覆盖，别重复挂一条
            List<Map<String, Object>> ps = readParams(row.get("params"));
            List<ViewConfParser.OutField> qrys = new ArrayList<>();
            for (Map<String, Object> p : ps) {
                Object k = p.get("key");
                if (k != null) qrys.add(new ViewConfParser.OutField(String.valueOf(k),
                        String.valueOf(p.getOrDefault("label", "")), String.valueOf(p.getOrDefault("type", ""))));
            }
            if (qrys.isEmpty()) continue;
            out.add(new ViewConfParser.ViewDef(String.valueOf(row.get("code")), String.valueOf(row.get("name")),
                    String.valueOf(row.get("kind")), String.valueOf(row.get("code")), List.of(), qrys,
                    ev.isBlank() || "[]".equals(ev) ? "" : firstSource(ev)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readParams(Object raw) {
        if (raw == null) return List.of();
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(String.valueOf(raw), List.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : (List<Object>) parsed) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** evidence 存的是 ["路径 <bex bex_code=\"X\">"]，取路径段用于同文件比对 */
    private static String firstSource(String evidenceJson) {
        try {
            List<?> l = new com.fasterxml.jackson.databind.ObjectMapper().readValue(evidenceJson, List.class);
            if (l.isEmpty()) return "";
            return String.valueOf(l.get(0)).split(" ")[0].trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Path repoRoot(Object localPath) {
        String dir = localPath == null ? null : String.valueOf(localPath);
        if (dir == null || dir.isBlank() || !Files.isDirectory(Path.of(dir))) return null;
        return Path.of(dir).toAbsolutePath().normalize();
    }
}
