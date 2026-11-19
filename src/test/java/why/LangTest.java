package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import why.lang.Ast;
import why.lang.Interpreter;
import why.lang.Parser;
import why.lang.Scope;
import why.lang.Value;

class LangTest {

    /** Mirrors the page compiler: top-level defs are hoisted before the controller runs. */
    private static Scope run(String source) {
        List<Ast.Stmt> body = new Parser(source, "test.why", 1).parseProgram();
        Map<String, Ast.Def> defs = new LinkedHashMap<>();
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.Def def) defs.put(def.name(), def);
        }
        Scope scope = new Scope(null);
        Interpreter interpreter = new Interpreter(defs, scope);
        interpreter.exec(body, scope);
        return scope;
    }

    private static Object eval(String source) {
        Scope scope = new Scope(null);
        Interpreter interpreter = new Interpreter(Map.of(), scope);
        return interpreter.eval(new Parser(source, "test.why", 1).parseExpressionOnly(), scope);
    }

    @Test
    void arithmeticAndPrecedence() {
        Scope scope = run("x = 1 + 2 * 3\ny = (1 + 2) * 3\nz = 7 / 2\nr = 7 % 2\n");
        assertEquals(7L, scope.get("x"));
        assertEquals(9L, scope.get("y"));
        assertEquals(3L, scope.get("z"));
        assertEquals(1L, scope.get("r"));
    }

    @Test
    void stringsInterpolate() {
        Scope scope = run("""
                name = "why"
                greeting = "hi #{name}! #{2 + 3}"
                single = 'no #{name}'
                """);
        assertEquals("hi why! 5", scope.get("greeting"));
        assertEquals("no #{name}", scope.get("single"));
    }

    @Test
    void conditionalsChooseABranch() {
        Scope scope = run("""
                x = 0
                if x == 1
                  y = "one"
                elsif x == 0
                  y = "zero"
                else
                  y = "many"
                end
                """);
        assertEquals("zero", scope.get("y"));
    }

    @Test
    void whileLoopsWithNextAndBreak() {
        Scope scope = run("""
                i = 0
                total = 0
                while i < 4
                  i += 1
                  if i == 3
                    next
                  end
                  total += i
                end
                j = 0
                while true
                  j += 1
                  if j == 2
                    break
                  end
                end
                """);
        assertEquals(4L, scope.get("i"));
        assertEquals(7L, scope.get("total"));
        assertEquals(2L, scope.get("j"));
    }

    @Test
    void functionsCanBeCalledBeforeTheirDefinition() {
        Scope scope = run("""
                x = twice(21)
                def twice(n)
                  n * 2
                end
                """);
        assertEquals(42L, scope.get("x"));
    }

    @Test
    void blocksIterateAndCloseOverOuterVariables() {
        Scope scope = run("""
                total = 0
                [1, 2, 3].each do |n|
                  total += n
                end
                doubled = [1, 2, 3].map do |n|
                  n * 2
                end
                """);
        assertEquals(6L, scope.get("total"));
        assertEquals(List.of(2L, 4L, 6L), scope.get("doubled"));
    }

    @Test
    void hashesAndProperties() {
        Scope scope = run("""
                post = {title: "Why", views: 3}
                title = post.title
                count = post.views
                missing = post.nope
                """);
        assertEquals("Why", scope.get("title"));
        assertEquals(3L, scope.get("count"));
        assertEquals(null, scope.get("missing"));
    }

    @Test
    void truthinessFollowsRuby() {
        assertEquals(false, eval("!0"));
        assertEquals(true, eval("not nil"));
        assertEquals(true, eval("not false"));
        assertEquals(true, eval("1 == 1.0"));
        assertTrue(Value.truthy(eval("\"\"")));
    }

    @Test
    void indexingAndStringMethods() {
        Scope scope = run("""
                last = [10, 20, 30][-1]
                upper = "why".upcase
                parts = "a,b".split(",")
                joined = ["a", "b"].join("-")
                """);
        assertEquals(30L, scope.get("last"));
        assertEquals("WHY", scope.get("upper"));
        assertEquals(List.of("a", "b"), scope.get("parts"));
        assertEquals("a-b", scope.get("joined"));
    }

    @Test
    void parseErrorsCarryTheFileAndLine() {
        WhyError error = assertThrows(WhyError.class, () -> run("x = 1\n\nif\nend\n"));
        assertEquals("test.why", error.file());
        assertTrue(error.line() >= 3);
    }
}
