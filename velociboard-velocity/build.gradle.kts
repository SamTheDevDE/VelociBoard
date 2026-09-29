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
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val pluginVersion = project.version.toString()
val generateVersion = tasks.register("generateVersion") {
    val output = layout.buildDirectory.dir("generated/sources/version/java")
    inputs.property("pluginVersion", pluginVersion)
    outputs.dir(output)
    doLast {
        val file = output.get().file("de/samthedev/velociboard/BuildVersion.java").asFile
        file.parentFile.mkdirs()
        file.writeText("package de.samthedev.velociboard;\n\nfinal class BuildVersion {\n"
                + "    static final String VALUE = \"$pluginVersion\";\n}\n")
    }
}

sourceSets.main {
    java.srcDir(generateVersion)
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
