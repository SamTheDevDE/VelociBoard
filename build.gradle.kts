plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    implementation("org.yaml:snakeyaml:2.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
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
