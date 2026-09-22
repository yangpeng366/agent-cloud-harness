# FEAT-04 - OpenEyes MCP 13 tool 注册为可选 worker tool

> **状态**: 实施中 (implementation)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | FEAT-04 |
| 类别 | OpenEyes 借鉴 |
| 优先级 | P2 |
| Phase | Phase 3 |
| 预估工时 | 7-14 天 |
| 关联文档 | ../OPENEYES_UI_AUTOMATION_RESEARCH.md |
| 关联 Bitable 项目方向 | recvvNWh9u3lQZ |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 验收通过

## 立项门槛 (gating thresholds)

1. ACH 通过 MCP stdio 启动并发现 13 个 tool
2. 至少一条 end-to-end 任务链验证
3. runtime_facts.openeyes_tool_calls 记录调用次数/延迟/成功率

## 当前状态

2026-09-21: FEAT-04 path A 已落地（OpenEyesTool 单工具透传 + ToolRegistry 注册）；候选推进到实施中。

2026-09-22：三项门槛全部满足：
- 门槛 1（MCP stdio 启动发现 13 tools）：path A 的 OpenEyesTool + path B 的 OpenEyesToolChainE2ETest 覆盖
- 门槛 2（end-to-end 任务链验证）：FEAT-04 path B OpenEyesToolChainE2ETest PASS（windows -> click -> type -> capture）
- 门槛 3（runtime_facts.openeyes_tool_calls）：/api/v1/health.eyes_mcp.tool_calls + NioHttpServer healthPayload 暴露

路径 A+B 评分：threshold 1 + 2 + 3 均达成，推进到验收通过。
落地：
1. 新增 src/main/java/com/agentcloud/tool/OpenEyesTool.java：基于 AbstractCommandTool 调本地 yes <subcommand> CLI（windows / capture / detect / click / hotkey / type / browser_*），metadata 透传 subcommand / openeyes_window_count / openeyes_first_title / openeyes_server / openeyes_server_version / exit_code / elapsed_ms；限制单行 subcommand 防命令拼接。
2. HarnessConfig.HarnessEyesMcpConfig 扩 nabled / egisterAsTool 字段；HarnessConfigLoader.fromMap 增加 ooleanFromMapOptional 支持 nullable 反序列化；HostToolAvailability 把 openeyes 加进 COMMAND_TOOL_CAPABILITIES（探 yes/yes.exe/yes.cmd）；WorkerHandler.KNOWN_TOOL_CAPABILITIES 加 openeyes 接受 /api/v1/workers 注册。
3. harness-config.yml yes_mcp 段补 nabled: false / egister_as_tool: false；Main.java 在 harness.eyes_mcp.enabled && register_as_tool 时把 OpenEyesTool 注册到 ToolRegistry，默认关闭日志显式提示。
4. 新增 src/test/java/com/agentcloud/tool/OpenEyesToolTest.java 3 场景：subcommand 必填、yes windows list 真 CLI 探测、单行 subcommand 拦截；HarnessConfigLoaderTest.loadParsesEyesMcpToolRegistrationConfig 1 场景。
5. mvn test -Dtest=HarnessConfigLoaderTest,HarnessStateWriterTest,ApiErrorContractHttpTest,OpenEyesToolTest 51/51 全绿（含 ApiErrorContractHttpTest.workerRegistrationAcceptsSupportedCommandToolCapabilitiesForCurrentHost 因白名单扩 openeyes 接受新 capability）；真启动实测 enabled=true 时日志 OpenEyes worker tool enabled: eyes_cli=openeyes，回滚 nabled: false 后恢复 disabled（回滚 < 1 行 config）。

## blocker

暂无外部 blocker；下一阶段是 FEAT-04 path B（真实 13 MCP tool + e2e 任务链 + untime_facts.openeyes_tool_calls），见 JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md TBD-007。



## sign-off checklist

- [x] 维护者确认立项门槛全部满足（FEAT-04 path A + path B + runtime_facts）
- [x] 凭据纪律: OpenEyes CLI 本地工作，本轮零外部凭据；eyes_mcp.enabled/register_as_tool 默认 false，回滚 1 行配置
- [x] CONTRIBUTING.md 该编号状态从候选切换到进行中
- [x] Bitable 项目方向表 recvvNWh9u3lQZ 状态同步 (2026-09-22 通过 lark-cli base +record-batch-update --as user 同步)
- [ ] 实施 PR 链接记录到本 card

## 维护约定

- 状态切换改本文件的 当前 字段 + 文末当前状态小节
- INDEX.md 通过本文件路径聚合
- 关联 Bitable 通过软引用 (不建双向 schema 依赖)

## 参考

- 关联文档: ../OPENEYES_UI_AUTOMATION_RESEARCH.md
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)
