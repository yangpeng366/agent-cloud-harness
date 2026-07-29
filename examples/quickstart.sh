#!/usr/bin/env bash
#
# Agent Cloud Harness · 端到端快速验证脚本（curl + jq）
#
# 前置：
#   1. 已按根目录 README 完成 mvn package
#   2. 服务已启动：java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
#   3. 已安装 curl 与 jq
#
# 用法：
#   BASE_URL=http://localhost:8080 ./examples/quickstart.sh
#
# 走一条完整的控制面 happy path，全程只调用 REST API，不依赖 LLM 配置。

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

if ! command -v jq >/dev/null 2>&1; then
  echo "错误：需要 jq 来解析 JSON，请先安装 jq（或改用 quickstart.ps1）。" >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "错误：需要 curl。" >&2
  exit 1
fi

echo "==> 1/7 健康检查"
curl -fsS "$BASE_URL/api/v1/health" | jq .

echo
echo "==> 2/7 创建会话"
SESSION=$(curl -fsS -X POST "$BASE_URL/api/v1/sessions" \
  -H "Content-Type: application/json" \
  -d '{"title":"quickstart demo session"}')
echo "$SESSION" | jq .
SESSION_ID=$(echo "$SESSION" | jq -r '.data.id')
echo "会话 ID：$SESSION_ID"

echo
echo "==> 3/7 在会话中创建任务"
TASK=$(curl -fsS -X POST "$BASE_URL/api/v1/tasks" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"hello world\",\"task_type\":\"coding\",\"source\":\"user\",\"priority\":\"high\",\"intent\":\"demo\",\"session_id\":\"$SESSION_ID\"}")
echo "$TASK" | jq .
TASK_ID=$(echo "$TASK" | jq -r '.data.id')
echo "任务 ID：$TASK_ID"

echo
echo "==> 4/7 查看 worker 列表（路由候选）"
curl -fsS "$BASE_URL/api/v1/workers" | jq '.data'

echo
echo "==> 5/7 轮询任务状态（最多 6 次，每次间隔 2s）"
for i in $(seq 1 6); do
  DETAIL=$(curl -fsS "$BASE_URL/api/v1/tasks/$TASK_ID")
  STATUS=$(echo "$DETAIL" | jq -r '.data.status')
  WORKER=$(echo "$DETAIL" | jq -r '.data.assigned_worker // "未分配"')
  echo "  [$i] status=$STATUS  assigned_worker=$WORKER"
  case "$STATUS" in
    done|failed|closed|cancelled) break ;;
  esac
  sleep 2
done

echo
echo "==> 6/7 取 live_flow 聚合诊断"
curl -fsS "$BASE_URL/api/v1/tasks/$TASK_ID/live_flow" \
  | jq '.data | {task_id: .task.id, status: .task.status, assigned_worker: (.task.assigned_worker // "未分配"), checkpoints: ((.checkpoints // []) | length), tool_invocations: ((.tool_invocations // []) | length), learning_memories: ((.learning_memories // []) | length)}'

echo
echo "==> 7/7 关闭会话"
curl -fsS -X POST "$BASE_URL/api/v1/sessions/$SESSION_ID/close" \
  | jq '.data | {id, status}'

echo
echo "完成。可用浏览器打开 $BASE_URL/console/ 查看图形化面板。"