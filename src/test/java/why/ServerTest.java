package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.lang.Db;

class ServerTest {

    @TempDir
    Path dir;

    private WhyServer server;
    private HttpClient client;

    @BeforeEach
    void start() throws IOException {
        write("header.why", "view:\n<header>site</header>\n");
        write("index.why", """
                imports:
                header

                controller:
                posts = db.query("select title from posts order by id")
                count = posts.length

                view:
                {{> header}}
                {{#each posts}}<h2>{{it.title}}</h2>{{/each}}
                <p>count={{count}}</p>
                """);
        write("plain.why", "<h1>plain</h1>\n");
        write("broken.why", "controller:\nif\nend\n\nview:\nnever\n");
        write("style.css", "body{color:red}\n");

        try (Db db = new Db(dir.resolve("data.db"))) {
            db.exec("create table posts (id integer primary key, title text)", List.of());
            db.exec("insert into posts (id, title) values (1, 'first'), (2, 'second')", List.of());
        }

        server = new WhyServer(new Site(dir), 0);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rendersAPageWithImportsAndDbRows() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/html"));
        assertTrue(response.body().contains("<header>site</header>"));
        assertTrue(response.body().contains("<h2>first</h2><h2>second</h2>"));
        assertTrue(response.body().contains("<p>count=2</p>"));
    }

    @Test
    void servesPlainPagesAndExtensionFreeUrls() throws Exception {
        assertEquals("<h1>plain</h1>\n", get("/plain.why").body());
        assertEquals("<h1>plain</h1>\n", get("/plain").body());
    }

    @Test
    void servesStaticFilesAndReportsMisses() throws Exception {
        HttpResponse<String> css = get("/style.css");
        assertEquals(200, css.statusCode());
        assertEquals("text/css; charset=utf-8", css.headers().firstValue("content-type").orElse(""));
        assertEquals("body{color:red}\n", css.body());
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    void errorsBecomeA500PageWithALocation() throws Exception {
        HttpResponse<String> response = get("/broken");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("broken.why:2"));
    }

    @Test
    void errorPageShowsTheSourceChainAndASuggestion() throws Exception {
        write("header.why", "imports:\nnav\n\nview:\n<header>\n{{> nav}}\n</header>\n");
        write("nav.why", "view:\n<nav>{{str2(\"hi\")}}</nav>\n");
        write("chain.why", """
                imports:
                header

                controller:
                title = "x"

                view:
                {{> header}}
                """);

        HttpResponse<String> response = get("/chain");
        assertEquals(500, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("<p class=\"loc\">nav.why:2</p>"));
        assertTrue(body.contains("chain.why:8"));
        assertTrue(body.contains("header.why:6"));
        assertTrue(body.contains("str2"));
        assertTrue(body.contains("^"));
        assertTrue(body.contains("did you mean"));
    }

    @Test
    void errorPageSuggestsAMisspelledPartial() throws Exception {
        write("page.why", "imports:\nheader\n\nview:\n{{> heder}}\n");

        HttpResponse<String> response = get("/page");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("page.why:5"));
        assertTrue(response.body().contains("heder"));
        assertTrue(response.body().contains("did you mean"));
    }

    @Test
    void compileErrorsInAnImportShowTheImportChain() throws Exception {
        write("leaf.why", "view:\n{{> nope}}\n");
        write("mid.why", "imports:\nleaf\n\nview:\n{{> leaf}}\n");
        write("top.why", "imports:\nmid\n\nview:\n{{> mid}}\n");

        HttpResponse<String> response = get("/top");
        assertEquals(500, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("leaf.why:2"));
        assertTrue(body.contains("top.why:2"));
        assertTrue(body.contains("mid.why:2"));
    }

    @Test
    void syntaxErrorsShowTheOffendingLine() throws Exception {
        write("syntax.why", "controller:\nx = )\n\nview:\nhi\n");

        HttpResponse<String> response = get("/syntax");
        assertEquals(500, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("syntax.why:2"));
        assertTrue(body.contains("x = )"));
        assertTrue(body.contains("^"));
    }

    @Test
    void underscoreFilesAreImportOnly() throws Exception {
        write("_snippet.why", "view:\n<span>snippet</span>\n");
        write("_secret.css", "body{color:black}\n");
        write("uses.why", "imports:\n_snippet\n\nview:\n{{> _snippet}}\n");

        assertEquals(404, get("/_snippet").statusCode());
        assertEquals(404, get("/_snippet.why").statusCode());
        assertEquals(404, get("/_secret.css").statusCode());
        assertEquals("<span>snippet</span>\n\n", get("/uses").body());
    }

    @Test
    void queryParametersReachThePage() throws Exception {
        write("echo.why", "controller:\nname = params.name\n\nview:\nhello {{name}}\n");
        assertEquals("hello why\n", get("/echo?name=why").body());
    }

    @Test
    void postRedirectsAndFormFieldsReachThePage() throws Exception {
        write("sign.why", """
                controller:
                if request.method == "POST"
                  redirect("/signed?name=" + form.name)
                end

                view:
                <form method="post" action="/sign"></form>
                """);
        write("signed.why", "controller:\nname = params.name\n\nview:\nsigned {{name}}\n");

        HttpResponse<String> posted = post("/sign", "name=why");
        assertEquals(303, posted.statusCode());
        assertEquals("/signed?name=why", posted.headers().firstValue("location").orElse(""));
        assertEquals("signed why\n", get("/signed?name=why").body());
    }
}
