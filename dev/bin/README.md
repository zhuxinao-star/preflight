# dev\bin\ 启停与构建脚本

| 脚本 | 环境 | 用途 |
|------|------|------|
| `build.bat` | Windows 开发机 | 用 `..\..\tools\` 内的 JDK21+Maven 构建 `..\..\src\preflight-server`，产物拷成 `dev\lib\preflight-server.jar`；依赖仓在 `tools\m2-repo\`，零全局 footprint |
| `start.bat` / `stop.bat` / `status.bat` | Windows | 启动 / 停止 / 查看状态（启动后不留窗口） |
| `start.sh` / `stop.sh` / `status.sh` | Linux 部署机 | 同上，PID 落工作区根的 `preflight.pid` |

`build-scratch.bat` 已随目录整理删除：它依赖一份源码临时拷贝 `.build-scratch\`，而 jar 现在落在 `dev\lib\`，构建时若被运行中的服务占住，正确做法是先 `stop.bat` 再 `build.bat package`（`build.bat` 会明确报出拷贝失败的原因）。

## 路径约定（三个脚本共用）

```
工作区根\           = 脚本所在目录的上上级
├── dev\bin\        本目录（脚本）
├── dev\lib\        部署件：preflight-server.jar + drivers\（外部 JDBC 驱动）
├── src\            源码（build.bat 的 -f 指向 src\preflight-server\pom.xml）
├── data\  logs\    程序按相对路径读写 ⇒ 脚本在拉 java 前 cd 到工作区根
├── tools\          JDK / Maven / m2-repo
└── config\         maven-settings.xml
```

## 快速开始

```bat
dev\bin\build.bat package        :: 构建（首次需联网下载依赖；必须显式给 goal）
dev\bin\start.bat                :: 启动，浏览器打开 http://localhost:8320
dev\bin\status.bat               :: 查看进程与健康状态
dev\bin\stop.bat                 :: 停止
```

**首次登录**：系统初始化时会弹出"创建管理员账号"向导；之后在「系统设置 → 用户管理」添加组员（admin/tester/viewer 三角色）。

功能总览见 [../../docs/00-项目作用与现状.md](../../docs/00-项目作用与现状.md)，逐个按钮的用法见 [../../docs/07-操作手册.html](../../docs/07-操作手册.html)。

## 环境隔离承诺

- 脚本只在**自身进程内**设置 JDK/Maven 路径，不写 PATH、不写注册表、不装全局组件。
- Maven 本地仓库定向在 `tools\m2-repo\`，不污染 `~/.m2`。
- 卸载 = 删除整个工作区目录，系统不留任何痕迹。
