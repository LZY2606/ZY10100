package gsb;

import com.sun.net.httpserver.HttpServer;
import gsb.store.EventLog;
import gsb.store.Workspace;
import gsb.store.WorkspaceStore;
import gsb.web.ApiHandler;
import gsb.web.StaticHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

/**
 * Application entry point.
 *
 * <pre>
 *   ./gradlew run --args='--port 5223'
 *   ./gradlew run --args='--port 5223 --data ./data --recover'
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        int port = 5223;
        String dataDir = "data";
        boolean recover = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = args[++i];
                case "--recover" -> recover = true;
                case "--help" -> {
                    System.out.println("Usage: --port N --data DIR [--recover]");
                    return;
                }
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        Path root = Paths.get(dataDir).toAbsolutePath();
        WorkspaceStore store = new WorkspaceStore(root);
        System.out.println("[pair-wisegsb] data directory: " + root);

        if (recover) {
            int recovered = runRecovery(store);
            System.out.println("[pair-wisegsb] recovery removed " + recovered
                    + " corrupt/incomplete event-log line(s)");
        }

        try {
            for (String id : store.registry().keySet()) {
                try {
                    store.open(id);
                } catch (EventLog.ChainBrokenException e) {
                    System.err.println("[pair-wisegsb] FATAL: event chain broken in " + id
                            + " at seq " + e.seq + ": " + e.getMessage());
                    System.err.println("  Re-run with --recover to truncate after the last"
                            + " verified event (a .corrupt backup is created).");
                    throw e;
                }
            }
        } catch (EventLog.ChainBrokenException e) {
            System.exit(2);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/", new ApiHandler(store)::handle);
        StaticHandler staticHandler = new StaticHandler();
        server.createContext("/", staticHandler::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("[pair-wisegsb] listening on http://127.0.0.1:" + port);
    }

    private static int runRecovery(WorkspaceStore store) throws IOException {
        int total = 0;
        for (String id : store.registry().keySet()) {
            Workspace ws = new Workspace(id,
                    store.root().resolve("images").resolve(id));
            Path backup = ws.dir.resolve("events.log.corrupt."
                    + System.currentTimeMillis());
            int removed = ws.log.truncateAfterLastValid(backup);
            if (removed > 0) {
                System.out.println("[pair-wisegsb] " + id + ": removed " + removed
                        + " invalid lines; backup at " + backup);
            }
            total += removed;
        }
        return total;
    }
}
