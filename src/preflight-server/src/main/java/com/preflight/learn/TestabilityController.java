package com.preflight.learn;

import com.preflight.common.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 可测性体检 API（第 11 轮）：项目页常驻调用轻量项；【深度体检】才会真实打 Base URL 与连库，
 * 那一步可能几十秒（内网库不可达时等超时），所以单独留痕便于排查"谁把页面卡住了"。
 */
@RestController
@RequestMapping("/api/testability")
public class TestabilityController {

    private final TestabilityService service;
    private final AuditService audit;
    private final com.preflight.common.ProgressRegistry progress;

    public TestabilityController(TestabilityService service, AuditService audit,
                                 com.preflight.common.ProgressRegistry progress) {
        this.service = service;
        this.audit = audit;
        this.progress = progress;
    }

    @GetMapping
    public Map<String, Object> check(@RequestParam long projectId,
                                    @RequestParam(defaultValue = "false") boolean deep,
                                    @RequestParam(required = false) String pid) {
        if (deep) {
            // 几十秒的等待要在按钮上看得到在做什么；分母要给才是真进度——体检项数是固定的，
            // 但逐项计数由 service 内部循环负责，这里只登记阶段名，不假装知道跑到第几项。
            progress.begin(pid, "深度体检：真实打地址与连库", 0);
            try {
                Map<String, Object> r = service.check(projectId, true);
                audit.log("testability.deep", "project#" + projectId, String.valueOf(r.get("summary")));
                return r;
            } finally {
                progress.end(pid);
            }
        }
        return service.check(projectId, false);
    }
}
