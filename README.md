# Linux Activity Assistance

Java 21 Maven multi-module application with an optional Linux X11 JNI tracker. After the user accepts the consent dialog, the client reports the foreground application's identity and window title every five seconds. It does not capture keys or application contents. A persistent monitor window shows connection state and provides a Stop tracking control.

The project contains `shared`, `server`, `client`, and `remote-assistance` modules. The remote-assistance module is an unintegrated prototype and is not part of the activity server workflow.

- [Build guide](docs/build.md): requirements, native build, Maven verification, and client launch.
- [Deployment guide](docs/deployment.md): TLS launch configuration and headless systemd deployment.
- [Security and configuration guide](docs/security.md): enrollment, RMI security, firewall, serialization filter, and prototype warning.
