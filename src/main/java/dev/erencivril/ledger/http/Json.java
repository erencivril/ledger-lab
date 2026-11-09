package dev.erencivril.ledger.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.util.UUID;

/** JSON in and out. Amounts are read as BigDecimal straight from the text, never through a double. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json() {}

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode readObject(String body) {
        if (body == null || body.isBlank()) {
            throw new BadRequest("invalid_json", "Request body is required");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new BadRequest("invalid_json", "Request body is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new BadRequest("invalid_json", "Request body must be a JSON object");
        }
        return node;
    }

    static String requiredText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw new BadRequest("missing_field", "'" + field + "' is required");
        }
        if (!node.isTextual()) {
            throw new BadRequest("invalid_field", "'" + field + "' must be a string");
        }
        return node.asText();
    }

    static UUID requiredUuid(JsonNode body, String field) {
        return parseUuid(requiredText(body, field), field);
    }

    static UUID parseUuid(String text, String name) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new BadRequest("invalid_id", "'" + name + "' is not a valid UUID");
        }
    }

    /** Accepts a JSON string ("10.50") or number (10.50). Strings are the safer choice for clients. */
    static BigDecimal requiredDecimal(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw new BadRequest("missing_field", "'" + field + "' is required");
        }
        return decimal(node, field);
    }

    static BigDecimal optionalDecimal(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : decimal(node, field);
    }

    static boolean optionalBoolean(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new BadRequest("invalid_field", "'" + field + "' must be true or false");
        }
        return node.asBoolean();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().strip());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw new BadRequest("invalid_amount", "'" + field + "' must be a decimal number");
    }
}
