package com.g27.DepChain.LayeredChannels;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class StubbornLink {
  private final FairLossLink fairLossLink;
  private final Set<PendingEntry> pending = ConcurrentHashMap.newKeySet();
  private static class PendingEntry {
    final int seq;
    final Message msg;
    PendingEntry(int seq, Message msg) { this.seq = seq; this.msg = msg; }
    @Override public int hashCode() { return Integer.hashCode(seq); }
    @Override public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof PendingEntry)) return false;
      return this.seq == ((PendingEntry)o).seq;
    }
  }
  private Thread senderThread;

  public StubbornLink(int localPort, int debug) throws Exception { // used by other classes
    this.fairLossLink = new FairLossLink(localPort, debug);
    this.senderThread = startRetransmitionThread();

  }

  public void send(Message message, int seq) {
    synchronized (pending) {
      pending.add(new PendingEntry(seq, message));
      pending.notifyAll();
    }
  }

  public void rawSend(Message message) throws IOException {
    fairLossLink.send(message);
  }


  private Thread startRetransmitionThread() {
    Thread t = new Thread(() -> {
      long timeout = 100;
      while (!Thread.currentThread().isInterrupted()) {
            if (pending.isEmpty()) {
              synchronized (pending) {
                try {
                  pending.wait(timeout);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            }
            for (PendingEntry pe : pending) {
              try {
                fairLossLink.send(pe.msg);
              } catch (IOException e) {
                System.err.println("An error has occured when calling FairLossLink.send() function");
              }
            }
        try {
          Thread.sleep(timeout);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
        timeout = Math.min(2000, timeout * 2);
      }
    });
    t.setDaemon(true);
    t.start();
    return t;
  }

  public void stopSend(int seq) {
    synchronized (pending) {
      pending.removeIf(pe -> pe.seq == seq);
    }
  }


  public Message receive() throws Exception {
    Message m = fairLossLink.receive();
    return m;
  }

  public void close() {
    senderThread.interrupt();
    fairLossLink.close();
  }
}
