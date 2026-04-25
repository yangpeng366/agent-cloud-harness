package com.agentcloud.runtime.policy;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;

import java.util.List;

public interface ActiveContextPolicy {
    ActiveContext build(Task task, ResumePacket packet, Checkpoint checkpoint, List<Event> events, List<Decision> decisions,
                        List<Artifact> artifacts, List<String> learnedHints,
                        RetentionPolicy retentionPolicy, ExclusionPolicy exclusionPolicy);
}
