# Why

A small, Ruby-flavored, old-school-PHP-style page language for the JVM. Drop a `.why` file in a
folder, hit its URL, it runs. No router, no build step, no framework boilerplate.

Requires JDK 21+ and Maven 3.9+ on `PATH`:

```bash
mvn package
java -jar target/why.jar ./site
# Why is serving /…/site at http://localhost:8080
```

If port 8080 is taken, add `--port 3000`.

To compile every page without serving it:

```bash
java -jar target/why.jar check ./site
```

`check` reports unresolved imports and partials, duplicate `def`s, undefined function calls and URL
collisions, then prints a page map. It exits 1 when it finds anything and does not touch the
database.

This working copy has no system-wide Java or Maven, so it carries a portable toolchain in the
gitignored `.tools/` folder. The same build from this folder:

```bash
export JAVA_HOME="$PWD/.tools/jdk"; export PATH="$JAVA_HOME/bin:$PATH"
.tools/mvn/bin/mvn package
.tools/jdk/bin/java -jar target/why.jar ./site --port 3000
```

Status: **M0 and M1 are done**, and the demo's M2 write path (a guestbook) is in place. This is
a proof of concept, not a production runtime.

## M0 — the shell

- Java 21, Maven, one runnable `target/why.jar` (SQLite JDBC shaded in).
- `com.sun.net.httpserver.HttpServer` with a virtual-thread executor.
- Every `.why` file under the site folder is served at its path: `/` is `index.why`,
  `/posts/archive` is `posts/archive.why`, and `/about` also finds `about.why`. Files whose name
  starts with `_` are import-only and have no URL.
- Other files are served as-is (CSS, JS, images, fonts) with content types.
- 404 page; a 500 page with `file:line`, the offending source line and a caret, the partial or
  import chain that led there, did-you-mean hints, and the message; one request log line per
  request.
- `java -jar why.jar [site] [--port N] [--db FILE]`; `java -jar why.jar check [site]` compiles
  the whole site without serving it.

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
- `redirect(path)` — stop the page and answer `303 See Other` with `Location: path`

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

{{#if posts.length}} … {{else}} … {{/if}}
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
`{{posts.length}}` work.

## The `db` global

SQLite, at `<site>/data.db` by default (`--db` overrides it). One connection per request.

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

## Project layout

```
pom.xml
site/                      demo site: pages, partials, CSS, schema.sql
src/main/java/why/
  Main.java                CLI
  WhyServer.java           HTTP handler, errors, static files
  Site.java                the .why extension and URL → file resolution
  PageLoader.java          imports/controller/view section splitting
  PageCompiler.java        import resolution and compilation
  Program.java             a compiled page
  Checker.java             compile-only site check and page map
  lang/                    Lexer, Parser, Ast, Interpreter, Scope, Value, Db
  tpl/                     Template, TemplateParser
  util/Html.java
src/test/java/why/         JUnit 5 tests (language, templates, pages, HTTP)
```

## Scope

Milestones M0–M2, kept deliberately small. Classes, packages, bytecode, Truffle, sandboxing and
performance work are out of scope. Decisions, assumptions and deferred features live in
[NOTES.md](NOTES.md); M2's awkward edges are in [FRICTION.md](FRICTION.md).
