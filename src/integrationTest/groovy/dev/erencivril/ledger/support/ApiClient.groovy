package dev.erencivril.ledger.support

import dev.erencivril.ledger.App
import dev.erencivril.ledger.Config
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Starts the whole application on a random port against the Testcontainers and talks to it over HTTP. */
class ApiClient implements Closeable {

    static class Response {
        int status
        Map<String, List<String>> headers
        String raw
        def getBody() { raw ? new JsonSlurper().parseText(raw) : null }
        String header(String name) { headers[name.toLowerCase()]?.first() }
    }

    final App app
    private final HttpClient client = HttpClient.newHttpClient()

    ApiClient(Map overrides = [:]) {
        def env = [
                PORT                    : '0',
                RISK_ENABLED            : 'false',
                DATABASE_URL            : Infra.jdbcUrl(),
                DATABASE_USER           : Infra.POSTGRES.username,
                DATABASE_PASSWORD       : Infra.POSTGRES.password,
                REDIS_URL               : Infra.redisUrl(),
                VELOCITY_MAX_TRANSFERS  : '1000',
        ] + overrides.collectEntries { k, v -> [(k as String): v as String] }
        app = App.start(Config.fromEnv(env))
    }

    Response get(String path) { send(HttpRequest.newBuilder(uri(path)).GET().build()) }

    Response post(String path, Object body, Map headers = [:]) {
        def text = body instanceof String ? body : JsonOutput.toJson(body)
        def builder = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(text))
        builder.header('Content-Type', 'application/json')
        headers.each { k, v -> builder.header(k as String, v as String) }
        send(builder.build())
    }

    String createAccount(String currency, String initial = null, String owner = 'tester') {
        def body = [owner: owner, currency: currency]
        if (initial != null) body.initialBalance = initial
        def r = post('/accounts', body)
        assert r.status == 201: r.raw
        r.body.id
    }

    Response transfer(String key, String from, String to, String amount, String currency = 'USD', boolean stepUpConfirmed = false) {
        def body = [sourceAccountId: from, destinationAccountId: to, amount: amount, currency: currency]
        if (stepUpConfirmed) body.stepUpConfirmed = true
        post('/transfers', body,
                key == null ? [:] : ['Idempotency-Key': key])
    }

    private URI uri(String path) { URI.create("http://localhost:${app.port()}${path}") }

    private Response send(HttpRequest request) {
        def r = client.send(request, HttpResponse.BodyHandlers.ofString())
        new Response(status: r.statusCode(), headers: r.headers().map(), raw: r.body())
    }

    @Override
    void close() { app.close() }
}
