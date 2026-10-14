#!/usr/bin/env python3
import sys, re, binascii

def decode_error_string(data):
    # data: bytes starting with 4-byte selector
    # layout: 4 selector | 32 offset | 32 length | data
    if len(data) < 4+32+32:
        return '<truncated>'
    offset = int.from_bytes(data[4:36], 'big')
    length = int.from_bytes(data[36:68], 'big')
    start = 4 + offset
    end = start + length
    if end > len(data):
        return '<truncated>'
    return data[start:end].decode(errors='replace')


def decode_notallowed(data):
    # selector + 32 bytes (address right-aligned)
    if len(data) < 4+32:
        return '<truncated>'
    addr_bytes = data[4+12:4+32]
    return '0x' + addr_bytes.hex()


def decode_frontrunningguard(data):
    # selector + 32 (address) + 32 (uint256)
    if len(data) < 4+32+32:
        return '<truncated>'
    addr_bytes = data[4+12:4+32]
    num = int.from_bytes(data[4+32:4+64], 'big')
    return 'address=' + '0x' + addr_bytes.hex() + ', value=' + str(num)


selectors = {
    '08c379a0': ('Error(string)', decode_error_string),
    'fa5cd00f': ('NotAllowed(address)', decode_notallowed),
    '8726fc8c': ('FrontrunningGuard(address,uint256)', decode_frontrunningguard),
}


def find_reverts(text):
    # match only revert=0xHEX to focus on actual revert data
    pattern = re.compile(r'revert=0x([0-9a-fA-F]+)')
    return pattern.findall(text)


def main():
    if len(sys.argv) < 2:
        print('Usage: decode_reverts.py <logfile>')
        sys.exit(2)
    path = sys.argv[1]
    txt = open(path,'r',errors='ignore').read()
    hexes = find_reverts(txt)
    seen = set()
    for h in hexes:
        if h in seen: continue
        seen.add(h)
        if len(h) < 8:
            print('SKIP short:', h)
            continue
        sel = h[:8].lower()
        b = binascii.unhexlify(h)
        if sel in selectors:
            name, fn = selectors[sel]
            try:
                decoded = fn(b)
            except Exception as e:
                decoded = '<decode-error:' + str(e) + '>'
            print(f'{sel} => {name}: {decoded}')
        else:
            print(f'{sel} => unknown selector, raw={h}')

if __name__=='__main__':
    main()
