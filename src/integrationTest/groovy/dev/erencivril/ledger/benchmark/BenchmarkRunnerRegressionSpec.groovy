package dev.erencivril.ledger.benchmark

import spock.lang.Specification
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntConsumer

class BenchmarkRunnerRegressionSpec extends Specification {
    def "unexpected worker exceptions abort the runner instead of printing a partial report"() {
        when:
        BenchmarkMain.runWorkers(20, 3, { int i -> throw new IllegalStateException('injected worker failure') } as IntConsumer)

        then:
        def e = thrown(IllegalStateException)
        e.cause instanceof IllegalStateException
        e.cause.message == 'injected worker failure'
    }

    def "the runner processes every requested operation exactly once"() {
        given:
        def counts = new java.util.concurrent.atomic.AtomicIntegerArray(37)

        when:
        BenchmarkMain.runWorkers(37, 4, { int i -> counts.incrementAndGet(i) } as IntConsumer)

        then:
        (0..<37).every { counts.get(it) == 1 }
    }
}
