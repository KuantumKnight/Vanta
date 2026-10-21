# Android Gradle blocker

Checked in the Codex desktop-launched PowerShell on Windows 11 on 2026-10-21.

## Result

Gradle fails before project configuration, task discovery, dependency resolution, or compilation. The wrapper downloads/starts Gradle 9.6.0 and successfully starts its daemon; Gradle then fails while the JDK initializes its Windows NIO selector. This prevents running Android unit tests, lint, and assembly tasks from the Codex-launched shell.

Exact diagnostic command, run from `android`:

```powershell
.\gradlew.bat help --stacktrace --info --no-daemon
```

The relevant exception chain is:

```text
java.io.IOException: Unable to establish loopback connection
  at sun.nio.ch.PipeImpl$Initializer.run(PipeImpl.java:103)
  at sun.nio.ch.WEPollSelectorImpl.<init>(WEPollSelectorImpl.java:78)
  at sun.nio.ch.WEPollSelectorProvider.openSelector(WEPollSelectorProvider.java:64)
  at java.nio.channels.Selector.open(Selector.java:295)
Caused by: java.net.SocketException: Invalid argument: connect
  at sun.nio.ch.UnixDomainSockets.connect0(Native Method)
  at sun.nio.ch.UnixDomainSockets.connect(UnixDomainSockets.java:148)
  at sun.nio.ch.PipeImpl$Initializer$LoopbackConnector.run(PipeImpl.java:133)
```

The stack is in JDK `Selector.open()` / `PipeImpl`, not in an Android task or project build script. A standalone probe using the Gradle-selected JDK also fails:

```powershell
@(
    'import java.nio.channels.Selector;'
    'Selector.open();'
    '/exit'
) | & "$env:JAVA_HOME\bin\jshell.exe" --execution local
```

It prints `java.io.IOException: Unable to establish loopback connection` with `java.net.SocketException: Invalid argument: connect`. The same probe fails using the other JDK 17 installation found on `PATH`.

## Environment and causes checked

- Gradle wrapper: 9.6.0, with a configured distribution SHA-256. The wrapper distribution and daemon both start.
- Gradle runtime: Temurin OpenJDK 17.0.20.1, selected from `JAVA_HOME`; Gradle's required Java runtime is available.
- The project uses `org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8`. Gradle's client reports that its JVM arguments do not match, so even `--no-daemon` launches a single-use daemon. The daemon starts; its selector initialization then hits the same exception. This message is not a project compilation or memory-allocation failure.
- `TEMP` and `TMP` point to the normal short Windows temp path; `TMPDIR` is unset.
- A .NET TCP listener/client connects successfully over `127.0.0.1` in the same shell. The failing operation is the JDK selector's internal socket-pipe initialization.
- No proxy variables or extra Java/Gradle option variables were set in the shell.
- The `settings.gradle.kts`, root build script, app build script, and wrapper configuration do not execute far enough to account for this selector exception.
- Retrying with `--no-daemon` and IPv4 preference has the same failure. Matching `GRADLE_OPTS` to the project's heap/encoding options also leaves Gradle launching a single-use daemon and failing at the selector.
- No project-local workaround is justified: the minimal JDK probe fails independently of this repository, and overriding the selector provider does not avoid the failing internal pipe. I made no application, Gradle, or security-policy changes.

This symptom also matches Windows Codex command-runner reports where the same `Selector.open()` probe and Gradle command fail inside the app-launched shell but work from a regular host terminal: [OpenAI Codex issue #40902](https://github.com/openai/codex/issues/40902). That is corroborating evidence, not a substitute for verifying this machine outside the app.

## Host-terminal verification

Open a regular Windows PowerShell or Windows Terminal window outside Codex, then run:

```powershell
Set-Location 'E:\Career_Development\Projects\system\android'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
```

If the host terminal succeeds, it confirms the current Gradle checks are blocked by the Codex-launched shell context rather than the Android project. Until then, the Android Gradle checks remain unverified.
