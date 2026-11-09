.PHONY: build test integration mutation benchmark check up down smoke

build:        ## compile and generate the jOOQ classes
	./gradlew assemble

test:         ## unit specs (no Docker needed)
	./gradlew test

integration:  ## integration specs, starts PostgreSQL and Redis with Testcontainers
	./gradlew integrationTest

mutation:     ## PIT mutation testing on the domain package, report in build/reports/pitest
	./gradlew pitest

benchmark:   ## compare the three lock strategies (needs Docker, about 7 minutes), report in build/benchmark/report.md
	./gradlew benchmark

check:        ## unit + integration
	./gradlew build

up:           ## app + postgres + redis on http://localhost:8080
	docker compose up --build -d --wait

down:
	docker compose down -v

smoke: up     ## run scripts/smoke.sh against the compose stack
	./scripts/smoke.sh http://localhost:8080
