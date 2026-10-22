# Vanta 2 Batch 5.1 — Device Accessibility Acceptance

**Status: PARTIAL — emulator sign-in checks only.** No physical Android device was attached. The authenticated app surfaces could not be reached with the tested CI build, so this is not a full device or accessibility acceptance.

## Test record

| Field | Evidence |
| --- | --- |
| Date | 2026-10-22 (local) |
| Device class | Android Emulator, AVD `SYSTEM_CI_QA_FINAL`; **not a physical device** |
| Emulator profile / reported product | Pixel 2 profile; `sdk_gphone64_x86_64` |
| Android | Android 16, API 36; 1080 × 1920, 420 dpi |
| Display size | AVD default 1080 × 1920 at 420 dpi, plus one larger setting using `wm density 480` (360 dp logical width); restored to the default 420 dpi afterward |
| Initial visual-check build | `dev.system.app`, debug APK from CI run `39744563346`, source `78aa02a81a06474d528259ddef58dbe193cd8cc7` |
| IME-fix verification build | `dev.system.app`, debug APK from CI run `39753761328`, source `f1e4e3d682598854389f400595646d9743e0256c` |
| IME-fix APK SHA-256 | `8DF72C72B4AFE739CBF9992DD1D22302884D0B40C27657905D266E2939AF2A45` |
| Workspace source base for this follow-up | `f1e4e3d682598854389f400595646d9743e0256c` |
| Backend / test account | CI artifact had no Supabase project URL or publishable key; sign-in was blocked. No account or production credentials were used. |

The emulator was booted from the installed Android 36 system image and ADB reported it as `emulator-5554`. The app installed and launched to its email sign-in screen. The screen displayed: “Connect Supabase to enable sign-in. Add the project URL and publishable key to android/local.properties.” The disabled sign-in action prevented entry into the authenticated app. On the initial APK, 2.0× text with Gboard open moved the viewport into the status-bar region (`adjustPan`). The final `adjustResize` APK from run `39753761328` was then installed after uninstalling the E2E fixture build, which had a different debug signing key. On that APK, all three tested font scales retained the 63 px status-bar inset with Gboard closed and open: viewport `[0,63][1080,1857]` without Gboard and `[0,63][1080,1048]` with Gboard. The focused field stayed inside the resized viewport and IME Done dismissed Gboard at 1.0×, 1.3×, and 2.0×. The app remained signed out because this build had no Supabase configuration. After testing, `pm clear` removed app data; `run-as` confirmed there were no remaining private files and `files/offline-e2e-secrets.json` was absent. Font scale and emulator density were restored to 1.0× and 420 dpi.

## Final accessibility verdict table

| Gate | Status | Evidence / notes |
| --- | --- | --- |
| Status-bar / inset issue | **PASS — EMULATOR SIGN-IN RETEST** | On APK run `39753761328`, the viewport stayed at y=63 with Gboard closed and resized to the visible area above Gboard at 1.0×, 1.3×, and 2.0×. See the six `signin-adjustResize-*` captures. |
| TalkBack | **PARTIAL** | TalkBack package is installed; no audible traversal was completed. UIAutomator exposed a focusable/clickable `EditText` and nearby “Email address” label text, which does not verify the spoken label or traversal order. |
| Large font | **PARTIAL** | Sign-in was retested at 1.0×, 1.3×, and 2.0× with and without Gboard on the `adjustResize` APK; the viewport kept the top inset and the focused field stayed visible. Authenticated screens remain blocked. |
| Contrast | **PARTIAL** | Source-token calculations on sign-in: light primary/secondary/error text 16.45:1 / 9.05:1 / 6.21:1; field outline 4.31:1. Dark primary/secondary/error text 13.59:1 / 10.23:1 / 10.20:1. No scanner was run; disabled-action contrast and authenticated screens remain unassessed. |
| Touch targets | **PARTIAL** | Baseline sign-in field/action bounds measured at about 315 × 64 dp and 315 × 52 dp. No full control audit or patched APK measurement. |
| Keyboard/IME | **PARTIAL — INSET PASS; AUTHENTICATED ACTIONS BLOCKED** | On the `adjustResize` APK, Gboard resized the viewport while preserving the status-bar inset at all three font scales; IME Done dismissed Gboard. Sign-in remained disabled because the APK had no Supabase configuration, so primary-action and authenticated keyboard flows were not tested. |
| Reduced motion | **NOT RUN** | Focus/timeline/completion flows were blocked by sign-in; reduced-motion behavior was not exercised. |
| Authenticated screens | **BLOCKED** | The tested CI APK lacked a Supabase URL and publishable key; no authenticated session was available during the runtime retest. The disposable project setup did not produce an app sign-in. No credentials are included in this report. |
| Physical device | **NOT RUN** | No physical Android device was attached. All device evidence here is from `SYSTEM_CI_QA_FINAL`. |

## Surface coverage

| Surface | Result | Notes |
| --- | --- | --- |
| Email sign-in | **PARTIAL** | Initial and fixed builds were checked at 1.0×, 1.3×, and 2.0×. The final APK passed the emulator status-bar inset check with Gboard open and closed. Sign-in could not be submitted because the CI build had no backend configuration. |
| Today / home | **NOT RUN** | Authentication blocked. |
| Quick Capture | **NOT RUN** | Authentication blocked. |
| Task detail and edit | **NOT RUN** | Authentication blocked; Save and field traversal were not checked. |
| Week / habits / analytics / settings | **NOT RUN** | Authentication blocked. |
| Focus, timeline, completion, and navigation motion | **NOT RUN** | Authentication blocked; reduced-motion behavior was not exercised. |
| Home-screen widgets | **NOT RUN** | No authenticated data or configured widget state was available. |

## Accessibility observations

- **TalkBack: NOT TESTED.** The TalkBack package was installed, but it was not enabled for an audible traversal. A UIAutomator hierarchy inspection showed a focusable, clickable `EditText` and nearby “Email address” label text; this does not establish the label or traversal order TalkBack speaks.
- **Font scale: PARTIAL.** Sign-in was captured at 1.0×, 1.3×, and 2.0×. At 1.3× the complete sign-in content remained visible. At 2.0× the content wrapped and required vertical scrolling to reach the lower warning and disabled action. On the final APK, the viewport remained below the status bar and above Gboard at every tested scale; the sign-in action was still disabled. No authenticated screen was available to inspect.
- **Light/dark and contrast: PARTIAL.** The sign-in screen was visually captured in both system themes. Relative-luminance calculations from the sign-in theme tokens give light primary/secondary/error text ratios of 16.45:1 / 9.05:1 / 6.21:1 and a field-outline ratio of 4.31:1 against the light card surface; dark primary/secondary/error text ratios are 13.59:1 / 10.23:1 / 10.20:1 against the dark card surface. These are source-token calculations, not Accessibility Scanner results. The disabled action’s contrast, enabled controls, authenticated screens, and other states remain unassessed.
- **Touch targets: LIMITED SPOT CHECK.** At 1.0×, the email field bounds were 828 × 168 px (about 315 × 64 dp) and the sign-in action bounds were 828 × 137 px (about 315 × 52 dp), using 420 dpi. These two sign-in bounds exceed 48 dp in both dimensions. The action was disabled; no complete control audit was possible.
- **Keyboard: PARTIAL.** The email field accepted focus and opened the emulator’s Gboard. IME Done dismissed it. The disabled action could be brought into view after dismissal and scrolling. Hardware keyboard navigation, Tab / Shift+Tab, and Task Edit Save behavior were not tested.
- **Reduced motion: NOT TESTED.** Focus, timeline, completion, and navigation transitions were inaccessible behind sign-in, and the OS animation/reduced-motion settings were not evaluated against those flows.
- **Inset retest: PASS on the tested emulator.** The initial APK with `adjustPan` reported `[0,0][1080,386]` at 2.0× with Gboard. The final APK from run `39753761328`, source `f1e4e3d`, reported `[0,63][1080,1857]` with Gboard closed and `[0,63][1080,1048]` with Gboard open at 1.0×, 1.3×, and 2.0×. The focused field stayed within the viewport; IME Done dismissed Gboard.
- **Code fix:** `SignInScreen` applies Compose `safeDrawingPadding()` before the scroll modifier. `MainActivity` now declares `android:windowSoftInputMode="adjustResize"`. CI run `39753761328` passed the Android release gate, and its debug APK passed the sign-in emulator inset retest. This is emulator-only evidence; local Gradle still fails before project configuration in the host JDK selector.

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
| [Patched sign-in, 1.0×](evidence/vanta2-batch51-accessibility/signin-patched-font-1.0.png) | Hosted APK at default font scale. |
| [Patched sign-in, 1.3×](evidence/vanta2-batch51-accessibility/signin-patched-font-1.3.png) | Hosted APK at larger font scale. |
| [Patched sign-in, 2.0×](evidence/vanta2-batch51-accessibility/signin-patched-font-2.0.png) | Hosted APK at maximum tested font scale; content remains below the status bar. |
| [Patched sign-in, 2.0× keyboard open](evidence/vanta2-batch51-accessibility/signin-patched-font-2.0-keyboard.png) | Gboard open with the email field focused. |
| [2.0× keyboard-open `adjustPan` repro](evidence/vanta2-batch51-accessibility/signin-patched-font-2.0-keyboard-adjustpan-repro.png) | Fresh ADB capture from the hosted APK; UI hierarchy reports viewport `[0,0][1080,386]` while Gboard is open. |
| [Patched sign-in, larger display size](evidence/vanta2-batch51-accessibility/signin-patched-display-size-large.png) | Hosted APK at 1.0× font scale with emulator density override 480 dpi; all sign-in controls remain within the scroll viewport. Override was reset to 420 dpi. |
| [adjustResize sign-in, 1.0×](evidence/vanta2-batch51-accessibility/signin-adjustResize-font-1.0.png) | Final CI APK, Gboard closed; viewport `[0,63][1080,1857]`. |
| [adjustResize sign-in, 1.0× with IME](evidence/vanta2-batch51-accessibility/signin-adjustResize-font-1.0-ime.png) | Final CI APK, Gboard open; viewport `[0,63][1080,1048]`, focused field remains below status bar. |
| [adjustResize sign-in, 1.3×](evidence/vanta2-batch51-accessibility/signin-adjustResize-font-1.3.png) | Final CI APK, Gboard closed. |
| [adjustResize sign-in, 1.3× with IME](evidence/vanta2-batch51-accessibility/signin-adjustResize-font-1.3-ime.png) | Final CI APK, Gboard open; viewport resized with status-bar inset retained. |
| [adjustResize sign-in, 2.0×](evidence/vanta2-batch51-accessibility/signin-adjustResize-font-2.0.png) | Final CI APK at maximum tested font scale, Gboard closed. |
| [adjustResize sign-in, 2.0× with IME](evidence/vanta2-batch51-accessibility/signin-adjustResize-font-2.0-ime.png) | Final CI APK at maximum tested font scale, Gboard open; viewport resized with status-bar inset retained. |

## Android gate attempt

Attempted the Android workflow tasks locally: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`, and `connectedDebugAndroidTest`. Gradle exited before project configuration with `java.io.IOException: Unable to establish loopback connection` while the JDK opened its selector. Hosted CI run `39753761328` passed the release gate and built the `adjustResize` APK used for the final sign-in retest. The CI artifact reports 57 managed-device tests, 0 failures, 0 errors, and 0 skipped. The local build configs were restored byte-for-byte; no secret values were written to this report.

## Manual acceptance checklist to close the gap

Run this with a physical Android device where available, or an approved emulator configured with a dedicated non-production test backend and account. Record device model, Android/API version, app source commit SHA, APK SHA-256, and date. Keep screenshots for each failed or noteworthy state.

1. Install the exact release candidate and sign into the dedicated test account. Visit **Today, Quick Capture, Task Detail, Task Edit, Week, Habits, Analytics, Settings, Focus/timeline, and widgets**. Exercise empty, populated, validation-error, loading, and success states where available.
2. Enable TalkBack. Traverse each screen from its first element through the last; record spoken labels, roles, values, selected/checked/disabled states, heading order, focus order, modal focus handling, and whether every action is reachable without a gesture that has no accessible alternative. In Task Edit, verify field labels and that Save is announced and reachable with both keyboard and screen reader.
3. Set system font/display scale to **1.0×, 1.3×, and 2.0×**. On every listed surface, verify text is not clipped or obscured, content reflows and scrolls, focus remains visible, and dialogs/menus remain operable. Repeat with the keyboard open and confirm IME Next/Done and primary actions remain reachable.
4. Check every interactive control’s hit area, including navigation, icon-only actions, dialogs, widget controls, and disabled states. Confirm at least **48 × 48 dp** where applicable and that controls are not obstructed or overlapping.
5. Capture light and dark themes. Measure text, icon, state, and control contrast with an accessibility checker; inspect disabled controls and error/success colors. Record the checker and each failing element rather than treating visual inspection as a contrast pass.
6. Enable the platform reduced-motion setting and disable/scale down system animations. Revisit Focus/timeline, completion, and navigation transitions; verify essential information and state changes remain understandable and actionable without motion.
7. Add each widget size to the home screen and verify its accessible name/state, TalkBack traversal, text scaling, light/dark appearance, refresh behavior, and action targets.
8. The `adjustResize` sign-in check is complete on the API 36 emulator at 1.0×, 1.3×, and 2.0× with and without Gboard; it retained the top inset at all six settings. Repeat the critical flows on at least one supported physical device and record device-specific differences. The overall status remains **PARTIAL** until all required surfaces and accessibility checks have evidence and no blocking issue remains.

**Current acceptance: ACCESSIBILITY PARTIAL.** The specific 2.0× font/status-bar issue now passes the Gboard-open and closed retest on the `adjustResize` APK, at all three tested font scales. This pass is limited to sign-in on an Android 16/API 36 emulator. One larger display-size setting was checked on sign-in only. TalkBack traversal, full contrast checks, reduced motion, widgets, authenticated screens across display sizes, and a physical device remain unverified.
