package why.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import why.WhyError;

/** Tree-walking evaluator for controllers and for expressions inside template tags. */
public final class Interpreter {

    private static final Object NEXT = new Object();
    private static final Object BREAK = new Object();

    private final Map<String, Function> functions = new HashMap<>();

    public Interpreter(Map<String, Ast.Def> defs, Scope globals) {
        for (Map.Entry<String, Ast.Def> entry : defs.entrySet()) {
            Ast.Def def = entry.getValue();
            functions.put(entry.getKey(), new Function(def.params(), def.body(), globals));
        }
    }

    public void exec(List<Ast.Stmt> body, Scope scope) {
        for (Ast.Stmt stmt : body) exec(stmt, scope);
    }

    /** Executes statements and returns the value of the last expression (used for blocks). */
    private Object execBody(List<Ast.Stmt> body, Scope scope) {
        Object last = null;
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ExprStmt e) {
                last = eval(e.expr(), scope);
            } else {
                exec(stmt, scope);
                last = null;
            }
        }
        return last;
    }

    private void exec(Ast.Stmt stmt, Scope scope) {
        if (stmt instanceof Ast.Assign assign) {
            Object value = eval(assign.value(), scope);
            if (!assign.op().equals("=")) {
                Object current = scope.get(assign.name());
                value = Value.binary(assign.op().substring(0, 1), current, value, assign.line());
            }
            scope.set(assign.name(), value);
        } else if (stmt instanceof Ast.ExprStmt e) {
            eval(e.expr(), scope);
        } else if (stmt instanceof Ast.If branch) {
            for (Ast.Branch candidate : branch.branches()) {
                if (Value.truthy(eval(candidate.cond(), scope))) {
                    exec(candidate.body(), scope);
                    return;
                }
            }
            exec(branch.otherwise(), scope);
        } else if (stmt instanceof Ast.While loop) {
            while (Value.truthy(eval(loop.cond(), scope))) {
                try {
                    exec(loop.body(), scope);
                } catch (BreakSignal stop) {
                    break;
                } catch (NextSignal ignored) {
                    // continue with the next iteration
                }
            }
        } else if (stmt instanceof Ast.Def def) {
            functions.put(def.name(), new Function(def.params(), def.body(), scope));
        } else if (stmt instanceof Ast.Return value) {
            throw new ReturnSignal(value.value() == null ? null : eval(value.value(), scope));
        } else if (stmt instanceof Ast.Break) {
            throw new BreakSignal();
        } else if (stmt instanceof Ast.Next) {
            throw new NextSignal();
        } else {
            throw new WhyError("cannot execute " + stmt.getClass().getSimpleName(), null, stmt.line());
        }
    }

    public Object eval(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.IntLit lit) return lit.value();
        if (expr instanceof Ast.FloatLit lit) return lit.value();
        if (expr instanceof Ast.StrLit lit) return lit.value();
        if (expr instanceof Ast.BoolLit lit) return lit.value();
        if (expr instanceof Ast.NilLit) return null;
        if (expr instanceof Ast.Interp interp) {
            StringBuilder out = new StringBuilder();
            for (Ast.Expr part : interp.parts()) out.append(Value.str(eval(part, scope)));
            return out.toString();
        }
        if (expr instanceof Ast.ArrayLit array) {
            List<Object> out = new ArrayList<>();
            for (Ast.Expr item : array.items()) out.add(eval(item, scope));
            return out;
        }
        if (expr instanceof Ast.HashLit hash) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Ast.Pair pair : hash.pairs()) out.put(eval(pair.key(), scope), eval(pair.value(), scope));
            return out;
        }
        if (expr instanceof Ast.Var var) return scope.get(var.name());
        if (expr instanceof Ast.Get get) return property(eval(get.target(), scope), get.name(), get.line());
        if (expr instanceof Ast.Index index) return index(eval(index.target(), scope), eval(index.index(), scope), index.line());
        if (expr instanceof Ast.Call call) return call(call, scope);
        if (expr instanceof Ast.Binary binary) return binary(binary, scope);
        if (expr instanceof Ast.Unary unary) {
            Object value = eval(unary.operand(), scope);
            return switch (unary.op()) {
                case "not" -> !Value.truthy(value);
                case "-" -> Value.negate(value, unary.line());
                default -> throw new WhyError("unknown operator '" + unary.op() + "'", null, unary.line());
            };
        }
        throw new WhyError("unknown expression", null, expr.line());
    }

    private Object binary(Ast.Binary expr, Scope scope) {
        if (expr.op().equals("&&")) {
            Object left = eval(expr.left(), scope);
            return Value.truthy(left) ? eval(expr.right(), scope) : left;
        }
        if (expr.op().equals("||")) {
            Object left = eval(expr.left(), scope);
            return Value.truthy(left) ? left : eval(expr.right(), scope);
        }
        return Value.binary(expr.op(), eval(expr.left(), scope), eval(expr.right(), scope), expr.line());
    }

    // ---- calls ---------------------------------------------------------

    private Object call(Ast.Call call, Scope scope) {
        List<Object> args = new ArrayList<>();
        for (Ast.Expr arg : call.args()) args.add(eval(arg, scope));
        Function block = call.block() == null
                ? null
                : new Function(call.block().params(), call.block().body(), scope);

        if (call.target() == null) {
            Function fn = functions.get(call.name());
            if (fn != null) return invoke(fn, args);
            return builtin(call.name(), args, call.line());
        }
        return method(eval(call.target(), scope), call.name(), args, block, call.line());
    }

    private Object invoke(Function fn, List<Object> args) {
        Scope scope = fn.closure().child();
        for (int i = 0; i < fn.params().size(); i++) {
            scope.define(fn.params().get(i), i < args.size() ? args.get(i) : null);
        }
        try {
            return execBody(fn.body(), scope);
        } catch (ReturnSignal signal) {
            return signal.value;
        }
    }

    private Object builtin(String name, List<Object> args, int line) {
        Object arg = args.isEmpty() ? null : args.get(0);
        return switch (name) {
            case "str" -> Value.str(arg);
            case "int" -> Value.toLong(arg);
            case "float" -> Value.toDouble(arg);
            default -> throw new WhyError("undefined function '" + name + "'", null, line);
        };
    }

    private Object method(Object receiver, String name, List<Object> args, Function block, int line) {
        if (receiver == null) throw new WhyError("nil has no method '" + name + "'", null, line);
        Object first = args.isEmpty() ? null : args.get(0);

        if (receiver instanceof Db db) {
            if (!(first instanceof String sql)) {
                throw new WhyError("db." + name + " needs a SQL string", null, line);
            }
            List<Object> params = args.isEmpty() ? List.of() : args.subList(1, args.size());
            return switch (name) {
                case "query" -> db.query(sql, params);
                case "exec" -> db.exec(sql, params);
                case "get" -> db.get(sql, params);
                default -> throw new WhyError("'db' has no method '" + name + "' (try query, exec or get)", null, line);
            };
        }

        if (receiver instanceof List<?> raw) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            return switch (name) {
                case "length", "size" -> (long) list.size();
                case "first" -> list.isEmpty() ? null : list.get(0);
                case "last" -> list.isEmpty() ? null : list.get(list.size() - 1);
                case "push", "append" -> {
                    list.add(first);
                    yield list;
                }
                case "join" -> join(list, first);
                case "include?" -> list.stream().anyMatch(item -> Value.equal(item, first));
                case "reverse" -> {
                    List<Object> copy = new ArrayList<>(list);
                    Collections.reverse(copy);
                    yield copy;
                }
                case "sum" -> {
                    Object total = 0L;
                    for (Object item : list) total = Value.binary("+", total, item, line);
                    yield total;
                }
                case "each" -> {
                    requireBlock(block, "each", line);
                    for (Object item : new ArrayList<>(list)) {
                        if (step(block, Collections.singletonList(item)) == BREAK) break;
                    }
                    yield null;
                }
                case "map" -> {
                    requireBlock(block, "map", line);
                    List<Object> out = new ArrayList<>();
                    for (Object item : list) {
                        Object result = step(block, Collections.singletonList(item));
                        if (result == BREAK) break;
                        out.add(result == NEXT ? null : result);
                    }
                    yield out;
                }
                case "select" -> {
                    requireBlock(block, "select", line);
                    List<Object> out = new ArrayList<>();
                    for (Object item : list) {
                        Object result = step(block, Collections.singletonList(item));
                        if (result == BREAK) break;
                        if (result != NEXT && Value.truthy(result)) out.add(item);
                    }
                    yield out;
                }
                default -> throw new WhyError("list has no method '" + name + "'", null, line);
            };
        }

        if (receiver instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) raw;
            return switch (name) {
                case "keys" -> new ArrayList<>(map.keySet());
                case "values" -> new ArrayList<>(map.values());
                case "length", "size" -> (long) map.size();
                case "has_key?" -> map.containsKey(first);
                case "each" -> {
                    requireBlock(block, "each", line);
                    for (Map.Entry<Object, Object> entry : new ArrayList<>(map.entrySet())) {
                        if (step(block, Arrays.asList(entry.getKey(), entry.getValue())) == BREAK) break;
                    }
                    yield null;
                }
                default -> throw new WhyError("hash has no method '" + name + "'", null, line);
            };
        }

        if (receiver instanceof String text) {
            String arg = Value.str(first);
            return switch (name) {
                case "length", "size" -> (long) text.length();
                case "upcase" -> text.toUpperCase(Locale.ROOT);
                case "downcase" -> text.toLowerCase(Locale.ROOT);
                case "strip" -> text.strip();
                case "split" -> split(text, arg);
                case "include?" -> text.contains(arg);
                case "start_with?" -> text.startsWith(arg);
                case "ends_with?" -> text.endsWith(arg);
                case "to_i" -> Value.toLong(text);
                case "to_f" -> Value.toDouble(text);
                case "reverse" -> new StringBuilder(text).reverse().toString();
                case "replace" -> text.replace(arg, args.size() > 1 ? Value.str(args.get(1)) : "");
                case "capitalize" -> text.isEmpty()
                        ? text
                        : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
                default -> throw new WhyError("string has no method '" + name + "'", null, line);
            };
        }

        if (receiver instanceof Number number) {
            return switch (name) {
                case "to_i" -> number.longValue();
                case "to_f" -> number.doubleValue();
                case "abs" -> {
                    if (receiver instanceof Double || receiver instanceof Float) yield Math.abs(number.doubleValue());
                    yield Math.abs(number.longValue());
                }
                case "round" -> Math.round(number.doubleValue());
                default -> throw new WhyError("number has no method '" + name + "'", null, line);
            };
        }

        throw new WhyError(Value.typeName(receiver) + " has no method '" + name + "'", null, line);
    }

    /** {@code a.b} without parentheses: a hash key first, then a zero-argument method. */
    private Object property(Object target, String name, int line) {
        if (target == null) return null;
        if (target instanceof Map<?, ?> map) {
            if (map.containsKey(name)) return map.get(name);
            return switch (name) {
                case "length", "size" -> (long) map.size();
                case "keys" -> new ArrayList<>(map.keySet());
                case "values" -> new ArrayList<>(map.values());
                default -> null;
            };
        }
        if (target instanceof Db) {
            throw new WhyError("'db' methods need parentheses, as in db.query(\"select 1\")", null, line);
        }
        return method(target, name, List.of(), null, line);
    }

    private Object index(Object target, Object key, int line) {
        if (target instanceof List<?> raw) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            long at = Value.toLong(key);
            if (at < 0) at += list.size();
            if (at < 0 || at >= list.size()) return null;
            return list.get((int) at);
        }
        if (target instanceof Map<?, ?> map) return map.get(key);
        if (target == null) return null;
        throw new WhyError("cannot index " + Value.typeName(target), null, line);
    }

    private Object step(Function block, List<Object> args) {
        try {
            return invoke(block, args);
        } catch (NextSignal ignored) {
            return NEXT;
        } catch (BreakSignal ignored) {
            return BREAK;
        }
    }

    private static void requireBlock(Function block, String name, int line) {
        if (block == null) {
            throw new WhyError("'" + name + "' needs a block, as in list." + name + " do |item| ... end", null, line);
        }
    }

    private static String join(List<Object> list, Object separator) {
        String sep = Value.str(separator);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(sep);
            out.append(Value.str(list.get(i)));
        }
        return out.toString();
    }

    private static List<Object> split(String text, String separator) {
        String[] parts = separator.isEmpty() ? text.split("\\s+") : text.split(Pattern.quote(separator), -1);
        return new ArrayList<>(Arrays.asList(parts));
    }

    // ---- control flow ---------------------------------------------------

    private static final class ReturnSignal extends RuntimeException {
        private final Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private static final class BreakSignal extends RuntimeException {
        BreakSignal() {
            super(null, null, false, false);
        }
    }

    private static final class NextSignal extends RuntimeException {
        NextSignal() {
            super(null, null, false, false);
        }
    }
}
