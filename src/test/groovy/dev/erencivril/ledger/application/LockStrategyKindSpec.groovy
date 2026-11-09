package dev.erencivril.ledger.application

import spock.lang.Specification

class LockStrategyKindSpec extends Specification {

    def "names are parsed ignoring case and surrounding spaces"() {
        expect:
        LockStrategyKind.parse(text) == kind

        where:
        text            | kind
        'pessimistic'   | LockStrategyKind.PESSIMISTIC
        ' Optimistic '  | LockStrategyKind.OPTIMISTIC
        'ATOMIC'        | LockStrategyKind.ATOMIC
    }

    def "an unknown name is refused and the message lists the choices"() {
        when:
        LockStrategyKind.parse(text)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('pessimistic')
        e.message.contains('optimistic')
        e.message.contains('atomic')

        where:
        text << ['row', '', null]
    }

    def "each kind builds the matching strategy"() {
        expect:
        LockStrategyKind.valueOf(name).create(StrategySupport.planner()).class == type

        where:
        name          | type
        'PESSIMISTIC' | PessimisticLockStrategy
        'OPTIMISTIC'  | OptimisticVersionStrategy
        'ATOMIC'      | AtomicUpdateStrategy
    }
}
