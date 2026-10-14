package com.g27.DepChain.LayeredChannels;

import com.g27.DepChain.Crypto.AuthConfig;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AuthenticatedPerfectLinkTest {

    private static AuthConfig makeConfig(Integer id, Map<Integer, KeyPair> keyPairs) throws Exception {
        KeyPair kp = keyPairs.get(id);
        Map<Integer, java.security.PublicKey> pubKeys = new HashMap<>();
        for (Map.Entry<Integer, KeyPair> e : keyPairs.entrySet()) {
            pubKeys.put(e.getKey(), e.getValue().getPublic());
        }
        return new AuthConfig(kp.getPrivate(), kp.getPublic(), pubKeys);
    }

    @Test
    void shouldSendAndReceiveAuthenticatedMessage() throws Exception {
        System.out.println("Starting AuthenticatedPerfectLinkTest.shouldSendAndReceiveAuthenticatedMessage");
        // Setup keys for two parties
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kpA = kpg.generateKeyPair();
        KeyPair kpB = kpg.generateKeyPair();
        Map<Integer, KeyPair> keyPairs = new HashMap<>();
        keyPairs.put(0, kpA);
        keyPairs.put(1, kpB);

        AuthConfig configA = makeConfig(0, keyPairs);
        AuthConfig configB = makeConfig(1, keyPairs);
        configA.generateDHKeyPair();
        configB.generateDHKeyPair();

        // Setup links

        AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(5100, 0, 1, configA, 0);
        AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(5101, 1, 0, configB, 0);

        byte[] message = "Hello Authenticated B".getBytes(StandardCharsets.UTF_8);
        linkA.establishSession(InetAddress.getLocalHost(), 5101);
        Thread.sleep(1000); // wait for session to establish
        linkA.send(new Message(message, InetAddress.getLocalHost(), 5101));
        Thread.sleep(1000); // wait for delivery
        List<Message> received = linkB.receive();
        assertArrayEquals(message, received.get(0).getData());

        linkA.close();
        linkB.close();
    }

    /**
     * A packet whose HMAC does not match its payload must be dropped.
     * The valid message sent before the tampered one must still be delivered.
     */
    @Test
    void tamperedMessageIsDropped() throws Exception {
        System.out.println("Starting AuthenticatedPerfectLinkTest.tamperedMessageIsDropped");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kpA = kpg.generateKeyPair();
        KeyPair kpB = kpg.generateKeyPair();
        Map<Integer, KeyPair> keyPairs = new HashMap<>();
        keyPairs.put(0, kpA);
        keyPairs.put(1, kpB);

        AuthConfig configA = makeConfig(0, keyPairs);
        AuthConfig configB = makeConfig(1, keyPairs);
        configA.generateDHKeyPair();
        configB.generateDHKeyPair();

        AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(5200, 0, 1, configA, 0);
        AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(5201, 1, 0, configB, 0);

        // Establish session
        linkA.establishSession(InetAddress.getLocalHost(), 5201);
        Thread.sleep(1000);

        // Send one valid message
        byte[] valid = "valid".getBytes(StandardCharsets.UTF_8);
        linkA.send(new Message(valid, InetAddress.getLocalHost(), 5201));

        // Inject packet
        byte[] payload = "tampered-payload".getBytes(StandardCharsets.UTF_8);
        byte[] fakeMAC = new byte[32]; // all-zero MAC
        ByteBuffer buf = ByteBuffer.allocate(4 + fakeMAC.length + payload.length);
        buf.putInt(fakeMAC.length);
        buf.put(fakeMAC);
        buf.put(payload);
        linkA.senddebug(buf.array(), InetAddress.getLocalHost(), 5201);

        Thread.sleep(500);

        List<Message> received = linkB.receive();

        // Only the valid message should pass HMAC verification
        assertEquals(1, received.size(), "Tampered message must be dropped");
        assertArrayEquals(valid, received.get(0).getData());

        linkA.close();
        linkB.close();
    }
}
