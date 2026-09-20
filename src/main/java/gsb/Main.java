package gsb;

import gsb.service.AppKernel;
import gsb.web.WebServer;

import java.nio.file.Path;
import java.time.Clock;

/** Application entry point. Usage: run --args='--port 5223 [--data ./data]'. */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = 5223;
        Path dataDir = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: --port <n> [--data <dir>]");
                    System.exit(2);
                }
            }
        }
        AppKernel kernel = new AppKernel(dataDir, Clock.systemUTC());
        WebServer server = new WebServer(kernel, port);
        server.start();
        System.out.println("GSB consensus server listening on http://127.0.0.1:"
                + server.boundPort());
        System.out.println("data directory: " + dataDir.toAbsolutePath());
        for (String id : kernel.workspaceIds()) {
            System.out.println("restored workspace: " + id);
        }
    }
}
