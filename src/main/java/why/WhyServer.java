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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import why.lang.Db;
import why.lang.Flash;
import why.lang.Redirect;
import why.util.Html;

/** The HTTP server: one handler, virtual threads, every request compiled and rendered from disk. */
public final class WhyServer {
    private final Site site;
    private final PageCompiler compiler;
    private final HttpServer server;
    /** Pending one-shot flashes, keyed by the why_session cookie value. */
    private final Map<String, Map<String, Object>> flashes = new ConcurrentHashMap<>();

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
            if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("POST")) {
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
            if (site.isPage(file)) {
                status = servePage(exchange, file);
            } else if (method.equals("POST")) {
                status = 405;
                sendText(exchange, 405, "Method Not Allowed");
            } else {
                status = serveStatic(exchange, file);
            }
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
        String sessionId = sessionId(exchange);
        Map<String, Object> carried = sessionId == null ? Map.of() : flashes.remove(sessionId);
        Flash flash = new Flash(carried == null ? Map.of() : carried);
        String html;
        try {
            Program program = compiler.compile(file);
            Map<String, Object> globals = globals(exchange, flash);
            try (Db db = new Db(site.dbFile())) {
                globals.put("db", db);
                html = program.render(globals);
            }
        } catch (Redirect redirect) {
            persistFlash(exchange, sessionId, flash);
            sendRedirect(exchange, redirect.location());
            return 303;
        } catch (WhyError e) {
            persistFlash(exchange, sessionId, flash);
            WhyError located = e.locate(display, e.line());
            System.err.println("why: " + located);
            sendHtml(exchange, 500, whyErrorPage(located));
            return 500;
        }
        persistFlash(exchange, sessionId, flash);
        sendHtml(exchange, 200, html);
        return 200;
    }

    /** The validated why_session cookie value, or null when there is none. */
    private static String sessionId(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                int equals = part.indexOf('=');
                if (equals < 0) continue;
                if (!part.substring(0, equals).strip().equals("why_session")) continue;
                String id = part.substring(equals + 1).strip();
                if (id.matches("[A-Za-z0-9-]{1,64}")) return id;
            }
        }
        return null;
    }

    /** Stores messages set this request; a visitor without a session gets a cookie. */
    private void persistFlash(HttpExchange exchange, String sessionId, Flash flash) {
        Map<String, Object> pending = flash.pending();
        if (pending.isEmpty()) return;
        String id = sessionId;
        if (id == null) {
            id = UUID.randomUUID().toString();
            exchange.getResponseHeaders().add("Set-Cookie",
                    "why_session=" + id + "; Path=/; HttpOnly; SameSite=Lax");
        }
        flashes.put(id, pending);
    }

    private int serveStatic(HttpExchange exchange, Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        send(exchange, 200, contentType(name), Files.readAllBytes(file));
        return 200;
    }

    private Map<String, Object> globals(HttpExchange exchange, Flash flash) {
        Map<Object, Object> params = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) parseUrlEncoded(rawQuery, params);

        Map<Object, Object> form = form(exchange);

        Map<Object, Object> request = new LinkedHashMap<>();
        request.put("method", exchange.getRequestMethod());
        request.put("path", exchange.getRequestURI().getPath());
        request.put("query", rawQuery == null ? "" : rawQuery);
        request.put("params", params);
        request.put("form", form);

        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("request", request);
        globals.put("params", params);
        globals.put("form", form);
        globals.put("flash", flash);
        return globals;
    }

    /** POSTed urlencoded fields; empty on GET or other content types. */
    private Map<Object, Object> form(HttpExchange exchange) {
        Map<Object, Object> fields = new LinkedHashMap<>();
        if (!exchange.getRequestMethod().equals("POST")) return fields;
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null
                && !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return fields;
        }
        try {
            String body = new String(exchange.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            parseUrlEncoded(body, fields);
        } catch (IOException e) {
            throw new WhyError("cannot read the request body: " + e.getMessage());
        }
        return fields;
    }

    private static void parseUrlEncoded(String encoded, Map<Object, Object> into) {
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            into.put(decode(key), decode(value));
        }
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

    private void sendRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(303, -1);
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

    /** The 500 page: file:line, the source excerpt with a caret, the message, then the path here. */
    private String whyErrorPage(WhyError error) {
        StringBuilder body = new StringBuilder("<h1>Why error</h1>");
        if (!error.location().isEmpty()) {
            body.append("<p class=\"loc\">").append(Html.escape(error.location())).append("</p>");
        }

        String source = sourceLine(error.file(), error.line());
        if (source != null) {
            source = source.replace("\t", "    ");
            int column = caretColumn(source, error.getMessage());
            String number = String.valueOf(error.line());
            body.append("<pre class=\"snippet\">")
                    .append(number).append(" | ").append(Html.escape(source)).append('\n')
                    .append(" ".repeat(number.length())).append(" | ")
                    .append(" ".repeat(column)).append("^</pre>");
        }

        body.append("<pre>").append(Html.escape(error.getMessage())).append("</pre>");

        if (!error.chain().isEmpty()) {
            body.append("<p class=\"via\">Reached via</p><ul class=\"chain\">");
            for (WhyError.Frame frame : error.chain()) {
                body.append("<li><code>")
                        .append(Html.escape(frame.file() + ":" + frame.line()))
                        .append("</code>");
                String callSite = sourceLine(frame.file(), frame.line());
                if (callSite != null) body.append(" ").append(Html.escape(callSite.strip()));
                body.append("</li>");
            }
            body.append("</ul>");
        }

        return layout("Why error", body.toString());
    }

    /** One source line, or null when the file or line is unavailable. */
    private String sourceLine(String file, int line) {
        if (file == null || line <= 0) return null;
        Path path = site.root().resolve(file).normalize();
        if (!path.startsWith(site.root()) || !Files.isRegularFile(path)) return null;
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            return line <= lines.size() ? lines.get(line - 1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Where to put the caret: under the quoted name in the message when it appears on the line,
     * otherwise under the first non-space character.
     */
    private static int caretColumn(String line, String message) {
        int quote = message.indexOf('\'');
        if (quote >= 0) {
            int end = message.indexOf('\'', quote + 1);
            if (end > quote) {
                int at = line.indexOf(message.substring(quote + 1, end));
                if (at >= 0) return at;
            }
        }
        return line.length() - line.stripLeading().length();
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
                + ".snippet{background:#fff;padding:.5rem .75rem}"
                + ".via{margin:1.5rem 0 .25rem;color:#777}"
                + ".chain{margin:.25rem 0;padding-left:1.2rem;color:#555}.chain li{margin:.1rem 0}"
                + "code{background:#f4f4f4;padding:.1rem .3rem}</style></head><body>" + body + "</body></html>";
    }
}
