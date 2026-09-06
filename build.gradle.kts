import java.security.MessageDigest

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.nulli0n.bots4velo"
version = providers.gradleProperty("pluginVersion").orElse("3.0.3").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val generatedVersionSourceDir = layout.buildDirectory.dir("generated/sources/version/main/java")

val generateBuildConstants = tasks.register("generateBuildConstants") {
    inputs.property("pluginVersion", project.version.toString())
    outputs.dir(generatedVersionSourceDir)

    doLast {
        val sourceFile = generatedVersionSourceDir.get()
            .file("dev/nulli0n/vbot/BuildConstants.java")
            .asFile
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package dev.nulli0n.vbot;

            public final class BuildConstants {
                public static final String VERSION = "${project.version}";

                private BuildConstants() {
                }
            }
            """.trimIndent() + System.lineSeparator()
        )
    }
}

sourceSets {
    named("main") {
        java.srcDir(generatedVersionSourceDir)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":addon-api"))
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    compileOnly("com.github.NEZNAMY:TAB-API:6.0.0")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0")

    implementation(project(":transport-api"))
    implementation(project(":backend-protocol"))
    implementation("org.yaml:snakeyaml:2.5")
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.velocitypowered:velocity-api:3.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val stagePaperCompanion = tasks.register<Copy>("stagePaperCompanion") {
    dependsOn(":paper-companion:shadowJar")
    from(project(":paper-companion").layout.buildDirectory.file(
        "libs/bots4velo-paper-${project.version}.jar"))
    into(layout.buildDirectory.dir("libs"))
}

tasks {
    compileJava {
        dependsOn(generateBuildConstants)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    test {
        useJUnitPlatform()
    }

    shadowJar {
        dependsOn(stagePaperCompanion)
        dependsOn(
            ":adapters:legacy-1_16_5:shadowJar",
            ":adapters:modern-1_21_11:shadowJar",
            ":adapters:modern-26_1:shadowJar",
            ":adapters:modern-26_2:shadowJar"
        )
        archiveBaseName.set("bots4velo")
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // Service providers from Adventure and Cloudburst must remain visible to
        // the transformer even though duplicate classes/resources are excluded.
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        mergeServiceFiles()

        relocate("com.google.gson", "dev.nulli0n.vbot.lib.gson")
        relocate("org.yaml.snakeyaml", "dev.nulli0n.vbot.lib.snakeyaml")

        from(zipTree(layout.projectDirectory.file("adapters/legacy-1_16_5/build/libs/legacy-1_16_5.jar")))
        from(zipTree(layout.projectDirectory.file("adapters/modern-1_21_11/build/libs/modern-1_21_11.jar")))
        from(zipTree(layout.projectDirectory.file("adapters/modern-26_1/build/libs/modern-26_1.jar")))
        from(zipTree(layout.projectDirectory.file("adapters/modern-26_2/build/libs/modern-26_2.jar")))
    }

    build {
        dependsOn(shadowJar)
    }
}

val verifyShadowJar = tasks.register<JavaExec>("verifyShadowJar") {
    group = "verification"
    description = "Loads the relocated protocol client from the single deployable JAR."
    dependsOn(tasks.shadowJar)
    classpath = files(tasks.shadowJar.flatMap { it.archiveFile })
    mainClass.set("dev.nulli0n.vbot.verify.ProtocolSmokeMain")
}

tasks.check {
    dependsOn(
        verifyShadowJar,
        ":transport-api:check",
        ":addon-api:check",
        ":backend-protocol:check",
        ":paper-companion:check",
        ":adapters:legacy-1_16_5:check",
        ":adapters:modern-1_21_11:check",
        ":adapters:modern-26_1:check",
        ":adapters:modern-26_2:check"
    )
}

val writeArtifactChecksum = tasks.register("writeArtifactChecksum") {
    dependsOn(tasks.shadowJar)
    doLast {
        val jars = fileTree(layout.buildDirectory.dir("libs")) { include("*.jar") }.files
            .sortedBy { it.name }
        check(jars.map { it.name }.toSet() == setOf(
            "bots4velo-${project.version}.jar",
            "bots4velo-paper-${project.version}.jar"
        )) { "Expected Velocity and Paper plugin JARs, found ${jars.map { it.name }}" }
        jars.forEach { jar ->
            val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
                .joinToString("") { byte: Byte -> "%02x".format(byte) }
            jar.resolveSibling(jar.name + ".sha256").writeText("$digest  ${jar.name}\n")
        }
    }
}
