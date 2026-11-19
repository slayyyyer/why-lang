package why;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import why.lang.Ast;
import why.lang.Parser;
import why.tpl.Template;
import why.tpl.TemplateParser;

/** Loads a page, resolves its imports and compiles everything into a {@link Program}. */
public final class PageCompiler {
    private final Site site;
    private final PageLoader loader;

    public PageCompiler(Site site) {
        this.site = site;
        this.loader = new PageLoader(site);
    }

    public Program compile(Path file) {
        Page page = loader.load(file);
        String display = site.relative(file);

        List<Ast.Stmt> body = new Parser(page.controllerSource(), display, page.controllerLine()).parseProgram();

        Map<String, Ast.Def> defs = new LinkedHashMap<>();
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.Def def) defs.put(def.name(), def);
        }

        Map<String, Template> partials = new LinkedHashMap<>();
        List<Path> stack = new ArrayList<>();
        stack.add(file.toAbsolutePath().normalize());
        collect(page, display, defs, partials, stack);

        Template view = TemplateParser.parse(page.viewSource(), display, page.viewLine());
        view.validatePartials(partials);
        for (Template partial : partials.values()) partial.validatePartials(partials);

        return new Program(body, defs, partials, view);
    }

    /**
     * Adds the imports' views as partials and their top-level {@code def}s as functions.
     * Imports are transitive; the importing page's own defs win over imported ones.
     * Top-level controller statements in an imported page are not executed.
     */
    private void collect(Page page, String display, Map<String, Ast.Def> defs,
                         Map<String, Template> partials, List<Path> stack) {
        for (Page.Import imp : page.imports()) {
            Path imported = resolve(page.file(), imp.name());
            if (imported == null) {
                throw new WhyError("cannot find import '" + imp.name() + "'", display, imp.line());
            }
            Path normalized = imported.toAbsolutePath().normalize();
            if (stack.contains(normalized)) {
                throw new WhyError("circular import '" + imp.name() + "'", display, imp.line());
            }

            Page importedPage = loader.load(imported);
            String importedDisplay = site.relative(imported);
            partials.putIfAbsent(imp.name(),
                    TemplateParser.parse(importedPage.viewSource(), importedDisplay, importedPage.viewLine()));

            List<Ast.Stmt> importedBody = new Parser(
                    importedPage.controllerSource(), importedDisplay, importedPage.controllerLine()).parseProgram();
            for (Ast.Stmt stmt : importedBody) {
                if (stmt instanceof Ast.Def def) defs.putIfAbsent(def.name(), def);
            }

            stack.add(normalized);
            collect(importedPage, importedDisplay, defs, partials, stack);
            stack.remove(stack.size() - 1);
        }
    }

    private Path resolve(Path pageFile, String name) {
        Path directory = pageFile.toAbsolutePath().getParent();
        Path local = directory == null ? null : directory.resolve(name + Site.PAGE_EXTENSION);
        if (local != null && Files.isRegularFile(local)) return local.normalize();
        Path inRoot = site.root().resolve(name + Site.PAGE_EXTENSION).normalize();
        return Files.isRegularFile(inRoot) ? inRoot : null;
    }
}
