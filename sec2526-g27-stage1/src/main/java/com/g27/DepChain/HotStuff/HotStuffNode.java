
package com.g27.DepChain.HotStuff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;

import org.json.JSONObject;

import com.g27.DepChain.smartContact.runtime.EvmExecutor;
import com.g27.DepChain.smartContact.model.Transaction;
import com.g27.DepChain.Chain.Execution;
import com.g27.DepChain.Crypto.AuthConfig;
import com.g27.DepChain.Crypto.BLSConfig;
import com.g27.DepChain.LayeredChannels.AuthenticatedPerfectLink;
import com.g27.DepChain.LayeredChannels.Message;
import com.g27.DepChain.Chain.ClientOpps;

import java.security.PublicKey;

import java.nio.file.Files;
import java.nio.file.Paths;

import tech.pegasys.teku.bls.BLSSignature;

/**
 * Quorum size: n − f where f = ⌊(n−1)/3⌋ (2f+1 for n = 3f+1).
 */
public class HotStuffNode {
  // ── Links to all members ──────────────────────────────────────────
  final Map<Integer, AuthenticatedPerfectLink> memberLinks = new ConcurrentHashMap<>();

  // Links to clients (support multiple client ports/groups)
  final java.util.Map<Integer, AuthenticatedPerfectLink> clientLinks = new java.util.concurrent.ConcurrentHashMap<>();
  String clientAddress = "localhost";
  int clientPort = 6000; // base configured client port (may be overridden per-link)

  // ── identity & network ──────────────────────────────────────────────
  final int id;
  final Map<Integer, NodeConfig.MemberInfo> members; // peerId → addr+port
  int byzantineMode;
  final int port; // the port this node listens on for member messages (not used for client
                  // messages in this simplified version)

  // ── crypto (kept for future threshold-signature QCs) ────────────────
  final AuthConfig crypto;
  final BLSConfig blsConfig;
  // ── threads & lifecycle ─────────────────────────────────────────────
  final Thread recvThread;
  private volatile boolean running = true;

  // ── BFT parameters ──────────────────────────────────────────────────
  final int n; // total members
  final int f; // max byzantine faults
  final int quorumSize; // n − f

  // ── HotStuff state ──────────────────────────────────────────────────
  volatile int viewNumber = 0;
  final Map<String, Boolean> logs = new ConcurrentHashMap<>();

  // votes collected by the leader key = "view:blockData"
  final Map<Integer, Map<Integer, QC>> newViewResponses = new ConcurrentHashMap<>();
  final Set<Integer> prepareVotes = new HashSet<Integer>();
  final Set<Integer> precommitVotes = new HashSet<Integer>();
  final Set<Integer> commitVotes = new HashSet<Integer>();
  final List<BLSSignature> blsSignatures = new ArrayList<BLSSignature>();

  // guards against double-triggering a QC when votes arrive concurrently
  final Set<String> triggered = ConcurrentHashMap.newKeySet();

  // pointers for safety rules (simplified: no locking rules, always accept)
  QC highQC = null;
  boolean catchingup = false; // flag to indicate if we're in the middle of a catchup
  int PileBlock = 0; // track block number for catchup and view change
  int commitedBlockNumber = -1; // track the highest committed block number for catchup and view change
  volatile long lastProposeReceivedAtMs = 0L;

  // ── message type tags ───────────────────────────────────────────────
  private static final String PRE_COMMIT = "PRE_COMMIT";
  private static final String COMMIT = "COMMIT";
  private static final String DECIDE = "DECIDE";

  final List<InternalBlock> internalBlocks = Collections.synchronizedList(new ArrayList<>());
  final List<String> appBlocks = Collections.synchronizedList(new ArrayList<>());
  final Map<Integer, PublicKey> clientPubKeys = new ConcurrentHashMap<>();
  // ECDSA client addresses (normalized, no 0x, lowercase)
  final Map<Integer, String> clientEcdsaAddresses = new ConcurrentHashMap<>();
  // ECDSA client public keys as raw X||Y bytes (64 bytes)
  final Map<Integer, byte[]> clientEcdsaPublicBytes = new ConcurrentHashMap<>();
  // Track next-expected nonce per client address (normalized, no 0x)
  final Map<String, Long> clientNonces = new ConcurrentHashMap<>();

  // ── EVM state ────────────────────────────────────────────────────────────
  // All replicas maintain identical EVM world state through deterministic
  // execution of committed transactions.
  final EvmExecutor evmExecutor = new EvmExecutor();

  // ==================================================================
  // Constructor
  // ==================================================================

  public HotStuffNode(NodeConfig cfg) throws Exception {
    this.id = cfg.getId();
    this.members = new LinkedHashMap<>(cfg.getMembers());
    this.crypto = cfg.getCrypto();
    this.byzantineMode = cfg.getByzantineFlag();
    this.port = cfg.getPort();

    this.blsConfig = cfg.getBlsConfig();

    this.n = members.size();
    this.f = (n - 1) / 3;
    this.quorumSize = 2 * f + 1;

    this.recvThread = new Thread(this::recvLoop, "hotstuff-recv-" + id);
    this.recvThread.setDaemon(true);
    // debug
    dbg("constructor");
    // Load client public keys from config/public_keys.json if present (needed
    // before opening client links)
    try {
      loadClientPublicKeys();
      dbg("Loaded client public keys: " + clientPubKeys.keySet());
    } catch (Exception e) {
      dbg("No client public keys loaded: " + e.getMessage());
    }
    openClientCOnnection();
    System.out.println("Node " + id + " initialized with " + n + " members (f=" + f + ", quorumSize=" + quorumSize
        + "), byzantine=" + byzantineMode);
    openConnectionsToAllMembers();
    System.out.println("Node " + id + " opened connections to all members");
  }

  private void loadClientPublicKeys() {
    try {
      String content = new String(Files.readAllBytes(Paths.get("config/public_keys.json")));
      org.json.JSONObject obj = new org.json.JSONObject(content);
      // Prefer explicit ecdsa mapping keyed by id: obj.ecdsa{"0":{...},"1":{...},...}
      org.json.JSONObject ecdsaMap = obj.optJSONObject("ecdsa");
      if (ecdsaMap != null) {
        java.util.Iterator<String> keys = ecdsaMap.keys();
        Map<Integer, String> authMap = new java.util.HashMap<>();
        while (keys.hasNext()) {
          String k = keys.next();
          try {
            int id = Integer.parseInt(k);
            org.json.JSONObject ent = ecdsaMap.optJSONObject(k);
            if (ent == null)
              continue;
            String pub = ent.optString("ecdsaPublic", "");
            String addr = ent.optString("address", "");
            // store in AuthConfig map (raw pub hex)
            if (pub != null && !pub.isBlank())
              authMap.put(id, pub.startsWith("0x") ? pub.substring(2) : pub);
            // populate node mappings: prefer explicit address, else derive from pub
            if (addr != null && !addr.isBlank()) {
              String a = addr.startsWith("0x") ? addr.substring(2) : addr;
              clientEcdsaAddresses.put(id, a.toLowerCase());
              clientNonces.put(a.toLowerCase(), 0L);
            } else if (pub != null && !pub.isBlank()) {
              try {
                org.apache.tuweni.bytes.Bytes pubb = org.apache.tuweni.bytes.Bytes
                    .fromHexString(pub.startsWith("0x") ? pub : ("0x" + pub));
                org.apache.tuweni.bytes.Bytes32 h32 = org.apache.tuweni.crypto.Hash.keccak256(pubb);
                String derived = h32.slice(12, 20).toHexString();
                if (derived.startsWith("0x"))
                  derived = derived.substring(2);
                clientEcdsaAddresses.put(id, derived.toLowerCase());
                clientNonces.put(derived.toLowerCase(), 0L);
              } catch (Throwable ignore) {
              }
            }
            // store raw pub bytes as BigInteger when available
            if (pub != null && !pub.isBlank()) {
              try {
                String hex = pub.startsWith("0x") ? pub.substring(2) : pub;
                byte[] pubBytes = org.web3j.utils.Numeric.hexStringToByteArray(hex);
                if (pubBytes.length == 65 && pubBytes[0] == 0x04) {
                  byte[] xy = java.util.Arrays.copyOfRange(pubBytes, 1, pubBytes.length);
                  clientEcdsaPublicBytes.put(id, xy);
                } else if (pubBytes.length == 64) {
                  clientEcdsaPublicBytes.put(id, pubBytes);
                } else if (pubBytes.length > 0) {
                  int off = (pubBytes[0] == 0x04) ? 1 : 0;
                  if (pubBytes.length - off == 64) {
                    clientEcdsaPublicBytes.put(id, java.util.Arrays.copyOfRange(pubBytes, off, pubBytes.length));
                  }
                }
              } catch (Throwable ignore2) {
              }
            }
          } catch (Throwable ignore) {
          }
        }
        // make the raw ecdsa map available via AuthConfig
        try {
          if (this.crypto != null)
            this.crypto.setEcdsaPublics(authMap);
        } catch (Throwable ignore) {
        }
      } else {
        // fallback: keep backwards-compatible clients[] parsing (indexed)
        org.json.JSONArray clients = obj.optJSONArray("clients");
        if (clients == null)
          return;
        for (int i = 0; i < clients.length(); i++) {
          try {
            org.json.JSONObject c = clients.getJSONObject(i);
            String addr = c.optString("address", null);
            if (addr != null && !addr.isBlank()) {
              addr = addr.startsWith("0x") ? addr.substring(2) : addr;
              clientEcdsaAddresses.put(i, addr.toLowerCase());
              clientNonces.put(addr.toLowerCase(), 0L);
              continue;
            }
            String ecdsaPub = c.optString("ecdsaPublic", null);
            if (ecdsaPub != null && !ecdsaPub.isBlank()) {
              try {
                org.apache.tuweni.bytes.Bytes pub = org.apache.tuweni.bytes.Bytes
                    .fromHexString(ecdsaPub.startsWith("0x") ? ecdsaPub : ("0x" + ecdsaPub));
                org.apache.tuweni.bytes.Bytes32 h32 = org.apache.tuweni.crypto.Hash.keccak256(pub);
                String derived = h32.slice(12, 20).toHexString();
                if (derived.startsWith("0x"))
                  derived = derived.substring(2);
                clientEcdsaAddresses.put(i, derived.toLowerCase());
                clientNonces.put(derived.toLowerCase(), 0L);
                try {
                  String hex = ecdsaPub.startsWith("0x") ? ecdsaPub.substring(2) : ecdsaPub;
                  byte[] pubBytes = org.web3j.utils.Numeric.hexStringToByteArray(hex);
                  if (pubBytes.length == 65 && pubBytes[0] == 0x04) {
                    byte[] xy = java.util.Arrays.copyOfRange(pubBytes, 1, pubBytes.length);
                    clientEcdsaPublicBytes.put(i, xy);
                  } else if (pubBytes.length == 64) {
                    clientEcdsaPublicBytes.put(i, pubBytes);
                  } else if (pubBytes.length > 0) {
                    int off = (pubBytes[0] == 0x04) ? 1 : 0;
                    if (pubBytes.length - off == 64) {
                      clientEcdsaPublicBytes.put(i, java.util.Arrays.copyOfRange(pubBytes, off, pubBytes.length));
                    }
                  }
                } catch (Throwable ignore2) {
                }
              } catch (Throwable ignore) {
              }
            }
          } catch (Throwable ignore) {
          }
        }
      }
    } catch (Throwable t) {
      // ignore
    }
  }

  /**
   * Insert the client request JSON into clientRequests ordered by fee (gasPrice *
   * gasLimit) descending.
   *
   * @param requestJson the client request in JSON format
   * @param hash        the hash of the request, or {@code null}
   */
  public synchronized void enqueueClientRequest(String requestJson, String hash) {
    com.g27.DepChain.Chain.DataBlockManager.enqueueClientRequest(this.id, requestJson, hash);
  }

  /**
   * Insert the client request JSON into clientRequests ordered by fee (gasPrice *
   * gasLimit) descending.
   *
   * @param requestJson the client request in JSON format
   */
  public synchronized void enqueueClientRequest(String requestJson) {
    // legacy single-argument overload -> delegate with null hash
    enqueueClientRequest(requestJson, null);
  }

  public void dbg(String fn) {
    try {
      System.out.println("[DBG][node=" + id + "] " + fn);
    } catch (Exception e) {
      System.out.println("[DBG] " + fn);
    }
  }

  /**
   * Public getters/setters for fields accessed by external modules (Chain).
   *
   * @return the node id
   */
  public int getId() {
    return this.id;
  }

  public int getN() {
    return this.n;
  }

  public com.g27.DepChain.Crypto.AuthConfig getCrypto() {
    return this.crypto;
  }

  /**
   * Returns any available client link (if present) for compatibility.
   *
   * @return any available {@link AuthenticatedPerfectLink}, or {@code null} if
   *         none
   */
  public AuthenticatedPerfectLink getClientLink() {
    return this.clientLinks.values().stream().findFirst().orElse(null);
  }

  /**
   * Add a client link bound to the given local port.
   *
   * @param localPort the local UDP port the link is bound to
   * @param link      the {@link AuthenticatedPerfectLink} to register
   */
  public void addClientLink(int localPort, AuthenticatedPerfectLink link) {
    this.clientLinks.put(localPort, link);
  }

  /**
   * Get client link bound to a specific local port.
   *
   * @param localPort the local UDP port
   * @return the {@link AuthenticatedPerfectLink} for that port, or {@code null}
   *         if none
   */
  public AuthenticatedPerfectLink getClientLinkForPort(int localPort) {
    return this.clientLinks.get(localPort);
  }

  /**
   * Remove and close a client link for a given local port.
   *
   * @param localPort the local UDP port of the link to remove
   */
  public void removeClientLink(int localPort) {
    AuthenticatedPerfectLink l = this.clientLinks.remove(localPort);
    if (l != null) {
      try {
        l.close();
      } catch (Exception ignore) {
      }
    }
  }

  public String getClientAddress() {
    return this.clientAddress;
  }

  public int getClientPort() {
    return this.clientPort;
  }

  public void setClientAddress(String addr) {
    this.clientAddress = addr;
  }

  public void setClientPort(int port) {
    this.clientPort = port;
  }

  public java.util.List<String> getClientRequests() {
    return com.g27.DepChain.Chain.DataBlockManager.getClientRequests(this.getId());
  }

  /**
   * Expose ECDSA client address map (normalized address by client id).
   *
   * @return map from client id to normalized (no-0x, lowercase) Ethereum address
   */
  public java.util.Map<Integer, String> getClientEcdsaAddresses() {
    return this.clientEcdsaAddresses;
  }

  /**
   * Expose ECDSA client public key raw bytes map.
   *
   * @return map from client id to raw 64-byte X||Y public key
   */
  public java.util.Map<Integer, byte[]> getClientEcdsaPublicBytes() {
    return this.clientEcdsaPublicBytes;
  }

  /**
   * Expose client nonces map keyed by normalized address.
   *
   * @return map from normalized address to next expected nonce
   */
  public java.util.Map<String, Long> getClientNonces() {
    return this.clientNonces;
  }

  /**
   * Return client id for a given normalized ECDSA address (no 0x, lowercase), or
   * null if unknown.
   *
   * @param addr the ECDSA address (with or without 0x prefix)
   * @return the client id, or {@code null} if not found
   */
  public Integer getClientIdForAddress(String addr) {
    if (addr == null)
      return null;
    String norm = (addr.startsWith("0x") || addr.startsWith("0X")) ? addr.substring(2) : addr;
    norm = norm.toLowerCase();
    for (java.util.Map.Entry<Integer, String> e : this.clientEcdsaAddresses.entrySet()) {
      try {
        String v = e.getValue();
        if (v != null && v.equalsIgnoreCase(norm))
          return e.getKey();
      } catch (Throwable ignore) {
      }
    }
    return null;
  }

  /**
   * Searches the logs map to check behaviours.
   *
   * @param log the log key to look up
   * @return {@code true} if the log entry is set, {@code false} otherwise
   */
  public boolean getLog(String log) {
    return logs.getOrDefault(log, false);
  }

  public void addLog(String log, boolean isaccomplished) {
    logs.put(log, isaccomplished);
  }

  public void setByzantineMode(int mode) {
    this.byzantineMode = mode;
  }

  public void noteProposeReceivedNow() {
    this.lastProposeReceivedAtMs = System.currentTimeMillis();
  }

  public long getLastProposeReceivedAtMs() {
    return this.lastProposeReceivedAtMs;
  }

  // ==================================================================
  // Lifecycle
  // ==================================================================
  public synchronized void establish() {
    dbg("establish");
    sendEstablishToAllMembers();
  }

  public synchronized void start() {
    dbg("start");
    recvThread.start();

    // Legacy placeholder genesis creation removed. Genesis is loaded below via
    // GenesisLoader.

    // Delegate EVM deployment and genesis seeding to Chain package; use returned
    // genesis JSON as first block
    try {
      String genesisJson = com.g27.DepChain.smartContact.genesis.GenesisLoader.applyGenesisFromFile(evmExecutor);
      if (genesisJson != null) {
        try {
          if (!internalBlocks.isEmpty()) {
            internalBlocks.set(0, new InternalBlock(genesisJson));
            String persistedGenesis = PersistedBlockFormatter.normalize(genesisJson, appBlocks);
            appBlocks.set(0, persistedGenesis);
            PersistedBlockFileStore.saveToChainDirectory(0, persistedGenesis);
          } else {
            QC genesisQC = new QC("null");
            InternalBlock genesis = new InternalBlock(genesisJson, genesisQC, "null", 0, 0);
            internalBlocks.add(genesis);
            String persistedGenesis = PersistedBlockFormatter.normalize(genesisJson, appBlocks);
            appBlocks.add(persistedGenesis);
            PersistedBlockFileStore.saveToChainDirectory(0, persistedGenesis);
            this.commitedBlockNumber = 0;
            this.PileBlock = 0;
            this.highQC = genesisQC;
          }
          dbg("Genesis block replaced with generated genesis JSON");
          addLog("Genesis", true);
        } catch (Throwable ignore) {
        }
      }
    } catch (Throwable ignore) {
    }
    try {
      // notify leader we're awake by sending NEW_VIEW (our current highQC)
      HotStuffView.sendMyHigthQC(this);
    } catch (Exception e) {
      dbg("failed to send NEW_VIEW on start: " + e.getMessage());
    }
  }

  public synchronized void openClientCOnnection() {
    int clients = Math.max(1, clientEcdsaAddresses.size());
    ClientOpps.openAllClientConnections(this, clients);
  }

  /** Opens AuthenticatedPerfectLink connections for every member except self. */
  public synchronized void openConnectionsToAllMembers() {
    HotStuffNetwork.openConnectionsToAllMembers(this);
  }

  /** Sends establish connection to every member except self. */
  public synchronized void sendEstablishToAllMembers() {
    HotStuffNetwork.sendEstablishToAllMembers(this);
  }

  public synchronized void stop() {
    dbg("stop");
    running = false;
    recvThread.interrupt();
    for (AuthenticatedPerfectLink link : memberLinks.values()) {
      link.close();
    }
    for (AuthenticatedPerfectLink cl : clientLinks.values()) {
      try {
        cl.close();
      } catch (Exception ignore) {
      }
    }
  }

  /**
   * Returns an immutable snapshot of the committed chain.
   *
   * @return a copy of the committed block list
   */
  public synchronized List<String> getChain() {
    dbg("getChain");
    return new ArrayList<>(appBlocks);
  }

  // ==================================================================
  // Leader helpers
  // ==================================================================

  int getLeader(int view) {
    return view % n;
  }

  boolean isLeader(int view) {
    return getLeader(view) == id;
  }

  /**
   * Convenience entry-point used by App.java to kick off the first round.
   *
   * @throws Exception if the proposal fails
   */
  public synchronized void proposeGenesis() throws Exception {
    dbg("proposeGenesis");
    if (viewNumber == 0 && id == 0) {
      proposeBlock("Genesis");
    }
  }

  public synchronized void proposeBlock(String blockData) throws Exception {
    HotStuffPhases.proposeBlock(this, blockData);
  }

  /**
   * Main receiver loop: receive messages from all member and client links,
   * dispatch to handlers, and view change on timeout.
   */
  private void recvLoop() {
    dbg("recvLoop start");
    final long SOCKET_POLL_MS = 200L;
    final long VIEW_TIMEOUT_MS = 20_000L;
    long lastProgressAtMs = System.currentTimeMillis();
    while (running) {
      try {
        boolean sawProgress = false;
        if (byzantineMode == 1) {
          continue; // byzantine mode 1 node drops everything
        }
        // Receive from every member link
        // dbg("waiting to receive messages from members");
        for (int memberkey : memberLinks.keySet()) {
          AuthenticatedPerfectLink memberLink = memberLinks.get(memberkey);
          try {
            List<Message> messages = memberLink.receive();

            if (messages != null && !messages.isEmpty()) {
              sawProgress = true;
            }
            for (Message m : messages) {
              String content = new String(m.getData(), StandardCharsets.UTF_8);
              dbg("recvLoop -> dispatch message from member=" + memberkey + " len=" + m.getData().length);
              HotStuffDispatch.dispatch(this, content);
            }
          } catch (Exception e) {
            if (running)
              e.printStackTrace();
          }
        }

        // Poll all client links (receive regardless of session-established flag)
        try {
          for (AuthenticatedPerfectLink cl : clientLinks.values()) {
            try {
              if (cl.isSessionEstablished()) {
                List<Message> clientMessages = cl.receive();
                if (clientMessages != null) {
                  if (!clientMessages.isEmpty()) {
                    sawProgress = true;
                  }
                  for (Message m : clientMessages) {
                    String content = new String(m.getData(), StandardCharsets.UTF_8);
                    dbg("recvLoop -> client message len=" + m.getData().length);
                    HotStuffDispatch.dispatch(this, content);
                  }
                }
              }
            } catch (Exception ignoreInner) {
              if (running)
                ignoreInner.printStackTrace();
            }
          }
        } catch (Exception e) {
          if (running)
            e.printStackTrace();
        }

        if (sawProgress) {
          lastProgressAtMs = System.currentTimeMillis();
        }

        long now = System.currentTimeMillis();
        long silentForMs = now - lastProgressAtMs;
        if (silentForMs >= VIEW_TIMEOUT_MS) {
          System.out.println("[" + id + "] View timeout after " + silentForMs + "ms, advancing view");
          lastProgressAtMs = now;
          HotStuffView.passNewView(this, viewNumber + 1);
        }

        Thread.sleep(SOCKET_POLL_MS); // poll sockets frequently and drive 12s timeout by wall-clock

      } catch (Exception e) {
        if (running)
          e.printStackTrace();
      }
    }
  }

  synchronized void handlePropose(InternalBlock block, QC highQC, int view, int sender) throws Exception {
    HotStuffPhases.handlePropose(this, block, highQC, view, sender);
  }

  synchronized void handleQCM1(int view, int senderId, BLSSignature blsSignature) throws Exception {
    HotStuffPhases.handleQCM1(this, view, senderId, blsSignature);
  }

  /**
   * Leader sends PRE_COMMIT to replicas → replicas verify and vote PRE_COMMIT.
   *
   * @param view      the current consensus view number
   * @param senderId  the id of the leader sending the message
   * @param proposeQC the PREPARE quorum certificate
   * @throws Exception if broadcast fails
   */
  synchronized void sendPreCommit(int view, int senderId, QC proposeQC) throws Exception {
    dbg("sendPreCommit view=" + view + " sender=" + senderId);
    JSONObject preCommitMsg = new JSONObject();
    preCommitMsg.put("type", PRE_COMMIT);
    preCommitMsg.put("view", view);
    preCommitMsg.put("id", senderId);
    preCommitMsg.put("proposeQC", proposeQC.toString());
    preCommitMsg.put("blockNumber", internalBlocks.size() - 1);

    String message = preCommitMsg.toString();

    broadcast(message, false);
  }

  synchronized void handlePreCommit(int view, int senderId, QC proposeQC, int blockNumber)
      throws Exception {
    HotStuffPhases.handlePreCommit(this, view, senderId, proposeQC, blockNumber);
  }

  synchronized void handleQCM2(int view, int senderId, BLSSignature blsSignature) throws Exception {
    HotStuffPhases.handleQCM2(this, view, senderId, blsSignature);
  }

  /**
   * Leader sends COMMIT to replicas → replicas verify and vote COMMIT.
   *
   * @param view        the current consensus view number
   * @param senderId    the id of the leader sending the message
   * @param preCommitQC the PRE_COMMIT quorum certificate
   * @throws Exception if broadcast fails
   */
  synchronized void sendCommit(int view, int senderId, QC preCommitQC) throws Exception {
    dbg("sendCommit view=" + view + " sender=" + senderId);
    JSONObject commitMsg = new JSONObject();
    commitMsg.put("type", COMMIT);
    commitMsg.put("view", view);
    commitMsg.put("id", senderId);
    commitMsg.put("preCommitQC", preCommitQC.toString());
    commitMsg.put("blockNumber", internalBlocks.size() - 1);

    String message = commitMsg.toString();
    broadcast(message, false);
  }

  /**
   * Replica handles COMMIT from leader → updates lock, votes COMMIT.
   *
   * @param view        the current consensus view number
   * @param senderId    the id of the leader
   * @param PreCommitQC the PRE_COMMIT quorum certificate
   * @param blockNumber the block number being committed
   * @throws Exception if handling fails
   */
  synchronized void handleCommit(int view, int senderId, QC PreCommitQC, int blockNumber)
      throws Exception {
    HotStuffPhases.handleCommit(this, view, senderId, PreCommitQC, blockNumber);
  }

  synchronized void handleQCM3(int view, int senderId, BLSSignature blsSignature) throws Exception {
    HotStuffPhases.handleQCM3(this, view, senderId, blsSignature);
  }

  /**
   * Leader sends DECIDE to replicas → replicas commit block, advance view.
   *
   * @param view     the current consensus view number
   * @param senderId the id of the leader sending the message
   * @param commitQC the COMMIT quorum certificate
   * @throws Exception if broadcast fails
   */
  synchronized void sendDecide(int view, int senderId, QC commitQC) throws Exception {
    dbg("sendDecide view=" + view + " sender=" + senderId);
    JSONObject obj = new JSONObject();
    obj.put("type", DECIDE);
    obj.put("view", view);
    obj.put("id", senderId);
    obj.put("commitQC", commitQC.toString());
    obj.put("blockNumber", internalBlocks.size() - 1);
    broadcast(obj.toString(), false);
  }

  /**
   * Replica handles DECIDE from leader → commits block, advances view.
   *
   * @param view        the current consensus view number
   * @param senderId    the id of the leader
   * @param CommitQC    the COMMIT quorum certificate
   * @param blockNumber the block number being decided
   * @throws Exception if commit or view transition fails
   */
  synchronized void handleDecide(int view, int senderId, QC CommitQC, int blockNumber) throws Exception {
    HotStuffPhases.handleDecide(this, view, senderId, CommitQC, blockNumber);
  }

  // ==================================================================
  // Commit & vote helpers
  // ==================================================================

  /**
   * Appends the block to the local chain and executes it through the EVM.
   *
   * @param blockData the raw block data string to commit
   */
  synchronized void executeCommit(String blockData) {
    // Delegate commit execution to Chain helper
    // derive current leader index and map to ECDSA address if available
    try {
      int leaderId = getLeader(viewNumber);
      String leaderAddrNorm = this.clientEcdsaAddresses.get(leaderId);
      org.hyperledger.besu.datatypes.Address leader = null;
      if (leaderAddrNorm != null) {
        String hex = leaderAddrNorm.startsWith("0x") ? leaderAddrNorm : ("0x" + leaderAddrNorm);
        leader = org.hyperledger.besu.datatypes.Address.fromHexString(hex);
      }
      com.g27.DepChain.Chain.CommitExecutor.executeCommit(this, blockData, leader);
    } catch (Throwable t) {
      dbg("Error executing commit: " + t.getMessage());
    }
  }

  /**
   * Expose EVM executor to Chain helpers.
   *
   * @return the shared {@link EvmExecutor} instance
   */
  public EvmExecutor getEvmExecutor() {
    return this.evmExecutor;
  }

  /**
   * Public wrapper so Chain can request removal of client request entries.
   *
   * @param blockdata the block data string used to identify requests to remove
   */
  public synchronized void removeClientRequests(String blockdata) {
    com.g27.DepChain.Chain.ClientOpps.removeClientRequests(this, blockdata);
  }

  /**
   * Public wrapper to append committed block and persist state (used by Chain).
   *
   * @param blockData the raw block data string
   * @param txs       the list of transactions included in the block
   * @param blockHash the SHA-256 hash of the committed block
   */
  public synchronized void appendCommittedBlock(String blockData, java.util.List<Transaction> txs, String blockHash) {
    String persistedBlock = PersistedBlockFormatter.normalize(blockData, appBlocks);
    int persistedIndex = this.commitedBlockNumber + 1;
    if (appBlocks.add(persistedBlock)) {
      System.out.println("[" + id + "] COMMITTED persistedBlock=" + persistedBlock + "  chain=" + appBlocks);
    }
    PersistedBlockFileStore.saveToChainDirectory(persistedIndex, persistedBlock);
    this.commitedBlockNumber++;
    dbg("Committed block in-memory number=" + this.commitedBlockNumber + " hash=" + blockHash);
  }

  /**
   * Public wrapper to send reply data to client(s).
   *
   * @param replyData the reply payload to send
   */
  public synchronized void sendToClientPublic(String replyData) {
    ClientOpps.sendtoClient(this, replyData);
  }

  /**
   * Execute a read-only EVM call directly (no consensus needed).
   * Returns the ABI-encoded output or an error message.
   *
   * @param tx the transaction JSON object describing the read call
   * @return a JSON object containing the EVM result or error
   */
  synchronized JSONObject executeRead(JSONObject tx) {
    return Execution.executeRead(evmExecutor, tx);
  }

  synchronized void sendToLeader(String message) throws Exception {
    HotStuffNetwork.sendToLeader(this, message);
  }

  // ==================================================================
  // Broadcast
  // ==================================================================

  /**
   * Sends message to every member except self.
   *
   * @param message the message to broadcast
   * @param byz     if {@code true}, applies Byzantine equivocation behaviour
   * @throws Exception if sending fails
   */
  synchronized void broadcast(String message, boolean byz) throws Exception {
    HotStuffNetwork.broadcast(this, message, byz);
  }

  synchronized void semiBroadcast(String message) throws Exception {
    HotStuffNetwork.semiBroadcast(this, message);
  }
}
