import io.izzel.taboolib.gradle.Basic
import io.izzel.taboolib.gradle.Bukkit
import io.izzel.taboolib.gradle.BukkitHook

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
    id("io.izzel.taboolib") version "2.0.40"
}

group = "cn.linmumua"
version = providers.gradleProperty("project.version").get()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public")
    maven("https://oss.sonatype.org/content/groups/public")
    maven("https://repo.codemc.io/repository/maven-releases")
    maven("https://repo.codemc.io/repository/maven-snapshots")
    maven("https://repo.catnies.top/releases")
    maven("https://jitpack.io")
    // PlaceholderAPI (compileOnly) is only published on the ExtendedClip repo.
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.tabooproject.org/repository/releases/")
}

dependencies {
    compileOnly(providers.gradleProperty("server.folia").get())

    // PacketEvents API — provided at runtime by the PacketEvents plugin via
    // paper-plugin.yml `join-classpath: true`. We only compile against it here
    // and must NOT shade it into our jar.
    compileOnly(providers.gradleProperty("libs.packetevents").get()) {
        exclude(group = "net.kyori")
    }


    compileOnly(platform(providers.gradleProperty("libs.adventure_bom").get()))
    compileOnly(providers.gradleProperty("libs.adventure_api").get())
    compileOnly(providers.gradleProperty("libs.adventure_text_minimessage").get())
    compileOnly(providers.gradleProperty("libs.adventure_text_serializer_gson").get())
    compileOnly(providers.gradleProperty("libs.adventure_text_serializer_legacy").get())
    compileOnly(providers.gradleProperty("libs.adventure_text_serializer_plain").get())

    // PlaceholderAPI API — provided at runtime by the PlaceholderAPI plugin via
    // paper-plugin.yml `join-classpath: true` (declared as a softdepend). We only
    // compile against it; the runtime call is isolated in PlaceholderApiHook and is
    // guarded by a plugin-presence check so a missing PAPI never breaks class-loading.
    compileOnly("me.clip:placeholderapi:2.11.6")

    // ---- Database stack (ORM + pool + drivers) ----
    // These four are declared as compileOnly so they are NOT shaded into the jar.
    // They are downloaded at runtime into cache/taboolib and injected by TabooLib's
    // common-env through the @RuntimeDependency annotations in
    // cn.linmumua.mutool.database.DatabaseRuntimeDeps. Versions are pinned to match
    // the legacy MumuTools database (H2 2.x file format) so old data can be re-read.
    compileOnly("com.j256.ormlite:ormlite-core:6.1")
    compileOnly("com.j256.ormlite:ormlite-jdbc:6.1")
    compileOnly("com.zaxxer:HikariCP:6.1.0")
    compileOnly("com.h2database:h2:2.3.232")
    compileOnly("com.mysql:mysql-connector-j:9.1.0")

    // ---- Redis cross-server message bus (lettuce) ----
    // compileOnly so it is NOT shaded into the jar. It is injected at runtime into the
    // plugin class loader by TabooLib's common-env through the @RuntimeDependency in
    // cn.linmumua.mutool.redis.RedisRuntimeDeps (transitive = true also pulls
    // netty / reactor-core, keeping the injected class loader self-contained).
    // Gson is provided by Paper/folia at runtime (compile scope of folia-api).
    compileOnly("io.lettuce:lettuce-core:6.5.5.RELEASE")
}

taboolib {
    env {
        install(Basic, Bukkit, BukkitHook)
    }
    version {
        taboolib = "6.3.0-8f586e4"
    }
}

tasks {
    clean {
        delete("$rootDir/target")
    }

    shadowJar {
        archiveFileName.set("${project.name}-${project.version}-all.jar")
        destinationDirectory.set(file("$projectDir/target"))
    }
}

// Make the shadow jar go through TabooLib's relocation + environment injection,
// so target/Mutool-<version>-all.jar is a self-contained TabooLib plugin.
afterEvaluate {
    val jarTask = tasks.named<org.gradle.api.tasks.bundling.Jar>("shadowJar").get()
    tasks.named<io.izzel.taboolib.gradle.TabooLibMainTask>("taboolibMainTask").configure {
        inJar = jarTask.archiveFile.get().asFile
    }
    jarTask.finalizedBy("taboolibMainTask")
}
