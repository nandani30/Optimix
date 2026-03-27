package com.optimix;

import com.optimix.api.Router;
import com.optimix.config.AppConfig;
import com.optimix.config.DatabaseConfig;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optimix Backend — Entry Point
 *
 * Starts the Javalin REST API server on localhost:7070.
 * The Electron frontend spawns this JAR as a child process.
 *
 * Run: java -jar target/optimix-backend-1.0.0.jar
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("=======================================");
        log.info("  Optimix Backend v1.0.0 starting...");
        log.info("=======================================");

        // 1. Initialize SQLite schema
        DatabaseConfig.initializeSchema();

        // 2. Configure Javalin
        Javalin app = Javalin.create(config -> {
            // Use Jackson for JSON
            config.jsonMapper(new JavalinJackson());
        });

        // 3. Add CORS headers manually via before-filter
        // This works regardless of which Javalin 5.x minor version is installed
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin",  "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        });

        // Handle OPTIONS preflight requests
        app.options("/*", ctx -> {
            ctx.header("Access-Control-Allow-Origin",  "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.status(200);
        });

        // 4. Register all routes
        Router.register(app);

        // 5. Start — ONLY on localhost, never exposed externally
        int port = AppConfig.getPort();
        app.start("127.0.0.1", port);

        log.info("Optimix backend listening on http://127.0.0.1:{}", port);
        log.info("Health check: http://127.0.0.1:{}/health", port);

        // 6. Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Optimix backend...");
            app.stop();
        }));
    }
}
