plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":velociboard-api"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveFileName.set("VelociBoard-Paper-${project.version}.jar")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
