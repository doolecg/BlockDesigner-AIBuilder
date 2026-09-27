// AI Builder, a BlockDesigner plugin released on its own. Build it with:  ./gradlew jar
// then install build/libs/ai-builder-<version>.jar with Plugins > Manage plugins > Install.
//
// It compiles against the BlockDesigner plugin API jars in libs/. BlockDesigner provides them, Jackson and JavaFX at
// runtime, so they are never bundled into the plugin.
plugins {
    `java-library`
    alias(libs.plugins.javafx)
}

group = "io.blockdesigner.plugins"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(26)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing,-classfile", "-parameters"))
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
    configuration = "compileOnly"
}

// The BlockDesigner API this plugin targets (from BlockDesigner 0.4.22).
val blockDesigner = files("libs/blockdesigner-plugin-api-0.4.22.jar", "libs/blockdesigner-core-0.4.22.jar")

dependencies {
    compileOnly(blockDesigner)
    compileOnly(libs.jackson.databind)

    testImplementation(blockDesigner)
    testImplementation(libs.jackson.databind)
    // JavaFX classes on the test classpath, so enabling the plugin (which makes its pages) can be tested; no toolkit is started.
    for (m in listOf("base", "graphics", "controls")) testImplementation("org.openjfx:javafx-$m:${libs.versions.javafx.get()}:win")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The manifest's version is this project's.
tasks.named<ProcessResources>("processResources") {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("blockdesigner-plugin.json") { filter { it.replace("@VERSION@", v) } }
}
