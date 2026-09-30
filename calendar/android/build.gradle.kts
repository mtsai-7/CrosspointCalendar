// AGP 9.4 needs Gradle >= 9.6 and JDK 17+; Kotlin is built into AGP 9
// (the org.jetbrains.kotlin.android plugin must not be applied).
plugins {
    id("com.android.application") version "9.4.1" apply false
}
