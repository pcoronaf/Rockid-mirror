#!/usr/bin/env bash
# End-to-end loopback test of the wire protocol without any device:
# stream-generator (sender role) -> mock-receiver (receiver role) over 127.0.0.1, including
# the pairing-code handshake, AEAD encryption, fragmentation/reassembly and STATS.
# Usage: tools/e2e-loopback.sh [seconds]   (default 8). Exit code 0 = frames flowed.
set -euo pipefail
cd "$(dirname "$0")"
DURATION="${1:-8}"
# Pick a free port pair so repeated or parallel runs never collide.
PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("",0));print(s.getsockname()[1]);s.close()')"
VIDEO_PORT="$((PORT + 1))"
OUT="$(mktemp -d)"
trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$OUT"' EXIT

# A structurally valid Annex-B stream is enough for the transport (the mock does not decode):
python3 - "$OUT/test.h264" <<'PY'
import sys, random
random.seed(1)
def nal(t, body): return b'\x00\x00\x00\x01' + bytes([t]) + body
def payload(n): return bytes(random.choice(range(0x10, 0xFF)) for _ in range(n))
out = b''
for f in range(300):            # 10 s at 30 fps
    if f % 30 == 0:
        out += nal(0x67, b'\x42\xc0\x1e' + payload(20)) + nal(0x68, b'\xce' + payload(4))
        out += nal(0x65, b'\x88' + payload(12000))   # IDR, first_mb_in_slice = 0
    else:
        out += nal(0x41, b'\x9a' + payload(2500))    # P slice, first_mb_in_slice = 0
open(sys.argv[1], 'wb').write(out)
PY

GRADLE_OPTS="${GRADLE_OPTS:-}"
./gradlew -q --no-daemon :mock-receiver:installDist :stream-generator:installDist >/dev/null
mock-receiver/build/install/mock-receiver/bin/mock-receiver --no-mdns --port "$PORT" --video-port "$VIDEO_PORT" --dump "$OUT/out.h264" > "$OUT/receiver.log" 2>&1 &
sleep 2
mkfifo "$OUT/stdin"
stream-generator/build/install/stream-generator/bin/stream-generator --host 127.0.0.1 --port "$PORT" --file "$OUT/test.h264" --fps 30 --loop < "$OUT/stdin" > "$OUT/sender.log" 2>&1 &
exec 3>"$OUT/stdin"
for _ in $(seq 1 50); do
  CODE=$(grep -o 'PAIRING CODE: [0-9 ]*' "$OUT/receiver.log" | head -1 | sed 's/PAIRING CODE: //; s/ //g' || true)
  [ -n "${CODE:-}" ] && break
  sleep 0.2
done
[ -n "${CODE:-}" ] || { echo "no pairing code shown"; cat "$OUT/receiver.log"; exit 1; }
echo "$CODE" >&3
sleep "$DURATION"
echo "--- receiver ---"; tail -n 6 "$OUT/receiver.log"
echo "--- sender ---";   tail -n 4 "$OUT/sender.log"
grep -q "authenticated" "$OUT/receiver.log" || { echo "FAIL: handshake did not complete"; exit 1; }
FRAMES=$(grep -o 'frames [0-9]*' "$OUT/receiver.log" | tail -1 | awk '{print $2}')
[ "${FRAMES:-0}" -gt 100 ] || { echo "FAIL: only ${FRAMES:-0} frames delivered"; exit 1; }
cmp -s <(head -c 20000 "$OUT/out.h264") <(python3 -c "
import sys
d=open('$OUT/test.h264','rb').read(); sys.stdout.buffer.write(d[:20000])") || { echo "FAIL: dumped stream differs from source"; exit 1; }
echo "PASS: $FRAMES access units delivered, dump matches source bytes"
