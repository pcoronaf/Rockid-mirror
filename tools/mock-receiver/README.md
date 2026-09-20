# mock-receiver

Desktop stand-in for the glasses so the Android sender can be exercised end to end (discovery,
pairing, encryption, streaming, reconnection) with no Rokid hardware.

```sh
cd tools && ./gradlew :mock-receiver:run --args="--name 'Desk mock' --dump out.h264"
# in another terminal, once frames arrive:
ffplay -fflags nobuffer -flags low_delay -f h264 out.h264
```

It advertises `_rokidmirror._tcp` via mDNS (JmDNS), prints the pairing code to the console, and
reports fps / bitrate / loss once a second. Credentials are kept in memory only, so every
restart requires pairing again (use it to test the "Forget receiver" path on the phone too).
