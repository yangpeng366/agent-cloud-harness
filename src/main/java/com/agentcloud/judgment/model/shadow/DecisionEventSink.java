package com.agentcloud.judgment.model.shadow;

import java.util.List;

/**
 * DecisionEvent 写入抽象。M0 默认实现 JsonlDecisionEventSink；后续由 MySQL outbox（参见第 47 号笔记）或 Kafka 替代。
 */
public interface DecisionEventSink {
    void append(DecisionEvent event);
    List<DecisionEvent> drainSnapshot();
}