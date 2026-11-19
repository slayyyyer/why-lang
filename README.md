# Why

[![CI](https://github.com/slayyyyer/why-lang/actions/workflows/ci.yml/badge.svg)](https://github.com/slayyyyer/why-lang/actions/workflows/ci.yml)

A small, Ruby-flavored page language for the JVM. MVC at the scale of a single page: imports, a
controller, and a view in one file, with the URL as the filename and SQLite data access built in.
Controller and view are separate sections. Drop it in a folder and it's live. No router, no build
step, no framework boilerplate.

> Named in tribute to _why the lucky stiff_, whose tiny, whimsical tools (Camping, Shoes, *Why's (Poignant) Guide to Ruby*) made programming feel like play.

Requires JDK 21+ and Maven 3.9+ on `PATH`:

```bash
mvn package
java -jar target/why.jar ./site
# Why is serving /…/site at http://localhost:8080
```

If port 8080 is taken, add `--port 3000`. For a public deployment, add `--production` to return a
generic 500 page without paths, source, SQL details or stack traces; the details are still written
to the server log. Behind TLS, add `--secure-cookies` to mark the session cookie `Secure`.

To parse and check every page without serving it:

```bash
java -jar target/why.jar check ./site
```

`check` reports unresolved imports and partials, duplicate `def`s, undefined function calls and URL
collisions, then prints a page map. It exits 1 when it finds anything and does not touch the
database.

## Website

The project site lives in `docs/` as plain HTML/CSS with no build step. Pushes to `main` that touch
`docs/` or `.github/workflows/pages.yml` publish it to GitHub Pages. In the repository settings,
set **Pages → Build and deployment → Source** to **GitHub Actions**.

Preview it locally with the JDK's built-in static server. On this Windows machine (Git Bash), `-d`
needs an absolute path:

```bash
.tools/jdk/bin/jwebserver -p 4000 -d "$(cygpath -m "$PWD/docs")"
```

`jwebserver` rejects a relative `-d` path on every OS. To avoid paths entirely, change into the
folder and let it use its default directory — with the JDK on your `PATH`:

```bash
cd docs
jwebserver -p 4000
```

This working copy has no system-wide Java or Maven, so it carries a portable toolchain in the
gitignored `.tools/` folder. The same build from this folder:

```bash
export JAVA_HOME="$PWD/.tools/jdk"; export PATH="$JAVA_HOME/bin:$PATH"
.tools/mvn/bin/mvn package
.tools/jdk/bin/java -jar target/why.jar ./site --port 3000
```

Status: **a proof of concept**. The page format, controller language, views, SQLite access, forms,
redirects, one-shot flash messages, diagnostics and `why check` are implemented. This is not a
production runtime.

## Security

Why pages are **trusted executable code**, not passive templates. They run inside the server process
with access to request data and the configured database, and there is no sandbox. **Never deploy or
accept page uploads from untrusted users**: an untrusted page is unsafe and is equivalent to running
untrusted code on the server.

For an exposed deployment:

- terminate TLS in a reverse proxy and use `--secure-cookies`; do not expose the plain HTTP listener
- run Why as a dedicated, non-root service account with least-privilege filesystem permissions
- keep the SQLite database outside the web root, using an explicit `--db` path
- keep regular, protected backups outside the web root and test restoring them
- use `--production` and review the service's logs and network access

See [SECURITY.md](SECURITY.md) for the full security policy and deployment checklist.

## M0 — the shell

- Java 21, Maven, one runnable `target/why.jar` (SQLite JDBC shaded in).
- `com.sun.net.httpserver.HttpServer` with a virtual-thread executor.
- Every `.why` file under the site folder is served at its path: `/` is `index.why`,
  `/posts/archive` is `posts/archive.why`, and `/about` also finds `about.why`. Files whose name
  starts with `_` are import-only and have no URL.
- Allowlisted public assets (HTML, CSS, JS, JSON, SVG, images, fonts and text) are served with
  content types. Database, schema, backup and other files are not public.
- 404 page; a 500 page with `file:line`, the offending source line and a caret, the partial or
  import chain that led there, did-you-mean hints, and the message in development; `--production`
  returns a generic 500 while logging the details server-side; one request log line per request.
- Hard per-request limits: 64 KiB bodies, 16 KiB/100 headers, 32 concurrent requests, a 10-second
  timeout, 100k interpreter steps and 1M rendered characters.
- `java -jar why.jar [site] [--port N] [--db FILE] [--production] [--secure-cookies]`;
  `java -jar why.jar check [site]` parses and checks the whole site without serving it.

## M1 — the language and views

- The three page sections with ordering validation and exact file/line errors.
- `imports:` give you partials (`{{> name}}`) and controller functions (`def`s), recursively,
  with cycle detection.
- A Ruby-flavored core language (below).
- Handlebars-flavored views (below).
- A `db` global over SQLite, plus `request` and `params` globals.

## M2 — the first write path

`/guestbook` in the demo site is a writable page: a POST form, one bound `db.exec` insert, and a
303 redirect back to the same page. It adds:

- `form` — fields from an `application/x-www-form-urlencoded` POST body (empty on GET); also
  `request.form`
- `redirect("/path")` — stop the page and answer `303 See Other`; the target must be a
  path-absolute same-origin path
- `flash` — one-shot messages carried across the redirect: `flash.set("notice", "…")`, then
  `{{flash.notice}}` on the next page request

The page-model friction found while building it is logged in [FRICTION.md](FRICTION.md).

## File format

A page has up to three sections. Each label is a line containing exactly the label at column 0:

```
imports:
header
footer

controller:
posts = db.query("select * from posts order by id desc")
title = "Latest posts"

view:
{{> header}}
<h1>{{title}}</h1>
{{#each posts}}
  <h2>{{it.title}}</h2>
{{/each}}
{{> footer}}
```

- Sections are optional but must appear in the order `imports`, `controller`, `view`.
- A file with no labels at all is a view (plain HTML works).
- Any other `word:` line at column 0 before `view:` is an error with file and line.
- Once `view:` starts, everything is template text to the end of the file.
- Each import is one bare name. It resolves to `<name>.why` next to the importing file first,
  then in the site root.
- A file whose name starts with `_` is import-only: `_header.why` is listed as `_header` in
  `imports:` (and used as `{{> _header}}`), but `/_header` and `/_header.why` both answer 404.
  Static files are covered too, so `_notes.css` is not served.

Imports are transitive. An imported page's `controller:` contributes only its `def`s; its
top-level statements do not run when you import it. `def`s are hoisted, so a page may call one
before its definition line.

## Language

```ruby
# values
n = 42
pi = 3.14
name = "why #{n}"          # double quotes interpolate
raw = 'no #{interpolation}'  # single quotes are literal
list = [1, 2, 3]
post = {title: "Why", views: 3}

# operators: + - * / % == != < <= > >= && || ! and or not
# integer / integer is integer division, like Ruby

# control flow
if n > 10
  label = "big"
elsif n == 0
  label = "nothing"
else
  label = "small"
end

unless done
  title = "still going"
end

while n > 0
  n -= 1
  if n == 2
    next
  end
  if n == 0
    break
  end
end

# functions
def shout(text)
  text.upcase + "!"
end

# blocks close over the surrounding scope
total = 0
[1, 2, 3].each do |number|
  total += number
end
doubled = [1, 2, 3].map do |number|
  number * 2
end
```

Only `nil` and `false` are falsy (Ruby rules: `0` and `""` are truthy). Undefined names read as
`nil`. `+` with a string on either side concatenates.

Built-in methods:

- Strings: `length`/`size`, `upcase`, `downcase`, `strip`, `split`, `include?`, `start_with?`,
  `ends_with?`, `to_i`, `to_f`, `reverse`, `replace`, `capitalize`
- Lists: `length`/`size`, `first`, `last`, `push`/`append`, `join`, `include?`, `reverse`, `sum`,
  `each`/`map`/`select` with a block
- Hashes: `keys`, `values`, `length`/`size`, `has_key?`, `each` with a block
- Numbers: `to_i`, `to_f`, `abs`, `round`
- Functions: `str(x)`, `int(x)`, `float(x)`, `redirect(path)`

## Views

```
{{title}}              escaped with the same expression grammar as the controller
{{{title}}}            raw
{{& title}}            raw
{{! a comment }}

{{#if posts.length > 0}} … {{else}} … {{/if}}
{{#unless done}} … {{/unless}}

{{#each posts}}
  <h2>{{it.title}}</h2>
  <p>#{{@index}} · first={{@first}} · last={{@last}}</p>
{{else}}
  <p>No posts.</p>
{{/each}}

{{> header}}
```

Inside `{{#each}}` the current item is `it`; `@index`, `@first` and `@last` are available, and
`@key` when iterating a hash. A mustache holds a real expression, so `{{shout(post.title)}}` and
`{{posts.length}}` work. Only `nil` and `false` are falsy in views too, so an empty list's `length`
is `0` and still truthy; use `posts.length > 0` for an empty state.

## The `db` global

SQLite, at `<site>/data.db` by default (`--db` overrides it); point `--db` outside the site folder
for an exposed deployment. One connection per request.

```ruby
posts = db.query("select * from posts order by id desc")   # list of hashes
post  = db.get("select * from posts where id = ?", id)     # first row or nil
n     = db.exec("insert into posts (title) values (?)", title)
```

Rows are hashes keyed by column label, so `{{it.title}}` and `post.title` work. Arguments are
bound as prepared-statement parameters.

If the database file does not exist when the server starts and `<site>/schema.sql` exists, the
script is applied once so a site folder is self-contained.

## Request globals

- `params` — query parameters as strings, e.g. `/post?id=3` gives `params.id == "3"`.
- `form` — POSTed form fields as strings, empty on GET. Only `application/x-www-form-urlencoded`
  bodies are read (64 KiB cap).
- `request` — a hash with `method`, `path`, `query`, `params` and `form`.
- `flash` — one-shot messages. `flash.set("notice", "…")` stores a value for the next page
  request, where `{{flash.notice}}` can read it; the request after that no longer has it. Messages
  live in a bounded, expiring in-memory store keyed by a server-issued `why_session` cookie;
  restarting the server drops them.

## Project layout

```
pom.xml
site/                      demo site: pages, partials, CSS, schema.sql
docs/                      project site, published to GitHub Pages
.github/workflows/ci.yml     tests and demo site check
.github/workflows/pages.yml  GitHub Pages publishing
src/main/java/why/
  Main.java                CLI
  WhyServer.java           HTTP handler, errors, static files
  Site.java                the .why extension and URL → file resolution
  PageLoader.java          imports/controller/view section splitting
  PageCompiler.java        import resolution and parsing
  Program.java             a parsed page
  Checker.java             parse-and-check site map
  lang/                    Lexer, Parser, Ast, Interpreter, Scope, Value, Db
  tpl/                     Template, TemplateParser
  util/Html.java
src/test/java/why/         JUnit 5 tests (language, templates, pages, HTTP)
```

## Scope

Milestones M0–M2, kept deliberately small. Classes, packages, bytecode, Truffle, sandboxing and
performance work are out of scope. Decisions, assumptions and deferred features live in
[NOTES.md](NOTES.md); M2's awkward edges are in [FRICTION.md](FRICTION.md).
