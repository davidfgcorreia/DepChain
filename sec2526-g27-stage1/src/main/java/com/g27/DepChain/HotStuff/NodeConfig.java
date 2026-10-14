package com.g27.DepChain.HotStuff;

import java.util.Map;
import java.net.InetAddress;

import com.g27.DepChain.Crypto.AuthConfig;
import com.g27.DepChain.Crypto.BLSConfig;

public class NodeConfig {
  public static class MemberInfo {
    public final InetAddress address;
    public final int port;

    public MemberInfo(InetAddress address, int port) {
      this.address = address;
      this.port = port;
    }
  }

  private final int id;
  private final String hostname;
  private final int port;
  private final Map<Integer, MemberInfo> members; // id -> MemberInfo
  private final AuthConfig crypto;
  private final int byzantineFlag;
  private final int connectionMode;
  private final BLSConfig blsConfig;

  public NodeConfig(int id, String hostname, int port, int byzantineFlag, int connectionMode,
      Map<Integer, MemberInfo> members, AuthConfig crypto, BLSConfig blsConfig) {
    this.id = id;
    this.hostname = hostname;
    this.port = port;
    this.members = members;
    this.crypto = crypto;
    this.byzantineFlag = byzantineFlag;
    this.connectionMode = connectionMode;
    this.blsConfig = blsConfig;
  }

  public int getId() {
    return id;
  }

  public String getHostname() {
    return hostname;
  }

  public int getPort() {
    return port;
  }

  public Map<Integer, MemberInfo> getMembers() {
    return members;
  }

  public AuthConfig getCrypto() {
    return crypto;
  }

  public int getByzantineFlag() {
    return byzantineFlag;
  }

  public int getConnectionMode() {
    return connectionMode;
  }

  public BLSConfig getBlsConfig() {
    return blsConfig;
  }
}
