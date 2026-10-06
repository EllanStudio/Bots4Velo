plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://jitpack.io")
    maven("https://repo1.maven.org/maven2/")
}
dependencies {
    compileOnly(project(":transport-api"))
    implementation("com.github.GeyserMC:mcprotocollib:1.16.5-2")
    implementation("net.kyori:adventure-text-serializer-plain:4.7.0")
}
tasks.shadowJar {
    archiveFileName.set("legacy-1_16_5.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    relocate("dev.nulli0n.vbot.adapter.legacy", "dev.nulli0n.vbot.adapter.v1_16_5")
    relocate("com.github.steveice10", "dev.nulli0n.vbot.adapter.v1_16_5.lib.steveice10")
    relocate("io.netty", "dev.nulli0n.vbot.adapter.v1_16_5.lib.netty")
    relocate("it.unimi.dsi.fastutil", "dev.nulli0n.vbot.adapter.v1_16_5.lib.fastutil")
    relocate("com.google.gson", "dev.nulli0n.vbot.adapter.v1_16_5.lib.gson")
    relocate("net.kyori", "dev.nulli0n.vbot.adapter.v1_16_5.lib.kyori")
    relocate("org.slf4j", "dev.nulli0n.vbot.adapter.v1_16_5.lib.slf4j")
}
