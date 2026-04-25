package com.agentcloud.runtime.policy;

import java.util.List;

public interface ExclusionPolicy {
    List<String> apply(List<String> items);
}
