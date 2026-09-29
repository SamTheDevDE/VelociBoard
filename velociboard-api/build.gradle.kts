plugins {
    `java-library`
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
