# Jev-A - Jev x 飞书巡检假阳性减少 (长期脑洞)

> **状态**: 候选 (candidate)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | Jev-A |
| 类别 | 脑洞 |
| 优先级 | P2 |
| Phase | 长期 |
| 预估工时 | 30+ 天 |
| 关联文档 | ../JEV_PATROL_INTEGRATION_PLAN.md §3 脑洞 A |
| 关联 Bitable 项目方向 | recvvP9I0XrgTJ |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 候选

## 立项门槛 (gating thresholds)

1. L2 跑通 1 个月
2. 跳过即跳过; 发送限于 progress>=0.7
3. 静默汇总到每日摘要

## 当前状态

(待签字)

## blocker

Jev-Patrol-L2 未跑通

## sign-off checklist

- [ ] 维护者确认立项门槛全部满足
- [ ] 凭据纪律: key 仍按 ~/.openclaw/secrets/ 凭据约定存放
- [ ] CONTRIBUTING.md 该编号状态从候选切换到进行中
- [ ] Bitable 项目方向表 recvvP9I0XrgTJ 状态同步
- [ ] 实施 PR 链接记录到本 card

## 维护约定

- 状态切换改本文件的 当前 字段 + 文末当前状态小节
- INDEX.md 通过本文件路径聚合
- 关联 Bitable 通过软引用 (不建双向 schema 依赖)

## 参考

- 关联文档: ../JEV_PATROL_INTEGRATION_PLAN.md §3 脑洞 A
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)
