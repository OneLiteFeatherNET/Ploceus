package net.onelitefeather.ploceus;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "net.onelitefeather.ploceus",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /// The domain is pure. jakarta.persistence is the single exception: those are
    /// compile-time annotations with no runtime behaviour.
    @ArchTest
    static final ArchRule domainDependsOnNothing = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.bukkit..", "org.mvplugins..", "org.hibernate..",
                    "com.zaxxer..", "liquibase..", "org.incendo..",
                    "com.github.stefvanschie..", "net.datafaker..")
            .because("the domain must stay testable without a server or a database");

    @ArchTest
    static final ArchRule uiMustNotReachPersistence = noClasses()
            .that().resideInAPackage("..ui..")
            .should().dependOnClassesThat().resideInAPackage("..persistence..")
            .because("the UI talks to services, never to repositories");

    @ArchTest
    static final ArchRule commandsMustNotReachPersistence = noClasses()
            .that().resideInAPackage("..command..")
            .should().dependOnClassesThat().resideInAPackage("..persistence..")
            .because("commands talk to services, never to repositories");

    @ArchTest
    static final ArchRule servicesMustNotReachTheUi = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..ui..")
            .because("the dependency runs from ui to service, never back");

    /// Scoped to the business layers rather than "outside ..platform.." - the composition
    /// root (PloceusPlugin, in the root package) must call MultiverseCoreApi.whenLoaded,
    /// and PloceusModule (in ..di..) must bind the API instance. The intent is to keep
    /// Multiverse out of the business layers, not out of the composition root.
    @ArchTest
    static final ArchRule onlyPlatformTouchesMultiverse = noClasses()
            .that().resideInAnyPackage("..domain..", "..persistence..", "..service..", "..ui..", "..command..")
            .should().dependOnClassesThat().resideInAPackage("org.mvplugins..")
            .because("Multiverse stays behind the platform adapter; only the composition root may see it");

    @ArchTest
    static final ArchRule vavrStaysInsidePlatform = noClasses()
            .that().resideInAnyPackage("..domain..", "..persistence..", "..service..", "..ui..", "..command..")
            .should().dependOnClassesThat().resideInAPackage("org.mvplugins.multiverse.external..")
            .because("shaded Vavr must not leak out of the platform adapter");

    @ArchTest
    static final ArchRule dialogChannelIsSelfContained = noClasses()
            .that().resideInAPackage("..ui.dialog..")
            .should().dependOnClassesThat().resideInAnyPackage("..ui.inventory..", "..ui.chat..")
            .because("the three channels must stay interchangeable");

    @ArchTest
    static final ArchRule inventoryChannelIsSelfContained = noClasses()
            .that().resideInAPackage("..ui.inventory..")
            .should().dependOnClassesThat().resideInAnyPackage("..ui.dialog..", "..ui.chat..")
            .because("the three channels must stay interchangeable");

    @ArchTest
    static final ArchRule chatChannelIsSelfContained = noClasses()
            .that().resideInAPackage("..ui.chat..")
            .should().dependOnClassesThat().resideInAnyPackage("..ui.dialog..", "..ui.inventory..")
            .because("the three channels must stay interchangeable");
}
