# GitHub Readiness Snapshot

> 本文件用于记录 2026-08-02 的当前公开就绪度快照，帮助后续贡献者与主会话快速判断：仓库已恢复、叙事已补齐，但仍存在少量需要人工收口的公开边界。

## 结论

- `agent-cloud-harness` 在 2026-07-31 目录异常后已恢复为可继续推进 GitHub-ready 文档修补的状态。
- 当前对外资产已覆盖：README、STARTUP_GUIDE、CONTRIBUTING、SECURITY、CODE_OF_CONDUCT、examples、`.github/` issue+PR 模板、CI workflow、ROADMAP、CHANGELOG。
- 当前主要剩余公开边界不在“缺基础文档”，而在：
  1. 工作区存在未提交的文档/测试/代码草稿，公开前需要人工确认公开范围。
  2. 部分历史文档仍带有本地 dev token / 本机路径 / 旧假设，建议在首个公开 commit 前统一复核。
  3. 远端 GitHub Actions、严格人工 A-H 手点、真实干净环境预检仍是历史遗留 gate，尚未真正完成。

## 已确认的健康项

- 仓库目录已恢复：`README.md`、`WAKE.md`、`AGENTS.md`、`docs/`、`src/`、`STATE.md`、`DECISIONS.md` 均可直接续写。
- 快速开始与示例已落地：`examples/README.md`、`examples/quickstart.sh`、`examples/quickstart.ps1` 提供同一条 happy path，适合外部首次使用者。
- Contributor 引导已存在：`CONTRIBUTING.md` 含开发前提、提交流程、提交前检查、Good First Issues / Help Wanted 候选列表。
- GitHub 社区文件已存在：`.github/workflows/ci.yml`、`.github/ISSUE_TEMPLATE/*`、`.github/PULL_REQUEST_TEMPLATE.md`。
- 对外边界已说明：`README.md` 与 `SECURITY.md` 明确当前更适合本地/单机/受控环境，不建议直接暴露公网。

## 仍待人工收口的项

- 未提交工作树：`DECISIONS.md`、`STATE.md`、`docs/docs/`、`src/main/java/com/agentcloud/worker/CodexAppServerWorkerExecutor.java`、新测试文件尚未整理为首个公开提交切片。
- 历史 dev token / 本机路径：`CONTRIBUTING.md` 已记录 GFI-06 的清理方向；公开前建议复核 `docs/`、执行记录、测试 fixture 中的本地 token 与主机路径。
- 历史 release checklist 口径：`docs/GITHUB_RELEASE_CHECKLIST.md` 仍保留 2026-06-02 的若干未完成项，需要在当前工作树状态下重新判断哪些仍是真实阻塞项、哪些只是历史假设未清。
- 探针任务仍停在人工 gate：`task_6886b7bacc1c4ace` 当前 `waiting_human`，不影响文档公开，但说明 runtime 验证尚未完全收尾。

## 建议的下一步

1. 由主会话确认未提交改动中哪些适合纳入首个公开 commit，哪些应继续留在本地工作树。
2. 做一轮“公开边界复核”：重点看 dev token、主机路径、环境变量默认值、示例配置占位符。
3. 根据复核结果更新 `docs/GITHUB_RELEASE_CHECKLIST.md` 与 `ROADMAP.md` 的 release gate 口径。
4. 如需进一步降低贡献门槛，可再补一份面向外部贡献者的“如何跑通第一个任务”图文/录屏说明。