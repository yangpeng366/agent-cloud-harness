# `/dialogue/` Chat-First + Chat Facade 验收记录模板

> 用途：把 [DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md](./DIALOGUE_CHAT_FACADE_ACCEPTANCE_RUNBOOK.md)
> 里的自动化证据和 8 条真实页面路径验收，收成一次可归档的记录。

---

## 1. 基本信息

- 日期：
- 执行人：
- 分支 / 提交：
- 运行环境：
  - Java：
  - 端口：
  - façade surface：
- 相关命令：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-ChatFacadeAcceptanceWithLocalHarness.ps1 -SkipBuild -Port _____`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WebConsoleHandlerHttpTest,ChatFacadeHandlerHttpTest'`

---

## 2. 自动化证据

### 2.1 Local Harness Runner

- [ ] `dialogue_shell_probe` 通过
- [ ] `chat_probe` 通过
- [ ] `responses_probe` 通过

输出摘要：

```json
{
  "base_url": "",
  "dialogue_shell_probe": {},
  "chat_probe": {},
  "responses_probe": {}
}
```

### 2.2 Java HTTP / Handler 合同

- [ ] `WebConsoleHandlerHttpTest`
- [ ] `ChatFacadeHandlerHttpTest`

命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WebConsoleHandlerHttpTest,ChatFacadeHandlerHttpTest'
```

结果摘要：

```text
<填写通过/失败摘要>
```

### 2.3 前端 smoke

- [ ] `dialogue-shell-markup-plan.test.mjs`
- [ ] `dialogue-composer-markup-plan.test.mjs`
- [ ] `dialogue-composer-plan.test.mjs`
- [ ] `dialogue-composer-request-plan.test.mjs`
- [ ] `dialogue-facade-surface-plan.test.mjs`
- [ ] `dialogue-facade-client-plan.test.mjs`
- [ ] `dialogue-facade-response-plan.test.mjs`
- [ ] `dialogue-facade-stream-plan.test.mjs`
- [ ] `dialogue-facade-reply-kind.test.mjs`
- [ ] `dialogue-facade-reply-plan.test.mjs`
- [ ] `dialogue-facade-reply-highlight-plan.test.mjs`
- [ ] `dialogue-facade-reply-ui-consistency.test.mjs`
- [ ] `dialogue-phase6-path-matrix.test.mjs`
- [ ] `dialogue-responses-path-matrix.test.mjs`

结果摘要：

```text
<填写通过/失败摘要>
```

---

## 3. 真实页面路径验收

> 这里的路径名称与 runbook 保持一致。每条路径至少记录：
> - 是否通过
> - 页面入口
> - 观察到的 UI 反馈
> - 若失败，失败点在哪一层：shell / request / response / affordance / continuity

### A. `message_only`

- [ ] 通过
- 页面入口：
- 输入：
- 观察结果：
- 备注：

### B. `message_only + task_id`

- [ ] 通过
- 页面入口：
- 输入：
- 观察结果：
- 备注：

### C. `task_required`

- [ ] 通过
- 页面入口：
- 输入：
- 观察结果：
- 备注：

### D. `follow-up + manual-start`

- [ ] 通过
- 页面入口：
- 输入：
- 观察结果：
- 备注：

### E. `manual-start continuity`

- [ ] 通过
- 页面入口：
- 输入：
- 观察结果：
- 备注：

### F. `stream fallback`

- [ ] 通过
- 页面入口：
- Network / 观察结果：
- 是否只出现一次请求：
- 备注：

### G. `#facade=responses + message_only`

- [ ] 通过
- 页面入口：
- 输入：
- 观察结果：
- 备注：

### H. `#facade=responses + task_required`

- [ ] 通过
- 页面入口：
- 输入：
- 观察结果：
- 备注：

---

## 4. 缺口与结论

### 4.1 未覆盖项

- [ ] token-level streaming 仍未验收
- [ ] 完整 `/v1/responses` item/tool-call surface 仍未验收
- [ ] 其余：

### 4.2 最终判断

- [ ] 本轮仅自动化通过，仍缺真实页面验收
- [ ] 本轮真实页面验收已完成，可作为 Phase 5/6 completion evidence

补充说明：

```text
<填写最终结论>
```
