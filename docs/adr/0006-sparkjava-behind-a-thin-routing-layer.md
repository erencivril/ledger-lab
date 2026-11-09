# 0006. SparkJava stays, behind a thin routing layer

Status: accepted, with a known exit path

## Context

SparkJava 2.9.4 was released in 2022 and has not had a release since. It is small and easy to read, which suits a project meant to be understood line by line, but an unmaintained HTTP library is a risk: no fixes for new vulnerabilities in the library itself, and it pulls an older Jetty.

## Decision

- Keep SparkJava for now.
- Keep it behind a thin layer. Only `LedgerRoutes` (and `App`, which starts the server) import `spark.*`. `LedgerRoutes` parses a request, calls a service, and renders JSON. It holds no business rule. Error mapping lives in `ApiError`, which does not know about SparkJava at all.
- An ArchUnit rule stops the `application` and `domain` packages from importing `spark..`, so the framework cannot leak inward.
- The exit path is **Javalin** (closest in shape, also a small lambda-based routing API, actively maintained) or **Helidon SE** (if a more explicit, no-annotation routing style is wanted). Moving means rewriting `LedgerRoutes` and the server start in `App`. Services, domain, persistence and the HTTP-level integration specs stay as they are, because those specs talk to the server over HTTP.

## Consequences

- The cost of leaving is one class plus a few lines, and the integration specs say when the replacement behaves the same.
- Until then the library's age is a known risk. The container image is scanned in CI (see [../../README.md](../../README.md)), and the scan did surface it: two Jetty 9.4 CVEs (CVE-2026-10050, CVE-2026-2332) are fixed only in commercial 9.4 builds. Jetty is pinned to 9.4.58, the last open-source 9.4 release, and the two findings are accepted in [`.trivyignore`](../../.trivyignore) with a reason and an expiry date (2027-02-28). If they are still open by then, the move to Javalin described above is the fix.
- Nothing in the project depends on SparkJava features beyond routing, `before`/`after` filters and exception mapping.

## Evidence

- [`ArchitectureSpec`](../../src/test/groovy/dev/erencivril/ledger/ArchitectureSpec.groovy): layering rules, including that `application` does not know `spark..`.
- [`ApiIntegrationSpec`](../../src/integrationTest/groovy/dev/erencivril/ledger/ApiIntegrationSpec.groovy): the whole HTTP contract, written against the running server and not against Spark classes.
