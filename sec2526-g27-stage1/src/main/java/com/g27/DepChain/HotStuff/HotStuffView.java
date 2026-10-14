package com.g27.DepChain.HotStuff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;
import com.g27.DepChain.Chain.DataBlockManager;

public class HotStuffView {

  /**
   * Passes the view, clearing arrays used during consensus.
   */
  public static void passNewView(HotStuffNode node, int newView) {
    node.dbg("passNewView to=" + newView);
    if (newView <= node.viewNumber)
      return; // only pass to higher views
    System.out.println("[" + node.id + "] Passing to new view " + newView);
    node.viewNumber = newView;
    // Clear votes and state related to the old view
    node.prepareVotes.clear();
    node.precommitVotes.clear();
    node.commitVotes.clear();
    node.blsSignatures.clear();
    node.triggered.clear();
    sendMyHigthQC(node);

  }

  /**
   * Sends the HighQC from the provided node to the leader in a NEW_VIEW message.
   */
  public static void sendMyHigthQC(HotStuffNode node) {
    try {
      node.dbg("sendMyHigthQC");
      JSONObject obj = new JSONObject();
      obj.put("type", "NEW_VIEW");
      obj.put("view", node.viewNumber);
      obj.put("id", node.id);
      obj.put("highQC", node.highQC != null ? node.highQC.toString() : "null");
      String message = obj.toString();
      node.sendToLeader(message);
    } catch (Exception e) {
      System.err.println("[" + node.id + "] Failed to send highQC in NEW_VIEW: " + e.getMessage());
    }
  }

  /**
   * Handles a NEW_VIEW reply sent by the leader. Verifies its correctness and
   * starts doing a catch-up if needed.
   */
  public static void handleNewViewReply(HotStuffNode node, int view, int replicaId, String replicaHighQCstr) {
    node.dbg("handleNewViewReply view=" + view + " replica=" + replicaId);
    try {

      Map<Integer, QC> map = node.newViewResponses.computeIfAbsent(view, v -> new ConcurrentHashMap<>());
      QC qc = new QC(replicaHighQCstr);
      map.put(replicaId, qc);

      if (map.size() >= node.quorumSize && !node.triggered.contains("NEW_VIEW:" + node.id + node.viewNumber)) {
        node.triggered.add("NEW_VIEW:" + node.id + node.viewNumber);

        node.dbg("handleNewViewReply -> received quorum of NEW_VIEW replies for view " + view);

        QC selected = HotStuffVerify.getHigthestQC(node, map);

        if (selected != null) {
          System.out.println("[" + node.id + "] Selected highQC from NEW_VIEW replies: " + selected.toString());
        } else {
          System.out
              .println("[" + node.id + "] No valid highQC found in NEW_VIEW replies; proceeding with local highQC");
        }

        try {
          if (selected != null && !selected.getBlockDataHash().equals(node.highQC.getBlockDataHash())) {
            System.out.println("[" + node.id + "] Detected higher QC in NEW_VIEW replies, initiating catchup");
            System.out
                .println(
                    "[" + node.id + "] Current highQC: " + (node.highQC != null ? node.highQC.toString() : "null"));
            catchup(node, selected, node.commitedBlockNumber);
            node.highQC = selected; // update to the new highQC if we got one
          }
        } catch (Exception e) {
          System.err.println("[" + node.id + "] Error in catchup after NEW_VIEW replies: " + e.getMessage());
        }

        synchronized (node) {
          while (node.catchingup) {
            node.wait();
          }
        }

        viewpropose(node);
      }
    } catch (Exception e) {
      System.err.println("[" + node.id + "] Error handling NEW_VIEW_REPLY: " + e.getMessage());
    }
  }

  /**
   * Starts a catch-up operation, sending to a quorum of replicas a message to
   * receive the blocks it needs to.
   */
  public static void catchup(HotStuffNode node, QC selected, int lastCommited) throws Exception {
    node.dbg("catchup lastCommitted=" + lastCommited + " selected=" + (selected == null ? "null" : "qc"));
    node.catchingup = true;
    JSONObject obj = new JSONObject();
    obj.put("type", "CATCH_UP");
    obj.put("highQC", selected != null ? selected.toString() : "null");
    obj.put("lastblockgood", lastCommited);
    obj.put("id", node.id);
    obj.put("view", node.viewNumber);
    node.semiBroadcast(obj.toString());
  }

  /**
   * Replies to a catchup message it received. Collects and sends all the missing
   * blocks the sender is missing.
   */
  public static void catchupReply(HotStuffNode node, QC selectedQC, int lastCommited, int replicaId) throws Exception {
    node.dbg("catchupReply replica=" + replicaId + " lastCommitted=" + lastCommited);

    if (selectedQC != null) {
      if (!selectedQC.equalBlock(node.highQC)) {
        return;
      }

      List<InternalBlock> missingBlocks = new ArrayList<>();
      if (node.PileBlock > lastCommited + 1) {
        for (int i = lastCommited + 1; i <= node.PileBlock; i++) {
          InternalBlock b = node.internalBlocks.get(i);
          if (b != null) {
            missingBlocks.add(b);
          } else {
            System.err.println("[" + node.id + "] Missing block " + i + " in catchup reply, cannot proceed");
            return;
          }
        }
      }
      if (!missingBlocks.isEmpty()) {
        JSONArray arr = new JSONArray();
        for (InternalBlock ib : missingBlocks) {
          arr.put(ib.toString());
        }
        JSONObject reply = new JSONObject();
        reply.put("type", "CATCH_UP_REPLY");
        reply.put("missingBlocks", arr);
        reply.put("view", node.viewNumber);
        reply.put("id", node.id);
        node.sendToLeader(reply.toString());
      }
    }

  }

  /**
   * Adds all the missing blocks provided during catchup into the internal blocks.
   */
  private static boolean addCatchupBlocks(HotStuffNode node, JSONArray missingBlocks) {
    try {
      for (int i = 0; i < missingBlocks.length(); i++) {
        String item = missingBlocks.getString(i);
        InternalBlock b = new InternalBlock(item);

        HotStuffVerify.verifyblock(node, b);

        node.internalBlocks.add(b);
        if (b.getBlockNumber() > node.PileBlock) {
          node.PileBlock = b.getBlockNumber() + 1;
        }

        if (!HotStuffVerify.verifyblockCatchup(node, b)) {
          System.err.println("[" + node.id + "] Failed to verify block in catchup reply: " + b.toString());
          return false;
        }

        if (b.getQcs().size() == 4) {
          node.executeCommit(b.getBlockData());
        }
      }
    } catch (Exception e) {
      System.err.println("[" + node.id + "] Failed to parse missing blocks in catchup reply: " + e.getMessage());
      return false;
    }
    return true;
  }

  /**
   * handles the replies from the replicas to the catch-up request it sent.
   */
  public static void catchupReplyHandler(HotStuffNode node, JSONArray missingBlocks) {
    node.dbg("catchupReplyHandler missingBlocks=" + missingBlocks.length());
    if (!node.catchingup) {
      return;
    }
    if (addCatchupBlocks(node, missingBlocks)) {
      node.catchingup = false;
      synchronized (node) {
        node.notifyAll();
      }
    }
    InternalBlock lastBlock = node.internalBlocks.get(node.internalBlocks.size() - 1);
    List<QC> lastQCs = lastBlock.getQcs();
    QC lastQC = lastQCs.isEmpty() ? null : lastQCs.get(lastQCs.size() - 1);
    node.highQC = lastQC;
    try {
      catchup(node, lastQC, node.commitedBlockNumber);
    } catch (Exception e) {
      System.err.println("[" + node.id + "] Error in viewpropose after catchup: " + e.getMessage());
    }
  }

  public static void viewpropose(HotStuffNode node) throws Exception {
    node.dbg("viewpropose");

    // Leader pacing: only propose when at least 12s passed since last received
    // PROPOSE.
    if (node.isLeader(node.viewNumber)) {
      long lastRecv = node.getLastProposeReceivedAtMs();
      if (lastRecv > 0L) {
        long elapsed = System.currentTimeMillis() - lastRecv;
        final long minGapMs = 12_000L;
        if (elapsed < minGapMs) {
          long waitMs = minGapMs - elapsed;
          System.out
              .println("[" + node.id + "] Delaying propose: waiting " + waitMs + "ms since last PROPOSE received");
          Thread.sleep(waitMs);
        }
      }
    }

    InternalBlock parentBlock = node.internalBlocks.isEmpty() ? null
        : node.internalBlocks.get(node.internalBlocks.size() - 1);
    String parentHash = "null";
    if (parentBlock != null) {
      parentHash = parentBlock.getHash(parentBlock.getQcs().size() - 1);
    }

    int LastPhase = parentBlock != null ? parentBlock.getQcs().size() - 1 : -1;
    QC lastQC = parentBlock != null && !parentBlock.getQcs().isEmpty()
        ? parentBlock.getQcs().get(parentBlock.getQcs().size() - 1)
        : null;

    // If the last QC indicates a null/genesis QC (view == -1), treat as fully
    // committed
    if (lastQC != null && lastQC.getViewNumber() == -1) {
      LastPhase = 3;
    }

    if (LastPhase == 0) {
      // Always build a fresh block for LastPhase==0.
      // Remove the current internal block (if present) so it is not re-used.
      try {
        if (node.PileBlock >= 0 && node.PileBlock < node.internalBlocks.size()) {
          node.internalBlocks.remove(node.PileBlock);
          node.PileBlock--;
        }
      } catch (Throwable ignore) {
      }
      String data = DataBlockManager.buildAndRegisterDataBlock(node.id, parentHash);
      node.proposeBlock(data);
    } else if (LastPhase == 1) {
      node.sendPreCommit(node.viewNumber, node.id, lastQC);
    } else if (LastPhase == 2) {
      node.sendCommit(node.viewNumber, node.id, lastQC);
    } else if (LastPhase == 3) {
      System.out.println("[" + node.id + "] Last phase was COMMIT, executing block and proposing new one");
      String data = DataBlockManager.buildAndRegisterDataBlock(node.id, parentHash);
      node.proposeBlock(data);
    } else {
      // LastPhase == -1: internalBlocks is empty, propose fresh
      String data = DataBlockManager.buildAndRegisterDataBlock(node.id, parentHash);
      node.proposeBlock(data);
    }
  }

}
