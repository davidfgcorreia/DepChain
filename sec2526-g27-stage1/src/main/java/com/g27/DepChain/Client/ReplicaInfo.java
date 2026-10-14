package com.g27.DepChain.Client;

import java.net.InetAddress;

public record ReplicaInfo(int id, InetAddress address, int port) {}
