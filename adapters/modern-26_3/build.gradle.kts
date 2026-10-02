plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
sourceSets {
    main {
        java.srcDir("../modern-common/src/main/java")
        java.srcDir("../modern-common/src/compat-26_3/java")
    }
    test { java.srcDir("../modern-common/src/test/java") }
}
repositories {
    mavenCentral(); maven("https://repo.opencollab.dev/main/"); maven("https://jitpack.io")
}
dependencies {
    compileOnly(project(":transport-api"))
    implementation("org.geysermc.mcprotocollib:protocol:26.3-SNAPSHOT")
    implementation("net.kyori:adventure-text-serializer-plain:4.25.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(project(":transport-api"))
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
tasks.shadowJar {
    archiveFileName.set("modern-26_3.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    relocate("dev.nulli0n.vbot.adapter.modern", "dev.nulli0n.vbot.adapter.v26_3")
    relocate("org.geysermc.mcprotocollib", "dev.nulli0n.vbot.adapter.v26_3.lib.mcpl")
    // text-serializer-nbt ships in every MCPL version; unrelocated, only one copy survives the merged JAR.
    relocate("org.geysermc.adventure", "dev.nulli0n.vbot.adapter.v26_3.lib.geyseradventure")
    relocate("org.cloudburstmc", "dev.nulli0n.vbot.adapter.v26_3.lib.cloudburst")
    relocate("io.netty", "dev.nulli0n.vbot.adapter.v26_3.lib.netty")
    relocate("it.unimi.dsi.fastutil", "dev.nulli0n.vbot.adapter.v26_3.lib.fastutil")
    relocate("com.google.gson", "dev.nulli0n.vbot.adapter.v26_3.lib.gson")
    relocate("net.raphimc", "dev.nulli0n.vbot.adapter.v26_3.lib.raphimc")
    relocate("net.lenni0451", "dev.nulli0n.vbot.adapter.v26_3.lib.lenni0451")
    relocate("net.kyori", "dev.nulli0n.vbot.adapter.v26_3.lib.kyori")
    relocate("org.slf4j", "dev.nulli0n.vbot.adapter.v26_3.lib.slf4j")
}
