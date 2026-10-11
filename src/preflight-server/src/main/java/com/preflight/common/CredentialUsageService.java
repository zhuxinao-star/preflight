package com.preflight.common;

import com.preflight.repo.SvnRepoClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭据用途的单点推导（第卅二轮）：凭据保险库是一张全局表，SVN 拉代码的账号和被测系统的登录账号
 * 混在一起，界面上只看得到名字，于是"登录凭据"下拉里全是 work_app-svn 这类仓库账号
 * （用户原话："这里的凭据跟我登录有什么关系，登录凭据配置的不是 SVN 账号吗？"）。
 *
 * 判定只用一条已经存在的约定——SVN 桥接按 "&lt;仓库名&gt;-svn" 取凭据（见 {@link SvnRepoClient.VaultBridge}），
 * 所以"名字等于某个 SVN 仓库的约定名"就是 SVN 账号，别的一律按业务/其它。规则本身留在 SvnRepoClient，
 * 这里只负责反查，避免两处各写一遍字面量。
 *
 * 刻意不落库、不加列：这是派生事实，仓库改名或删掉后结论就该跟着变。
 */
@Service
public class CredentialUsageService {

    /**
     * 能当 YApi 文档凭据用的 kind（第卅六轮）：{@code yapi_login}=账号密码由系统登录换 token，
     * {@code yapi}/{@code token}=手粘 JWT（这条能力刻意留着，不能因为有自动登录就收走手填）。
     * 判定单点：YApi 页的凭据下拉、保险库的「用途」列、凭据检查都从这里认，两处各写一遍字面量迟早对不上。
     */
    public static final List<String> YAPI_KINDS = List.of("yapi_login", "yapi", "token");

    /** 这条 kind 是不是 YApi 文档凭据（不是被测系统登录账号，也不是 SVN 拉代码账号）。 */
    public static boolean isYapiKind(String kind) {
        return kind != null && YAPI_KINDS.contains(kind.trim());
    }

    /** YApi 凭据怎么拿到会话：login=系统登录换，token=用户手粘。 */
    public static String yapiVia(String kind) {
        return "yapi_login".equals(kind == null ? "" : kind.trim()) ? "login" : "token";
    }

    private final JdbcTemplate jdbc;

    public CredentialUsageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** credId → 它服务的 SVN 仓库名。只有名字命中 "&lt;仓库名&gt;-svn" 且那个仓库确实是 SVN 类型才算。 */
    public Map<Long, String> svnRepoByCredId() {
        List<Map<String, Object>> svnRepos = jdbc.queryForList("SELECT id, name FROM repos WHERE vcs_type='svn'");
        if (svnRepos.isEmpty()) return Map.of();
        Map<String, String> repoByCredName = new LinkedHashMap<>();
        for (Map<String, Object> r : svnRepos) {
            String name = String.valueOf(r.get("name"));
            repoByCredName.putIfAbsent(SvnRepoClient.svnCredentialName(name), name);
        }
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT id, name FROM credentials")) {
            String repo = repoByCredName.get(String.valueOf(c.get("name")));
            if (repo != null) out.put(((Number) c.get("id")).longValue(), repo);
        }
        return out;
    }

    /** 这条凭据是不是 SVN 拉代码账号（绑进登录流时用来提醒）。 */
    public boolean isSvnCredential(long credId) {
        return svnRepoByCredId().containsKey(credId);
    }
}
