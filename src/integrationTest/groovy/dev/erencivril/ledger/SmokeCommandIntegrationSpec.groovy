package dev.erencivril.ledger

import dev.erencivril.ledger.support.ApiClient
import spock.lang.Specification
import java.util.concurrent.TimeUnit

class SmokeCommandIntegrationSpec extends Specification {
    def "asserting smoke command passes against the real HTTP application and databases"() {
        given:
        def api = new ApiClient(RISK_ENABLED: true)

        when:
        def process = new ProcessBuilder('./scripts/smoke.sh', "http://localhost:${api.app.port()}".toString())
                .redirectErrorStream(true).start()
        assert process.waitFor(60, TimeUnit.SECONDS)
        def output = process.inputStream.text

        then:
        assert process.exitValue() == 0: output
        output.contains('SMOKE PASSED')

        cleanup:
        api.close()
    }
}
