# NOTES.md — The Breach Report

## 1. First impressions

The public feed is readable without credentials. A stranger controls the `q`
query parameter sent to `GET /api/posts/search` and the `authorName` and `body`
JSON fields sent to `POST /api/posts/{id}/comments`. The search input is the
dangerous database boundary: the backend inserts `q` directly into native SQL.

## 2. Reproducing the breach

### What I typed and where

I entered this exact payload in the feed search box. There is one trailing space
after `--`.

```text
' UNION SELECT id, email, password_hash FROM users --␠
```

Here `␠` denotes the final ASCII space character; it is not a literal symbol in
the payload.

### What each part does

- `'` closes the string in the original `LIKE '%...%'` expression.
- `UNION` appends rows from a second `SELECT` to the public post rows.
- `SELECT id, email, password_hash FROM users` matches the original query's
  three-column shape (`id`, `title`, `body`). The UI therefore renders an email
  as a result title and a password hash as its body.
- `-- ` starts an SQL line comment. It hides the trailing `%' OR body LIKE ...`
  text that the controller would otherwise append and that would make the SQL
  invalid.

### What came back

The endpoint returned 11 results: five normal post rows plus all six user rows.
For example:

```text
admin@inkfeed.app
$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK
```

Browser evidence: [public/sql-injection-before-fix.png](public/sql-injection-before-fix.png)

## 3. Why it worked (root cause)

The controller constructed a native SQL statement by concatenating the
untrusted `q` value into the statement text. Once concatenated, the database
could not distinguish the intended search data from attacker-supplied SQL
syntax, so it parsed and executed the injected `UNION SELECT`.

## 4. The fix

### Which road I took

The safe Spring Data repository method:
`findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)`.

### Why this fixes the root cause and not just the symptom

Spring Data constructs the query and binds each `q` as a value. The query shape
is fixed before the value reaches the database. Quotes, `UNION`, and comment
markers inside `q` remain literal search text and cannot become SQL commands.

### Why I did not block quotes or the word UNION

Blocklists address spellings rather than the trust-boundary mistake. Attackers
can use alternate syntax or encodings, and legitimate searches such as
`O'Brien` would be damaged. Binding the value fixes the cause without guessing
which characters users may type.

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

Zero results. `admin@inkfeed.app` and every other user row were absent.
Browser evidence: [public/sql-injection-after-fix.png](public/sql-injection-after-fix.png)

A normal search (`pen`, `color`, `comic`) still returns the right posts:

Yes. `pen` returned the pen post and the color-theory post (whose body contains
“complementary palettes”); `color` returned the color-theory post; and `comic`
returned the comic-panels post. I also posted a comment containing a quote and
the word `UNION`; it was saved as literal comment text.

## 6. If I had another hour

The comment endpoint is intentionally unauthenticated and still needs abuse
controls and length validation. Its database write is not SQL-injectable in the
same way: it creates a `Comment` entity and calls Spring Data JPA `save`, which
binds field values instead of concatenating them into SQL. Stored HTML/script
content is another concern, although React escapes rendered text by default.

Defense in depth could also give this API a database account that cannot select
password hashes (or move authentication data behind a separate service/schema),
add rate limiting, validate request sizes, and avoid exposing detailed database
errors.
