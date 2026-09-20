# packet-inspector

Decodes the cleartext 40-byte video packet header and validates control-message JSON.

```sh
cd tools && ./gradlew :packet-inspector:run --args="header 524d0101..."
./gradlew :packet-inspector:run --args="control ../protocol/examples/viewport_set.json"
```

Payloads are AEAD-encrypted on the wire and the tool has no access to session keys by design.
