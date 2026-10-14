package com.g27.DepChain.smartContact.artifacts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ContractCompiler
 *
 * Utility used at project initialization to compile Solidity contracts using
 * the `solc` compiler and produce artifact JSON files containing ABI and
 * bytecode. The public method `initContracts` is intended to be invoked once
 * during project startup.
 */
public final class ContractCompiler {

    private ContractCompiler() {}

    /**
     * Compile the known Solidity contract(s) and write artifacts.
     *
     * This method looks for `ISTCoin.sol` at the stage1 source path and uses
     * the external `solc` command to generate `.abi` and `.bin` files, which
     * are then combined into JSON artifacts placed under
     * `sec2526-g27-stage1/src/main/resources/artifacts`.
     *
     * @param workspaceRoot the workspace root (project root) as Path
     * @throws IOException on I/O errors
     * @throws InterruptedException if the solc process is interrupted
     */
    public static void initContracts(Path workspaceRoot) throws IOException, InterruptedException {
        Path solFile = workspaceRoot.resolve("sec2526-g27-stage1/src/main/java/com/g27/DepChain/smartContact/ISTCoin.sol");
        if (!Files.exists(solFile)) {
            System.err.println("ContractCompiler: ISTCoin.sol not found at " + solFile);
            return;
        }

        Path artifactsDir = workspaceRoot.resolve("sec2526-g27-stage1/src/main/resources/artifacts");
        Files.createDirectories(artifactsDir);

        Path solcOut = workspaceRoot.resolve("target/solc-output");
        Files.createDirectories(solcOut);

        // Run solc to produce .abi and .bin files in solcOut
        List<String> command = new ArrayList<>();
        command.add("solc");
        command.add("--optimize");
        command.add("--bin");
        command.add("--abi");
        command.add("-o");
        command.add(solcOut.toString());
        command.add(solFile.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        int rc = p.waitFor();
        if (rc != 0) {
            System.err.println("ContractCompiler: solc failed (rc=" + rc + ") output:\n" + new String(out, StandardCharsets.UTF_8));
            throw new IOException("solc compilation failed, return code=" + rc);
        }

        // Find .bin/.abi pairs and write artifact JSON files (per-contract and combined)
        StringBuilder combined = new StringBuilder();
        combined.append('{');
        boolean first = true;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(solcOut)) {
            for (Path entry : ds) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".bin")) {
                    String contractBase = name.substring(0, name.length() - 4);
                    Path bin = solcOut.resolve(contractBase + ".bin");
                    Path abi = solcOut.resolve(contractBase + ".abi");
                    if (Files.exists(bin) && Files.exists(abi)) {
                        String bytecode = Files.readString(bin, StandardCharsets.UTF_8).trim();
                        if (!bytecode.startsWith("0x")) bytecode = "0x" + bytecode;
                        String abiContent = Files.readString(abi, StandardCharsets.UTF_8).trim();

                        String artifactJson = "{\n" +
                                "  \"contractName\": \"" + contractBase + "\",\n" +
                                "  \"abi\": " + abiContent + ",\n" +
                                "  \"bytecode\": \"" + bytecode + "\"\n" +
                                "}\n";

                        Path outFile = artifactsDir.resolve(contractBase + ".json");
                        Files.writeString(outFile, artifactJson, StandardCharsets.UTF_8);
                        System.out.println("ContractCompiler: wrote artifact " + outFile);

                        if (!first) combined.append(',');
                        combined.append('\n').append('"').append(contractBase).append('"').append(':').append('{');
                        combined.append("\"abi\":").append(abiContent).append(',');
                        combined.append("\"bytecode\":\"").append(bytecode).append("\"}");
                        first = false;
                    }
                }
            }
        }

        combined.append('\n').append('}');
        Path combinedFile = artifactsDir.resolve("contracts.json");
        Files.writeString(combinedFile, combined.toString(), StandardCharsets.UTF_8);
        System.out.println("ContractCompiler: wrote combined artifact " + combinedFile);
    }
}
