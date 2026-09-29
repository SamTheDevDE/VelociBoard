plugins {
    id("com.gradleup.shadow") version "9.6.1" apply false
    kotlin("jvm") version "2.4.20" apply false
}

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.william278.net/releases/")
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
