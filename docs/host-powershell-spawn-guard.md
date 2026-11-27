# 宿主级 powershell 子进程创建挂死：开关与判定

- 建立日期：2026-11-28
- 影响范围：测试链路（`CommandToolExecutionTest`）+ 生产侧 `PowerShellTool` 的宿主可用性前提
- 关联方向：AI 时代代码形态与运行时降级（B 向运行时）

## 1. 现象

本机（Windows 11 + JDK 21.0.9）上，`new ProcessBuilder("powershell.exe", ...).start()` **永不返回**：

- 子进程被创建出来了（`Get-CimInstance Win32_Process` 可见，命令行正确），但初始线程停在 `Initialized` 状态，CPU 0.00，不再推进；
- 主线程栈停在 `java.lang.ProcessImpl.create(Native Method)`；
- 该线程**持有 `ProcessImpl` 的类锁**，于是同一 JVM 内后续任何 `ProcessBuilder.start()`
  （git / cmd 用例，乃至其它测试类）都 `BLOCKED` 在同一把锁上 ——
  整套 `mvn test` 从「一个用例挂起」退化为「全 JVM 进程创建死亡」，实测挂 20 分钟无输出。

对照实验结果（同一台机器、同一 JDK）：

| 场景 | 结果 |
|---|---|
| 原生 shell（PowerShell `Start-Process`）启动 powershell.exe | 2.0 s，exit 0 |
| `cmd.exe /d /c powershell.exe ...` | 2 s 内正常返回 |
| Java `ProcessBuilder("cmd.exe", "/d", "/c", "echo ...")` | 62 ms，exit 0 |
| Java **直接** `ProcessBuilder("powershell.exe", ...)` | 永不返回（native create 挂死） |
| Java 启动 `.cmd` 批处理（含 `-o` 收尾） | 正常，exit 7 |

结论：不是 powershell 坏了，也不是项目代码逻辑问题，而是**该宿主上 Java→powershell 的进程创建链路**被卡住
（疑似宿主安全防护链在 CreateProcess 阶段拦截 powershell 镜像）；由于卡在 native 且持类锁，
任何「加超时 + 放弃」的写法都救不了后续用例（超时线程仍持锁）。

## 2. 处置

### 2.1 测试侧开关（本次落地）

`CommandToolExecutionTest.powerShellToolRunsOnWindows` 加显式开关，**默认启用**：

| 配置项 | 默认 | 含义 |
|---|---|---|
| `ach.host.powershell.spawn` | `true` | `false` 时跳过该用例（本机必须设 false，否则全量回归被毒化） |

用法：

```
mvn -o -B -Dmaven.repo.local=D:/_ach-m2 test -Dach.host.powershell.spawn=false
```

健康宿主保持默认即可，覆盖不丢。判定口径是「命令行显式开关」而非自动探测——
自动探测本身也要 `ProcessBuilder.start()`，一旦命中就同样毒化 JVM，不能用作前置判断。

### 2.2 同时拆开的用例

原 `powerShellAndCmdToolsRunOnWindows` 把 powershell 与 cmd 断言耦合在一个方法里，
powershell 不可用时连 cmd 覆盖一起丢。现拆为两个用例：
`powerShellToolRunsOnWindows`（可开关跳过）/ `cmdToolRunsOnWindows`（恒跑）。

## 3. 复现命令

```
# 1) 确认宿主是否命中（预期挂住 / 不返回）
"C:\Program Files\Java\jdk-21.0.9+10\bin\java.exe" -e  # 用任意最小 java 程序调用 ProcessBuilder("powershell.exe","-NoLogo","-NonInteractive","-Command","exit 0")

# 2) 抓栈确认卡点
"C:\Program Files\Java\jdk-21.0.9+10\bin\jstack.exe" <pid>   # 找 ProcessImpl.create(Native Method)

# 3) 清理挂死子进程（挂起的 powershell 会占住父进程的 stdout 管道）
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Stop-Process -Id {$_.ProcessId} -Force
```

## 4. 回滚

- 删除 `CommandToolExecutionTest` 中 `POWERSHELL_SPAWN_FLAG` 相关的 `Assumptions` 行即可恢复原行为；
- 该开关只影响测试，不改生产 `PowerShellTool` 行为。

## 5. 已知关联问题（未在本文件处理）

- **生产风险（high）**：若部署宿主命中同样的挂死，`PowerShellTool.invoke` 会在 `executeProcess` 的
  `builder.start()` 处永久阻塞工作线程（`waitFor(timeoutMs)` 兜不住 `start()`），
  且同 JVM 内其它命令类工具随后全部阻塞。规避手段是不要把 powershell 当作生产必需工具，
  或后续在部署期做一次「一次性进程创建探针」（在独立 JVM 中跑，避免毒化主 JVM）。
- `eyes` CLI 在中文 Windows 上非 tty 输出按 cp936 编码，遇到 U+200B 会 `UnicodeEncodeError` 退出码 1。
  已在 `OpenEyesTool` 侧通过子进程环境 `PYTHONIOENCODING=utf-8` / `PYTHONUTF8=1` 修复（2026-11-28）。
