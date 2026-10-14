package com.g27.DepChain.LayeredChannels;

import java.net.InetAddress;

public class Message {
  private final byte[] data;
  private final InetAddress address;
  private final int port;

  public Message(byte[] data, InetAddress address, int port) {
    this.data = data;
    this.address = address;
    this.port = port;
  }

  public byte[] getData() {
    return this.data;
  }

  public InetAddress getAddress() {
    return this.address;
  }

  public int getPort() {
    return this.port;
  }
}
