package com.g27.DepChain.Client;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

import org.hyperledger.besu.datatypes.Address;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import org.web3j.crypto.Hash;
import org.json.JSONObject;

import com.g27.DepChain.LayeredChannels.AuthenticatedPerfectLink;

public class ClientInteraction {

  private final static int QUORUM = 3;

  /**
   * Kickstarts the iteraction with the client. Prints the helper menu, reads user
   * input and sends the requests to the replicas.
   *
   * @param sendLinks       A map to all connections from this client via
   *                        AuthenticatedPerfectLink.
   * @param replicaInfos    information about the replicas
   * @param clientEcKeyPair the client's ECDSA key Pair
   * @param chosenClientId  the id of the client.
   * @param CLIENT_PORT     the port where communications are going to happen.
   * @param responses       shared map used to store responses from replicas
   *                        (cleared before each request)
   * @param latchMap        map of request hashes to CountDownLatch
   *                        objects used to track quorum completion
   * @param requestMap      map associating request hashes with the original user
   *                        command for later reference
   *
   */
  public static void startInteraction(Map<Integer, AuthenticatedPerfectLink> sendLinks,
      List<ReplicaInfo> replicaInfos,
      ECKeyPair clientEcKeyPair,
      int chosenClientId,
      int CLIENT_PORT,
      ConcurrentHashMap<Integer, String> responses,
      ConcurrentHashMap<String, CountDownLatch> latchMap,
      ConcurrentHashMap<String, String> requestMap) {

    Scanner scanner = new Scanner(System.in);
    final Address[] senderBox = new Address[1];
    if (clientEcKeyPair != null) {
      try {
        String addrHex = "0x" + Keys.getAddress(clientEcKeyPair);
        senderBox[0] = Address.fromHexString(addrHex);
      } catch (Exception e) {
        System.err.println("[Client] Failed to derive address from ECDSA key: " + e.getMessage());
        scanner.close();
        return;
      }
    } else {
      System.err.println(
          "[Client] No ECDSA key available for this client. Ensure config/public_keys.json contains the client's keypair.");
      scanner.close();
      return;
    }
    final long[] nonceBox = { 0L };

    ClientApp.printMenu(senderBox[0]);

    while (true) {
      System.out.print("> ");
      String line = scanner.nextLine().trim();
      if (line.isEmpty())
        continue;

      String[] parts = line.split("\\s+");
      String cmd = parts[0].toLowerCase();

      if (cmd.equals("q") || cmd.equals("quit")) {
        System.out.println("Exiting client.");
        break;
      }

      if (cmd.equals("help") || cmd.equals("?")) {
        ClientApp.printMenu(senderBox[0]);
        continue;
      }

      if (cmd.equals("wait")) {
        if (parts.length < 2) {
          System.out.println("Usage: wait <requestHash> [timeoutMs]");
          continue;
        }
        String rh = parts[1];
        if (!rh.startsWith("0x") && !rh.startsWith("0X"))
          rh = "0x" + rh;
        long timeout = 30000L;
        if (parts.length >= 3) {
          try {
            timeout = Long.parseLong(parts[2]);
          } catch (NumberFormatException e) {
            System.out.println("Invalid timeout");
            continue;
          }
        }
        java.util.concurrent.CountDownLatch l = latchMap.get(rh);
        if (l == null) {
          System.out.println("No latch found for requestHash=" + rh);
          continue;
        }
        System.out.println("Waiting for quorum for request " + rh + " (timeout=" + timeout + "ms)");
        try {
          boolean ok = l.await(timeout, TimeUnit.MILLISECONDS);
          if (ok)
            System.out.println("Quorum reached for request " + rh);
          else
            System.out.println("Timeout waiting for quorum for " + rh);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          System.out.println("Interrupted while waiting");
        }
        latchMap.remove(rh);
        continue;
      }

      if (cmd.equals("addr")) {
        if (parts.length < 2) {
          System.out.println("Usage: addr <hex-address|id>");
          continue;
        }
        try {
          senderBox[0] = ClientApp.resolveAddress(parts[1]);
          System.out.println("Sender address set to " + senderBox[0].toHexString());
        } catch (Exception e) {
          System.out.println("Invalid address: " + e.getMessage());
        }
        continue;
      }

      if (cmd.equals("nonce")) {
        if (parts.length < 2) {
          System.out.println("Usage: nonce <n>");
          continue;
        }
        try {
          nonceBox[0] = Long.parseLong(parts[1]);
          System.out.println("Nonce set to " + nonceBox[0]);
        } catch (NumberFormatException e) {
          System.out.println("Invalid nonce");
        }
        continue;
      }

      if (cmd.equals("gaslimit") || cmd.equals("gas-limit")) {
        if (parts.length < 2) {
          System.out.println("Usage: gaslimit <n>");
          continue;
        }
        try {
          long gl = Long.parseLong(parts[1]);
          ClientTransactionBuilder.setDefaultGasLimit(gl);
          System.out.println("Default gas limit set to " + gl);
        } catch (NumberFormatException e) {
          System.out.println("Invalid gas limit");
        } catch (IllegalArgumentException e) {
          System.out.println("Invalid gas limit: " + e.getMessage());
        }
        continue;
      }

      if (cmd.equals("gasprice") || cmd.equals("gas-price")) {
        if (parts.length < 2) {
          System.out.println("Usage: gasprice <n>");
          continue;
        }
        try {
          long gp = Long.parseLong(parts[1]);
          ClientTransactionBuilder.setDefaultGasPrice(gp);
          System.out.println("Default gas price set to " + gp);
        } catch (NumberFormatException e) {
          System.out.println("Invalid gas price");
        } catch (IllegalArgumentException e) {
          System.out.println("Invalid gas price: " + e.getMessage());
        }
        continue;
      }

      JSONObject payload = null;
      boolean isRead = false;

      try {
        switch (cmd) {
          case "transfer": {
            if (parts.length < 3) {
              System.out.println("Usage: transfer <to|id> <amount>");
              continue;
            }
            Address to;
            try {
              to = ClientApp.resolveAddress(parts[1]);
            } catch (Exception e) {
              System.out.println("Invalid recipient: " + e.getMessage());
              continue;
            }
            BigInteger amt = new BigInteger(parts[2]);
            payload = ClientTransactionBuilder.istTransfer(senderBox[0], to, amt, nonceBox[0]++);
            break;
          }
          case "transfer-from": {
            if (parts.length < 4) {
              System.out.println("Usage: transfer-from <from|id> <to|id> <amount>");
              continue;
            }
            Address fromAddr, toAddr;
            try {
              fromAddr = ClientApp.resolveAddress(parts[1]);
            } catch (Exception e) {
              System.out.println("Invalid from-address: " + e.getMessage());
              continue;
            }
            try {
              toAddr = ClientApp.resolveAddress(parts[2]);
            } catch (Exception e) {
              System.out.println("Invalid to-address: " + e.getMessage());
              continue;
            }
            BigInteger amtTf = new BigInteger(parts[3]);
            payload = ClientTransactionBuilder.istTransferFrom(senderBox[0], fromAddr, toAddr, amtTf,
                nonceBox[0]++);
            break;
          }

          case "approve": {
            if (parts.length < 3) {
              System.out.println("Usage: approve <spender|id> <amount>");
              continue;
            }
            Address spender;
            try {
              spender = ClientApp.resolveAddress(parts[1]);
            } catch (Exception e) {
              System.out.println("Invalid spender: " + e.getMessage());
              continue;
            }
            BigInteger amtAp = new BigInteger(parts[2]);
            payload = ClientTransactionBuilder.istApprove(senderBox[0], spender, amtAp, nonceBox[0]++);
            break;
          }

          case "increase-allowance": {
            if (parts.length < 3) {
              System.out.println("Usage: increase-allowance <spender|id> <delta>");
              continue;
            }
            Address spenderInc;
            try {
              spenderInc = ClientApp.resolveAddress(parts[1]);
            } catch (Exception e) {
              System.out.println("Invalid spender: " + e.getMessage());
              continue;
            }
            BigInteger deltaInc = new BigInteger(parts[2]);
            payload = ClientTransactionBuilder.istIncreaseAllowance(senderBox[0], spenderInc, deltaInc,
                nonceBox[0]++);
            break;
          }

          case "decrease-allowance": {
            if (parts.length < 3) {
              System.out.println("Usage: decrease-allowance <spender|id> <delta>");
              continue;
            }
            Address spenderDec;
            try {
              spenderDec = ClientApp.resolveAddress(parts[1]);
            } catch (Exception e) {
              System.out.println("Invalid spender: " + e.getMessage());
              continue;
            }
            BigInteger deltaDec = new BigInteger(parts[2]);
            payload = ClientTransactionBuilder.istDecreaseAllowance(senderBox[0], spenderDec, deltaDec,
                nonceBox[0]++);
            break;
          }

          case "allowance": {
            if (parts.length < 3) {
              System.out.println("Usage: allowance <owner|id> <spender|id>");
              continue;
            }
            Address owner, spenderA;
            try {
              owner = ClientApp.resolveAddress(parts[1]);
            } catch (Exception e) {
              System.out.println("Invalid owner: " + e.getMessage());
              continue;
            }
            try {
              spenderA = ClientApp.resolveAddress(parts[2]);
            } catch (Exception e) {
              System.out.println("Invalid spender: " + e.getMessage());
              continue;
            }
            payload = ClientTransactionBuilder.istAllowance(senderBox[0], owner, spenderA);
            isRead = true;
            break;
          }

          case "depcoin": {
            if (parts.length < 3) {
              System.out.println("Usage: depcoin <to|id> <wei>");
              continue;
            }
            Address toDc;
            try {
              toDc = ClientApp.resolveAddress(parts[1]);
            } catch (Exception e) {
              System.out.println("Invalid recipient: " + e.getMessage());
              continue;
            }
            long wei;
            try {
              wei = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
              System.out.println("Invalid wei amount");
              continue;
            }
            payload = ClientTransactionBuilder.depCoinTransfer(senderBox[0], toDc, wei, nonceBox[0]++);
            break;
          }
          case "balance": {
            Address target = senderBox[0];
            if (parts.length >= 2) {
              try {
                target = ClientApp.resolveAddress(parts[1]);
              } catch (Exception e) {
                System.out.println("Invalid address: " + e.getMessage());
                continue;
              }
            }
            payload = ClientTransactionBuilder.istBalanceOf(senderBox[0], target);
            isRead = true;
            break;
          }
          case "total-supply": {
            payload = ClientTransactionBuilder.istTotalSupply(senderBox[0]);
            isRead = true;
            break;
          }
          default:
            System.out.println("Unknown or unimplemented command '" + cmd + "'.");
            continue;
        }
      } catch (Exception e) {
        System.out.println("Error building transaction: " + e.getMessage());
        continue;
      }

      // Clear shared slot map but do not block waiting for quorum.
      // Responses will be printed asynchronously by the listener so multiple
      // requests can be sent without waiting for replies.
      responses.clear();

      // Add unique identifier to prevent requestHash collisions for identical
      // requests
      payload.put("uniqueId", System.nanoTime());

      // Build payload string and signature once to avoid duplicated work per-replica
      String payloadStr = payload.toString();
      String sigHex = null;
      if (clientEcKeyPair != null) {
        try {
          byte[] msg = payloadStr.getBytes(StandardCharsets.UTF_8);
          byte[] hash = Hash.sha3(msg);
          Sign.SignatureData sd = Sign.signMessage(hash, clientEcKeyPair, false);
          byte[] sigBytes = new byte[65];
          System.arraycopy(sd.getR(), 0, sigBytes, 0, 32);
          System.arraycopy(sd.getS(), 0, sigBytes, 32, 32);
          sigBytes[64] = sd.getV()[0];
          sigHex = Numeric.toHexString(sigBytes);
        } catch (Throwable ignore) {
          sigHex = null;
        }
      }

      // Create a base envelope with common fields
      JSONObject baseEnvelope = new JSONObject();
      baseEnvelope.put("address", senderBox[0].toHexString());
      if (chosenClientId >= 0)
        baseEnvelope.put("clientId", chosenClientId);
      if (sigHex != null)
        baseEnvelope.put("signature", sigHex);

      // Compute request hash to identify this request across replies
      String reqHash = null;
      try {
        byte[] hh = Hash.sha3(payloadStr.getBytes(StandardCharsets.UTF_8));
        reqHash = Numeric.toHexString(hh);
        baseEnvelope.put("requestHash", reqHash);
        // create per-request latch for quorum tracking (not awaited here)
        latchMap.put(reqHash, new CountDownLatch(QUORUM));
        if (requestMap != null) {
          // keep the original user command for user-friendly final output
          requestMap.put(reqHash, line);
        }
      } catch (Throwable ignore) {
        reqHash = null;
      }

      for (ReplicaInfo replica : replicaInfos) {
        int rid = replica.id();
        AuthenticatedPerfectLink out = sendLinks.get(rid);
        if (out == null) {
          System.err.println("[Client] No send link for replica " + rid);
          continue;
        }
        int targetPort = CLIENT_PORT + 4 + rid;

        JSONObject envelope = new JSONObject(baseEnvelope.toString()); // shallow copy
        if (isRead) {
          envelope.put("type", "READ");
          envelope.put("value", payloadStr);
        } else {
          envelope.put("type", "REQUEST");
          envelope.put("value", payloadStr);
        }

        try {
          out.send(new com.g27.DepChain.LayeredChannels.Message(
              envelope.toString().getBytes(StandardCharsets.UTF_8),
              replica.address(),
              targetPort));
        } catch (Exception e) {
          if (com.g27.DepChain.Client.ClientApp.isDebug())
            e.printStackTrace();
        }
      }

      // Non-blocking mode: don't wait here. Listener will print responses
      // as they arrive. Continue accepting user input immediately.
      System.out.println("[" + (isRead ? "READ" : "WRITE") + "] Sent to replicas (non-blocking)");

    }

    scanner.close();
    System.out.println("Exiting…");
  }
}
