package dev.erencivril.ledger.risk

import dev.erencivril.ledger.domain.risk.RiskAction
import spock.lang.Specification

class RuleSetYamlSpec extends Specification {

    static final String VALID = '''
version: 3
description: Test set
stack:
  warnings: 2
  stepUps: 3
rules:
  - id: FIRST_PAYMENT_LARGE
    action: HOLD
    params:
      minAmountUsd: 1000
  - id: AMOUNT_DEVIATION
    action: STEP_UP
    enabled: false
    params:
      factor: 2.5
      minHistory: 3
'''

    def "it reads a rule set: version, stacking, rules in order, params as decimals, enabled flag"() {
        when:
        def parsed = RuleSetYaml.parse(VALID)
        def set = parsed.ruleSet()

        then:
        set.version() == 3
        set.description() == 'Test set'
        set.stackWarnings() == 2
        set.stackStepUps() == 3
        set.rules()*.id() == ['FIRST_PAYMENT_LARGE', 'AMOUNT_DEVIATION']
        set.rules()[0].action() == RiskAction.HOLD
        set.rules()[0].enabled()
        set.rules()[0].param('minAmountUsd') == new BigDecimal('1000')
        !set.rules()[1].enabled()
        set.rules()[1].param('factor') == new BigDecimal('2.5')
    }

    def "the hash is the SHA-256 of the text, so any edit changes it"() {
        expect:
        RuleSetYaml.parse(VALID).sha256() == RuleSetYaml.parse(VALID).sha256()
        RuleSetYaml.parse(VALID).sha256().length() == 64
        RuleSetYaml.parse(VALID + '# edit').sha256() != RuleSetYaml.parse(VALID).sha256()
        RuleSetYaml.parse('version: 1\ndescription: d\nrules: []\n').sha256() ==
                java.security.MessageDigest.getInstance('SHA-256').digest('version: 1\ndescription: d\nrules: []\n'.getBytes('UTF-8')).encodeHex().toString()
    }

    def "stack is optional and means no stacking"() {
        when:
        def set = RuleSetYaml.parse('version: 1\ndescription: d\nrules: []\n').ruleSet()

        then:
        set.stackWarnings() == 0
        set.stackStepUps() == 0
    }

    def "a rule without params or enabled gets the defaults"() {
        when:
        def rule = RuleSetYaml.parse('version: 1\ndescription: d\nrules:\n  - id: X\n    action: WARN\n').ruleSet().rules()[0]

        then:
        rule.enabled()
        rule.params().isEmpty()
    }

    def "bad input is rejected with a message that says what is wrong: #why"() {
        when:
        RuleSetYaml.parse(text)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(fragment)

        where:
        why                        | fragment             | text
        'not a mapping'            | 'mapping'            | '- a\n- b\n'
        'empty document'           | 'mapping'            | ''
        'broken yaml'              | 'YAML'               | 'version: [1\n'
        'unknown top-level key'    | "Unknown key 'versoin'" | 'versoin: 1\ndescription: d\nrules: []\n'
        'missing version'          | 'version'            | 'description: d\nrules: []\n'
        'version is text'          | 'version'            | 'version: one\ndescription: d\nrules: []\n'
        'version is a fraction'    | 'version'            | 'version: 1.5\ndescription: d\nrules: []\n'
        'missing description'       | 'description'        | 'version: 1\nrules: []\n'
        'rules is not a list'      | "'rules' must be a list" | 'version: 1\ndescription: d\nrules: {}\n'
        'rules missing'            | "'rules' must be a list" | 'version: 1\ndescription: d\n'
        'unknown rule key'         | "Unknown key 'actoin'" | 'version: 1\ndescription: d\nrules:\n  - id: X\n    actoin: WARN\n'
        'unknown stack key'        | "Unknown key 'warning'" | 'version: 1\ndescription: d\nstack: {warning: 1}\nrules: []\n'
        'stack is not a mapping'   | "'stack' must be a mapping" | 'version: 1\ndescription: d\nstack: 3\nrules: []\n'
        'rule is not a mapping'    | 'a rule must be a mapping' | 'version: 1\ndescription: d\nrules:\n  - X\n'
        'unknown action'           | "unknown action 'EXPLODE'" | 'version: 1\ndescription: d\nrules:\n  - id: X\n    action: EXPLODE\n'
        'missing action'           | 'action'             | 'version: 1\ndescription: d\nrules:\n  - id: X\n'
        'ALLOW as action'          | 'ALLOW'              | 'version: 1\ndescription: d\nrules:\n  - id: X\n    action: ALLOW\n'
        'params not a mapping'     | "'params' must be a mapping" | 'version: 1\ndescription: d\nrules:\n  - id: X\n    action: WARN\n    params: 5\n'
        'param not a number'       | "'minAmountUsd' must be a number" | 'version: 1\ndescription: d\nrules:\n  - id: X\n    action: WARN\n    params: {minAmountUsd: lots}\n'
        'same rule twice'          | 'twice'              | 'version: 1\ndescription: d\nrules:\n  - {id: X, action: WARN}\n  - {id: X, action: HOLD}\n'
    }

    def "the bundled version 1 file parses and builds an engine"() {
        given:
        def text = RuleSetYaml.classLoader.getResourceAsStream('risk/ruleset-v1.yaml').text

        when:
        def set = RuleSetYaml.parse(text).ruleSet()

        then:
        set.version() == 1
        set.rules()*.id() == ['FIRST_PAYMENT_LARGE', 'AMOUNT_DEVIATION', 'NEW_PAYEE_ACCOUNT']
        dev.erencivril.ledger.domain.risk.RuleCatalog.build(set).ruleSetVersion() == 1
    }
}
