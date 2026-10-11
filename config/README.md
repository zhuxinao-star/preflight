# config\

只放**构建期**用到的外部配置：

- `maven-settings.xml` — Maven 镜像（华为云 central）。由 `dev\bin\build.bat` 用 `-s` 显式传入。

## 运行时配置不在这里

应用的运行时配置唯一真源是 **`src\preflight-server\src\main\resources\application.yml`**，它打进 jar；改它要走 `dev\bin\build.bat package` + 重启。

这里**刻意不放** `application.yml` 的副本：Spring Boot 虽然支持外置 `config\application.yml` 覆盖，但一旦这里出现副本，就会出现"改了没生效"和"两份配置哪份作数"两个坑，比省下的几步更贵。真要按环境区分，再讨论外置覆盖的取舍。

同目录之外，运行期还需要这几处（都不算配置文件，列在此处便于查找）：

| 位置 | 作用 |
|------|------|
| `dev\lib\drivers\` | 外部 JDBC 驱动（达梦 `DmJdbcDriver18.jar`），启动用 `PropertiesLauncher` + `-Dloader.path` 加载 |
| `data\master.key` | 凭据保险库主密钥（首启自动生成）。**丢失则已存凭据不可解密**，备份时要连同 `preflight.db` 一起备 |
| `tools\m2-repo\` | Maven 本地依赖仓，定向在工作区内，不污染 `~/.m2` |
