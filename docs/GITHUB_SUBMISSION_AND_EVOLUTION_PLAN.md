# Agent Cloud Harness GitHub 提交与后续演进计划

> 仓库路径：`D:/gitAll/agent-cloud-harness/`  
> 项目：`agent-cloud-harness`  
> 当前版本：`0.1.0-SNAPSHOT`  
> 定位：continuity-first agent cloud control plane，即面向长期 agent 任务的运行、上下文、记忆、执行轨迹与恢复控制层。

## 1. 目标

把 `agent-cloud-harness` 从本地工程推进到 GitHub 上可理解、可运行、可维护、可持续演进的开源项目。

目标分三层：

1. **可提交**：仓库结构干净，README 能解释项目价值，基础运行和测试方式明确。
2. **可推广**：外部用户能在 5-10 分钟内理解它解决什么问题，为什么值得试。
3. **可维护**：有固定巡检节奏、Issue/PR 处理规则、版本路线，避免开源后失活。

## 2. GitHub 账号与安全

- 密码、token、cookie、私钥、`.env`、本地数据库、运行日志不得提交到仓库。
- 不在仓库文档、脚本、Issue 模板或 release notes 中记录个人账号、登录邮箱、密码恢复信息或私有 token。

建议：

- 使用 GitHub Personal Access Token 或 SSH key，不在脚本和文档中保存密码。
- `.env`、token、cookie、本地配置、数据库文件、日志文件必须加入 `.gitignore`。
- 提交前做一次敏感信息扫描，重点查：
  - `password`
  - `token`
  - `secret`
  - `api_key`
  - `Authorization`
  - 私钥片段：`BEGIN PRIVATE KEY`

## 3. 项目开源定位

### 3.1 一句话定位

`agent-cloud-harness` 是一个 continuity-first 的 agent runtime/control-plane，用来让长期 agent 任务具备可恢复的执行状态、可审计的工具轨迹、可压缩的上下文治理和面向多轮工作的记忆/事实抽取能力。

### 3.2 对外解释重点

不要只说“这是一个 agent 框架”，而要强调它解决的问题：

- 长任务中断后如何恢复？
- 多轮执行后上下文如何不漂移？
- 工具调用、执行结果、judgment trace 如何被审计？
- agent 输出如何变成结构化事实，并进入下一轮上下文？
- 多 agent / 多分支工作流如何共享稳定状态，而不是只靠聊天历史？

### 3.3 推荐 GitHub Topics

- `ai-agent`
- `agent-runtime`
- `llm`
- `workflow`
- `memory`
- `context-engineering`
- `agent-orchestration`
- `java`
- `sqlite`

## 4. 提交到 GitHub 前的仓库准备清单

### 4.1 必备文件

建议在 `D:/gitAll/agent-cloud-harness/` 内补齐或检查：

- `README.md`
  - 项目一句话定位
  - 核心能力
  - 快速开始
  - 示例截图/示例输出
  - 架构概览
  - Roadmap
- `LICENSE`
  - 默认建议：Apache-2.0 或 MIT
  - 若强调专利授权和企业使用边界，建议 Apache-2.0
- `.gitignore`
  - 忽略 target、日志、数据库、缓存、`.env`、本地配置、密钥
- `CONTRIBUTING.md`
  - 如何提 Issue
  - 如何提 PR
  - Java 21 / Maven / 测试要求
- `CHANGELOG.md`
  - 从 `v0.1.0` 开始记录版本变化
- `SECURITY.md`
  - 安全问题如何私下报告
- `.github/ISSUE_TEMPLATE/*`
  - Bug report
  - Feature request
  - Docs improvement
- `.github/pull_request_template.md`
  - 变更说明
  - 测试情况
  - 风险点

### 4.2 质量门槛

开源前至少完成：

- Java 21 环境下可构建
- Maven 测试可运行
- README Quick Start 跑得通
- 无明显敏感信息
- 仓库根目录不混杂临时文件、日志、压缩包、大型二进制
- 文档能解释核心链路：执行输出 -> fact extraction -> runtime facts -> next context

推荐验证命令：

```bash
mvn test
```

如果全量测试暂时受外部环境影响，至少记录当前通过的 targeted tests，并在 README 或 release notes 里说明限制。

## 5. 首次 GitHub 发布步骤

1. 本地清理仓库
   - 删除临时文件
   - 补齐 README / LICENSE / `.gitignore`
   - 运行测试
2. 初始化或检查 Git
   - `git status`
   - `git add .`
   - `git commit -m "Prepare initial public release"`
3. GitHub 创建仓库
   - 推荐仓库名：`agent-cloud-harness`
   - 初始建议先 private，确认没有敏感信息后再 public
4. 连接远程仓库
   - SSH：`git remote add origin git@github.com:<user>/agent-cloud-harness.git`
   - 或 HTTPS + token
5. 推送
   - `git branch -M main`
   - `git push -u origin main`
6. 创建首个 Release
   - tag：`v0.1.0`
   - Release notes 写清楚：当前能力、限制、下一步

## 6. 推广计划

### 6.1 第一阶段：基础曝光，1-2 周

目标：让项目被看见，收集第一批真实反馈。

动作：

- README 增加架构图 / 流程图
- 写一篇中文介绍文章
- 写一篇英文介绍文章
- 准备一个 3-5 分钟 demo
- 发布渠道：
  - GitHub
  - X / Twitter
  - Hacker News / Reddit（英文准备好后）
  - 知乎 / 掘金 / V2EX / 即刻 / 小红书（按内容风格选）

### 6.2 第二阶段：案例驱动，2-6 周

目标：用可复现案例证明项目不是概念。

建议做 3 个 demo case：

1. **长任务中断恢复**
   - 展示任务执行一半后如何从 runtime facts / packet / checkpoint 恢复。
2. **工具调用与执行轨迹审计**
   - 展示 execution boundary、tool invocation ids、judgment trace 如何辅助排错。
3. **上下文压缩与下一轮恢复**
   - 展示 agent 输出如何被抽取成事实，并进入下一轮 mounted/context packet。

每个案例配：

- 背景
- 操作步骤
- 运行截图/日志
- 和普通 chat-agent 的对比

建议放入：

- `examples/`
- `docs/cases/`

### 6.3 第三阶段：社区与生态，6-12 周

目标：从“项目”变成“小生态入口”。

动作：

- 增加 Roadmap board
- 给新手准备 `good first issue`
- 收集用户问题形成 FAQ
- 每 2-4 周发一次版本更新
- 做对比文档：
  - LangGraph
  - OpenHands
  - AutoGen
  - CrewAI
  - 其他 agent runtime / workflow 框架

## 7. 维护计划

### 7.1 仓库扫描频率

| 类型 | 频率 | 内容 |
|---|---:|---|
| 快速巡检 | 每天 1 次 | Issue、PR、CI 是否失败、安全提醒 |
| 深度巡检 | 每周 1 次 | 依赖更新、测试覆盖、文档过期、Roadmap 进度 |
| 发布检查 | 每 2-4 周 1 次 | CHANGELOG、版本号、Release notes、兼容性 |
| 架构复盘 | 每月 1 次 | 是否偏离 continuity-first 核心方向，模块边界是否变复杂 |

最低维护要求：

- **每周至少扫 2 次 GitHub 仓库**：Issue / PR / Security / Actions。
- **每周一次深度整理**：把问题分类到 bug、docs、feature、architecture。
- **每月一次版本或路线图更新**：即使不发版，也要让外界看到项目还活着。

### 7.2 Issue 处理规则

优先级：

1. 安全问题 / 数据泄露风险
2. 无法安装、构建或启动
3. 核心 runtime / memory / context 恢复错误
4. 文档不清楚
5. 新功能请求
6. 低优先级体验优化

响应 SLA 建议：

- 严重问题：24-48 小时内回应
- 普通 Issue：3-7 天内回应
- 功能建议：每周统一整理一次

### 7.3 PR 处理规则

合并前必须有：

- 变更说明
- 测试说明
- 风险说明
- 不引入敏感信息
- 不破坏核心架构方向

大型 PR 要求拆分：

- schema / runtime / UI / docs 分开
- 避免一次性改太多，导致不可回滚

## 8. 演进路线图

### Phase 0：开源准备

周期：1 周内

- 清理仓库
- 补齐 README / LICENSE / `.gitignore`
- 补齐 Quick Start
- 跑通基础测试
- 建立 GitHub 仓库

### Phase 1：最小可理解版本

周期：第 1-2 周

- 发布 `v0.1.0`
- 完成架构图和核心流程说明
- 补 1 个完整 demo
- 建立 Issue/PR 模板

### Phase 2：可验证案例版本

周期：第 3-6 周

- 补 2-3 个真实场景案例
- 增加 benchmark / evaluation 初版
- 增加 CI
- 做第一次外部推广

### Phase 3：可维护社区版本

周期：第 2-3 个月

- 建立贡献指南
- 标记 good first issue
- 每月一次 release
- 增加插件/扩展机制说明
- 输出对比文档和设计原则文档

### Phase 4：长期演进

周期：3 个月以后

重点方向：

- 更稳定的 agent runtime
- 更强的上下文治理与压缩机制
- 更透明的 execution boundary / tool trace / judgment trace
- 更好的多 agent 协作和任务恢复
- 更完整的测试、benchmark、案例库
- 从 task-centered runtime 演进到 goal lifecycle runtime，但不要一次性替换现有 Task runtime，应先作为上层生命周期包装

## 9. 推荐每周维护节奏

### 周一

- 扫 Issue / PR / Security alerts
- 看 GitHub Actions / CI 是否失败
- 规划本周 1-2 个重点任务

### 周三

- 处理中等优先级问题
- 更新文档或 demo
- 合并小 PR

### 周五

- 整理 CHANGELOG
- 更新 Roadmap
- 发一条项目进展动态

### 每月最后一周

- 做一次架构复盘
- 判断是否需要发版
- 清理 stale issue
- 复查 README 是否仍然准确

## 10. 当前建议的下一步

1. 在 `D:/gitAll/agent-cloud-harness/` 内补齐开源基础文件。
2. 做一次敏感信息扫描，尤其不要提交账号密码、token、cookie、本地配置、数据库和日志。
3. 跑 `mvn test` 或至少跑当前核心 targeted tests。
4. 先推 private GitHub 仓库，确认干净后再 public。
5. 发布 `v0.1.0`，同时准备第一篇推广文章。

## 11. 推荐后续文档拆分

当前计划可以先放在：

- `docs/GITHUB_SUBMISSION_AND_EVOLUTION_PLAN.md`

后续如果内容变多，可以拆成：

- `docs/ROADMAP.md`
- `docs/MAINTENANCE.md`
- `docs/PROMOTION_PLAN.md`
- `docs/RELEASE_PROCESS.md`
