workspace PreFlight 半自动测试系统 · 现状架构（reverse-engineered） {
  model {
    tester = person "测试 / 开发自测人员" "提测前对着检查单逐条验证改动，拍板执行"
    admin = person "系统管理员" "维护项目/仓库/环境/凭据/LLM 配置；可执行 DB 直测提交通道"
    viewer = person "只读观察者" "查看看板与结论，凭据掩码"

    vcs = softwareSystem "VCS 仓库（Git/SVN）" "待测项目的前/中/后台与数据库脚本目录" {
      location external
    }
    targetDb = softwareSystem "被测目标数据库（信创六库）" "DM8/Gauss/MySQL/Oracle/TiDB/SQLServer" {
      location external
    }
    targetSvc = softwareSystem "被测服务（HTTP 接口）" "需起服务的远程联测对象" {
      location external
    }
    llm = softwareSystem "LLM 供应商（云端 / 内网 Ollama）" "OpenAI 兼容协议；仅用于归纳与生成" {
      location external
    }
    browser = softwareSystem "浏览器" "运行期无 Node 依赖，静态资源由单体托管" {
      location external
    }

    preflight = softwareSystem "PreFlight 半自动测试系统" "基于代码学习 + 变更驱动的半自动测试闭环单体（Java 21 + Spring Boot 3.5 fat jar + SQLite + Flyway）" {
      spa = container "Web 单页应用" "原生 JS SPA · 10 个 hash 路由 · ERB 表单由 params_schema 渲染" "HTML + 原生 JS"
      api = container "REST API 层" "16 @RestController · /api/** · /mock/**" "Spring MVC"
      learn = container "学习与知识引擎" "扫描 + 语言适配器 + LLM 提炼 + 影响分析 + 知识确认" "Spring Service" {
        scan = component "扫描与语言适配器" "JavaParser 抽 Controller/Mapper；JSqlParser 抽表/过程；未覆盖走 LLM 兜底"
        extract = component "LLM 知识提炼流水线" "相邻文件聚合 → 接口卡/SQL卡/模块摘要 → 草稿入库 → 人工确认"
        impact = component "影响分析（file_bindings）" "file→line→ExtractedUnit→接口/数据对象；变更查表得受影响集合"
        kb = component "知识确认与知识包" "草稿→confirmed；导出/导入 zip 离线迁移"
        tcgen = component "用例生成与健康度" "L1-L4 断言草稿 + 用例自愈(suspect)；离线模板兜底"
      }
      repo = container "变更感知与仓库同步" "JGit/SVNKit 拉取 · diff 留痕 · 五分类 · 影响面派生" "JGit 6.x / SVNKit"
      exec = container "双引擎执行器（HTTP + DB）" "HTTP 远程测 + DB 免启动直测；认证会话与断言引擎" "Spring RestClient + JDBC" {
        httpEx = component "HTTP 执行器 + 认证会话" "骨架回填边界值 · 登录流会话缓存(TTL 25min) · 401 重登 · cURL 导出"
        dbEx = component "DB 直测执行器" "PreparedStatement 绑定 · 默认回滚 · 黑名单/行数上限 · 六库 DDL 闸门"
        assertNg = component "断言引擎（L1-L4）" "L1 状态码+耗时 · L2 JSONPath 等值/包含/正则 · L3 基线 diff · L4 DB 回读 {{ret.x}}"
        kjdp = component "KJDP 加解密封装（金证）" "逐位复刻 ka.js/des.js 加密登录（本地桩自检）"
      }
      llmgw = container "LLM 网关" "按 purpose 路由多供应商 · 指纹缓存 · 离线降级" "Spring RestClient + 本地文件缓存"
      store = container "知识库存储（元库 + 文件）" "元库 SQLite preflight.db（22 表）+ 大文本文件 + 本地索引" "SQLite + Flyway + 文件系统"
      ext = container "外部集成与增值模块" "YApi 回写 · 起飞检查单 · 提测结论单 · KJDP 桩" "Spring Service"

      tester --> spa "通过浏览器使用"
      admin --> spa "配置与运维"
      viewer --> spa "只读查看"
      browser --> spa "托管静态资源"
      spa --> api "REST /api/**"
      api --> repo "触发同步 / 查询改动流"
      api --> learn "触发学习 / 确认知识"
      api --> exec "POST /api/test-runs"
      api --> llmgw "代理 LLM 连通性测试"
      learn --> llmgw "提炼与摘要（purpose 路由）"
      tcgen --> llmgw "生成用例草稿"
      impact --> kb "读取文件↔实体映射"
      scan --> store "落库中间产物"
      extract --> store "写草稿/摘要/缓存"
      kb --> store "读写元库与知识文件"
      httpEx --> targetSvc "HTTP 远程联测（自动登录）"
      dbEx --> targetDb "JDBC 直测（默认回滚）"
      assertNg --> dbEx "L4 回读断言复用执行器"
      learn --> llm "外部 LLM 调用（可降级离线）"
      llmgw --> llm "网关转发 + 指纹缓存"
      ext --> store "检查单/结论单落库"
      exec --> store "执行快照与断言明细落库"
      repo --> vcs "fetch / log / diff（JGit、SVNKit）"
    }
  }

  views {
    systemContext preflight "L1 · 系统上下文" {
      include *
      autoLayout
    }
    container preflight "L2 · 容器视图" {
      include *
      autoLayout
    }
    component learn "L3 · 学习与影响分析" {
      include *
      autoLayout
    }
    component exec "L3 · 执行引擎" {
      include *
      autoLayout
    }
  }

  tags {
    external = tag "外部系统"
    core = tag "核心容器"
    data = tag "存储"
  }
  style {
    tag "外部系统" {
      background "#f8fafc"
      fontColor "#1f2937"
      borderStyle dashed
    }
    tag "核心容器" {
      background "#eef2ff"
      fontColor "#1e293b"
    }
    tag "存储" {
      background "#f0fdf4"
      fontColor "#14532d"
      shape cylinder2
    }
  }

  documentation {
    section "证据索引" {
      p ref "docs/README.md"
      p ref "docs/01-测试前置知识体系.md"
      p ref "docs/02-半自动测试系统需求文档.md"
      p ref "docs/03-半自动测试系统技术设计文档.md"
      p ref "docs/04-功能演进与扩展点设计.md"
      p ref "preflight/preflight-server/src/main/resources/db/migration/ (V1-V22)"
      p ref "preflight/preflight-server/src/main/java/com/preflight/ (控制器/服务)"
      p ref "preflight/preflight-server/src/main/resources/static/ (前端)"
    }
  }
}
