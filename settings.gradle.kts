rootProject.name = "Ploceus"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven {
            name = "OneLiteFeatherReleases"
            url = uri("https://repo.onelitefeather.dev/releases")
        }
    }
    versionCatalogs {
        create("libs") {
            version("paper", "26.2.build.123-stable")
            version("multiverse", "5.8.1")
            version("cloud", "2.0.0")
            version("guice", "7.0.0")
            version("hibernate", "7.4.8.Final")
            version("liquibase", "5.0.4")
            version("junit", "5.11.4")

            library("paper.api", "io.papermc.paper", "paper-api").versionRef("paper")
            library("multiverse.core", "org.mvplugins.multiverse.core", "multiverse-core").versionRef("multiverse")

            library("cloud.paper", "org.incendo", "cloud-paper").versionRef("cloud")
            library("cloud.annotations", "org.incendo", "cloud-annotations").versionRef("cloud")
            library("cloud.extras", "org.incendo", "cloud-minecraft-extras").versionRef("cloud")
            bundle("cloud", listOf("cloud.paper", "cloud.annotations", "cloud.extras"))

            library("guice", "com.google.inject", "guice").versionRef("guice")
            library("inventoryframework", "com.github.stefvanschie.inventoryframework", "IF").version("0.12.1")
            library("datafaker", "net.datafaker", "datafaker").version("2.7.0")

            library("hibernate.core", "org.hibernate.orm", "hibernate-core").versionRef("hibernate")
            library("hikari", "com.zaxxer", "HikariCP").version("7.1.0")
            library("liquibase.core", "org.liquibase", "liquibase-core").versionRef("liquibase")
            library("h2", "com.h2database", "h2").version("2.5.250")
            library("postgresql", "org.postgresql", "postgresql").version("42.7.13")
            bundle("persistence", listOf("hibernate.core", "hikari", "liquibase.core", "h2", "postgresql"))

            library("junit.jupiter", "org.junit.jupiter", "junit-jupiter").versionRef("junit")
            library("junit.platform.launcher", "org.junit.platform", "junit-platform-launcher").version("1.11.4")
            library("archunit", "com.tngtech.archunit", "archunit-junit5").version("1.5.0")
            library("testcontainers.postgresql", "org.testcontainers", "postgresql").version("1.21.4")
            library("testcontainers.junit", "org.testcontainers", "junit-jupiter").version("1.21.4")
            library("assertj", "org.assertj", "assertj-core").version("3.27.3")
        }
    }
}
