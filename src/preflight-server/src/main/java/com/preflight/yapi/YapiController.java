package com.preflight.yapi;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * YApi 文档同步 API（第卅四轮）。
 * 只有 /push 会写外部系统，且只写请求体里逐条列出来的项；其余端点全部是只读或只改本系统的配置。
 */
@RestController
@RequestMapping("/api/yapi")
public class YapiController {

    private final YapiService yapi;

    public YapiController(YapiService yapi) {
        this.yapi = yapi;
    }

    @GetMapping("/config")
    public Map<String, Object> config(@RequestParam long projectId) {
        return yapi.config(projectId);
    }

    /**
     * 存同步目标：落库前先做一次只读地址体检（N59）。确知填的是页面地址时拒绝保存并说清改法；
     * 只是此刻连不上或认不出形态时照存、回一条 {@code baseWarn}——提醒不许做成拦死，手动路径一直要走得通。
     */
    @PostMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        long projectId = num(body.get("projectId"));
        if (projectId <= 0) return Map.of("ok", false, "error", "缺少 projectId，不知道要记哪个项目的 YApi 目标");
        return yapi.saveConfig(projectId, body);
    }

    /**
     * 检查界面上当前选中的这条 YApi 凭据能不能用（账号密码类真登录一次，手填 token 类只解 JWT）。
     * credId / baseUrl 可带未保存的表单值：只读检查，不落配置、不写 YApi。
     */
    @PostMapping("/check-auth")
    public Map<String, Object> checkAuth(@RequestBody Map<String, Object> body) {
        long projectId = num(body.get("projectId"));
        if (projectId <= 0) return Map.of("ok", false, "error", "缺少 projectId，不知道检查哪个项目的凭据");
        long cid = num(body.get("credId"));
        return yapi.checkAuth(projectId, cid > 0 ? cid : null, (String) body.get("baseUrl"));
    }

    /**
     * 现拉 YApi 项目列表（用户不用手抄 id）。成败都带两条结论：地址通不通 + 这次拉取成不成。
     * baseUrl / credId 可带界面上还没保存的值（第四十一轮）：按钮验的就是用户眼前这一格。
     */
    @GetMapping("/projects")
    public Map<String, Object> projects(@RequestParam long projectId,
                                        @RequestParam(required = false) String baseUrl,
                                        @RequestParam(required = false) Long credId) {
        return yapi.listProjects(projectId, baseUrl, credId);
    }

    /** 现拉所选项目下的目录（分类）；yapiProjectId 留空则用已配置的那个。同样拆两条结论、同样认这一格 */
    @GetMapping("/cats")
    public Map<String, Object> cats(@RequestParam long projectId,
                                    @RequestParam(required = false) String yapiProjectId,
                                    @RequestParam(required = false) String baseUrl,
                                    @RequestParam(required = false) Long credId) {
        return yapi.listCats(projectId, yapiProjectId, baseUrl, credId);
    }

    /** 待同步的功能号候选：来自本系统的服务字典，服务端过滤+分页 */
    @GetMapping("/candidates")
    public Map<String, Object> candidates(@RequestParam long projectId,
                                          @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) Long repoId,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return yapi.candidates(projectId, kind, repoId, q, page, size);
    }

    /** 单个功能号的文档草稿 + YApi 现状 + 字段级差异（withRemote=0 时不访问 YApi，纯本地算） */
    @GetMapping("/draft")
    public Map<String, Object> draft(@RequestParam long projectId, @RequestParam String kind,
                                     @RequestParam String code,
                                     @RequestParam(defaultValue = "1") int withRemote) {
        return yapi.draft(projectId, kind, code, withRemote == 1);
    }

    /**
     * 保存人工维护的文档内容：只改本系统的覆盖表，一次 YApi 外呼都不发。
     * 不传的字段（inputs / outputs / title / path）表示"这次没动它"，传了才按差异记账。
     */
    @PostMapping("/draft/save")
    public Map<String, Object> saveDraft(@RequestBody Map<String, Object> body) {
        long projectId = num(body.get("projectId"));
        if (projectId <= 0) return Map.of("ok", false, "error", "缺少 projectId，不知道要维护哪个项目的文档");
        String kind = str(body.get("kind"));
        String code = str(body.get("code"));
        if (kind.isBlank() || code.isBlank()) {
            return Map.of("ok", false, "error", "缺少 kind 或 code：维护的是哪一个功能号要指名");
        }
        return yapi.saveDoc(projectId, kind, code, strOrNull(body.get("title")), strOrNull(body.get("path")),
                rows(body.get("inputs")), rows(body.get("outputs")));
    }

    /**
     * 推送：请求体里给出的每一项都是界面上勾过Checkbox的。
     * 空列表直接拒绝——不允许"没勾也全推"。
     */
    @PostMapping("/push")
    public Map<String, Object> push(@RequestBody Map<String, Object> body) {
        long projectId = num(body.get("projectId"));
        if (projectId <= 0) return Map.of("ok", false, "error", "缺少 projectId");
        List<Map<String, Object>> items = rows(body.get("items"));
        if (items == null || items.isEmpty()) {
            return Map.of("ok", false, "error", "一条都没勾：勾选要同步的功能号后再点推送");
        }
        return yapi.push(projectId, items);
    }

    @GetMapping("/last-push")
    public Map<String, Object> lastPush(@RequestParam long projectId) {
        return yapi.lastPush(projectId);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 请求体里的一段行清单。这里必须把"这一侧根本没传"和"传了但是空清单"分开：
     * null＝这次没动它（保存时整侧跳过）；空清单＝人把这一侧全删了（保存时逐行记 hidden）。
     * 一律转成空清单会让"只改标题"这种请求把 8 个入参、13 个出参全隐藏掉。
     */
    private static List<Map<String, Object>> rows(Object raw) {
        if (raw == null) return null;
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) m;
                items.add(cast);
            }
        }
        return items;
    }

    private static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
