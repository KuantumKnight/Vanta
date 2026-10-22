# Vanta 2 Batch 5 UI Audit

Date: 2026-10-22 | Branch: vanta2-batch3-batch4-integration
Scope: Android Compose screens, Glance widgets, notifications, navigation, and source-level performance/accessibility.

## Method and limits

This is a source audit of the integrated Batch 3/4 implementation. No source files were changed during the audit. The local Android build is blocked before Gradle project configuration by the host Java selector/loopback issue documented in docs/VANTA2_PRE_BATCH5_GATE.md. adb devices -l returned no connected device or emulator, so runtime screenshots, font-scale checks, and visual verification were unavailable. Findings below distinguish source evidence from runtime claims.

## UI inventory

| Surface | Current implementation | Empty / loading / error / offline behavior |
|---|---|---|
| App entry and navigation | VantaRoot owns selected destination and shared state. DashboardScreen presents eight destinations in a horizontal ScrollableTabRow; Today is initially selected. Focus is presented separately. | Authentication has a loading screen and sign-in state. Shared task data is local-first; actionable sync failures and queued completion recovery are surfaced. |
| Today | TodayTimeline uses a lazy vertical list: day summary, optional planner actions, overdue tasks, chronological task and Google Calendar rows, current-time marker, then unscheduled tasks. Rows are compact and stacked; timeline gaps are not proportional to clock time. | Empty-day copy exists, but currently tells the user to add a task in ChatGPT even though Quick Capture is in the app. Search has a no-match state. Calendar failure can retain cached events. |
| Task actions and detail | Timeline supports completion, starting Focus, long-press drag for eligible SOFT blocks, and a time-picker reschedule path. General task cards primarily expose completion. Week block inspection is informational; no dedicated task detail/edit screen is evident in these surfaces. | Completion is optimistic and can be queued for offline replay. Queue failure/conflict cards have retry/dismiss controls. |
| Quick Capture | Modal bottom sheet with phrase parsing, title, notes, scheduled/anytime mode, HARD/SOFT/FLEXIBLE type, priority, date/time, all-day, deadline, duration, validation, and save. Form is scrollable and responds to IME insets. | Inline validation errors and parser feedback are present. |
| Week | Seven-day planner with weekly totals, day selection, selected-day schedule, flexible tasks, previous/next navigation, block inspection and reschedule actions. Day cards use a fixed width and height. | Empty-day/flexible sections exist. No runtime narrow-width or large-font review was possible. |
| Upcoming and Goals | Searchable task list and goal list. Dashboard filters loaded tasks/goals for the active query. | Goals have loading/error handling. Upcoming empty/search behavior exists. |
| Habits | Due-today occurrences followed by a “Your routines” list, with history, streak details, complete/skip, and habit detail. | Today occurrence states and history descriptions are present. Habit detail is a non-scroll Column, creating clipping risk on short screens or with larger text. |
| Analytics | Period selection, multiple summary cards, planned-versus-actual view, and compact Canvas chart. | Dashboard renders the screen only when an analytics summary is non-null, leaving no explicit loading/empty/error state for a null summary. Chart values lack an accessible textual equivalent. |
| Evening Review | Review summary, resolved/remaining items, reschedule/skip actions, note, and finish action. | Calm review copy and persisted note path are present by source inspection; runtime/offline behavior was not exercised. |
| Settings / You | Planning, notification, appearance, analytics, Calendar, and account/data preferences. System, Light, and Dark appearance preference is already stored and applied by VantaTheme. | Calendar presents disconnected, connected, cached/stale, refresh-failure, and no-calendar states. Some weighted choice rows may be cramped at larger font scales. |
| Focus | Full-screen session with countdown, progress, elapsed/planned values, exit, end-early, and task completion action. Timer updates only in a leaf countdown while resumed. | Countdown is independent of network state. Timer typography is fixed at 60 sp and needs compact-height/font-scale validation. |
| Widgets | Two Glance families: NOW/NEXT and TODAY, with compact/medium/large content variants. Local state and owner-scoped feed are used; update scheduling is event-driven with one-time boundary work rather than a periodic UI poll. | Offline copy says state is saved and sync will retry. Widget palette is separately hard-coded from app theme; plus glyph accessibility and smallest text need review. |
| Notifications | Task reminder, morning briefing, and evening review scheduling use unique work and date/time checks. | Scheduling is idempotent by source inspection. Notification copy is generic; briefing does not include a useful count/first item, and there are no notification actions. |

## Existing visual system

- VantaTheme already provides separate light and dark Material color schemes and honors System, Light, and Dark preferences.
- Material color roles are centralized, but spacing, corner radii, typography choices, elevation, icon sizes, and motion timings are not centralized. Screens use local dp and sp values and independently styled cards/rows.
- There is no app typography scale beyond Material defaults and local overrides. The theme has no custom type family or named Vanta roles.
- Components include TaskCard, SectionHeading, EmptyState, status cards, and Material controls. Equivalent card and section patterns are still authored within individual screens.
- The overall palette uses a restrained teal accent and neutral surfaces; hard/soft/calendar distinctions are applied locally. The source alone does not establish that every semantic distinction remains readable in all modes.
- App-facing labels inspected use Vanta. SystemWidget, SystemRepository, and database identifiers are internal names, not public product labels.

## Inconsistencies and usability findings

### Navigation and hierarchy

- Eight destinations compete in a single horizontally scrolling top tab strip. Several destinations can be off-screen, and the current presentation gives Today no stronger product hierarchy than Settings or Review.
- On Today, search, a healthy sync timestamp, notification permission card, offline queue, morning briefing, planner/conflict/AI actions, summary, and timeline can all consume above-the-fold space. The timeline is the product's main surface but does not always get first visual priority.
- The top bar includes an explicit Sync action and healthy “Updated [time]” copy. Routine sync detail adds technical noise; actionable errors and unresolved queue items are more useful and already have dedicated presentation.
- Today empty-state copy references ChatGPT despite an in-app create action.
- Timeline headings and metadata include all-caps or implementation-oriented wording such as “TODAY TIMELINE,” “FIT INTO TODAY,” and “CALENDAR · HARD · READ ONLY.” Review these for user-centered language.
- Habits repeats due-today habits again in “Your routines,” including repeated streak information.
- Analytics is card-heavy and presents chart data without a textual summary.
- A task detail/edit surface is not apparent in the current task flows; Week inspection only shows block information.

### Accessibility and responsive risks

- Week date selection is communicated visually but lacks an explicit selected-state semantic; previous/next controls do have descriptions.
- The Analytics Canvas chart has a legend but no accessible value description.
- The widget plus glyph has no explicit content description, and widget palette/type sizes are independently specified.
- Week's fixed day-card dimensions, weighted Settings rows, and Focus's fixed 60 sp timer should be checked with large fonts and narrow widths.
- Habit detail uses a non-scroll column and can clip content on compact-height screens or at large text scale.
- Timeline task types are conveyed partly through local color/background treatment; verify shape/text/state cues also distinguish HARD, SOFT, FLEXIBLE, Calendar, completed, and conflicts.
- No explicit reduced-motion preference handling, semantic haptic policy, or shared motion system was found.
- No screenshots or device accessibility inspection are available because no emulator/device is attached.

## Performance and recomposition findings

- The current-time clock is minute-aligned and scoped to Today, which avoids a second-level root timer. However, TodayTimeline keys both its displayEntries and day plan calculations on the current minute, so task scheduling projections are recomputed each minute along with the current marker. Isolate marker state from the stable schedule projection.
- Focus timer updates once per second only inside FocusCountdown, pauses when the lifecycle is not resumed, and calculates from timestamps. Preserve this leaf boundary.
- VantaRoot observes the full pending-occurrence stream plus current-week and selected-week planner streams, and owns state for many destinations. DashboardScreen filters the pending list for the selected surface. This is a candidate for state/recomposition review; the prior performance audit also flags the unbounded owner-scoped pending list.
- SystemApplication builds Room/repository state, notification channels, preference collectors, and periodic sync work. Calendar repository/client and AI client construction are lazy. Do not move optional Calendar or AI initialization into startup.
- Analytics uses bounded date ranges and background aggregation by prior source review. Widget reads use an owner-scoped bounded feed and event-triggered refresh. Preserve these properties.
- The repository has Macrobenchmark and Baseline Profile infrastructure, but no benchmark ran in this environment. Do not claim startup/jank measurements without a device/CI run.

## Design freeze priorities

1. Define semantic light/dark color roles, typography, spacing, radii, elevation, icon sizing, timeline dimensions, and motion durations before screen redesign.
2. Keep Today primary and reduce persistent technical status to actionable states.
3. Isolate the minute clock so only the time marker updates; keep task and schedule projection data stable across minute ticks.
4. Replace the eight-peer tab strip with a clear, accessible navigation hierarchy without removing destinations or behavior.
5. Standardize task, section, empty/error, button, and sheet patterns with semantic and large-font support.
6. Align widgets to the app's semantic palette and give controls and data accessible labels.
7. Add text alternatives for analytics, explicit selection semantics for Week, and scrollable habit details.
8. Add restrained haptics/motion only where they confirm state changes; respect reduced motion.

## Post-audit implementation note

The minute-keyed schedule/day projection was replaced with a projection keyed by tasks, day, planning window, and Calendar blocks. This prevents overlap/free-time aggregation from rerunning just because the minute changes. The composable still observes the clock at its parent, so timeline content is recomposed each minute; Compose tracing is needed to assess the impact and further isolate the marker/current-next state.

## Source references

- Navigation and dashboard: android/app/src/main/java/dev/system/app/ui/navigation/DashboardScreen.kt, DashboardTab.kt
- Root state and screen data: android/app/src/main/java/dev/system/app/ui/VantaRoot.kt
- Theme: android/app/src/main/java/dev/system/app/ui/theme/VantaTheme.kt
- Today and timeline: android/app/src/main/java/dev/system/app/ui/today/TodayScreen.kt, ui/today/timeline/TodayTimeline.kt
- Task and capture: android/app/src/main/java/dev/system/app/ui/components/TaskCard.kt, ui/quickcapture/QuickCaptureSheet.kt
- Week: android/app/src/main/java/dev/system/app/ui/planner/week/WeeklyPlannerScreen.kt
- Habits: android/app/src/main/java/dev/system/app/habits/HabitsScreen.kt
- Analytics: android/app/src/main/java/dev/system/app/analytics/AnalyticsScreen.kt
- Settings and Calendar: android/app/src/main/java/dev/system/app/ui/settings/SettingsScreen.kt, ui/calendar/GoogleCalendarSettingsPanel.kt
- Focus: android/app/src/main/java/dev/system/app/ui/focus/FocusScreen.kt
- Widgets and refresh: android/app/src/main/java/dev/system/app/widget/SystemWidget.kt, TodayCompactWidget.kt, VantaWidgetRefresh.kt
- Notifications/startup: android/app/src/main/java/dev/system/app/notifications/VantaDailyNotifications.kt, SystemApplication.kt
- Existing host limitation and prior source audit: docs/VANTA2_PRE_BATCH5_GATE.md, docs/VANTA2_REGRESSION_PERFORMANCE_AUDIT.md
