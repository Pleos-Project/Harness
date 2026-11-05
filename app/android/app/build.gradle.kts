plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
    buildFeatures {
        compose = true
    }
}
// fixtures/constants.json(차종 표·기본 단가)·candidates_demo.json(오프라인 데모 후보) → assets/data/
// Kotlin 엔진(com.mist.pleos_app.engine)이 읽는다. 사본은 커밋하지 않는다(단일 출처는 fixtures/).
val repoFixtures = rootProject.file("../../fixtures")
val syncChagokData by tasks.registering(Copy::class) {
    from(repoFixtures) { include("constants.json", "candidates_demo.json") }
    into(layout.projectDirectory.dir("src/main/assets/data"))
}
tasks.named("preBuild") { dependsOn(syncChagokData) }

// 엔진 JUnit(FixturesTest)이 fixtures/ 를 읽으므로 fixture가 바뀌면 테스트를 다시 돌린다.
// 결과는 테스트마다 로그에 찍고, 실패하면 어느 fixture의 어느 값이 다른지 전체 메시지를 보여 준다.
tasks.withType<Test>().configureEach {
    inputs.dir(repoFixtures).withPropertyName("chagokFixtures")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.kotlinx.serialization.json)   // 엔진 JSON(계약·assets 데이터·장부 저장)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)   // @Preview

    testImplementation(libs.junit)
}
