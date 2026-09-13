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

    implementation(libs.bundles.cloud)
    annotationProcessor(libs.cloud.annotations)
    implementation(libs.guice)
    implementation(libs.inventoryframework)
    implementation(libs.datafaker)

    // compileOnly: supplied at runtime by paper-plugin.yml's library resolver
    compileOnly(libs.bundles.persistence)

    testImplementation(libs.paper.api)
    testImplementation(files("libs/multiverse-core-5.8.1.jar"))
    testImplementation(libs.bundles.persistence)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.archunit)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
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
