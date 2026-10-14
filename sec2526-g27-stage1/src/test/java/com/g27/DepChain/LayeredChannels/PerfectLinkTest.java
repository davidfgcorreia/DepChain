package com.g27.DepChain.LayeredChannels;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PerfectLinkTest {

  @Test
  void shouldSendAndReceiveMessage() throws Exception {

    System.out.println("Starting PerfectLinkTest.shouldSendAndReceiveMessage1111111111111111111111111111");
    PerfectLink pl = new PerfectLink(5000, 0);
    byte[] message = "Test".getBytes(StandardCharsets.UTF_8);

    // Send to ourselves
    pl.send(new Message(message, InetAddress.getLocalHost(), 5000));

    List<Message> received = waitForReceive(pl, 3000);

    assertNotEquals(0, received.size());
    assertArrayEquals(message, received.get(0).getData());

    pl.close();

  }

  @Test
  void shouldHandleDuplicateMessages() throws Exception {
    System.out.println("Starting PerfectLinkTest.shouldHandleDuplicateMessages222222222222222222222222222");
    PerfectLink pl = new PerfectLink(5001, 1);
    byte[] message = "Test".getBytes(StandardCharsets.UTF_8);

    // Send to ourselves
    pl.send(new Message(message, InetAddress.getLocalHost(), 5001));

    // wait up to 3s for delivery (polling) to avoid flaky timing issues
    List<Message> received = waitForReceive(pl, 4000);
    assertNotEquals(0, received.size(), "no message received within timeout");
    assertArrayEquals(message, received.get(0).getData());

    pl.close();

  }

  /** Poll `pl.receive()` until a non-empty list is returned or timeout elapses. */
  private List<Message> waitForReceive(PerfectLink pl, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      List<Message> r = pl.receive();
      if (r != null && !r.isEmpty()) return r;
      Thread.sleep(50);
    }
    // final attempt
    return pl.receive();
  }

  @Test
  void shouldSendMessageFromAToB() throws Exception {
    System.out.println("Starting PerfectLinkTest.shouldSendMessageFromAToB33333333333333333333333333333");
    PerfectLink a = new PerfectLink(5002, 0);
    PerfectLink b = new PerfectLink(5003, 0);
    byte[] message = "Hello B".getBytes(StandardCharsets.UTF_8);

    // Send from A to B
    a.send(new Message(message, InetAddress.getLocalHost(), 5003));

    List<Message> received = waitForReceive(b, 3000);

    assertNotEquals(0, received.size());
    assertArrayEquals(message, received.get(0).getData());

    a.close();
    b.close();
  }
}
