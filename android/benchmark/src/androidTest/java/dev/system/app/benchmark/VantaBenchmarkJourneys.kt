package dev.system.app.benchmark

import androidx.benchmark.macro.BaselineProfileRule
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
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
class VantaBenchmarkJourneys {
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
        if (device.wait(Until.hasObject(By.text("Today")), UI_WAIT_MS)) {
            device.findObjects(By.text("Today")).firstOrNull()?.click()
            device.waitForIdle()
        }

        // A pre-authenticated profile account reaches the task list here. On a
        // fresh test install, the scroll gesture still exercises the sign-in
        // screen's scroll container without fabricating authentication state.
        scrollFirstVisibleContainer()
        clickTabIfVisible("Upcoming")
        clickTabIfVisible("Goals")
        clickTabIfVisible("Today")
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.clickTabIfVisible(label: String) {
        if (device.wait(Until.hasObject(By.text(label)), UI_WAIT_MS)) {
            device.findObjects(By.text(label)).firstOrNull()?.click()
            device.waitForIdle()
        }
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

@LargeTest
@RunWith(AndroidJUnit4::class)
class VantaBaselineProfile {
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

            if (device.wait(Until.hasObject(By.text("Today")), UI_WAIT_MS)) {
                device.findObjects(By.text("Today")).firstOrNull()?.click()
                device.waitForIdle()
                device.findObjects(By.scrollable(true)).firstOrNull()?.let { list ->
                    list.setGestureMargin(device.displayWidth / 5)
                    list.fling(Direction.DOWN)
                    device.waitForIdle()
                    list.fling(Direction.UP)
                    device.waitForIdle()
                }
                listOf("Upcoming", "Goals", "Today").forEach { label ->
                    if (device.wait(Until.hasObject(By.text(label)), UI_WAIT_MS)) {
                        device.findObjects(By.text(label)).firstOrNull()?.click()
                        device.waitForIdle()
                    }
                }
            }
        }
    }
}
