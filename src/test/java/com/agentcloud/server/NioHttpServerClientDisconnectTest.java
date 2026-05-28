package com.agentcloud.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioHttpServerClientDisconnectTest {

    @Test
    void detectsKnownClientDisconnectMessages() {
        assertTrue(NioHttpServer.isClientDisconnect(new IOException("Broken pipe")));
        assertTrue(NioHttpServer.isClientDisconnect(new IOException("Connection reset")));
        assertTrue(NioHttpServer.isClientDisconnect(
            new IOException("你的主机中的软件中止了一个已建立的连接。")
        ));
        assertTrue(NioHttpServer.isClientDisconnect(
            new IOException("outer", new IOException("An established connection was aborted by the software in your host machine"))
        ));
    }

    @Test
    void doesNotTreatUnrelatedIoAsDisconnect() {
        assertFalse(NioHttpServer.isClientDisconnect(new IOException("disk full")));
    }
}
