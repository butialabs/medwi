// Force a modern JavaPoet on the Gradle buildscript classpath: the Hilt Gradle
// plugin's AggregateDepsTask needs ClassName.canonicalName() (JavaPoet >= 1.13),
// which AGP's older bundled JavaPoet lacks.
buildscript {
    dependencies {
        classpath("com.squareup:javapoet:1.13.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
}
