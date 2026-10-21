#!/bin/sh
# 一键启动：cd 到脚本所在目录再跑 main.py
# 用法： sh run.sh         （进菜单）
#        sh run.sh --check （环境自检）
#
# 注意：手机内部存储（/storage/emulated/0）不支持 chmod +x，
# 所以别指望 ./run.sh 能直接跑，用 sh run.sh 或 bash run.sh。

DIR=$(cd "$(dirname "$0")" && pwd)
cd "$DIR" || exit 1

if command -v python3 >/dev/null 2>&1; then
    PY=python3
elif command -v python >/dev/null 2>&1; then
    PY=python
else
    echo "没找到 python3。Termux 里装一下： pkg install python"
    exit 1
fi

exec "$PY" main.py "$@"
