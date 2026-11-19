# Notes

## Assumptions made for M0/M1

The specification I was given ends in the middle of the `imports:` rules, so these were my calls:

- **CLI**: `java -jar why.jar [site] [--port N] [--db FILE]`; default port 8080, default database
  `<site>/data.db`.
- **`schema.sql` bootstrap**: if the database file does not exist at startup, `<site>/schema.sql`
  is executed once. This keeps the demo site self-contained without a separate setup step.
- **Imported controllers** contribute their top-level `def`s only; their top-level statements are
  not executed. Imports are transitive. The importing page's own defs win over imported ones, and
  earlier imports win over later ones.
- **Extension-free URLs**: `/about` resolves to `about.why`, in addition to `/about.why`.
  Directories resolve to `index.why`.
- **Undefined variables** read as `nil` instead of raising, which keeps views forgiving.
- **String concatenation**: `+` with a string on either side concatenates rather than raising.
- **Numeric equality**: `1 == 1.0` is true.
- **`not` precedence**: `not a == b` is parsed as `not (a == b)`. Ruby's `!` binds much tighter;
  this PoC prefers the readable form.

## Deliberately left out (candidates for later)

### Language

- Modifier `if`/`unless` (`x = 1 if ok`), `case/when`, ranges, symbols, string formatting.
- `puts` and any other debugging output from a page.
- Keyword arguments, default parameters, splats, functions as first-class values.
- Classes and modules (explicit non-goal), exceptions (`begin`/`rescue`), `yield`.
- Assignment to `hash.key` or `list[0]`.
- Arity checking: missing arguments become `nil`, extra arguments are ignored.
- `return` inside a block returns from the block, not from the enclosing function.

### Templates

- Whitespace control (`{{~ … ~}}`), `{{#with}}`, helpers, `{{else if}}`, partial arguments,
  inline partials.
- Recursive partials are not detected; a self-referencing partial recurses until the stack ends.
- Dynamic partial names (`{{> (expr)}}`).

### Server and runtime

- Hot reload / compile cache: every request re-reads and recompiles the page. Fast enough for a
  PoC, and it makes "edit and refresh" trivially correct.
- Urlencoded POST bodies are parsed into `form`, and `redirect(path)` answers 303. Multipart
  bodies, uploads, cookies, sessions/flash, custom headers and other statuses are still out.
  The awkward parts are recorded in [FRICTION.md](FRICTION.md).
- Custom 404 pages, directory listings, configurable index files.
- A JSON helper (`render json:`). The spec allows Jackson or Gson, but nothing needed JSON yet,
  so the dependency was left out.
- Multiple sites per server, TLS, access control, request timeouts, size limits.
- Sandboxing and security hardening (explicit non-goal): pages can run arbitrary SQL and read the
  whole database.

### Database

- Transactions, connection pooling (one SQLite connection per request), migrations beyond the
  first-run `schema.sql`.
- H2 support. The `db` global is hard-wired to SQLite for now, although the driver is the only
  thing that would need to change.
