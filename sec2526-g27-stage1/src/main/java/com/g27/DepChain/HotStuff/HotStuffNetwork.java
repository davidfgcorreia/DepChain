package com.g27.DepChain.HotStuff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONObject;

import com.g27.DepChain.LayeredChannels.AuthenticatedPerfectLink;
import com.g27.DepChain.LayeredChannels.Message;

public class HotStuffNetwork {

  /**
   * Opens a AuthenticatedPerfectLink from the node provided to all the other
   * replicas.
   */
  public static void openConnectionsToAllMembers(HotStuffNode node) {
    node.dbg("openConnectionsToAllMembers");
    for (java.util.Map.Entry<Integer, NodeConfig.MemberInfo> entry : node.members.entrySet()) {
      int memberId = entry.getKey();
      if (memberId == node.id)
        continue;
      try {
        AuthenticatedPerfectLink memberLink = new AuthenticatedPerfectLink(node.port + memberId, node.id, memberId,
            node.crypto, 0);
        node.memberLinks.put(memberId, memberLink);
      } catch (Exception e) {
        System.err.println("Failed to open connection to member " + memberId + ": " + e.getMessage());
      }
    }
  }

  /**
   * Sends a establishSession to all links from the node provided.
   */
  public static void sendEstablishToAllMembers(HotStuffNode node) {
    node.dbg("sendEstablishToAllMembers");
    for (java.util.Map.Entry<Integer, NodeConfig.MemberInfo> entry : node.members.entrySet()) {
      int memberId = entry.getKey();
      if (memberId == node.id)
        continue;
      NodeConfig.MemberInfo info = entry.getValue();
      AuthenticatedPerfectLink memberLink = node.memberLinks.get(memberId);
      if (memberLink != null) {
        try {
          int targetPort = node.members.get(memberId).port + node.id;
          node.dbg("sendEstablish -> member=" + memberId + " targetPort=" + targetPort);
          memberLink.establishSession(info.address, targetPort);
          System.out.println("[" + node.id + "] Sent establish connection to member " + memberId + " at " + targetPort);
        } catch (Exception e) {
          System.err.println("Failed to send establish to member " + memberId + ": " + e.getMessage());
        }
      }
    }
  }

  /**
   * Sends the message provided to the leader of the current view.
   */
  public static void sendToLeader(HotStuffNode node, String message) throws Exception {
    node.dbg("sendToLeader view=" + node.viewNumber + " leader=" + node.getLeader(node.viewNumber));
    int leaderId = node.getLeader(node.viewNumber);

    if (leaderId == node.id) {
      HotStuffDispatch.dispatch(node, message);
      return;
    }
    AuthenticatedPerfectLink leaderLink = node.memberLinks.get(leaderId);
    if (leaderLink != null) {
      leaderLink.send(new Message(message.getBytes(StandardCharsets.UTF_8), node.members.get(leaderId).address,
          node.members.get(leaderId).port + node.id));
    } else {
      System.err.println("[" + node.id + "] No link to leader " + leaderId);
    }
  }

  /**
   * Sends the message provided to all replicas. Has support for byzantine
   * behaviour for testing purposes.
   */
  public static void broadcast(HotStuffNode node, String message, boolean byz) throws Exception {
    node.dbg("broadcast msgLen=" + (message == null ? 0 : message.length()));
    byte[] data = message.getBytes(StandardCharsets.UTF_8);
    int selfId = node.id;

    if (!byz) {
      for (java.util.Map.Entry<Integer, NodeConfig.MemberInfo> e : node.members.entrySet()) {
        int memberId = e.getKey();
        if (memberId == selfId)
          continue;
        NodeConfig.MemberInfo info = e.getValue();
        AuthenticatedPerfectLink memberLink = node.memberLinks.get(memberId);
        if (memberLink != null) {
          int targetPort = info.port + node.id;
          try {
            node.dbg("broadcast -> sending to member " + memberId + " at port " + targetPort);
            memberLink.send(new Message(data, info.address, targetPort));
            node.dbg("broadcast -> sent to member " + memberId);
          } catch (Exception ex) {
            System.err.println("[" + node.id + "] Failed to send to member " + memberId + ": " + ex.getMessage());
            ex.printStackTrace();
          }
        } else {
          System.err.println("[" + node.id + "] No link to member " + memberId);
        }
      }
      node.dbg("broadcast -> local dispatch");
      HotStuffDispatch.dispatch(node, message);
    } else {
      List<Integer> tempIds = new ArrayList<>(node.members.keySet());
      tempIds.remove(Integer.valueOf(node.id));

      JSONObject fakeProposal = new JSONObject(message);
      JSONObject fakeBlockJson = new JSONObject(fakeProposal.getString("block"));
      fakeBlockJson.put("blockData", "INVALID_BLOCK_DATA");
      fakeProposal.put("block", fakeBlockJson.toString());
      byte[] fakeData = fakeProposal.toString().getBytes(StandardCharsets.UTF_8);

      for (int i = 0; i < tempIds.size(); i++) {
        int memberId = tempIds.get(i);
        NodeConfig.MemberInfo info = node.members.get(memberId);
        AuthenticatedPerfectLink memberLink = node.memberLinks.get(memberId);
        if (memberLink != null) {
          boolean sendFake = memberId == 2 || memberId == 3;
          byte[] payload = sendFake ? fakeData : data;
          int targetPort = info.port + node.id;
          try {
            node.dbg("broadcast -> sending " + (sendFake ? "INVALID" : "REAL") + " to member " + memberId + " at port "
                + targetPort);
            memberLink.send(new Message(payload, info.address, targetPort));
          } catch (Exception ex) {
            System.err.println("[" + node.id + "] Failed to send to member " + memberId + ": " + ex.getMessage());
            ex.printStackTrace();
          }
        } else {
          System.err.println("[" + node.id + "] No link to member " + memberId);
        }
      }
      node.dbg("broadcast -> local dispatch");
      HotStuffDispatch.dispatch(node, message);
    }
  }

  /**
   * Sends the message provided to a quorum of replicas.
   */
  public static void semiBroadcast(HotStuffNode node, String message) throws Exception {
    node.dbg("semiBroadcast msgLen=" + (message == null ? 0 : message.length()));
    byte[] data = message.getBytes(StandardCharsets.UTF_8);
    int selfId = node.id;
    int target = node.f + 1;

    List<Integer> ids = new ArrayList<>();
    ids.addAll(node.members.keySet());
    Collections.shuffle(ids);

    int sent = 0;
    for (int memberId : ids) {
      if (sent >= target)
        break;
      if (memberId == selfId) {
        HotStuffDispatch.dispatch(node, message);
        sent++;
        continue;
      }
      NodeConfig.MemberInfo info = node.members.get(memberId);
      AuthenticatedPerfectLink memberLink = node.memberLinks.get(memberId);
      if (memberLink != null) {
        memberLink.send(new Message(data, info.address, info.port + node.id));
        sent++;
      }
    }
  }

}
