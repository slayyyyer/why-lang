package why.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import why.Site;
import why.WhyServer;
import why.lang.Csrf;

/**
 * An in-process server for tests. It starts on a free loopback port, does not follow redirects (so
 * a 303 stays visible), and submits the CSRF token a browser would send, so a POST page can be
 * exercised in a few lines.
 *
 * <pre>{@code
 * try (TestServer server = TestServer.start(Path.of("src/why"))) {
 *     assertEquals(200, server.get("/").status());
 *     Response posted = server.post("/guestbook", Map.of("name", "why"));
 *     assertEquals(303, posted.status());
 * }
 * }</pre>
 */
public final class TestServer implements AutoCloseable {
    private final WhyServer server;
    private final String token = Csrf.newToken();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private TestServer(WhyServer server) {
        this.server = server;
    }

    /** Starts a server for a site folder. */
    public static TestServer start(Path siteRoot) throws IOException {
        return start(new Site(siteRoot), false);
    }

    /** Starts a server, optionally with development diagnostics in error pages. */
    public static TestServer start(Site site, boolean diagnostics) throws IOException {
        WhyServer server = new WhyServer(site, "127.0.0.1", 0, diagnostics, false);
        server.start();
        return new TestServer(server);
    }

    public int port() {
        return server.port();
    }

    /** A GET with the harness's CSRF cookie attached. */
    public Response get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, null, Map.of("Cookie", cookie()));
    }

    /** A POST of urlencoded fields, with the harness's CSRF token added. */
    public Response post(String path, Map<String, String> fields) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        }
        if (body.length() > 0) body.append('&');
        body.append("_token=").append(token);
        return send("POST", path, body.toString(),
                "application/x-www-form-urlencoded", Map.of("Cookie", cookie()));
    }

    /**
     * A POST of a multipart form: text fields plus file parts, with the harness's CSRF token
     * added, the way a browser submits {@code enctype="multipart/form-data"}.
     */
    public Response postMultipart(String path, Map<String, String> fields, List<Part> files)
            throws IOException, InterruptedException {
        String boundary = "----why" + Csrf.newToken();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            writeField(body, boundary, field.getKey(), field.getValue());
        }
        writeField(body, boundary, "_token", token);
        for (Part file : files) {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"" + file.field()
                    + "\"; filename=\"" + file.filename() + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Type: " + file.contentType() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(file.bytes());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return sendBytes("POST", path, body.toByteArray(),
                "multipart/form-data; boundary=" + boundary, Map.of());
    }

    /** A request with explicit headers; the CSRF cookie is always attached. */
    public Response send(String method, String path, String body, String contentType,
            Map<String, String> headers) throws IOException, InterruptedException {
        return sendBytes(method, path,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), contentType, headers);
    }

    private Response sendBytes(String method, String path, byte[] body, String contentType,
            Map<String, String> headers) throws IOException, InterruptedException {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.putIfAbsent("Cookie", cookie());
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port() + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) request.header("Content-Type", contentType);
        all.forEach(request::header);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().map());
    }

    private static void writeField(ByteArrayOutputStream body, String boundary, String name,
            String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(value.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String cookie() {
        return "why_csrf=" + token;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop();
    }

    /** One file part in a multipart post. */
    public record Part(String field, String filename, String contentType, byte[] bytes) {}

    /** Status, body and headers of one response. Header names are lowercased by the JDK. */
    public record Response(int status, String body, Map<String, List<String>> headers) {
        public String header(String name) {
            return headers.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), List.of()).stream()
                    .findFirst().orElse("");
        }
    }
}
