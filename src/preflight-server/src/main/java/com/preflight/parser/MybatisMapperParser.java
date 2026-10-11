package com.preflight.parser;

import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis mapper XML 解析（PoC-B）：
 * 提取 select/insert/update/delete 语句；#{x} 转安全绑定占位符 ?，${x} 标红提示注入风险；
 * 交由 JSqlParser 还原校验并提取表名。这是"免启动直测"（能力 3）的 SQL 模板来源。
 */
@Component
public class MybatisMapperParser {

    private static final Pattern HASH_PARAM = Pattern.compile("#\\{\\s*([^}]+?)\\s*}");
    private static final Pattern DOLLAR_PARAM = Pattern.compile("\\$\\{\\s*([^}]+?)\\s*}");
    /** 固定顺序：产物顺序可预期，便于测试与前端展示 */
    private static final List<String> STATEMENT_TAGS = List.of("select", "insert", "update", "delete");

    private Document parseDoc(Path file) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = dbf.newDocumentBuilder();
        // mapper 文件的 DOCTYPE 指向外部 DTD：用空 EntityResolver 拦截，实现离线零外联
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(file.toFile());
    }

    /** N22：收集本文件的 <sql> 片段，key 同时登记 namespace.id 与裸 id，供跨文件 <include refid> 解析 */
    public Map<String, String> collectFragments(Path file) throws Exception {
        Document doc = parseDoc(file);
        NodeList m = doc.getElementsByTagName("mapper");
        if (m.getLength() == 0) return Map.of();
        String ns = ((Element) m.item(0)).getAttribute("namespace");
        Map<String, String> out = new LinkedHashMap<>();
        NodeList sqls = doc.getElementsByTagName("sql");
        for (int i = 0; i < sqls.getLength(); i++) {
            Element el = (Element) sqls.item(i);
            String id = el.getAttribute("id");
            String text = el.getTextContent();
            if (ns != null && !ns.isBlank()) out.put(ns + "." + id, text);
            out.putIfAbsent(id, text);
        }
        return out;
    }

    public List<ExtractedUnit> extract(Path file) throws Exception {
        return extract(file, Map.of());
    }

    /** externalFrags = 其它 mapper 文件的 <sql> 片段（key 含 namespace.id），解析跨文件 <include refid> */
    public List<ExtractedUnit> extract(Path file, Map<String, String> externalFrags) throws Exception {
        Document doc = parseDoc(file);

        NodeList mapperNodes = doc.getElementsByTagName("mapper");
        if (mapperNodes.getLength() == 0) {
            return List.of();
        }
        String fileName = file.getFileName().toString();
        List<ExtractedUnit> units = new ArrayList<>();

        // N22：<include refid> 片段 getTextContent 拿不到 → 把本文件 <sql id> 定义内联进 include 节点。
        // 多轮迭代到收敛，支持片段套片段；解析不到的留 INCLUDE_UNRESOLVED 标记并如实警告。
        Map<String, String> fragText = new LinkedHashMap<>();
        NodeList sqlNodes = doc.getElementsByTagName("sql");
        NodeList includeNodes = doc.getElementsByTagName("include");
        for (int pass = 0; pass < 5; pass++) {
            for (int i = 0; i < includeNodes.getLength(); i++) {
                Element inc = (Element) includeNodes.item(i);
                for (int c = inc.getChildNodes().getLength() - 1; c >= 0; c--) {
                    if (inc.getChildNodes().item(c) instanceof org.w3c.dom.Text) inc.removeChild(inc.getChildNodes().item(c));
                }
                String ref = inc.getAttribute("refid");
                String frag = fragText.get(ref);
                if (frag == null) frag = externalFrags.get(ref); // 跨文件：namespace.id 全名命中
                if (frag == null) {
                    for (Map.Entry<String, String> fe : externalFrags.entrySet()) {
                        if (fe.getKey().endsWith("." + ref)) { frag = fe.getValue(); break; } // 裸 id → 限定名兜底
                    }
                }
                inc.appendChild(doc.createTextNode(frag != null ? " " + frag + " " : " INCLUDE_UNRESOLVED(" + ref + ") "));
            }
            Map<String, String> next = new LinkedHashMap<>();
            for (int i = 0; i < sqlNodes.getLength(); i++) {
                Element el = (Element) sqlNodes.item(i);
                next.put(el.getAttribute("id"), el.getTextContent());
            }
            if (next.equals(fragText)) break;
            fragText = next;
        }

        for (String tag : STATEMENT_TAGS) {
            NodeList nodes = doc.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String id = el.getAttribute("id");
                String rawSql = normalize(el.getTextContent());
                if (rawSql.isBlank()) {
                    continue;
                }

                List<String> warnings = new ArrayList<>();
                Map<String, String> params = new LinkedHashMap<>();
                Matcher hash = HASH_PARAM.matcher(rawSql);
                StringBuilder exec = new StringBuilder();
                while (hash.find()) {
                    String simple = simpleName(hash.group(1));
                    params.putIfAbsent(simple, "string");
                    hash.appendReplacement(exec, "?");
                }
                hash.appendTail(exec);
                String executableSql = exec.toString();

                Matcher dollar = DOLLAR_PARAM.matcher(rawSql);
                if (dollar.find()) {
                    warnings.add("包含 ${} 拼接型占位符（" + dollar.group(1) + "），存在 SQL 注入风险，直测时需人工确认");
                    executableSql = replaceDollarLiterals(executableSql);
                }

                // N22 兜底清洗：嵌套/畸形花括号可能让上面两个正则漏掉残片（如 ${fn(x)} 截断成 DOLLAR)} ），
                // 残留 #{}/${} 会让直测与一键回归必然 ORA-00933。入库前再扫一遍直到干净。
                executableSql = scrubResidue(executableSql, params, warnings);
                if (executableSql.contains("INCLUDE_UNRESOLVED")) {
                    warnings.add("含本文件解析不到的 include 片段（可能跨文件 refid），模板不完整，直测前需人工补全");
                }

                SqlAnalyzer.SqlInfo info = SqlAnalyzer.analyze(executableSql);
                if (info.error() != null) {
                    warnings.add("SQL 还原校验未通过：" + info.error());
                }

                // 第 14 轮：另存一份"动态原文"（占位符与 <if>/<choose> 标签都还在），直测时按参数条件拼装。
                String dynamicBody = dynamicBodyOf(el, warnings);

                units.add(new ExtractedUnit("SQL_STATEMENT", fileName + "." + id, null,
                        null, params.entrySet().stream()
                        .map(e -> new ExtractedUnit.ParamDef(e.getKey(), e.getValue(), "sql", true, null))
                        .toList(),
                        info.tables() == null ? List.of() : info.tables(),
                        fileName, 0, 0, warnings,
                        rawSql.length() > 200 ? rawSql.substring(0, 200) + "..." : rawSql,
                        executableSql,
                        el.hasAttribute("databaseId") ? el.getAttribute("databaseId") : null,
                        dynamicBody));
            }
        }
        return units;
    }

    /** 动态原文超过这个长度就不存了（宁缺勿滥：这类巨型语句多半是拼接产物，存下来只会把库和知识包撑爆） */
    private static final int MAX_DYNAMIC_BODY = 20_000;

    /**
     * 第 14 轮：语句里有动态标签或占位符（#{}/${}）时，把内层原样序列化存下来（都不清洗），
     * 直测时按名字填参、按条件拼装。完全没有占位符的纯静态语句返回 null——拍平就是最终形状。
     */
    private String dynamicBodyOf(Element el, List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) appendSerialized(sb, c);
        String body = sb.toString();
        if (!DYNAMIC_TAG.matcher(body).find() && !body.contains("${") && !body.contains("#{")) return null;
        if (body.length() > MAX_DYNAMIC_BODY) {
            warnings.add("动态原文 " + body.length() + " 字符超过 " + MAX_DYNAMIC_BODY + "，未存；直测只能按拍平模板人工补条件");
            return null;
        }
        return body;
    }

    private static final Pattern DYNAMIC_TAG =
            Pattern.compile("<(if|choose|when|otherwise|where|set|trim|foreach|bind|selectKey)\\b");

    /** DOM → XML 文本：CDATA 原样保留，元素与属性按 XML 规则转义，注释/PI 不入拼装 */
    private void appendSerialized(StringBuilder sb, Node n) {
        switch (n.getNodeType()) {
            case Node.CDATA_SECTION_NODE -> sb.append("<![CDATA[").append(n.getNodeValue()).append("]]>");
            case Node.TEXT_NODE -> appendEscaped(sb, n.getNodeValue());
            case Node.ELEMENT_NODE -> {
                Element e = (Element) n;
                sb.append('<').append(e.getTagName());
                NamedNodeMap attrs = e.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    Attr a = (Attr) attrs.item(i);
                    sb.append(' ').append(a.getName()).append("=\"")
                            .append(a.getValue().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"))
                            .append('"');
                }
                sb.append('>');
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) appendSerialized(sb, c);
                sb.append("</").append(e.getTagName()).append('>');
            }
            default -> { }
        }
    }

    private void appendEscaped(StringBuilder sb, String text) {
        sb.append(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
    }

    /**
     * N22：${} → 安全字面量。若占位符本就包在引号里（'${x}' 或 '%${x}%'），只替换为 DOLLAR，
     * 否则补上引号替换为 'DOLLAR'——盲目补引号会产生 ''DOLLAR'' / '%'DOLLAR'%' 双重引号，直接 ORA-00933。
     * 判定需跳过紧邻的 %：like '%${x}%' 场景占位符左边挨着的是 % 而不是引号。
     */
    private String replaceDollarLiterals(String sql) {
        Matcher m = Pattern.compile("\\$\\{[^{}]*\\}").matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int l = m.start() - 1;
            while (l >= 0 && sql.charAt(l) == '%') l--;
            int r = m.end();
            while (r < sql.length() && sql.charAt(r) == '%') r++;
            boolean quoted = l >= 0 && sql.charAt(l) == '\'' && r < sql.length() && sql.charAt(r) == '\'';
            m.appendReplacement(sb, Matcher.quoteReplacement(quoted ? "DOLLAR" : "'DOLLAR'"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 折叠空白与换行，便于展示与解析 */
    private String normalize(String sql) {
        return Arrays.stream(sql.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    /**
     * N22：入库前最后一道清洗。逐轮消化残余的 #{} / ${}（含嵌套截断残片），
     * #{} 视为绑定参数补进 params，${} 用 'DOLLAR' 安全字面量替代；清洗后仍见裸
     * "#{" 或 "${" 则记警告，模板标记为需人工处理。
     */
    private String scrubResidue(String sql, Map<String, String> params, List<String> warnings) {
        String out = sql;
        int guard = 0;
        boolean scrubbed = false;
        while (guard++ < 50) {
            Matcher h = Pattern.compile("#\\{([^{}]*)}").matcher(out);
            if (h.find()) {
                params.putIfAbsent(h.group(1).trim().isEmpty() ? "residue" + guard : simpleName(h.group(1)), "string");
                out = h.replaceFirst("?");
                scrubbed = true;
                continue;
            }
            Matcher d = Pattern.compile("\\$\\{([^{}]*)}").matcher(out);
            if (d.find()) {
                out = replaceDollarLiterals(out);
                scrubbed = true;
                continue;
            }
            break;
        }
        if (scrubbed) {
            warnings.add("模板含嵌套/畸形占位符，已自动清洗为 ? 与 'DOLLAR'，直测前请人工核对语义");
        }
        // 无闭合花括号的极端残片：不再猜测，如实告知
        if (out.contains("#{") || out.contains("${")) {
            warnings.add("模板仍残留未闭合的 # 或 $ 占位符，需人工修正后才能直测");
        }
        return out;
    }

    /** #{user.name,jdbcType=VARCHAR} → user.name → name */
    private String simpleName(String expr) {
        String s = expr.split(",")[0].trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
