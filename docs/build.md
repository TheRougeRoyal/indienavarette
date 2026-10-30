# Build guide

## Requirements

- OpenJDK 21 and Maven 3.8+
- Linux with X11 development headers (`libx11-dev`) and CMake
- An X11 session for active-window metadata. Wayland currently reports `UNSUPPORTED_CAPABILITY` because a supported portal integration is not available.

## Build from source

From the repository root:

```sh
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build
mvn -DargLine="-Djava.library.path=$PWD/native/build" clean verify
```

The native build is required before Maven verification because `NativeWindowTrackerTest` loads `libveridion_native.so`.

The native client bundle is platform-specific: `libveridion_native.so` is Linux-only and requires an X11 desktop session. Windows and macOS need separate JNI builds. A headless client exits without collecting activity.

## Launch the client

Set `-Djava.library.path="$PWD/native/build"` when launching the client, or provide the full library path with `-Dveridion.native.path="$PWD/native/build/libveridion_native.so"`. See the [security and configuration guide](security.md) for the required TLS and enrollment properties.

The host can be supplied as the first positional argument or through `-Dactivity.server.host=...`. The positional argument takes precedence when both are present; the property defaults to `localhost` when neither is supplied. From the repository root:

```sh
java -Dactivity.client.id=client-01 \
  -Dactivity.enrollment.token="$CLIENT_ENROLLMENT_TOKEN" \
  -Djavax.net.ssl.trustStore=/secure/path/truststore.p12 \
  -Djavax.net.ssl.trustStorePassword="$TRUSTSTORE_PASSWORD" \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djava.library.path="$PWD/native/build" \
  -cp client/target/client-1.0-SNAPSHOT.jar:shared/target/shared-1.0-SNAPSHOT.jar \
  com.veridion.client.ActivityClient server.example.org
```

Provide password and token variables through a secret manager or another protected mechanism rather than shell history. Configure the server certificate SAN for the host passed to the client.
