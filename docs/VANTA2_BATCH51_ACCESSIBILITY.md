# Vanta 2 Batch 5.1 — Device Accessibility Acceptance

**Status: PARTIAL — emulator sign-in checks only.** No physical Android device was attached. The authenticated app surfaces could not be reached with the tested CI build, so this is not a full device or accessibility acceptance.

## Test record

| Field | Evidence |
| --- | --- |
| Date | 2026-10-22 (local) |
| Device class | Android Emulator, AVD `SYSTEM_CI_QA_FINAL`; **not a physical device** |
| Emulator profile / reported product | Pixel 2 profile; `sdk_gphone64_x86_64` |
| Android | Android 16, API 36; 1080 × 1920, 420 dpi |
| Display size | Default AVD size only (1080 × 1920 at 420 dpi); larger system display-size setting not yet tested |
| Tested app | `dev.system.app`, debug variant, `vanta-debug.apk` from CI run `39719944499` |
| Tested baseline build source SHA | `5c787482691029b639565e15b241529d2286de0b` (CI artifact source commit; predates the inset fix) |
| Tested APK SHA-256 | `185BF36F8FC1BE17A774CEC4482B6F4CCBA6EDEF107BA31E11E1070ED2C964A` |
| Workspace source base for this follow-up | `29b0e2fd267c55823ffe9ec6d686a2ade8f9e8b7` (the uncommitted inset patch is not included in this SHA) |
| Backend / test account | CI artifact had no Supabase project URL or publishable key; sign-in was blocked. No account or production credentials were used. |

The emulator was booted from the installed Android 36 system image and ADB reported it as `emulator-5554`. The app installed and launched successfully to its email sign-in screen. The UI displayed: “Connect Supabase to enable sign-in. Add the project URL and publishable key to android/local.properties.” The disabled sign-in action prevented entry into the authenticated app.

## Final accessibility verdict table

| Gate | Status | Evidence / notes |
| --- | --- | --- |
| Status-bar / inset issue | **PATCHED — RUNTIME VERIFICATION PENDING** | Reproduced at 2.0× with Gboard on API 36/target 36; the pre-fix capture shows the sign-in subtitle beneath status-bar icons. `SignInScreen` now applies `safeDrawingPadding()` to the scroll viewport. The patched APK could not be built locally because Gradle failed before project configuration in the host JDK selector. |
| TalkBack | **PARTIAL** | TalkBack package is installed; no audible traversal was completed. UIAutomator exposed a focusable/clickable `EditText` and nearby “Email address” label text, which does not verify the spoken label or traversal order. |
| Large font | **PARTIAL** | Baseline sign-in checked at 1.0×, 1.3×, and 2.0×. The 2.0× pre-fix overlap is recorded; all scales need retesting on the patched APK. Other screens are blocked by sign-in. |
| Contrast | **PARTIAL** | Source-token calculations on sign-in: light primary/secondary/error text 16.45:1 / 9.05:1 / 6.21:1; field outline 4.31:1. Dark primary/secondary/error text 13.59:1 / 10.23:1 / 10.20:1. No scanner was run; disabled-action contrast and authenticated screens remain unassessed. |
| Touch targets | **PARTIAL** | Baseline sign-in field/action bounds measured at about 315 × 64 dp and 315 × 52 dp. No full control audit or patched APK measurement. |
| Keyboard/IME | **PARTIAL** | Gboard and IME Done were exercised on baseline sign-in; at 2.0× the action is behind the open keyboard. Post-fix IME/nav-bar behavior is pending a rebuilt APK. |
| Reduced motion | **NOT RUN** | Focus/timeline/completion flows were blocked by sign-in; reduced-motion behavior was not exercised. |
| Authenticated screens | **BLOCKED** | The tested CI APK lacked a Supabase URL and publishable key. Specialist A is preparing a disposable test-user session; no credentials have been copied into this report. |
| Physical device | **NOT RUN** | No physical Android device was attached. All device evidence here is from `SYSTEM_CI_QA_FINAL`. |

## Surface coverage

| Surface | Result | Notes |
| --- | --- | --- |
| Email sign-in | **PARTIAL** | Visually checked in light and dark mode at 1.0×, light at 1.3× and 2.0×. Field focus, software keyboard, and IME Done dismissal were exercised. Sign-in itself could not be submitted because the CI build had no backend configuration. |
| Today / home | **NOT RUN** | Authentication blocked. |
| Quick Capture | **NOT RUN** | Authentication blocked. |
| Task detail and edit | **NOT RUN** | Authentication blocked; Save and field traversal were not checked. |
| Week / habits / analytics / settings | **NOT RUN** | Authentication blocked. |
| Focus, timeline, completion, and navigation motion | **NOT RUN** | Authentication blocked; reduced-motion behavior was not exercised. |
| Home-screen widgets | **NOT RUN** | No authenticated data or configured widget state was available. |

## Accessibility observations

- **TalkBack: NOT TESTED.** The TalkBack package was installed, but it was not enabled for an audible traversal. A UIAutomator hierarchy inspection showed a focusable, clickable `EditText` and nearby “Email address” label text; this does not establish the label or traversal order TalkBack speaks.
- **Font scale: PARTIAL.** Sign-in was captured at 1.0×, 1.3×, and 2.0×. At 1.3× the complete sign-in content remained visible. At 2.0× the content wrapped and required vertical scrolling to reach the lower warning and action. With the software keyboard open, the action was behind the keyboard; IME Done dismissed the keyboard, after which scrolling exposed the action. The action remained disabled. No other screen was available to inspect at these scales.
- **Light/dark and contrast: PARTIAL.** The sign-in screen was visually captured in both system themes. Relative-luminance calculations from the sign-in theme tokens give light primary/secondary/error text ratios of 16.45:1 / 9.05:1 / 6.21:1 and a field-outline ratio of 4.31:1 against the light card surface; dark primary/secondary/error text ratios are 13.59:1 / 10.23:1 / 10.20:1 against the dark card surface. These are source-token calculations, not Accessibility Scanner results. The disabled action’s contrast, enabled controls, authenticated screens, and other states remain unassessed.
- **Touch targets: LIMITED SPOT CHECK.** At 1.0×, the email field bounds were 828 × 168 px (about 315 × 64 dp) and the sign-in action bounds were 828 × 137 px (about 315 × 52 dp), using 420 dpi. These two sign-in bounds exceed 48 dp in both dimensions. The action was disabled; no complete control audit was possible.
- **Keyboard: PARTIAL.** The email field accepted focus and opened the emulator’s Gboard. IME Done dismissed it. The disabled action could be brought into view after dismissal and scrolling. Hardware keyboard navigation, Tab / Shift+Tab, and Task Edit Save behavior were not tested.
- **Reduced motion: NOT TESTED.** Focus, timeline, completion, and navigation transitions were inaccessible behind sign-in, and the OS animation/reduced-motion settings were not evaluated against those flows.
- **Observed large-text issue:** In the 2.0× scrolled and keyboard-open captures, the sign-in subtitle is visibly drawn beneath the status-bar icons. This is an observed overlap on the tested emulator, not a physical-device finding. The source patch is applied but has not been rebuilt or runtime-verified; the available APK exposed only sign-in.
- **Code fix:** `SignInScreen` now applies Compose `safeDrawingPadding()` before the scroll modifier. This uses platform-reported safe drawing insets rather than fixed device padding or smaller text. The host Gradle/JDK failure prevented compiling and runtime-verifying this patch so far.

## Captured emulator evidence

All images below are unmodified ADB screenshots from `SYSTEM_CI_QA_FINAL`; none are physical-device captures.

| Capture | What it records |
| --- | --- |
| [Light sign-in, 1.0×](evidence/vanta2-batch51-accessibility/signin-light-font-1.0.png) | Full sign-in screen and disabled action. |
| [Dark sign-in, 1.0×](evidence/vanta2-batch51-accessibility/signin-dark-font-1.0.png) | Sign-in screen with system dark theme enabled. |
| [Light sign-in, 1.3×](evidence/vanta2-batch51-accessibility/signin-light-font-1.3.png) | Larger text with all sign-in content visible. |
| [Light sign-in, 2.0× initial](evidence/vanta2-batch51-accessibility/signin-light-font-2.0-initial.png) | 2.0× text wrapping before scrolling. |
| [Light sign-in, 2.0× scrolled](evidence/vanta2-batch51-accessibility/signin-light-font-2.0-scrolled.png) | Lower sign-in content after vertical scroll. |
| [Light sign-in, 2.0× keyboard open](evidence/vanta2-batch51-accessibility/signin-light-font-2.0-keyboard.png) | Gboard open; lower action is obscured by the keyboard. |
| [Light sign-in, 2.0× keyboard dismissed](evidence/vanta2-batch51-accessibility/signin-light-font-2.0-ime-dismissed.png) | IME dismissed; field/action/configuration warning are visible after scroll. |
| [Light sign-in, 2.0× status-bar overlap before fix](evidence/vanta2-batch51-accessibility/signin-light-font-2.0-statusbar-overlap-before-fix.png) | Fresh ADB capture reproducing subtitle/status-bar overlap with Gboard open. |

## Android gate attempt

Attempted the Android workflow tasks locally: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`, and `connectedDebugAndroidTest`. Gradle exited before project configuration with `java.io.IOException: Unable to establish loopback connection` while the JDK opened its selector. No Android task, Kotlin compilation, unit test, lint, APK assembly, or instrumentation test ran. This matches the host/JDK diagnosis in [JAVA_LOOPBACK_ROOT_CAUSE.md](JAVA_LOOPBACK_ROOT_CAUSE.md). The local build configs were replaced temporarily with SDK-only / CI placeholder values and restored byte-for-byte; no secret values were written to this report.

## Manual acceptance checklist to close the gap

Run this with a physical Android device where available, or an approved emulator configured with a dedicated non-production test backend and account. Record device model, Android/API version, app source commit SHA, APK SHA-256, and date. Keep screenshots for each failed or noteworthy state.

1. Install the exact release candidate and sign into the dedicated test account. Visit **Today, Quick Capture, Task Detail, Task Edit, Week, Habits, Analytics, Settings, Focus/timeline, and widgets**. Exercise empty, populated, validation-error, loading, and success states where available.
2. Enable TalkBack. Traverse each screen from its first element through the last; record spoken labels, roles, values, selected/checked/disabled states, heading order, focus order, modal focus handling, and whether every action is reachable without a gesture that has no accessible alternative. In Task Edit, verify field labels and that Save is announced and reachable with both keyboard and screen reader.
3. Set system font/display scale to **1.0×, 1.3×, and 2.0×**. On every listed surface, verify text is not clipped or obscured, content reflows and scrolls, focus remains visible, and dialogs/menus remain operable. Repeat with the keyboard open and confirm IME Next/Done and primary actions remain reachable.
4. Check every interactive control’s hit area, including navigation, icon-only actions, dialogs, widget controls, and disabled states. Confirm at least **48 × 48 dp** where applicable and that controls are not obstructed or overlapping.
5. Capture light and dark themes. Measure text, icon, state, and control contrast with an accessibility checker; inspect disabled controls and error/success colors. Record the checker and each failing element rather than treating visual inspection as a contrast pass.
6. Enable the platform reduced-motion setting and disable/scale down system animations. Revisit Focus/timeline, completion, and navigation transitions; verify essential information and state changes remain understandable and actionable without motion.
7. Add each widget size to the home screen and verify its accessible name/state, TalkBack traversal, text scaling, light/dark appearance, refresh behavior, and action targets.
8. Repeat the critical flows on at least one supported physical device and record any device-specific differences. Recheck the observed 2.0× status-bar overlap on a patched APK at 1.0×, 1.3×, and 2.0×, including Gboard and navigation-bar states. Attach the resulting run record and screenshots here; change status to **PASS** only when all required surfaces and accessibility checks have evidence and no blocking issue remains.

**Current acceptance: ACCESSIBILITY PARTIAL.** The inset fix is in source but awaits Android build/runtime verification. The current emulator evidence is limited to the unauthenticated sign-in screen; it cannot close Batch 5.1 device/accessibility acceptance.
