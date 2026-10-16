# Linux Activity Assistance

Java 21 Maven multi-module application with an optional X11 JNI tracker. Activity collection reports the foreground application's identity and window title every five seconds; it does not capture keys or application contents. A persistent monitor window shows connection state and provides a Stop tracking control.

## Requirements

- OpenJDK 21 and Maven 3.8+
- Linux with X11 development headers (`libx11-dev`) and CMake
- X11 session for active-window metadata; Wayland currently reports `UNSUPPORTED_CAPABILITY` because a supported portal integration is not available

## Build from source

```sh
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build
mvn -DargLine="-Djava.library.path=$PWD/native/build" clean verify
```

The native build is required before Maven verification because `NativeWindowTrackerTest` loads `libveridion_native.so`. Set `-Djava.library.path="$PWD/native/build"` when launching the client, or provide the full library path with `-Dveridion.native.path="$PWD/native/build/libveridion_native.so"`. See Secure RMI configuration below for required TLS and enrollment properties. From the repository root, the Java classpath launch forms are:

```sh
java -Dactivity.enrollment.file=/secure/path/enrollments.properties \
  -Djavax.net.ssl.keyStore=/secure/path/server.p12 \
  -Djavax.net.ssl.keyStorePassword="$SERVER_KEYSTORE_PASSWORD" \
  -Djavax.net.ssl.keyStoreType=PKCS12 \
  -cp server/target/server-1.0-SNAPSHOT.jar:shared/target/shared-1.0-SNAPSHOT.jar \
  com.veridion.server.ServerMain

java -Dactivity.client.id=client-01 \
  -Dactivity.enrollment.token="$CLIENT_ENROLLMENT_TOKEN" \
  -Djavax.net.ssl.trustStore=/secure/path/truststore.p12 \
  -Djavax.net.ssl.trustStorePassword="$TRUSTSTORE_PASSWORD" \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djava.library.path="$PWD/native/build" \
  -cp client/target/client-1.0-SNAPSHOT.jar:shared/target/shared-1.0-SNAPSHOT.jar \
  com.veridion.client.ActivityClient server.example.org
```

Provide the password and token variables through a secret manager or another protected mechanism rather than shell history. Configure the server certificate SAN for the host passed to the client.

## Secure RMI configuration

The server and client read the same `activity.server.port` property (default `1099`); the registry and exported service share that port. Registry and remote-object calls use TLS. Configure the server JVM with a TLS keystore and clients with a truststore containing the server certificate; use matching DNS names in the certificate SAN and `java.rmi.server.hostname`. Example JVM properties are `-Djavax.net.ssl.keyStore=/path/server.p12 -Djavax.net.ssl.keyStorePassword=... -Djavax.net.ssl.keyStoreType=PKCS12` on the server, and `-Djavax.net.ssl.trustStore=/path/truststore.p12 -Djavax.net.ssl.trustStorePassword=... -Djavax.net.ssl.trustStoreType=PKCS12` on each client. Supply passwords through a protected secret mechanism in production.

Provision one unique random token per client in a server-only Java properties file, for example `client-01=<at-least-32-character-random-token>`. Protect it with owner-only filesystem permissions. Start the server with `-Dactivity.enrollment.file=/secure/path/enrollments.properties`. Start each client with `-Dactivity.client.id=client-01 -Dactivity.enrollment.token=<that-client-token>`. The server stores token hashes, checks ID/token pairs at enrollment, and issues a separate session credential for subsequent calls. Re-enrollment rotates that credential. Do not expose RMI ports without TLS and restricted firewall rules.

The client uses `activity.rmi.connect-timeout-ms` and `activity.rmi.read-timeout-ms` (defaults 5000 and 10000) for bounded RMI calls. It retries using exponential delays from 1 to 60 seconds. The consent endpoint in `remote-assistance` requires `-Dactivity.assistance.operator-token=<protected-32-character-token>` and TLS RMI transport. It contains screen capture and input methods, but it is not exported through the activity server registry or connected to a server-side operator workflow. Treat those methods as an unintegrated prototype; do not expose them as a production remote-assistance service.
