import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.6.20"
  kotlin("plugin.serialization") version "1.6.20"
  application
}

group = "me.laevatein"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

kotlin {
  val ktor_version = "2.0.0"
  sourceSets {
    val main by getting {
      dependencies {
        implementation("io.ktor:ktor-client-core:$ktor_version")
        implementation("io.ktor:ktor-client-cio:$ktor_version")
        implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
        implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
      }
    }
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "15"
}

application {
  mainClass.set("MainKt")
}