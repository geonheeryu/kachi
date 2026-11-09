package me.rgunny.kachi.collector.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * collector-service의 계층·배치 규칙(ADR 002, 패키지구조.md)을 검증하는 ArchUnit 테스트.
 */
@AnalyzeClasses(
    packages = ["me.rgunny.kachi.collector"],
    importOptions = [ImportOption.DoNotIncludeTests::class, ImportOption.DoNotIncludeJars::class]
)
class ArchitectureTest {

    companion object {

        @ArchTest
        @JvmField
        val layered_architecture: ArchRule = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Adapter").definedBy("..adapter..")
            .layer("Config").definedBy("..config..")
            .whereLayer("Adapter").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapter", "Config")

        @ArchTest
        @JvmField
        val domain_does_not_depend_on_outer_layers: ArchRule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..application..", "..adapter..", "..config..")

        @ArchTest
        @JvmField
        val application_service_depends_only_on_ports_not_adapters: ArchRule = noClasses()
            .that().resideInAPackage("..application.service..")
            .should().dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val application_does_not_depend_on_config: ArchRule = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..config..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val application_ports_are_independent: ArchRule = noClasses()
            .that().resideInAPackage("..application.port..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..application.service..", "..adapter..")

        @ArchTest
        @JvmField
        val inbound_adapter_does_not_depend_on_outbound_adapter: ArchRule = noClasses()
            .that().resideInAPackage("..adapter.inbound..")
            .should().dependOnClassesThat()
            .resideInAPackage("..adapter.outbound..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val outbound_adapters_do_not_depend_on_each_other: ArchRule = slices()
            .matching("..adapter.outbound.(*)..")
            .should().notDependOnEachOther()

        @ArchTest
        @JvmField
        val outbound_adapter_packages_are_named_after_ports: ArchRule = classes()
            .that().resideInAPackage("..adapter.outbound..")
            .should().resideInAnyPackage(
                "..adapter.outbound.collection..",
                "..adapter.outbound.keyword..",
                "..adapter.outbound.lock..",
                "..adapter.outbound.news..",
                "..adapter.outbound.outbox..",
                "..adapter.outbound.persistence..",
            )
            .because("adapter/outbound 1단은 그 어댑터가 구현하는 포트 패키지의 이름이다. 포트가 다른 어댑터들이 같은 구현 클래스를 함께 쓸 때만 기술 이름을 쓴다 (docs/패키지구조.md)")

        @ArchTest
        @JvmField
        val no_package_cycles: ArchRule = slices()
            .matching("me.rgunny.kachi.collector.(*)..")
            .should().beFreeOfCycles()

        @ArchTest
        @JvmField
        val domain_is_free_of_spring_stereotypes: ArchRule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().beAnnotatedWith("org.springframework.stereotype.Component")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Repository")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Controller")
            .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")

        @ArchTest
        @JvmField
        val outbound_ports_are_interfaces: ArchRule = classes()
            .that().resideInAPackage("..application.port.outbound..")
            .and().haveSimpleNameEndingWith("Port")
            .should().beInterfaces()

        @ArchTest
        @JvmField
        val inbound_use_case_ports_are_interfaces: ArchRule = classes()
            .that().resideInAPackage("..application.port.inbound..")
            .and().haveSimpleNameEndingWith("UseCase")
            .should().beInterfaces()

        @ArchTest
        @JvmField
        val rest_controllers_only_in_web_inbound_adapter: ArchRule = classes()
            .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .should().resideInAPackage("..adapter.inbound.web..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val rest_controller_advice_only_in_web_inbound_adapter: ArchRule = classes()
            .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
            .should().resideInAPackage("..adapter.inbound.web..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val mongo_documents_only_in_persistence_adapter: ArchRule = classes()
            .that().areAnnotatedWith("org.springframework.data.mongodb.core.mapping.Document")
            .should().resideInAPackage("..adapter.outbound.persistence..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val mongo_repositories_only_in_persistence_adapter: ArchRule = classes()
            .that().haveSimpleNameEndingWith("MongoRepository")
            .should().resideInAPackage("..adapter.outbound.persistence..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val configuration_properties_only_in_config: ArchRule = classes()
            .that().areAnnotatedWith("org.springframework.boot.context.properties.ConfigurationProperties")
            .should().resideInAPackage("..config..")
            .allowEmptyShould(true)
    }
}
