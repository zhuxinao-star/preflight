package com.preflight.learn;

import com.preflight.exec.ColumnValueCatalogService;
import com.preflight.exec.ColumnValueCatalogService.ColMeta;
import com.preflight.parser.DtoSchemaExpander;
import com.preflight.parser.InterfaceCustomerDispatchAdapter;
import com.preflight.parser.JavaSourceIndex;
import com.preflight.parser.ProcBody;
import com.preflight.parser.SqlColumns;
import com.preflight.parser.SqlParamClause;
import com.preflight.parser.DispatchAdapter.DispatchParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按真实代码推算一个功能号的出入参（第卅五轮）。
 *
 * <p>出发点是用
 * 户原话：「出参入参不能根据 viewConf 的配置，几乎没有几个接口会把出入参配置到 xml 里面，需要结合
 * 真实代码的逻辑」，以及「存储过程可以先去数据库目录看有没有相应的过程代码」。所以这条链是：
 *
 * <pre>
 * bex 码 → bexConf 的 attr1 → mybatis 里那条语句
 *        ├─ select 型：语句的列清单＝出参，#{}/${}/test 里的键＝入参
 *        └─ call 型：→ DB 仓库盘上的过程/包体源码
 *                     ├─ 形参（含 -- 中文说明）＋调用实参的位序＝入参怎么进去的
 *                     └─ OPEN 游标 FOR SELECT… ＝出参到底有哪些列
 * service 码 → serviceConf 的 service_business → 逐个按 bex 链再算一遍
 * </pre>
 *
 * <p>第四十二轮补第二条链：「一个功能号一条真实路径」那类 Spring 仓（统一访问转发中心）根本没有
 * bexConf，它的契约分布在四个地方——{@code interface-customer.properties} 的参数清单、控制器方法上的
 * 映射、方法体里 {@code jsonObj.getString("…")} 读出来的键、以及最后落到的那条 MyBatis 语句
 * （{@code DAO 接口全限定名＝mapper namespace、方法名＝语句 id}）。所以这里沿着 Java 源码一跳一跳走到语句，
 * 走到语句就按语句的列给（high），走不到才退回按返回类型 VO 平铺（medium，并写清这是退路）。
 *
 * <p>读取期现算、不落库；每一步都带文件与行号，认不出来的照实写进 unresolved，不用空表冒充「没有出参」。
 */
@Service
public class CodeChainService {

    /** 出参上限：一个接口正常不会几百列，超了就是动态语句还原出的片段被重复计入 */
    static final int FIELD_CAP = 400;

    /** 一次推演最多往实现里走几跳：控制器→服务→实现→DAO 已经四跳，再深的不追 */
    private static final int HOP_CAP = 6;
    /** 一个方法体里的调用最多跟几个：一个服务方法常调十几个 DAO，追少了会把真正那条语句漏在后面 */
    private static final int CALL_CAP = 24;
    /** 「这里没查到」最多说这么多条：真缺陷是查不到，但几百条同源的话会把真那条淹掉 */
    private static final int MISS_CAP = 20;

    private final JdbcTemplate jdbc;
    private final MapperIndex mapper;
    private final DbSourceIndex dbSource;
    private final CallPathIndex callPath;
    /** 列的中文说明与库内类型的唯一出处；离线探针传 null，此时只少说明、不少字段 */
    private final ColumnValueCatalogService catalog;

    public CodeChainService(JdbcTemplate jdbc, MapperIndex mapper, DbSourceIndex dbSource,
                            CallPathIndex callPath, ColumnValueCatalogService catalog) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.dbSource = dbSource;
        this.callPath = callPath;
        this.catalog = catalog;
    }

    public Map<String, Object> forCode(long projectId, String kind, String code) {
        Ctx c = new Ctx();
        c.projectId = projectId;
        resolve(projectId, kind, code, c, 0);
        fillTitles(c);
        if (!c.metaMiss.isEmpty()) {
            // 「这一列为什么没有中文说明」不许让人猜：两条通路都取不到才点名到这里，
            // 说清哪两条、各怎么补（第四十三轮补了盘上建表脚本这条永久通路）。
            step(c, "这几张表的中文说明两条通路都取不到——库内没学过它的列注释，盘上数据库仓库的建表脚本里"
                    + "也没有这张表，所以部分字段只带得出名、带不出中文说明："
                    + String.join("、", c.metaMiss) + "（【知识库 → 元数据反哺】连库学过这几张表就有了）",
                    "", 0, "medium");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("kind", kind);
        out.put("code", code);
        out.put("steps", c.steps);
        out.put("outputs", c.outputs.size() > FIELD_CAP ? c.outputs.subList(0, FIELD_CAP) : c.outputs);
        out.put("outputsTotal", c.outputs.size());
        out.put("inputs", c.inputs.size() > FIELD_CAP ? c.inputs.subList(0, FIELD_CAP) : c.inputs);
        out.put("inputsTotal", c.inputs.size());
        out.put("unresolved", c.unresolved);
        out.put("repos", c.reposSeen);
        out.put("note", note(c));
        return out;
    }

    // ---------- 主链 ----------

    /**
     * 最后一趟兜底：给「这一行的中文说明」再找一次出处（第四十三轮）。
     *
     * <p>用户口径「字段意思也没有」。实测最常见的一类是这种：入参是从 Java 方法体里按键取值学出来的
     * （{@code jsonObj.getString("dtxx")}），那条证据只说得出「代码读了这个键」，天生没有中文；
     * 而这个名字在同一条链后面落到的语句里就是 {@code t_hcgl_hf_khmddy.dtxx} 这一列，列注释里写着
     * 「动态信息展示列[...]」。名字对上、这条链碰到的表里认得到这一列、且各处给出的是同一句说明才补；
     * 两张表给出不同说明就不补——把别表的同名列凑过来，会变成推给 YApi 的假说明。
     *
     * <p>补的是「说明的出处」，不是「字段的出处」：行的 {@code via} 一字不动（那仍是按键取值），
     * 说明从哪一列来由 {@code titleVia} 自己说，界面上两件事分开显示。
     */
    private void fillTitles(Ctx c) {
        if (c.metas.isEmpty() || catalog == null) return;
        for (Map<String, Object> row : c.inputs) fillTitle(c, row);
        for (Map<String, Object> row : c.outputs) fillTitle(c, row);
    }

    private void fillTitle(Ctx c, Map<String, Object> row) {
        if (!commentOf(row).isBlank()) return;
        String col = String.valueOf(row.getOrDefault("field", ""));
        String hit = null;
        String from = "";
        for (Meta m : c.metas) {
            ColMeta cm = m.byName(col);
            String t = cm == null ? null : cm.comment();
            if (t == null || t.isBlank()) continue;
            if (hit != null && !hit.equals(t)) return;   // 两处给出不同说明：认不准，一律不补
            if (hit == null) from = m.tableOf(col);
            hit = t;
        }
        if (hit == null) return;
        row.put("title", hit);
        row.put("titleVia", from.isBlank() ? "这一列的中文说明" : from + "." + col.toLowerCase(Locale.ROOT) + " 的列注释");
    }

    private static String commentOf(Map<String, Object> row) {
        Object t = row.get("title");
        return t == null ? "" : String.valueOf(t).trim();
    }

    private static class Ctx {
        long projectId;
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Map<String, Object>> inputs = new ArrayList<>();
        List<Map<String, Object>> unresolved = new ArrayList<>();
        List<String> reposSeen = new ArrayList<>();
        Set<String> seenOut = new LinkedHashSet<>();
        Set<String> seenIn = new LinkedHashSet<>();
        Set<String> walked = new LinkedHashSet<>();
        Set<String> seenStmt = new LinkedHashSet<>();
        /** 这条链想读列说明、但本地 {@code table_meta} 里没这张表：界面要能说清「不是没有含义，是还没反哺」 */
        Set<String> metaMiss = new LinkedHashSet<>();
        /** 这条链经过的每一条语句（带各自的表与列说明出处）：给「按键取值」那种入参补中文说明用 */
        List<Meta> metas = new ArrayList<>();
        /** 表名（小写）→ 该表的列说明：整条链共用，一张表只连本地库问一次 */
        Map<String, Map<String, ColMeta>> colCache = new LinkedHashMap<>();
    }

    private void resolve(long projectId, String kind, String code, Ctx c, int depth) {
        if (depth > 2) return;   // service → business → bex 就到头，防自引用死循环
        String gate = kind + "|" + code.toLowerCase(Locale.ROOT);
        if (!c.walked.add(gate)) return;
        for (Repo rp : repos(projectId)) {
            MapperIndex.Repo r = rp.repo;
            // 第四十二轮：一码一路径那类 Spring 仓（统一访问转发中心）压根没有 bexConf，
            // 它的契约在 Java 源码里，走另一条链；老工程没有 src/main/java，对它这条判断直接为假。
            if (callPath.looksLikeSpringRepo(r.root) && knowsCallPath(r, rp.name, code)) {
                callPathChain(projectId, r, rp.name, code, c);
                continue;
            }
            if ("service".equalsIgnoreCase(kind)) {
                MapperIndex.Service svc = mapper.service(r, code);
                if (svc == null) continue;
                step(c, "serviceConf 把「" + code + "」声明为服务"
                        + (svc.name() == null ? "" : "（" + svc.name() + "）")
                        + "，业务码 " + svc.business().size() + " 个", svc.file(), 0, "high");
                c.reposSeen.add(rp.name);
                if (svc.business().isEmpty()) {
                    miss(c, "服务「" + code + "」在 serviceConf 里没有 service_business，参数只能看 atom 的实现类",
                            svc.file());
                }
                for (String b : svc.business()) {
                    resolveBex(projectId, r, rp.name, b, c, depth + 1);
                    if (mapper.bex(r, b) == null) resolveAtom(r, b, c);
                }
                continue;
            }
            resolveBex(projectId, r, rp.name, code, c, depth);
        }
    }

    /** bex 码：bexConf 的 attr1 → 语句；attr1 缺失或与码同名时按码找语句 */
    private void resolveBex(long projectId, MapperIndex.Repo r, String repoName, String code, Ctx c, int depth) {
        MapperIndex.Bex b = mapper.bex(r, code);
        String stmtId = null;
        if (b != null) {
            c.reposSeen.add(repoName);
            stmtId = b.attr1() == null || b.attr1().isBlank() ? null : b.attr1();
            step(c, "bexConf 声明「" + code + "」"
                    + (b.name() == null ? "" : "（" + b.name() + "）")
                    + "，attr1=" + (stmtId == null ? "（没写）" : stmtId)
                    + "，page_mode=" + (b.pageMode() == null ? "没写" : b.pageMode())
                    + "，bex_type=" + (b.bexType() == null ? "没写" : b.bexType()), b.file(), 0, "high");
            for (MapperIndex.Param p : b.params()) {
                in(c, p.code(), p.label(), jsonType(p.dataType()), "bexConf 入参声明", p.file(), 0, null, "high");
            }
            if (b.params().isEmpty()) {
                miss(c, "bexConf 里「" + code + "」没有 <param> 声明——老工程多数如此，入参以下面语句里真正读的键为准",
                        b.file());
            }
        }
        if (stmtId == null) stmtId = code;
        List<MapperIndex.Stmt> stmts = mapper.statements(r, stmtId);
        if (stmts.isEmpty() && !stmtId.equalsIgnoreCase(code)) stmts = mapper.statements(r, code);
        if (stmts.isEmpty()) {
            // 只在这个仓库真的有 mybatis / bexConf 目录时才说「查不到」：DB 仓库压根没有这些目录，
            // 对它报一句就是假消息，而且会把真那条原因淹掉。
            if (b != null && mapper.hasDir(r, "mybatis")) {
                miss(c, "attr1「" + stmtId + "」在这个仓库的 WEB-INF/classes/mybatis 里没有对应语句"
                        + "（可能在别的仓库、jar 内，或这条 bex 已废弃）", b.file());
            } else if (depth == 0 && b == null && mapper.hasDir(r, "bexConf") && mapper.hasDir(r, "mybatis")) {
                miss(c, "这个仓库的 bexConf 里没有「" + code + "」，也没找到同名语句", "");
            }
            return;
        }
        if (stmts.size() > 1) {
            miss(c, "语句 id「" + stmtId + "」在这个仓库里有 " + stmts.size() + " 份定义，下面用的是第一份", "");
        }
        statement(projectId, r, stmts.get(0), c);
    }

    // ---------- 第二条链：一码一路径（统一访问转发中心） ----------

    /** 这个仓的那本索引里到底认得这个码吗——不认得就别走，硬走只会产出一屏「查不到」 */
    private boolean knowsCallPath(MapperIndex.Repo r, String repoName, String code) {
        CallPathIndex.Repo cr = callPath.forRepo(r.root, repoName);
        return cr != null && (!callPath.endpoints(cr, code).isEmpty() || callPath.serviceDecl(cr, code) != null);
    }

    /**
     * 一个功能号在 Spring 仓里的完整推演：properties 声明（入参，high）→ 控制器方法 → 逐跳实现
     * → DAO 接口对应的 mapper 语句（出参，high）；走不到语句时按返回类型 VO 平铺（medium，并写明是退路）。
     */
    private void callPathChain(long projectId, MapperIndex.Repo mr, String repoName, String code, Ctx c) {
        CallPathIndex.Repo cr = callPath.forRepo(mr.root, repoName);
        if (cr == null) return;
        c.reposSeen.add(repoName);
        InterfaceCustomerDispatchAdapter.ServiceDecl sd = callPath.serviceDecl(cr, code);
        if (sd != null) {
            InterfaceCustomerDispatchAdapter.Decl decl = InterfaceCustomerDispatchAdapter.parseDecl(sd);
            step(c, "转发中心声明 service_id." + code + "＝" + brief(sd.spec())
                    + "（裸名＝必填、名:值＝默认值、|| 之后是条件规则）", sd.file(), sd.line(), "high");
            for (DispatchParam p : decl.params()) {
                in(c, p.key(), p.label() == null ? "" : p.label(), p.type() == null ? "string" : p.type(),
                        "interface-customer.properties 参数清单", sd.file(), sd.line(),
                        p.def() == null ? "声明里没给默认值＝必填" : "声明里的默认值 " + p.def(), "high");
            }
            for (int i = 1; i < decl.evidence().size(); i++) {   // 第 0 条就是上面那句声明
                step(c, decl.evidence().get(i), sd.file(), sd.line(), "high");
            }
        }
        List<CallPathIndex.Endpoint> eps = callPath.endpoints(cr, code);
        if (eps.isEmpty()) {
            miss(c, "properties 里声明了「" + code + "」，但这个仓的 src/main/java 里没有映射到它的方法"
                    + "——请求路径与业务参数都没学到（控制器可能在别的模块或 jar 里）", sd == null ? "" : sd.file());
            return;
        }
        if (eps.size() > 1) {
            miss(c, "「" + code + "」在 " + eps.size() + " 个控制器方法里都映射了，下面走的是 "
                    + eps.get(0).cls() + "#" + eps.get(0).method(), eps.get(1).file());
        }
        CallPathIndex.Endpoint ep = eps.get(0);
        step(c, "请求路径 " + ep.entryPath() + "（" + (ep.verb().isBlank() ? "动词未声明" : ep.verb())
                + "）出自 " + ep.cls() + "#" + ep.method(), ep.file(), ep.line(), "high");
        CallPathIndex.Cls cls = clsAt(cr, ep.cls(), ep.pkg());
        if (cls == null) {
            miss(c, "控制器类 " + ep.cls() + " 的源码没在这个仓里读到", ep.file());
            return;
        }
        hop(projectId, mr, cr, cls, ep.method(), 0, c);
        if (c.outputs.isEmpty()) returnTypeFallback(cr, cls, ep, c);
    }

    /**
     * 往实现里走一跳：方法体里读的键＝入参，调到的 DAO 接口方法＝那条语句＝出参。
     *
     * <p>只认盘上写死了的东西：接收者必须是本类声明的字段（{@code @Autowired} 注入的服务／DAO），
     * 它的类型要么能在本仓找到类，要么全限定名正好是某个 mapper 的 namespace。局部变量、静态工具类、
     * JDK 类型一律放过——在这里编一次「大概调了它」，界面上就会把编出来的字段当成接口出参。
     */
    private void hop(long projectId, MapperIndex.Repo mr, CallPathIndex.Repo cr, CallPathIndex.Cls cls,
                     String method, int depth, Ctx c) {
        if (cls == null || method == null || method.isBlank()) return;
        if (depth > HOP_CAP) {
            miss(c, "超过 " + HOP_CAP + " 层还没走到 SQL 语句，这一路没继续追（最后一跳是 "
                    + cls.simpleName() + "#" + method + "）", cls.file());
            return;
        }
        if (!c.walked.add("j|" + cls.simpleName() + "#" + method)) return;   // 同一个方法只走一次，防接口↔实现互调
        List<CallPathIndex.Method> ms = callPath.methods(cls, method);
        if (ms.isEmpty()) {
            // 接口方法本来就没有方法体：能按 namespace 落到语句就用语句，落不到才说「这里断了」
            if (daoStatement(projectId, mr, cls, method, c)) return;
            miss(c, cls.simpleName() + "#" + method + " " + (cls.isInterface()
                    ? "是接口方法，本仓既没有实现类也没有 namespace＋语句 id 与它同名的 mapper"
                    : "在这个类里没有方法体（可能继承自父类，父类源码不在本仓）"), cls.file());
            return;
        }
        for (CallPathIndex.Method m : ms) {
            step(c, "跳到 " + m.cls() + "#" + m.name() + "：读了 " + m.jsonKeys().size() + " 个键、有 "
                    + m.calls().size() + " 处带名字的调用", m.file(), m.line(), "high");
            for (String k : m.jsonKeys()) {
                in(c, k, "", "string", m.cls() + "#" + m.name() + " 里按这个键取值", m.file(), m.line(),
                        null, "medium");
            }
            int followed = 0;
            for (CallPathIndex.Call call : m.calls()) {
                if (followed++ >= CALL_CAP) {
                    miss(c, m.cls() + "#" + m.name() + " 里的调用多于 " + CALL_CAP + " 处，只追了前 "
                            + CALL_CAP + " 处", m.file(), m.line());
                    break;
                }
                follow(projectId, mr, cr, cls, call, depth, c);
            }
        }
    }

    /** 一次调用：先看它是不是 DAO（→语句），再看能不能在本仓换出实现类（→下一跳） */
    private void follow(long projectId, MapperIndex.Repo mr, CallPathIndex.Repo cr, CallPathIndex.Cls cls,
                        CallPathIndex.Call call, int depth, Ctx c) {
        String recv = call.recv();
        if (recv.equals("this") || recv.equals(cls.simpleName())) {
            hop(projectId, mr, cr, cls, call.name(), depth + 1, c);   // 同类自调用：不换类，只换方法
            return;
        }
        String fieldType = cls.fields().get(recv);
        if (fieldType == null) return;                    // 局部变量／静态工具／父类字段：不猜类型
        String simple = JavaSourceIndex.baseType(fieldType);
        if (simple.isEmpty()) return;
        if (daoStatement(projectId, mr, qualifiedOf(cls, simple), call.name(), c)) return;
        CallPathIndex.Cls target = implOf(cr, cls, simple);
        if (target == null) {
            // 只对「一看就该有实现」的接收者说话：否则一个仓里的 StringUtils 也会来一句查不到
            if (looksImplementable(simple) && c.unresolved.size() < MISS_CAP) {
                miss(c, "调用了 " + recv + "（" + simple + "#" + call.name() + "），但这个仓的 src/main/java 里"
                        + "没有它的源码，也没有 namespace 与它同名的 mapper——这一跳断了", cls.file());
            }
            return;
        }
        hop(projectId, mr, cr, target, call.name(), depth + 1, c);
    }

    /**
     * 「DAO 接口全限定名＝mapper namespace、方法名＝语句 id」这条对应关系。
     *
     * <p>MyBatis 的 mapper 接口可以不写实现类，全靠这层同名对应；Spring 工程里绝大多数出参就是这么来的。
     * namespace 有两种写法（全限定名／短名），所以两边都试一次。
     */
    private boolean daoStatement(long projectId, MapperIndex.Repo mr, CallPathIndex.Cls cls, String id, Ctx c) {
        return daoStatement(projectId, mr, cls == null ? null : qualifiedOfSelf(cls), id, c)
                || daoStatement(projectId, mr, cls == null ? null : cls.simpleName(), id, c);
    }

    private boolean daoStatement(long projectId, MapperIndex.Repo mr, String namespace, String id, Ctx c) {
        if (mr == null || namespace == null || namespace.isBlank() || id == null || id.isBlank()) return false;
        List<MapperIndex.Stmt> ss = mapper.statements(mr, namespace, id);
        if (ss.isEmpty()) return false;
        // 一条事实一个出口：链上两条跳撞到同一条语句时（handler 和 service 都调同一个 DAO 方法），
        // 第二遍既说不出新字段，又会把「落到 mapper」那行打两遍，界面看着像两条不同的证据。
        if (!c.seenStmt.add(namespace.toLowerCase(Locale.ROOT) + "|" + id.toLowerCase(Locale.ROOT))) return true;
        if (ss.size() > 1) {
            miss(c, "namespace「" + namespace + "」下语句 id「" + id + "」有 " + ss.size()
                    + " 份定义，下面用的是第一份", ss.get(1).file());
        }
        MapperIndex.Stmt s = ss.get(0);
        step(c, "落到 mapper：namespace=" + namespace + "、id=" + id
                + "（DAO 接口的全限定名＝mapper 的 namespace，方法名＝语句 id）", s.file(), 0, "high");
        if (!"select".equalsIgnoreCase(s.tag())) {
            // 链上经过写语句＝这个接口顺手改了哪张表，是一份事实；但它的列不是调用方给的入参
            // （updateByPrimaryKeySelective 那种 <if test="列 != null"> 会把整个 VO 当成入参灌进来）。
            // 真正的入参以 properties 声明与代码按键取值为准，这里只报到「经过」为止。
            step(c, "同一条链上还有写语句 <" + s.tag() + " id=\"" + s.id() + "\">：它改的是这张表的数据，"
                    + "列名不算这个接口的入参，所以没往参数表里记", s.file(), 0, "high");
            return true;
        }
        statement(projectId, mr, s, c);
        return true;
    }

    /** 类简名 + 包名 → 唯一声明；同名多个时按包名挑，挑不出用第一个（调用方自己已经说明用了哪条） */
    private CallPathIndex.Cls clsAt(CallPathIndex.Repo cr, String simpleName, String pkg) {
        List<CallPathIndex.Cls> list = callPath.classes(cr, simpleName);
        if (list.isEmpty()) return null;
        if (pkg != null && !pkg.isEmpty()) {
            for (CallPathIndex.Cls x : list) if (pkg.equals(x.pkg())) return x;
        }
        return list.get(0);
    }

    /**
     * 字段类型 → 真正要跳进去的那个类。
     *
     * <p>Spring 工程最常见的写法是「接口字段 + {@code XxxImpl} 实现」，接口里没有方法体，
     * 所以先按 import 的全限定名找、找不到再按同包找、最后才试 {@code 接口名 + Impl} 这个约定。
     * 三条都落不到返回 null，由调用方说清这一跳断了——不猜第三个候选。
     */
    private CallPathIndex.Cls implOf(CallPathIndex.Repo cr, CallPathIndex.Cls from, String simpleName) {
        CallPathIndex.Cls direct = clsAt(cr, simpleName, qualifiedInImports(from, simpleName));
        if (direct != null && !direct.isInterface()) return direct;
        CallPathIndex.Cls impl = clsAt(cr, simpleName + "Impl", null);
        if (impl != null) return impl;
        return direct;
    }

    /** 调用处 import 里那个类的包名（没有 import 命中就返回 null，交回按简名找） */
    private static String qualifiedInImports(CallPathIndex.Cls from, String simpleName) {
        String fq = CallPathIndex.qualified(from, simpleName);
        if (fq == null || from.imports() == null) return null;
        for (String i : from.imports()) {
            if (i.equals(fq)) return fq.substring(0, fq.lastIndexOf('.'));
        }
        return null;
    }

    /** 接收者字段的类型全名：import 命中优先，否则按「同包同名」这个最常见写法推 */
    private static String qualifiedOf(CallPathIndex.Cls from, String simpleName) {
        return CallPathIndex.qualified(from, simpleName);
    }

    private static String qualifiedOfSelf(CallPathIndex.Cls cls) {
        return cls.pkg() == null || cls.pkg().isEmpty() ? cls.simpleName() : cls.pkg() + "." + cls.simpleName();
    }

    /** 这个名字像不像「该有实现类／mapper」的东西：只用来决定要不要说那句「这一跳断了」 */
    private static boolean looksImplementable(String simpleName) {
        String s = simpleName.toLowerCase(Locale.ROOT);
        return s.endsWith("dao") || s.endsWith("mapper") || s.endsWith("service") || s.endsWith("manager")
                || s.endsWith("handler") || s.endsWith("client") || s.endsWith("repository");
    }

    /**
     * 走不到语句时的退路：按入口方法返回类型把 VO 字段平铺出来。
     *
     * <p>这一档只能标 medium：字段名与类型是盘上声明，可信；但这一列接口实际返不返要看那条语句，
     * 所以它替代不了语句那条证据。{@code XxxListVo extends Xxx} 那种「子类只加两个字段、业务列全在父类」
     * 的写法靠 {@link DtoSchemaExpander#flatten} 沿继承链收全——只遍历子类字段会把出参整个看漏。
     */
    private void returnTypeFallback(CallPathIndex.Repo cr, CallPathIndex.Cls cls, CallPathIndex.Endpoint ep, Ctx c) {
        for (CallPathIndex.Method m : callPath.methods(cls, ep.method())) {
            String ret = m.returnType() == null ? "" : m.returnType().trim();
            String elem = elementOf(ret);
            if (elem.isEmpty() || "void".equalsIgnoreCase(elem)) continue;
            if (!callPath.java(cr).knowsClass(elem)) {
                miss(c, "这条链没落到 SQL 语句，返回类型 " + ret + " 的源码也不在本仓——出参认不出来，"
                        + "不是「这个接口没有出参」", m.file(), m.line());
                continue;
            }
            boolean collection = !elem.equalsIgnoreCase(JavaSourceIndex.baseType(ret));
            List<DtoSchemaExpander.FlatField> fs =
                    new DtoSchemaExpander(callPath.java(cr)).flatten(elem, cls.imports());
            if (fs.isEmpty()) {
                miss(c, "返回类型 " + elem + " 在本仓有源码，但一个字段都没声明出来", m.file(), m.line());
                continue;
            }
            step(c, "这条链没落到 SQL 语句，出参按 " + m.cls() + "#" + m.name() + " 的返回类型 " + ret
                    + (collection ? "（集合，每条记录的字段如下）" : "") + " 平铺，含父类继承来的字段",
                    m.file(), m.line(), "medium");
            for (DtoSchemaExpander.FlatField f : fs) {
                out(c, f.name(), f.description() == null ? "" : f.description(), jsonTypeOfJava(f.rawType()),
                        "返回类型 " + f.owner() + " 的字段（没走到语句，按声明给）", m.file(), m.line(), "medium");
            }
            return;
        }
    }

    /**
     * {@code ContReply<List<XxxVo>>}／{@code List<XxxVo>}／{@code XxxVo[]} → 真正承载出参的那个元素类型。
     * 取不到泛型参数时原样返回简名（外层就是 VO 的写法）。
     */
    private static String elementOf(String rawType) {
        String cur = JavaSourceIndex.baseType(rawType);
        List<String> args = JavaSourceIndex.typeArgs(rawType);
        for (int i = 0; i < 3 && !args.isEmpty(); i++) {
            String next = JavaSourceIndex.baseType(args.get(args.size() - 1));
            if (next.isEmpty() || next.equalsIgnoreCase(cur)) break;
            cur = next;
            args = JavaSourceIndex.typeArgs(cur);
        }
        return cur;
    }

    /** 一个 Java 声明类型 → JSON 类型：口径在 {@link JavaSourceIndex#jsonTypeOf}，这里只是本地短名 */
    private static String jsonTypeOfJava(String rawType) {
        return JavaSourceIndex.jsonTypeOf(rawType);
    }

    /**
     * 一条语句的「列 → 中文说明 / 库内类型」出处。
     *
     * <p>用户要的是字段意思，而 mapper 里只有列名：{@code wjmc} 这种缩写在盘上任何一处 JavaDoc 里都
     * 没写过中文，唯一有据可查的是【元数据反哺】拉回来的表列注释（{@code table_meta}）。表则由这条语句自己的
     * {@code FROM}/{@code JOIN} 定（复用第廿六轮那套别名识别，不再写第二套）。
     *
     * <p>三种拿不准一律返回 null、界面就不给说明：① 表达式带限定名但别名不在这条语句的 FROM 里；
     * ② 这张表压根没学过；③ 裸列名在这条语句的多张表里都有、说明还不一样。宁可少一列说明，也不能把
     * 别的表的同名列凑过来——那会被当成接口文档推给 YApi。
     */
    private final class Meta {
        private final long projectId;
        private final Ctx ctx;
        private final Map<String, String> alias;
        private final List<String> tables;

        Meta(Ctx ctx, String body) {
            this.projectId = ctx.projectId;
            this.ctx = ctx;
            this.alias = SqlParamClause.aliases(body);
            this.tables = new ArrayList<>(new LinkedHashSet<>(alias.values()));
            ctx.metas.add(this);   // 这条链碰过哪些语句、每张表有哪些列，最后一趟兜底要用（见 fillTitles）
        }

        /** 带限定名的列（{@code wj.wjmc}）；裸列走 {@link #byName} */
        ColMeta of(String expr, String label) {
            String e = expr == null ? "" : expr.trim();
            int dot = e.lastIndexOf('.');
            // aliases() 的键是大写归一（第廿六轮那套口径），这里跟它比就得同样大写
            if (dot > 0 && e.substring(0, dot).matches("[A-Za-z_]\\w*")
                    && e.substring(dot + 1).matches("[A-Za-z_]\\w*")) {
                String table = alias.get(e.substring(0, dot).toUpperCase(Locale.ROOT));
                if (table == null) return null;
                ColMeta m = cols(table).get(e.substring(dot + 1).toLowerCase(Locale.ROOT));
                if (m != null) return m;
            }
            return byName(label != null && !label.isBlank() ? label : tailOf(e));
        }

        /** 裸列名／参数键：只在这条语句的一张表里认得到才给，认得到两张且说明不同就不给 */
        ColMeta byName(String col) {
            String key = col == null ? "" : col.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || !key.matches("[a-z_][a-z0-9_$#]*")) return null;
            ColMeta hit = null;
            for (String t : tables) {
                ColMeta m = cols(t).get(key);
                if (m == null) continue;
                if (hit != null && !hit.equals(m)) return null;
                hit = m;
            }
            return hit;
        }

        /** 这一列在这条语句的哪张表里（补说明时要把出处说清；认不到给空串） */
        String tableOf(String col) {
            String key = col == null ? "" : col.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || !key.matches("[a-z_][a-z0-9_$#]*")) return "";
            for (String t : tables) if (cols(t).containsKey(key)) return t;
            return "";
        }

        private Map<String, ColMeta> cols(String table) {
            if (catalog == null) return Map.of();
            // 一张表的列只问一次：一条链上几十条语句、来回同一张 t_hcgl_hf_khmddy，逐条语句各查一遍
            // 等于把「补说明」变成整条链最慢的一步（这一轮的兜底趟要按每个空说明的字段查一遍列）
            Map<String, ColMeta> m = ctx.colCache.computeIfAbsent(table.toLowerCase(Locale.ROOT),
                    k -> catalog.columnsOf(projectId, table));
            if (m.isEmpty()) ctx.metaMiss.add(table);   // 「为什么这一列没有中文说明」要有出处，不能让人猜
            return m;
        }

        private static String tailOf(String expr) {
            String s = expr == null ? "" : expr.trim();
            int sp = s.lastIndexOf(' ');
            if (sp > 0) s = s.substring(sp + 1);   // 「dy.bzxx dybzxx」这种漏写 AS 的，尾巴才是键名
            int dot = s.lastIndexOf('.');
            return dot < 0 ? s : s.substring(dot + 1);
        }
    }

    /** 说明取不到就给空串：界面按「这一列没有中文说明」处理，比挂个假说明坏得少 */
    private static String comment(ColMeta m) {
        return m == null || m.comment() == null ? "" : m.comment();
    }

    /** 库内声明类型 → JSON 类型：只分数字／布尔／字符串，认不出的按字符串（宁可不给也不给假类型） */
    private static String jsonTypeOfDb(String dataType) {
        String s = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        if (s.startsWith("int") || s.startsWith("num") || s.startsWith("dec") || s.startsWith("floa")
                || s.startsWith("doub") || s.startsWith("serial") || s.startsWith("money")) return "number";
        if (s.equals("bit") || s.startsWith("bool")) return "boolean";
        return "string";
    }

    /**
     * 一条语句：select 型直接认列，call 型再进过程源码。
     *
     * <p>第四十二轮先内联 {@code <include>}：Spring 工程的 select 大量写成
     * {@code select <include refid="xxBaseColumnList"/> from …}，不内联就一个列名都认不出，
     * 按实现推会假报「这个接口没有出参」；内联不到的片段照实说，不静默吞。
     */
    private void statement(long projectId, MapperIndex.Repo mr, MapperIndex.Stmt s, Ctx c) {
        List<String> missing = new ArrayList<>();
        String body = mr == null ? s.body() : mapper.expanded(mr, s, missing);
        if (!missing.isEmpty()) {
            miss(c, "语句里的公共列片段没找到原文：<include refid=\"" + String.join("」「\"  ", missing)
                    + "\">——这段列名认不出（片段可能在别的仓库或 jar 里）", s.file());
        }
        String ref = SqlColumns.callRef(body);
        Meta meta = new Meta(c, body);
        step(c, "语句 <" + s.tag() + " id=\"" + s.id() + "\">"
                + (ref == null ? "（普通 SQL）" : "（调用存储过程 " + ref + "）")
                + (s.attrs().toLowerCase(Locale.ROOT).contains("callable") ? " · statementType=CALLABLE" : ""),
                s.file(), 0, "high");
        SqlColumns.bindings(body).forEach((k, v) -> {
            ColMeta m = meta.byName(k);
            in(c, k, comment(m), m == null ? "string" : jsonTypeOfDb(m.dataType()), v, s.file(), 0, null, "high");
        });

        if (ref == null) {
            for (SqlColumns.Column col : SqlColumns.columnsLoose(body)) {
                if ("star".equals(col.conf())) {
                    miss(c, "语句最外层是 SELECT *，列名要连库看表结构才知道；下面给的是内层能认出的列",
                            s.file());
                } else if (col.label() == null) {
                    miss(c, "有个表达式列没起别名，认不出键名：" + brief(col.expr()), s.file());
                } else {
                    ColMeta m = meta.of(col.expr(), col.label());
                    out(c, col.label(), comment(m), m == null ? "" : jsonTypeOfDb(m.dataType()),
                            "语句的列/别名", s.file(), 0, col.conf());
                }
            }
            if (SqlColumns.clean(body).isEmpty()) miss(c, "语句体是空的", s.file());
            return;
        }
        procedure(projectId, ref, body, s, c);
    }

    /** call 型：盘上的过程源码 → 形参与游标目标 */
    private void procedure(long projectId, String ref, String body, MapperIndex.Stmt s, Ctx c) {
        DbSourceIndex.Def d = dbSource.find(projectId, ref);
        if (d == null) {
            miss(c, "调用 " + ref + "，但盘上的 DB 仓库里没有这份过程代码"
                    + (dbSource.capped(projectId) ? "（该仓库对象文件过多，索引只列了前一批）" : "")
                    + "；出参只能连库执行一次看返回游标", s.file());
            return;
        }
        step(c, "过程 " + ref + " 的源码在 " + d.repoName() + " 的 " + d.file() + ":" + d.line()
                + "（" + d.kind() + "）", d.file(), d.line(), "high");
        String header = header(d.block());
        List<ProcBody.Param> ps = ProcBody.params(header);
        List<SqlColumns.Arg> args = SqlColumns.callArgs(body);
        Set<String> cursors = new LinkedHashSet<>();
        for (int i = 0; i < ps.size(); i++) {
            ProcBody.Param p = ps.get(i);
            String arg = i < args.size() ? args.get(i).raw() : "";
            if (p.mode().contains("OUT")) {
                if (p.type().toLowerCase(Locale.ROOT).contains("refcur")
                        || p.type().toLowerCase(Locale.ROOT).contains("cursor")
                        || p.name().matches("(?i)rc\\d*")) {
                    cursors.add(p.name());
                    continue;
                }
                out(c, p.name(), p.title(), "OUT " + p.type(), "过程出参位（第 " + (i + 1) + " 位）",
                        d.file(), d.line(), "high");
                continue;
            }
            in(c, p.name(), p.title(), p.type(), "过程形参（第 " + (i + 1) + " 位）",
                    d.file(), d.line(), arg.isBlank() ? null : "调用处给的是 " + brief(arg), "high");
        }
        if (cursors.isEmpty()) {
            miss(c, "过程 " + ref + " 的形参里没有 REF CURSOR 出参位，出参可能是标量或写回参数", d.file());
            return;
        }
        for (String cur : cursors) {
            List<ProcBody.Open> opens = ProcBody.opens(d.block(), cur);
            for (ProcBody.Open o : opens) {
                String sql = o.sql();
                String from = "过程里 OPEN " + cur + " FOR（" + o.shape() + "）";
                String conf = "direct".equals(o.shape()) ? "high" : "low";
                if ("dynamic".equals(o.shape())) {
                    sql = ProcBody.dynamic(d.block(), o.sql());
                    if (sql.isBlank()) {
                        miss(c, "游标 " + cur + " 打开的是变量 " + o.sql() + "，过程里没找到给它的赋值", d.file());
                        continue;
                    }
                } else if ("unknown".equals(o.shape())) {
                    miss(c, "游标 " + cur + " 的 OPEN 目标认不出来：" + brief(o.sql()), d.file());
                    continue;
                }
                int n = emitColumns(c, cur, sql, from + " 第 " + o.line() + " 行", conf, d.file(), o.line());
                if (n == 0) {
                    miss(c, "游标 " + cur + " 的 " + ("dynamic".equals(o.shape()) ? "动态语句" : "语句")
                            + "在 " + o.line() + " 行，但列名还原不出来（拼接里带变量或整段是 SELECT *）",
                            d.file(), o.line());
                }
            }
            // 同一游标可能两条路都有：正常路径由共享查询函数返回，EXCEPTION 分支才自己 OPEN 一条错误行
            // （实测 UP_MID_28102044 的 RC2 就是这样），所以两条都要走，不能认出错误列就收工。
            int hop = cursorFromFunction(projectId, d, cur, c);
            if (opens.isEmpty() && hop < 0) {
                miss(c, "过程 " + ref + " 里既没有 OPEN " + cur + " FOR，也没有「" + cur
                        + " := 某函数(…)」这种赋值——这个游标可能由更深的子过程打开", d.file());
            }
        }
    }

    /** 从一段 SQL 认列并记账，返回认出的列数；0 不代表没有出参，只代表这里认不出 */
    private int emitColumns(Ctx c, String cur, String sql, String via, String conf, String file, int line) {
        int n = 0;
        Meta meta = new Meta(c, sql);
        for (SqlColumns.Column col : SqlColumns.columnsLoose(sql)) {
            if (col.label() == null || "*".equals(col.label())) {
                miss(c, cur + " 这一列认不出键名：" + brief(col.expr()), file, line);
                continue;
            }
            ColMeta m = meta.of(col.expr(), col.label());
            out(c, col.label(), comment(m), m == null ? "" : jsonTypeOfDb(m.dataType()), via, file, line, conf);
            n++;
        }
        return n;
    }

    /**
     * 出参游标由共享查询函数返回的写法（第卅五轮实测 p_kdcc.UP_MID_28102044 的 RC2）：
     * {@code rc2 := p_kdemp.FN_EMPLIMITQUERY2(t_sql, nb_params, …)}——过程自己一句 OPEN 都没有，
     * 真正的 {@code OPEN 游标 FOR 变量} 写在函数里，而那条语句又是调用处传进来的变量。
     * 所以要走两跳：函数里 OPEN 的变量 ← 哪个形参 ← 调用处哪个实参变量 ← 那里的字符串拼接。
     * 两跳都在盘上源码里，跨不过去的地方逐条说明；这种推断一律 conf=low。
     *
     * @return 认出的列数；-1 表示这个过程里没有「游标 := 某函数(…)」这种写法（原因交给上层说）
     */
    private int cursorFromFunction(long projectId, DbSourceIndex.Def caller, String cur, Ctx c) {
        List<ProcBody.Assign> as = ProcBody.assignedCalls(caller.block(), cur);
        if (as.isEmpty()) return -1;
        int n = 0;
        for (ProcBody.Assign a : as) {
            DbSourceIndex.Def f = dbSource.find(projectId, a.callee());
            if (f == null) {
                miss(c, cur + " 在 " + a.line() + " 行由函数 " + a.callee() + " 返回，但盘上没有这份函数代码"
                        + "；这一路的出参只能连库执行一次看返回游标", caller.file(), a.line());
                continue;
            }
            step(c, cur + " 的游标由函数 " + a.callee() + " 返回（" + f.repoName() + " 的 "
                    + f.file() + ":" + f.line() + "）", f.file(), f.line(), "high");
            n += hopIntoFunction(caller, cur, a, f, c);
        }
        return n;
    }

    private int hopIntoFunction(DbSourceIndex.Def caller, String cur, ProcBody.Assign a,
                                DbSourceIndex.Def f, Ctx c) {
        List<ProcBody.Param> fps = ProcBody.params(header(f.block()));
        Set<String> targets = new LinkedHashSet<>();
        int n = 0;
        for (ProcBody.Open o : ProcBody.opensAny(f.block())) {
            // 同一个变量在 CASE 的各参数个数分支里被重复 OPEN（实测 fn_emplimitquery2 有 13 个分支），只算一次
            if (!targets.add(o.shape() + "|" + o.sql())) continue;
            if ("direct".equals(o.shape())) {
                n += emitColumns(c, cur, o.sql(), "由函数 " + a.callee() + " 在第 " + o.line() + " 行打开的语句",
                        "low", f.file(), o.line());
                continue;
            }
            if (!"dynamic".equals(o.shape())) continue;
            for (String p : ProcBody.sourceParams(f.block(), fps, o.sql())) {
                int idx = paramIndex(fps, p);
                if (idx < 0 || idx >= a.args().size()) continue;
                String arg = a.args().get(idx).trim();
                if (!arg.matches("[A-Za-z_][A-Za-z0-9_$]*")) continue;   // 实参是字面量或表达式：不猜
                String sql = ProcBody.dynamic(caller.block(), arg);
                if (sql.isBlank()) continue;
                n += emitColumns(c, cur, sql, "由函数 " + a.callee() + " 打开：它的语句来自形参 " + p
                        + "（第 " + (idx + 1) + " 位）← 调用处变量 " + arg, "low",
                        caller.file(), ProcBody.assignLine(caller.block(), arg));
            }
        }
        if (n == 0) {
            miss(c, "游标 " + cur + " 由函数 " + a.callee() + " 返回，但函数里打开的语句还原不出列名"
                    + "（语句是调用处传进来的变量拼的，或整段是 SELECT *）", f.file(), f.line());
        }
        return n;
    }

    private static int paramIndex(List<ProcBody.Param> ps, String name) {
        for (int i = 0; i < ps.size(); i++) {
            if (ps.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** 服务码找不到 bex 声明时，至少把 atom 实现类里真正取的键给出来 */
    private void resolveAtom(MapperIndex.Repo r, String businessCode, Ctx c) {
        for (MapperIndex.Atom a : mapper.atoms(r, businessCode)) {
            Path f = javaSource(r.root, a.cls());
            if (f == null) {
                miss(c, "业务码 " + businessCode + " 的实现类 " + a.cls() + "#" + a.method() + " 在本仓库没有 .java 源码",
                        a.file());
                continue;
            }
            String text = read(f);
            if (text == null) continue;
            int n = 0;
            for (java.util.regex.Matcher m = GET_PARAM.matcher(text); m.find(); ) {
                in(c, m.group(1), "", "string", "atom 代码 getParameter(\"…\")",
                        rel(r.root, f), 0, null, "medium");
                n++;
            }
            step(c, "atom " + a.cls() + "#" + a.method() + " 里取了 " + n + " 个键", rel(r.root, f), 0, "medium");
        }
    }

    private static final java.util.regex.Pattern GET_PARAM =
            java.util.regex.Pattern.compile("get(?:Parameter|String|Integer|Long|Object)\\(\\s*\"([^\"]{1,64})\"");

    /** 定义段里第一个括号内的形参表 */
    private static String header(String block) {
        int open = block.indexOf('(');
        if (open < 0) return "";
        int close = SqlColumns.matching(block, open);
        return close < 0 ? block.substring(open + 1) : block.substring(open + 1, close);
    }

    // ---------- 记账小工具 ----------

    private void step(Ctx c, String text, String file, int line, String conf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("file", file);
        if (line > 0) m.put("line", line);
        m.put("conf", conf);
        c.steps.add(m);
    }

    private void out(Ctx c, String field, String title, String type, String via, String file, int line, String conf) {
        if (field == null || field.isBlank()) return;
        if (!c.seenOut.add(field.toLowerCase(Locale.ROOT))) return;
        c.outputs.add(field(field, title, type, "out", via, file, line, conf, null));
    }

    private void in(Ctx c, String field, String title, String type, String via, String file, int line,
                    String from, String conf) {
        if (field == null || field.isBlank()) return;
        if (!c.seenIn.add(field.toLowerCase(Locale.ROOT))) return;
        c.inputs.add(field(field, title, type, "in", via, file, line, conf, from));
    }

    private static Map<String, Object> field(String f, String title, String type, String io, String via,
                                             String file, int line, String conf, String from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", f);
        m.put("title", title == null ? "" : title);
        m.put("type", type == null || type.isBlank() ? "string" : type);
        m.put("io", io);
        m.put("via", via);
        m.put("file", file);
        if (line > 0) m.put("line", line);
        m.put("conf", conf);
        if (from != null) m.put("from", from);
        return m;
    }

    private void miss(Ctx c, String reason, String file) {
        miss(c, reason, file, 0);
    }

    private void miss(Ctx c, String reason, String file, int line) {
        for (Map<String, Object> m : c.unresolved) {
            if (reason.equals(m.get("reason"))) return;   // 同一句话从两个仓库/两个游标说两遍没意义
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        if (file != null && !file.isBlank()) m.put("file", file);
        if (line > 0) m.put("line", line);
        c.unresolved.add(m);
    }

    private String note(Ctx c) {
        if (!c.outputs.isEmpty() && !c.unresolved.isEmpty()) {
            return "出参按代码认出了 " + c.outputs.size() + " 个，另有 " + c.unresolved.size()
                    + " 处认不出来（逐条见下）";
        }
        if (!c.outputs.isEmpty()) return null;
        if (!c.steps.isEmpty()) {
            return "按代码走到的这一步没能认出列名，原因逐条列在下面——这不是「这个接口没有出参」";
        }
        return "盘上两条链都没有「这个代码」的声明：老工程看 bexConf / serviceConf / mybatis 目录，"
                + "一码一路径那类看 interface-customer.properties 与 controller 的请求路径。"
                + "这个仓库可能不是这两类工程，或工作副本已经不在盘上。";
    }

    // ---------- 反向：改动文件 → 受影响的接口码 ----------

    /** 一条反向命中：接口码 + 被碰到的字段（含出参/入参）+ 这条命中凭什么算 */
    public record CodeHit(String kind, String code, String field, String io, String basis, String repo) {}

    /**
     * 反向：改动碰到的字段牵动哪些接口码（第卅五轮）。
     *
     * <p>第卅四轮的「改动 → 功能号」只认 viewConf/bexConf 的声明行，而用户口径是「几乎没有几个接口会把
     * 出入参配置到 xml 里面」——所以对改 mapper 语句、改存储过程的提交，那条路基本数不出接口。
     * 这里把正向那条链反过来走：
     * ① 改动文件里有 MyBatis 语句 → 这条语句的列名／绑定键正好是被改字段 → 语句 id 被哪个 bex 的 attr1 指用；
     * ② 改动文件定义了过程／包 → 哪条语句 call 它 → 同一条 bex 链。
     * service 码按 serviceConf 的 {@code service_business} 反查一并带出。basis 逐条说清查到的是哪一种，界面照实展示。
     *
     * @param fieldsByFile 文件尾键（{@code FileKeys.tail} 归一）→ 该文件里被碰到的字段（小写）→ 出参还是入参
     */
    public List<CodeHit> codesForFields(long projectId, Map<String, Map<String, String>> fieldsByFile) {
        List<CodeHit> out = new ArrayList<>();
        if (fieldsByFile == null || fieldsByFile.isEmpty()) return out;
        for (Map.Entry<String, Map<String, String>> e : fieldsByFile.entrySet()) {
            String tail = e.getKey();
            if (tail == null || tail.isBlank() || e.getValue().isEmpty()) continue;
            // 一个文件可能定义几十个过程（包体、整库导出），先按内容把名字取全；非 SQL 源码后缀直接跳过
            Set<String> defs = new LinkedHashSet<>(dbSource.namesDefinedIn(projectId, tail));
            String base = tail.substring(tail.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            if (dot > 0) defs.add(base.substring(0, dot).toLowerCase(Locale.ROOT));   // 多数老库：文件名就是对象名
            for (Repo rp : repos(projectId)) {
                MapperIndex.Repo r = rp.repo;
                for (MapperIndex.Stmt s : mapper.stmtsInFile(r, tail)) {
                    Set<String> cols = new LinkedHashSet<>();
                    for (SqlColumns.Column c : SqlColumns.columnsLoose(s.body())) {
                        if (c.label() != null) cols.add(c.label().toLowerCase(Locale.ROOT));
                    }
                    Set<String> binds = new LinkedHashSet<>();
                    SqlColumns.bindings(s.body()).keySet().forEach(k -> binds.add(k.toLowerCase(Locale.ROOT)));
                    for (Map.Entry<String, String> f : e.getValue().entrySet()) {
                        String io = cols.contains(f.getKey()) ? "out" : binds.contains(f.getKey()) ? "in" : null;
                        if (io == null) continue;   // 这条语句里没有这个字段，别把它算到用这条语句的码上
                        addHits(out, r, rp.name, mapper.codesOfStmt(r, s.id()), f.getKey(), io,
                                "改动文件里的语句 <" + s.id() + "> 真的" + ("out".equals(io) ? "选出" : "读这个键")
                                        + "，而这条语句被 bexConf 的 attr1 指用");
                    }
                }
                for (String def : defs) {
                    Set<String> codes = mapper.codesOfProc(r, def);
                    if (codes.isEmpty()) continue;
                    for (Map.Entry<String, String> f : e.getValue().entrySet()) {
                        addHits(out, r, rp.name, codes, f.getKey(), f.getValue(),
                                "改动文件定义了过程／包「" + def + "」，接口码的语句调用它");
                    }
                }
            }
        }
        return out;
    }

    /** 一个码命中就顺带把挂在它的 service 码也记上——两种接口码在界面上都要能看到 */
    private void addHits(List<CodeHit> out, MapperIndex.Repo r, String repoName, Set<String> codes,
                         String field, String io, String basis) {
        for (String code : codes) {
            out.add(new CodeHit("bex", code, field, io == null ? "" : io, basis, repoName));
            for (String svc : mapper.servicesOfBex(r, code)) {
                out.add(new CodeHit("service", svc, field, io == null ? "" : io,
                        basis + "；这个 bex 又挂在服务码「" + code + "」的业务码下", repoName));
            }
        }
    }

    // ---------- 仓库与文件读取 ----------

    public record Repo(String name, MapperIndex.Repo repo) {}

    /** 测试缝：驱动脚本可以覆盖这份清单，生产走 repos 表 */
    protected List<Repo> repos(long projectId) {
        List<Repo> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT name, local_path FROM repos WHERE project_id=? ORDER BY id", projectId)) {
            String dir = r.get("local_path") == null ? "" : String.valueOf(r.get("local_path"));
            if (dir.isBlank() || !Files.isDirectory(Path.of(dir))) continue;
            MapperIndex.Repo repo = mapper.forRepo(Path.of(dir), String.valueOf(r.get("name")));
            if (repo != null) out.add(new Repo(String.valueOf(r.get("name")), repo));
        }
        return out;
    }

    /** com.x.Y → 仓库里那个 .java（按包路径找，找不到再按类名在 src 目录下搜一次） */
    private Path javaSource(Path root, String cls) {
        String rel = cls.replace('.', '/') + ".java";
        try (var w = Files.walk(root, 8)) {
            for (Path p : w.toList()) {
                if (Files.isRegularFile(p) && p.toString().replace('\\', '/').endsWith(rel)) return p;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private String read(Path p) {
        try {
            byte[] b = Files.readAllBytes(p);
            try {
                java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b));
                return new String(b, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception notUtf8) {
                return new String(b, java.nio.charset.Charset.forName("GBK"));
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.getFileName().toString();
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    /** bexConf 的 data_type 是 JDBC 类型码，与 KjdpDispatchAdapter 同一档口径 */
    private static String jsonType(String dataType) {
        if (dataType == null) return "string";
        return switch (dataType.trim()) {
            case "2", "3", "4", "5", "6", "7", "8" -> "number";
            default -> "string";
        };
    }
}
