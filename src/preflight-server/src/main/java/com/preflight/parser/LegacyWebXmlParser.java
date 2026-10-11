package com.preflight.parser;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 传统 J2EE 工程适配（WebLogic/WebSphere 时代的 web.xml）：
 * 解析 servlet-mapping 产出接口草稿，让老工程也能进入学习闭环（无参数信息，置信度低，人工确认补全）。
 */
@Component
public class LegacyWebXmlParser {

    public List<ExtractedUnit> extract(Path file) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
        Document doc = builder.parse(file.toFile());

        Map<String, String> servletClass = new HashMap<>();
        NodeList servlets = doc.getElementsByTagName("servlet");
        for (int i = 0; i < servlets.getLength(); i++) {
            Element s = (Element) servlets.item(i);
            String name = child(s, "servlet-name");
            String cls = child(s, "servlet-class");
            if (name != null && cls != null) servletClass.put(name, cls);
        }

        List<ExtractedUnit> units = new ArrayList<>();
        NodeList mappings = doc.getElementsByTagName("servlet-mapping");
        for (int i = 0; i < mappings.getLength(); i++) {
            Element mp = (Element) mappings.item(i);
            String name = child(mp, "servlet-name");
            String pattern = child(mp, "url-pattern");
            if (name == null || pattern == null) continue;
            String cls = servletClass.getOrDefault(name, name);
            units.add(new ExtractedUnit("HTTP_ENDPOINT", cls, "ANY", pattern,
                    List.of(), List.of(), relativeWebInfPath(file), 0, 0,
                    List.of("老工程 servlet 映射：无参数信息，请人工补全入参"),
                    "servlet: " + cls + " → " + pattern, null, null));
        }
        return units;
    }

    /** web.xml 在仓库深处（如 WEB-INF/），绑定路径需回退到仓库相对位置 */
    private String relativeWebInfPath(Path file) {
        Path p = file;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 3 && p.getParent() != null; i++) {
            parts.add(0, p.getFileName().toString());
            p = p.getParent();
        }
        return String.join("/", parts);
    }

    private String child(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent().trim();
    }
}
