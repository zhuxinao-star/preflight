# PreFlight 工作区目录导航

项目是做什么的、能力清单、设计原则 → 见 [docs/00-项目作用与现状.md](docs/00-项目作用与现状.md)。本文件只说一件事：**哪个目录放什么、怎么跑起来**。

```
D:\project test\
├── docs\        需求·设计·计划·手册·改进记录（含 evidence\ 架构图证据）
├── config\      构建用配置（maven-settings.xml）+ 配置真源在哪的说明
├── src\         源码，只有源码：src\preflight-server\{pom.xml, src\}
├── dev\         部署目录，不含源码：dev\bin\ 启停构建脚本 + dev\lib\ jar 与外部 JDBC 驱动
├── data\        运行数据：preflight.db(+wal/shm) + master.key + artifacts\ + backups\
├── logs\        日志：preflight.log（logback 滚动）+ stdout.log（控制台合并）+ archive\
├── tools\       构建工具链：JDK 21 + Maven 3.9.11 + m2-repo\（本地依赖仓，离线可重编；体积大，不随仓库发布）
├── archive\     与本项目运行无关的遗留：外部工具会话状态、一把旧 master.key、2026-09-19 的旧编译类
└── README.md    本文件
```

## 启停与构建（Windows）

```bat
dev\bin\start.bat                 :: 启动，浏览器打开 http://localhost:8320（后台运行，不留窗口；打印 [OK] 的判据是端口真在监听）
dev\bin\status.bat                :: 端口属主 PID + 镜像名 + /actuator/health
dev\bin\stop.bat                  :: 停止（只杀 8320 上那个 java.exe）
dev\bin\build.bat package         :: 构建并把 jar 拷进 dev\lib\preflight-server.jar（本仓当前没有 src\test，所以不跑测试）
```

Linux 部署机用 `dev\bin\start.sh` / `stop.sh` / `status.sh`（同一套目录约定，PID 落 `preflight.pid`）。

**改代码前后的顺序**：先 `dev\bin\stop.bat` 再 `build.bat package`，然后 `start.bat`。为什么仍要先停服：运行时用的是 `dev\lib\` 那份、构建写的是 `src\preflight-server\target\` 那份，所以**在跑的服务不会被打坏**，但只要服务锁着 `dev\lib\preflight-server.jar`，最后那步拷贝就会失败并报 `[WARN] built ok but copy to dev\lib failed`——**构建成功而部署没成**。前端 `src\...\resources\static\`（`app.js`/`app.css`/`index.html`）打进 jar，改完必须重新 package + 重启才生效。

## 用 IntelliJ IDEA 开发

直接 **Open** `src\preflight-server\pom.xml`（Maven 项目导入，源码根、`src/main/java`、`src/main/resources` 布局都是标准 Maven 的）。settings 用 `config\maven-settings.xml`（只配了中央仓镜像）。本机 JDK 选 `tools\jdk-21.0.12.1+1`、Maven 指向 `tools\apache-maven-3.9.11`、本地仓库 `tools\m2-repo`——**别用 IDEA 自带的 Maven 默认 `~/.m2`**，那会绕过本工作区的离线依赖仓。

**克隆下来而没有 `tools\` 时**：该目录体积大、不随仓库发布。`build.bat` 会自动回落到 PATH 上的 `java` / `mvn` 与默认 `~/.m2`（首次构建需联网下依赖）；有 `tools\` 时仍优先用它，本机行为不变。`start.bat` 的 java 次序是 `dev\jre\bin\java.exe` → `tools\jdk-*` → PATH 上的 `java`。

## 三条容易被忽略的约定

- **`data\` 和 `logs\` 必须留在工作区根**：`application.yml` 里写的是相对路径（`jdbc:sqlite:data/preflight.db`、`logging.file.name=logs/preflight.log`），相对的是**进程的当前工作目录**，`start.bat` 因此在拉起 java 前 `cd` 到工作区根。把这两个目录挪进 `dev\` 而不改脚本，服务会在原地新建一个空库。
- **配置的唯一真源在 jar 里**：`src\preflight-server\src\main\resources\application.yml`。`config\` 不放它的副本，避免两份事实（副本改了不生效，比没副本更坏）。
- **Windows 侧认进程靠端口，不靠命令行里的串**：`start`/`stop`/`status` 用 `netstat -ano` 取 8320 的属主 PID，再用 `tasklist` 核对镜像名是不是 `java.exe`；不是它就 `[REFUSED]` 什么都不杀。端口与全部路径只写在 `dev\bin\_env.bat` 一处。java 命令行里仍保留 `preflight-server`（日志与排查要看），但脚本不再拿它当匹配依据——按名字匹配认不出是哪一台 java。
- **这台机器的批处理起不了 powershell**：`powershell.exe` 在 `.bat` 上下文里被拒（rc=5「拒绝访问」），PATH 名与全路径一样。所以隐藏启动走 `cscript` + `dev\bin\run-hidden.vbs`。换机器时若 cscript 也被拦，`start.bat` 会明说并回落成一个**最小化窗口**（任务栏会留着），而不是假装成功。
