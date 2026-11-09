package me.rgunny.kachi.notification.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * notification-core의 계층 규칙을 검증하는 ArchUnit 테스트.
 *
 * 검사 범위는 도메인의 외부 의존, 포트의 형태, 패키지 순환이다.
 */
@AnalyzeClasses(
    packages = ["me.rgunny.kachi.notification"],
    importOptions = [ImportOption.DoNotIncludeTests::class, ImportOption.DoNotIncludeJars::class]
)
class ArchitectureTest {

    companion object {

        @ArchTest
        @JvmField
        val domain_does_not_depend_on_application: ArchRule = noClasses()
            .that().resideInAPackage("..notification.domain..")
            .should().dependOnClassesThat()
            .resideInAPackage("..notification.application..")

        @ArchTest
        @JvmField
        val application_ports_are_independent_of_services: ArchRule = noClasses()
            .that().resideInAPackage("..notification.application.port..")
            .should().dependOnClassesThat()
            .resideInAPackage("..notification.application.service..")

        @ArchTest
        @JvmField
        val application_does_not_depend_on_config: ArchRule = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..config..")
            .allowEmptyShould(true)

        @ArchTest
        @JvmField
        val no_package_cycles: ArchRule = slices()
            .matching("me.rgunny.kachi.notification.(*)..")
            .should().beFreeOfCycles()

        @ArchTest
        @JvmField
        val core_is_free_of_spring_stereotypes: ArchRule = noClasses()
            .that().resideInAnyPackage("..notification.domain..", "..notification.application..")
            .should().beAnnotatedWith("org.springframework.stereotype.Component")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Repository")

        @ArchTest
        @JvmField
        val outbound_port_roots_hold_only_interfaces: ArchRule = classes()
            .that().resideInAPackage("..notification.application.port.outbound.*")
            .and().areTopLevelClasses()
            .should().beInterfaces()

        @ArchTest
        @JvmField
        val inbound_port_roots_hold_only_interfaces: ArchRule = classes()
            .that().resideInAPackage("..notification.application.port.inbound.*")
            .and().areTopLevelClasses()
            .should().beInterfaces()
    }
}
