# dev\bin\ 启停与构建脚本

| 脚本 | 环境 | 用途 |
|------|------|------|
| `build.bat` | Windows 开发机 | 用 `..\..\tools\` 内的 JDK21+Maven 构建 `..\..\src\preflight-server`，产物拷成 `dev\lib\preflight-server.jar`；依赖仓在 `tools\m2-repo\`，零全局 footprint。`tools\` 不随源码发布，缺 JDK/Maven 时回落到 PATH 上的 `java`/`mvn`（此时 `-o` 离线跑不了，首次要联网） |
| `start.bat` | Windows | 启动。**回话只在端口真的监听之后才打**：`[OK] PreFlight is up on port 8320 (PID …, window hidden)`；起不来打 `[FAILED]`，不自证成功 |
| `stop.bat` / `status.bat` | Windows | 停止 / 查看状态。都**按端口认进程**（`netstat -ano` 取 8320 的属主 PID），不按进程名猜 |
| `_env.bat` | Windows | 上面四个脚本**唯一**的路径与端口真源；也可 `call _env.bat :pf_pid_by_port` 复用「按端口找 PID」 |
| `run-hidden.vbs` | Windows | `start.bat` 用的无窗口启动器（`cscript` 调用） |
| `start.sh` / `stop.sh` / `status.sh` | Linux 部署机 | 同上，但**仍按工作区根的 `preflight.pid` 认进程**（没有本轮那套"端口＋镜像名"护栏）；要不要对齐，见 `docs/06` §54.4 的 N80 |

**为什么 Windows 侧不用 powershell**：这台机器在批处理上下文里启动 `powershell.exe` 一律被拒（rc=5，`拒绝访问。`），PATH 名与全路径都一样，与调用链无关（实测见 `docs/06-改进计划.md` §54）。所以隐藏启动走 `cscript` + `run-hidden.vbs`，认进程走 `netstat`/`tasklist`。

两处兜底都写在屏上，不静默换路：cscript 起不来 ⇒ 回落**最小化窗口**并明说"这个窗口会留在任务栏"；端口被**非 java.exe** 占住 ⇒ `stop.bat` 报 `[REFUSED] … that is not PreFlight. Nothing was killed.`，绝不误杀。

`build-scratch.bat` 已随目录整理删除：它依赖一份源码临时拷贝 `.build-scratch\`，而 jar 现在落在 `dev\lib\`，构建时若被运行中的服务占住，正确做法是先 `stop.bat` 再 `build.bat package`（`build.bat` 会明确报出拷贝失败的原因）。

## 路径约定（Windows 侧由 `_env.bat` 一处定义）

```
工作区根\           = 脚本所在目录的上上级
├── dev\bin\        本目录（脚本）
├── dev\lib\        部署件：preflight-server.jar + drivers\（外部 JDBC 驱动）
├── dev\jre\        可选：自带 JRE，找 java 的第一优先级
├── src\            源码（build.bat 的 -f 指向 src\preflight-server\pom.xml）
├── data\  logs\    程序按相对路径读写 ⇒ 脚本在拉 java 前 cd 到工作区根
├── tools\          JDK / Maven / m2-repo（不随源码发布）
└── config\         maven-settings.xml
```

端口 `8320`、健康地址、日志路径同样只在 `_env.bat` 里有一份；要改端口改那一个文件，四个脚本一起跟着变。

java 的优先级：`dev\jre\bin\java.exe` → `tools\jdk-*\bin\java.exe` → PATH 上的 `java`。选定后**先跑一次 `java -version` 验证真能执行**，不能执行就报错退出并告诉你三条出路，不用一个跑不起来的路径去"启动成功"。

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
