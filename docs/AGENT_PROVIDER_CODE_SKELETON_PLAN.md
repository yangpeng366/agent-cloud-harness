# 代码 Skeleton 设计建议（Agent Provider Phase 1）

## 1. 文档目标

本文档给出 `agent-cloud-harness` 在 **Phase 1** 引入 Agent Provider 能力时，最小可落地的一组代码 skeleton 建议。

目标不是一次性实现全功能，而是：
- 先把核心类型和边界立起来
- 让后续实现有稳定挂点
- 尽量贴合当前 Java 21 + record + 手工装配 + HttpHandler 风格

但需要明确的是：

- 这份文档里的不少骨架已经出现在当前仓库
- 因此它现在更适合作为“现状核对 + 剩余骨架缺口”清单
- 目标是让 provider 线尽快服务于近端的小任务闭环评估，而不是继续停留在纯 scaffold 讨论

建议与以下文档一起看：

- `AGENT_PROVIDER_TECHNICAL_DESIGN.md`
- `AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md`
- `GOAL_ORIENTED_EVAL_PLAN.md`
- `EVAL_SCENARIOS.md`

---

## 2. Phase 1 最小代码目标

第一阶段不追求把所有 provider 都跑通，只追求建立这 5 层骨架：

1. **Provider 领域模型**
2. **Provider Registry**
3. **Provider Discovery**
4. **Agent API Handler**
5. **最小 Provider Skeleton（先 OpenClaw / Codex）**

这样一来，系统就能先支持：
- 列出 provider
- 查看 provider 状态
- 刷新 provider 状态

当前状态比这再往前一步：

- inventory / status / refresh 已有基础 API
- run detail / run events / run artifacts / runtime health 也已有初步读面
- 现在更需要补的是稳定性、契约一致性，以及和任务评估闭环的接线

---

## 3. 建议新增文件清单

以下清单里，部分文件已经存在，部分仍可视为后续增量点：

```text
src/main/java/com/agentcloud/agent/
  AgentProvider.java
  AgentProviderDescriptor.java
  AgentProviderStatus.java
  AgentProviderRegistry.java
  AgentProviderResolver.java
  AgentDiscoveryService.java
  SimpleAgentDiscoveryService.java
  AgentArtifactRef.java
  AgentRunRef.java
  AgentRunResult.java

src/main/java/com/agentcloud/agent/providers/
  OpenClawProvider.java
  CodexProvider.java

src/main/java/com/agentcloud/engine/
  AgentRunService.java

src/main/java/com/agentcloud/server/
  AgentHandler.java
  AgentRunHandler.java
  RuntimeHealthHandler.java
```

仍偏未来态、尚未真正落地的主要是：

- 更完整的 `AgentExecutionPlanner`
- 更强的 provider runtime supervisor
- 更多 provider 实现
- provider inventory 的独立持久化

---

## 4. 代码风格约束

从现有仓库看，建议遵循：
- 简单对象优先用 `record`
- 逻辑服务用普通 class
- 不引入 Spring / DI 容器
- 在 `Main.java` 里手工装配
- JSON 输出继续走 `NioHttpServer.SHARED_MAPPER`
- 命名保持直白，不先做抽象工厂泛滥

---

## 5. Skeleton 设计

## 5.1 AgentProviderDescriptor.java

职责：描述 provider 的静态信息。

建议：
```java
package com.agentcloud.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentProviderDescriptor(
    String providerId,
    String displayName,
    String providerType,
    String transport,
    List<String> capabilities,
    Map<String, Object> metadata
) {
    public AgentProviderDescriptor {
        if (providerId == null) providerId = "";
        if (displayName == null || displayName.isBlank()) displayName = providerId;
        if (providerType == null || providerType.isBlank()) providerType = "local_cli";
        if (transport == null || transport.isBlank()) transport = "process";
        if (capabilities == null) capabilities = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
```

---

## 5.2 AgentProviderStatus.java

职责：描述 provider 当前可用状态。

建议：
```java
package com.agentcloud.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentProviderStatus(
    String providerId,
    boolean installed,
    String version,
    String authStatus,
    boolean ready,
    String readinessReason,
    Instant checkedAt,
    Map<String, Object> metadata
) {
    public AgentProviderStatus {
        if (providerId == null) providerId = "";
        if (authStatus == null || authStatus.isBlank()) authStatus = "unknown";
        if (checkedAt == null) checkedAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }
}
```

---

## 5.3 AgentProvider.java

职责：定义统一 provider contract。

Phase 1 先只保留最小接口：

```java
package com.agentcloud.agent;

public interface AgentProvider {
    AgentProviderDescriptor descriptor();

    AgentProviderStatus detect();

    default AgentProviderStatus refreshStatus() {
        return detect();
    }
}
```

### 为什么先这么小
因为第一阶段主要目标是：
- inventory
- detect
- readiness
- auth status

先不急着把 `runTask()`、`createSession()` 全塞进来，否则 skeleton 会很重。
后续第二阶段再扩展更稳。

---

## 5.4 AgentProviderRegistry.java

职责：集中管理 provider 实例。

建议：
```java
package com.agentcloud.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentProviderRegistry {
    private final Map<String, AgentProvider> providers = new LinkedHashMap<>();

    public AgentProviderRegistry register(AgentProvider provider) {
        if (provider == null) {
            return this;
        }
        providers.put(provider.descriptor().providerId(), provider);
        return this;
    }

    public AgentProvider get(String providerId) {
        return providers.get(providerId);
    }

    public List<AgentProvider> list() {
        return new ArrayList<>(providers.values());
    }

    public List<AgentProviderStatus> listStatuses() {
        return providers.values().stream()
            .map(AgentProvider::detect)
            .toList();
    }

    public AgentProviderStatus refresh(String providerId) {
        AgentProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("provider not found");
        }
        return provider.refreshStatus();
    }
}
```

---

## 5.5 AgentDiscoveryService.java

职责：定义探测接口。

```java
package com.agentcloud.agent;

import java.util.List;

public interface AgentDiscoveryService {
    AgentProviderStatus detect(AgentProvider provider);

    List<AgentProviderStatus> detectAll();
}
```

---

## 5.6 SimpleAgentDiscoveryService.java

职责：第一阶段最小实现。

它可以非常简单：
- 持有 `AgentProviderRegistry`
- 直接调用 provider.detect()

```java
package com.agentcloud.agent;

import java.util.List;

public class SimpleAgentDiscoveryService implements AgentDiscoveryService {
    private final AgentProviderRegistry registry;

    public SimpleAgentDiscoveryService(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AgentProviderStatus detect(AgentProvider provider) {
        return provider.detect();
    }

    @Override
    public List<AgentProviderStatus> detectAll() {
        return registry.list().stream()
            .map(AgentProvider::detect)
            .toList();
    }
}
```

---

## 5.7 OpenClawProvider.java

职责：作为第一个 skeleton provider。

Phase 1 只需要返回：
- descriptor
- detect status

探测策略可以简单到：
- 认为 embedded/openclaw 默认 installed=true
- authStatus=`unsupported` 或 `ok`
- ready=true

```java
package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class OpenClawProvider implements AgentProvider {
    @Override
    public AgentProviderDescriptor descriptor() {
        return new AgentProviderDescriptor(
            "openclaw",
            "OpenClaw",
            "embedded",
            "inproc",
            List.of("chat", "tool", "session", "orchestration"),
            Map.of("model_tier", "orchestrator")
        );
    }

    @Override
    public AgentProviderStatus detect() {
        return new AgentProviderStatus(
            "openclaw",
            true,
            "runtime",
            "ok",
            true,
            null,
            Instant.now(),
            Map.of("source", "embedded_runtime")
        );
    }
}
```

---

## 5.8 CodexProvider.java

职责：本地 CLI skeleton provider。

Phase 1 可以先不真的启动 codex，只做安装/版本探测。

建议策略：
- 尝试执行 `codex --version`
- 成功则 installed=true
- 失败则 installed=false
- authStatus 先保守用 `unknown`

如果不想一开始写复杂进程调用，也可以先硬编码 stub，再第二步替换。

建议第一版就先做一个最小 stub：

```java
package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class CodexProvider implements AgentProvider {
    @Override
    public AgentProviderDescriptor descriptor() {
        return new AgentProviderDescriptor(
            "codex",
            "Codex",
            "local_cli",
            "pty",
            List.of("chat", "code", "patch", "session"),
            Map.of("model_tier", "strong", "binary", "codex")
        );
    }

    @Override
    public AgentProviderStatus detect() {
        return new AgentProviderStatus(
            "codex",
            false,
            null,
            "unknown",
            false,
            "provider probe not implemented yet",
            Instant.now(),
            Map.of("binary", "codex")
        );
    }
}
```

后续再把 `detect()` 替换成真实 probe。

---

## 5.9 AgentHandler.java

职责：暴露 `/api/v1/agents` 相关接口。

当前至少应覆盖：
- `GET /api/v1/agents`
- `GET /api/v1/agents/{id}`
- `POST /api/v1/agents/{id}/refresh`
- `GET /api/v1/agents/{id}/runs`

实现风格应对齐现有 `WorkerHandler` / `TaskHandler`。

Skeleton 行为建议：

### GET `/api/v1/agents`
返回：
- `registry.list()` + `provider.detect()` 投影结果

### GET `/api/v1/agents/{id}`
返回：
- 单个 provider descriptor + detect status 合并视图

### POST `/api/v1/agents/{id}/refresh`
返回：
- `registry.refresh(id)` 结果

---

## 6. Main.java 装配建议

当前 `Main.java` 已经是统一装配入口，所以接入点很清晰。

这部分骨架现在多数已经存在；如果继续补硬，装配形态仍建议保持：

```java
AgentProviderRegistry agentProviderRegistry = new AgentProviderRegistry()
    .register(new OpenClawProvider())
    .register(new CodexProvider());

AgentDiscoveryService agentDiscoveryService = new SimpleAgentDiscoveryService(agentProviderRegistry);
```

然后把 `agentProviderRegistry` 以及 run 相关服务传给 `NioHttpServer`。

---

## 7. NioHttpServer.java 改造建议

当前已有：
- `/api/v1/tasks`
- `/api/v1/sessions`
- `/api/v1/workers`

当前不应只停在 `/api/v1/agents`，至少还要保持：

```java
server.createContext("/api/v1/agents", new AgentHandler(agentProviderRegistry, agentRunService, mapper));
server.createContext("/api/v1/agent_runs", new AgentRunHandler(agentRunService));
server.createContext("/api/v1/runtime_health", new RuntimeHealthHandler(agentRunService));
```

如果路径匹配逻辑和其他 handler 一样，这一步改动会很小。

---

## 8. Phase 1 最小实现顺序

前 5 步基础骨架已经基本出现，后续更合适的顺序是：

### Step 6
把 provider 维度继续补进 task/live-flow/experiment 相关聚合读面

### Step 7
补 provider run failure 分类、auth/readiness 语义、返回契约一致性

### Step 8
让这套骨架直接服务 `GOAL_ORIENTED_EVAL_PLAN.md` 中的小型真实任务闭环 proof

---

## 9. 第二阶段自然扩展点

当前阶段之后，可以顺着扩展：

### 9.1 扩展 AgentProvider 接口
新增：
- `createSession()`
- `runTask()`
- `getRun()`
- `listArtifacts()`

### 9.2 把 provider 维度继续注入 task detail / route trace / live flow
让任务和 provider 建立更完整的解释链。

### 9.3 收紧真实 probe
例如把 `CodexProvider` 从 stub readiness 逐步收敛到真实探测。

---

## 10. 风险控制建议

### 不要一开始就做太全
如果 Phase 1 就试图把 run/session/artifact 全打通，范围会迅速失控。

### 不要和 Worker 体系硬耦合
provider skeleton 先独立起来，再考虑 route trace 集成。

### 先让读面服务评估
这条线现在不只是“把 `/api/v1/agents` 跑起来”，而是要让 `/agents`、`/agent_runs`、`/runtime_health`、`/tasks/{id}/provider_selection` 一起支撑近端闭环证明。

---

## 11. 结论

如果继续推进这条线，当前最小且正确的切口不是重新搭骨架，而是：

- 校对现有 skeleton 与 API 契约是否一致
- 把 provider 读面继续接到 task / live flow / experiment
- 收紧 probe、readiness、failure 分类
- 让这些读面直接服务小任务闭环评估

这一步仍然很小，但意义更直接，因为它会让 `agent-cloud-harness` 的 provider 线从“能看见”走到“能拿来证明 orchestrated 闭环价值”。

也就是从“有 orchestration 核心”走向“有多 Agent 管理外壳”的第一块真实代码基础。
