# OpenEyes CLI 环境与 JSON 契约（ach 侧说明）

> 状态: 现行
> 创建: 2026-11-17
> 适用: `scripts/Run-OpenEyesUiAssertion.ps1`、`scripts/Run-NativeAppAcceptanceProbe.ps1`、`scripts/Run-OpenEyesTypeVerified.ps1`，以及经 `Invoke-ScreenshotDiffAssertion` 触达它们的 `Run-BaselineMatrixRealWorkerSmoke.ps1` / `Run-FlakyComparison.ps1`
> 共享实现: `scripts/lib/OpenEyesCli.ps1`（合同测试 `tests/Test-OpenEyesCli.ps1`）

## 1. 用途

这三个 acceptance 脚本都以 `eyes` CLI 为数据源。2026-11-17 巡检发现 `eyes` 已到 0.2.0，与脚本原先的假设有三处不兼容，导致本机所有 OpenEyes 验收脚本直接失败（此前的"验收通过"证据在当前机器上不可复现）。本文记录修复后的调用约定，避免再次静默漂移。

## 2. 关键环境变量（由 `Initialize-OpenEyesCliEnvironment` 自动设置，仅作用于本进程及其子进程）

| 变量 | 值 | 作用 | 不设置的后果 |
|---|---|---|---|
| `PYTHONIOENCODING` | `utf-8` | 让 `eyes` 的 stdout 用 UTF-8 | zh-CN 控制台默认 gbk；窗口标题含 U+200B/emoji 时 `UnicodeEncodeError` 使整条命令失败 |
| `PYTHONUTF8` | `1`（仅当未设置） | Python UTF-8 mode 兜底 | 同上（次要） |
| `PYTHONPATH` | openeyes 源码根（仅当探针判定 `module_missing`） | 绕开失效的 editable 安装 | `ModuleNotFoundError: No module named 'openeyes'` |

源码根发现顺序：`-OpenEyesSourceRootCandidates` → `$env:OPENEYES_REPO` → `D:\gitAll\openeyes` → `E:\gitAll\openeyes` → `D:\gitAll\openeyes-staging` → `$env:USERPROFILE\openeyes`；只认含 `openeyes\__init__.py` 的目录。

**永久修复（人工，一次性）**：`python -m pip install -e D:\gitAll\openeyes`（当前 editable 安装的映射还指向老的 `E:\gitAll\openeyes`）。在此之前，脚本的 `PYTHONPATH` 兜底可以独立工作。

## 3. JSON 契约

- 机器解析必须显式传 `--json`；不传时 `windows list` / `detect` 输出**人类可读表格**，`ConvertFrom-Json` 会抛错。
- `--json` 输出**信封**，不是裸数组：
  - `eyes windows list --json` → `{ ok, count, windows: [ {hwnd,pid,title,class_name,x,y,w,h} ] }`
  - `eyes detect --window <hwnd> --json` → `{ ok, hwnd, count, elements: [ {control_type,name,automation_id,bbox,center,state:{enabled,visible,...}} ] }`
- `ok=false` 视为失败，错误串取自 `error`/`message`/`detail`。
- `ConvertFrom-OpenEyesPayload` 同时容忍三种旧形态：裸数组、单条记录、NDJSON 逐行；表格文本一律拒绝并报 `cannot parse eyes output as JSON`。

## 4. 探针与失败分类

`Initialize-OpenEyesCliEnvironment` 用 `eyes --version` 做探针（不一上来就枚举窗口），分类：

| status | 触发 | 处置 |
|---|---|---|
| `ok` | 探针 exit 0 | 正常执行，版本写入报告 `eyes_cli.version` |
| `not_found` | PATH 无 `eyes` | 报告 `remediation` 提示 `pip install -e` 或 `-EyesBin` |
| `module_missing` | stderr 含 `No module named 'openeyes'` | 找到源码根后自动注入 `PYTHONPATH` 并重探，`recovered=true` |
| `console_encoding` | stderr 含 `UnicodeEncodeError` | 提示 `PYTHONIOENCODING=utf-8` |
| `failed` | 其它非零退出 | 人工跑一次看 stderr |

验收脚本在 CLI 不可用时**fail-fast**：不再进入业务断言，报告里 `eyes_cli` 字段直接给出状态、原因、修复建议。

## 5. 默认值 / 开关

| 参数 | 默认 | 说明 |
|---|---|---|
| `-EyesBin` | `eyes` | 可指向具体 `eyes.exe` |
| `-EyesTimeoutSec` | `60` | 单次 `eyes` 子进程超时，超时即 kill 并记 `timed_out` |
| `-SkipCliEnvSetup` | 关 | 跳过环境初始化（仅排障用；此时脚本退化为旧行为） |
| `-OpenEyesSourceRootCandidates` | 空 | 追加自定义源码根候选 |

## 6. 回滚

删掉 `scripts/lib/OpenEyesCli.ps1` 并给三个脚本加 `-SkipCliEnvSetup` 即可回到旧行为，但旧行为在当前 CLI 版本下不可用（表格输出 + gbk 崩溃）。因此建议回滚方向是"修环境"（`pip install -e`），不是"回滚代码"。

## 7. 生效环境与凭据

- 生效环境: 本机 Windows（需要交互式桌面会话，UIA 依赖桌面）；CI 不跑这三个脚本。
- 凭据: 零外部凭据，全本地 CLI。
