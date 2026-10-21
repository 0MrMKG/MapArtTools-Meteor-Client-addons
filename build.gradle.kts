plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven("https://api.modrinth.com/maven")
    maven("https://jitpack.io")
    maven("https://maven.fallenbreath.me/releases")
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.meteor.client)
    modImplementation(libs.litematica) {
        exclude(group = "fi.dy.masa.malilib")
    }
    modImplementation(libs.malilib)
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )

        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())
        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}

// Run the dependency-free planners/state-machine tests on every check/build.
// Keep Minecraft runtime classes out of this standalone regression classpath.
val regressionOutput = layout.buildDirectory.dir("regression-classes")
val compileRegression by tasks.registering(JavaCompile::class) {
    group = "verification"
    description = "Compiles standalone regression checks and their pure Java helpers."
    source(fileTree("tests/java") { include("**/*.java") })
    source(provider {
        fileTree("src/main/java") { include("**/*.java") }.files.filter { file ->
            val source = file.readText(Charsets.UTF_8)
            listOf("net.minecraft.", "meteordevelopment.", "fi.dy.", "net.fabricmc.", "org.lwjgl.", "org.spongepowered.")
                .none { it in source }
        }
    })
    classpath = files()
    destinationDirectory.set(regressionOutput)
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

val regressionRuns = fileTree("tests/java") { include("**/*Test.java") }.files.sortedBy { it.path }.map { source ->
    tasks.register<JavaExec>("regression" + source.nameWithoutExtension) {
        group = "verification"
        dependsOn(compileRegression)
        classpath = files(regressionOutput)
        mainClass.set(source.relativeTo(file("tests/java")).invariantSeparatorsPath.removeSuffix(".java").replace('/', '.'))
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        workingDir(projectDir)
    }
}

val regressionTest by tasks.registering {
    group = "verification"
    description = "Runs all standalone regression checks."
    dependsOn(regressionRuns)
}
tasks.named("check") { dependsOn(regressionTest) }
