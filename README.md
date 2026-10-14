# Linux Activity Assistance

Java 21 Maven multi-module application with an optional X11 JNI tracker. Activity collection reports the foreground application's identity and window title every five seconds; it does not capture keys or application contents. The client can be stopped by closing its process.

## Requirements

- OpenJDK 21 and Maven 3.8+
- Linux with X11 development headers (`libx11-dev`) and CMake
- X11 session for active-window metadata; Wayland currently reports `UNSUPPORTED_CAPABILITY` because a supported portal integration is not available

## Build from source

```sh
mvn clean package
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build
```

Set `-Djava.library.path="$PWD/native/build"` when launching the client, or provide the full library path with `-Dveridion.native.path="$PWD/native/build/libveridion_native.so"`. The server listens on port 1099 by default; set `-Dactivity.server.port=PORT` and `-Djava.rmi.server.hostname=ADDRESS` for another port or advertised address. Start `com.veridion.server.ServerMain`, then `com.veridion.client.ActivityClient [server-host]` with the client module and shared module on the classpath.

The current RMI service has no authentication or transport encryption and should only be exposed on a trusted network. RMI calls are limited to validated registration, activity metadata, and heartbeats.
