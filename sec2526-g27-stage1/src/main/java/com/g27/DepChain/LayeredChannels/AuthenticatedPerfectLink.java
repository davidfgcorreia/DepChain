package com.g27.DepChain.LayeredChannels;

import java.net.InetAddress;
import java.security.PublicKey;
import java.util.List;
import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.g27.DepChain.Crypto.AuthConfig;

public class AuthenticatedPerfectLink {
  private final PerfectLink perfect;
  private final AuthConfig crypto;
  private final int localId;
  private final int remoteId;
  private boolean sessionEstablished = false;
  private boolean establishSent = false;
  private Mac hmac;


  public int getRemoteId() {
    return remoteId;
  }

  public boolean isSessionEstablished() {
    return sessionEstablished;
  }


  public AuthenticatedPerfectLink(int localPort, int localId, int remoteId, AuthConfig crypto,int debug) throws Exception {
    this.perfect = new PerfectLink(localPort,debug);
    this.crypto = crypto;
    this.localId = localId;
    this.remoteId = remoteId;
    listenerinit();
  }

  public void listenerinit() {
    new Thread(() -> {

      Message m = null;
      try {
        synchronized(perfect) {
        while (!sessionEstablished) {
          List<Message> messages = perfect.receive(); // now returns a snapshot list
          if (messages != null && !messages.isEmpty()) {
            m = messages.get(0);
            break;
          }
          perfect.wait(); // wait for new messages or timeout to check again
        }
      }
      } catch (Exception e) {
        e.printStackTrace();
      }

      try {
      if (!establishSent) {
        establishSession(m.getAddress(), m.getPort());
        establishSent = true;
      }
      }
      catch (Exception e) {
        e.printStackTrace();
      }


      byte[] recv = m.getData();
      ByteBuffer rbb = ByteBuffer.wrap(recv);
      int rSigLen = rbb.getInt();
      byte[] remoteSig = new byte[rSigLen];
      rbb.get(remoteSig);
      byte[] remoteDHPub = new byte[recv.length - 4 - rSigLen];
      rbb.get(remoteDHPub);

      try {
        PublicKey remoteStatic = crypto.getMemberPublicKey(remoteId);
        if (remoteStatic == null)
          throw new IllegalArgumentException("Remote static public key missing");
        if (!crypto.verify(remoteDHPub, remoteSig, remoteStatic))
          throw new SecurityException("DH pub signature invalid");

        crypto.computeSessionSecret(remoteDHPub);
        byte[] sessionSecret = crypto.getSessionSecret();
        hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(sessionSecret, "HmacSHA256"));

        // System.out.println("AuthenticatedPerfectLink: Session secret established with " + remoteId);
        sessionEstablished = true;
        synchronized (this) {
        this.notifyAll();
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }).start();
  }

  // Establish authenticated DH-based session using the underlying perfect link
  public void establishSession(InetAddress remoteAddr, int remotePort) throws Exception {
    establishSent = true;
    byte[] myDHPub = crypto.getDHPublicEncoded();
    byte[] myDHPubSig = crypto.sign(myDHPub);
    // send DH pub + sig (length-prefixed using ByteBuffer)
    int sigLen = myDHPubSig.length;
    ByteBuffer bb = ByteBuffer.allocate(4 + sigLen + myDHPub.length);
    bb.putInt(sigLen);
    bb.put(myDHPubSig);
    bb.put(myDHPub);
    perfect.send(new Message(bb.array(), remoteAddr, remotePort));

    // System.out.println("AuthenticatedPerfectLink: Sent DH pub to " + remoteAddr.getHostAddress() + ":" + remotePort);


  }

  public void send(Message m) throws Exception {
    if (!sessionEstablished)
      throw new IllegalStateException("Session not established");
    byte[] data = m.getData();
    byte[] mac = hmac.doFinal(data);
    int macLen = mac.length;
    ByteBuffer pbb = ByteBuffer.allocate(4 + macLen + data.length);
    pbb.putInt(macLen);
    pbb.put(mac);
    pbb.put(data);
    // System.out.println("AuthenticatedPerfectLink send -> " + m.getAddress().getHostAddress() + ":" + m.getPort());
    perfect.send(new Message(pbb.array(), m.getAddress(), m.getPort()));
  }

  public List<Message> receive() throws Exception {
    List<Message> received = new java.util.ArrayList<>();
    if (!sessionEstablished)
      throw new IllegalStateException("Session not established");
    List<Message> messages = perfect.receive(); // now returns a snapshot list
    if (messages != null && !messages.isEmpty()) {
      for (Message m : messages) {
        byte[] packet = m.getData();
        InetAddress address = m.getAddress();
        int port = m.getPort();
        ByteBuffer pbb = ByteBuffer.wrap(packet);
        int macLen = pbb.getInt();
        if (packet.length < 4 + macLen)
          continue; // malformed
        byte[] mac = new byte[macLen];
        pbb.get(mac);
        byte[] data = new byte[packet.length - 4 - macLen];
        pbb.get(data);
        byte[] computed = hmac.doFinal(data);
        if (!java.util.Arrays.equals(mac, computed))
          continue; // corrupted
        // System.out.println("AuthenticatedPerfectLink receive <- " + address.getHostAddress() + ":" + port);
        received.add(new Message(data, address, port));
      }
    }
    return received;
  }

  public void senddebug(byte[] data, InetAddress address, int port) {
    try {
      perfect.send(new Message(data, address, port));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void close() {
    perfect.close();
  }
}
