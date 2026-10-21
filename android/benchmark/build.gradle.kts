plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "dev.system.app.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    targetVariant = "benchmark"
}

baselineProfile {
    // Connected API 33+ devices/emulators can record profiles without root.
    useConnectedDevices = true
}

dependencies {
    androidTestImplementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
