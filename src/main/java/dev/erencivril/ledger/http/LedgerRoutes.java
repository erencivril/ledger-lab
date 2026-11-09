package dev.erencivril.ledger.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import dev.erencivril.ledger.application.AccountService;
import dev.erencivril.ledger.application.HoldService;
import dev.erencivril.ledger.application.Ports.HealthProbe;
import dev.erencivril.ledger.application.TransferService;
import dev.erencivril.ledger.application.TransferService.TransferCommand;
import dev.erencivril.ledger.domain.Account;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.Transfer;
import dev.erencivril.ledger.domain.TransferStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

/** The HTTP adapter: parses requests, calls the services, renders JSON. No business rules live here. */
public final class LedgerRoutes {

    private static final Logger log = LoggerFactory.getLogger(LedgerRoutes.class);
    private static final String JSON = "application/json";
    private static final int MAX_KEY_LENGTH = 255;

    private final AccountService accounts;
    private final TransferService transfers;
    private final HoldService holds;
    private final HealthProbe postgres;
    private final HealthProbe redis;

    public LedgerRoutes(
            AccountService accounts,
            TransferService transfers,
            HoldService holds,
            HealthProbe postgres,
            HealthProbe redis) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.holds = holds;
        this.postgres = postgres;
        this.redis = redis;
    }

    public void register(Service http) {
        http.before((req, res) -> req.attribute("startedAt", System.nanoTime()));
        http.after((req, res) -> res.type(JSON));
        http.afterAfter((req, res) -> log.info(
                "{} {} -> {} in {} ms",
                req.requestMethod(),
                req.pathInfo(),
                res.status(),
                (System.nanoTime() - (Long) req.attribute("startedAt")) / 1_000_000));

        http.post("/accounts", this::createAccount);
        http.get("/accounts/:id", this::getAccount);
        http.get("/accounts/:id/statement", this::statement);
        http.post("/transfers", this::createTransfer);
        http.get("/transfers/:id", this::getTransfer);
        http.post("/transfers/:id/release", this::releaseTransfer);
        http.post("/transfers/:id/reject", this::rejectTransfer);
        http.get("/health", this::health);

        http.exception(Exception.class, (error, req, res) -> render(res, error));
        http.notFound((req, res) -> {
            res.type(JSON);
            return Json.write(new ErrorBody(
                    "route_not_found", "No route for " + req.requestMethod() + " " + req.pathInfo(), null));
        });
    }

    private String createAccount(Request req, Response res) {
        JsonNode body = Json.readObject(req.body());
        String owner = Json.requiredText(body, "owner");
        Currency currency = Currency.parse(Json.requiredText(body, "currency"));
        BigDecimal initial = Json.optionalDecimal(body, "initialBalance");
        Money opening = initial == null ? null : new Money(initial, currency);

        Account account = accounts.open(owner, currency, opening);
        res.status(201);
        res.header("Location", "/accounts/" + account.id());
        return Json.write(Views.AccountView.of(account));
    }

    private String getAccount(Request req, Response res) {
        UUID id = Json.parseUuid(req.params(":id"), "id");
        return Json.write(Views.AccountView.of(accounts.get(id)));
    }

    private String statement(Request req, Response res) {
        UUID id = Json.parseUuid(req.params(":id"), "id");
        Integer limit = intParam(req, "limit", 1, AccountService.MAX_PAGE_SIZE);
        Long cursor = longParam(req, "cursor");
        return Json.write(Views.StatementView.of(id, accounts.statement(id, limit, cursor)));
    }

    private String createTransfer(Request req, Response res) {
        String key = req.headers("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new BadRequest("missing_idempotency_key", "The Idempotency-Key header is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BadRequest("invalid_idempotency_key", "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        JsonNode body = Json.readObject(req.body());
        UUID source = Json.requiredUuid(body, "sourceAccountId");
        UUID destination = Json.requiredUuid(body, "destinationAccountId");
        Currency currency = Currency.parse(Json.requiredText(body, "currency"));
        Money amount = new Money(Json.requiredDecimal(body, "amount"), currency);
        boolean stepUpConfirmed = Json.optionalBoolean(body, "stepUpConfirmed");

        TransferService.TransferResult result =
                transfers.transfer(new TransferCommand(key, source, destination, amount, stepUpConfirmed));
        // 202: the request was understood and the money has left the sender, but the payee is not paid yet.
        boolean held = result.transfer().status() == TransferStatus.PENDING;
        res.status(result.replayed() ? 200 : held ? 202 : 201);
        res.header("Idempotency-Replayed", String.valueOf(result.replayed()));
        res.header("Location", "/transfers/" + result.transfer().id());
        return Json.write(Views.TransferView.of(result.transfer(), result.risk()));
    }

    private String getTransfer(Request req, Response res) {
        UUID id = Json.parseUuid(req.params(":id"), "id");
        return Json.write(Views.TransferView.of(transfers.get(id), transfers.riskOf(id).orElse(null)));
    }

    private String releaseTransfer(Request req, Response res) {
        UUID id = Json.parseUuid(req.params(":id"), "id");
        Transfer released = holds.release(id);
        return Json.write(Views.TransferView.of(released, transfers.riskOf(id).orElse(null)));
    }

    private String rejectTransfer(Request req, Response res) {
        UUID id = Json.parseUuid(req.params(":id"), "id");
        Transfer rejected = holds.reject(id);
        return Json.write(Views.TransferView.of(rejected, transfers.riskOf(id).orElse(null)));
    }

    /** 200 while Postgres is up. Redis being down is reported but does not fail the check: transfers still work. */
    private String health(Request req, Response res) {
        boolean pgUp = postgres.isUp();
        boolean redisUp = redis.isUp();
        res.status(pgUp ? 200 : 503);
        return Json.write(new Views.HealthView(
                pgUp ? (redisUp ? "up" : "degraded") : "down", pgUp ? "up" : "down", redisUp ? "up" : "down"));
    }

    private void render(Response res, Throwable error) {
        ApiError api = ApiError.from(error);
        if (api.status() >= 500) {
            log.error("Unhandled error", error);
        }
        res.status(api.status());
        res.type(JSON);
        if (api.retryAfterSeconds() != null) {
            res.header("Retry-After", String.valueOf(api.retryAfterSeconds()));
        }
        res.body(Json.write(new ErrorBody(api.code(), api.message(), api.reasonCodes())));
    }

    /** {@code reasonCodes} is only present on risk refusals. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorBody(String code, String message, List<String> reasonCodes) {}

    private static Integer intParam(Request req, String name, int min, int max) {
        String raw = req.queryParams(name);
        if (raw == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw new BadRequest("invalid_" + name, "'" + name + "' must be an integer from " + min + " to " + max);
    }

    private static Long longParam(Request req, String name) {
        String raw = req.queryParams(name);
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(raw);
            if (value >= 1) {
                return value;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw new BadRequest("invalid_" + name, "'" + name + "' must be a positive integer");
    }
}
