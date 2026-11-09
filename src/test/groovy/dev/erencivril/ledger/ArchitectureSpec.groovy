package dev.erencivril.ledger

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/** Keeps the layering honest: the domain knows nothing about frameworks, the application nothing about adapters. */
class ArchitectureSpec extends Specification {

    @Shared
    def classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages('dev.erencivril.ledger')

    def "the domain depends only on the JDK and itself"() {
        expect:
        noClasses().that().resideInAPackage('..ledger.domain..')
                .should().dependOnClassesThat()
                .resideOutsideOfPackages('..ledger.domain..', 'java..')
                .allowEmptyShould(true).check(classes)
    }

    def "the application layer does not know about persistence, HTTP or cache adapters"() {
        expect:
        noClasses().that().resideInAPackage('..ledger.application..')
                .should().dependOnClassesThat()
                .resideInAnyPackage('..ledger.persistence..', '..ledger.http..', '..ledger.cache..', '..ledger.risk..', 'org.jooq..', 'spark..', 'redis..', 'com.zaxxer..')
                .allowEmptyShould(true).check(classes)
    }

    def "adapters do not depend on each other"() {
        expect:
        noClasses().that().resideInAPackage('..ledger.persistence..')
                .should().dependOnClassesThat().resideInAnyPackage('..ledger.http..', '..ledger.cache..')
                .allowEmptyShould(true).check(classes)
        noClasses().that().resideInAPackage('..ledger.cache..')
                .should().dependOnClassesThat().resideInAnyPackage('..ledger.http..', '..ledger.persistence..')
                .allowEmptyShould(true).check(classes)
        noClasses().that().resideInAPackage('..ledger.http..')
                .should().dependOnClassesThat().resideInAnyPackage('..ledger.persistence..', '..ledger.cache..', 'org.jooq..')
                .allowEmptyShould(true).check(classes)
    }
}
