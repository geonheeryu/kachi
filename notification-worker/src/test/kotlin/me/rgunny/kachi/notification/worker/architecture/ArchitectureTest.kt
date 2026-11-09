package me.rgunny.kachi.notification.worker.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * notification-worker의 배치 규칙을 검증하는 ArchUnit 테스트.
 *
 * 검사 범위는 adapter가 notification-core의 포트에만 의존하는지와 클래스 배치다.
 */
@AnalyzeClasses(
    packages = ["me.rgunny.kachi.notification.worker"],
    importOptions = [ImportOption.DoNotIncludeTests::class, ImportOption.DoNotIncludeJars::class]
)
class ArchitectureTest {

    companion object {

        @ArchTest
        @JvmField
        val adapters_depend_on_core_ports_not_service_implementations: ArchRule = noClasses()
            .that().resideInAPackage("..worker.adapter..")
            .should().dependOnClassesThat()
            .resideInAPackage("me.rgunny.kachi.notification.application.service..")

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
            .that().resideInAPackage("..worker.adapter.inbound..")
            .should().dependOnClassesThat()
            .resideInAPackage("..worker.adapter.outbound..")

        @ArchTest
        @JvmField
        val inbound_adapters_do_not_implement_outbound_ports: ArchRule = noClasses()
            .that().resideInAPackage("..worker.adapter.inbound..")
            .should().implement(JavaClass.Predicates.resideInAPackage("..notification.application.port.outbound.."))
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val outbound_adapters_do_not_depend_on_each_other: ArchRule = slices()
            .matching("..worker.adapter.outbound.(*)..")
            .should().notDependOnEachOther()

        @ArchTest
        @JvmField
        val outbound_adapter_packages_are_named_after_ports: ArchRule = classes()
            .that().resideInAPackage("..worker.adapter.outbound..")
            .should().resideInAnyPackage(
                "..worker.adapter.outbound.dlt..",
                "..worker.adapter.outbound.idempotency..",
                "..worker.adapter.outbound.notification..",
                "..worker.adapter.outbound.outbox..",
                "..worker.adapter.outbound.recipient..",
                "..worker.adapter.outbound.sender..",
                "..worker.adapter.outbound.persistence..",
            )
            .because("adapter/outbound 1단은 그 어댑터가 구현하는 포트 패키지의 이름이다. 포트가 다른 어댑터들이 같은 구현 클래스를 함께 쓸 때만 기술 이름을 쓴다 (docs/패키지구조.md)")

        @ArchTest
        @JvmField
        val no_package_cycles: ArchRule = slices()
            .matching("me.rgunny.kachi.notification.worker.(*)..")
            .should().beFreeOfCycles()

        @ArchTest
        @JvmField
        val mongo_documents_only_in_persistence_adapter: ArchRule = classes()
            .that().areAnnotatedWith("org.springframework.data.mongodb.core.mapping.Document")
            .should().resideInAPackage("..worker.adapter.outbound.persistence..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val configuration_properties_only_in_config: ArchRule = classes()
            .that().areAnnotatedWith("org.springframework.boot.context.properties.ConfigurationProperties")
            .should().resideInAPackage("..worker.config..")
            .allowEmptyShould(true)
    }
}
