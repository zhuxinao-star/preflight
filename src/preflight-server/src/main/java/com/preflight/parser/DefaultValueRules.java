package com.preflight.parser;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 预填默认值规则表（第 11 轮）：学习能给出"有哪些字段"，但给不出"这个业务该传什么值"。
 * 这里规定的是一小批**跨项目通用的报文头字段**——它们决定请求能不能被服务端接受，
 * 所以不能停留在 "TEST" 这种占位符上。只有**一条规则都没命中**的字段才走类型兜底值。
 *
 * 刻意不落库：接口卡的 params_schema 是"学到什么"，写进规则值会污染 schema 哈希、
 * 把历史用例刷成待复核；所以只在读取/生成时按这张表算。
 */
public final class DefaultValueRules {

    private DefaultValueRules() {}

    /** 信封里表示"这次调用打到哪个功能"的字段名（各家写法都收） */
    private static final String[] FUNCTION_KEYS = {
            "service_id", "serviceid", "server_id", "serverid", "msg_id", "msgid", "func_no", "funcno"};

    /**
     * 功能号：接口路径最后一段里形如 hf100096 / P9999999 / 12020001 的业务编号。
     * 纯 CRUD 风格路径（/user/{id}、/api/login）取不到。
     *
     * <p>返回 {@link Optional} 而不是 null，因为"这条路径末段不是一个业务编号"是一条<b>结论</b>
     * （本次没有功能号），不是一种可以往下传的值。第 48 轮口径。
     */
    public static Optional<String> functionNo(String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        String p = path.replaceAll("/+$", "");
        int slash = p.lastIndexOf('/');
        String tail = (slash >= 0 ? p.substring(slash + 1) : p).trim();
        if (tail.isEmpty() || tail.length() > 40 || tail.indexOf('{') >= 0) return Optional.empty();
        return tail.matches("(?i)[a-z][a-z0-9_]*[0-9]+|[0-9]{4,}") ? Optional.of(tail) : Optional.empty();
    }

    /**
     * 报文头模板里表示"这一格要等于<b>本次</b>功能号"的占位串。
     * 写侧（两个 {@code DispatchAdapter} 给的头模板）、读侧（测试台换值、体检判旧模板）共用这一串，
     * 免得两边各写各的字面量、哪天改了一半就只剩"永远判不出过期"的体检。
     */
    public static final String CODE_TEMPLATE = "$code";

    /**
     * 操作员字段里"不传就打不开业务"的那几个：码值 1 是各家信封的通行缺省
     * （金证前端 ka.js 的 OP_CODE 就是写死的 1，转发中心这边的报文头类只声明字段、不给初值）。
     */
    private static final String[] OPERATOR_ONE_KEYS = {
            "op_code", "opcode", "opr_code", "op_role", "oprole", "opr_role"};

    /** 一条规则的三种结论，必须分得开：没这条规则 / 给个写死的常量 / 要「本次功能号」。 */
    public enum RuleKind { NOT_MATCHED, CONST, CODE }

    /**
     * 规则命中结果。{@code value} 用的是 {@link Optional}，因为"这一格该等于本次功能号，可这一次没有功能号"
     * 与"根本没有这条规则"是两件事——把前者表示成 null，调用方就会当成后者继续往下走，
     * 一路落到按字段名编值（<code>func_no → "2026-01-01"</code> 那一类），编出来的值比空着更坏。
     *
     * <p>{@link RuleKind#CONST} 一定带值；{@code value} 为空只可能出现在 {@link RuleKind#CODE}
     * 且这一次没有功能号那一种情况——那一种要照空串发（第 46 轮口径），但结论必须摆出来，不许退成
     * {@link RuleKind#NOT_MATCHED}。
     */
    public record Rule(RuleKind kind, Optional<String> value) {

        static final Rule NOT_MATCHED = new Rule(RuleKind.NOT_MATCHED, Optional.empty());

        /** 这一格填得出值吗（填不出来时界面要点名说为什么，不许静默留空） */
        public boolean filled() {
            return value.isPresent();
        }
    }

    /**
     * 查这一格的规则。
     *
     * @param functionNo 本次功能号；<b>这次没有就传 {@link Optional#empty()}</b>，传 null 是直接拒（未配置不是值）
     */
    public static Rule rule(String fieldName, Optional<String> functionNo) {
        Objects.requireNonNull(functionNo, "本次功能号要么给值，要么明确给 Optional.empty()");
        if (fieldName == null) return Rule.NOT_MATCHED;
        String n = normalize(fieldName);
        for (String k : OPERATOR_ONE_KEYS) {
            if (n.equals(k)) return new Rule(RuleKind.CONST, Optional.of("1"));
        }
        for (String k : FUNCTION_KEYS) {
            // 命中即算命中：本次有没有码是另一种结论，交给调用方按 kind 说清，不在这里退成"没规则"
            if (n.equals(k)) return new Rule(RuleKind.CODE, functionNo);
        }
        return Rule.NOT_MATCHED;
    }

    /** 键名归一：大小写与连字符不区分（报文头这边 camelCase、那边 snake_case，同一张表要都认） */
    public static String normalize(String fieldName) {
        return fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT).replace("-", "_");
    }

    /**
     * 这一格按规矩要不要等于<b>本次功能号</b>——只看规则归哪类，与这次到底有没有码无关。
     * 界面要点名"留空是因为没码"的格，用的就是这个谓词；不许自己再抄一份键名清单。
     */
    public static boolean requiresCode(String fieldName) {
        return rule(fieldName, Optional.empty()).kind() == RuleKind.CODE;
    }

    /** schema 节点上是否已经有人工/学习给出的值（有则以它为准） */
    public static boolean hasOwnValue(Map<String, Object> ps) {
        return ps != null && ps.get("default") != null;
    }
}
