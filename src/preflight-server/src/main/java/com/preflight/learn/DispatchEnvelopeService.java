package com.preflight.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.parser.DispatchAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分发入口的信封判定（第卅五轮）：一个 service / bex 代码被前端怎么调，
 * 就按那种带法给文档写请求级字段——不再假定"每个接口都带 service/func/func_url/pageoffset/pagecount"。
 *
 * <p>判据只有一条：<b>分发字典里那条 evidence 指向的前端调用点原文</b>。字典学习时把
 * {@code 代码 → js 文件:行号} 记了下来，这里回到同一行去认对象字面量的顶层键
 * （解析在 {@link CallSiteEnvelope}，本类只管找文件、读文件和汇总口径）。
 *
 * <p>没有调用点证据时先看框架自己怎么说：一个功能号一条真实路径的那种框架（统一访问转发中心），
 * 请求级字段由 {@link DispatchAdapter#requestFields} 声明，{@code basis} 标成 {@code framework-contract}
 * ——那是契约不是猜的。两者都没有时才按 {@code kind} 给最小信封（bex 只带 {@code bex_codes}、
 * service 只带 {@code service}），并把 {@code basis} 标成 {@code fallback-minimal}
 * 加一句为什么，界面上恒显——用户看得见这条是推的、要去改哪里。
 *
 * <p>只读：读库用 SELECT，读盘只读工作副本里的文件，按 路径+大小+修改时间 缓存文本；
 * 推导结果一律读取期现算，不落库。
 */
@Service
public class DispatchEnvelopeService {

    /** evidence 里的调用点形态：{@code 相对路径.js:行号} */
    private static final Pattern P_JS = Pattern.compile("^(.+?\\.jsx?):(?:line\\s*)?(\\d+)$", Pattern.CASE_INSENSITIVE);

    private record Cached(long size, long mtime, String text) {}

    private final JdbcTemplate jdbc;
    private final Map<String, DispatchAdapter> byName = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> e) {
            return size() > 128;
        }
    };

    public DispatchEnvelopeService(JdbcTemplate jdbc, List<DispatchAdapter> adapters) {
        this.jdbc = jdbc;
        for (DispatchAdapter a : adapters) byName.put(a.displayName(), a);
    }

    /** 一个代码的信封判定结果；找不到字典条目时 ok=false，由调用方照实转述 */
    public Map<String, Object> forTarget(long projectId, String kind, String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT kind, code, sources, evidence, framework, entry_path FROM dispatch_targets"
                        + " WHERE project_id=? AND kind=? AND code=?",
                projectId, kind, code);
        if (rows.isEmpty()) {
            return Map.of("ok", false, "error", "服务字典里没有 " + kind + "「" + code + "」，无从判定信封");
        }
        Map<String, List<Integer>> anchors = new LinkedHashMap<>();   // 证据里的 js 路径 → 行号
        int jsRefs = 0;
        String framework = "";
        String entryPath = "";
        for (Map<String, Object> r : rows) {
            if (framework.isEmpty() && r.get("framework") != null) framework = String.valueOf(r.get("framework")).trim();
            if (entryPath.isEmpty() && r.get("entry_path") != null) entryPath = String.valueOf(r.get("entry_path")).trim();
            for (String ev : readEvidence(r.get("evidence"))) {
                Matcher m = P_JS.matcher(ev.trim());
                if (!m.matches()) continue;
                jsRefs++;
                Path file = resolve(projectId, m.group(1));
                if (file == null) {
                    anchors.computeIfAbsent("MISS:" + m.group(1), k -> new ArrayList<>());
                    continue;
                }
                anchors.computeIfAbsent(file.toString(), k -> new ArrayList<>()).add(Integer.parseInt(m.group(2)));
            }
        }

        Map<String, CallSiteEnvelope.Observed> best = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean viaContent = false;
        int contentHits = 0, contentUsed = 0;
        for (Map.Entry<String, List<Integer>> e : anchors.entrySet()) {
            if (e.getKey().startsWith("MISS:")) {
                missing.add(e.getKey().substring(5));
                continue;
            }
            String src = read(Path.of(e.getKey()));
            if (src == null) {
                missing.add(e.getKey());
                continue;
            }
            CallSiteEnvelope.Result res = CallSiteEnvelope.collect(src, e.getValue(), code);
            contentHits += res.contentHits();
            contentUsed += res.contentUsed();
            for (CallSiteEnvelope.Observed o : res.observed()) {
                if ("content".equals(o.via())) viaContent = true;
                CallSiteEnvelope.Observed cur = best.get(o.field());
                if (cur != null && (cur.literal() || !o.literal())) continue;   // 有字面量值的那个说话
                best.put(o.field(), o);
                String at = shortPath(e.getKey()) + " " + o.at() + " → " + o.field()
                        + (o.literal() ? "='" + o.value() + "'" : "（表达式，值取自调用点但非字面量）");
                if (!evidence.contains(at)) evidence.add(at);
            }
        }

        String basis;
        String note;
        List<Map<String, Object>> env = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(best.keySet());
        for (String key : CallSiteEnvelope.KNOWN.keySet()) {     // 展示顺序按 KNOWN，不按各文件出现先后
            CallSiteEnvelope.Observed o = best.get(key);
            if (o != null) env.add(field(key, o.value(), o.literal(), o.at()));
        }
        if (!best.isEmpty()) {
            basis = "call-site";
            note = "按 " + jsRefs + " 个前端调用点认出的信封字段：" + String.join("、", seen)
                    + "。调用点没写的字段不写进文档——" + kind + "「" + code + "」的实际带法就是这些。";
            if (viaContent) {
                note += "其中按【内容锚点】找回的：字典记的行号在这份文件里已经没有这个码了（SVN 更新后行号会漂），"
                        + "系统改按「含这个码的那一行」定位，字段仍是调用点原文里出现的，不是编的。";
            }
        } else {
            // 框架自己声明了请求字段（转发中心那种"一个功能号一条真实路径"）：这是事实，不是推的
            List<Map<String, Object>> contract = byName.containsKey(framework)
                    ? byName.get(framework).requestFields(code, entryPath) : List.of();
            if (!contract.isEmpty()) {
                basis = "framework-contract";
                env.addAll(contract);
                for (Map<String, Object> f : contract) seen.add(String.valueOf(f.get("field")));
                note = "按「" + framework + "」的框架契约带的字段：" + String.join("、", seen)
                        + "。这类框架每个功能号有自己的请求路径（" + (entryPath.isBlank() ? "没学到路径" : entryPath)
                        + "），不是靠一个公共入口分发，所以请求级字段由框架声明给出，不是从前端调用点猜的"
                        + "；业务参数另列。";
            } else {
                basis = "fallback-minimal";
                String f = "bex".equals(kind) ? "bex_codes" : "service";
                env.add(field(f, code, true, "按 kind 兜底"));
                note = (jsRefs == 0
                        ? "字典里这个代码没有前端调用点证据（只从 metaData/调用链学到）"
                        : "有 " + jsRefs + " 个调用点证据，但那些行里没认出信封字段（可能整个请求体是变量传进来的）")
                        + "。这里按 " + kind + " 的最小带法给了 " + f + " 一项，是「推的」：真实调用还带别的字段时，"
                        + "到「知识库 → 服务字典」看这个码的证据，或在 YApi 上直接改那条文档。";
            }
        }
        if (!missing.isEmpty()) {
            note += "；这些调用点所在文件不在本地工作副本里，没读到：" + String.join("、", missing);
        }
        if (contentUsed < contentHits) {
            note += "；这个码在文件里出现 " + contentHits + " 行，按基数保护只看了前 " + contentUsed
                    + " 行（要更全的带法到「知识库 → 服务字典」看这条的证据）";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("kind", kind);
        out.put("code", code);
        out.put("basis", basis);
        // 锚点怎么落到的：line＝字典行号那一行确实有这个码；content＝行号漂了、按内容找回；
        // framework＝这类框架不走公共入口，字段由框架契约声明。界面按这个自述
        out.put("anchor", best.isEmpty() ? ("framework-contract".equals(basis) ? "framework" : "none")
                : (viaContent ? "content" : "line"));
        out.put("contentHits", contentHits);
        out.put("contentUsed", contentUsed);
        out.put("envelope", env);
        out.put("evidence", evidence);
        out.put("anchors", jsRefs);
        out.put("fields", new ArrayList<>(seen));
        out.put("note", note);
        out.put("framework", framework);
        String fu = best.containsKey("func_url") ? best.get("func_url").value() : envValue(env, "func_url");
        out.put("funcUrl", fu);
        out.put("serviceValue", best.containsKey("service") ? best.get("service").value() : envValue(env, "service"));
        return out;
    }

    /** 框架契约那条链上取字段值（调用点没证据时 env 里装的是适配器声明的契约字段） */
    private static String envValue(List<Map<String, Object>> env, String key) {
        for (Map<String, Object> f : env) {
            if (key.equals(f.get("field"))) {
                Object v = f.get("value");
                return v == null ? "" : String.valueOf(v);
            }
        }
        return "";
    }

    private static Map<String, Object> field(String key, String value, boolean literal, String at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", key);
        m.put("type", key.equals("pageoffset") || key.equals("pagecount") ? "integer" : "string");
        m.put("title", CallSiteEnvelope.KNOWN.getOrDefault(key, key));
        m.put("value", value);
        m.put("literal", literal);
        m.put("at", at);
        return m;
    }

    // ---------- 找文件 ----------

    /**
     * 证据里的相对路径不保证相对于哪个根：字典是在某个仓库的学习里写的，
     * 路径却可能带兄弟仓库前缀（{@code work_platform/js/...}）甚至整段仓库目录。
     * 所以按「本仓库根 → 上一级目录 → 逐级削掉首段」找第一个真实存在的文件，不猜。
     */
    private Path resolve(long projectId, String rel) {
        String r = rel.replace('\\', '/').trim();
        if (r.startsWith("/") || r.matches("^[A-Za-z]:.*")) {
            Path abs = Path.of(r);
            if (Files.isRegularFile(abs)) return abs;
            r = r.replaceFirst("^/", "");
        }
        List<Path> bases = roots(projectId);
        String cur = r;
        for (int trim = 0; trim <= 4 && !cur.isEmpty(); trim++) {
            for (Path b : bases) {
                Path p = b.resolve(cur);
                if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize();
                Path parent = b.getParent();
                if (parent != null) {
                    Path pp = parent.resolve(cur);
                    if (Files.isRegularFile(pp)) return pp.toAbsolutePath().normalize();
                }
            }
            int slash = cur.indexOf('/');
            if (slash < 0) break;
            cur = cur.substring(slash + 1);
        }
        return null;
    }

    private List<Path> roots(long projectId) {
        List<Path> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT local_path FROM repos WHERE project_id=? ORDER BY id", projectId)) {
            String dir = row.get("local_path") == null ? "" : String.valueOf(row.get("local_path"));
            if (dir.isBlank()) continue;
            Path p = Path.of(dir);
            if (Files.isDirectory(p) && !out.contains(p)) out.add(p);
        }
        out.sort(Comparator.comparingInt(p -> p.toString().length()));   // 短的根先试：少一层拼接
        return out;
    }

    /** 前端脚本以 UTF-8 为主，个别老文件是 GBK：先按 UTF-8 严格解，失败再退 GBK */
    private String read(Path p) {
        try {
            long size = Files.size(p);
            long mtime = Files.getLastModifiedTime(p).toMillis();
            String key = p.toString();
            Cached c = cache.get(key);
            if (c != null && c.size() == size && c.mtime() == mtime) return c.text();
            String text;
            try {
                text = Files.readString(p, StandardCharsets.UTF_8);
            } catch (java.nio.charset.MalformedInputException e1) {
                text = new String(Files.readAllBytes(p), Charset.forName("GBK"));
            }
            cache.put(key, new Cached(size, mtime, text));
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    private static String shortPath(String full) {
        String s = full.replace('\\', '/');
        int i = s.lastIndexOf('/', s.lastIndexOf('/') - 1);
        return i < 0 ? s : s.substring(i + 1);
    }

    private List<String> readEvidence(Object raw) {
        if (raw == null) return List.of();
        try {
            List<?> l = mapper.readValue(String.valueOf(raw), List.class);
            List<String> out = new ArrayList<>();
            for (Object o : l) out.add(String.valueOf(o));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
