# GitHub First Release Next Actions

> 本文档只回答一个问题：在当前仓库状态下，下一步最值得执行的动作是什么，而不是再继续扩展发布辅助脚本。
>
> 历史材料说明：本文保留当时首发收口阶段的下一步建议，适合回看当时的发布阻塞项。当前不作为 release 第一入口；如需看当前公开前 gate，请优先看 `docs/GITHUB_RELEASE_CHECKLIST.md`，如需看实际 replay / stage 边界，请看 `docs/GITHUB_FIRST_RELEASE_EXECUTION_GUIDE.md`。

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
- 首发 commit slice 的 dry-run / stage preview / stage file list 仍然有效：
  - `docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md`
  - `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md`
- 当前已新增一轮 index audit：
  - `docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md`
  - 最新结果显示：当前真实 index 已不再保持之前的三段 staged 状态
  - 当前只剩 `docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md` 仍在 staged，其他主 slice 文件都不再停留在 index 中
- 三段首发主提交已经真实进入本地 Git 历史：
  - `8350a8c` `chore: prepare repository baseline for public first release`
  - `d7fefea` `feat: ship chat-first dialogue facade and related UI flows`
  - `4f559c2` `chore: add acceptance harness and operator release tooling`

## 现在不要优先做的事

以下动作目前收益已经很低：

- 再补一层 release helper
- 再加一份平行 checklist
- 再解释一次哪些文件属于首发 / 证据 / defer
- 再把已经完成的三段主 slice 重新 stage 一遍当成进展

这些信息当前都已经有脚本和文档承载。

## 现在最值得做的 3 件事

### 1. 填真实 GitHub 仓库 URL 并准备真实远端 push

当前 `README.md` 里仍是发布占位地址：

```text
<your-published-repo-url>
```

这一步虽然小，但现在已经是最直接的公开发布 gate：

- 对外正式发布时，要把它替换成真实公开仓库地址
- 然后把当前三段本地首发 commit push 到真实远端
- 并观察 GitHub Actions 在真实仓库里跑一轮

### 2. 进行 `/dialogue/` A-H 真实人工验收

这是当前仍然没有被任何自动化代理掉的 gate。

入口参考：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`

### 3. 决定 evidence-only working logs 是否进入公开历史

当前主三段首发 commit 已经落地，但这批文件仍是 working logs / evidence-only：

- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md`
- `docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md`
- `docs/GITHUB_FIRST_RELEASE_*_2026-05-11.md`
- `docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`

现在应明确决定：

- 哪些保留在公开历史里
- 哪些只作为本地/内部记录
- 不要让它们和“产品已完成所有 gate”混淆

## 当前“可公开但未完结”的准确说法

如果现在就要对外描述当前状态，更准确的说法是：

> The repository is close to a public first release: the three local first-release commits already exist, release slicing and local precheck are in place, and the remaining gates are the real public repo wiring, the `/dialogue/` manual acceptance pass, and the remote CI run.

## 发布完成前仍不能越界宣称的事项

以下内容当前仍然不能当作完成事实：

- `README.md` 已填入真实公开仓库地址
- `/dialogue/` A-H 八条人工验收已完成
- GitHub Actions 已在真实远端仓库跑绿
- 项目已达到 production-ready distributed platform 水平
