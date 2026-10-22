# Vanta 2 Design System

Status: Batch 5 design freeze baseline | Date: 2026-10-22

Vanta uses quiet surfaces, a restrained teal accent, readable type, and a small geometry family. The system is built on existing Material 3 Compose components and adds no UI dependency. It preserves the System, Light, and Dark preference already stored by Batch 4.

## Design character

The interface should feel calm, precise, soft, tactile, fast, focused, and premium. Establish hierarchy through type size, weight, alignment, and spacing before adding color or decoration. Use one primary teal accent. Use semantic colors only when an item needs a distinct meaning. Cards have flat or near-flat surfaces; elevation communicates layering rather than decoration.

## Tokens

Tokens live in android/app/src/main/java/dev/system/app/ui/theme/VantaTokens.kt.

| Family | Values / roles |
|---|---|
| Spacing | 4, 8, 12, 16, 20, 24, 32, 40 dp |
| Corners | 4 dp tight, 8 dp control, 16 dp card, 28 dp sheet |
| Elevation | 0 dp flat, 1 dp card, 3 dp raised, 8 dp modal |
| Icons | 16, 20, 24 dp; 48 dp interactive target |
| Layout | 20 dp screen inset, 16 dp compact inset, 720 dp content max, 560 dp form max |
| Timeline | 56 dp time column, 20 dp rail, 8 dp marker, 44/56 dp compact/standard minimum block height, 8 dp row gap |
| Motion | 0 ms immediate, 120 ms fast, 200 ms standard, 280 ms emphasized |
| Type roles | Hero time, screen title, section title, task title, body, metadata, caption, statistic |

New and substantially edited UI should use these tokens instead of introducing one-off dimensions. Material typography remains font-scale aware; do not disable font padding or scale fonts down to fit.

## Color roles

VantaTheme exposes Material color roles for standard controls and LocalVantaColors.current for product semantics:

- Surfaces: surfaceRaised, surfaceSubtle
- Text: textPrimary, textSecondary, textMuted
- Accent: accent
- Status: successContainer/onSuccessContainer, warningContainer/onWarningContainer, dangerContainer/onDangerContainer
- Schedule: timelineHard/onTimelineHard, timelineSoft/onTimelineSoft, timelineFlexible/onTimelineFlexible
- Other product surfaces: calendarExternal/onCalendarExternal, habit, focus

Light mode uses warm-neutral off-white surfaces, dark charcoal text, teal accent, and subdued semantic containers. Dark mode uses layered charcoal-green surfaces with brighter readable semantic foregrounds; it is not a color inversion. Schedule categories pair a distinct semantic foreground and background, so text or shape cues should accompany color wherever those categories affect a decision.

## Typography and geometry

VantaTheme installs a Material 3 typography scale with a measured weight hierarchy: display, headline, and main title roles carry more weight; body and supporting roles remain regular. Named VantaTypeSize roles are available where a screen needs a product-specific size. Material styles and VantaTypeSize use scalable sp values.

Material shapes now use 8 dp controls, 16 dp cards, and 28 dp sheets. Do not make every control pill-shaped. Interactive controls must keep at least a 48 dp touch target, even when the visible icon is smaller.

## Components

android/app/src/main/java/dev/system/app/ui/components/VantaComponents.kt provides:

- VantaCard with Standard, Subtle, and Raised tones
- VantaSectionHeader with a shared title/detail hierarchy
- VantaChip for a non-interactive semantic label
- VantaEmptyState with a content-width limit and optional action

These components are intentionally small wrappers around Material 3. Continue to use Material Button, TextField, Dialog, and Sheet controls unless a Vanta component gives a clear consistency benefit. Avoid adding wrappers without repeated use.

## Motion and reduced motion

VantaMotion contains short transition durations. Call vantaMotionDuration(duration) before passing a value to an animation spec. VantaTheme observes Android's animator-duration scale and supplies LocalVantaReducedMotion; when the system disables animations, the helper returns zero. Do not add perpetual decorative motion or delay interaction while an animation runs. Haptic feedback should remain a separate, deliberate interaction policy.

## Widgets

Glance widgets do not inherit Compose CompositionLocals. Their palette should mirror the same semantic roles using the values in VantaTheme.kt: neutral surface layers, teal accent, subdued schedule colors, and readable semantic foregrounds. Keep widget text readable at supported sizes and expose meaningful descriptions for icon-only controls.

## Integration guidance

- Use LocalVantaColors for schedule type and status meaning; use MaterialTheme.colorScheme for ordinary controls.
- Use VantaSpacing and VantaCorner for layout rhythm and custom shapes.
- Prefer VantaSectionHeader and VantaCard where they replace repeated local patterns.
- Keep healthy sync/database implementation detail out of primary screen content; show only states that need user action.
- Keep task, Calendar, habit, focus, and planner behavior unchanged while applying visual tokens.
- Review light, dark, large text, compact width, and offline/error states on device before calling visual acceptance complete.

## Current coverage

The theme and shared token/component baseline are in place, with a first migration across the Batch 5 surfaces. The audit in docs/VANTA2_BATCH5_UI_AUDIT.md records source findings and verification limits; the batch summary in docs/VANTA2_BATCH5_REPORT.md tracks remaining work. Android build and screenshot verification remain subject to the host/CI device gate documented in docs/VANTA2_PRE_BATCH5_GATE.md.
