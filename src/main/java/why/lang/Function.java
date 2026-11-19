package why.lang;

import java.util.List;

/** A user-defined function or a block: parameters, body and the scope it closes over. */
public record Function(List<String> params, List<Ast.Stmt> body, Scope closure) {}
