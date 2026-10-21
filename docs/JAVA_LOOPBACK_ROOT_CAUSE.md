# Java loopback root-cause investigation

Conclusion: **HOST/JDK FAILURE IDENTIFIED**

The installed Java 17 runtimes fail in `Selector.open()` while ordinary IPv4, IPv6, and `localhost` TCP bind/connect/accept operations succeed. Gradle's recorded failure occurs in that same selector initialization path before project configuration. This identifies the release blocker as a Java 17 selector/self-pipe failure in this host environment, not an application-source failure. The evidence does not identify which Windows component or policy causes the JDK failure.

## Runtime inventory

- Selected `JAVA_HOME`: `C:\Users\Sarvesh M\AppData\Local\Programs\Temurin\jdk-17.0.20.1+1`.
- `java -version`: Eclipse Temurin OpenJDK 17.0.20.1+1, 64-bit, build date 2026-08-18.
- `javac -version`: 17.0.20.1.
- `where.exe java` found the selected Temurin JDK, `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe` (same Temurin 17.0.20.1 build), and an Oracle Java 8 path shim.
- `where.exe javac` found both Temurin JDK 17 installations; neither is a distinct JDK vendor/build. The installed-JDK registry entry also reports Temurin 17.0.20.1.
- Common Android Studio and JetBrains install roots and uninstall registry entries were checked; no Android Studio/JBR, JetBrains Runtime, Microsoft OpenJDK, or other JDK 17 installation was found.
- `C:\Program Files\Java\jre1.8.0_481` is an Oracle Java 8 **runtime only**, not a JDK and not the required Java 17 toolchain.
- The environment contained `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_SDK_ROOT`. No `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`, `JAVA_TOOL_OPTIONS`, or `GRADLE_OPTS` variable was set. No proxy credentials were printed.

## Minimal Java probe

A temporary `LoopbackProbe.java` was compiled outside the repository with each Temurin JDK. It separately attempted `Selector.open()` and a server bind → client connect → server accept against `127.0.0.1`, `::1`, and `localhost`.

Both installed Temurin 17 runtimes produced:

```text
Selector.open=FAIL java.io.IOException: Unable to establish loopback connection
Caused by: java.net.SocketException: Invalid argument: connect
  at java.base/sun.nio.ch.UnixDomainSockets.connect0(Native Method)
  at java.base/sun.nio.ch.PipeImpl$Initializer$LoopbackConnector.run(PipeImpl.java:133)
  at java.base/sun.nio.ch.WEPollSelectorImpl.<init>(WEPollSelectorImpl.java:78)
```

On both JDKs, the probe reported `bind/connect/accept=PASS` for `127.0.0.1`, `::1`, and `localhost`. Running with `-Djava.net.preferIPv4Stack=true` and `-Djava.net.preferIPv6Addresses=false` did not change the selector failure; the corresponding raw sockets continued to pass.

The Oracle Java 8.0_481 runtime passed `Selector.open()` and all three socket cases. It cannot be used to clear this Android build gate because the project requires JDK 17 and it has no compiler.

## Windows networking observations

- Windows reports `Windows 10 Home Single Language`, version `2009`, build `26200`.
- `ping -n 1 127.0.0.1` and `ping -n 1 localhost` succeeded.
- `Test-NetConnection` ping succeeded for `127.0.0.1` and `localhost`; its TCP connection result was false because no service was listening. The Java probe independently verified TCP listener/client traffic on both address families.
- DNS resolved `localhost` to both `::1` and `127.0.0.1`; .NET selected `::1` for `localhost`. Both direct Java address-family probes pass.
- The Windows hosts file has the standard localhost mappings commented out with the note that localhost resolution is handled by DNS. It contains no localhost override. The file has other unrelated domain entries; none were changed.
- IPv4 and IPv6 loopback routes both use `Loopback Pseudo-Interface 1`. Windows reports that interface connected. Several VPN/virtual adapters are installed, including Cloudflare WARP, Radmin, VMware, and WSL; their presence alone does not establish causation.
- No firewall, VPN, endpoint-protection, hosts, Winsock, or network setting was modified. No network reset was run.

## Gradle relation and safe next step

The previous Gradle attempt failed in the same `Selector.open()` → `WEPollSelectorImpl` → `PipeImpl` path before Gradle configured the Android project. The direct JDK probe independently reproduces that failure, so there is no evidence to attribute it to Vanta source or Gradle project configuration.

No working JDK 17 was found on this machine. In a regular Windows Terminal outside Codex, first run the same Java probe and `java -version`. If Selector works there, run the Gradle checks from that terminal and use that JDK for this checkout. If it fails there too, test a clean, vendor-supported JDK 17 installation in a temporary location with the probe before changing `JAVA_HOME` or `org.gradle.java.home`. If a clean JDK 17 still fails, ask the machine administrator to inspect endpoint-security and Windows socket-provider logs for the JDK selector's internal self-pipe connection. The current evidence does not justify `netsh winsock reset`, disabling security software, or editing the hosts file.

## Portable JDK 21 alternative attempt

Microsoft Learn lists the [Microsoft Build of OpenJDK 21.0.12.1 LTS](https://learn.microsoft.com/en-us/java/openjdk/download), including a Windows x64 ZIP and Microsoft-provided `.sha256sum.txt` sidecar. The interrupted download was resumed in the unique temporary directory `%TEMP%\vanta-msjdk21-probe-14f447531a8149dc99323cf6acf15930` and verified before extraction:

```text
Expected SHA-256 (Microsoft sidecar): 192441a9d27da813bada974bb88b4cf64d37a9589ed37f204374d411ca5ce07f
Actual SHA-256:                       192441a9d27da813bada974bb88b4cf64d37a9589ed37f204374d411ca5ce07f
Result: verification passed; extracted only to the temporary probe directory
```

Microsoft OpenJDK 21.0.12.1 reproduces the same `Selector.open()` failure: `Unable to establish loopback connection`, caused by `SocketException: Invalid argument: connect` in `PipeImpl$Initializer$LoopbackConnector`. Direct Java server/client traffic still passes for `127.0.0.1`, `::1`, and `localhost`. Retrying `android\gradlew.bat help --no-daemon --stacktrace` with this verified JDK also fails before project configuration in Gradle's daemon connection with the same selector exception. No Android task was discovered. No global installation, repository JVM setting, or host networking setting was changed.
