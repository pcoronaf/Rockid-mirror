# stream-generator

JVM tool that replays an Annex-B H.264 file to a receiver over the real Rokid Mirror protocol
(pairing, encryption, fragmentation). Use it for Task D / M0: bring the glasses receiver up
and soak it for 10 minutes with a known 480p30 source before any phone is involved.

```sh
ffmpeg -f lavfi -i testsrc2=size=848x480:rate=30 -t 60 -c:v libx264 -profile:v main \
       -tune zerolatency -x264-params keyint=30:bframes=0 -f h264 test480p30.h264
cd tools && ./gradlew :stream-generator:run --args="--host 192.168.1.50 --file ../test480p30.h264 --fps 30 --width 848 --height 480 --loop"
```

Type the pairing code shown on the glasses when prompted. The tool prints the receiver's STATS
(fps, loss, reassembly/decode/render ms) every second, which is what the M0 report needs.

For a synthetic source on the phone itself (colour bars, moving grid, frame counter, timestamp,
alternating black/white marker) use **Test pattern** in the sender app; it drives the real
MediaCodec encoder without MediaProjection consent.
