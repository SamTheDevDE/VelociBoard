plugins {
    java
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":velociboard-api"))
    compileOnly("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    compileOnly("net.william278:velocityscoreboardapi:2.1.1")
    compileOnly("net.luckperms:api:5.5")
    implementation("org.yaml:snakeyaml:2.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.shadowJar {
    archiveFileName.set("VelociBoard-Velocity-${project.version}.jar")
    relocate("org.yaml.snakeyaml", "de.samthedev.velociboard.lib.snakeyaml")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
