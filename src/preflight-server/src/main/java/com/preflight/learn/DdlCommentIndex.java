package com.preflight.learn;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 盘上建表脚本里的列注释与列类型（第四十三轮的第二条通路）。
 *
 * <p>用户口径「yapi 接口预览里字段意思也没有」。实测这台库的 {@code table_meta} 只连库学过 12 张表，
 * 而 {@code db_prod} 工作副本里有 1887 个建表脚本、其中 1113 个写着
 * {@code comment on column KDCC.T_HCGL_HF_KHMDDY.dtxx is '动态信息展示列[...]'}——
 * 说明一直就在盘上，只是没人读。这与第卅五轮"过程源码在盘上"是同一件事：库停了也照样能取到语义。
 *
 * <p>口径：<b>库内学到的那份优先</b>（那才是当前真实结构），这里只补空缺。类型也从建表语句的列声明取，
 * 同样只在库内没有时给。全程只读文件，什么都不写。
 */
@Service
public class DdlCommentIndex {

    /** 一条列注释：中文说明 + 出处（仓库名 / 相对文件 / 行号） */
    public record DdlCol(String comment, String dataType, String table, String repoName, String file, int line) {}

    /** 库对象脚本的扩展名：比过程索引多 {@code tab}（建表脚本就存成 .tab） */
    private static final List<String> EXTS = List.of("tab", "sql", "pck", "vw", "trg");
    private static final Charset GBK = Charset.forName("GBK");
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    /** 整仓最多扫这么多文件；扫不完时界面上要照实说，不能假装"盘上没有这张表" */
    private static final int MAX_FILES = 20_000;
    /** 一个文件里出现 {@code comment on column} 才算带列注释的脚本，其余文件不解 */
    private static final Pattern MARK = Pattern.compile("(?i)comment\\s+on\\s+column\\s");
    /** comment on column [SCHEMA.]TABLE.COL [is '中文'] —— is 子句可换行，注释串里可以有任何字符 */
    private static final Pattern COL = Pattern.compile(
            "(?is)comment\\s+on\\s+column\\s+([\\w$.\"']+)\\s+(?:is\\s+'((?:[^']|'')*)'\\s*;?)?");
    /** create table [SCHEMA.]TABLE ( 之后的列声明行：{@code wjmc VARCHAR2(200),} */
    private static final Pattern CREATE = Pattern.compile("(?is)create\\s+table\\s+[\\w$.\"']*\\s*\\(");
    private static final Pattern COLDEF = Pattern.compile("^\\s*\"?([A-Za-z_][\\w$]{1,62})\"?\\s+([A-Za-z]+(?:\\s*\\([^)]*\\))?)");
    private static final List<String> NOT_COLS = List.of("constraint", "primary", "unique", "foreign",
            "check", "create", "comment", "partition", "tablespace", "logging", "suppressing", "inheritable");

    private final JdbcTemplate jdbc;
    private final Map<Long, Repo> byProject = lru(6);

    public DdlCommentIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一张表的列（列名小写 → 注释与类型）。整表名与裸表名都能认：脚本里的表常带 schema 前缀
     * （{@code KDCC.T_X}），而依据链给的是 mapper 里写的名字。
     *
     * <p>同名表出现在多个 schema（库里有 {@code kdcc.t_hcgl_hf_khmddy} 与 {@code kdetl.t_hcgl_hf_khmddy_1216}
     * 这种近名副本）时按见到顺序合并，一列已有说明不被后面的空值抹掉——与 {@code columnsOf} 同一套口径。
     */
    public Map<String, DdlCol> columnsOf(long projectId, String table) {
        String bare = bare(table);
        if (bare.isEmpty()) return Map.of();
        Repo r = repo(projectId);
        if (r == null) return Map.of();
        // 外层键是裸表名，内层键是脚本里带 schema 的全名（kdcc.t_x）——两级都要按这个形状取，
        // 拿全名去外层、拿裸名去内层，结果就是一个都认不出（第四十三轮 C3 那个空值的真因）。
        Map<String, Map<String, DdlCol>> byFull = r.tables.get(bare);
        if (byFull == null) return Map.of();
        Map<String, DdlCol> out = new LinkedHashMap<>();
        Map<String, DdlCol> exact = byFull.get(lower(table == null ? "" : table.trim()));
        if (exact != null) {
            merge(out, exact);
            return out;
        }
        for (Map<String, DdlCol> cols : byFull.values()) merge(out, cols);
        return out;
    }

    /** 扫过但没建完索引的项目数——「盘上没有」和「还没扫到」是两件事（与过程索引同一口径） */
    public boolean capped(long projectId) {
        Repo r = repo(projectId);
        return r != null && r.capped;
    }

    private static void merge(Map<String, DdlCol> out, Map<String, DdlCol> add) {
        if (add == null) return;
        add.forEach((k, v) -> {
            DdlCol old = out.get(k);
            if (old == null) {
                out.put(k, v);
                return;
            }
            out.put(k, new DdlCol(blank(old.comment()) ? v.comment() : old.comment(),
                    blank(old.dataType()) ? v.dataType() : old.dataType(),
                    old.table(), old.repoName(), old.file(), old.line()));
        });
    }

    // ---------- 按项目建索引 ----------

    private static class Repo {
        final Map<String, Map<String, Map<String, DdlCol>>> tables = new LinkedHashMap<>();
        int files;
        boolean capped;
    }

    private Repo repo(long projectId) {
        Repo cached = byProject.get(projectId);
        if (cached != null) return cached;
        Repo r = build(projectId);
        if (r != null) byProject.put(projectId, r);
        return r;
    }

    private Repo build(long projectId) {
        List<Map<String, Object>> repos;
        try {
            repos = jdbc.queryForList("SELECT name, local_path FROM repos WHERE project_id=? ORDER BY id", projectId);
        } catch (Exception e) {
            return null;
        }
        Repo r = new Repo();
        for (Map<String, Object> row : repos) {
            Path root = Path.of(String.valueOf(row.get("local_path")));
            if (!Files.isDirectory(root)) continue;
            String repoName = String.valueOf(row.get("name"));
            try (var w = Files.walk(root)) {
                for (Path p : w.toList()) {
                    String s = p.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
                    if (s.contains("/.git/") || s.contains("/node_modules/")) continue;
                    if (!Files.isRegularFile(p)) continue;
                    int dot = s.lastIndexOf('.');
                    if (dot < 0 || !EXTS.contains(s.substring(dot + 1))) continue;
                    if (++r.files > MAX_FILES) {
                        r.capped = true;
                        break;
                    }
                    parseFile(r, repoName, root, p);
                }
            } catch (Exception ignore) {
                // 一个仓库走不通不能带崩整趟：这里取不到说明就是回到"只说字段名"
            }
        }
        return r;
    }

    private void parseFile(Repo r, String repoName, Path root, Path file) {
        String text = read(file);
        if (text == null || !MARK.matcher(text).find()) return;
        String rel = rel(root, file);
        Map<String, String> declared = declaredTypes(text);
        Matcher m = COL.matcher(text);
        while (m.find()) {
            String full = unquote(m.group(1));
            int dot = full.lastIndexOf('.');
            if (dot <= 0) continue;              // 没有 schema 前缀的写法认不出表，宁可不给
            String table = lower(full.substring(0, dot + 1)) + lower(full.substring(dot + 1));
            String col = lower(full.substring(dot + 1));
            String cmt = m.group(2) == null ? "" : m.group(2).replace("''", "'").trim();
            put(r, table, col, new DdlCol(cmt, declared.get(col), bare(table), repoName, rel, lineOf(text, m.start())));
        }
    }

    private static void put(Repo r, String table, String col, DdlCol v) {
        if (blank(v.comment()) && blank(v.dataType())) return;
        String bare = bare(table);
        r.tables.computeIfAbsent(bare, k -> new LinkedHashMap<>())
                .computeIfAbsent(table, k -> new LinkedHashMap<>())
                .computeIfAbsent(col, k -> new DdlCol("", "", table, v.repoName(), v.file(), v.line()));
        DdlCol old = r.tables.get(bare).get(table).get(col);
        r.tables.get(bare).get(table).put(col, new DdlCol(blank(old.comment()) ? v.comment() : old.comment(),
                blank(old.dataType()) ? v.dataType() : old.dataType(), table, v.repoName(), v.file(), v.line()));
    }

    /** create table 括号里的列声明 → 类型（只认第一个左括号后的那段，够建表脚本用） */
    private static Map<String, String> declaredTypes(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher c = CREATE.matcher(text);
        if (!c.find()) return out;
        int i = c.end();
        int depth = 1;
        int end = text.length();
        for (int p = i; p < text.length() && depth > 0; p++) {
            char ch = text.charAt(p);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ';' && depth == 0) {
                end = p;
                break;
            }
            if (depth == 0) end = p;
        }
        for (String line : text.substring(i, Math.min(end, text.length())).split("\r?\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("--")) continue;
            String head = l.toLowerCase(Locale.ROOT);
            if (NOT_COLS.stream().anyMatch(head::startsWith)) continue;
            Matcher d = COLDEF.matcher(l);
            if (!d.find()) continue;
            out.putIfAbsent(lower(d.group(1)), d.group(2).replaceAll("\\s+", ""));
        }
        return out;
    }

    /** 工作副本以 GBK 为主、少数 UTF-8：先按 UTF-8 严格解，失败退 GBK（与过程索引同一套） */
    private static String read(Path p) {
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
            return p.getFileName() == null ? "?" : p.getFileName().toString();
        }
    }

    private static int lineOf(String text, int at) {
        int n = 1;
        for (int i = 0; i < at && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    private static String unquote(String s) {
        return s == null ? "" : s.replace("\"", "").replace("'", "");
    }

    private static String bare(String table) {
        String s = table == null ? "" : table.trim();
        int dot = s.lastIndexOf('.');
        return lower(dot < 0 ? s : s.substring(dot + 1));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
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
