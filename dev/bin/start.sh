#!/bin/sh
# ============================================================
# PreFlight 启动脚本（Linux 部署机）
# 目录约定：dev/bin 本脚本 | dev/lib 部署件 | 工作区根目录 data\ logs\
#           （程序按相对路径读写，见 application.yml），所以启动前先 cd 到工作区根目录
# JDK 优先级：dev/jre > $JAVA_HOME > tools/jdk-* > PATH
# ============================================================
BIN_DIR=$(cd "$(dirname "$0")" && pwd)
DEV_DIR=$(cd "$BIN_DIR/.." && pwd)
ROOT_DIR=$(cd "$BIN_DIR/../.." && pwd)

if [ -x "$DEV_DIR/jre/bin/java" ]; then
    JAVA="$DEV_DIR/jre/bin/java"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif [ -x "$(ls -d "$ROOT_DIR"/tools/jdk-*/bin/java 2>/dev/null | head -1)" ]; then
    JAVA=$(ls -d "$ROOT_DIR"/tools/jdk-*/bin/java | head -1)
else
    JAVA="java"
fi

JAR="$DEV_DIR/lib/preflight-server.jar"
if [ ! -f "$JAR" ]; then
    echo "[错误] 未找到 jar: $JAR  -  先跑 dev/bin/build.bat package"
    exit 1
fi

mkdir -p "$ROOT_DIR/logs" "$ROOT_DIR/data" "$DEV_DIR/lib/drivers"
cd "$ROOT_DIR" || exit 1

# PropertiesLauncher 加载 dev/lib/drivers 下的外部 JDBC 驱动（如达梦 DmJdbcDriver18）
nohup "$JAVA" -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 \
    -Dloader.path="$DEV_DIR/lib/drivers" -cp "$JAR" \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    >> "$ROOT_DIR/logs/stdout.log" 2>&1 &
echo $! > "$ROOT_DIR/preflight.pid"

echo "PreFlight 启动中... PID=$(cat "$ROOT_DIR/preflight.pid")  地址: http://localhost:8320  日志: logs/stdout.log"
