"""Adds one peer to a Transmission 4.x resume file's peers2 list, which Transmission loads as TR_PEER_FROM_RESUME.

    python3 -I tr_resume_peer.py <file.resume> <ipv4:port>

Transmission keeps loopback peers from this source, unlike those named by a tracker or PEX, so interop.sh can make it
dial a Stream seeder on 127.0.0.1. Each entry is {flags, socket_address: 4-byte IPv4 + 2-byte port}.
"""
import ipaddress
import struct
import sys


def string(data, i):
    colon = data.index(b':', i)
    n = int(data[i:colon])
    return data[colon + 1:colon + 1 + n], colon + 1 + n


def skip(data, i):
    c = data[i:i + 1]
    if c == b'i':
        return data.index(b'e', i) + 1
    if c in (b'l', b'd'):
        i += 1
        while data[i:i + 1] != b'e':
            i = skip(data, i)
        return i + 1
    return string(data, i)[1]


def main():
    path, peer = sys.argv[1], sys.argv[2]
    host, port = peer.rsplit(':', 1)
    address = ipaddress.IPv4Address(host).packed + struct.pack('>H', int(port))
    entry = b'd5:flagsi0e14:socket_address6:' + address + b'e'
    item = b'6:peers2l' + entry + b'e'
    data = open(path, 'rb').read()
    if data[:1] != b'd':
        sys.exit('not a bencoded dictionary')
    out, i, inserted = b'd', 1, False
    while data[i:i + 1] != b'e':
        key, j = string(data, i)
        end = skip(data, j)
        # Dictionary keys stay sorted; an existing peers2 is replaced.
        if not inserted and key > b'peers2':
            out += item
            inserted = True
        if key != b'peers2':
            out += data[i:end]
        i = end
    if not inserted:
        out += item
    open(path, 'wb').write(out + b'e')


if __name__ == '__main__':
    main()
