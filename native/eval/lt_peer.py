#!/usr/bin/env python3
"""libtorrent 2.x as an independent BitTorrent peer for interop.sh. A test peer only: libtorrent is not part of the app.

    lt_peer.py fetch <torrent | magnet> <peer host:port> <directory> [--name <path end>] [--transport tcp|utp|both]
                     [--encryption enabled|forced|disabled] [--timeout seconds]
    lt_peer.py seed <torrent> <directory> [--transport ...] [--encryption ...]

Loopback only: it listens on 127.0.0.1 and reaches only the peer it is given. No DHT, local discovery, port mapping,
peer exchange plugin or trackers. `fetch` prints "<sha256> <bytes> bytes in <s> s, <how the peer was reached>" and
exits 0 when the file is complete; `seed` prints "seeding on 127.0.0.1:<port>" and runs until it is terminated,
logging how each peer connected.
"""
import argparse
import hashlib
import os
import signal
import sys
import time

import libtorrent as lt

# peer_info::utp_socket, which the Python binding doesn't name.
UTP_SOCKET = 1 << 17


def session(transport, encryption):
    policy = {"enabled": lt.enc_policy.pe_enabled, "forced": lt.enc_policy.pe_forced, "disabled": lt.enc_policy.pe_disabled}[encryption]
    settings = {
        "listen_interfaces": "127.0.0.1:0",
        "outgoing_interfaces": "",
        "enable_dht": False,
        "enable_lsd": False,
        "enable_upnp": False,
        "enable_natpmp": False,
        "enable_ip_notifier": False,
        "dht_bootstrap_nodes": "",
        "allow_multiple_connections_per_ip": True,
        "enable_outgoing_tcp": transport in ("tcp", "both"),
        "enable_incoming_tcp": transport in ("tcp", "both"),
        "enable_outgoing_utp": transport in ("utp", "both"),
        "enable_incoming_utp": transport in ("utp", "both"),
        "out_enc_policy": int(policy),
        "in_enc_policy": int(policy),
        "allowed_enc_level": int(lt.enc_level.rc4) if encryption == "forced" else int(lt.enc_level.both),
        "prefer_rc4": encryption == "forced",
        "alert_mask": int(lt.alert.category_t.error_notification | lt.alert.category_t.peer_notification | lt.alert.category_t.connect_notification | lt.alert.category_t.storage_notification),
        "user_agent": "stream-interop",
    }
    # No default plugins, so no ut_pex; only what these checks need. Nothing is exchanged beyond loopback anyway.
    s = lt.session(settings, 0)
    s.add_extension("ut_metadata")
    return s


def add(s, source, directory):
    if source.startswith("magnet:"):
        atp = lt.parse_magnet_uri(source)
    else:
        atp = lt.add_torrent_params()
        atp.ti = lt.torrent_info(source)
    atp.save_path = directory
    atp.trackers = []
    return s.add_torrent(atp)


def describe(p):
    transport = "uTP" if p.flags & UTP_SOCKET else "TCP"
    encrypted = "RC4" if p.flags & lt.peer_info.rc4_encrypted else "plaintext"
    return f"{transport}/{encrypted}"


def log_alerts(s):
    alerts = s.pop_alerts()
    for a in alerts:
        if a.category() & lt.alert.category_t.error_notification:
            print("alert:", a.message(), file=sys.stderr, flush=True)
    return alerts


def parse_peer(text):
    host, _, port = text.rpartition(":")
    return (host, int(port))


def fetch(a):
    os.makedirs(a.directory, exist_ok=True)
    s = session(a.transport, a.encryption)
    handle = add(s, a.source, a.directory)
    peer = parse_peer(a.peer)
    handle.connect_peer(peer)
    start = time.time()
    index = None
    how = "no peer"
    last = 0.0
    flushing = False
    flushed = False
    while time.time() - start < a.timeout:
        alerts = log_alerts(s)
        flushed = flushed or any(isinstance(alert, lt.cache_flushed_alert) for alert in alerts)
        peers = handle.get_peer_info()
        for p in peers:
            how = describe(p)
        if time.time() - last > 2 and not peers:  # A refused or not yet accepted connection is tried again.
            last = time.time()
            handle.connect_peer(peer)
        if not handle.status().has_metadata:
            time.sleep(0.005)
            continue
        info = handle.torrent_file()
        files = info.orig_files()
        if index is None:
            names = [files.file_path(i) for i in range(files.num_files())]
            index = next(i for i, n in enumerate(names) if n.endswith(a.name)) if a.name else 0
            handle.prioritize_files([4 if i == index else 0 for i in range(files.num_files())])
        if handle.file_progress()[index] == files.file_size(index):
            status = handle.status()
            if not status.errc.value() and status.state in (lt.torrent_status.seeding, lt.torrent_status.finished):
                # Completion counters precede asynchronous disk writes. Hash only after libtorrent flushes them.
                if not flushing:
                    handle.flush_cache()
                    flushing = True
                if not flushed:
                    time.sleep(0.005)
                    continue
                path = os.path.join(a.directory, files.file_path(index))
                actual = os.path.getsize(path)
                if actual != files.file_size(index):
                    raise RuntimeError(f"flushed file has {actual} bytes, expected {files.file_size(index)}")
                digest = hashlib.sha256()
                with open(path, "rb") as f:
                    for chunk in iter(lambda: f.read(1 << 20), b""):
                        digest.update(chunk)
                print(f"{digest.hexdigest()} {files.file_size(index)} bytes in {time.time() - start:.1f} s, {how}", flush=True)
                os._exit(0)
        time.sleep(0.005)
    status = handle.status()
    print(f"timed out: state {status.state}, {status.total_done} bytes, {status.num_peers} peers, error {status.errc.message()}", file=sys.stderr, flush=True)
    log_alerts(s)
    os._exit(1)


def seed(a):
    s = session(a.transport, a.encryption)
    handle = add(s, a.torrent, a.directory)
    deadline = time.time() + 60
    while handle.status().state not in (lt.torrent_status.seeding,):
        log_alerts(s)
        if time.time() > deadline:
            print("Error: never finished checking:", handle.status().state, handle.status().errc.message(), flush=True)
            os._exit(1)
        time.sleep(0.05)
    print(f"seeding on 127.0.0.1:{s.listen_port()}", flush=True)
    stop = []
    signal.signal(signal.SIGTERM, lambda *_: stop.append(1))
    signal.signal(signal.SIGINT, lambda *_: stop.append(1))
    seen = set()
    while not stop:
        log_alerts(s)
        for p in handle.get_peer_info():
            how = (p.ip, describe(p))
            if how not in seen:
                seen.add(how)
                print(f"peer {p.ip[0]}:{p.ip[1]} {how[1]} client={p.client!r}", flush=True)
        time.sleep(0.005)
    os._exit(0)


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    f = commands.add_parser("fetch")
    f.add_argument("source")
    f.add_argument("peer")
    f.add_argument("directory")
    f.add_argument("--name", help="fetch the file whose path ends with this (default: the first file)")
    f.add_argument("--timeout", type=float, default=45)
    z = commands.add_parser("seed")
    z.add_argument("torrent")
    z.add_argument("directory")
    for c in (f, z):
        c.add_argument("--transport", default="both", choices=["tcp", "utp", "both"])
        c.add_argument("--encryption", default="enabled", choices=["enabled", "forced", "disabled"])
    a = parser.parse_args()
    (fetch if a.command == "fetch" else seed)(a)


if __name__ == "__main__":
    main()
