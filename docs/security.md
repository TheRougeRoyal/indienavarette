# Security and configuration guide

## Secure RMI

The server and client read the same `activity.server.port` property (default `1099`); the registry and exported service share that port. Registry and remote-object calls use TLS. Configure the server JVM with a TLS keystore and clients with a truststore containing the server certificate. Use matching DNS names in the certificate SAN and `java.rmi.server.hostname`.

Example server TLS properties are `-Djavax.net.ssl.keyStore=/path/server.p12 -Djavax.net.ssl.keyStorePassword=... -Djavax.net.ssl.keyStoreType=PKCS12`. Client properties are `-Djavax.net.ssl.trustStore=/path/truststore.p12 -Djavax.net.ssl.trustStorePassword=... -Djavax.net.ssl.trustStoreType=PKCS12`. Supply passwords through a protected secret mechanism in production.

The server requires `activity.server.advertised-host`, or the standard `java.rmi.server.hostname`, so its RMI stub advertises a reachable address. The client accepts the server host as its first positional argument or via `activity.server.host`; the positional argument takes precedence, and the property defaults to `localhost`.

## Client enrollment and session credentials

Provision one unique random token per client in a server-only Java properties file, for example `client-01=<at-least-32-character-random-token>`. Protect it with owner-only filesystem permissions. Start the server with `-Dactivity.enrollment.file=/secure/path/enrollments.properties`. Start each client with `-Dactivity.client.id=client-01 -Dactivity.enrollment.token=<that-client-token>`. The server stores token hashes, checks ID/token pairs at enrollment, and issues a separate session credential for subsequent calls. Re-enrollment rotates that credential. Do not expose RMI ports without TLS and restricted firewall rules.

The client registers its local hostname. If hostname lookup fails, `activity.client.hostname` supplies the fallback name; otherwise it uses `unknown`.

The client uses `activity.rmi.connect-timeout-ms` and `activity.rmi.read-timeout-ms` (defaults 5000 and 10000) for bounded RMI calls. It retries using exponential delays from 1 to 60 seconds.

## Serialization filter

Before creating the registry, the server installs a bounded serialization filter. Its application/JDK allowlist contains `ActivityEvent`, `String`, and the `java.time.Ser` serialization proxy used by `LocalDateTime`; Java RMI transport classes are also permitted. The filter limits depth to 20, references to 10,000, arrays to 10,000 elements, and serialized input to 1 MiB. Add future serialized API types deliberately and test that unlisted types remain rejected.

## Remote-assistance prototype warning

The consent endpoint in `remote-assistance` requires `-Dactivity.assistance.operator-token=<protected-32-character-token>` and TLS RMI transport. The module contains screen capture and input methods, but it is not exported through the activity server registry or connected to a server-side operator workflow. Treat those methods as an unintegrated prototype; do not expose them as a production remote-assistance service. It is not integrated into the activity server or client.
