# PreFlight · 现状建模总结

> 产物：`workspace.dsl`（C4 DSL）· `preflight-modules.dot`（模块关系）· `preflight-dataflow.dot`（数据流）· `evidence.md`

## 总体介绍
**PreFlight** 是一个"基于代码学习 + 变更驱动的半自动测试闭环"单体，目标是把"提测前对照人工检查单"这一最繁琐环节自动化。Java 21 + Spring Boot 3.5 fat jar，SQLite 元库（Flyway V1–V22），原生 JS SPA。

## 设计初衷
> 代码仓库是天然的知识底座；**让代码自己告诉你改了哪里、影响到什么**，测试人员不必再人工逐行翻代码对照需求。`docs/README.md`、`docs/01.md`。

## 核心功能（按业务场景）
- **学习与知识引擎**：静态解析（JavaParser/JSqlParser）抽 Controller/Mapper/表/过程 → LLM 提炼接口卡/SQL卡/模块摘要 → 草稿入库 → 人工确认；知识包 zip 导出/导入离线迁移。
- **变更感知与影响分析**：JGit/SVNKit 拉取 diff → 五分类（前端/后端/SQL/DDL/配置）→ 影响面派生 → file_bindings 查表 → 受影响接口/表 → 用例草稿。
- **双引擎执行**：HTTP 远程测（骨架回填、登录会话 TTL 25min、401 重登、cURL 导出）+ **DB 免启动直测**（JDBC 直连，默认回滚，六库 DDL 闸门）。
- **L1–L4 断言引擎**：L1 状态码 + 耗时 / L2 JSONPath / L3 基线 diff / **L4 数据库回读断言**；基线缓存自动重评；用例自愈（suspect 标记）。
- **LLM 网关**：按 purpose 路由多供应商 + 指纹缓存 + **离线模板兜底**（LLM 不可用时仍可用）。
- **金证 KJDP 登录加解密本地桩**：逐位复刻 `ka.js`/`des.js` 的 KJDP 加解密，不依赖外网。
- **YApi 回写 / 起飞检查单 / 提测结论单**：对接测试流程产物。
- **RBAC + 审计**：`tester / admin / viewer` 三种角色，凭据统一掩码，可配置审计开关。

## 亮点
1. **DB 免启动直测**是差异化亮点：不需要被测服务在运行，直连被测库即可校验 SQL/存储过程，对信创六库（DM8/Gauss/MySQL/Oracle/TiDB/SQLServer）有专门 DDL 闸门。
2. **L4 回读断言**——把数据库回读当断言层，比"只看 HTTP 200"更严格。
3. **离线可用**——LLM 不可用时用例生成走本地模板；KJDP 桩不走外网；静态资源不走 Node。
4. **变更驱动**——影响分析通过 `file_bindings` 查表而非重扫，单次成本可控。

## 不足 / 风险
- **单体 fat jar 耦合**：学习与执行共用一套 Spring 上下文，扩展性受限（`docs/04.md` 已规划扩展点）。
- **SQLite 元库**：22 表 Flyway 管理，多用户并发时写入吞吐受限。
- **原生 JS 单页应用**：`app.js` 已 5129 行，未框架化，状态管理复杂时易失控。
- **LLM 提炼质量**：受供应商/版本影响，有指纹缓存但仍需人工确认；离线兜底是模板而非真实归纳。
- **RBAC 默认策略未详证**：`AuthController` 存在，但 5.2 未展开（见 `evidence.md`）。

## 模块 → 文件 对照（速查）
| 模块 | 源码 | 前端 |
|---|---|---|
| 学习与知识引擎 | `com.preflight.learn.*` | `#learn #confirm` |
| 变更感知与仓库同步 | `com.preflight.repo.*` | `#repo #impact` |
| 双引擎执行 | `com.preflight.exec.{Http*,Db*,Kjdp*}` | `#runs #runs/{id}` |
| LLM 网关 | `com.preflight.llm.*` | `#llm` |
| 知识库存储 | Flyway V1–V22 + 文件 | `#knowledge` |
| 外部集成 | `com.preflight.yapi.*` `com.preflight.report.*` | `#checklist #conclusion` |
| 鉴权 | `com.preflight.security.*` | 各路由守卫 |

## 图文件
- `workspace.dsl` · Structurizr C4 DSL（L1 上下文 / L2 容器 / L3 组件）
- `preflight-modules.dot` · Graphviz 模块/包关系图（27 节点 / 31 边）
- `preflight-dataflow.dot` · 变更驱动数据流图（17 节点 / 17 边，含 L4 断言 / 六库 DDL 闸门 / 离线降级）

## 验证路径
```
python -c "import graphviz; graphviz.Source.from_file('preflight-modules.dot')"   # 语法 OK
python -c "import graphviz; graphviz.Source.from_file('preflight-dataflow.dot')"  # 语法 OK
# 安装 graphviz 后: dot -Tpng preflight-modules.dot -o modules.png
```
