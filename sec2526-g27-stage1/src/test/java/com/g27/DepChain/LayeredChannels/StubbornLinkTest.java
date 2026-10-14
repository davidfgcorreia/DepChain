package com.g27.DepChain.LayeredChannels;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class StubbornLinkTest {

    /**
     * A message added to the pending set must be retransmitted.
     * After stopSend() the retransmissions must stop.
     */
    @Test
    void retransmitsUntilStopped() throws Exception {
        System.out.println("Starting StubbornLinkTest.retransmitsUntilStopped");
        StubbornLink sender   = new StubbornLink(5300, 0);
        StubbornLink receiver = new StubbornLink(5301, 0);

        byte[] payload = "retransmit-me".getBytes(StandardCharsets.UTF_8);
        List<byte[]> received = new ArrayList<>();

        Thread collector = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Message m = receiver.receive(); // blocks until next packet
                    synchronized (received) {
                        received.add(m.getData());
                    }
                }
            } catch (Exception ignored) {}
        });
        collector.setDaemon(true);
        collector.start();

        // initial timeout = 100 ms, doubles each round
        sender.send(new Message(payload, InetAddress.getLocalHost(), 5301), 42);
        Thread.sleep(600);

        int countDuringRetransmit;
        synchronized (received) {
            countDuringRetransmit = received.size();
        }
        assertTrue(countDuringRetransmit > 1,
                "StubbornLink should have retransmitted; got " + countDuringRetransmit + " copy/copies");

        // stop
        sender.stopSend(42);
        Thread.sleep(600);

        int countAfterStop;
        synchronized (received) {
            countAfterStop = received.size();
        }
        assertEquals(countDuringRetransmit, countAfterStop,
                "No new messages should arrive after stopSend()");

        collector.interrupt();
        sender.close();
        receiver.close();
    }
}
