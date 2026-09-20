package gsb.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Serves the single-page frontend from classpath:/static. */
public final class StaticHandler {
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            ex.sendResponseHeaders(400, -1);
            ex.close();
            return;
        }
        String resource = "/static" + path;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                try (InputStream fallback = getClass().getResourceAsStream("/static/index.html")) {
                    if (fallback == null) {
                        ex.sendResponseHeaders(404, -1);
                        ex.close();
                        return;
                    }
                    serve(ex, "text/html; charset=utf-8", fallback.readAllBytes());
                }
                return;
            }
            String ct = contentType(path);
            serve(ex, ct, in.readAllBytes());
        }
    }

    private void serve(HttpExchange ex, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
