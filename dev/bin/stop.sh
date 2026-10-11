#!/bin/sh
# 停止 PreFlight：优先按 dev/bin/start.sh 落的 PID 文件；PID 失效时按 jar 名匹配进程
BIN_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$BIN_DIR/../.." && pwd)
PID_FILE="$ROOT_DIR/preflight.pid"

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "停止进程: $PID"
        kill "$PID"
        # 最多等 10 秒优雅退出，超时强杀
        i=0
        while kill -0 "$PID" 2>/dev/null && [ $i -lt 10 ]; do
            sleep 1; i=$((i + 1))
        done
        kill -0 "$PID" 2>/dev/null && kill -9 "$PID"
        echo "完成。"
    else
        echo "进程已不存在。"
    fi
    rm -f "$PID_FILE"
else
    echo "无 PID 文件，尝试按 jar 名匹配..."
    pkill -f "preflight-server.*\.jar" && echo "完成。" || echo "未发现运行中的 PreFlight。"
fi
