# Changelog

All notable changes to **Agent Cloud Harness** are documented in this file.

格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，本项目遵循
[Semantic Versioning](https://semver.org/spec/v2.0.0.html)。

> 当前版本为 `0.1.0-SNAPSHOT`，尚未发布正式 release。首个正式版本 `0.1.0` 将在
> `CONTRIBUTING.md` 的发布前清单与 Good First Issues 阻塞项闭合后发布。
> 本文件随每次合并到 `main` 的变更更新；正式 release 时，将 `[Unreleased]` 段落
> 提升为带版本号与日期的段落，并在其上方新建空的 `[Unreleased]`。
>
> 更新约定：贡献者按 Conventional Commits（`feat:` / `fix:` / `docs:` / `chore:` /
> `test:` / `refactor:`）提交，并在同一次 PR 中把对应条目归入 `[Unreleased]` 的
> Added / Changed / Deprecated / Removed / Fixed / Security / Docs 分组。

## [Unreleased]

### Added
- Provider preflight 健康探针体系：可在 Web Console 触发 agent / provider 可达性预检，
  支持动态配置探针、记录 startup probe、暴露 unsupported discovered providers
  （`feat: configure dynamic provider preflight probes` 等系列提交）。
- Provider run file tail / preview 流式订阅：支持读取 provider 运行文件快照、
  订阅预览更新、流式推送 snapshot（`feat: stream provider run file snapshots` 等系列提交）。
- Generic stream JSON provider 解析与 native CLI provider 协议推断。
- Web Console 启动协议探针可视化展示。
- Dispatch warmup 可禁用开关。
- Goal progress 追踪与 partial 状态、Dialogue UI 增强。

### Fixed
- 修复 worker round 中 resume provider session 丢失问题
  （`fix: preserve resume provider session in worker rounds`）。

### Docs
- 新增 Good First Issues / Help Wanted 候选清单，见 `CONTRIBUTING.md`。
- 对齐 provider 与 acceptance 边界文档。
- docs index audit 持续全绿（113 篇根 Markdown，0 violation / 0 orphan）。

### In Development（工作树中，尚未提交）
- CCX ↔ Harness 双向对接：`Run-HarnessWithCcx.ps1` 集成脚本、
  harness-strong / harness-fast 模型名映射、`HarnessConfig` / `HarnessState` 类。
- Free-model worker lane：`WorkerRegistryConfigRegistration` 测试。

## [0.1.0] - 待发布

首个公开基线，对应 pom 版本 `0.1.0-SNAPSHOT`。发布前需先闭合
`CONTRIBUTING.md` 中的 Good First Issues 阻塞项（尤其是 GFI-05：`.gitignore`
误忽略所有 `README.md`，导致 docs 治理索引层不会随仓发布）。

### 核心能力（详见 README）
- 会话与任务生命周期：create -> schedule -> pause / resume / handoff / escalate -> close。
- Worker 自动路由：按 capability + readiness + learning memory 匹配，保留 fallback。
- Tool-aware 执行：单轮最多 3 步受控工具链（本地文件搜索 / 读取 / 写入 / 列表），
  带路径校验与重复调用守卫。
- 续跑与交接上下文：在 pause / handoff / escalate 节点生成 `ResumePacket` /
  `HandoffPacket`，并写入 checkpoint。
- 运行时 Judgment：基于 LLM 的 execution / completion 判断，输出推荐动作与下一步建议。
- Learning Memory：自动记录 routing preference 与 completion pattern，用于后续路由优化。
- 实验矩阵：内置 baseline case catalog，支持 `strong_only` / `small_only` /
  `orchestrated` 三种模式的可比较实验 run。
- Web GUI：内置 `/console/`（任务 / 会话 / Worker 观测面板）与
  `/dialogue/`（对话式任务发布与消息流）。

### 运行时要求
- Java 21（启用 `--enable-preview`）、Maven 3.9+、对 `${user.home}/.agentcloud/` 写权限。
- 默认监听 `8080`。