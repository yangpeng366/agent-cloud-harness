package com.agentcloud.judgment.model.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * M0 Shadow 默认 sink。append 即追加一行 JSON，UTF-8 无 BOM，失败抛 IllegalStateException 不静默吞掉。
 * 内部 ring 默认 cap=1024，仅用于本地回放与测试，磁盘 JSONL 才是事实。
 */
public class JsonlDecisionEventSink implements DecisionEventSink {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path filePath;
    private final Object writeLock = new Object();
    private final List<DecisionEvent> ring = new ArrayList<>();
    private final int ringCap;

    public JsonlDecisionEventSink(Path filePath) {
        this(filePath, 1024);
    }

    public JsonlDecisionEventSink(Path filePath, int ringCap) {
        this.filePath = filePath;
        this.ringCap = ringCap;
    }

    @Override
    public void append(DecisionEvent event) {
        synchronized (writeLock) {
            try {
                Path parent = filePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (BufferedWriter w = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(MAPPER.writeValueAsString(event));
                    w.newLine();
                }
            } catch (IOException e) {
                throw new IllegalStateException("DecisionEvent append failed: " + filePath, e);
            }
            ring.add(event);
            if (ring.size() > ringCap) {
                ring.remove(0);
            }
        }
    }

    @Override
    public List<DecisionEvent> drainSnapshot() {
        synchronized (writeLock) {
            return Collections.unmodifiableList(new ArrayList<>(ring));
        }
    }

    public Path filePath() {
        return filePath;
    }
}