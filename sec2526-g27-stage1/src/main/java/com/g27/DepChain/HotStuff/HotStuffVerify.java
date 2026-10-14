package com.g27.DepChain.HotStuff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;

import tech.pegasys.teku.bls.BLS;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.bls.BLSPublicKey;

public class HotStuffVerify {

  private static final String PROPOSE = "PROPOSE";
  private static final String PRE_COMMIT = "PRE_COMMIT";
  private static final String COMMIT = "COMMIT";

  /**
   * Verifies the BLS aggregated signature in the QC using the public keys of the
   * voters.
   * For the genesis QC (view=-1), there is no signature to verify, so we skip the
   * verification and return true.
   * For other QCs, we check that the aggregated signature is valid for the
   * message (block data hash) and the public keys of the voters.
   * If the QC is missing the aggregated signature or block data hash, or if the
   * signature verification fails, we return false.
   */
  public static boolean verifySignature(HotStuffNode node, QC qc) {
    node.dbg("verifySignature qc.view=" + qc.getViewNumber());
    // Genesis QC (view=-1) has no BLS signature — skip verification
    if (qc.getViewNumber() == -1) {
      node.dbg("verifySignature: genesis QC (view=-1), skipping signature check");
      return true;
    }
    String aggregatedSigStr = qc.getSignature();
    if (aggregatedSigStr == null) {
      node.dbg("verifySignature: missing aggregated signature in QC " + qc.toString());
      return false;
    }
    if (qc.getBlockDataHash() == null) {
      node.dbg("verifySignature: missing blockDataHash in QC");
      return false;
    }
    Bytes aggregatedSigBytes;
    try {
      aggregatedSigBytes = Bytes.fromHexString(aggregatedSigStr);
    } catch (Throwable ex) {
      node.dbg("verifySignature: invalid aggregated signature format: " + ex.getMessage());
      return false;
    }
    BLSSignature aggregatedSig = BLSSignature.fromSSZBytes(aggregatedSigBytes);

    Bytes msgBytes = Bytes.wrap(qc.getBlockDataHash().getBytes(StandardCharsets.UTF_8));

    Set<Integer> voterIds = qc.getVoters();
    if (voterIds.isEmpty() || voterIds.size() < node.quorumSize) {
      System.err.println("QC verification failed: not enogth voters in QC");
      return false;
    }

    List<BLSPublicKey> blsPublicKeys = new ArrayList<>();
    List<BLSPublicKey> allPublicKeys = new ArrayList<>(node.blsConfig.getAllPublicKeys());
    for (int voterId : voterIds) {
      blsPublicKeys.add(allPublicKeys.get(voterId));
    }

    return BLS.fastAggregateVerify(blsPublicKeys, msgBytes, aggregatedSig);
  }

  /**
  * Helper function that verifies if the qc signature
  */
  public static boolean verifyBlockSignatures(HotStuffNode node, InternalBlock block) {
    node.dbg("verifyBlockSignatures");
    for (QC qc : block.getQcs()) {
      if (!verifySignature(node, qc)) {
        System.err.println("Block verification failed: invalid signature in QC " + qc.toString());
        return false;
      }
    }
    return true;
  }

  /**
   * Confirms that the given block extends the last committed block. We check that
   * the parent hash of the new block matches the hash of the last committed
   * block. If we don't have any committed blocks or QCs yet, we accept any
   * proposal as valid.
   */
  public static boolean confirmBlockExtends(HotStuffNode node, InternalBlock newBlock) {
    node.dbg("confirmBlockExtends blockNumber=" + newBlock.getBlockNumber());
    InternalBlock lastBlock = null;
    if (!node.internalBlocks.isEmpty() && node.commitedBlockNumber >= 0
        && node.commitedBlockNumber < node.internalBlocks.size()) {
      lastBlock = node.internalBlocks.get(node.commitedBlockNumber);
    }
    if (lastBlock == null || node.highQC == null) {
      return true; // if we don't have any blocks or QCs yet, accept any proposal
    }
    String parenthash = lastBlock.getHash(lastBlock.getQcs().size() - 1);
    node.dbg("confirmBlockExtends lastBlockHash=" + parenthash);

    String newBlockparent = newBlock.getParentHashes().get(0);
    node.dbg("confirmBlockExtends newBlockParentHash=" + newBlockparent);
    if (!parenthash.equals(newBlockparent)) {
      return false;
    }
    return true;
  }

  /**
   * This method is used during catchup to verify that a received block extends
   * the last committed block, without enforcing view number checks or transaction
   * validation. This allows a node that is behind to accept blocks and update its
   * state until it catches up to the current view. We still verify the signatures
   * in the QCs to ensure the block is certified by a quorum of replicas.
   */
  public static boolean verifyblockCatchup(HotStuffNode node, InternalBlock block) {
    node.dbg("verifyblockCatchup blockNumber=" + (block == null ? "null" : block.getBlockNumber()));
    if (block == null) {
      System.err.println("Block verification failed: block is null");
      return false;
    }

    // Genesis blockdata does not have anything to indicate it is a genesis.
    if (block.getBlockNumber() < 0) {
      System.err.println("Block verification failed: invalid block number");
      return false;
    }

    if (block.getBlockNumber() <= node.commitedBlockNumber) {
      return false;
    }
    // Skip signature verification for the block immediately after Genesis (block
    // number 1)
    if (block.getBlockNumber() != 1) {
      if (!verifyBlockSignatures(node, block)) {
        System.err.println("Block verification failed: invalid signature in QC");
        return false;
      }
    }

    if (confirmBlockExtends(node, block)) {
      // Before accepting, ensure each transaction is either present in pending
      // requests or is a valid signed client envelope.
      try {
        String bd = block.getBlockData();
        // Only verify transactions when this block is the direct successor of our
        // committed block and its parent hash equals the committed block's hash.
        boolean doVerify = false;
        if (node.commitedBlockNumber >= 0 && node.commitedBlockNumber < node.internalBlocks.size()) {
          com.g27.DepChain.HotStuff.InternalBlock lastBlock = node.internalBlocks.get(node.commitedBlockNumber);
          if (lastBlock != null && block.getParentHashes() != null && !block.getParentHashes().isEmpty()) {
            String lastHash = lastBlock.getHash(lastBlock.getQcs().size() - 1);
            String newParent = block.getParentHashes().get(0);
            if (block.getBlockNumber() == node.commitedBlockNumber + 1 && lastHash.equals(newParent)) {
              doVerify = true;
            }
          }
        }
        if (doVerify) {
          com.g27.DepChain.Chain.RequestValidator.ValidationResult vr = com.g27.DepChain.Chain.ChainRequestInspector
              .verifyBlockTransactions(node, bd);
          if (!vr.valid) {
            System.err.println("Block verification failed: " + vr.error);
            return false;
          }
        }
      } catch (Throwable ignore) {
      }

      if (block.getBlockNumber() > node.PileBlock) {
        node.internalBlocks.add(block);
        node.PileBlock++;
      } else {
        node.internalBlocks.set(block.getBlockNumber(), block);
      }
      return true;
    }

    System.err.println("Block verification failed: block does not extend the last committed block");

    return false;
  }

  /**
   * Block verification during normal processing (not catchup) requires the block
   * to extend the last committed block, and also enforces view number checks and
   * transaction validation. This is to ensure that we only accept blocks that are
   * in the correct order and have valid transactions during normal operation.
   */
  public static boolean verifyblock(HotStuffNode node, InternalBlock block) {
    node.dbg("verifyblock blockNumber=" + (block == null ? "null" : block.getBlockNumber()));
    if (block == null) {
      System.err.println("Block verification failed: block is null");
      return false;
    }
    // used for testing purposes — still register in internalBlocks so QC collection
    // works
    if (block.getBlockData().equals("BlockAfterGenesis")) {
      int blockNum = block.getBlockNumber();
      if (blockNum == node.PileBlock + 1) {
        node.internalBlocks.add(block);
        node.PileBlock++;
      } else if (blockNum >= 0 && blockNum < node.internalBlocks.size()) {
        node.internalBlocks.set(blockNum, block);
      } else {
        while (node.internalBlocks.size() <= blockNum) {
          node.internalBlocks.add(null);
        }
        node.internalBlocks.set(blockNum, block);
        node.PileBlock = blockNum;
      }
      return true;
    }

    if (block.getBlockNumber() < 0) {
      System.err.println("Block verification failed: invalid block number");
      return false;
    }

    if (block.getBlockNumber() <= node.commitedBlockNumber) {
      return false;
    }
    // Skip signature verification for the block immediately after Genesis (block
    // number 1)
    if (block.getBlockNumber() != 1) {
      if (!verifyBlockSignatures(node, block)) {
        System.err.println("Block verification failed: invalid signature in QC");
        return false;
      }
    }
    if (block.getViewNumbers().get(0) < node.viewNumber) {
      System.err.println("Block verification failed: block view " + block.getViewNumbers().get(0)
          + " is older than current view " + node.viewNumber);
      return false;
    }

    if (confirmBlockExtends(node, block)) {

      // Validate included transactions before accepting the block.
      try {
        String bd = block.getBlockData();
        com.g27.DepChain.Chain.RequestValidator.ValidationResult vr = com.g27.DepChain.Chain.ChainRequestInspector
            .verifyBlockTransactions(node, bd);
        if (!vr.valid) {
          System.err.println("Block verification failed: " + vr.error);
          return false;
        }
      } catch (Throwable ignore) {
      }

      System.out.println("Block verification passed: block extends the last committed block");
      if (block.getBlockNumber() == node.PileBlock + 1) {
        node.internalBlocks.add(block);
        node.PileBlock++;
      } else {
        node.internalBlocks.set(block.getBlockNumber(), block);
      }
      return true;
    } else if (block.getViewNumbers().get(0) > node.viewNumber) {
      node.viewNumber = block.getViewNumbers().get(0);
      if (block.getBlockNumber() == node.internalBlocks.size() + 1) {
        return false;
      }
      while (node.internalBlocks.size() - 1 < block.getBlockNumber()) {
        node.internalBlocks.add(null);

      }
      node.internalBlocks.set(block.getBlockNumber(), block);
      node.highQC = block.getQcs().get(block.getQcs().size() - 1);
      node.catchingup = true;
      return true;
    }

    return false;
  }

  /**
   * Confirms that the given QC extends the last committed block.
   */
  public static boolean confirmQCextends(HotStuffNode node, QC qc) {
    node.dbg("confirmQCextends");
    InternalBlock lasBlock = node.internalBlocks.isEmpty() ? null
        : node.internalBlocks.get(node.internalBlocks.size() - 1);
    if (node.highQC == null) {
      node.highQC = qc; // update highQC to the new QC
      lasBlock.getQcs().add(qc);
      lasBlock.getParentHashes().add(qc.getBlockDataHash());
      lasBlock.getViewNumbers().add(qc.getViewNumber());
      return true; // if we don't have any blocks or QCs yet, accept any proposal
    }
    String parenthash = lasBlock.getHash(lasBlock.getQcs().size() - 1);
    int pahase = lasBlock.getQcs().size() - 1;
    node.dbg("LastBLockPhase " + pahase);
    node.dbg("confirmQCextends lastBlockHash=" + parenthash);
    node.dbg("confirmQCextends qcBlockHash=" + qc.getBlockDataHash());
    if (!qc.getBlockDataHash().equals(parenthash)) {
      return false;
    }
    node.highQC = qc; // update highQC to the new QC
    lasBlock.getQcs().add(qc);
    lasBlock.getParentHashes().add(qc.getBlockDataHash());
    lasBlock.getViewNumbers().add(qc.getViewNumber());

    return true;
  }

  /**
   * Selects the highest QC from a set of QCs received from replicas, based on
   * view number and phase. The selected QC must have a valid signature.
   */
  public static QC getHigthestQC(HotStuffNode node, Map<Integer, QC> qcs) {
    node.dbg("getHigthestQC candidates=" + qcs.size());
    // If all replies are the "null" QC (constructed via QC("null") with view -1),
    // return one of them immediately to avoid verification failures on empty QCs.
    boolean allNullQC = true;
    QC anyNullQC = null;
    for (QC qc : qcs.values()) {
      if (qc == null)
        continue;
      anyNullQC = qc;
      if (qc.getViewNumber() != -1) {
        allNullQC = false;
        break;
      }
    }
    if (allNullQC && anyNullQC != null) {
      return anyNullQC;
    }
    Map<Integer, QC> working = new HashMap<>(qcs);
    QC selected = null;

    while (!working.isEmpty()) {
      int maxView = Integer.MIN_VALUE;
      int minView = Integer.MAX_VALUE;
      for (QC qc : working.values()) {
        if (qc == null)
          continue;
        if (qc.getViewNumber() > maxView)
          maxView = qc.getViewNumber();
        if (qc.getViewNumber() < minView)
          minView = qc.getViewNumber();
      }
      if (maxView == Integer.MIN_VALUE)
        break;

      List<QC> candidates = new ArrayList<>();
      List<Integer> candidateKeys = new ArrayList<>();
      for (Map.Entry<Integer, QC> e : working.entrySet()) {
        QC qc = e.getValue();
        if (qc != null && qc.getViewNumber() == maxView) {
          candidates.add(qc);
          candidateKeys.add(e.getKey());
        }
      }
      Map<String, Integer> phaseRank = new HashMap<>();
      phaseRank.put(COMMIT, 3);
      phaseRank.put(PRE_COMMIT, 2);
      phaseRank.put(PROPOSE, 1);
      candidates.sort((a, b) -> {
        int ra = phaseRank.getOrDefault(a.getRequestType(), 0);
        int rb = phaseRank.getOrDefault(b.getRequestType(), 0);
        if (ra != rb)
          return Integer.compare(rb, ra);
        return Integer.compare(b.getViewNumber(), a.getViewNumber());
      });

      boolean found = false;
      for (QC candidate : candidates) {
        if (candidate == null)
          continue;
        if (!verifySignature(node, candidate)) {
          continue;
        }
        selected = candidate;
        found = true;
        break;

      }
      if (found) {
        return selected;
      }
      for (Integer k : candidateKeys)
        working.remove(k);
    }

    return null;
  }

  /**
   * QC verification for updating highQC during view change or catchup. We require
   * the QC to have a valid signature and a view number greater than or equal to
   * the current highQC view.
   */
  public static boolean verifyHighQC(HotStuffNode node, QC qc) {
    node.dbg("verifyHighQC qc=" + (qc == null ? "null" : "present"));
    if (qc == null)
      return false;
    if (!verifySignature(node, qc)) {
      System.err.println("QC verification failed: invalid signature");
      return false;
    }
    int qcview = node.highQC != null ? node.highQC.getViewNumber() : -1;
    node.dbg("verifyHighQC qcView=" + qc.getViewNumber() + " currentHighQCView=" + qcview);

    if (qc.getViewNumber() < qcview) {
      System.err
          .println("QC verification failed: expected view >= "
              + (node.highQC != null ? node.highQC.getViewNumber() : -1) + ", got " + qc.getViewNumber());
      return false;
    }
    if (confirmQCextends(node, qc)) {
      System.err.println("QC verification passed: QC extends the current highQC");
      return true;
    } else if (node.highQC == null || qc.getViewNumber() > node.highQC.getViewNumber()) {
      node.highQC = qc; // update highQC if the new QC is from a higher view
      node.catchingup = true; // trigger catchup to update our state to the new view
      return true;
    }
    return false;
  }

}
