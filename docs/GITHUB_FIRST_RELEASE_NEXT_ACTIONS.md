# GitHub First Release Next Actions

> 本文档只回答一个问题：在当前仓库状态下，下一步最值得执行的动作是什么，而不是再继续扩展发布辅助脚本。

## 当前状态摘要

基于当前真实证据：

- `README.md` / `.github/` / `CONTRIBUTING.md` / `SECURITY.md` / `CODE_OF_CONDUCT.md` 已补齐
- `.github/workflows/ci.yml` 已覆盖：
  - `mvn test`
  - `node --check src/main/resources/web/dialogue/app.js`
  - `node --test src/test/js/*.test.mjs`
- 本地预检已实跑：
  - `docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md`
- 首发范围 dry-run 已稳定：
  - `docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md`
- 首发 commit slice dry-run 已稳定，且 `unmatched = none`：
  - `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
- `Repository Baseline` 已真实进入暂存区，且未混入 evidence-only 文件：
  - `git diff --cached --stat`
  - `git diff --cached --name-only`
- `chat-first / facade product line` 与 `acceptance harness and operator docs` 也已真实进入暂存区：
  - `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`

## 现在不要优先做的事

以下动作目前收益已经很低：

- 再补一层 release helper
- 再加一份平行 checklist
- 再解释一次哪些文件属于首发 / 证据 / defer

这些信息当前都已经有脚本和文档承载。

## 现在最值得做的 3 件事

### 1. 核对并决定是否直接提交当前三段主 slice

按这个顺序执行：

1. `Repository Baseline`
2. `chat-first / facade product line`
3. `acceptance harness and operator docs`

参考入口：

- `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_COMMIT_SEQUENCE_2026-05-11.md`

当前前三段主 slice 已不再只是 dry-run：

- 三批主提交范围都已真实进入 index
- staged diff 仍未混入 acceptance records / dry-run snapshots / stage preview working logs

真正未进入 index 的仍然主要是：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_*_2026-05-11.md` working snapshots
- `docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`

### 2. 替换真实 GitHub 仓库 URL

当前 `README.md` 里仍是发布占位地址：

```text
<your-published-repo-url>
```

这一步虽然小，但它仍然属于当前最明确的未完成首发 gate：

- 对外正式发布时，要把它替换成真实公开仓库地址
- 在正式发布前，不要把示例写成伪造的 GitHub 占位 URL

### 3. 进行 `/dialogue/` A-H 真实人工验收

这是当前仍然没有被任何自动化代理掉的 gate。

入口参考：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`

## 当前“可公开但未完结”的准确说法

如果现在就要对外描述当前状态，更准确的说法是：

> The repository is close to a public first release: release slicing, local precheck, CI baseline, and acceptance tooling are in place, but the real `/dialogue/` manual acceptance pass and final public repo wiring are still outstanding.

## 发布完成前仍不能越界宣称的事项

以下内容当前仍然不能当作完成事实：

- `README.md` 已填入真实公开仓库地址
- `/dialogue/` A-H 八条人工验收已完成
- GitHub Actions 已在真实远端仓库跑绿
- 项目已达到 production-ready distributed platform 水平
