# Deployment guide

## Server launch

Configure the TLS keystore and enrollment file as described in the [security and configuration guide](security.md). The server requires `activity.server.advertised-host` (or the standard `java.rmi.server.hostname`) so the exported RMI stub advertises an address clients can reach. From the repository root, a headless server can be started with:

```sh
java -Dactivity.enrollment.file=/secure/path/enrollments.properties \
  -Dactivity.server.advertised-host=server.example.org \
  -Djavax.net.ssl.keyStore=/secure/path/server.p12 \
  -Djavax.net.ssl.keyStorePassword="$SERVER_KEYSTORE_PASSWORD" \
  -Djavax.net.ssl.keyStoreType=PKCS12 \
  -cp server/target/server-1.0-SNAPSHOT.jar:shared/target/shared-1.0-SNAPSHOT.jar \
  com.veridion.server.ServerMain --no-dashboard
```

The registry and service share the configured port, `1099` by default. `ServerMain --no-dashboard` or `-Dactivity.dashboard.disabled=true` keeps the server headless. The Swing dashboard requires a graphical session and is not a browser dashboard.

## systemd template

`deploy/veridion.service` is a headless systemd unit template. Install the server and shared JARs as `/opt/veridion/server.jar` and `/opt/veridion/shared.jar`. Create an `EnvironmentFile` at `/etc/veridion/server.env` containing `ADVERTISED_HOST`, `ENROLLMENT_FILE`, `KEYSTORE_PATH`, and `KEYSTORE_PASSWORD`, and grant the `veridion` service user read access to the enrollment and keystore files. Keep that environment file owner-readable only.

After installing the unit, run `systemctl daemon-reload` and enable/start `veridion.service`. Restrict the RMI firewall rule to the private VPN interface/network; the service uses TCP 1099 by default. Prefer WireGuard or Tailscale for trusted client networks.
