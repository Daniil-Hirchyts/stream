#!/bin/sh
# Loopback interoperability between Stream's engine and independent implementations. Everything runs on 127.0.0.1
# with generated content: no public trackers, DHT, local discovery, port mapping, LAN peers or internet.
#
#   native/eval/interop.sh            builds what it needs, prints one line per check and a summary
#
# Independent peers: librqbit 9.1.0 (native/eval/rqbit, src/bin/interop.rs), when it builds; it shares the uTP
# library (librqbit-utp 0.7.0) with the engine, so its uTP rows are not independent at that layer. Independent web
# seeds: Node's http/https (webseed.mjs). HTTPS uses a CA generated here and given to the engine as an explicit trust
# root (STREAM_CA_FILE); verification is never disabled. Needs cargo, node, openssl, python3 and shasum.
# CARGO_OFFLINE=1 builds the rqbit client from the local Cargo cache only.
#
# Optional independent clients, run only when found (./setup-tools.sh downloads them outside the repository and
# STREAM_INTEROP_TOOLS names that directory; otherwise the PATH is searched), each with DHT, local discovery, peer
# exchange, port mapping and public trackers off. Their rows are skipped, and say so, when they are absent:
#   libtorrent 2.x (lt_peer.py): BEP 52, MSE/PE and its own uTP, in both directions; the independent check for pure v2.
#   aria2: its own MSE/PE, v1 over TCP. Transmission: its own MSE/PE and libutp, v1. WebTorrent: v1 and hybrid-as-v1 over TCP (it has no BEP 52).
#   http-server (npm): a web seed not written for these checks.
set -eu
# Select a subset when following up one client; avoid repeating an already checked matrix.
clients=${STREAM_INTEROP_CLIENTS:-rqbit webseed libtorrent aria2 transmission webtorrent http-server}
wants() { case " $clients " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
native="$here/.."
work=$(mktemp -d "${TMPDIR:-/tmp}/stream-interop.XXXXXX")
pids=""
mkdir -p "$work/logs"
cleanup() {
  for p in $pids; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
  if [ -n "${STREAM_INTEROP_EVIDENCE:-}" ]; then
    mkdir -p "$STREAM_INTEROP_EVIDENCE"
    cp -R "$work/logs/." "$STREAM_INTEROP_EVIDENCE/"
    echo "Interop logs retained in $STREAM_INTEROP_EVIDENCE"
  fi
  rm -rf "$work"
}
trap cleanup EXIT INT TERM

cargo=${CARGO:-$(command -v cargo || echo "$HOME/.cargo/bin/cargo")}
[ -x "$cargo" ] || { echo "cargo is required" >&2; exit 1; }
offline=
[ "${CARGO_OFFLINE:-0}" = 1 ] && offline=--offline
echo "Building stream-fixture and the fetch example..."
(cd "$native/core" && "$cargo" build --locked --release --bin stream-fixture --example fetch >"$work/logs/build-stream" 2>&1)
fixture="$native/core/target/release/stream-fixture"
fetch="$native/core/target/release/examples/fetch"
rqbit=
if wants rqbit && (cd "$here/rqbit" && "$cargo" build --locked --release $offline --bin interop >"$work/logs/build-rqbit" 2>&1); then
  rqbit="$here/rqbit/target/release/interop"
else
  echo "librqbit client did not build; its rows are skipped."
fi

pass=0 fail=0 expected=0 skipped=0
row() { # row <PASS|FAIL|EXPECTED-FAIL|SKIP> <name> <detail>
  case "$1" in
    PASS) pass=$((pass + 1)) ;;
    FAIL) fail=$((fail + 1)) ;;
    EXPECTED-FAIL) expected=$((expected + 1)) ;;
    SKIP) skipped=$((skipped + 1)) ;;
  esac
  last_row=$1
  printf '%-14s %-58s %s\n' "$1" "$2" "$3"
}
sha() { shasum -a 256 "$1" 2>/dev/null | cut -d' ' -f1; }
# wait_line <file> <pattern>: the background process's ready line, within 30 s; fails if it exits first.
wait_line() {
  i=0
  while ! grep -q "$2" "$1" 2>/dev/null; do
    i=$((i + 1))
    grep -q '^Error' "$1" 2>/dev/null && return 1
    [ $i -gt 300 ] && { echo "timed out waiting for '$2' in $1" >&2; cat "$1" >&2; return 1; }
    sleep 0.1
  done
}
json() { python3 -I -c "import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))" "$@"; }
# with_webseed <in.torrent> <out.torrent> <url>: adds a BEP 19 url-list, the last key of the top-level dictionary.
with_webseed() {
  python3 -I - "$@" <<'PY'
import sys
data = open(sys.argv[1], 'rb').read()
url = sys.argv[3].encode()
assert data.endswith(b'e')
open(sys.argv[2], 'wb').write(data[:-1] + b'8:url-listl' + str(len(url)).encode() + b':' + url + b'ee')
PY
}
# check <name> <expected-sha> <log> <command...>: a download whose first output word is the content's SHA-256.
check() {
  name=$1 want=$2 log=$3
  shift 3
  if "$@" >"$log" 2>"$log.err" && [ "$(head -n1 "$log" | cut -d' ' -f1)" = "$want" ]; then
    row PASS "$name" "$(head -n1 "$log" | cut -d' ' -f2-)"
  else
    row FAIL "$name" "$(cat "$log" "$log.err" | grep -v '^log:' | tail -n1 | cut -c1-160)"
    if [ -n "${STREAM_INTEROP_VERBOSE:-}" ]; then tail -n 20 "$log.err" >&2; fi
  fi
}
# check_refused <name> <reason> <log> <command...>: a download that must not succeed.
check_refused() {
  name=$1 reason=$2 log=$3
  shift 3
  if "$@" >"$log" 2>"$log.err"; then
    row FAIL "$name" "unexpectedly succeeded"
  else
    row EXPECTED-FAIL "$name" "$reason"
  fi
}

# ---- Content and torrents ----
mkdir -p "$work/media/series" "$work/t" "$work/logs"
"$fixture" content "$work/media/film.bin" $((24 << 20)) 7
"$fixture" content "$work/media/series/a.bin" 1363149 8
"$fixture" content "$work/media/series/b.bin" 5243003 9
"$fixture" content "$work/media/series/c.bin" 716800 10
film_sha=$(sha "$work/media/film.bin")
a_sha=$(sha "$work/media/series/a.bin")
b_sha=$(sha "$work/media/series/b.bin")
for v in v1 v2 hybrid; do
  "$fixture" create "$work/t/film-$v.torrent" "$work/media/film.bin" 262144 $v >"$work/t/film-$v.json"
  "$fixture" create "$work/t/series-$v.torrent" "$work/media/series" 262144 $v >"$work/t/series-$v.json"
done
v1_hash=$(json "$work/t/film-v1.json" 'd["v1"]')
v2_hash=$(json "$work/t/film-v2.json" 'd["v2"]')
hy_v1=$(json "$work/t/film-hybrid.json" 'd["v1"]')
hy_v2=$(json "$work/t/film-hybrid.json" 'd["v2"]')
# The middle file of the multi-file torrents, by its index in the torrent (pad files count in v2 and hybrid).
b_index() { json "$work/t/series-$1.json" '[f["index"] for f in d["files"] if f["path"].endswith("b.bin")][0]'; }

# ---- Stream's seeder -> independent leecher ----
"$fixture" seed --listen 127.0.0.1:0 \
  "$work/t/film-v1.torrent" "$work/media" "$work/t/film-v2.torrent" "$work/media" \
  "$work/t/film-hybrid.torrent" "$work/media" "$work/t/series-hybrid.torrent" "$work/media" \
  "$work/t/series-v2.torrent" "$work/media" >"$work/logs/seeder.txt" 2>&1 &
pids="$pids $!"
wait_line "$work/logs/seeder.txt" "listening on port"
ours="127.0.0.1:$(sed -n 's/^listening on port //p' "$work/logs/seeder.txt")"

if [ -n "$rqbit" ]; then
  L="$work/logs"
  check "Stream seed -> rqbit: v1 torrent, TCP" "$film_sha" "$L/r1" "$rqbit" fetch "$work/t/film-v1.torrent" "$ours" "$work/r1"
  check "Stream seed -> rqbit: hybrid torrent, TCP" "$film_sha" "$L/r2" "$rqbit" fetch "$work/t/film-hybrid.torrent" "$ours" "$work/r2"
  check_refused "Stream seed -> rqbit: pure v2 torrent" "rqbit 9.1.0 has no BEP 52 support" "$L/r3" \
    "$rqbit" fetch "$work/t/film-v2.torrent" "$ours" "$work/r3"
  check "Stream seed -> rqbit: v1 magnet (BEP 9 metadata)" "$film_sha" "$L/r4" \
    "$rqbit" fetch "magnet:?xt=urn:btih:$v1_hash" "$ours" "$work/r4"
  check "Stream seed -> rqbit: hybrid magnet by btih (BEP 9)" "$film_sha" "$L/r5" \
    "$rqbit" fetch "magnet:?xt=urn:btih:$hy_v1" "$ours" "$work/r5"
  check "Stream seed -> rqbit: v1 torrent, uTP only" "$film_sha" "$L/r6" \
    "$rqbit" fetch "$work/t/film-v1.torrent" "$ours" "$work/r6" utp
  check "Stream seed -> rqbit: multi-file hybrid (first file)" "$a_sha" "$L/r7" \
    "$rqbit" fetch "$work/t/series-hybrid.torrent" "$ours" "$work/r7"
else
  row SKIP "Stream seed -> rqbit" "no independent leecher built"
fi

# ---- Independent seeder -> Stream's leecher ----
if [ -n "$rqbit" ]; then
  L="$work/logs"
  mkdir -p "$work/rs1" "$work/rs2" "$work/rs3"
  cp "$work/media/film.bin" "$work/rs1/"
  cp "$work/media/film.bin" "$work/rs2/"
  # rqbit's output folder holds a multi-file torrent's files directly.
  cp "$work/media/series/"* "$work/rs3/"
  "$rqbit" seed "$work/t/film-v1.torrent" "$work/rs1" 127.0.0.1:0 both >"$L/rseed1" 2>&1 &
  pids="$pids $!"
  "$rqbit" seed "$work/t/film-hybrid.torrent" "$work/rs2" 127.0.0.1:0 both >"$L/rseed2" 2>&1 &
  pids="$pids $!"
  "$rqbit" seed "$work/t/series-hybrid.torrent" "$work/rs3" 127.0.0.1:0 tcp >"$L/rseed3" 2>&1 &
  pids="$pids $!"
  r1= r2= r3=
  wait_line "$L/rseed1" "seeding on" && r1=$(sed -n 's/^seeding on //p' "$L/rseed1")
  wait_line "$L/rseed2" "seeding on" && r2=$(sed -n 's/^seeding on //p' "$L/rseed2")
  wait_line "$L/rseed3" "seeding on" && r3=$(sed -n 's/^seeding on //p' "$L/rseed3")
  check "rqbit seed -> Stream: v1 torrent, TCP (MSE tried, plaintext)" "$film_sha" "$L/s1" "$fetch" "$work/t/film-v1.torrent" "$r1"
  check "rqbit seed -> Stream: v1 torrent, MSE disabled" "$film_sha" "$L/s2" \
    env STREAM_ENCRYPTION=disabled "$fetch" "$work/t/film-v1.torrent" "$r1"
  check_refused "rqbit seed -> Stream: v1 torrent, MSE forced" "rqbit 9.1.0 has no MSE/PE" "$L/s3" \
    env STREAM_ENCRYPTION=forced STREAM_STALL_SECONDS=8 "$fetch" "$work/t/film-v1.torrent" "$r1"
  check "rqbit seed -> Stream: v1 magnet (BEP 9 metadata)" "$film_sha" "$L/s4" "$fetch" "magnet:?xt=urn:btih:$v1_hash" "$r1"
  check "rqbit seed -> Stream: v1 torrent, uTP only" "$film_sha" "$L/s5" env STREAM_TCP=0 "$fetch" "$work/t/film-v1.torrent" "$r1"
  check "rqbit seed -> Stream: hybrid torrent, TCP" "$film_sha" "$L/s6" "$fetch" "$work/t/film-hybrid.torrent" "$r2"
  check "rqbit seed -> Stream: hybrid magnet btih+btmh" "$film_sha" "$L/s7" \
    "$fetch" "magnet:?xt=urn:btih:$hy_v1&xt=urn:btmh:1220$hy_v2" "$r2"
  if [ -n "$r3" ]; then
    check "rqbit seed -> Stream: multi-file hybrid, middle file" "$b_sha" "$L/s8" \
      "$fetch" "$work/t/series-hybrid.torrent" "$r3" "$(b_index hybrid)"
  else
    row SKIP "rqbit seed -> Stream: multi-file hybrid" "rqbit did not seed it: $(tail -n1 "$L/rseed3")"
  fi
  row SKIP "rqbit seed -> Stream: pure v2" "rqbit 9.1.0 cannot seed v2"
else
  row SKIP "rqbit seed -> Stream" "no independent seeder built"
fi

# ---- Independent web seeds (Node) -> Stream's leecher ----
if wants webseed; then
L="$work/logs"
node "$here/webseed.mjs" "$work/media" >"$L/http" 2>&1 &
pids="$pids $!"
wait_line "$L/http" "listening on"
http="http://127.0.0.1:$(sed -n 's/^listening on //p' "$L/http")"
for v in v1 v2 hybrid; do
  with_webseed "$work/t/film-$v.torrent" "$work/t/film-$v-web.torrent" "$http/film.bin"
  check "Node HTTP web seed -> Stream: $v single file" "$film_sha" "$L/w-$v" "$fetch" "$work/t/film-$v-web.torrent" -
  with_webseed "$work/t/series-$v.torrent" "$work/t/series-$v-web.torrent" "$http/"
  check "Node HTTP web seed -> Stream: $v multi-file, middle file" "$b_sha" "$L/wm-$v" \
    "$fetch" "$work/t/series-$v-web.torrent" - "$(b_index $v)"
done

mkdir -p "$work/tls"
cat >"$work/tls/leaf.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=IP:127.0.0.1
EOF
if openssl req -x509 -newkey rsa:2048 -nodes -keyout "$work/tls/ca.key" -out "$work/tls/ca.pem" -days 2 \
     -subj "/CN=Stream interop test CA" -addext "basicConstraints=critical,CA:TRUE" \
     -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1 &&
   openssl req -newkey rsa:2048 -nodes -keyout "$work/tls/leaf.key" -out "$work/tls/leaf.csr" -subj "/CN=127.0.0.1" \
     >/dev/null 2>&1 &&
   openssl x509 -req -in "$work/tls/leaf.csr" -CA "$work/tls/ca.pem" -CAkey "$work/tls/ca.key" -CAcreateserial \
     -out "$work/tls/leaf.pem" -days 2 -extfile "$work/tls/leaf.cnf" >/dev/null 2>&1; then
  node "$here/webseed.mjs" "$work/media" "$work/tls/leaf.key" "$work/tls/leaf.pem" >"$L/https" 2>&1 &
  pids="$pids $!"
  wait_line "$L/https" "listening on"
  https="https://127.0.0.1:$(sed -n 's/^listening on //p' "$L/https")"
  with_webseed "$work/t/film-hybrid.torrent" "$work/t/film-https.torrent" "$https/film.bin"
  check "Node HTTPS web seed -> Stream: hybrid, CA file given" "$film_sha" "$L/ws1" \
    env STREAM_CA_FILE="$work/tls/ca.pem" "$fetch" "$work/t/film-https.torrent" -
  check_refused "Node HTTPS web seed -> Stream: untrusted certificate" "refused: certificate not trusted" "$L/ws2" \
    env STREAM_STALL_SECONDS=8 "$fetch" "$work/t/film-https.torrent" -
else
  row SKIP "Node HTTPS web seed" "openssl could not make the test certificates"
fi

fi

# ---- Optional independent clients ----
tools=${STREAM_INTEROP_TOOLS:-}
L="$work/logs"
ours_port=${ours##*:}
# seen <pattern> <log>: the other side recorded the feature, so the row can't pass over something else.
seen() { grep -q "$1" "$2" 2>/dev/null; }
stop() {
  kill "$1" 2>/dev/null || true
  wait "$1" 2>/dev/null || true
  kept=
  for pid in $pids; do [ "$pid" = "$1" ] || kept="$kept $pid"; done
  pids=$kept
}
# confirm <name> <pattern> <log>: replaces the last row's PASS when the pattern (unless "any") is missing.
confirm() {
  [ "$last_row" = PASS ] && [ "$2" != any ] || return 0
  seen "$2" "$3" || { row FAIL "$1" "the transfer worked but '$2' was not observed"; pass=$((pass - 1)); }
}
free_port() { python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }
# Start in this shell, not in tracker_url's command substitution, so cleanup owns the process.
node "$here/tracker.mjs" >"$L/tracker" 2>&1 &
tracker_pid=$!
pids="$pids $tracker_pid"
wait_line "$L/tracker" "listening on"
tracker_url() { echo "http://127.0.0.1:$(sed -n 's/^listening on //p' "$L/tracker")/peer/$1/announce"; }

# ---- libtorrent ----
lt_python=${STREAM_INTEROP_PYTHON:-}
[ -z "$lt_python" ] && [ -x "$tools/lt-venv/bin/python" ] && lt_python="$tools/lt-venv/bin/python"
[ -z "$lt_python" ] && lt_python=python3
if wants libtorrent && lt_version=$("$lt_python" -I -c 'import libtorrent as l; print(l.__version__)' 2>/dev/null); then
  echo "libtorrent $lt_version"
  lt() { "$lt_python" -I -W ignore "$here/lt_peer.py" "$@"; }
  n=0
  # lt_fetch <name> <sha> <observed> <source> [lt_peer options]: Stream's seeder -> libtorrent.
  lt_fetch() {
    name=$1 want=$2 observed=$3 source=$4
    shift 4
    n=$((n + 1))
    check "Stream seed -> libtorrent: $name" "$want" "$L/lf$n" lt fetch "$source" "$ours" "$work/lf$n" "$@"
    confirm "Stream seed -> libtorrent: $name" "$observed" "$L/lf$n"
  }
  lt_fetch "v1, TCP, plaintext" "$film_sha" "TCP/plaintext" "$work/t/film-v1.torrent" --transport tcp --encryption disabled
  lt_fetch "v1, TCP, MSE forced" "$film_sha" "TCP/RC4" "$work/t/film-v1.torrent" --transport tcp --encryption forced
  lt_fetch "v1, uTP only" "$film_sha" "uTP/" "$work/t/film-v1.torrent" --transport utp --encryption disabled
  lt_fetch "v1, uTP only, MSE forced" "$film_sha" "uTP/RC4" "$work/t/film-v1.torrent" --transport utp --encryption forced
  lt_fetch "v1 magnet (BEP 9)" "$film_sha" any "magnet:?xt=urn:btih:$v1_hash"
  lt_fetch "pure v2, TCP" "$film_sha" "TCP/" "$work/t/film-v2.torrent" --transport tcp
  lt_fetch "pure v2, TCP, MSE forced" "$film_sha" "TCP/RC4" "$work/t/film-v2.torrent" --transport tcp --encryption forced
  lt_fetch "pure v2, uTP only, MSE forced" "$film_sha" "uTP/RC4" "$work/t/film-v2.torrent" --transport utp --encryption forced
  lt_fetch "pure v2 magnet (BEP 9, BEP 52 hash requests)" "$film_sha" any "magnet:?xt=urn:btmh:1220$v2_hash"
  lt_fetch "hybrid, TCP" "$film_sha" "TCP/" "$work/t/film-hybrid.torrent" --transport tcp
  lt_fetch "hybrid magnet btih+btmh" "$film_sha" any "magnet:?xt=urn:btih:$hy_v1&xt=urn:btmh:1220$hy_v2"
  lt_fetch "multi-file pure v2, middle file" "$b_sha" any "$work/t/series-v2.torrent" --name b.bin
  lt_fetch "multi-file hybrid, middle file" "$b_sha" any "$work/t/series-hybrid.torrent" --name b.bin

  # lt_seed_row <name> <sha> <observed in the seed's log> <torrent> <seed options> -- <fetch env...> -- <source> [file]
  lt_seed_row() {
    name=$1 want=$2 observed=$3 torrent=$4
    shift 4
    seed_options=
    while [ "$1" != -- ]; do seed_options="$seed_options $1"; shift; done
    shift
    fetch_env=
    while [ "$1" != -- ]; do fetch_env="$fetch_env $1"; shift; done
    shift
    n=$((n + 1))
    # shellcheck disable=SC2086 # Words, on purpose.
    "$lt_python" -I -W ignore "$here/lt_peer.py" seed "$torrent" "$work/media" $seed_options >"$L/ls$n" 2>&1 &
    seed_pid=$!
    pids="$pids $seed_pid"
    if ! wait_line "$L/ls$n" "seeding on"; then
      row FAIL "libtorrent seed -> Stream: $name" "$(tail -n1 "$L/ls$n")"
      stop "$seed_pid"
      return
    fi
    address=$(sed -n 's/^seeding on //p' "$L/ls$n")
    source=$1
    file=${2:-0}
    if [ "$observed" = refused ]; then
      # shellcheck disable=SC2086
      check_refused "libtorrent seed -> Stream: $name" "both sides insist: nothing may be transferred" "$L/lt$n" \
        env STREAM_STALL_SECONDS=8 $fetch_env "$fetch" "$source" "$address" "$file"
    else
      # shellcheck disable=SC2086
      check "libtorrent seed -> Stream: $name" "$want" "$L/lt$n" env $fetch_env "$fetch" "$source" "$address" "$file"
      sleep 0.2
      confirm "libtorrent seed -> Stream: $name" "$observed" "$L/ls$n"
    fi
    stop "$seed_pid"
  }
  T="$work/t"
  lt_seed_row "v1, TCP, plaintext" "$film_sha" "TCP/plaintext" "$T/film-v1.torrent" --encryption disabled -- STREAM_ENCRYPTION=disabled -- "$T/film-v1.torrent"
  lt_seed_row "v1, MSE offered, either accepted" "$film_sha" "TCP/" "$T/film-v1.torrent" -- -- "$T/film-v1.torrent"
  lt_seed_row "v1, MSE forced both ways" "$film_sha" "TCP/RC4" "$T/film-v1.torrent" --encryption forced -- STREAM_ENCRYPTION=forced -- "$T/film-v1.torrent"
  lt_seed_row "Stream forces MSE, seed refuses it" "" refused "$T/film-v1.torrent" --encryption disabled -- STREAM_ENCRYPTION=forced -- "$T/film-v1.torrent"
  lt_seed_row "Stream refuses MSE, seed forces it" "" refused "$T/film-v1.torrent" --encryption forced -- STREAM_ENCRYPTION=disabled -- "$T/film-v1.torrent"
  lt_seed_row "v1, uTP only" "$film_sha" "uTP/" "$T/film-v1.torrent" --transport utp --encryption disabled -- STREAM_TCP=0 STREAM_ENCRYPTION=disabled -- "$T/film-v1.torrent"
  lt_seed_row "v1, uTP only, MSE forced" "$film_sha" "uTP/RC4" "$T/film-v1.torrent" --transport utp --encryption forced -- STREAM_TCP=0 STREAM_ENCRYPTION=forced -- "$T/film-v1.torrent"
  lt_seed_row "v1 magnet (BEP 9)" "$film_sha" any "$T/film-v1.torrent" -- -- "magnet:?xt=urn:btih:$v1_hash"
  lt_seed_row "pure v2, TCP, plaintext" "$film_sha" "TCP/plaintext" "$T/film-v2.torrent" --encryption disabled -- STREAM_ENCRYPTION=disabled -- "$T/film-v2.torrent"
  lt_seed_row "pure v2, MSE forced" "$film_sha" "TCP/RC4" "$T/film-v2.torrent" --encryption forced -- STREAM_ENCRYPTION=forced -- "$T/film-v2.torrent"
  lt_seed_row "pure v2, uTP only, MSE forced" "$film_sha" "uTP/RC4" "$T/film-v2.torrent" --transport utp --encryption forced -- STREAM_TCP=0 STREAM_ENCRYPTION=forced -- "$T/film-v2.torrent"
  lt_seed_row "pure v2 magnet (BEP 9, BEP 52 hash requests)" "$film_sha" any "$T/film-v2.torrent" -- -- "magnet:?xt=urn:btmh:1220$v2_hash"
  lt_seed_row "hybrid torrent" "$film_sha" any "$T/film-hybrid.torrent" -- -- "$T/film-hybrid.torrent"
  lt_seed_row "hybrid magnet btih+btmh" "$film_sha" any "$T/film-hybrid.torrent" -- -- "magnet:?xt=urn:btih:$hy_v1&xt=urn:btmh:1220$hy_v2"
  lt_seed_row "multi-file pure v2, middle file" "$b_sha" any "$T/series-v2.torrent" -- -- "$T/series-v2.torrent" "$(b_index v2)"
  lt_seed_row "multi-file hybrid, middle file" "$b_sha" any "$T/series-hybrid.torrent" -- -- "$T/series-hybrid.torrent" "$(b_index hybrid)"
else
  row SKIP "libtorrent" "unavailable or not selected (setup-tools.sh, STREAM_INTEROP_PYTHON, STREAM_INTEROP_CLIENTS)"
fi

# ---- aria2 (shares no code with Stream or the others) ----
aria2=
[ -x "$tools/conda-env/bin/aria2c" ] && aria2="$tools/conda-env/bin/aria2c"
[ -z "$aria2" ] && aria2=$(command -v aria2c || true)
if wants aria2 && [ -n "$aria2" ]; then
  echo "$("$aria2" --version | head -n1)"
  aria_common="--no-conf=true --enable-dht=false --enable-dht6=false --enable-peer-exchange=false --bt-enable-lpd=false --disable-ipv6=true --file-allocation=none --console-log-level=warn --summary-interval=0 --bt-stop-timeout=45"
  # aria_fetch <name> <sha> <torrent> <output file> [options]: Stream's seeder -> aria2, found through the loopback tracker.
  aria_fetch() {
    name=$1 want=$2 torrent=$3 out=$4
    shift 4
    n=$((n + 1))
    d="$work/af$n"
    mkdir -p "$d"
    # shellcheck disable=SC2086
    if "$aria2" $aria_common --seed-time=0 --listen-port="$(free_port)" --dir="$d" --bt-tracker="$(tracker_url "$ours_port")" "$@" "$torrent" >"$L/af$n" 2>&1 &&
       [ "$(sha "$d/$out")" = "$want" ]; then
      row PASS "Stream seed -> aria2: $name" "$(stat -f%z "$d/$out") bytes"
    else
      row FAIL "Stream seed -> aria2: $name" "$(tail -n1 "$L/af$n" | cut -c1-160)"
    fi
  }
  n=0
  aria_fetch "v1, TCP" "$film_sha" "$work/t/film-v1.torrent" film.bin
  aria_fetch "v1, MSE forced" "$film_sha" "$work/t/film-v1.torrent" film.bin --bt-force-encryption=true
  aria_fetch "hybrid (as v1)" "$film_sha" "$work/t/film-hybrid.torrent" film.bin
  aria_fetch "hybrid (as v1), MSE forced" "$film_sha" "$work/t/film-hybrid.torrent" film.bin --bt-force-encryption=true

  # aria_seed <torrent> [options]: seeds from the media directory; sets aria_address and aria_pid.
  aria_seed() {
    torrent=$1
    shift
    port=$(free_port)
    n=$((n + 1))
    # shellcheck disable=SC2086
    "$aria2" $aria_common --seed-ratio=0.0 --seed-time=600 --check-integrity=true --bt-seed-unverified=false --listen-port="$port" --dir="$work/media" "$@" "$torrent" >"$L/as$n" 2>&1 &
    aria_pid=$!
    pids="$pids $aria_pid"
    aria_address="127.0.0.1:$port"
    sleep 1
  }
  aria_seed "$work/t/film-v1.torrent"
  check "aria2 seed -> Stream: v1, TCP (MSE tried first)" "$film_sha" "$L/s1a" "$fetch" "$work/t/film-v1.torrent" "$aria_address"
  check "aria2 seed -> Stream: v1, MSE disabled" "$film_sha" "$L/s1b" env STREAM_ENCRYPTION=disabled "$fetch" "$work/t/film-v1.torrent" "$aria_address"
  check "aria2 seed -> Stream: v1 magnet (BEP 9)" "$film_sha" "$L/s1c" "$fetch" "magnet:?xt=urn:btih:$v1_hash" "$aria_address"
  stop "$aria_pid"
  aria_seed "$work/t/film-v1.torrent" --bt-force-encryption=true
  check "aria2 seed -> Stream: v1, MSE forced both ways" "$film_sha" "$L/s2" env STREAM_ENCRYPTION=forced "$fetch" "$work/t/film-v1.torrent" "$aria_address"
  check_refused "aria2 seed -> Stream: seed forces MSE, Stream refuses it" "both sides insist: nothing may be transferred" "$L/s3" \
    env STREAM_ENCRYPTION=disabled STREAM_STALL_SECONDS=8 "$fetch" "$work/t/film-v1.torrent" "$aria_address"
  stop "$aria_pid"
  aria_seed "$work/t/film-hybrid.torrent"
  check "aria2 seed -> Stream: hybrid torrent (as v1)" "$film_sha" "$L/s4" "$fetch" "$work/t/film-hybrid.torrent" "$aria_address"
  stop "$aria_pid"
  row SKIP "aria2: pure v2" "aria2 1.37 has no BEP 52"
  row SKIP "aria2: uTP" "aria2 1.37 has no uTP"
else
  row SKIP "aria2" "not found or not selected (setup-tools.sh, STREAM_INTEROP_CLIENTS)"
fi

# ---- Transmission ----
tr_daemon= tr_remote= tr_libs=
if [ -d "$tools/tr/x/transmission-cli" ]; then
  tr_daemon=$(find "$tools/tr/x/transmission-cli" -type f -name transmission-daemon | head -n1)
  tr_remote=$(find "$tools/tr/x/transmission-cli" -type f -name transmission-remote | head -n1)
  tr_libs=$(find "$tools/tr/x" -type d -name lib | tr '\n' ':')
elif command -v transmission-daemon >/dev/null 2>&1 && command -v transmission-remote >/dev/null 2>&1; then
  tr_daemon=$(command -v transmission-daemon)
  tr_remote=$(command -v transmission-remote)
fi
if wants transmission && [ -n "$tr_daemon" ]; then
  echo "$(env DYLD_FALLBACK_LIBRARY_PATH="$tr_libs" "$tr_daemon" --version 2>&1 | head -n1)"
  # tr_start <directory> <options...>: a daemon on loopback; sets tr_rpc, tr_port and tr_pid.
  n=0
  tr_start() {
    d=$1
    shift
    n=$((n + 1))
    mkdir -p "$d" "$work/trc$n"
    tr_rpc=$(free_port)
    tr_port=$(free_port)
    cat >"$work/trc$n/settings.json" <<EOF2
{"pex-enabled": false, "dht-enabled": false, "lpd-enabled": false, "port-forwarding-enabled": false,
 "blocklist-enabled": false, "rpc-bind-address": "127.0.0.1", "rpc-whitelist-enabled": true,
 "rpc-whitelist": "127.0.0.1", "rpc-authentication-required": false, "rpc-host-whitelist-enabled": false,
 "bind-address-ipv4": "127.0.0.1", "bind-address-ipv6": "::1", "default-trackers": "", "watch-dir-enabled": false,
 "incomplete-dir-enabled": false, "speed-limit-up-enabled": false, "speed-limit-down-enabled": false,
 "ratio-limit-enabled": false, "idle-seeding-limit-enabled": false, "message-level": 2}
EOF2
    # shellcheck disable=SC2086
    env DYLD_FALLBACK_LIBRARY_PATH="$tr_libs" "$tr_daemon" -f -g "$work/trc$n" -p "$tr_rpc" -P "$tr_port" -w "$d" -O -Y -M -B -T "$@" >"$L/tr$n" 2>&1 &
    tr_pid=$!
    pids="$pids $tr_pid"
    i=0
    while ! tr_cmd -l >/dev/null 2>&1; do
      i=$((i + 1))
      [ $i -gt 100 ] && return 1
      sleep 0.1
    done
  }
  tr_cmd() { env DYLD_FALLBACK_LIBRARY_PATH="$tr_libs" "$tr_remote" "127.0.0.1:$tr_rpc" "$@"; }
  # tr_wait <percent pattern> <seconds>: until the first torrent's "Percent Done" matches.
  tr_wait() {
    i=0
    while ! tr_cmd -t 1 -i 2>/dev/null | grep -q "Percent Done: $1"; do
      i=$((i + 1))
      [ $i -gt $(($2 * 5)) ] && return 1
      sleep 0.2
    done
  }
  # tr_seed <torrent> <options...>: the daemon verifies the existing file, then seeds it.
  tr_seed() {
    torrent=$1
    shift
    tr_start "$work/media" "$@" || return 1
    tr_cmd -a "$torrent" >/dev/null || return 1
    tr_wait 100% 30 || return 1
    tr_cmd -t 1 -s >/dev/null || true
    sleep 0.5
    tr_address="127.0.0.1:$tr_port"
  }
  # tr_fetch <name> <sha> <torrent> <daemon options...>: Stream's seeder dials the daemon (Transmission accepts it).
  tr_fetch() {
    name=$1 want=$2 torrent=$3
    shift 3
    d="$work/trf$n"
    if tr_start "$d" "$@" && tr_cmd -a "$torrent" >/dev/null; then
      "$fixture" seed --listen 127.0.0.1:0 --peer "127.0.0.1:$tr_port" "$torrent" "$work/media" >"$L/trs$n" 2>&1 &
      seed_pid=$!
      pids="$pids $seed_pid"
      if tr_wait 100% 45 && [ "$(sha "$d/film.bin")" = "$want" ]; then
        row PASS "Stream seed -> Transmission: $name" "$(tr_cmd -t 1 -i | grep -E 'Percent Done' | tr -s ' ')"
      else
        row FAIL "Stream seed -> Transmission: $name" "$(tr_cmd -t 1 -i 2>/dev/null | grep -E 'Percent Done|Error' | tr -s ' ' | tr '\n' ';' | cut -c1-160)"
      fi
      stop "$seed_pid"
    else
      row FAIL "Stream seed -> Transmission: $name" "the daemon did not start"
    fi
    stop "$tr_pid"
  }
  # tr_dial <name> <sha> <torrent-or-magnet> <seeder options> -- <daemon options...>: Transmission dials Stream. It drops
  # loopback peers named by a tracker or PEX (its is_martian_addr) but keeps those from its resume file, so the torrent
  # is added paused, the daemon stopped, Stream's address written into the resume file's peers2, and the daemon
  # restarted. Every piece must then arrive on the connection Transmission opened to Stream's listen port.
  tr_dial() {
    name=$1 want=$2 torrent=$3
    shift 3
    seed_opts=""
    while [ "$1" != "--" ]; do seed_opts="$seed_opts $1"; shift; done
    shift
    d="$work/trd$n"
    dial_port=$(free_port)
    if tr_start "$d" "$@" && tr_cmd --start-paused -a "$torrent" >/dev/null && sleep 0.5 && stop "$tr_pid" &&
      resume=$(find "$work/trc$n" -name '*.resume' | head -n1) && [ -n "$resume" ] &&
      python3 -I "$here/tr_resume_peer.py" "$resume" "127.0.0.1:$dial_port"; then
      # shellcheck disable=SC2086
      "$fixture" seed --listen "127.0.0.1:$dial_port" $seed_opts "$work/t/film-v1.torrent" "$work/media" "$work/t/film-hybrid.torrent" "$work/media" >"$L/trds$n" 2>&1 &
      seed_pid=$!
      pids="$pids $seed_pid"
      wait_line "$L/trds$n" "listening on"
      # The same configuration directory: the restarted daemon loads the patched resume file.
      cfg=$n
      case "$seed_opts" in *--no-utp*) transports=tcp ;; *--no-tcp*) transports=utp ;; *) transports=utp,tcp ;; esac
      settings_dial "$work/trc$cfg/settings.json" "$transports"
      env DYLD_FALLBACK_LIBRARY_PATH="$tr_libs" "$tr_daemon" -f -g "$work/trc$cfg" -p "$tr_rpc" -P "$tr_port" -w "$d" -O -Y -M -B -T "$@" >"$L/trd$cfg" 2>&1 &
      tr_pid=$!
      pids="$pids $tr_pid"
      i=0
      while ! tr_cmd -l >/dev/null 2>&1 && [ $i -lt 100 ]; do i=$((i + 1)); sleep 0.1; done
      tr_cmd -t 1 -s >/dev/null 2>&1
      if tr_wait 100% 60 && [ "$(sha "$d/film.bin")" = "$want" ]; then
        outgoing=$(grep -c "127.0.0.1:$dial_port \[[^]]*\]: got peer msg 'piece'" "$L/trd$cfg" || true)
        incoming=$(grep -E "got peer msg 'piece'" "$L/trd$cfg" | grep -vc "127.0.0.1:$dial_port " || true)
        transport=$(grep -m1 -oE "127.0.0.1:$dial_port socket \((µTP|tcp)\)|127.0.0.1:$dial_port (utp_on_state_change|is connected)" "$L/trd$cfg" | head -n1 || true)
        if [ "$outgoing" -gt 0 ] && [ "$incoming" -eq 0 ] && grep -q "127.0.0.1:$dial_port.*isIncoming is false" "$L/trd$cfg"; then
          row PASS "Transmission dials Stream: $name" "$outgoing pieces on its own connection to Stream ($transport)"
        else
          row FAIL "Transmission dials Stream: $name" "pieces outgoing=$outgoing incoming=$incoming"
        fi
      else
        row FAIL "Transmission dials Stream: $name" "$(tr_cmd -t 1 -i 2>/dev/null | grep -E 'Percent Done|Error' | tr -s ' ' | tr '\n' ';' | cut -c1-160)"
      fi
      stop "$seed_pid"
    else
      row FAIL "Transmission dials Stream: $name" "could not prepare the resume file"
    fi
    stop "$tr_pid"
  }
  # settings_dial <settings.json> <transports>: trace logging names the connection each piece arrives on, and
  # Transmission 4.1's preferred_transports picks the one the seeder offers.
  settings_dial() {
    python3 -I -c 'import json,sys; p=sys.argv[1]; s=json.load(open(p)); s["message-level"]=6
s["preferred_transports"]=sys.argv[2].split(","); json.dump(s,open(p,"w"))' "$1" "$2"
  }
  tr_dial "v1 over TCP" "$film_sha" "$work/t/film-v1.torrent" --no-utp -- -et
  tr_dial "v1 over uTP (libutp)" "$film_sha" "$work/t/film-v1.torrent" --no-tcp -- -et
  tr_dial "v1, encryption required (TCP)" "$film_sha" "$work/t/film-v1.torrent" --no-utp -- -er
  tr_dial "hybrid (as v1)" "$film_sha" "$work/t/film-hybrid.torrent" -- -et
  tr_dial "v1 magnet (BEP 9 from Stream)" "$film_sha" "magnet:?xt=urn:btih:$v1_hash" -- -et
  tr_fetch "v1, plaintext tolerated" "$film_sha" "$work/t/film-v1.torrent" -et
  tr_fetch "v1, encryption required" "$film_sha" "$work/t/film-v1.torrent" -er
  tr_fetch "hybrid (as v1)" "$film_sha" "$work/t/film-hybrid.torrent" -et
  if tr_seed "$work/t/film-v1.torrent" -et; then
    check "Transmission seed -> Stream: v1, TCP (MSE tried first)" "$film_sha" "$L/s5" "$fetch" "$work/t/film-v1.torrent" "$tr_address"
    check "Transmission seed -> Stream: v1, MSE disabled" "$film_sha" "$L/s6" env STREAM_ENCRYPTION=disabled "$fetch" "$work/t/film-v1.torrent" "$tr_address"
    check "Transmission seed -> Stream: v1, uTP only (libutp)" "$film_sha" "$L/s7" env STREAM_TCP=0 "$fetch" "$work/t/film-v1.torrent" "$tr_address"
    check "Transmission seed -> Stream: v1 magnet (BEP 9)" "$film_sha" "$L/s8" "$fetch" "magnet:?xt=urn:btih:$v1_hash" "$tr_address"
  else
    row FAIL "Transmission seed -> Stream" "the daemon did not start seeding: $(tail -n1 "$L/tr$n" | cut -c1-120)"
  fi
  stop "$tr_pid"
  if tr_seed "$work/t/film-v1.torrent" -er; then
    check "Transmission seed -> Stream: v1, MSE forced both ways" "$film_sha" "$L/s9" env STREAM_ENCRYPTION=forced "$fetch" "$work/t/film-v1.torrent" "$tr_address"
    check "Transmission seed -> Stream: v1, uTP only, MSE forced" "$film_sha" "$L/s10" env STREAM_TCP=0 STREAM_ENCRYPTION=forced "$fetch" "$work/t/film-v1.torrent" "$tr_address"
    check_refused "Transmission seed -> Stream: seed requires MSE, Stream refuses it" "both sides insist: nothing may be transferred" "$L/s11" \
      env STREAM_ENCRYPTION=disabled STREAM_STALL_SECONDS=8 "$fetch" "$work/t/film-v1.torrent" "$tr_address"
  else
    row FAIL "Transmission seed -> Stream: encryption required" "the daemon did not start seeding"
  fi
  stop "$tr_pid"
  if tr_seed "$work/t/film-hybrid.torrent" -et; then
    check "Transmission seed -> Stream: hybrid torrent (as v1)" "$film_sha" "$L/s12" "$fetch" "$work/t/film-hybrid.torrent" "$tr_address"
  else
    row FAIL "Transmission seed -> Stream: hybrid" "the daemon did not start seeding"
  fi
  stop "$tr_pid"
  row SKIP "Transmission: pure v2" "Transmission 4.1 has no BEP 52 downloads"
else
  row SKIP "Transmission" "not found or not selected (setup-tools.sh, STREAM_INTEROP_CLIENTS)"
fi

# ---- WebTorrent and an independent web server ----
modules=
[ -d "$tools/npm/node_modules" ] && modules="$tools/npm/node_modules"
if wants webtorrent && [ -n "$modules" ] && [ -d "$modules/webtorrent" ] &&
   node "$here/webtorrent_peer.mjs" "$modules" probe >"$L/webtorrent-probe" 2>&1; then
  echo "webtorrent $(node -p "require('$modules/webtorrent/package.json').version")"
  wt() { node "$here/webtorrent_peer.mjs" "$modules" "$@"; }
  check "Stream seed -> WebTorrent: v1, TCP" "$film_sha" "$L/w1" wt fetch "$work/t/film-v1.torrent" "$ours" "$work/w1"
  check "Stream seed -> WebTorrent: hybrid (as v1), TCP" "$film_sha" "$L/w2" wt fetch "$work/t/film-hybrid.torrent" "$ours" "$work/w2"
  # WebTorrent 3 has no BEP 52: its parse-torrent rejects a torrent without a v1 info dictionary. The row passes if a
  # later WebTorrent downloads the right bytes, is an expected failure only for that refusal, and fails otherwise.
  if wt fetch "$work/t/film-v2.torrent" "$ours" "$work/w2v2" >"$L/w2v2" 2>&1 && grep -q "^$film_sha " "$L/w2v2"; then
    row PASS "Stream seed -> WebTorrent: pure v2" "$(cut -d' ' -f2- "$L/w2v2")"
  elif grep -q "Invalid torrent identifier" "$L/w2v2"; then
    row EXPECTED-FAIL "Stream seed -> WebTorrent: pure v2" "WebTorrent $(node -p "require('$modules/webtorrent/package.json').version") rejects BEP 52-only torrents"
  else
    row FAIL "Stream seed -> WebTorrent: pure v2" "$(tail -n1 "$L/w2v2" | cut -c1-120)"
  fi
  mkdir -p "$work/ws"
  cp "$work/media/film.bin" "$work/ws/"
  node "$here/webtorrent_peer.mjs" "$modules" seed "$work/t/film-v1.torrent" "$work/ws" >"$L/w3s" 2>&1 &
  wt_pid=$!
  pids="$pids $wt_pid"
  if wait_line "$L/w3s" "seeding on"; then
    address=$(sed -n 's/^seeding on //p' "$L/w3s")
    check "WebTorrent seed -> Stream: v1, TCP (MSE tried first)" "$film_sha" "$L/w3" "$fetch" "$work/t/film-v1.torrent" "$address"
    check "WebTorrent seed -> Stream: v1 magnet (BEP 9)" "$film_sha" "$L/w4" "$fetch" "magnet:?xt=urn:btih:$v1_hash" "$address"
    check "WebTorrent seed -> Stream: MSE forced" "$film_sha" "$L/w5" \
      env STREAM_ENCRYPTION=forced "$fetch" "$work/t/film-v1.torrent" "$address"
    # The same seed by the hybrid's v1 half: WebTorrent serves v1 pieces, which Stream checks against both hashes' data.
    stop "$wt_pid"
    node "$here/webtorrent_peer.mjs" "$modules" seed "$work/t/film-hybrid.torrent" "$work/ws" >"$L/w6s" 2>&1 &
    wt_pid=$!
    pids="$pids $wt_pid"
    if wait_line "$L/w6s" "seeding on"; then
      check "WebTorrent seed -> Stream: hybrid (as v1)" "$film_sha" "$L/w6" "$fetch" "$work/t/film-hybrid.torrent" "$(sed -n 's/^seeding on //p' "$L/w6s")"
    else
      row FAIL "WebTorrent seed -> Stream: hybrid (as v1)" "$(tail -n1 "$L/w6s" | cut -c1-120)"
    fi
  else
    row FAIL "WebTorrent seed -> Stream" "$(tail -n1 "$L/w3s" | cut -c1-120)"
  fi
  stop "$wt_pid"
else
  row SKIP "WebTorrent" "unavailable (missing package or runtime dependency; see webtorrent-probe log)"
fi
if wants http-server && [ -n "$modules" ] && [ -f "$modules/http-server/bin/http-server" ]; then
  port=$(free_port)
  node "$modules/http-server/bin/http-server" "$work/media" -a 127.0.0.1 -p "$port" -s -c-1 >"$L/hs" 2>&1 &
  hs_pid=$!
  pids="$pids $hs_pid"
  sleep 1
  for v in v1 v2 hybrid; do
    with_webseed "$work/t/film-$v.torrent" "$work/t/film-$v-hs.torrent" "http://127.0.0.1:$port/film.bin"
    check "http-server web seed -> Stream: $v single file" "$film_sha" "$L/hs-$v" "$fetch" "$work/t/film-$v-hs.torrent" -
    with_webseed "$work/t/series-$v.torrent" "$work/t/series-$v-hs.torrent" "http://127.0.0.1:$port/"
    check "http-server web seed -> Stream: $v multi-file, middle file" "$b_sha" "$L/hsm-$v" \
      "$fetch" "$work/t/series-$v-hs.torrent" - "$(b_index $v)"
  done
  stop "$hs_pid"
else
  row SKIP "http-server web seed" "not found or not selected (setup-tools.sh, STREAM_INTEROP_CLIENTS)"
fi

echo
echo "$pass passed, $fail failed, $expected failed as expected, $skipped skipped"
[ "$fail" = 0 ]
