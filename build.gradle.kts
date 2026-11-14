buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // O AGP 9.5 traz o Kotlin 2.2.10, mas o LiteRT-LM é compilado com Kotlin 2.4
        // e só pode ser lido por um compilador 2.3 ou mais novo.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
