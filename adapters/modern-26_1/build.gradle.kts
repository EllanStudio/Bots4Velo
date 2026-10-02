plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
sourceSets.main {
    java.srcDir("../modern-common/src/main/java")
    java.srcDir("../modern-common/src/compat-pre26_3/java")
}
repositories {
    mavenCentral(); maven("https://repo.opencollab.dev/main/"); maven("https://jitpack.io")
}
dependencies {
    compileOnly(project(":transport-api"))
    implementation("org.geysermc.mcprotocollib:protocol:26.1-SNAPSHOT")
    implementation("net.kyori:adventure-text-serializer-plain:4.25.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
}
tasks.shadowJar {
    archiveFileName.set("modern-26_1.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    relocate("dev.nulli0n.vbot.adapter.modern", "dev.nulli0n.vbot.adapter.v26_1_2")
    relocate("org.geysermc.mcprotocollib", "dev.nulli0n.vbot.adapter.v26_1_2.lib.mcpl")
    relocate("org.cloudburstmc", "dev.nulli0n.vbot.adapter.v26_1_2.lib.cloudburst")
    relocate("io.netty", "dev.nulli0n.vbot.adapter.v26_1_2.lib.netty")
    relocate("it.unimi.dsi.fastutil", "dev.nulli0n.vbot.adapter.v26_1_2.lib.fastutil")
    relocate("com.google.gson", "dev.nulli0n.vbot.adapter.v26_1_2.lib.gson")
    relocate("net.raphimc", "dev.nulli0n.vbot.adapter.v26_1_2.lib.raphimc")
    relocate("net.lenni0451", "dev.nulli0n.vbot.adapter.v26_1_2.lib.lenni0451")
    relocate("net.kyori", "dev.nulli0n.vbot.adapter.v26_1_2.lib.kyori")
    relocate("org.slf4j", "dev.nulli0n.vbot.adapter.v26_1_2.lib.slf4j")
}
