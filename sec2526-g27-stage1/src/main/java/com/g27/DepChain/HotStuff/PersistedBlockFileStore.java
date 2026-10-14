package com.g27.DepChain.HotStuff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class PersistedBlockFileStore {

  private PersistedBlockFileStore() {
  }

  static void saveToChainDirectory(int blockIndex, String persistedBlockJson) {
    try {
      Path chainDir = Paths.get("data", "chain");
      Files.createDirectories(chainDir);
      String fileName = String.format("block-%06d.json", blockIndex);
      Path out = chainDir.resolve(fileName);
      Files.writeString(out, persistedBlockJson, StandardCharsets.UTF_8);
    } catch (Exception e) {
      // Do not throw: block commit flow should continue even if disk write fails.
    }
  }
}
