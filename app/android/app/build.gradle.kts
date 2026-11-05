plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mist.pleos_app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mist.pleos_app"   // June App 레포와 같은 값. App Market 등록 전 확정(DECISIONS 열린 결정 1)
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    // 주행 규제(CarUxRestrictionsManager)·AAOS 속성(RANGE_REMAINING 등)
    useLibrary("android.car")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// app/shared/chagok-engine.js → assets/chagok/engine.js (빌드 때 복사, 사본은 커밋하지 않음)
val syncChagokEngine by tasks.registering(Copy::class) {
    from(rootProject.file("../shared/chagok-engine.js")) { rename { "engine.js" } }
    into(layout.projectDirectory.dir("src/main/assets/chagok"))
}
tasks.named("preBuild") { dependsOn(syncChagokEngine) }

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
}
