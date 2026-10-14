package com.g27.DepChain.Crypto;

import java.security.*;
import java.util.Map;
import javax.crypto.KeyAgreement;

import org.web3j.crypto.ECKeyPair;

public class AuthConfig {
  private final PrivateKey privateKey;
  private final PublicKey publicKey;
  private final Map<Integer, PublicKey> memberPublicKeys; // id -> PublicKey
  // Optional ECDSA (hex) map for ids -> ECDSA public hex (unprefixed or prefixed)
  private Map<Integer, String> ecdsaPublics = new java.util.HashMap<>();
  // ECDSA keypair for signing client responses
  private ECKeyPair ecdsaKeyPair = null;

  // DH keypair for session
  private KeyPair dhKeyPair;
  private byte[] sessionSecret;

  public AuthConfig(PrivateKey privateKey, PublicKey publicKey, Map<Integer, PublicKey> memberPublicKeys)
      throws Exception {
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.memberPublicKeys = memberPublicKeys;
  }

  public void setEcdsaPublics(Map<Integer, String> map) {
    if (map == null) return;
    this.ecdsaPublics.clear();
    this.ecdsaPublics.putAll(map);
  }

  public Map<Integer, String> getEcdsaPublics() {
    return java.util.Collections.unmodifiableMap(this.ecdsaPublics);
  }

  public void setEcdsaKeyPair(ECKeyPair keyPair) {
    this.ecdsaKeyPair = keyPair;
  }

  public ECKeyPair getEcdsaKeyPair() {
    return ecdsaKeyPair;
  }

  public byte[] sign(byte[] data) throws Exception {
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(privateKey);
    signer.update(data);
    return signer.sign();
  }

  public boolean verify(byte[] data, byte[] sig, PublicKey pubKey) throws Exception {
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(pubKey);
    verifier.update(data);
    return verifier.verify(sig);
  }

  public PublicKey getPublicKey() {
    return publicKey;
  }

  public PublicKey getMemberPublicKey(int memberId) {
    return memberPublicKeys.get(memberId);
  }

  // --- Diffie-Hellman session key exchange ---
  public KeyPair generateDHKeyPair() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("DH");
    kpg.initialize(2048);
    this.dhKeyPair = kpg.generateKeyPair();
    return this.dhKeyPair;
  }

  public byte[] getDHPublicEncoded() {
    return dhKeyPair.getPublic().getEncoded();
  }

  public void computeSessionSecret(byte[] remoteDHPub) throws Exception {
    KeyFactory kf = KeyFactory.getInstance("DH");
    PublicKey remotePub = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(remoteDHPub));
    KeyAgreement ka = KeyAgreement.getInstance("DH");
    ka.init(dhKeyPair.getPrivate());
    ka.doPhase(remotePub, true);
    this.sessionSecret = ka.generateSecret();
  }

  public byte[] getSessionSecret() {
    return sessionSecret;
  }
}
