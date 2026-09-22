package com.agentcloud.worker;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerWorkerExecutorInterruptReadinessTest {

    @Test
    void interruptedReaderReturnsNullBeforeHardDeadline() throws Exception {
        Class<?> sessionClass = Class.forName("com.agentcloud.worker.CodexAppServerWorkerExecutor$JsonRpcSession");
        var constructor = sessionClass.getDeclaredConstructor(
            java.io.Writer.class,
            BufferedReader.class,
            java.io.OutputStream.class
        );
        constructor.setAccessible(true);
        var reader = new BufferedReader(new StringReader(""));
        Object session = constructor.newInstance(
            new java.io.StringWriter(),
            reader,
            new java.io.ByteArrayOutputStream()
        );

        var nextEnvelope = sessionClass.getDeclaredMethod("nextEnvelope", long.class);
        nextEnvelope.setAccessible(true);

        Thread executor = new Thread(() -> {
            try {
                nextEnvelope.invoke(session, System.currentTimeMillis() + 2_000L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "interrupt-readiness-test-reader");
        executor.start();
        Thread.sleep(50);
        executor.interrupt();
        executor.join(3_000L);

        assertTrue(!executor.isAlive(), "interrupted reader thread should finish before the hard deadline");
        Object result = nextEnvelope.invoke(session, System.currentTimeMillis() + 1_000L);
        assertNull(result, "interrupted reader should return null instead of blocking until deadline");
    }
}