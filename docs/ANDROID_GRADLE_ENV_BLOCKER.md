# Android Gradle Environment Blocker

Checked from the Codex desktop-launched Windows PowerShell on 2026-10-21. This isolates the failure to Java's Windows selector self-pipe path before Gradle project configuration. It does not yet establish whether the failure is specific to the Codex-launched shell or affects regular Windows terminals too.

## Exact Gradle failure

From `E:\Career_Development\Projects\system\android`, ran:

```powershell
.\gradlew.bat help --no-daemon --stacktrace --debug
```

Exit code: `1`. The first exception and root cause were:

```text
java.io.IOException: Unable to establish loopback connection
  at java.base/sun.nio.ch.PipeImpl$Initializer.run(PipeImpl.java:103)
  at java.base/sun.nio.ch.PipeImpl$Initializer.run(PipeImpl.java:67)
  at java.base/sun.nio.ch.PipeImpl.<init>(PipeImpl.java:198)
  at java.base/sun.nio.ch.WEPollSelectorImpl.<init>(WEPollSelectorImpl.java:78)
  at java.base/java.nio.channels.Selector.open(Selector.java:295)
Caused by: java.net.SocketException: Invalid argument: connect
  at java.base/sun.nio.ch.UnixDomainSockets.connect0(Native Method)
  at java.base/sun.nio.ch.UnixDomainSockets.connect(UnixDomainSockets.java:148)
  at java.base/sun.nio.ch.PipeImpl$Initializer$LoopbackConnector.run(PipeImpl.java:133)
```

Gradle reaches the daemon-connection setup and fails while Java opens a `WEPollSelectorImpl`. The failure occurs before settings/project evaluation, task discovery, dependency resolution, or compilation.

## Java and environment

- `java -version`: Temurin OpenJDK `17.0.20.1` (build `17.0.20.1+1`, 2026-08-18).
- `JAVA_HOME`: `C:\Users\Sarvesh M\AppData\Local\Programs\Temurin\jdk-17.0.20.1+1`.
- `where.exe java` returned that JDK, `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe` (also Temurin `17.0.20.1`), and an Oracle Java 8 path shim.
- The Android build requires Java 17; the selected JDK meets that requirement.
- `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`, `JAVA_TOOL_OPTIONS`, `GRADLE_OPTS`, and `GRADLE_USER_HOME` were unset in the initial shell.
- No proxy credentials or Java/Gradle JVM overrides were present to redact or bypass.

## Network and selector probes

A throwaway Java source probe was written under `%TEMP%`, outside the repository. On both installed Temurin 17 JDKs, it independently opened a server socket and connected to it successfully for each of `localhost`, `127.0.0.1`, and `::1`. The same probe then failed at `Selector.open()` with:

```text
java.io.IOException: Unable to establish loopback connection
Caused by: java.net.SocketException: Invalid argument: connect
```

Windows `.NET Test-NetConnection` ping checks succeeded for `127.0.0.1`, `localhost`, and `::1`. `localhost` resolved to `::1` and `127.0.0.1`. The Windows hosts file contains only the commented-out localhost entries and a comment that localhost resolution is handled by DNS. No hosts-file change was made.

This distinguishes ordinary TCP loopback bind/connect from the JDK selector's internal self-pipe connection. It does not establish a specific firewall, antivirus, or host-file cause; none was disabled or changed.

## Gradle state isolation

- `.\gradlew.bat --stop` completed successfully and reported no active Gradle daemons.
- `.\gradlew.bat help --no-daemon` using the existing Gradle user home failed with the same selector exception.
- A fresh temporary `GRADLE_USER_HOME` retry also failed with the same selector exception. The wrapper initially stalled while fetching the Gradle 9.6.0 distribution; to complete the isolation without relying on that download, the already cached Gradle 9.6.0 distribution was copied into the temporary home, then the wrapper retry was run. That retry exited `1` with `java.io.IOException: Unable to establish loopback connection`.

## Assessment and next host check

The failure is independent of this Android source tree and cached Gradle user state: the JDK-level selector probe fails with both installed Java 17 runtimes, while raw IPv4 and IPv6 loopback sockets work. The exact Windows subsystem or process-launch policy responsible is not yet proven because this session cannot run a command in a regular, non-Codex Windows terminal.

In a regular Windows Terminal or PowerShell window outside Codex, run:

```powershell
Set-Location 'E:\Career_Development\Projects\system\android'
java -version
@('import java.nio.channels.Selector;', 'Selector.open();', '/exit') | & "$env:JAVA_HOME\bin\jshell.exe" --execution local
.\gradlew.bat help --no-daemon --stacktrace
```

If `Selector.open()` and Gradle succeed there, run the Android checks from that terminal; that would isolate the failure to the Codex-launched process context. If they fail there too, keep the captured exception and have the machine administrator inspect Java/Windows networking policy and endpoint-security logs for the JDK self-pipe socket path. Do not change the hosts file or disable firewall/security tooling without evidence.

Until that comparison succeeds, Android compilation, unit tests, lint, APK builds, instrumentation, and benchmark compilation remain blocked. No application or Gradle project source was changed by this diagnosis.
