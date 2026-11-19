package why;

import java.util.List;
import java.util.Map;

import why.lang.Ast;
import why.lang.Interpreter;
import why.lang.Scope;
import why.tpl.Template;

/** A compiled page: controller statements, functions, partials and the view. */
public final class Program {
    private final List<Ast.Stmt> body;
    private final Map<String, Ast.Def> defs;
    private final Map<String, Template> partials;
    private final Template view;

    Program(List<Ast.Stmt> body, Map<String, Ast.Def> defs, Map<String, Template> partials, Template view) {
        this.body = body;
        this.defs = defs;
        this.partials = partials;
        this.view = view;
    }

    /** Runs the controller against the request globals, then renders the view. */
    public String render(Map<String, Object> globals) {
        Scope scope = new Scope(null);
        globals.forEach(scope::define);
        Interpreter interpreter = new Interpreter(defs, scope);
        interpreter.exec(body, scope);
        return view.render(interpreter, scope, partials);
    }
}
