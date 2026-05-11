# Contributing

感谢对 Agent Cloud Harness 的关注。

## 开发前提

- Java 21
- Maven 3.9+
- Windows 开发机建议直接使用仓库内脚本：
  - `.\scripts\Use-Java21.ps1`
  - `.\scripts\Build-WithJava21.ps1`
  - `.\scripts\Test-WithJava21.ps1`

## 基本流程

1. Fork 仓库并创建分支。
2. 修改代码或文档。
3. 本地运行测试。
4. 确认没有把 `.tmp/`、`test-results/`、本机日志、数据库文件提交进来。
5. 提交 PR，并说明：
   - 变更目的
   - 影响范围
   - 测试方式
   - 是否涉及 API / 文档更新

## 代码约定

- 业务代码使用英文标识符。
- 注释以中文为主。
- 领域对象优先保持不可变，使用 `withXxx()` 风格更新。
- 新增 HTTP 接口时，沿用现有 `HttpHandler` 模式，不引入新的 Web 框架。
- 修改接口行为时，同步更新：
  - `docs/API_CONTRACTS.md`
  - `docs/TROUBLESHOOT.md`
  - 相关测试

## 提交前检查

最少完成以下检查：

```powershell
.\scripts\Test-WithJava21.ps1
```

如果只改了文档或前端静态逻辑，也请至少说明你做了哪些局部验证。

## 大改建议

如果变更涉及以下方向，建议先在 issue 或 PR 描述中写清楚方案：

- 控制图节点语义变更
- Packet / Checkpoint 合同变更
- `/dialogue/` 或 `/console/` 交互重构
- `/v1/chat/completions` / `/v1/responses` façade 兼容层调整

