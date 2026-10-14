package com.g27.DepChain.HotStuff;

import com.g27.DepChain.Crypto.AuthConfig;
import com.g27.DepChain.Crypto.BLSConfig;

import org.jetbrains.annotations.TestOnly;
import org.junit.jupiter.api.*;
import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSPublicKey;

import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byzantine fault tolerance tests for HotStuffNode.
 *
 *
 * Byzantine modes under test:
 * 0 = honest
 * 1 = drop all incoming messages
 * 2 = equivocate as leader (send fake block to replicas 2 and 3)
 * 3 = send invalid BLS signatures on every vote
 */
class HotStuffByzantineTest {

  private static final int N = 4;

  private List<HotStuffNode> buildCluster(int[] byzantineModes, int basePort) throws Exception {
    Map<Integer, KeyPair> keyPairs = new HashMap<>();
    Map<Integer, PublicKey> memberKeys = new HashMap<>();
    Map<Integer, NodeConfig.MemberInfo> memberInfos = new LinkedHashMap<>();

    KeyPairGenerator kg = KeyPairGenerator.getInstance("RSA");
    kg.initialize(2048);
    for (int i = 0; i < N; i++) {
      KeyPair kp = kg.generateKeyPair();
      keyPairs.put(i, kp);
      memberKeys.put(i, kp.getPublic());
      memberInfos.put(i, new NodeConfig.MemberInfo(
          InetAddress.getByName("localhost"), basePort + i * N));
    }

    SecureRandom rng = new SecureRandom();
    List<BLSKeyPair> blsKeyPairs = new ArrayList<>();
    List<BLSPublicKey> allPubKeys = new ArrayList<>();
    for (int i = 0; i < N; i++) {
      BLSKeyPair kp = BLSKeyPair.random(rng);
      blsKeyPairs.add(kp);
      allPubKeys.add(kp.getPublicKey());
    }

    List<HotStuffNode> nodes = new ArrayList<>();
    for (int i = 0; i < N; i++) {
      AuthConfig authConfig = new AuthConfig(
          keyPairs.get(i).getPrivate(), keyPairs.get(i).getPublic(), memberKeys);
      authConfig.generateDHKeyPair();
      BLSConfig blsConfig = new BLSConfig(blsKeyPairs.get(i), allPubKeys);
      NodeConfig cfg = new NodeConfig(i, "localhost", basePort + i * N,
          byzantineModes[i], 0, memberInfos, authConfig, blsConfig);
      nodes.add(new HotStuffNode(cfg));
    }
    return nodes;
  }

  /**
   * Establishes sessions, starts recv threads, and kicks off genesis proposal.
   */
  private void startCluster(List<HotStuffNode> nodes) throws Exception {
    for (HotStuffNode node : nodes)
      node.establish();
    Thread.sleep(4000); // allow DH session handshakes to complete
    for (HotStuffNode node : nodes)
      node.start();
  }

  private void stopCluster(List<HotStuffNode> nodes) {
    for (HotStuffNode node : nodes)
      node.stop();
  }

  /**
   * Polls until all nodes in the list contain the expected block, or times out.
   */
  private void awaitCommit(List<HotStuffNode> nodes, String expectedBlock,
      long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      // boolean allCommitted = nodes.stream()
      // .allMatch(n -> n.getChain().contains(expectedBlock))
      boolean allCommitted = true;
      for (HotStuffNode node : nodes) {
        if (!node.getLog(expectedBlock)) {
          allCommitted = false;
        }
      }
      if (allCommitted)
        return;
      Thread.sleep(500);
    }
  }

  // ── Tests ──────────────────────────────────────────────────────────

  /**
   * All 4 nodes are honest. Genesis block must be committed by every node.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void allHonestNodesGenesis() throws Exception {
    int basePort = 8000;
    List<HotStuffNode> nodes = buildCluster(new int[] { 0, 0, 0, 0 }, basePort);
    try {
      startCluster(nodes);
      awaitCommit(nodes, "Genesis", 20_000);
      for (HotStuffNode node : nodes) {
        assertTrue(node.getLog("Genesis"), "Node " + nodes.indexOf(node) + " should have committed Genesis");
      }
    } finally {
      stopCluster(nodes);
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void allHonestNodesBlockAfterGenesis() throws Exception {
    int basePort = 8100;
    List<HotStuffNode> nodes = buildCluster(new int[] { 0, 0, 0, 0 }, basePort);
    try {
      startCluster(nodes);
      awaitCommit(nodes, "Genesis", 20_000);
      for (HotStuffNode node : nodes) {
        node.proposeBlock("BlockAfterGenesis");
      }
      awaitCommit(nodes, "BlockAfterGenesis", 40_000);
      for (HotStuffNode node : nodes) {
        assertTrue(node.getLog("BlockAfterGenesis"),
            "Node " + node.getId() + " should have committed BlockAfterGenesis");
      }
    } finally {
      stopCluster(nodes);
    }
  }

  /**
   * Node 3 is in drop mode (mode 1)
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void byzantineDropReplica() throws Exception {
    int basePort = 8200;
    // Node 3 drops everything; nodes 0, 1, 2 are honest
    List<HotStuffNode> nodes = buildCluster(new int[] { 0, 0, 0, 1 }, basePort);
    try {
      startCluster(nodes);
      List<HotStuffNode> honestNodes = nodes.subList(0, 3);
      awaitCommit(honestNodes, "Genesis", 20_000);
      for (HotStuffNode node : honestNodes) {
        assertTrue(node.getLog("Genesis"),
            "Honest node " + honestNodes.indexOf(node)
                + " should have committed Genesis despite Byzantine drop replica");
      }
    } finally {
      stopCluster(nodes);
    }
  }

  /**
   * Node 1 in mode 2 will send real blocks to some replicas and fake blocks to
   * others, causing equivocation.
   */
  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void byzantineLeaderEquivocation() throws Exception {
    int basePort = 8300;
    List<HotStuffNode> nodes = buildCluster(new int[] { 0, 2, 0, 0 }, basePort);
    try {
      startCluster(nodes);
      Thread.sleep(70_000);
      List<HotStuffNode> honestNodes = List.of(nodes.get(0), nodes.get(2), nodes.get(3));
      List<String> referenceChain = honestNodes.get(0).getChain();
      assertFalse(referenceChain.isEmpty(),
          "Honest nodes should have committed at least one block");
      for (HotStuffNode n : honestNodes) {
        assertEquals(referenceChain, n.getChain(),
            "Node " + nodes.indexOf(n) + " chain should match other honest nodes");
      }
    } finally {
      stopCluster(nodes);
    }
  }

  /**
   * Node 3 is in invalid-BLS mode (mode 3)
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void byzantineInvalidBLSVote() throws Exception {
    int basePort = 8400;
    List<HotStuffNode> nodes = buildCluster(new int[] { 0, 0, 0, 3 }, basePort);
    try {
      startCluster(nodes);
      List<HotStuffNode> honestNodes = nodes.subList(0, 3);
      awaitCommit(honestNodes, "Genesis", 20_000);
      for (HotStuffNode node : honestNodes) {
        assertTrue(node.getLog("Genesis"),
            "Honest node " + honestNodes.indexOf(node)
                + " should have committed Genesis despite invalid BLS from node 3");
      }
    } finally {
      stopCluster(nodes);
    }
  }

  /**
   * Node 1 is in replay-attack mode (mode 4).
   * Honest nodes must reject the replayed block.
   */
  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void byzantineReplayAttacks() throws Exception {
    int basePort = 8500;
    List<HotStuffNode> nodes = buildCluster(new int[] { 0, 0, 4, 0 }, basePort);
    try {
      startCluster(nodes);
      List<HotStuffNode> honestNodes = List.of(nodes.get(0), nodes.get(1), nodes.get(3));

      // wait for genesis to commit
      awaitCommit(honestNodes, "Genesis", 20_000);

      // wait for commit of actual blocks (not genesis) which triggers the replay
      // attack
      Thread.sleep(40_000);

      // no duplicate blocks should be committed
      for (HotStuffNode n : honestNodes) {
        List<String> chain = n.getChain();
        Set<String> unique = new HashSet<>(chain);
        assertEquals(unique.size(), chain.size(),
            "Node " + nodes.indexOf(n) + " should have no duplicate blocks.");
      }
    } finally {
      stopCluster(nodes);
    }
  }

  /**
   * Node 3 is in Drop Mode, changes to Normal Mode and has to catchup.
   * Needs to have the same blocks as the other nodes.
   */
  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  void CatchUpImplementation() throws Exception {
    int basePort = 8600;
    List<HotStuffNode> nodes = buildCluster(new int[] { 0, 0, 0, 1 }, basePort);

    try {
      startCluster(nodes);
      List<HotStuffNode> honestNodes = List.of(nodes.get(0), nodes.get(1), nodes.get(2));
      HotStuffNode node3 = nodes.get(3);

      awaitCommit(honestNodes, "Genesis", 40_000);
      for (HotStuffNode node : honestNodes) {
        node.proposeBlock("BlockAfterGenesis");
      }

      awaitCommit(honestNodes, "BlockAfterGenesis", 40_000);
      for (HotStuffNode node : honestNodes) {
        assertTrue(node.getLog("BlockAfterGenesis"),
            "Node " + node.getId() + " should have committed BlockAfterGenesis");
      }
      node3.setByzantineMode(0);

      awaitCommit(List.of(node3), "BlockAfterGenesis", 40_000);

      assertTrue(node3.getLog("BlockAfterGenesis"), "Node 3 should have blockAfterGenesis.");

    } finally {
      stopCluster(nodes);
    }
  }

}
