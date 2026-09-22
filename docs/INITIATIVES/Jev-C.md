# Jev-C - Jev x 项目间 keep_probability 联动网 (长期脑洞)

> **状态**: 候选 (candidate)
> **创建**: 2026-09-21
> **维护者**: yangpeng@sobey.com

## 基本信息

| 字段 | 值 |
|---|---|
| 编号 | Jev-C |
| 类别 | 脑洞 |
| 优先级 | P2 |
| Phase | 长期 |
| 预估工时 | 30+ 天 |
| 关联文档 | ../JEV_PATROL_INTEGRATION_PLAN.md §3 脑洞 C |
| 关联 Bitable 项目方向 | recvvP9I0XrgTJ |

## 状态机

```
候选 (candidate) -- 维护者签字 --> 立项中 -- 开始实施 --> 实施中 -- PR --> 验收中 -- 通过 --> 已关闭
                            |
                            `-- 撤回 --> 已撤销
```

**当前**: 候选

## 立项门槛 (gating thresholds)

1. L4 完成
2. 项目间引用网计算
3. 自动发现 项目间共享 learnings

## 当前状态

(待签字)

## blocker

Jev-Graph-A 未完成

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

- 关联文档: ../JEV_PATROL_INTEGRATION_PLAN.md §3 脑洞 C
- 设计依据: docs/JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md
- 本地落仓决策依据: 2026-09-21 Codex session (落仓优于新建 Bitable 表, 等 schema 稳定后迁)
