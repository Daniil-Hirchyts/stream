#!/bin/sh
# Loopback interoperability between Stream's engine and independent implementations. Everything runs on 127.0.0.1
# with generated content: no trackers, DHT, local discovery, port mapping, LAN peers or internet.
#
#   native/eval/interop.sh            builds what it needs, prints one line per check and a summary
#
# Independent peers: librqbit 9.1.0 (native/eval/rqbit, src/bin/interop.rs), when it builds; it shares the uTP
# library (librqbit-utp 0.7.0) with the engine, so its uTP rows are not independent at that layer. Independent web
# seeds: Node's http/https (webseed.mjs). HTTPS uses a CA generated here and given to the engine as an explicit trust
# root (STREAM_CA_FILE); verification is never disabled. Needs cargo, node, openssl, python3 and shasum.
# CARGO_OFFLINE=1 builds the rqbit client from the local Cargo cache only.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
native="$here/.."
work=$(mktemp -d "${TMPDIR:-/tmp}/stream-interop.XXXXXX")
pids=""
cleanup() {
  for p in $pids; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT INT TERM

offline=
[ "${CARGO_OFFLINE:-0}" = 1 ] && offline=--offline
echo "Building stream-fixture and the fetch example..."
(cd "$native/core" && cargo build --locked --release --bin stream-fixture --example fetch >/dev/null 2>&1)
fixture="$native/core/target/release/stream-fixture"
fetch="$native/core/target/release/examples/fetch"
rqbit=
if (cd "$here/rqbit" && cargo build --locked --release $offline --bin interop >/dev/null 2>&1); then
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
  printf '%-14s %-58s %s\n' "$1" "$2" "$3"
}
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
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
    [ -n "${STREAM_INTEROP_VERBOSE:-}" ] && tail -n 20 "$log.err" >&2
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
  "$work/t/film-hybrid.torrent" "$work/media" "$work/t/series-hybrid.torrent" "$work/media" >"$work/logs/seeder.txt" 2>&1 &
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

echo
echo "$pass passed, $fail failed, $expected failed as expected, $skipped skipped"
[ "$fail" = 0 ]
