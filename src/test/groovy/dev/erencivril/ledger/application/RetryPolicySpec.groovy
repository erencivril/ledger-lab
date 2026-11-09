package dev.erencivril.ledger.application

import spock.lang.Specification

class RetryPolicySpec extends Specification {

    def "the ceiling doubles with every failed attempt and stops at the cap"() {
        expect:
        new RetryPolicy(10, 5).ceilingMillis(failed) == ceiling

        where:
        failed | ceiling
        1      | 5
        2      | 10
        3      | 20
        4      | 40
        8      | 640
        9      | 1000
        30     | 1000
    }

    def "the jitter is a random wait between zero and the ceiling, both included: it asks for a value below ceiling + 1"() {
        given: 'a random source that records the bound it was given and answers with a fixed number'
        def boundAskedFor = []
        def random = new Random() {
            @Override
            long nextLong(long bound) {
                boundAskedFor << bound
                return 4L
            }
        }

        expect:
        new RetryPolicy(3, 5).jitterMillis(2, random) == 4L
        boundAskedFor == [11L]   // the ceiling after two failures is 10, so the answer is one of 0..10
    }

    def "a policy needs at least one attempt and a positive base"() {
        when:
        new RetryPolicy(attempts, base)

        then:
        thrown(IllegalArgumentException)

        where:
        attempts | base
        0        | 5
        -1       | 5
        3        | 0
        3        | -2
    }

    def "one attempt means no retries"() {
        expect:
        new RetryPolicy(1, 5).maxAttempts() == 1
    }
}
