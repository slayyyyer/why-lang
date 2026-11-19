package why.tpl;

import java.util.List;
import java.util.Map;

import why.WhyError;
import why.lang.Ast;
import why.lang.Interpreter;
import why.lang.Scope;
import why.lang.Value;
import why.util.Html;

/** A compiled view: literal text, {@code {{...}}} mustaches, blocks and partials. */
public final class Template {

    public interface Node {}

    public record Text(String text) implements Node {}

    public record Echo(Ast.Expr expr, boolean escape, int line) implements Node {}

    public record Partial(String name, int line) implements Node {}

    public record Block(Ast.Expr cond, List<Node> body, List<Node> otherwise, int line) implements Node {}

    public record Each(Ast.Expr expr, List<Node> body, List<Node> otherwise, int line) implements Node {}

    private final String file;
    private final List<Node> nodes;

    Template(String file, List<Node> nodes) {
        this.file = file;
        this.nodes = List.copyOf(nodes);
    }

    public String render(Interpreter interp, Scope scope, Map<String, Template> partials) {
        StringBuilder out = new StringBuilder();
        renderInto(out, interp, scope, partials);
        return out.toString();
    }

    void renderInto(StringBuilder out, Interpreter interp, Scope scope, Map<String, Template> partials) {
        renderNodes(out, nodes, interp, scope, partials);
    }

    private void renderNodes(StringBuilder out, List<Node> nodes, Interpreter interp, Scope scope,
                             Map<String, Template> partials) {
        for (Node node : nodes) {
            if (node instanceof Text text) {
                out.append(text.text());
            } else if (node instanceof Echo echo) {
                String value = Value.str(interp.eval(echo.expr(), scope));
                out.append(echo.escape() ? Html.escape(value) : value);
            } else if (node instanceof Partial partial) {
                Template target = partials.get(partial.name());
                if (target == null) {
                    throw new WhyError("unknown partial '" + partial.name() + "'", file, partial.line());
                }
                target.renderInto(out, interp, scope, partials);
            } else if (node instanceof Block block) {
                List<Node> branch = Value.truthy(interp.eval(block.cond(), scope)) ? block.body() : block.otherwise();
                renderNodes(out, branch, interp, scope, partials);
            } else if (node instanceof Each each) {
                renderEach(out, each, interp, scope, partials);
            }
        }
    }

    private void renderEach(StringBuilder out, Each each, Interpreter interp, Scope scope,
                            Map<String, Template> partials) {
        Object value = interp.eval(each.expr(), scope);
        if (value instanceof List<?> list && !list.isEmpty()) {
            int index = 0;
            for (Object item : list) {
                Scope child = iteration(scope, item, index, list.size());
                renderNodes(out, each.body(), interp, child, partials);
                index++;
            }
        } else if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Scope child = iteration(scope, entry.getValue(), index, map.size());
                child.define("@key", entry.getKey());
                renderNodes(out, each.body(), interp, child, partials);
                index++;
            }
        } else if (value instanceof List<?> || value instanceof Map<?, ?> || !Value.truthy(value)) {
            renderNodes(out, each.otherwise(), interp, scope, partials);
        } else {
            renderNodes(out, each.body(), interp, iteration(scope, value, 0, 1), partials);
        }
    }

    private static Scope iteration(Scope parent, Object item, int index, int size) {
        Scope child = parent.child();
        child.define("it", item);
        child.define("@index", (long) index);
        child.define("@first", index == 0);
        child.define("@last", index == size - 1);
        return child;
    }

    /** Reports missing partials while the page is compiled instead of when it is served. */
    public void validatePartials(Map<String, Template> partials) {
        validateNodes(nodes, partials);
    }

    private void validateNodes(List<Node> nodes, Map<String, Template> partials) {
        for (Node node : nodes) {
            if (node instanceof Partial partial) {
                if (!partials.containsKey(partial.name())) {
                    throw new WhyError("unknown partial '" + partial.name() + "'", file, partial.line());
                }
            } else if (node instanceof Block block) {
                validateNodes(block.body(), partials);
                validateNodes(block.otherwise(), partials);
            } else if (node instanceof Each each) {
                validateNodes(each.body(), partials);
                validateNodes(each.otherwise(), partials);
            }
        }
    }
}
