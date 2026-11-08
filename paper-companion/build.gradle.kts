plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.nulli0n.bots4velo"
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

repositories {
    // Paper's proxy serves the legacy API and its Adventure transitive graph.
    // Keep a direct Maven fallback without letting Central timeouts mask it.
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo1.maven.org/maven2/")
}

dependencies {
    implementation(project(":backend-protocol"))
    // Paper used the com.destroystokyo coordinates before the 1.17 namespace
    // migration. Compiling against this API keeps the companion loadable on
    // the Java 16-era 1.16.5 server while using only API retained by modern Paper.
    compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("bots4velo-paper")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
