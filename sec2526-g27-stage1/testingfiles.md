# Test Files

This document tracks what each test file validates.

## Full Test Coverage Table

| Test file | What it validates |
|---|---|
| `Chain/ChainBlockVerificationTest.java` | App-layer request/block verification: signature validity, request-hash consistency, nonce ordering, read-request signature format, and block gas-cap checks. |
| `Chain/DataBlockManagerTest.java` | Request admission and ordering: gas-price priority, per-sender nonce safety, gas-limited block selection, deterministic block-hash shape, and queue removal helpers. |
| `HotStuff/HotStuffByzantineTest.java` | Multi-node consensus behavior under byzantine modes (drop, equivocation, invalid BLS, replay), honest-chain consistency, and catch-up path behavior. |
| `HotStuff/HotStuffSecurityTest.java` | Cryptographic security: BLS signature verification rejection of wrong keys and tampered messages, InternalBlock null/malformed input handling, and chain-extension safety rule (conflicting PROPOSE with wrong parent hash is rejected). |
| `HotStuff/EndToEndClientFlowIntegrationTest.java` | End-to-end client flow simulation (client 4 + client 5): signed request validation, commit execution, read-back state checks, negative fee/gas failure paths, ERC-20 frontrunning-prevention assertions, two-tx same-block with insufficient gas for second tx, and mixed-block with EVM-reverted first tx (nonce still consumed) followed by successful second tx. |
| `LayeredChannels/AuthenticatedPerfectLinkTest.java` | Authenticated link behavior: DH session establishment, successful authenticated delivery, and drop of tampered (invalid MAC) packets. |
| `LayeredChannels/PerfectLinkTest.java` | Perfect-link semantics: message delivery, duplicate handling, and sender-to-receiver transfer path. |
| `LayeredChannels/StubbornLinkTest.java` | Retransmission behavior until stop condition (ACK/stopSend) and halt of further sends after stop. |
| `smartContact/EvmExecutorTest.java` | EVM runtime correctness: contract deployment, ERC-20 operations, native transfers, failure paths, and gas-fee arithmetic helpers. |
| `smartContact/FrontrunningPreventionTest.java` | ERC-20 allowance frontrunning protections: non-zero-to-non-zero `approve` guard and `decreaseAllowance` behavior after allowance drain. |
| `smartContact/GenesisLoaderTest.java` | Deterministic genesis application: account seeding, deployer derivation, contract deployment, metadata/supply checks, and initial balances. |
| `smartContact/TransferFeeTest.java` | Commit-path fee accounting via `CommitExecutor`: sender debit, receiver credit, and leader fee credit invariants. |

## Chain/ChainBlockVerificationTest.java

Status: fully documented.

Covered behavior:
- App-layer client request signature/data validation (without node/socket startup):
  - Accepts correctly signed client request envelopes.
  - Rejects envelopes with incorrect signatures.
  - Rejects requestHash mismatches.
- App-layer read-request signature checks:
  - Rejects malformed read signatures (invalid length/format).
  - Accepts well-formed read signatures.
- Block payload verification at app layer (envelope-based validation):
  - Accepts valid signed envelopes with correct nonce ordering and gas within cap.
  - Rejects nonce ordering violations for the same sender.
  - Rejects envelopes with invalid signatures.
  - Rejects block payloads exceeding app-layer gas cap (600,000).
- Replica response signature verification compatibility:
  - Verifies replica-signed response payloads against stored public key logic.
  - Confirms tampering invalidates the signature.

## Chain/DataBlockManagerTest.java

Status: fully documented.

Covered behavior:
- Priority between different clients:
  - Two valid operations from different clients are ordered by gas price.
  - Highest gas price is selected first even if it was enqueued later.
- Priority within the same client:
  - Two operations from the same sender are ordered by nonce.
  - Nonce ordering has precedence over gas-price ordering for that sender.
- Transaction validity (through `enqueueClientRequest`):
  - Valid request is accepted.
  - Rejected cases include missing `value`, malformed JSON payload, `gasPrice < 1`, `gasLimit <= 0`, and `gasLimit > 200_000`.
- Block gas constraints:
  - `pollTopRequestsForGas` respects provided block gas limits and skips entries that would overflow the cap.
  - Missing `gasLimit` in a tx uses default 200,000 gas during selection.
  - `buildAndRegisterDataBlock` respects internal max gas per block (600,000).
- Block serialization invariants:
  - Output includes `transactions`, `previous_block_hash`, and `block_hash`.
  - `block_hash` is deterministic and matches `SHA-256(transactions.toString())` with `0x` prefix.
  - `previous_block_hash` is preserved and supports null parent hashes.
- Queue mutation helpers:
  - `removeClientRequestExact` removes the expected envelope.
  - `removeClientRequestMatchingInnerValue` removes by inner tx payload.


## HotStuff/HotStuffByzantineTest.java

Status: fully documented.

Covered behavior:
- All-honest baseline commit flow:
  - Genesis commit across all nodes.
  - Commit of a post-genesis block under honest operation.
- Byzantine tolerance scenarios:
  - Mode 1 (drop-all replica): honest quorum still commits.
  - Mode 2 (equivocating leader): honest replicas maintain consistent chain.
  - Mode 3 (invalid BLS voter): honest quorum still commits.
  - Mode 4 (replay attack): honest nodes reject duplicate/replayed commits.
- Catch-up scenario:
  - A previously dropping node returns to honest mode and catches up to committed blocks.

Test rationale:
- Verifies practical BFT behavior in a real multi-node setup, including adversarial modes and recovery via catch-up.

## HotStuff/EndToEndClientFlowIntegrationTest.java

Status: fully documented.

Covered behavior (`simulateClientFlow_withReads_and_frontrunningProtection_inBothRaceOrders`):
- Simulates terminal-style client operation flow with two clients (id 4 and id 5).
- Validates signed client envelopes through `RequestValidator` before commit execution.
- Executes state-changing operations through `CommitExecutor` and confirms expected contract state via read calls.
- Covers transfer, allowance updates (`approve`, `increaseAllowance`, `decreaseAllowance`), and `transferFrom` path.
- Two sequential client5 transactions where the second fails because the sender cannot afford `maxFee = gasLimit * gasPrice`; token state remains unchanged.
- `transferFrom` submitted by client5 using `from=client4` without allowance; call fails and balances/allowance remain unchanged.
- Out-of-gas execution case where sender balance is above fee limit, but `gasUsed > gasLimit`; transaction does not execute token transfer and full fee limit is charged.
- Frontrunning-prevention race order A: owner approves, unsafe non-zero→non-zero approve is rejected, spender drains allowance, owner's late decreaseAllowance fails (underflow).
- Frontrunning-prevention race order B: owner decreases first, spender tries stale larger spend and fails.
- Two transactions from the same client bundled in one block when the client's native balance covers only the first tx's max fee: first tx succeeds, second tx is dropped (native balance exhausted before upfront deduction).

Covered behavior (`commitExecutor_mixedBlock_revertedTxStillAdvancesNonce_validSecondTxSucceeds`):
- Two transactions from the same client bundled in one block where tx1 EVM-reverts (IST over-transfer beyond balance) and tx2 is valid (1 IST transfer).
- Asserts tx1 had no effect on IST balances (revert rolled back ERC-20 state).
- Asserts tx2 executed and moved exactly 1 IST.
- Asserts both nonces were consumed: the committed nonce advances past tx1 even though it reverted, preventing replay of the reverted tx's nonce slot.

## HotStuff/HotStuffSecurityTest.java

Status: fully documented.

Covered behavior:
- BLS signature verification security:
  - Rejects QC signatures created with wrong BLS key pairs.
  - Rejects QC signatures when the message content (blockDataHash) is tampered with.
- InternalBlock input validation:
  - Throws IllegalArgumentException for null string input.
  - Handles malformed PROPOSE messages missing required "block" field.
- Chain-extension safety rule (`verifyblock_rejectsConflictingProposeWithWrongParentHash`):
  - Seeds a committed genesis block on the node directly (package-private fields).
  - Confirms a block correctly extending genesis (matching parent hash) is accepted.
  - Confirms a block at the same view but with a wrong parent hash is rejected by `confirmBlockExtends`, even when BLS verification is skipped (block number 1 special-case).

Test rationale:
- Provides cryptographic security validation for HotStuff consensus protocol components, ensuring signature verification correctly rejects invalid signatures and malformed inputs, and that the chain-extension safety invariant prevents Byzantine leaders from forking the committed chain.

## LayeredChannels/AuthenticatedPerfectLinkTest.java

Status: fully documented.

Covered behavior:
- Establishes authenticated sessions between peers and validates successful message delivery.
- Verifies that tampered packets with invalid HMAC are dropped.

## LayeredChannels/PerfectLinkTest.java

Status: fully documented.

Covered behavior:
- Local send/receive correctness.
- Duplicate handling path.
- Point-to-point send from one link instance to another.


## LayeredChannels/StubbornLinkTest.java

Status: fully documented.

Covered behavior:
- Retransmits pending messages repeatedly while active.
- Stops retransmitting after stop condition (`stopSend`) is applied.


## smartContact/EvmExecutorTest.java

Status: fully documented.

Covered behavior:
- Environment setup and deployment:
  - Creates a fresh in-memory EVM world per test.
  - Seeds native balances for ALICE, BOB, richHolder, and poorHolder.
  - Deploys ISTCoin and validates deployed address/account existence.
  - Confirms deployer native balance remains positive after deploy fees.
- Metadata and supply views:
  - `name()` returns `IST Coin`.
  - `symbol()` returns `IST`.
  - `decimals()` returns `2`.
  - `totalSupply()` returns `10_000_000_000` smallest units.
- ERC-20 state views:
  - `balanceOf(richHolder)` equals full initial supply.
  - `balanceOf(poorHolder)` and `balanceOf(ALICE)` are initially zero.
  - `allowance(owner, spender)` starts at zero.
- `transfer` function cases:
  - Success path transfers tokens from richHolder to ALICE.
  - Sender balance decreases by transferred amount.
  - Failure path when sender has insufficient token balance.
  - Failure path when recipient is zero address.
- `approve` function cases (with frontrunning guard):
  - Success on `approve(0 -> N)`.
  - Success on `approve(N -> 0)` (revoke).
  - Failure on `approve(N -> M)` with `N > 0` and `M > 0`.
  - Failure when spender is zero address.
- `increaseAllowance` / `decreaseAllowance` cases:
  - Atomic adjustment sequence validates final allowance.
  - Underflow-protected failure after frontrunner drains allowance.
- `transferFrom` function cases:
  - Success path spends allowance and deducts remaining value correctly.
  - Failure path when no allowance exists.
  - Failure path when transfer amount exceeds allowance.
- Native transfer execution (`EvmExecutor.transfer`):
  - Success path transfers native coin and charges intrinsic gas (21,000).
  - Failure path when gas limit is below intrinsic minimum.
  - Failure path when sender native balance is insufficient.
- Transaction fee math / builder constraints:
  - Rejects `gasPrice == 0`.
  - Rejects `gasLimit == 0`.
  - Validates `actualFee` uses gas used when below gas limit.
  - Validates `actualFee` caps at gas limit when gas used exceeds it.
  - Validates `maxFee == gasPrice * gasLimit`.

Function-coverage verification against current ISTCoin public/external API:
- `name`: covered.
- `symbol`: covered.
- `decimals`: covered.
- `totalSupply`: covered.
- `balanceOf`: covered.
- `allowance`: covered.
- `transfer`: covered (success + failure cases).
- `approve`: covered (success + failure cases).
- `transferFrom`: covered (success + failure cases).
- `increaseAllowance`: covered.
- `decreaseAllowance`: covered (success + failure case).

## smartContact/FrontrunningPreventionTest.java

Status: fully documented.

Covered behavior:
- `approve` frontrunning guard:
  - Allows initial `approve(0 -> N)`.
  - Rejects unsafe `approve(N -> M)` when both are non-zero.
- Allowance-drain scenario:
  - After allowance is consumed by `transferFrom`, `decreaseAllowance` reverts as expected.

## smartContact/GenesisLoaderTest.java

Status: fully documented.

Covered behavior:
- Generates genesis JSON through GenesisGenerator and asserts the generated payload is not null.
- Applies genesis.json through GenesisLoader.applyGenesisFromFile and asserts apply result is not null.
- Derives clients 4, 5, and 6 from public keys and asserts all three addresses exist and are available in world state.
- Parses genesis transactions and asserts at least one deployment transaction exists.
- Verifies the first genesis transaction includes a deployer address and that deployer equals client 4.
- Recomputes IST contract address from deployer and nonce 0 and verifies:
  - contract account exists,
  - code is set,
  - code is non-empty.
- Calls IST metadata views and verifies:
  - decimals() == 2,
  - name() == "IST Coin",
  - symbol() == "IST".
- Calls totalSupply() and verifies total supply equals 10,000,000,000 smallest units.
- Calls balanceOf() for deployer, client 5, and client 6 and verifies:
  - deployer holds full supply,
  - client 5 holds 0 IST,
  - client 6 holds 0 IST,
  - conservation over these tracked holders: deployer + client5 + client6 == totalSupply.
- Verifies native coin seeded balances for client 5 and client 6 are exactly 100,000,000.
- Verifies native coin final balance for client 4 is within expected deploy-fee bounds:
  - greater than 0,
  - less than or equal to initial 100,000,000,
  - greater than or equal to initial minus max fee (3,000,000),
  - strictly less than initial when client 4 is deployer.

Helper logic used by this test:
- Builds EVM call transactions with sender nonce management.
- Decodes uint256 return values via AbiEncoder.
- Decodes ABI dynamic string returns for name and symbol.
- Recomputes contract address using keccak(sender || nonce).

## smartContact/TransferFeeTest.java

Status: fully documented.

Covered behavior:
- Commit-path native transfer fee accounting (`CommitExecutor.executeCommit`):
  - Executes a `TRANSFER` transaction through commit flow (not direct `EvmExecutor.transfer` call).
  - Uses an envelope payload (`address`, `value`, `requestHash`) compatible with commit parsing.
- Sender/receiver/leader balance invariants after commit execution:
  - Sender balance decreases by `value + actualFee`.
  - Receiver balance increases by transferred `value`.
  - Leader/replica fee account increases by `actualFee`.
- Fee math assumptions validated in test assertions:
  - Intrinsic transfer gas is treated as 21,000.
  - `actualFee = min(gasLimit, intrinsicGas) * gasPrice`.
  - Final sender balance equals `initialSender - value - actualFee`.
- Test harness constraints:
  - Uses a test-specific `HotStuffNode` stub to isolate commit-layer accounting.
  - Avoids network/channel side effects by no-oping commit side-channel methods.
