package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.testing.TestServer;

/** File uploads: the {@code files} global, the {@code upload} methods, and their limits. */
class UploadTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static TestServer.Part file(String field, String name, String type, String body) {
        return new TestServer.Part(field, name, type, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void multipartFieldsAndFilesReachThePage() throws Exception {
        write("upload.why", """
                controller:
                picture = files.photo

                view:
                {{form.title}}|{{picture.filename}}|{{picture.type}}|{{picture.size}}|{{picture.text}}
                """);

        try (TestServer server = TestServer.start(dir)) {
            TestServer.Response response = server.postMultipart("/upload",
                    Map.of("title", "hi"),
                    List.of(file("photo", "notes.txt", "text/plain", "hello")));

            assertEquals(200, response.status());
            assertEquals("hi|notes.txt|text/plain|5|hello\n", response.body());
        }
    }

    @Test
    void anUntouchedFileInputIsNil() throws Exception {
        write("empty.why", """
                view:
                {{#if files.photo}}file{{else}}none{{/if}}
                """);

        try (TestServer server = TestServer.start(dir)) {
            TestServer.Response response = server.postMultipart("/empty", Map.of("x", "1"),
                    List.of(file("photo", "", "application/octet-stream", "")));

            assertEquals("none\n", response.body());
        }
    }

    @Test
    void urlencodedFormsHaveNoFiles() throws Exception {
        write("plain.why", """
                view:
                {{files.length}}
                """);

        try (TestServer server = TestServer.start(dir)) {
            assertEquals("0\n", server.post("/plain", Map.of("name", "why")).body());
        }
    }

    @Test
    void uploadsSaveInsideTheSite() throws Exception {
        write("save.why", """
                controller:
                name = ""
                if request.method == "POST"
                  name = files.photo.save("uploads")
                end

                view:
                saved {{name}}
                """);

        try (TestServer server = TestServer.start(dir)) {
            TestServer.Response response = server.postMultipart("/save", Map.of(),
                    List.of(file("photo", "Notes.TXT", "text/plain", "hello")));

            assertEquals(200, response.status());
            String name = response.body().substring("saved ".length()).strip();
            assertTrue(name.endsWith(".txt"), name);
            assertEquals(1, name.chars().filter(c -> c == '.').count(), name);
            assertEquals("hello", Files.readString(dir.resolve("uploads").resolve(name)));
        }
    }

    @Test
    void saveCannotEscapeTheSite() throws Exception {
        write("escape.why", """
                controller:
                files.photo.save("../why-upload-outside")

                view:
                never
                """);

        try (TestServer server = TestServer.start(dir)) {
            assertEquals(500, server.postMultipart("/escape", Map.of(),
                    List.of(file("photo", "a.txt", "text/plain", "x"))).status());
            assertFalse(Files.exists(dir.getParent().resolve("why-upload-outside")));
        }
    }

    @Test
    void saveRefusesASymlinkedFolderThatLeavesTheSite() throws Exception {
        write("symlinked.why", """
                controller:
                files.photo.save("uploads")

                view:
                never
                """);
        Path outside = Files.createTempDirectory("why-upload-outside-");
        Path link = dir.resolve("uploads");
        try {
            Assumptions.assumeTrue(createDirectoryLink(link, outside),
                    "symbolic links or junctions are unavailable");

            try (TestServer server = TestServer.start(dir)) {
                assertEquals(500, server.postMultipart("/symlinked", Map.of(),
                        List.of(file("photo", "a.txt", "text/plain", "x"))).status());
                try (var entries = Files.list(outside)) {
                    assertEquals(0, entries.count(), "the upload escaped the site through a link");
                }
            }
        } finally {
            deleteLink(link);
            Files.deleteIfExists(outside);
        }
    }

    /** A directory symlink, or a Windows junction; false when neither is available. */
    private static boolean createDirectoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            if (!isWindows()) return false;
            Process process = new ProcessBuilder(
                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static void deleteLink(Path link) throws Exception {
        try {
            Files.deleteIfExists(link);
        } catch (IOException e) {
            if (!isWindows()) throw e;
            Process process = new ProcessBuilder("cmd", "/c", "rmdir", link.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        }
    }

    @Test
    void multipartPostsStillNeedACsrfToken() throws Exception {
        write("guarded.why", "view:\nok\n");

        try (TestServer server = TestServer.start(dir)) {
            String body = "--x\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--x--\r\n";
            assertEquals(403, server.send("POST", "/guarded", body,
                    "multipart/form-data; boundary=x", Map.of()).status());
        }
    }

    @Test
    void malformedMultipartBodiesAreBadRequests() throws Exception {
        write("guarded.why", "view:\nok\n");

        try (TestServer server = TestServer.start(dir)) {
            assertEquals(400, server.send("POST", "/guarded", "not really multipart",
                    "multipart/form-data", Map.of()).status());
        }
    }

    @Test
    void multipartBodiesAreLimitedToTheirPartCount() throws Exception {
        write("many.why", "view:\nok\n");
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < AllocationLimits.UPLOAD_PARTS; i++) {
            fields.put("f" + i, "x");
        }

        try (TestServer server = TestServer.start(dir)) {
            assertEquals(400, server.postMultipart("/many", fields, List.of()).status());
        }
    }

    @Test
    void oversizedMultipartBodiesAreRejected() throws Exception {
        write("big.why", "view:\nok\n");
        byte[] big = new byte[AllocationLimits.UPLOAD_BYTES + 1];
        Arrays.fill(big, (byte) 'x');

        try (TestServer server = TestServer.start(dir)) {
            assertEquals(413, server.postMultipart("/big", Map.of(),
                    List.of(new TestServer.Part("photo", "big.bin", "application/octet-stream", big)))
                    .status());
        }
    }
}
