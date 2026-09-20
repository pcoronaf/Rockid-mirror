# Latency marker (optical end-to-end measurement)

The optical test measures true glass-to-glass latency, independent of any software timestamp.

1. On the phone open **Rokid Mirror → Marker**. The screen shows a frame counter, a monotonic
   millisecond clock and a square that flips black/white every display frame.
2. Start mirroring the phone (Mirror phone) so the glasses show the same screen.
3. Film phone display and glasses display **in one shot** with a 240 fps camera
   (most flagship phones: slow-motion mode). Hold the glasses so the waveguide is visible.
4. Step through the recording. For a given camera frame read the counter on the phone and on
   the glasses. `latency_ms = (phone_counter - glasses_counter) * 1000 / phone_display_fps`
   (Galaxy S25 at 120 Hz: multiply the frame difference by 8.33 ms).
5. Collect at least 30 samples across one minute and report min / median / p95, not a single
   best result (docs/latency-testing.md).

The synthetic source (Test pattern) draws the same marker into the encoder directly and can be
used to measure the pipeline without the phone display in the loop.
