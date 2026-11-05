// 차곡 안드로이드 프로젝트 — 빌드 환경(H_Mate 11/4 설정에서 가져옴). 앱 기능은 아직 없음.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // JDK 21 툴체인 자동 받기 (gradle/gradle-daemon-jvm.properties)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Vehicle SDK · NaviHelper (ai.pleos.playground). 인증 없이 열림(11/5 확인)
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

rootProject.name = "Chagok"
include(":app")
