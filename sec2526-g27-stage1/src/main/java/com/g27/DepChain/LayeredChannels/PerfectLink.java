package com.g27.DepChain.LayeredChannels;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;

public class PerfectLink {

  private final StubbornLink stubborn;
  // track delivered sequences per sender (address:port -> map of seq -> timestamp)
  private final Map<String, ConcurrentMap<Integer, Long>> delivered = new ConcurrentHashMap<>();
  private final Set<List<Message>> processedMessages = ConcurrentHashMap.newKeySet(); // for testing duplicate delivery
  // per-sender next expected sequence number for ordered delivery
  private final Map<String, Integer> nextExpected = new ConcurrentHashMap<>();
  // per-sender buffer for out-of-order messages (ordered by seq)
  private final Map<String, ConcurrentSkipListMap<Integer, Message>> pendingBuffer = new ConcurrentHashMap<>();
  // NOTE: pendingBuffer is persistent and must not be cleaned; only `delivered` is trimmed

  private final AtomicInteger seqGen = new AtomicInteger(0);
  private final Set<Integer> pendingAcks = ConcurrentHashMap.newKeySet();
  private final BlockingQueue<Message> deliverQueue = new LinkedBlockingQueue<>();
  private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

  private final Thread receiverThread;
  private volatile boolean running = true;

  public PerfectLink(int port, int debug) throws Exception {
    this.stubborn = new StubbornLink(port, debug);
    this.receiverThread = new Thread(this::recvLoop);
    this.receiverThread.setDaemon(true);
    this.receiverThread.start();
    this.cleaner.scheduleAtFixedRate(this::cleanupOldDelivered, 30, 30, TimeUnit.SECONDS);
  }


  private void recvLoop() {
    while (running) {
      try {
        Message r = stubborn.receive();
        byte[] buf = r.getData();
        if (buf == null || buf.length < 1)
          continue; // malformed
        int type = buf[0] & 0xFF;
        String key = r.getAddress().getHostAddress() + ":" + r.getPort();
        if (type == 1) { // ACK (1 byte type + 4 byte seq)
          if (buf.length < 5)
            continue; // malformed ACK
          int seq = ByteBuffer.wrap(buf, 1, 4).getInt();
          pendingAcks.remove(seq);
          stubborn.stopSend(seq);
        } else if (type == 0) { // DATA (1 byte type + 8 byte timestamp + 4 byte seq + payload)
          if (buf.length < 13)
            continue; // malformed DATA
          long ts = ByteBuffer.wrap(buf, 1, 8).getLong();
          int seq = ByteBuffer.wrap(buf, 9, 4).getInt();
          long now = System.currentTimeMillis();
          // allow a 5000 ms margin (both directions)
          if (Math.abs(now - ts) > 5000) {
           continue;
          }
          ConcurrentMap<Integer, Long> seen = delivered.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
          if (seen.containsKey(seq)) {
            // duplicate: still send ACK via fair-loss (raw) to avoid ACK retransmission
            byte[] ack = ByteBuffer.allocate(5).put((byte) 1).putInt(seq).array();
            stubborn.rawSend(new Message(ack, r.getAddress(), r.getPort()));
            continue;
          }
          long arriveTime = System.currentTimeMillis();
          seen.put(seq, arriveTime);
          byte[] payloadBytes = Arrays.copyOfRange(buf, 13, buf.length);
          Message payloadMsg = new Message(payloadBytes, r.getAddress(), r.getPort());
          // send ACK back
          byte[] ack = ByteBuffer.allocate(5).put((byte) 1).putInt(seq).array();
          stubborn.rawSend(new Message(ack, r.getAddress(), r.getPort()));
          // buffer the message and flush in-order
          ConcurrentSkipListMap<Integer, Message> bufMap = pendingBuffer.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
          bufMap.put(seq, payloadMsg);
          // initialize expected seq if absent
          nextExpected.putIfAbsent(key, 0);
          // flush any contiguous in-order messages
          Integer expected = nextExpected.get(key);
          while (true) {
            Message m = bufMap.remove(expected);
            if (m == null) break;
            // Acquire `this` (PerfectLink) first so callers that synchronize on
            // the PerfectLink instance (e.g. AuthenticatedPerfectLink) won't deadlock
            synchronized (this) {
              synchronized (deliverQueue) {
                deliverQueue.put(m);
                deliverQueue.notifyAll(); // wake up any waiting receiver(s) on the queue
              }
              this.notifyAll(); // wake up any threads waiting on the PerfectLink monitor
            }
            expected = expected + 1;
            nextExpected.put(key, expected);
          }
        }
      } catch (Exception e) {
        if (running)
          e.printStackTrace();
      }
    }
  }

  public void send(Message message) throws Exception {
    int seq = seqGen.getAndIncrement();
    byte[] data = message.getData();
    InetAddress address = message.getAddress();
    int port = message.getPort();
    ByteBuffer packetBuf = ByteBuffer.allocate(1 + 8 + 4 + data.length);
    packetBuf.put((byte) 0); // DATA
    packetBuf.putLong(System.currentTimeMillis());
    packetBuf.putInt(seq);
    packetBuf.put(data);
    byte[] packet = packetBuf.array();

    stubborn.send(new Message(packet, address, port), seq);
    pendingAcks.add(seq);
  }


  public List<Message> receive() {
    synchronized (deliverQueue) {
      List<Message> messages = new ArrayList<>(deliverQueue);
      processedMessages.add(messages);
      deliverQueue.clear();
      return messages;
    }
  }

  // to be used wit client
  public Message receiveOne() {
      Message msg = null;
      try {
        msg = deliverQueue.take();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      List<Message> singleMsgList = new ArrayList<>();
      singleMsgList.add(msg);
      processedMessages.add(singleMsgList);
      return msg;
  }

  public void close() {
    running = false;
    receiverThread.interrupt();
    cleaner.shutdownNow();
    stubborn.close();
  }

  private void cleanupOldDelivered() {
    try {
      long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2);
      for (Map.Entry<String, ConcurrentMap<Integer, Long>> e : delivered.entrySet()) {
        ConcurrentMap<Integer, Long> map = e.getValue();
        for (Iterator<Map.Entry<Integer, Long>> it = map.entrySet().iterator(); it.hasNext(); ) {
          Map.Entry<Integer, Long> me = it.next();
          if (me.getValue() < cutoff) {
            it.remove();
          }
        }
        if (map.isEmpty()) {
          delivered.remove(e.getKey(), map);
        }
      }
    } catch (Exception ignored) {
    }
  }

  public void sendTest(Message message){
    try {
      this.send(message);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
