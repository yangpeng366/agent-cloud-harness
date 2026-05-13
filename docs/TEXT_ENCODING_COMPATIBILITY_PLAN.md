# 文本编码兼容方案

这份文档只回答一件事：

- 当前 harness 里，哪些文本必须强制 UTF-8
- 哪些文本需要做“UTF-8 优先，但允许宿主机本地编码兜底”的兼容处理

它不替代：

- `STARTUP_GUIDE.md`
- `docs/TROUBLESHOOT.md`
- `docs/WEB_CONSOLE.md`

---

## 1. 问题边界

当前仓库里有两类完全不同的文本来源：

### 1.1 仓库内文件 / HTTP / 前端静态资源

这类内容包括：

- `src/main/resources/web/**`
- `schema.sql`
- JSON API request / response
- SSE / façade 输出
- 本地文件工具 `read/write/patch`

这类内容的目标很简单：

- **统一使用 UTF-8**

这里不做“编码猜测”。

原因是：

1. 这些内容由本仓库自己控制
2. 一旦放宽，会把真正的编码错误掩盖掉

### 1.2 外部进程输出

这类内容包括：

- `shell` / `cmd` / `powershell` / `git` tool 的 stdout/stderr
- 本地 CLI provider 的版本探测输出
- provider-native CLI worker 的进程输出

这类内容不能假设永远是 UTF-8。

尤其在 Windows 中文环境下，进程可能仍然输出：

- `GBK`
- `GB18030`
- 宿主机默认 ANSI code page

如果直接按 UTF-8 硬解码，就会出现典型 mojibake，例如：

- `���: û���ҵ����� "15252"��`

而真实含义可能只是：

- `错误: 没有找到线程 "15252"`

---

## 2. 当前策略

### 2.1 文件与 HTTP：继续严格 UTF-8

以下链路继续保持严格 UTF-8：

- 文件读写
- 静态资源分发
- JSON / SSE
- façade 输出

不引入自动探测，不做本地编码回退。

### 2.2 外部进程输出：UTF-8 优先，自适应兜底

外部进程输出统一按下面顺序解码：

1. 先识别 BOM（UTF-8 / UTF-16LE / UTF-16BE）
2. 再做严格 UTF-8 解码
3. 如果严格 UTF-8 失败，再走第三方字符集探测
4. 探测仍不确定时：
   - Windows：优先尝试宿主机默认 charset，再尝试 `GB18030` / `GBK`
   - 非 Windows：回退宿主机默认 charset

这条策略的目标不是“什么都猜对”，而是：

1. **尽量用 UTF-8**
2. 当外部工具明确不是 UTF-8 时，不再把结果直接打成乱码

---

## 3. 第三方依赖引入原则

允许适当引入第三方包，但范围要收紧。

当前建议：

- 引入一个轻量级字符集探测库
- 只用于**外部进程输出**
- 不把探测逻辑扩散到仓库文件读写链路

当前实现方向：

- `juniversalchardet`

原因：

1. 体积小
2. 适合做未知字节流的 charset hint
3. 不改变现有 HTTP / JSON / 文件 UTF-8 主约束

---

## 4. 落点

### 4.1 需要接入自适应解码的链路

- `src/main/java/com/agentcloud/tool/AbstractCommandTool.java`
- `src/main/java/com/agentcloud/agent/providers/LocalCliAgentProvider.java`
- `src/main/java/com/agentcloud/worker/ProviderCliWorkerExecutor.java`

### 4.2 暂时不改的链路

以下链路仍保持 UTF-8 直读：

- `ReadFileTool`
- `WriteFileTool`
- `PatchFileTool`
- `WebConsoleHandler`
- `NioHttpServer`
- app-server 型协议（例如显式 UTF-8 JSON 流）

理由是这些链路的协议/文件归属是明确的，不应为了兼容少数 Windows 控制台输出而放宽。

---

## 5. 发布前检查点

在上 GitHub 前，编码兼容至少要满足：

1. Windows 中文环境下，命令失败输出不再默认变成 mojibake
2. provider/native worker 失败摘要应优先显示可读文本
3. 现有 UTF-8 文件与前端静态资源行为不回退
4. 文档继续明确：
   - 文件/HTTP 用 UTF-8
   - 只有外部进程输出走自适应解码

---

## 6. 当前结论

这次编码兼容不是要把整个项目改成“自动猜编码”。

更准确地说：

- **仓库内部协议继续严格 UTF-8**
- **宿主机外部进程输出改成 UTF-8 优先 + 本地编码兜底**

这样既能保住主链路的一致性，也能把 Windows 中文环境里最常见的命令输出乱码收住。
