package dev.system.app.benchmark

import androidx.benchmark.macro.BaselineProfileRule
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "dev.system.app"
private const val UI_WAIT_MS = 1_500L

@LargeTest
@RunWith(AndroidJUnit4::class)
class VantaBenchmarkJourneysTest {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        startActivityAndWait()
    }

    @Test
    fun coldStartupWithoutBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        startActivityAndWait()
    }

    @Test
    fun coldStartupRequiringBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        startActivityAndWait()
    }

    @Test
    fun warmStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    @Test
    fun todayScrollAndDashboardNavigation() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        navigateToDashboardDestination("Today")

        // A pre-authenticated profile account reaches the task list here. On a
        // fresh test install, the scroll gesture still exercises the sign-in
        // screen's scroll container without fabricating authentication state.
        scrollFirstVisibleContainer()
        listOf("Week", "Habits", "Upcoming", "Goals", "Today").forEach { label ->
            navigateToDashboardDestination(label)
        }
        openQuickCaptureIfAvailable()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.scrollFirstVisibleContainer() {
        val scrollable: UiObject2? = device.findObjects(By.scrollable(true)).firstOrNull()
        scrollable?.let { container ->
            container.setGestureMargin(device.displayWidth / 5)
            container.fling(Direction.DOWN)
            device.waitForIdle()
            container.fling(Direction.UP)
            device.waitForIdle()
        }
    }
}

/**
 * Reaches both primary destinations and items in the More menu. On fresh installs
 * that remain on sign-in/loading, the Dashboard's Today tab is absent and the
 * interaction is skipped rather than fabricating account state.
 */
private fun MacrobenchmarkScope.navigateToDashboardDestination(label: String) {
    // More has a stable content description and only exists once Dashboard is
    // composed. This avoids mistaking a page heading or task title for nav.
    if (!device.wait(Until.hasObject(By.desc("More destinations")), UI_WAIT_MS)) return

    if (label in setOf("Today", "Week", "Habits")) {
        device.findObjects(By.text(label)).firstOrNull { it.isClickable }?.let { tab ->
            tab.click()
            device.waitForIdle()
        }
        return
    }

    val more = device.findObject(By.desc("More destinations")) ?: return
    more.click()
    if (device.wait(Until.hasObject(By.text(label)), UI_WAIT_MS)) {
        device.findObjects(By.text(label)).firstOrNull { it.isClickable }?.let { item ->
            item.click()
            device.waitForIdle()
        } ?: run {
            device.pressBack()
            device.waitForIdle()
        }
    } else {
        device.pressBack()
        device.waitForIdle()
    }
}

/** Exercises the capture sheet without writing to an account or inventing task data. */
private fun MacrobenchmarkScope.openQuickCaptureIfAvailable() {
    val add = device.findObject(By.desc("Create a task")) ?: return
    add.click()
    if (device.wait(Until.hasObject(By.text("Quick Capture")), UI_WAIT_MS)) {
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}

@LargeTest
@RunWith(AndroidJUnit4::class)
class VantaBaselineProfileTest {
    @get:Rule
    val profileRule = BaselineProfileRule()

    @Test
    fun startupAndPrimaryScreens() {
        profileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
            maxIterations = 5,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            if (device.wait(Until.hasObject(By.desc("More destinations")), UI_WAIT_MS)) {
                navigateToDashboardDestination("Today")
                device.findObjects(By.scrollable(true)).firstOrNull()?.let { list ->
                    list.setGestureMargin(device.displayWidth / 5)
                    list.fling(Direction.DOWN)
                    device.waitForIdle()
                    list.fling(Direction.UP)
                    device.waitForIdle()
                }
                listOf("Week", "Habits", "Upcoming", "Goals", "Today").forEach { label ->
                    navigateToDashboardDestination(label)
                }
                openQuickCaptureIfAvailable()
            }
        }
    }
}
