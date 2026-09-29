// PhotoMaidCore für Android: plattformneutrale Domain-Logik (1:1-Port von Sources/PhotoMaidCore).
// Reines Kotlin/JVM, keine Android-Abhängigkeit, auf Linux testbar.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
