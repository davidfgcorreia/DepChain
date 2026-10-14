# DepChain — Byzantine Fault Tolerant Consensus

Byzantine Fault Tolerant distributed consensus system implementing HotStuff with BLS signatures, layered channels, and a small EVM-backed runtime.

## Team and source

Public showcase prepared by David Correia from the team repository `goncalodoliveira/sec-2526-g27`. Original contributors are recorded in [ATTRIBUTION.md](ATTRIBUTION.md). This is coursework/prototype code, not a production blockchain.

The source combines HotStuff consensus, BLS signatures, layered communication channels, and an EVM-backed runtime. Node and client commands, along with the split test suites, are documented below.

## Prerequisites

- Java 17 and Maven
- Local ports available for replicas and clients

## Quick Start

From the repository root:

```bash
cd sec2526-g27-stage1
```

Build (skip tests):

```bash
mvn clean package -DskipTests
```

Run the node:

```bash
mvn exec:java
# or explicitly
mvn exec:java -Dexec.mainClass=com.g27.DepChain.App
```

## Client Startup (required id + optional debug)

Client execution is gated by the `run-client` profile and **requires** `client.id`.

Run client with id:

```bash
mvn -Prun-client -Dclient.id=4 exec:java
```

Run client with id and debug enabled:

```bash
mvn -Prun-client -Dclient.id=4 -Ddebug=true exec:java
```

Notes:
- `client.id` must be an integer.
- `debug` accepts `true`/`1`/`yes`.

## Running Tests

WARNING: The tests will fail if they are run before executing the app. Before trying to run tests, make sure you have the public keys by running:

```bash
mvn exec:java
# or explicitly
mvn exec:java -Dexec.mainClass=com.g27.DepChain.App
```

Tests are disabled by default in Maven config. Use the `run-tests` profile to execute them.

### 1) Run the main test suite (excluding APL and EndToEnd)

```bash
mvn -Prun-tests -Dtest='!PerfectLinkTest,!AuthenticatedPerfectLinkTest,!EndToEndClientFlowIntegrationTest' test
```

### 2) Run PL,APL test separately

```bash
mvn -Prun-tests -Dtest=PerfectLinkTest test
```

```bash
mvn -Prun-tests -Dtest=AuthenticatedPerfectLinkTest test
```



### 3) Run End-to-End test separately

```bash
mvn -Prun-tests -Dtest=EndToEndClientFlowIntegrationTest test
```

Why split these two tests:
- `AuthenticatedPerfectLinkTest` (APL) `PerfectLinkTest (PL)` and `EndToEndClientFlowIntegrationTest` should be executed individually because network ports are not always released in time when run in the same batch with other tests.

## Test Files Overview

Main test locations:
- `sec2526-g27-stage1/src/test/java/com/g27/DepChain/Chain`
- `sec2526-g27-stage1/src/test/java/com/g27/DepChain/HotStuff`
- `sec2526-g27-stage1/src/test/java/com/g27/DepChain/LayeredChannels`
- `sec2526-g27-stage1/src/test/java/com/g27/DepChain/smartContact`

Key classes include:
- `DataBlockManagerTest`
- `ChainBlockVerificationTest`
- `HotStuffByzantineTest`
- `AuthenticatedPerfectLinkTest`
- `EndToEndClientFlowIntegrationTest`
- `EvmExecutorTest`
