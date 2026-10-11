# 证据索引 · PreFlight 现状架构

| 构件 | 类型 | 证据文件（grep 可验证） | 状态 |
|---|---|---|---|
| PreFlight 单体（Java 21 + Spring Boot 3.5 + SQLite + Flyway） | 软件系统 | `preflight/preflight-server/pom.xml` | confirmed |
| Web 单页应用（原生 JS SPA · 10 hash 路由） | 容器 | `resources/static/index.html` `app.js` | confirmed |
| REST API 层（16 @RestController） | 容器 | `grep -rn "@RestController" → 16` | confirmed |
| 学习与知识引擎（扫描+LLM 提炼+影响分析） | 容器 | `com.preflight.learn.*` | confirmed |
| 变更感知与仓库同步（JGit/SVNKit） | 容器 | `com.preflight.repo.*` `pom.xml jgit/svntools` | confirmed |
| 双引擎执行器（HTTP + DB 免启动直测） | 容器 | `com.preflight.exec.{HttpTestRun,DbTest*}` | confirmed |
| LLM 网关（按用途路由 + 缓存 + 离线降级） | 容器 | `com.preflight.llm.LlmGatewayService` `LlmConfig` | confirmed |
| 知识库存储（元库 + 文件 + 本地索引） | 容器 | `Flyway V1-V22` `llm_cache` | confirmed |
| 外部集成与增值模块（YApi/检查单/桩） | 容器 | `Yapi*` `ReleaseChecklist` `Mock*` | confirmed |
| 用户 / 管理员 / 观察者 | 人物 | `docs/01.md·3.1` `AuthController` | confirmed |
| VCS（Git/SVN 仓库） | 外部系统 | `com.preflight.repo.git/svn` | confirmed |
| 被测数据库（信创六库） | 外部系统 | `DbTestExecutor` `六库差异` | confirmed |
| 被测服务（HTTP） | 外部系统 | `HttpTestRunExecutor` | confirmed |
| LLM（云端/Ollama） | 外部系统 | `LlmGatewayService` | confirmed |
| 浏览器（静态资源托管） | 外部系统 | `resources/static` | confirmed |
| L4 断言复用 dbEx | 组件 | `HttpAssertionEngine` `L4 回读` | confirmed |
| DB 直测默认回滚 | 组件 | `DbTestExecutor rollback` | confirmed |
| 六库 DDL 闸门 | 组件 | `六库差异` `DDL 闸门` | confirmed |
| LLM 离线降级 | 组件 | `LlmGateway 离线` | confirmed |
| KJDP 金证加解密本地桩 | 组件 | `com.preflight.exec.Kjdp*` | confirmed |
| 认证会话 TTL 25min / 401 重登 | 组件 | `HttpTestRunExecutor` `25min` `401` | confirmed |
| 检查单 / 提测结论单 | 模块 | `ReleaseChecklist*` | confirmed |

## 产物文件
- `workspace.dsl` · Structurizr DSL（L1 上下文 + L2 容器 + L3 组件）
- `preflight-modules.dot` · Graphviz 模块/包关系图（前端→API→服务→解析→存储→外部）
- `preflight-dataflow.dot` · Graphviz 变更驱动数据流图（L4 断言 / 六库 DDL 闸门 / 离线降级）

## 未决（assumed / unknown）
- RBAC 默认策略与审计粒度：`AuthController` 存在，但 5.2 未详证。
- 检查单/结论单的落库 schema：见 Flyway `V1-V22`，未逐条核验。
