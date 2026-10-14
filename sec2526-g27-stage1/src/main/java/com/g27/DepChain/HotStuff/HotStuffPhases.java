package com.g27.DepChain.HotStuff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.json.JSONObject;

import com.g27.DepChain.BLS.BLSImpl;

import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.bls.BLSPublicKey;

public class HotStuffPhases {

  /**
   * Leader proposes a block to replicas. The block includes a reference to the
   * high QC of the current view,
   * which is used for safety and liveness.
   */
  public static void proposeBlock(HotStuffNode node, String blockData) throws Exception {
    node.dbg("proposeBlock data=" + (blockData == null ? "null" : blockData));

    // Drop the proposal if in Byzantine mode 1
    if (node.byzantineMode == 1) {
      System.out.println("[" + node.id + "] Byzantine mode 1: Not proposing blocks");
      return;
    }

    int currentView = node.viewNumber;
    if (!node.isLeader(currentView)) {
      System.out.println("[" + node.id + "] Not leader for view " + currentView + ", cannot propose");
      return;
    }

    if (node.byzantineMode == 2) {
      System.out.println("[" + node.id + "] Byzantine mode 2: Proposing invalid block to half of the replicas");
    }

    /**
     * In a correct leader, the block proposal includes the block data, the high QC
     * of the current view, and the parent block hash.
     * In Byzantine mode 2, the leader proposes an invalid block (e.g., with
     * incorrect parent hash or malformed data)
     * In Byzantine mode 4, the leader re-proposes the last committed block instead
     * of a new block.
     */
    if (node.byzantineMode != 4) {
      InternalBlock parentBlock = node.internalBlocks.isEmpty() ? null
          : node.internalBlocks.get(node.internalBlocks.size() - 1);
      String parentHash = "null";
      if (parentBlock != null) {
        parentHash = parentBlock.getHash(parentBlock.getQcs().size() - 1);
      }
      QC highQC = node.highQC;
      if (highQC == null)
        highQC = new QC("null");

      InternalBlock newBlock = new InternalBlock(blockData, highQC, parentHash, currentView,
          node.internalBlocks.size());
      JSONObject proposalObj = new JSONObject();
      proposalObj.put("type", "PROPOSE");
      proposalObj.put("block", newBlock.toString());
      proposalObj.put("highQC", highQC.toString());
      proposalObj.put("view", currentView);
      proposalObj.put("id", node.id);

      node.dbg("proposeBlock -> broadcasting proposal");
      boolean equivocate = node.byzantineMode == 2 && !"Genesis".equals(blockData);
      node.broadcast(proposalObj.toString(), equivocate);
    } else {
      if (node.commitedBlockNumber > 0) {
        System.out.println("[" + node.id + "] Byzantine mode 4: Replay attack - re-proposing last committed block");
        InternalBlock replayBlock = node.internalBlocks.get(node.commitedBlockNumber);
        QC replayQC = replayBlock.getQcs().get(0); // high QC used in previous proposal

        JSONObject proposalObj = new JSONObject();
        proposalObj.put("type", "PROPOSE");
        proposalObj.put("block", replayBlock.toString());
        proposalObj.put("highQC", replayQC.toString());
        proposalObj.put("view", replayBlock.getViewNumbers().get(0)); // original view
        proposalObj.put("id", node.id);

        node.broadcast(proposalObj.toString(), false);
      }
    }
  }

  /**
   * Replicas receive the proposal, verify the block and the high QC, and if
   * valid, vote PREPARE.
   * In Byzantine mode 3, replicas sign garbage instead of the correct block hash
   * for their PREPARE vote, causing the leader's aggregation to fail verification
   * at the next step.
   */
  public static void handlePropose(HotStuffNode node, InternalBlock block, QC highQC, int view, int sender)
      throws Exception {
    node.dbg("handlePropose from=" + sender + " view=" + view + " blockData="
        + (block.getBlockData() == null ? "null" : block.getBlockData()) + " highQC="
        + (highQC != null ? highQC.toString() : "null"));

    if (!HotStuffVerify.verifyblock(node, block)) {
      return;
    }
    if (node.getLeader(node.viewNumber) != sender) {
      System.out.println("[" + node.id + "] Rejected PROPOSE from non-leader " + sender);
      return;
    }

    // Track the last valid PROPOSE receipt time for leader-side pacing decisions.
    node.noteProposeReceivedNow();

    String blockHash = block.getHash(0);

    if (node.byzantineMode == 3) {
      System.out.println("[" + node.id + "] Byzantine mode 3: signing garbage for PREPARE vote");
      blockHash = "INVALID_SIG";
    }

    BLSSignature blsSignature = BLSImpl.sign(node.blsConfig, blockHash);
    Bytes blsSignatureBytes = blsSignature.toSSZBytes();
    String hexBlsSignature = blsSignatureBytes.toHexString();

    JSONObject prepareMsg = new JSONObject();
    prepareMsg.put("type", "QCM1");
    prepareMsg.put("view", view);
    prepareMsg.put("id", node.id);
    prepareMsg.put("blsSignature", hexBlsSignature);

    node.sendToLeader(prepareMsg.toString());

    if (node.catchingup) {
      HotStuffView.catchup(node, node.highQC, node.commitedBlockNumber);
    }
  }

  /**
   * Leader receives PREPARE votes from replicas. Once it collects a quorum of
   * valid PREPARE votes,
   * it aggregates the signatures and broadcasts a PRE_COMMIT message to replicas.
   * In Byzantine mode 3, the leader's aggregation will fail verification at the
   * next step due to garbage signatures from replicas.
   */
  public static void handleQCM1(HotStuffNode node, int view, int senderId, BLSSignature blsSignature)
      throws Exception {
    node.dbg("handleQCM1 sender=" + senderId + " view=" + view);
    if (!node.isLeader(view))
      return;

    InternalBlock currentBlock = node.internalBlocks.get(node.PileBlock);
    String blockHash = currentBlock.getHash(0);

    if (!node.triggered.contains("PREPARE:" + node.id + node.viewNumber)) {
      List<BLSPublicKey> blsPublicKey = new ArrayList<>();
      blsPublicKey.add(node.blsConfig.getAllPublicKeys().get(senderId));

      if (BLSImpl.verify(blsPublicKey, blockHash, blsSignature)) {
        System.out.println("[" + node.id + "] Verified PREPARE vote from " + senderId);
      } else {
        System.out.println("[" + node.id + "] Failed to verify PREPARE vote from " + senderId);
        return;
      }
      if (node.prepareVotes.contains(senderId)) {
        System.out.println("[" + node.id + "] Duplicate PREPARE vote from " + senderId);
        return;
      }
      node.blsSignatures.add(blsSignature);
      node.prepareVotes.add(senderId);
    }

    System.out.println(
        "[" + node.id + "] PREPARE vote from " + senderId + " (" + node.prepareVotes.size() + "/" + node.quorumSize
            + ") view="
            + view);
    if (node.prepareVotes.size() >= node.quorumSize
        && !node.triggered.contains("PREPARE:" + node.id + node.viewNumber)) {

      node.triggered.add("PREPARE:" + node.id + node.viewNumber);

      BLSSignature aggregateSignatures = BLSImpl.aggregate(node.blsSignatures);
      List<BLSPublicKey> all = node.blsConfig.getAllPublicKeys();
      List<BLSPublicKey> blsPublicKeys = new ArrayList<>();
      for (int sender : node.prepareVotes) {
        blsPublicKeys.add(all.get(sender));
      }
      if (BLSImpl.verify(blsPublicKeys, blockHash, aggregateSignatures)) {
        node.blsSignatures.clear();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String blockData = currentBlock.getBlockData();
        String blockDatahash = blockData != null
            ? Base64.getEncoder().encodeToString(digest.digest(blockData.getBytes(StandardCharsets.UTF_8)))
            : "null";

        String hexAggregateSignatures = aggregateSignatures.toSSZBytes().toHexString();
        QC proposeQC = new QC(blockHash, blockDatahash, hexAggregateSignatures, node.prepareVotes, "PREPARE", view);
        node.sendPreCommit(view, node.id, proposeQC);
      } else {
        node.dbg("verification failed");
      }
    }

  }

  /**
   * Replicas receive the PRE_COMMIT message from the leader, verify the QC, and
   * if valid, vote COMMIT.
   * In Byzantine mode 3, replicas sign garbage instead of the correct block hash
   * for their COMMIT vote, causing the leader's aggregation to fail verification
   * at the next step.
   */
  public static void handlePreCommit(HotStuffNode node, int view, int senderId, QC proposeQC, int blockNumber)
      throws Exception {
    node.dbg("handlePreCommit from=" + senderId + " view=" + view + " blockNumber=" + blockNumber);
    if (node.getLeader(view) != senderId) {
      System.out.println("[" + node.id + "] Rejected Precommit from non-leader " + senderId);
      return;
    }

    if (!HotStuffVerify.verifyHighQC(node, proposeQC)) {
      System.out.println("[" + node.id + "] Rejected PReCOMMIT: invalid proposeQC");
      return;
    }
    if (node.viewNumber > view) {
      System.out
          .println(
              "[" + node.id + "] Ignoring PROPOSE for old view " + view + " (current view=" + node.viewNumber + ")");
      return;
    }

    String Blockmessage = proposeQC.getDataHash() + "|" + proposeQC.getBlockDataHash() + "|" + node.viewNumber + "|"
        + proposeQC.toString();

    node.dbg("handleCommit blockHash: " + Blockmessage);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    String hashBlock = Base64.getEncoder().encodeToString(digest.digest(Blockmessage.getBytes(StandardCharsets.UTF_8)));

    if (node.byzantineMode == 3) {
      System.out.println("[" + node.id + "] Byzantine mode 3: signing garbage for PRE_COMMIT vote");
      hashBlock = "INVALID_SIG";
    }

    BLSSignature blsSignature = BLSImpl.sign(node.blsConfig, hashBlock);
    Bytes blsSignatureBytes = blsSignature.toSSZBytes();
    String hexBlsSignature = blsSignatureBytes.toHexString();

    JSONObject prepareMsg = new JSONObject();
    prepareMsg.put("type", "QCM2");
    prepareMsg.put("view", view);
    prepareMsg.put("id", node.id);
    prepareMsg.put("blsSignature", hexBlsSignature);

    node.sendToLeader(prepareMsg.toString());

    if (node.catchingup) {
      HotStuffView.catchup(node, node.highQC, node.commitedBlockNumber);
    }

  }

  /**
   * Leader receives COMMIT votes from replicas. Once it collects a quorum of
   * valid COMMIT votes, it aggregates the signatures and broadcasts a DECIDE
   * message to replicas, which then commit the block and advance the view.
   */
  public static void handleQCM2(HotStuffNode node, int view, int senderId, BLSSignature blsSignature)
      throws Exception {
    node.dbg("handleQCM2 sender=" + senderId + " view=" + view);
    if (!node.isLeader(view))
      return;

    InternalBlock currentBlock = node.internalBlocks.get(node.PileBlock);
    String blockHash = currentBlock.getHash(1);

    if (!node.triggered.contains("PreCommit:" + node.id + node.viewNumber)) {
      List<BLSPublicKey> blsPublicKey = new ArrayList<>();
      blsPublicKey.add(node.blsConfig.getAllPublicKeys().get(senderId));

      if (BLSImpl.verify(blsPublicKey, blockHash, blsSignature)) {
        System.out.println("[" + node.id + "] Verified PreCommit vote from " + senderId);
      } else {
        System.out.println("[" + node.id + "] Failed to verify PreCommit vote from " + senderId);
        return;
      }
      if (node.precommitVotes.contains(senderId)) {
        System.out.println("[" + node.id + "] Duplicate PRECOMMIT vote from " + senderId);
        return;
      }
      node.blsSignatures.add(blsSignature);
      node.precommitVotes.add(senderId);
    }

    System.out.println(
        "[" + node.id + "] PreCommit vote from " + senderId + " (" + node.precommitVotes.size() + "/" + node.quorumSize
            + ") view="
            + view);
    if (node.precommitVotes.size() >= node.quorumSize
        && !node.triggered.contains("PreCommit:" + node.id + node.viewNumber)) {
      node.triggered.add("PreCommit:" + node.id + node.viewNumber);

      node.dbg("quorum reached");

      BLSSignature aggregateSignatures = BLSImpl.aggregate(node.blsSignatures);
      List<BLSPublicKey> all = node.blsConfig.getAllPublicKeys();
      List<BLSPublicKey> blsPublicKeys = new ArrayList<>();
      for (int sender : node.precommitVotes) {
        blsPublicKeys.add(all.get(sender));
      }

      if (BLSImpl.verify(blsPublicKeys, blockHash, aggregateSignatures)) {
        node.blsSignatures.clear();
        String blockDatahash = currentBlock.getBlockData() != null
            ? Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(currentBlock.getBlockData().getBytes(StandardCharsets.UTF_8)))
            : "null";

        String hexAggregateSignatures = aggregateSignatures.toSSZBytes().toHexString();
        QC preCommit = new QC(blockHash, blockDatahash, hexAggregateSignatures, node.precommitVotes, "PRECOMMIT", view);
        node.sendCommit(view, node.id, preCommit);
      } else {
        node.dbg("verification failed");
      }
    }

  }

  /**
   * Leader receives COMMIT votes from replicas. Once it collects a quorum of
   * valid COMMIT votes, it aggregates the signatures and broadcasts a DECIDE
   * message to replicas, which then commit the block and advance the view.
   * In Byzantine mode 3, the leader's aggregation will fail verification at the
   * next step due to garbage signatures from replicas.
   */
  public static void handleCommit(HotStuffNode node, int view, int senderId, QC PreCommitQC, int blockNumber)
      throws Exception {
    node.dbg("handleCommit from=" + senderId + " view=" + view + " blockNumber=" + blockNumber);

    if (node.getLeader(view) != senderId) {
      System.out.println("[" + node.id + "] Rejected Commit from non-leader " + senderId);
      return;
    }
    if (!HotStuffVerify.verifyHighQC(node, PreCommitQC)) {
      System.out.println("[" + node.id + "] Rejected Commit: invalid PRECOMMIT QC");
      return;
    }
    if (node.viewNumber > view) {
      System.out
          .println(
              "[" + node.id + "] Ignoring Commit for old view " + view + " (current view=" + node.viewNumber + ")");
      return;
    }

    String blockHashStr = PreCommitQC.getDataHash() + "|" + PreCommitQC.getBlockDataHash() + "|" + node.viewNumber + "|"
        + PreCommitQC.toString();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    String hashBlock = Base64.getEncoder().encodeToString(digest.digest(blockHashStr.getBytes(StandardCharsets.UTF_8)));

    if (node.byzantineMode == 3) {
      System.out.println("[" + node.id + "] Byzantine mode 3: signing garbage for COMMIT vote");
      hashBlock = "INVALID_SIG";
    }

    BLSSignature blsSignature = BLSImpl.sign(node.blsConfig, hashBlock);
    Bytes blsSignatureBytes = blsSignature.toSSZBytes();
    String hexBlsSignature = blsSignatureBytes.toHexString();

    JSONObject prepareMsg = new JSONObject();
    prepareMsg.put("type", "QCM3");
    prepareMsg.put("view", view);
    prepareMsg.put("id", node.id);
    prepareMsg.put("blsSignature", hexBlsSignature);

    node.sendToLeader(prepareMsg.toString());

    if (node.catchingup) {
      HotStuffView.catchup(node, node.highQC, node.commitedBlockNumber);
    }
  }

  /**
   * Leader receives COMMIT votes from replicas. Once it collects a quorum of
   * valid COMMIT votes, it aggregates the signatures and broadcasts a DECIDE
   * message to replicas, which then commit the block and advance the view.
   */
  public static void handleQCM3(HotStuffNode node, int view, int senderId, BLSSignature blsSignature)
      throws Exception {
    node.dbg("handleQCM3 sender=" + senderId + " view=" + view);
    if (!node.isLeader(view))
      return;

    InternalBlock currentBlock = node.internalBlocks.get(node.PileBlock);
    String blockHash = currentBlock.getHash(2);

    if (!node.triggered.contains("Commit:" + node.id + node.viewNumber)) {
      List<BLSPublicKey> blsPublicKey = new ArrayList<>();
      blsPublicKey.add(node.blsConfig.getAllPublicKeys().get(senderId));

      if (BLSImpl.verify(blsPublicKey, blockHash, blsSignature)) {
        System.out.println("[" + node.id + "] Verified Commit vote from " + senderId);
      } else {
        System.out.println("[" + node.id + "] Failed to verify Commit vote from " + senderId);
        return;
      }
      if (node.commitVotes.contains(senderId)) {
        System.out.println("[" + node.id + "] Duplicate COMMIT vote from " + senderId);
        return;
      }
      node.blsSignatures.add(blsSignature);
      node.commitVotes.add(senderId);
    }

    System.out.println(
        "[" + node.id + "] Commit vote from " + senderId + " (" + node.commitVotes.size() + "/" + node.quorumSize
            + ") view="
            + view);
    if (node.commitVotes.size() >= node.quorumSize && !node.triggered.contains("Commit:" + node.id + node.viewNumber)) {

      node.triggered.add("Commit:" + node.id + node.viewNumber);

      BLSSignature aggregateSignatures = BLSImpl.aggregate(node.blsSignatures);
      List<BLSPublicKey> all = node.blsConfig.getAllPublicKeys();
      List<BLSPublicKey> blsPublicKeys = new ArrayList<>();
      for (int sender : node.commitVotes) {
        blsPublicKeys.add(all.get(sender));
      }
      if (BLSImpl.verify(blsPublicKeys, blockHash, aggregateSignatures)) {
        node.blsSignatures.clear();
        String blockDatahash = currentBlock.getBlockData() != null
            ? Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(currentBlock.getBlockData().getBytes(StandardCharsets.UTF_8)))
            : "null";

        String hexAggregateSignatures = aggregateSignatures.toSSZBytes().toHexString();
        QC commitQC = new QC(blockHash, blockDatahash, hexAggregateSignatures, node.commitVotes, "COMMIT", view);
        node.sendDecide(view, node.id, commitQC);
      } else {
        node.dbg("verification failed");
      }
    }

  }

  /**
   * Leader receives COMMIT votes from replicas. Once it collects a quorum of
   * valid COMMIT votes, it aggregates the signatures and broadcasts a DECIDE
   * message to replicas, which then commit the block and advance the view.
   */
  public static void handleDecide(HotStuffNode node, int view, int senderId, QC CommitQC, int blockNumber)
      throws Exception {
    node.dbg("handleDecide from=" + senderId + " view=" + view + " blockNumber=" + blockNumber);
    if (node.getLeader(view) != senderId) {
      System.out.println("[" + node.id + "] Rejected Decide from non-leader " + senderId);
      return;
    }

    if (!HotStuffVerify.verifyHighQC(node, CommitQC)) {
      System.out.println("[" + node.id + "] Rejected DECIDE: invalid CommitQC");
      return;
    }

    String blockData = node.internalBlocks.get(blockNumber).getBlockData();
    node.executeCommit(blockData);
    node.highQC = CommitQC; // update highQC to the commitQC of the decided block
    HotStuffView.passNewView(node, view + 1); // advance to next view after commit
  }

}
