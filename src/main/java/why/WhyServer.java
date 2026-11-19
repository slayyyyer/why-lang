package why;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import why.lang.Db;
import why.util.Html;

/** The HTTP server: one handler, virtual threads, every request compiled and rendered from disk. */
public final class WhyServer {
    private final Site site;
    private final PageCompiler compiler;
    private final HttpServer server;

    public WhyServer(Site site, int port) throws IOException {
        this.site = site;
        this.compiler = new PageCompiler(site);
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", this::handle);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        int status = 500;
        try {
            if (!method.equals("GET") && !method.equals("HEAD")) {
                status = 405;
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            Path file = site.resolve(path);
            if (file == null) {
                status = 404;
                sendHtml(exchange, 404, notFound(path));
                return;
            }
            status = site.isPage(file) ? servePage(exchange, file) : serveStatic(exchange, file);
        } catch (RuntimeException e) {
            status = 500;
            e.printStackTrace();
            try {
                sendHtml(exchange, 500, errorPage("Internal error", String.valueOf(e), ""));
            } catch (IOException ignored) {
                // The response may already be committed; nothing else to send.
            }
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            System.out.println(method + " " + path + " " + status + " " + millis + "ms");
            exchange.close();
        }
    }

    private int servePage(HttpExchange exchange, Path file) throws IOException {
        String display = site.relative(file);
        String html;
        try {
            Program program = compiler.compile(file);
            Map<String, Object> globals = globals(exchange);
            try (Db db = new Db(site.dbFile())) {
                globals.put("db", db);
                html = program.render(globals);
            }
        } catch (WhyError e) {
            WhyError located = e.file() == null ? new WhyError(e.getMessage(), display, e.line()) : e;
            System.err.println("why: " + located);
            sendHtml(exchange, 500, errorPage("Why error", located.getMessage(), located.location()));
            return 500;
        }
        sendHtml(exchange, 200, html);
        return 200;
    }

    private int serveStatic(HttpExchange exchange, Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        send(exchange, 200, contentType(name), Files.readAllBytes(file));
        return 200;
    }

    private Map<String, Object> globals(HttpExchange exchange) {
        Map<Object, Object> params = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) continue;
                int equals = pair.indexOf('=');
                String key = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                params.put(decode(key), decode(value));
            }
        }

        Map<Object, Object> request = new LinkedHashMap<>();
        request.put("method", exchange.getRequestMethod());
        request.put("path", exchange.getRequestURI().getPath());
        request.put("query", rawQuery == null ? "" : rawQuery);
        request.put("params", params);

        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("request", request);
        globals.put("params", params);
        return globals;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        send(exchange, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return switch (extension) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    private static String notFound(String path) {
        return layout("Not found", "<h1>404</h1><p>No page at <code>" + Html.escape(path) + "</code>.</p>");
    }

    private static String errorPage(String title, String message, String location) {
        StringBuilder body = new StringBuilder("<h1>").append(Html.escape(title)).append("</h1>");
        if (!location.isEmpty()) {
            body.append("<p class=\"loc\">").append(Html.escape(location)).append("</p>");
        }
        body.append("<pre>").append(Html.escape(message)).append("</pre>");
        return layout(title, body.toString());
    }

    private static String layout(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + Html.escape(title) + "</title>"
                + "<style>body{font:16px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;margin:6vh auto;max-width:44rem;"
                + "padding:0 1.5rem;color:#222}h1{font-size:1.4rem}.loc{color:#a00}"
                + "pre{background:#f4f4f4;padding:1rem;overflow:auto;border-left:3px solid #a00}"
                + "code{background:#f4f4f4;padding:.1rem .3rem}</style></head><body>" + body + "</body></html>";
    }
}
