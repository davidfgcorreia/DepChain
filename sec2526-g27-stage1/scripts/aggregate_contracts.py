#!/usr/bin/env python3
"""
Aggregate compiled Solidity ABI/BIN files into a single JSON file
written to src/main/resources/artifacts/contracts.json

Usage:
  python3 scripts/aggregate_contracts.py

It expects compiled outputs under target/solc-output with files
like ContractName.abi and ContractName.bin
"""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SOLC_OUT = ROOT / 'target' / 'solc-output'
OUT_JSON = ROOT / 'src' / 'main' / 'resources' / 'artifacts' / 'contracts.json'

def main():
    if not SOLC_OUT.exists():
        print(f"No solc output directory found at {SOLC_OUT}")
        sys.exit(1)

    contracts = {}
    for abi_file in SOLC_OUT.glob('*.abi'):
        name = abi_file.stem
        bin_file = SOLC_OUT / f"{name}.bin"
        try:
            abi_text = abi_file.read_text(encoding='utf-8').strip()
        except Exception as e:
            print(f"Failed to read ABI {abi_file}: {e}")
            continue

        try:
            # parse ABI to ensure valid JSON
            abi_json = json.loads(abi_text)
        except Exception:
            # if ABI is not pure JSON array, keep as raw string
            abi_json = abi_text

        bytecode = None
        if bin_file.exists():
            bytecode = bin_file.read_text(encoding='utf-8').strip()

        contracts[name] = {
            'abi': abi_json,
            'bytecode': bytecode
        }

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(contracts, indent=2), encoding='utf-8')
    print(f"Wrote {OUT_JSON} with {len(contracts)} contract(s)")

if __name__ == '__main__':
    main()
