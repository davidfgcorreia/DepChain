#!/usr/bin/env bash
set -euo pipefail

CONTRACT="src/main/resources/ISTCoin.sol"
OUTDIR="src/main/resources/artifacts"
OUTFILE="$OUTDIR/ISTCoin.json"

mkdir -p "$OUTDIR"

echo "Compiling $CONTRACT -> $OUTFILE"

if command -v solc >/dev/null 2>&1; then
  echo "Using local solc"
  solc --optimize --combined-json abi,bin,bin-runtime,metadata "$CONTRACT" > "$OUTFILE"
elif command -v docker >/dev/null 2>&1; then
  echo "Using dockerized solc image"
  docker run --rm -v "$(pwd)":/sources ethereum/solc:stable solc --optimize --combined-json abi,bin,bin-runtime,metadata "/sources/$CONTRACT" > "$OUTFILE"
else
  echo "Error: neither 'solc' nor 'docker' is available. Install solc or Docker." >&2
  exit 1
fi

if command -v jq >/dev/null 2>&1; then
  jq . "$OUTFILE" > "$OUTFILE.tmp" && mv "$OUTFILE.tmp" "$OUTFILE"
fi

echo "Wrote $OUTFILE"
