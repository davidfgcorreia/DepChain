package com.g27.DepChain.LayeredChannels;

import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.io.IOException;

public class FairLossLink {
  private final DatagramSocket socket;
  private final int bufferSize = 4096;
  private final int debug;

  public FairLossLink(int port, int debug) throws SocketException {
    this.socket = new DatagramSocket(port);
    this.debug = debug;
  }

  public DatagramSocket getSocket() {
    return this.socket;
  }

  public void send(Message message) throws IOException {

    byte[] data = message.getData();
    InetAddress address = message.getAddress();
    int port = message.getPort();

    if (debug == 1 && (Math.random() < 0.7)) {
      return; // simulate 90% loss

    }
    DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
    socket.send(packet);
  }

  public Message receive() throws IOException {
    byte[] buffer = new byte[bufferSize];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    socket.receive(packet);
    byte[] received = new byte[packet.getLength()];
    System.arraycopy(buffer, 0, received, 0, packet.getLength());
    return new Message(received, packet.getAddress(), packet.getPort());
  }

  public void close() {
    socket.close();
  }
}
