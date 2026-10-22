# Vanta 2 Batch 5.1 — Device Accessibility Acceptance

**Status: PARTIAL — emulator sign-in checks only.** No physical Android device was attached. The authenticated app surfaces could not be reached with the tested CI build, so this is not a full device or accessibility acceptance.

## Test record

| Field | Evidence |
| --- | --- |
| Date | 2026-10-22 (local) |
| Device class | Android Emulator, AVD `SYSTEM_CI_QA_FINAL`; **not a physical device** |
| Emulator profile / reported product | Pixel 2 profile; `sdk_gphone64_x86_64` |
| Android | Android 16, API 36; 1080 × 1920, 420 dpi |
| Tested app | `dev.system.app`, debug variant, `vanta-debug.apk` from CI run `39719944499` |
| Tested build source SHA | `5c787482691029b639565e15b241529d2286de0b` (CI artifact source commit) |
| Tested APK SHA-256 | `185BF36F8FC1BE17A774CEC4482B6F4CCBA6EDEF107BA31E11E1070ED2C964A` |
| Workspace checkout at inspection | `f96d27f19dbc70812f82803ef89679838934f4e2` (distinct from the tested CI artifact) |
| Backend / test account | CI artifact had no Supabase project URL or publishable key; sign-in was blocked. No account or production credentials were used. |

The emulator was booted from the installed Android 36 system image and ADB reported it as `emulator-5554`. The app installed and launched successfully to its email sign-in screen. The UI displayed: “Connect Supabase to enable sign-in. Add the project URL and publishable key to android/local.properties.” The disabled sign-in action prevented entry into the authenticated app.

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
- **Light/dark and contrast: PARTIAL.** The sign-in screen was visually captured in both system themes. No contrast ratios or automated Accessibility Scanner results were collected. Enabled controls, error/state colors on authenticated screens, and other themes remain unassessed.
- **Touch targets: LIMITED SPOT CHECK.** At 1.0×, the email field bounds were 828 × 168 px (about 315 × 64 dp) and the sign-in action bounds were 828 × 137 px (about 315 × 52 dp), using 420 dpi. These two sign-in bounds exceed 48 dp in both dimensions. The action was disabled; no complete control audit was possible.
- **Keyboard: PARTIAL.** The email field accepted focus and opened the emulator’s Gboard. IME Done dismissed it. The disabled action could be brought into view after dismissal and scrolling. Hardware keyboard navigation, Tab / Shift+Tab, and Task Edit Save behavior were not tested.
- **Reduced motion: NOT TESTED.** Focus, timeline, completion, and navigation transitions were inaccessible behind sign-in, and the OS animation/reduced-motion settings were not evaluated against those flows.
- **Observed large-text issue:** In the 2.0× scrolled, keyboard-open, and IME-dismissed captures, the sign-in subtitle is visibly drawn beneath the status-bar icons. This is an observed overlap on the tested emulator, not a physical-device finding. It remains unpatched; the available build exposed only sign-in, and no fix could be validated against the authenticated surfaces.
- **Code fixes:** None. The available evidence did not demonstrate a specific semantics defect that could be fixed safely without the missing authenticated screen context.

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

## Manual acceptance checklist to close the gap

Run this with a physical Android device where available, or an approved emulator configured with a dedicated non-production test backend and account. Record device model, Android/API version, app source commit SHA, APK SHA-256, and date. Keep screenshots for each failed or noteworthy state.

1. Install the exact release candidate and sign into the dedicated test account. Visit **Today, Quick Capture, Task Detail, Task Edit, Week, Habits, Analytics, Settings, Focus/timeline, and widgets**. Exercise empty, populated, validation-error, loading, and success states where available.
2. Enable TalkBack. Traverse each screen from its first element through the last; record spoken labels, roles, values, selected/checked/disabled states, heading order, focus order, modal focus handling, and whether every action is reachable without a gesture that has no accessible alternative. In Task Edit, verify field labels and that Save is announced and reachable with both keyboard and screen reader.
3. Set system font/display scale to **1.0×, 1.3×, and 2.0×**. On every listed surface, verify text is not clipped or obscured, content reflows and scrolls, focus remains visible, and dialogs/menus remain operable. Repeat with the keyboard open and confirm IME Next/Done and primary actions remain reachable.
4. Check every interactive control’s hit area, including navigation, icon-only actions, dialogs, widget controls, and disabled states. Confirm at least **48 × 48 dp** where applicable and that controls are not obstructed or overlapping.
5. Capture light and dark themes. Measure text, icon, state, and control contrast with an accessibility checker; inspect disabled controls and error/success colors. Record the checker and each failing element rather than treating visual inspection as a contrast pass.
6. Enable the platform reduced-motion setting and disable/scale down system animations. Revisit Focus/timeline, completion, and navigation transitions; verify essential information and state changes remain understandable and actionable without motion.
7. Add each widget size to the home screen and verify its accessible name/state, TalkBack traversal, text scaling, light/dark appearance, refresh behavior, and action targets.
8. Repeat the critical flows on at least one supported physical device and record any device-specific differences. Recheck the observed 2.0× status-bar overlap after correcting it. Attach the resulting run record and screenshots here; change status to **PASS** only when all required surfaces and accessibility checks have evidence and no blocking issue remains.

**Current acceptance: ACCESSIBILITY PARTIAL.** The emulator evidence is limited to the unauthenticated sign-in screen; it cannot close Batch 5.1 device/accessibility acceptance.
