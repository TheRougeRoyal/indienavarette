# Final Engineering Audit: Linux Activity Tracking and Remote Assistance

Audit performed 2026-10-17 against the repository contents and command output recorded below. This is an engineering review, not a penetration test, certification, or production security assessment.

## 1. Executive summary

The repository contains a Java 21 Maven multi-module activity tracker, TLS-protected RMI activity service, X11 JNI tracker, Swing administrator dashboard, and a separate remote-assistance endpoint prototype. The normal activity collection flow has visible user consent and a stop control. The endpoint has distinct screen-view and input-control consent prompts, but it is not registered by `ServerMain` or connected to an operator workflow.

**Release recommendation: Not ready for release.** The remote-input DTO does not enforce most of its documented safety properties: sequence numbers are unused, coordinate limits are not checked against the display, buttons/key codes/scroll amounts are unchecked, and held inputs are not released when a session ends. Operator credentials are one shared static secret rather than an authenticated operator identity; sessions have no local expiry. Those gaps preclude safe use of the assistance endpoint. The dashboard's CSV writer also lacks a fixed encoding and RFC 4180 CRLF record endings, and neither it nor the desktop UI has automated behavior tests.

## 2. System architecture and module responsibilities

- `shared`: RMI interfaces, event and command DTOs, port configuration, and a TLS RMI client socket factory.
- `server`: activity enrollment/authentication, bounded per-client logs, heartbeat status, RMI registry startup, and Swing dashboard.
- `client`: enrollment/retry loop, visible collection-consent monitor, X11/Wayland tracker selection, and activity reporting.
- `remote-assistance`: exported `ConsentClientHandler` with consent state, screen capture and Robot input methods. It has no application entry point or registry binding in the server.
- `native`: C/JNI library `libveridion_native.so`, using Xlib/EWMH to query active-window metadata.

The root POM sets Java release 21 and builds five reactor projects (root plus four modules). Artifacts are `shared`, `server`, `client`, and `remote-assistance` version `1.0-SNAPSHOT`; the JARs are not shaded or executable bundles.

## 3. Implemented functionality by phase

Phases could not be independently mapped to source because phase definitions are not in this repository. Status below refers to observable features:

- **Implemented and verified**: Java compilation; enrollment and credential rotation; TLS client-certificate trust and hostname checks in local TLS tests; activity event storage limit; X11 metadata query on this audit host; Wayland explicit unsupported result; consent-state unit tests.
- **Implemented but only partially verified**: visible activity-consent monitor; dashboard refresh/search/export; consent-gated screen capture; Robot input path. Dashboard behavior is not covered by tests. Screen capture and input were not validated against a controlled real desktop scenario.
- **Incomplete**: secure, end-to-end remote-assistance workflow; robust input protocol validation, replay protection, input cleanup, and session expiry; operator-specific authorization; CSV portability and spreadsheet-safe export.
- **Unsupported by design**: Wayland active-window metadata capture (no portal integration), headless desktop capture/control, arbitrary command execution, and access to compositor-protected content.

## 4. Authentication and authorization model

`ActivityServerImpl` reads a Java properties enrollment file selected by `activity.enrollment.file`, hashes per-client tokens with SHA-256, compares hashes using `MessageDigest.isEqual`, and issues a random 32-byte session credential on enrollment. Re-registering the same client ID replaces its credential. Activity and heartbeat calls check that credential using constant-time byte comparison.

RMI registry and exported objects use JSSE TLS sockets. The client socket factory uses the default trust managers and sets endpoint identification to `HTTPS`; it does not disable certificate or hostname verification. The server socket factories do not require mutual TLS, so client authentication is application-token based. Correct truststore, keystore, SAN, host name, secret file permissions and firewall configuration remain operator responsibilities. The source contains no trust-all TLS implementation or production literal credential; tests contain intentionally fixed test secrets.

Session credentials are not independently expired or rotated on a timer. Heartbeat expiry only changes the dashboard's online indication; it does not revoke a credential. Enrollment tokens remain valid for re-enrollment until the server's properties file is changed. The assistance endpoint has a single operator-token hash and no operator identity, per-user role, or per-session expiry. A leaked operator token grants the same authority to any caller that can reach the endpoint.

## 5. Consent state machine and permission boundaries

`ConsentClientHandler` transitions through `IDLE -> REQUESTING_CONSENT -> ACTIVE_VIEW`, then to `ACTIVE_CONTROL` after a second prompt. Failed, closed, or unavailable dialogs deny consent. Screen capture is allowed in either active state; input requires `ACTIVE_CONTROL`. Input consent is separate from view consent and defaults to denied.

The session ID is a random UUID. The same configured operator token is checked on each endpoint call, but the ID is not bound to a distinct authenticated operator or client-side session generation. Termination clears the active ID. A pending initial consent request has no session ID yet, so it cannot be canceled through `terminateSession`; its requester must wait for the local prompt to resolve. There is no local session expiry, revocation UI, shutdown hook for the exported object, or reconnect integration. Reconnecting the activity client does not automatically restore assistance, but no assistance reconnect lifecycle exists to verify.

Capture, control approval, input, and termination synchronize on the handler. Consequently, a termination call waits while a Robot command already inside the synchronized method completes; after termination returns, subsequent commands fail the state/session check. This provides serialized revocation, not cancellation of an in-flight OS input. The input-consent dialog also holds the handler monitor while waiting, so termination cannot complete until the local user answers or closes it.

## 6. Remote-input protocol and validation

`RemoteInputCommand` is a typed serializable record with command type, coordinates, button, key code, scroll amount and `sequenceNumber`. Current `validate()` checks only non-null type and non-negative coordinates for pointer and button commands. The handler does not read `sequenceNumber`; repeated or stale commands are therefore not rejected. No request-size/rate/queue bound is needed for a queue (there is no queue), but there is also no rate limiter. No screen-bound, button-mask, key-code or scroll-range validation exists. Each command creates a `Robot`; keys/buttons pressed by the application are not tracked or released on termination. Robot availability and permissions depend on the user's graphical session and operating system.

These are release-blocking safety limitations. Automated input tests use injected consent decisions, but any path reaching `Robot` is an OS-level side effect; this audit did not claim a controlled real-desktop input acceptance test.

## 7. Linux desktop compatibility

On X11 the native code reads `_NET_ACTIVE_WINDOW`, `_NET_WM_NAME` with `XFetchName` fallback, and `WM_CLASS`; it returns application and title strings through JNI. It opens/closes a display per call and frees Xlib property buffers and JNI local references. UTF-8 conversion replaces malformed sequences, but source truncation can cut a multibyte sequence. The `WM_CLASS` parser's loop stops at the first NUL and therefore selects the first string (instance) rather than the second class string despite the report's WM_CLASS claim. It does not return window contents or keystrokes.

Wayland sessions return `UNSUPPORTED_CAPABILITY` without attempting an X11/compositor bypass. Missing JNI library is caught by tracker selection and returns an `UNAVAILABLE` result; direct `NativeWindowTracker` construction fails at class initialization if unavailable. Headless tracking client startup exits with a clear message. Native CMake configure and build succeeded on this Linux/JDK 21 host. A tracker test reported `SUPPORTED` and an active application, so X11/JNI querying was exercised in the current graphical session; this does not establish compatibility across desktop environments.

## 8. Administrator dashboard

Dashboard construction and timer callbacks are started on the Swing Event Dispatch Thread, and button actions run on that thread. Its five-second timer refreshes clients and logs using in-memory snapshots. The server returns an immutable copied log map and bounds each client's retained history at 5,000 events.

Refresh, log filtering and CSV writing run on the EDT. Large histories or slow file I/O can freeze the UI. CSV fields are quoted and embedded quotes doubled, so commas, quotes, CR/LF and Unicode remain representable in fields, but `PrintWriter(File)` uses the platform default charset, records use platform line endings rather than mandated CRLF, and `PrintWriter` can suppress write errors unless checked. Spreadsheet formula interpretation is not mitigated. There are no dashboard tests. `EXIT_ON_CLOSE` ends the process but there is no explicit registry/unexport cleanup path.

## 9. Build and test results

Environment: Linux x86_64, kernel `7.0.0-38-generic`, OpenJDK `21.0.12.1` (Ubuntu), Maven `3.8.7`, CMake `3.28.3`. The host exposed a graphical X11 session. Build output confirms `native/build/libveridion_native.so`.

Commands executed:

```text
mvn clean verify
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build
mvn -Djava.library.path="$PWD/native/build" clean verify
mvn -DargLine='-Djava.library.path=/home/aakashraj/Documents/github-aakash/indienavarette/native/build' clean verify
git diff --check
```

The first plain Maven run built shared/server and ran 21 tests, then failed because the forked test JVM could not load `veridion_native`. Passing `-Djava.library.path` as a Maven system property did not affect the forked JVM; Surefire emitted a warning. A subsequent attempt inside the sandbox could not bind sockets for TLS tests. TLS tests therefore ran in the explicitly approved unrestricted Maven invocation.

The unrestricted run after building native and injecting a deterministic input-consent prompt is authoritative: **BUILD SUCCESS**. Test results: shared 3, server 12, client 5, remote-assistance 9; total **29 tests, 0 failures, 0 errors, 0 skipped**. The previous test stall was fixed by making the input-consent prompt injectable for tests while retaining the visible Swing dialog in the public production constructor. A regression test verifies that denying the separate input prompt leaves a session view-only.

The suite includes unit tests, mocked client-connector behavior, local TLS socket integration tests, direct server-object load simulation, and a JNI/X11 tracker smoke test. The load test uses 100 simulated clients, 20 worker threads, and 10,000 direct in-process event submissions (no RMI/network); audit runs reported 45 ms (222,222 events/s), 47 ms (212,766 events/s), and 36 ms (277,778 events/s). Timing is wall clock around enrollment plus event processing, with no latency percentiles and no controlled hardware/JVM benchmark methodology. These are transient local micro-test measurements, not a WAN or end-to-end throughput claim. The remote-input unit path called `Robot.mouseMove(100,100)` on the current graphical session, but did not independently observe or assert pointer movement; no controlled automated or manual Robot acceptance test was performed.

## 10. Performance methodology and measured results

Only `server/src/test/java/com/veridion/server/LoadTest.java` produces performance figures. It starts a server object on an ephemeral export port, enrolls 100 clients from a 20-thread executor, then directly invokes the in-process server 100 times per client. The timer covers those enrollment and event calls, with `LongAdder` counting only successful event submissions. It reports total events divided by elapsed wall-clock seconds. It does not use RMI stubs for event reporting, a real network, warmup/repetition, percentile latency, or a specified hardware profile beyond this audit environment above. Current audit observations were 222,222, 212,766 and 277,778 events/s in separate runs; do not use them as stable capacity guarantees.

## 11. Deployment and configuration instructions

Clean checkout build (from repository root):

```sh
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build
mvn -DargLine="-Djava.library.path=$PWD/native/build" clean verify
```

The server is launched with both `server/target/server-1.0-SNAPSHOT.jar` and `shared/target/shared-1.0-SNAPSHOT.jar` on its classpath. Configure `activity.enrollment.file`, `activity.server.port` (default 1099), `java.rmi.server.hostname`, JSSE keystore path/password/type, and a certificate SAN matching the advertised host. Create a protected `clientId=token` properties file with distinct high-entropy tokens of at least 32 characters.

The client classpath is `client/target/client-1.0-SNAPSHOT.jar` plus `shared/target/shared-1.0-SNAPSHOT.jar`; provide the client ID, its matching enrollment token, server host/port, JSSE truststore and native library path. Run `com.veridion.client.ActivityClient [server-host]` after starting the server. The client asks the local user for activity collection consent and offers a visible stop control.

The `remote-assistance` artifact has no operational main/registry binding; compiling it does not deploy an assistance service. TLS keystore/truststore passwords must come from a protected deployment secret mechanism, not command history. Shut down the server process to stop the registry; graceful unexport/cleanup is not implemented. Restrict network access to the configured RMI port and protect enrollment files and keystore material.

## 12. Security limitations and residual risks

- High: input validation, replay rejection, input release cleanup, rate controls, and session expiry are absent.
- High: remote assistance is not integrated into the server workflow; the shared operator secret cannot identify or scope an operator.
- High: exported assistance endpoint's TLS server socket does not require client certificates; authorization depends on possession of the shared token.
- Medium: client enrollment/session tokens are delivered as JVM system properties and may be exposed in process listings or operational diagnostics if mishandled.
- Medium: dashboard CSV is not fixed to UTF-8/CRLF and does not mitigate spreadsheet formula injection; UI file work runs on the EDT.
- Medium: no automated dashboard tests, no controlled screen capture test, and no deterministic test of revocation during an in-flight native input action.
- Medium: the X11 parser chooses WM_CLASS instance data and UTF-8 truncation can produce replacement characters.
- Operational: no certificate issuance/rotation automation, no durable activity store, and no authenticated dashboard user model.

No trust-all TLS setup or hardcoded production secret was found in the inspected implementation. This source review and automated test evidence do not establish absence of all vulnerabilities.

## 13. Known unsupported features

Wayland active-window tracking, headless Robot operation, a server/operator remote-assistance workflow, per-operator identities/roles, durable logs, dashboard authentication, certificate lifecycle automation, remote clipboard/file transfer, and WAN performance validation are unsupported or absent.

## 14. Reproducibility instructions

Use Java 21, Maven 3.8+, CMake, JNI headers, and X11 development libraries. From a clean checkout, run the three commands in Section 11 in order. Maven must receive the native library path in Surefire's `argLine`; the native test otherwise fails to load the JNI library. TLS tests open local sockets and require an environment that permits loopback binding. X11 test results depend on the current user's display/session and only validate metadata retrieval on that host.

## 15. Final release-readiness assessment

**Not ready for release.** The clean Java/native builds can establish compilation and local testability, but the remote-input path lacks the validation and lifecycle safeguards necessary for controlled use, its operator authorization is shared-secret based, and it is not integrated into a deployable workflow. Dashboard export and responsiveness gaps and lack of GUI test coverage also remain. Limit any further use to isolated development testing after reviewing the outstanding findings; do not deploy the assistance endpoint to user desktops.
