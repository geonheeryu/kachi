package me.rgunny.kachi.notification.routing.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * notification-routing의 계층·배치 규칙(ADR 002, 패키지구조.md)을 검증하는 ArchUnit 테스트.
 */
@AnalyzeClasses(
    packages = ["me.rgunny.kachi.notification.routing"],
    importOptions = [ImportOption.DoNotIncludeTests::class, ImportOption.DoNotIncludeJars::class]
)
class ArchitectureTest {

    companion object {

        @ArchTest
        @JvmField
        val layered_architecture: ArchRule = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Domain").definedBy("..routing.domain..")
            .layer("Application").definedBy("..routing.application..")
            .layer("Adapter").definedBy("..routing.adapter..")
            .layer("Config").definedBy("..routing.config..")
            .whereLayer("Adapter").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapter", "Config")

        @ArchTest
        @JvmField
        val domain_does_not_depend_on_outer_layers: ArchRule = noClasses()
            .that().resideInAPackage("..routing.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..routing.application..", "..routing.adapter..", "..routing.config..")

        @ArchTest
        @JvmField
        val application_service_depends_only_on_ports_not_adapters: ArchRule = noClasses()
            .that().resideInAPackage("..routing.application.service..")
            .should().dependOnClassesThat()
            .resideInAPackage("..routing.adapter..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val application_ports_are_independent: ArchRule = noClasses()
            .that().resideInAPackage("..routing.application.port..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..routing.application.service..", "..routing.adapter..")

        @ArchTest
        @JvmField
        val application_does_not_depend_on_config: ArchRule = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..config..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val inbound_adapter_does_not_depend_on_outbound_adapter: ArchRule = noClasses()
            .that().resideInAPackage("..routing.adapter.inbound..")
            .should().dependOnClassesThat()
            .resideInAPackage("..routing.adapter.outbound..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val inbound_adapters_do_not_implement_outbound_ports: ArchRule = noClasses()
            .that().resideInAPackage("..routing.adapter.inbound..")
            .should().implement(JavaClass.Predicates.resideInAPackage("..routing.application.port.outbound.."))
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val outbound_adapters_do_not_depend_on_each_other: ArchRule = slices()
            .matching("..routing.adapter.outbound.(*)..")
            .should().notDependOnEachOther()

        @ArchTest
        @JvmField
        val outbound_adapter_packages_are_named_after_ports: ArchRule = classes()
            .that().resideInAPackage("..routing.adapter.outbound..")
            .should().resideInAnyPackage(
                "..routing.adapter.outbound.job..",
                "..routing.adapter.outbound.messaging..",
                "..routing.adapter.outbound.recipient..",
                "..routing.adapter.outbound.persistence..",
            )
            .because("adapter/outbound 1단은 그 어댑터가 구현하는 포트 패키지의 이름이다. 포트가 다른 어댑터들이 같은 구현 클래스를 함께 쓸 때만 기술 이름을 쓴다 (docs/패키지구조.md)")

        @ArchTest
        @JvmField
        val no_package_cycles: ArchRule = slices()
            .matching("me.rgunny.kachi.notification.routing.(*)..")
            .should().beFreeOfCycles()

        @ArchTest
        @JvmField
        val domain_is_free_of_spring_stereotypes: ArchRule = noClasses()
            .that().resideInAPackage("..routing.domain..")
            .should().beAnnotatedWith("org.springframework.stereotype.Component")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Repository")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Controller")
            .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")

        @ArchTest
        @JvmField
        val outbound_port_roots_hold_only_interfaces: ArchRule = classes()
            .that().resideInAPackage("..routing.application.port.outbound.*")
            .and().areTopLevelClasses()
            .should().beInterfaces()

        @ArchTest
        @JvmField
        val inbound_port_roots_hold_only_interfaces: ArchRule = classes()
            .that().resideInAPackage("..routing.application.port.inbound.*")
            .and().areTopLevelClasses()
            .should().beInterfaces()

        @ArchTest
        @JvmField
        val mongo_documents_only_in_persistence_adapter: ArchRule = classes()
            .that().areAnnotatedWith("org.springframework.data.mongodb.core.mapping.Document")
            .should().resideInAPackage("..routing.adapter.outbound.persistence..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val configuration_properties_only_in_config: ArchRule = classes()
            .that().areAnnotatedWith("org.springframework.boot.context.properties.ConfigurationProperties")
            .should().resideInAPackage("..routing.config..")
            .allowEmptyShould(true)
    }
}
