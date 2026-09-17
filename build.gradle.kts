plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.0.2"
}

group = "net.onelitefeather"
version = providers.gradleProperty("version").get()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(files("libs/multiverse-core-5.8.1.jar"))
    // Provided by Paper itself (paper-api declares it as an `api` dependency, so it's already
    // on every plugin's runtime classpath) - compileOnly so it is neither shaded into the
    // plugin jar nor fetched again by PloceusBootstrap's MavenLibraryResolver.
    compileOnly(libs.slf4j.api)

    implementation(libs.bundles.cloud)
    annotationProcessor(libs.cloud.annotations)
    implementation(libs.guice)
    implementation(libs.inventoryframework)
    implementation(libs.datafaker)
    // Bundled directly (implementation, not compileOnly) and deliberately left OUT of the
    // shadowJar relocations below: Paper does not supply it, and MavenLibraryResolver is
    // reserved for the heavyweight, multi-megabyte persistence stack this project already
    // fetches lazily at startup - opentelemetry-api is a tiny, dependency-free, SemVer-stable
    // artifact with no such need. It must keep its real `io.opentelemetry.api.*` package names
    // because the OpenTelemetry javaagent recognises and bridges GlobalOpenTelemetry by exact
    // class identity; relocating it would make the agent unable to see it at all.
    implementation(libs.opentelemetry.api)

    // compileOnly: supplied at runtime by paper-plugin.yml's library resolver
    compileOnly(libs.bundles.persistence)

    testImplementation(libs.paper.api)
    testImplementation(files("libs/multiverse-core-5.8.1.jar"))
    testImplementation(libs.slf4j.api)
    testImplementation(libs.opentelemetry.api)
    // In-memory span/metric exporters used to assert on real (not mocked) OTel SDK behaviour.
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.bundles.persistence)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.archunit)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

/// `paper-plugin.yml` carries `version: '${version}'`; nothing substituted it, so the jar
/// shipped a literal `${version}` to Paper. Replaced by hand rather than with `expand(...)`,
/// which runs the whole file through Groovy's template engine and would choke on any other
/// `$` or backslash a future edit introduces.
tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("paper-plugin.yml") {
        filter { line -> line.replace("\${version}", pluginVersion) }
    }
}

tasks.withType<JavaCompile> {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

tasks.shadowJar {
    archiveClassifier.set("")
    val base = "net.onelitefeather.ploceus.libs"
    relocate("org.incendo.cloud", "$base.cloud")
    relocate("com.google.inject", "$base.guice")
    relocate("com.github.stefvanschie.inventoryframework", "$base.inventoryframework")
    relocate("net.datafaker", "$base.datafaker")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
