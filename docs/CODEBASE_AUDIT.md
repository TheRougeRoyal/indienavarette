# Codebase audit

Audited source, Maven and CMake build files, and tracked/generated tree on 2026-10-15. Generated `target/` JARs and `native/build/` objects were present and are not valid evidence of a source build.

| Severity | Affected files | Finding | Proposed fix / status |
|---|---|---|---|
| Critical | `native/src/window_tracker.c` | `_NET_ACTIVE_WINDOW` was fetched with an incompatible pointer and no type/format validation; X properties could be misread. X11 title retrieval did not honor UTF-8 and identity was constant. | Validate `XA_WINDOW`/format 32, read `_NET_WM_NAME` as UTF8, fallback to `XFetchName`, query `WM_CLASS`, release X allocations. Implemented. |
| High | `client/src/main/java/com/veridion/client/ActivityClient.java` | Hard-coded developer home path and setting `java.library.path` at runtime (ineffective after JVM startup). | Remove path, support standard library path or explicit `veridion.native.path`; document build. Implemented. |
| High | `server/.../ActivityServerImpl.java` | Unvalidated nulls, unregistered reports dereferenced absent map, duplicate timestamp overwrote events, logs unbounded. | Validate requests, reject unknown IDs, preserve collisions with unique map keys and cap at 5,000 events/client. Implemented. |
| High | RMI interfaces and server | No authentication, authorization, encryption, or advertised host configuration; exposed registry defaults may fail remotely or expose metadata. | Configurable RMI advertised host/port added. Authentication and TLS remain required before untrusted-network deployment. |
| Medium | `ActivityServerImpl.ClientSession` | Heartbeat timestamp was not safely published and stale clients never expired. | Volatile timestamp and 30-second online/expired state in dashboard. Implemented. |
| Medium | `DashboardUI.java` | Dashboard model updates were manual; mutable log map escaped server and Swing updates could be called off EDT. | Return snapshot map and poll on Swing Timer (EDT). Implemented. |
| Medium | `client/.../NativeWindowTracker.java`, `native/CMakeLists.txt` | JNI loading failed opaquely and native build configuration was incomplete/platform-dependent. | Explicit path override, graceful capability result, CMake JNI/X11 include/link configuration. Implemented; compile verified separately. |
| Medium | Client desktop integration | Wayland was not detected and X11-specific JNI could not safely provide Wayland active-window data. | Introduce `DesktopTracker` boundary and explicit `UNSUPPORTED_CAPABILITY`; no security bypass. Portal-backed metadata remains future work. |
| Medium | `remote-assistance/` | Module contains only a POM; `IClientHandler` has no implementation, lifecycle, or transport integration. | Add explicit assistance-session implementation after policy and consent protocol are defined. Not implemented because no protocol or UI contract exists. |
| Low | Root and module POMs | Compiler release and test runner were not centrally pinned; no source tests exist. | Pin compiler release 21 and Surefire; retain reactor module structure. Implemented. |
| Low | Repository tree | Generated JARs, CMake cache/binaries, IDE files and logs were not ignored. README absent. | Add `.gitignore` and clean-source instructions. Implemented. |

## Remaining limitations

No global keyboard capture, credential collection, or content extraction is present or proposed. The current implementation records window titles, which can themselves contain sensitive user-visible metadata; deployment should obtain informed user consent and protect server storage. Wayland portals do not currently provide a portable generic active-window title interface, so the client returns an explicit unsupported state rather than trying to evade compositor restrictions. No automated tests were supplied; source compilation/build verification is reported in the change summary.
