package why.tpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import why.WhyError;
import why.lang.Ast;
import why.lang.Block;
import why.lang.Interpreter;
import why.lang.Scope;
import why.lang.Value;
import why.util.Html;
import why.util.Suggest;

/** A compiled view: literal text, {@code {{...}}} mustaches, blocks and partials. */
public final class Template {

    private static final int MAX_PARTIAL_DEPTH = 128;

    public interface Node {}

    public record Text(String text) implements Node {}

    public record Echo(Ast.Expr expr, boolean escape, int line) implements Node {}

    public record Partial(String name, int line) implements Node {}

    public record Block(Ast.Expr cond, List<Node> body, List<Node> otherwise, int line) implements Node {}

    public record Each(Ast.Expr expr, List<Node> body, List<Node> otherwise, int line) implements Node {}

    /** A page-defined {@code {{#name args}}} block, rendered by calling the def {@code name}. */
    public record Helper(String name, List<Ast.Expr> args, List<Node> body, List<Node> otherwise,
                         int line) implements Node {}

    private final String file;
    private final List<Node> nodes;

    Template(String file, List<Node> nodes) {
        this.file = file;
        this.nodes = List.copyOf(nodes);
    }

    /** The parsed view nodes, for tools that inspect templates without rendering them. */
    public List<Node> nodes() {
        return nodes;
    }

    public String render(Interpreter interp, Scope scope, Map<String, Template> partials) {
        StringBuilder out = new StringBuilder();
        renderInto(out, interp, scope, partials);
        return out.toString();
    }

    void renderInto(StringBuilder out, Interpreter interp, Scope scope, Map<String, Template> partials) {
        renderInto(out, interp, scope, partials, 0);
    }

    private void renderInto(StringBuilder out, Interpreter interp, Scope scope,
                            Map<String, Template> partials, int depth) {
        if (depth > MAX_PARTIAL_DEPTH) {
            throw new WhyError("partial depth budget exceeded", file, 0);
        }
        renderNodes(out, nodes, interp, scope, partials, depth);
    }

    private void renderNodes(StringBuilder out, List<Node> nodes, Interpreter interp, Scope scope,
                             Map<String, Template> partials, int depth) {
        for (Node node : nodes) {
            try {
                interp.step();
                if (node instanceof Text text) {
                    interp.output(text.text().length());
                    out.append(text.text());
                } else if (node instanceof Echo echo) {
                    String value = Value.str(interp.eval(echo.expr(), scope));
                    String rendered = echo.escape() ? Html.escape(value) : value;
                    interp.output(rendered.length());
                    out.append(rendered);
                } else if (node instanceof Partial partial) {
                    Template target = partials.get(partial.name());
                    if (target == null) {
                        throw new WhyError("unknown partial '" + partial.name() + "'"
                                + Suggest.didYouMean(partial.name(), partials.keySet()),
                                file, partial.line());
                    }
                    try {
                        target.renderInto(out, interp, scope, partials, depth + 1);
                    } catch (WhyError e) {
                        throw e.at(file, partial.line());
                    }
                } else if (node instanceof Block block) {
                    List<Node> branch = Value.truthy(interp.eval(block.cond(), scope)) ? block.body() : block.otherwise();
                    renderNodes(out, branch, interp, scope, partials, depth);
                } else if (node instanceof Each each) {
                    renderEach(out, each, interp, scope, partials, depth);
                } else if (node instanceof Helper helper) {
                    List<Object> args = new ArrayList<>();
                    for (Ast.Expr arg : helper.args()) args.add(interp.eval(arg, scope));
                    why.lang.Block body = new TemplateBlock(helper, interp, scope, partials, depth);
                    // Helper output is markup the def already built, so it is not escaped again.
                    out.append(Value.str(interp.callFunction(helper.name(), args, body, helper.line())));
                }
            } catch (WhyError e) {
                throw e.locate(file, e.line());
            }
        }
    }

    private void renderEach(StringBuilder out, Each each, Interpreter interp, Scope scope,
                            Map<String, Template> partials, int depth) {
        Object value = interp.eval(each.expr(), scope);
        if (value instanceof List<?> list && !list.isEmpty()) {
            int index = 0;
            for (Object item : list) {
                interp.step();
                Scope child = iteration(scope, item, index, list.size());
                renderNodes(out, each.body(), interp, child, partials, depth);
                index++;
            }
        } else if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                interp.step();
                Scope child = iteration(scope, entry.getValue(), index, map.size());
                child.define("@key", entry.getKey());
                renderNodes(out, each.body(), interp, child, partials, depth);
                index++;
            }
        } else if (value instanceof List<?> || value instanceof Map<?, ?> || !Value.truthy(value)) {
            renderNodes(out, each.otherwise(), interp, scope, partials, depth);
        } else {
            renderNodes(out, each.body(), interp, iteration(scope, value, 0, 1), partials, depth);
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
        validate(partials, Set.of());
    }

    /** Also checks that every custom block names a def the page can call. */
    public void validate(Map<String, Template> partials, Set<String> helpers) {
        validateNodes(nodes, partials, helpers);
    }

    private void validateNodes(List<Node> nodes, Map<String, Template> partials, Set<String> helpers) {
        for (Node node : nodes) {
            if (node instanceof Partial partial) {
                if (!partials.containsKey(partial.name())) {
                    throw new WhyError("unknown partial '" + partial.name() + "'"
                            + Suggest.didYouMean(partial.name(), partials.keySet()), file, partial.line());
                }
            } else if (node instanceof Helper helper) {
                if (!helpers.contains(helper.name())) {
                    throw new WhyError("unknown block helper '" + helper.name() + "'"
                            + Suggest.didYouMean(helper.name(), helpers), file, helper.line());
                }
                validateNodes(helper.body(), partials, helpers);
                validateNodes(helper.otherwise(), partials, helpers);
            } else if (node instanceof Block block) {
                validateNodes(block.body(), partials, helpers);
                validateNodes(block.otherwise(), partials, helpers);
            } else if (node instanceof Each each) {
                validateNodes(each.body(), partials, helpers);
                validateNodes(each.otherwise(), partials, helpers);
            }
        }
    }

    /** A template block as a def sees it: calling it renders the body in the caller's scope. */
    private final class TemplateBlock implements why.lang.Block {
        private final Helper helper;
        private final Interpreter interp;
        private final Scope scope;
        private final Map<String, Template> partials;
        private final int depth;

        TemplateBlock(Helper helper, Interpreter interp, Scope scope,
                      Map<String, Template> partials, int depth) {
            this.helper = helper;
            this.interp = interp;
            this.scope = scope;
            this.partials = partials;
            this.depth = depth;
        }

        /** @param args the first argument becomes {@code it} in the rendered body */
        @Override
        public Object yield(List<Object> args) {
            Scope body = args.isEmpty() ? scope : scope.child();
            if (!args.isEmpty()) body.define("it", args.get(0));
            return render(helper.body(), body);
        }

        @Override
        public Object inverse() {
            return render(helper.otherwise(), scope);
        }

        private String render(List<Node> nodes, Scope scope) {
            StringBuilder out = new StringBuilder();
            renderNodes(out, nodes, interp, scope, partials, depth + 1);
            return out.toString();
        }
    }
}
