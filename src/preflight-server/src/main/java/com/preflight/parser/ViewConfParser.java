package com.preflight.parser;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 出参视图声明解析（第卅四轮）。老工程在 {@code WEB-INF/conf/metaData/viewConf/*.xml} 里，
 * 用 {@code <conf req="[{'bex_codes':'48002026'}]">} 把一份出参契约绑到某个 service/bex 码上，
 * 再用 {@code <col_item field="DWMC" title="坐席部门"/>} 逐个列出出参字段与中文名
 * （{@code <qry_item>} 是同一个视图上的查询条件，属入参侧）。
 *
 * 这套声明此前一直没人学：入参字典只读了 serviceConf / bexConf / atomConf。
 * 纯静态解析、读取期现算，不落库。
 */
public final class ViewConfParser {

    /** 一个字段：名字 + 中文名 + 界面控件类型（datagrid 列 / datebox / 文本框） */
    public record OutField(String field, String title, String type) {}

    /** 一条视图声明：绑到哪个码（kind=service|bex）、出参列、查询条件列、证据文件 */
    public record ViewDef(String viewId, String title, String kind, String code,
                          List<OutField> cols, List<OutField> qrys, String source) {}

    private static final Pattern REQ_CODE = Pattern.compile(
            "\\b(service|bex_codes)\\s*[=:]\\s*['\"]([^'\"]{1,64})['\"]");
    private static final long MAX_XML_BYTES = 4L * 1024 * 1024;

    /** 解析一个仓库工作副本下的全部视图声明；没有 viewConf 目录就返回空，不抛。 */
    public List<ViewDef> parseRepo(Path repoRoot) {
        List<ViewDef> out = new ArrayList<>();
        if (repoRoot == null) return out;
        Path dir = repoRoot.resolve("WEB-INF/conf/metaData/viewConf");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .toList()) {
                out.addAll(parseFile(p));
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public List<ViewDef> parseFile(Path file) {
        List<ViewDef> out = new ArrayList<>();
        try {
            if (Files.size(file) > MAX_XML_BYTES) return out;
            for (Element v : elements(file, "view")) {
                String id = attr(v, "id");
                if (id == null) id = attr(v, "name");
                String viewTitle = attr(v, "title");
                List<OutField> cols = new ArrayList<>();
                List<OutField> qrys = new ArrayList<>();
                for (Element c : children(v, "col_item")) {
                    String f = attr(c, "field");
                    if (f != null) cols.add(new OutField(f, attr(c, "title"), attr(c, "type")));
                }
                for (Element c : children(v, "qry_item")) {
                    String f = attr(c, "field");
                    if (f != null) qrys.add(new OutField(f, attr(c, "title"), attr(c, "type")));
                }
                String rel = relOf(file);
                for (String[] kc : reqCodes(v)) {
                    out.add(new ViewDef(id, viewTitle, kc[0], kc[1], cols, qrys, rel));
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /**
     * 一个视图绑到哪些码上，两种写法都要认（第卅四轮实测：work_app 146 个 view 里
     * {@code <conf service="...">} 直写 85 处、{@code <conf req="[{'service':'...'}]">} 57 处，
     * 只认 req 会漏掉大半）；req 里一次可以绑多个码，按出现顺序去重。
     */
    private List<String[]> reqCodes(Element view) {
        List<String[]> pairs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element c : children(view, "conf")) {
            for (String attrName : List.of("service", "bex_codes", "bex_code", "service_code")) {
                String v = attr(c, attrName);
                if (v == null) continue;
                addCode(seen, pairs, attrName.startsWith("bex") ? "bex" : "service", v);
            }
            String req = attr(c, "req");
            if (req == null) continue;
            Matcher m = REQ_CODE.matcher(req);
            while (m.find()) {
                addCode(seen, pairs, "service".equals(m.group(1)) ? "service" : "bex", m.group(2));
            }
        }
        return pairs;
    }

    private void addCode(Set<String> seen, List<String[]> pairs, String kind, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty() || code.length() > 64) return;
        if (seen.add(kind + "|" + code)) pairs.add(new String[]{kind, code});
    }

    // ---------- 与 KjdpDispatchAdapter 同一套容错口径：UTF-8 失败退 GBK、吞掉外部实体 ----------

    private List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) out.add((Element) list.item(i));
        return out;
    }

    private List<Element> elements(Path file, String tag) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder b = dbf.newDocumentBuilder();
            b.setEntityResolver((p, s) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            List<Element> out = new ArrayList<>();
            NodeList list = b.parse(in).getElementsByTagName(tag);
            for (int i = 0; i < list.getLength(); i++) out.add((Element) list.item(i));
            return out;
        }
    }

    private String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 证据路径只留"从 metaData 这一段起"，与入参字典的 relOf 同样避免暴露整台机器目录 */
    private String relOf(Path p) {
        List<String> parts = new ArrayList<>();
        Path cur = p;
        for (int i = 0; i < 5 && cur != null; i++) {
            Path name = cur.getFileName();
            if (name == null) break;
            parts.add(0, name.toString());
            if ("metaData".equals(name.toString())) break;
            cur = cur.getParent();
        }
        return String.join("/", parts);
    }
}
