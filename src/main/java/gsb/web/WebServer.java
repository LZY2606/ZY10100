package gsb.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import gsb.service.AppKernel;
import gsb.service.ExportService;
import gsb.service.ValidationException;
import gsb.storage.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** JDK HTTP server hosting the static single-page UI and the JSON/PNG API. */
public final class WebServer {
    private final AppKernel kernel;
    private final int port;
    private HttpServer server;

    public WebServer(AppKernel kernel, int port) {
        this.kernel = kernel;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api", new ApiHandler(kernel));
        server.createContext("/api/import", new ImportHandler(kernel));
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int boundPort() {
        return server == null ? port : server.getAddress().getPort();
    }

    /** Re-import a previously exported ZIP into a new workspace. */
    static final class ImportHandler implements HttpHandler {
        private final AppKernel kernel;

        ImportHandler(AppKernel kernel) {
            this.kernel = kernel;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                if (!ex.getRequestMethod().equals("POST")) {
                    throw ValidationException.of("METHOD_NOT_ALLOWED", "use POST");
                }
                byte[] body = ex.getRequestBody().readAllBytes();
                JsonNode meta;
                byte[] zip;
                String contentType = ex.getRequestHeaders().getFirst("Content-Type");
                if (contentType != null && contentType.contains("application/json")) {
                    JsonNode wrapper = Json.MAPPER.readTree(body);
                    zip = java.util.Base64.getDecoder().decode(
                            wrapper.path("bundleBase64").asText());
                    meta = wrapper;
                } else {
                    zip = body;
                    meta = null;
                }
                String newId = meta == null ? "imported-" + System.currentTimeMillis()
                        : meta.path("newWorkspaceId").asText(
                                "imported-" + System.currentTimeMillis());
                String newName = meta == null ? newId : meta.path("name").asText(newId);

                gsb.service.WorkspaceService target =
                        new gsb.service.WorkspaceService(kernel.store(), kernel.clock());
                var report = new ExportService().reimport(target, newId, newName, zip);
                if (report.accepted()) {
                    kernel.register(target);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("accepted", report.accepted());
                out.put("workspaceId", report.workspaceId());
                out.put("sourcesRestored", report.sourcesRestored());
                out.put("eventsRestored", report.eventsRestored());
                out.put("regionPixels", report.regionPixels());
                out.put("boundaryHash", report.boundaryHash());
                out.put("problems", report.problems());
                out.put("warnings", report.warnings());
                HttpSupport.writeJson(ex, report.accepted() ? 201 : 409, out);
            } catch (ValidationException e) {
                HttpSupport.error(ex, 400, e.code, e.getMessage(), e.reasons);
            }
        }
    }

    /** Serves resources/web/index.html and assets from the classpath. */
    static final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.isBlank()) {
                path = "/web/index.html";
            } else {
                path = "/web" + path;
            }
            if (path.contains("..")) {
                HttpSupport.error(ex, 400, "BAD_PATH", "illegal path", java.util.List.of());
                return;
            }
            try (InputStream in = WebServer.class.getResourceAsStream(path)) {
                if (in == null) {
                    HttpSupport.error(ex, 404, "NOT_FOUND", path, java.util.List.of());
                    return;
                }
                byte[] data = in.readAllBytes();
                String type = contentType(path);
                ex.getResponseHeaders().add("Content-Type", type);
                ex.sendResponseHeaders(200, data.length);
                ex.getResponseBody().write(data);
                ex.close();
            }
        }

        private static String contentType(String path) {
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
}
