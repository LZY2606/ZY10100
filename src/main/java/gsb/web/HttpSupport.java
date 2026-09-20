package gsb.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import gsb.service.ValidationException;
import gsb.storage.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small helpers for the JDK HTTP server used by all routes. */
final class HttpSupport {
    private HttpSupport() {}

    static JsonNode readJson(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            throw ValidationException.of("EMPTY_BODY", "request body is required");
        }
        return Json.MAPPER.readTree(body);
    }

    static void writeJson(HttpExchange ex, int status, Object value) throws IOException {
        byte[] data = Json.write(value);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    static void writeBytes(HttpExchange ex, int status, String contentType, byte[] data)
            throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    static void error(HttpExchange ex, int status, String code, String message,
                      Iterable<String> reasons) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("reasons", reasons);
        writeJson(ex, status, body);
    }

    static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw ValidationException.of("MISSING_FIELD", "field '" + field + "' is required");
        }
        return value.asText();
    }
}
