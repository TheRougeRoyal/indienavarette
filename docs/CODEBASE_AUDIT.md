# Codebase audit

Audited source, Maven and CMake build files, and tracked/generated tree on 2026-10-15. Generated `target/` JARs and `native/build/` objects were present and are not valid evidence of a source build.

| Severity | Affected files | Finding | Proposed fix / status |
|---|---|---|---|
| Critical | `native/src/window_tracker.c` | `_NET_ACTIVE_WINDOW` was fetched with an incompatible pointer and no type/format validation; X properties could be misread. X11 title retrieval did not honor UTF-8 and identity was constant. | Validate `XA_WINDOW`/format 32, read `_NET_WM_NAME` as UTF8, fallback to `XFetchName`, query `WM_CLASS`, release X allocations. Implemented. |
| High | `client/src/main/java/com/veridion/client/ActivityClient.java` | Hard-coded developer home path and setting `java.library.path` at runtime (ineffective after JVM startup). | Remove path, support standard library path or explicit `veridion.native.path`; document build. Implemented. |
| High | `server/.../ActivityServerImpl.java` | Unvalidated nulls, unregistered reports dereferenced absent map, duplicate timestamp overwrote events, logs unbounded. | Validate requests, reject unknown IDs, preserve collisions with unique map keys and cap at 5,000 events/client. Implemented. |
| High | RMI interfaces and server | Initial service had no authentication, authorization, or encryption. | Require provisioned per-client enrollment tokens, issue session credentials, validate every activity/heartbeat, and use TLS RMI sockets. Registry/object ports and connect/read timeouts are configurable. TLS keystore/truststore and firewall configuration remain deployment requirements. |
| Medium | `ActivityServerImpl.ClientSession` | Heartbeat timestamp was not safely published and stale clients never expired. | Volatile timestamp and 30-second online/expired state in dashboard. Implemented. |
| Medium | `DashboardUI.java` | Dashboard model updates were manual; mutable log map escaped server and Swing updates could be called off EDT. | Return snapshot map and poll on Swing Timer (EDT). Implemented. |
| Medium | `client/.../NativeWindowTracker.java`, `native/CMakeLists.txt` | JNI loading failed opaquely and native build configuration was incomplete/platform-dependent. | Explicit path override, graceful capability result, CMake JNI/X11 include/link configuration. Implemented; compile verified separately. |
| Medium | Client desktop integration | Wayland was not detected and X11-specific JNI could not safely provide Wayland active-window data. | Introduce `DesktopTracker` boundary and explicit `UNSUPPORTED_CAPABILITY`; no security bypass. Portal-backed metadata remains future work. |
| Medium | `remote-assistance/` | Module initially had no implementation. | Added a TLS-exported, operator-token-authorized consent lifecycle with active-session ID and termination. No server-side session workflow, screen sharing, or remote control is implemented. |
| Low | Root and module POMs | Compiler release and test runner were not centrally pinned; no source tests exist. | Pin compiler release 21 and Surefire; retain reactor module structure. Implemented. |
| Low | Repository tree | Generated JARs, CMake cache/binaries, IDE files and logs were not ignored. README absent. | Add `.gitignore` and clean-source instructions. Implemented. |

## Remaining limitations

No global keyboard capture, credential collection, or content extraction is present or proposed. The current implementation records window titles, which can themselves contain sensitive user-visible metadata; deployment should obtain informed user consent and protect server storage. Wayland portals do not currently provide a portable generic active-window title interface, so the client returns an explicit unsupported state rather than trying to evade compositor restrictions. TLS runtime handshake and graphical interaction require configured certificates and a graphical session; Maven tests verify service logic and lifecycle behavior but do not simulate those environments.
