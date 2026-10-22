plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

val allowEmulatorBenchmarks = providers.gradleProperty("allowEmulatorBenchmarks").orNull == "true"

android {
    namespace = "dev.system.app.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (allowEmulatorBenchmarks) {
            testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        }
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Connected API 33+ devices/emulators can record profiles without root.
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
