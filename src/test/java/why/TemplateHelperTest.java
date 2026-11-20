package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateHelperTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String render(String source, Map<String, Object> globals) throws IOException {
        Path page = write("page.why", source);
        return new PageCompiler(new Site(dir)).compile(page).render(globals);
    }

    @Test
    void aDefRendersTheBlockWithYield() throws Exception {
        String html = render("""
                controller:
                def card(title)
                  "<section><h2>" + title + "</h2>" + yield + "</section>"
                end

                view:
                {{#card "Hi"}}<p>body</p>{{/card}}
                """, Map.of());

        assertEquals("<section><h2>Hi</h2><p>body</p></section>\n", html);
    }

    @Test
    void blockInverseRendersTheElseSection() throws Exception {
        String source = """
                controller:
                def maybe(cond)
                  if cond
                    return "<div>" + yield + "</div>"
                  end
                  "<div>" + block.inverse + "</div>"
                end

                view:
                {{#maybe shown}}yes{{else}}no{{/maybe}}
                """;

        assertEquals("<div>yes</div>\n", render(source, Map.of("shown", true)));
        assertEquals("<div>no</div>\n", render(source, Map.of("shown", false)));
    }

    @Test
    void yieldArgumentsBecomeItInTheBody() throws Exception {
        String html = render("""
                controller:
                def list(items)
                  out = ""
                  items.each do |item|
                    out = out + yield(item)
                  end
                  out
                end

                view:
                {{#list posts}}<li>{{it.title}}</li>{{/list}}
                """, Map.of("posts", List.of(Map.of("title", "A"), Map.of("title", "B"))));

        assertEquals("<li>A</li><li>B</li>\n", html);
    }

    @Test
    void theBodyIsEscapedButHelperMarkupIsNot() throws Exception {
        String html = render("""
                controller:
                def wrap()
                  "<div>" + yield + "</div>"
                end

                view:
                {{#wrap}}{{title}}{{/wrap}}
                """, Map.of("title", "<b>x</b>"));

        assertEquals("<div>&lt;b&gt;x&lt;/b&gt;</div>\n", html);
    }

    @Test
    void helpersCanBeCalledWithNoArguments() throws Exception {
        String html = render("""
                controller:
                def panel()
                  "<aside>" + yield + "</aside>"
                end

                view:
                {{#panel}}<p>note</p>{{/panel}}
                """, Map.of());

        assertEquals("<aside><p>note</p></aside>\n", html);
    }

    @Test
    void aLanguageBlockIsYieldableToo() throws Exception {
        String html = render("""
                controller:
                def wrap()
                  "<b>" + yield + "</b>"
                end

                result = wrap() do
                  "x"
                end

                view:
                {{{result}}}
                """, Map.of());

        assertEquals("<b>x</b>\n", html);
    }

    @Test
    void yieldWithoutABlockIsARuntimeError() throws Exception {
        Path page = write("page.why", """
                controller:
                def oops()
                  yield
                end

                x = oops()

                view:
                {{x}}
                """);

        WhyError error = assertThrows(WhyError.class,
                () -> new PageCompiler(new Site(dir)).compile(page).render(Map.of()));
        assertTrue(error.getMessage().contains("needs a block"), error.getMessage());
        assertEquals("page.why", error.file());
    }

    @Test
    void anUnknownHelperIsACompileError() throws Exception {
        Path page = write("page.why", "view:\n{{#nope}}x{{/nope}}\n");

        WhyError error = assertThrows(WhyError.class,
                () -> new PageCompiler(new Site(dir)).compile(page));
        assertTrue(error.getMessage().contains("unknown block helper 'nope'"), error.getMessage());
        assertEquals(2, error.line());
    }

    @Test
    void importedDefsCanBeHelpers() throws Exception {
        write("helpers.why", """
                controller:
                def badge(text)
                  "<span>" + text + "</span>"
                end

                view:
                helper
                """);
        Path page = write("page.why", """
                imports:
                helpers

                view:
                {{#badge "new"}}ignored{{/badge}}
                """);

        assertEquals("<span>new</span>\n",
                new PageCompiler(new Site(dir)).compile(page).render(Map.of()));
    }
}
