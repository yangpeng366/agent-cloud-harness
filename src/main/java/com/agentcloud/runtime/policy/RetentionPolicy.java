package com.agentcloud.runtime.policy;

import java.util.List;

public interface RetentionPolicy {
    List<String> apply(List<String> items, int limit);
}
