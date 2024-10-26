// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
//    ext.objectboxVersion = "4.0.3" // For Groovy build scripts
     val objectboxVersion by extra("4.0.3") // For KTS build scripts

    repositories {
        mavenCentral()
    }

    dependencies {
        // Android Gradle Plugin 4.1.0 or later supported
        classpath("com.android.tools.build:gradle:8.1.0")
        classpath("io.objectbox:objectbox-gradle-plugin:$objectboxVersion")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}