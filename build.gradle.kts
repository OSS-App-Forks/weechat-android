import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

subprojects {
    repositories {
        mavenCentral()
        google()
        maven { url = uri("https://jitpack.io") }
    }
}

// to print a sensible task graph, uncomment the following lines and run:
//   $ gradlew :app:assembleDebug taskTree --no-repeat
// plugins {
//     id("com.dorongold.task-tree") version "1.5"
// }

defaultTasks("assembleDebug")

repositories {
    google()
}

buildscript {
    repositories {
        mavenCentral()
        google()
        maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
    }

    dependencies {
        // R8 below 3.1.79, 3.2.70, and 3.3.62, has an issue;
        // it tries to convert some Enums to ints but kind of stops halfway.
        // This pin, and the above R8 repo are needed because at the moment this version
        // is not available in maven central. TODO remove when it is
        // https://github.com/ubergeek42/weechat-android/pull/537
        // https://issuetracker.google.com/issues/236618700
        classpath("com.android.tools:r8:3.3.62")

        classpath("com.android.tools.build:gradle:7.2.1")
        classpath("org.aspectj:aspectjtools:1.9.9.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.0")
        classpath("org.jetbrains.kotlin:kotlin-serialization:1.7.0")
    }
}

subprojects {
    tasks.withType<Test> {
        useJUnitPlatform()                      // aka JUnit 5

        testLogging {
            outputs.upToDateWhen { false }      // always rerun tests

            events("skipped", "failed")

            // https://github.com/gradle/gradle/issues/5431
            // https://github.com/gradle/kotlin-dsl-samples/issues/836#issuecomment-384206237
            addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) {}
                override fun beforeTest(testDescriptor: TestDescriptor) {}
                override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
                override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                    // print only the bottom-level test result information
                    if (suite.className == null) return

                    val details = if (result.skippedTestCount > 0 || result.failedTestCount > 0) {
                        ": ${result.successfulTestCount} successes, " +
                                "${result.failedTestCount} failures, " +
                                "${result.skippedTestCount} skipped"
                    } else {
                        ""
                    }

                    println("${suite.displayName}: ${result.resultType} " +
                            "(${result.testCount} tests$details)")
                }
            })
        }
    }
}


// The below is a plugin that checks for dependency updates.
// To get a plain text report, run:
//   $ ./gradlew dependencyUpdates
// See https://github.com/ben-manes/gradle-versions-plugin
plugins {
    id("com.github.ben-manes.versions") version "0.42.0"
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
    rejectVersionIf {
        isNonStable(candidate.version) && !isNonStable(currentVersion)
    }
}