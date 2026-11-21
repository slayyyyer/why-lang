# M2 friction log

Notes from adding the demo's writable guestbook: `site/guestbook.why`, one `guestbook` table,
a POST form, a bound `db.exec` insert, and a redirect back. Written while building it.

## What held up

- The whole write path is one page. Branching on `request.method` reads clearly: validate,
  insert, redirect; otherwise list and render.
- `form.name` and bound `db.exec` parameters were enough; no model layer or ORM was needed.
- Header/footer partials meant the page inherited the site chrome in three lines.

## Where the page model got in the way

1. **There is no response object, so `redirect` became a thrown signal.**
   A page cannot set a status or a header. `redirect(path)` throws `Redirect` through the
   interpreter; the server catches it and answers 303. That works, but every future response
   concern (status, content type, headers, cookies) needs another builtin, and 303 is hardcoded.

2. **`params` already meant "query string", so forms needed a second global.**
   `form` is separate, and duplicated at `request.form`. A page that wants "input from either
   place" has to choose by method; there is no merged view and no `$_REQUEST` equivalent.

3. **PRG needs a success channel, and there is no flash or session.**
   The thank-you is `/guestbook?posted=1`: visible, bookmarkable, spoofable, and gone on a clean
   refresh. A one-shot message would need state the page model does not have.
   *(Addressed after M2: `flash.set` plus an in-memory `why_session` cookie now carries the notice
   across the redirect, and the flag is gone.)*

4. **Validation has nowhere to live.**
   Empty fields simply fall through: no insert, no redirect, no message. Re-rendering with the
   submitted values and an error would mean inventing `old`/`error` variables and a per-page
   convention; the page format suggests nothing. Most form frameworks leave validation to the
   application, which is why every tutorial invents its own answer.

5. **The write branch is invisible.**
   The controller runs on GET and POST; the only guard is `request.method == "POST"`. A typo in
   the string silently gives the read path. There is no method-only page and no guard syntax.

6. **A failed insert aborts the page as a 500.**
   `db.exec` throws on a constraint or a lock, and there is no `rescue`, so the browser gets the
   error page and the submitted input is lost. Acceptable for a PoC, awkward the moment two
   people write at once.

7. **No date or time helpers; the timestamp is SQLite's.**
   The insert uses `datetime('now')` to avoid inventing a time API. Formatting or localizing it
   later is string slicing in the view. The page model has no clock.

8. **Schema changes are one-shot.**
   `schema.sql` only runs when `data.db` is missing, so adding the guestbook table meant deleting
   the database (and the old posts). There is no migration story in the page model, and no
   natural place to put one.

9. **POST plumbing is hardwired into the server.**
   Only `application/x-www-form-urlencoded`, read up to 64 KiB, parsed into `form`; pages cannot
   intercept a request, so each body shape is a `WhyServer` change.
   *(Addressed after M2: multipart bodies now fill `files` and `upload.save(folder)` writes under
   the site, which confirmed the diagnosis — the feature is a server change, not a page one. The
   upload work exposed its own friction. The parser is hand-rolled because the JDK has none; it
   holds the whole body in memory, so the 4 MiB cap is a memory budget rather than a stream; and
   it runs before the CSRF check, so an unauthenticated upload is still read and parsed in full.
   `Upload` carries `site.root()` to prove `save` stays inside the site, which leaves
   jar-packaged sites unable to save, and the parser turns the empty filename of an untouched
   `<input type="file">` into `nil` by convention. JSON bodies and request hooks still need
   `WhyServer`.)*

10. **No CSRF, spam, or rate-limit story.**
    Any POST from anywhere is accepted. That is fine for a local demo and a decision for a public
    one; the page has one natural place (`form`) and no token mechanism to put in it.
    *(Addressed after M2 for CSRF: `csrf.token` now rides an HttpOnly `why_csrf` cookie, and a
    POST without the matching `_token` field is rejected with 403 before the page runs. Spam and
    rate limits are still out; request slots and a timeout bound concurrency, not request rate.)*

11. **Redirect targets are hand-built strings.**
    `/guestbook?posted=1` is a literal. There is no URL/query helper, so a redirect carrying user
    input (`"/signed?name=" + name`) puts it into `Location` unencoded; `&`, spaces or non-ASCII
    would break it. The demo dodges this by redirecting only to a fixed path.

12. **Testing the write path is HTTP-only.**
    One integration test posts bytes and checks the 303; the insert itself was checked by hand
    against the running demo. There is no way to invoke a page with a synthetic request from the
    language or the CLI, so write pages sit outside the fast test loop.

13. **`{{#if count}}` is always true.**
    Ruby truthiness means `0` is truthy, so the natural "are there entries?" check renders an
    empty list instead of the empty state. The demo writes `{{#if count > 0}}`; every page has to
    remember that `if x` tests existence, not emptiness. A language with falsy zero is friendlier
    here, and the disagreement is now visible in template code.
