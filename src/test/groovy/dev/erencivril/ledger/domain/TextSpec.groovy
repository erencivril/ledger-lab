package dev.erencivril.ledger.domain

import spock.lang.Specification

class TextSpec extends Specification {
    def "well-formed UTF-16 including optional values is accepted unchanged"() {
        when:
        Text.requireWellFormed(value, 'payeeName')

        then:
        noExceptionThrown()

        where:
        value << [null, '', ' ', 'ASCII?', 'Çağrı 李', 'e' + (char) 0x0301,
                  String.valueOf((char) 0xd7ff), String.valueOf((char) 0xe000),
                  new String(Character.toChars(0x10000)), new String(Character.toChars(0x10ffff)),
                  'A' + new String(Character.toChars(0x1f600)) + 'Z',
                  new String(Character.toChars(0x1f600)) + new String(Character.toChars(0x1f601))]
    }

    def "unpaired surrogates fail with a stable domain error"() {
        when:
        Text.requireWellFormed(value, 'payeeName')

        then:
        def error = thrown(DomainException.InvalidText)
        error.code() == 'invalid_text'
        error.message == "'payeeName' must be well-formed Unicode"

        where:
        value << [String.valueOf((char) 0xd800), String.valueOf((char) 0xdbff),
                  String.valueOf((char) 0xdc00), String.valueOf((char) 0xdfff),
                  'A' + (char) 0xd800 + 'Z', 'A' + (char) 0xdc00 + 'Z',
                  '' + (char) 0xd800 + (char) 0xd800,
                  '' + (char) 0xdc00 + (char) 0xd800,
                  new String(Character.toChars(0x1f600)) + (char) 0xd800,
                  new String(Character.toChars(0x1f600)) + (char) 0xdc00]
    }
}
