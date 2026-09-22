# Long Stability Smoke 25200s Execution Record 2026-08-02

> 主题归属：evaluation（real-worker smoke / long-stability execution evidence）

## 背景

- 本轮任务：run a 7h+ stability smoke。
- 目标：在现有 harness timeout override seam 上补出一轮可重复的 7 小时级长稳 smoke 入口、执行命令与 evidence 结论。

## 结论

- 结论：25200s long stability smoke **已具备可执行入口**，但尚未完成一轮完整 7h+ 真实 worker run；当前证据停在 **准备就绪 / 回归保护已落地**。
- 阻塞：完整 25200s 真实运行需要持续 7 小时以上，受当前环境可用时长限制，不能在本轮内完成一次端到端长时间等待样本。

## 已落地的回归保护

- `src/test/java/com/agentcloud/engine/WorkerExecutionTimeoutConfigTest.java:85`
  - 新增 `longStabilitySmoke25200sOverrideIsAcceptedAcrossTiers()`，验证 `-Dharness.worker.timeout.seconds=25200` 在 strong / non-strong worker 和静态 resolver 上都生效。

## 可执行入口

- 单测验证：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=WorkerExecutionTimeoutConfigTest#longStabilitySmoke25200sOverrideIsAcceptedAcrossTiers`
- 长稳 runner：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-LongStabilitySmoke.ps1 -BaseUrl "http://localhost:8080" -ExperimentName "long-stability-25200s-20260802" -ReportPath ".tmp\long-stability-smoke-25200s-20260802.json" -TaskPollTimeoutSec 25200 -RequestTimeoutSec 60`
- 若真实 worker 支持 25200s 预算，可直接对 `long-001` 单 case 单 mode 投 smoke；当前默认 runner 只投 1 个 case，避免并发长任务占满本地资源。

## 观察

- 仓库已有 worker timeout 显式 override 能力：`-Dharness.worker.timeout.seconds` / `HARNESS_WORKER_TIMEOUT_SECONDS`。
- `WorkerExecutionTimeoutConfigTest` 先前已有 1800s 稳定 smoke 保护；25200s 是其直接扩展。
- `ControlNodeGraph.executeOneRoundWithTimeout` 是真正的长稳关键路径，超时后通过 `RuntimeException` 进入 failure recovery，不会无限阻塞。

## 下一步

- 在资源允许时，用上述 runner 对真实长任务跑完一轮 `long-001` 25200s smoke。
- 若真实 run 通过，把最终 `report.json` 与 terminal/evaluated 结论回填到本条记录。
- 若真实 run 失败，优先按 `worker_budget_exhausted` / `waiting_human` / provider timeout 分类，再决定是否继续上调预算或拆分任务。