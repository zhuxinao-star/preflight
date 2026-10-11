#!/bin/sh
# 查看 PreFlight 进程与健康状态
BIN_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$BIN_DIR/../.." && pwd)
PID_FILE="$ROOT_DIR/preflight.pid"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "运行中, PID: $(cat "$PID_FILE")"
else
    echo "未运行（或 PID 文件已失效，按 jar 名再认一次）"
    pgrep -f "preflight-server.*\.jar" && echo "  ^ 找到上面对应的进程"
fi
curl -s -m 2 http://localhost:8320/actuator/health && echo
