package gsb.web;

import com.sun.net.httpserver.HttpExchange;
import gsb.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class Http {
    private Http() {}

    static byte[] body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            return bos.toByteArray();
        }
    }

    static Map<String, Object> jsonBody(HttpExchange ex) throws IOException {
        byte[] b = body(ex);
        if (b.length == 0) {
            return Json.obj();
        }
        try {
            return Json.parseObject(new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw gsb.store.ApiException.badRequest("BAD_JSON",
                    "request body must be JSON: " + e.getMessage());
        }
    }

    static void writeJson(HttpExchange ex, int status, Object value) throws IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void writeBytes(HttpExchange ex, int status, String contentType, byte[] bytes)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String query(HttpExchange ex, String key, String dflt) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) {
            return dflt;
        }
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            if (k.equals(key)) {
                return i < 0 ? "" : java.net.URLDecoder.decode(pair.substring(i + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return dflt;
    }

    static String actor(HttpExchange ex) {
        List<String> who = ex.getRequestHeaders().get("X-Actor");
        return who == null || who.isEmpty() ? "anonymous" : who.get(0);
    }
}
