# CCX Integration Precheck Execution Record

> 执行日期：2026-07-22
> 验证对象：CCX 网关端到端可用性
> 执行脚本：`scripts/Run-CcxIntegrationPrecheck.ps1`

## 执行环境

- CCX 网关地址：`http://127.0.0.1:3688`
- CCX 版本：v2.9.37 (build 2026-07-07, git 7835222674d5)
- ProxyKey 来源：codex `config.toml` 中 `[model_providers.ccx].experimental_bearer_token`
- 测试模型：`codex`（CCX 内部路由名，实际路由到 `glm-4-flash`）

## 验证结果

| 检查项 | 状态 | 说明 |
|--------|------|------|
| Health | PASS | CCX 健康，uptime=23249s |
| Model List | PASS | 30 个模型可用 |
| Test Model (codex) | PASS | `codex` 在模型列表中 |
| Completion | PASS | `model=codex` 成功返回 `precheck ok`，实际路由到 `glm-4-flash` |

**整体结果**：ALL PASS — CCX 已具备 harness 端到端集成的前提条件。

## CCX 可用模型（部分）

```
codex, kimi-k2.5, kimi-k2.6, deepseek-v4-pro, deepseek-v4-flash,
glm-5, glm-5-turbo, glm-5.1, glm-5.2, glm-4.5, glm-4.5-air, ...
```

## 注意事项

- `upstreamCount=0` 出现在 health 响应中，但实际 completion 能正常返回。这说明 CCX 使用了缓存模型列表 + 按需路由，不是只有 active upstream channel 才能服务。
- `model=codex` 是 CCX 内部路由名，实际响应 `model=glm-4-flash`。codex `config.toml` 当前的 `model = "codex"` 设置让 CCX 自动选择最佳上游。
- `model=glm-5.2` 直接请求返回 503，说明不是所有模型名都有活跃的 upstream channel；但通过 `codex` 路由名可以正常获取 completion。

## 下一动作

1. 启动 harness 服务
2. 创建一个 `codex` worker 的 task
3. 验证 harness -> CCX -> LLM -> loop judge -> decide 端到端闭环
4. 目标：至少拿到一个 `done` 或 `active` 且有实质输出的 task
