package com.agentcloud.engine;

import java.util.UUID;

public final class IdGenerator {
    private IdGenerator() {}

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String newId(String prefix) {
        return prefix + "_" + newId().substring(0, 16);
    }
}
