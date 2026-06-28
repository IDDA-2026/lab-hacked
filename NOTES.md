# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

InkFeed is fully public — no login is needed to read posts, search, or
comment. That means every input a stranger can type anywhere in the app
is untrusted by definition; there's no "logged in, so probably safe"
assumption to lean on.

Two places a stranger's input reaches the backend directly:


The search box (GET /api/posts/search?q=...) — whatever I type gets
sent as the q parameter.
The comment form (POST /api/posts/{id}/comments) — whatever I type
gets sent as the comment body.


The search box is the one I focused on first, since the README pointed
at it directly and it clearly builds a query from raw text.

---

## 2. Reproducing the breach

What I've typed to test the vulnerability and where

Typed into the InkFeed search box on the feed page:

' UNION SELECT id, email, password_hash FROM users --

What each part of it does


' — closes the string literal early. The backend wraps my input
in '%...%' before it ever reaches the database. A single quote ends
that string right where it appears, so everything I type after it gets
parsed as real SQL instead of as search text.
UNION SELECT id, email, password_hash FROM users — pulls in the
other table. UNION stacks a second query's rows onto the first
query's rows, as long as both queries return the same number of
columns. I confirmed (from the actual SQL error message in the backend
terminal) that the original query selects exactly 3 columns:
id, title, body. So my injected SELECT also returns exactly 3
columns, just pulled from users instead of posts: id, email,
password_hash. The frontend has no idea the data came from a
different table — it just renders column 2 as the title and column 3
as the body, whatever values land there.
-- — hides the rest of the original query. After my input, the
backend still appends the rest of its hardcoded SQL (the closing %'
and OR body LIKE '%...%'). Without silencing it, that leftover text
would cause another syntax error. -- is a SQL line comment — it
tells the database to ignore everything after it on that line, so the
leftover SQL is never even parsed.


What came back

The search results rendered as normal-looking post cards, but the
"title" field showed user email addresses and the "body" field showed
password hashes — including admin@inkfeed.app and its password hash.
Screenshot taken.

![alt text](<Screenshot 2026-06-29 032323.png>)

---

## 3. Why it worked (root cause)

javaString sql = "SELECT id, title, body FROM posts " +
        "WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";

List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();

The search method builds the SQL query by concatenating the raw q
parameter straight into the query string with +. There is no
separation between "the query the developer wrote" and "the value a
stranger typed in" — by the time entityManager.createNativeQuery(sql)
runs, both are just one flat string of text.

The database has no concept of "this part was supposed to be a search
term." It just parses whatever characters are in front of it and
executes them as SQL. So when my input contained SQL syntax (a quote,
a UNION SELECT, a comment marker), the database didn't reject it as
"invalid data" — it correctly executed it as SQL, because by that point
it genuinely was SQL. The vulnerability isn't that the database is
broken; it's that the application handed the database a command it
never should have been allowed to write.

---

## ## 4. The fix

### Which road did I take?

Parameterized native query — I kept the native SQL (didn't switch to the
derived repository method), but replaced the string concatenation with a
bound parameter.

```java
String sql = "SELECT id, title, body FROM posts " +
        "WHERE title LIKE CONCAT('%', ?1, '%') OR body LIKE CONCAT('%', ?1, '%')";

List<Object[]> rows = entityManager.createNativeQuery(sql)
        .setParameter(1, q)
        .getResultList();
```

I went this way instead of the derived method because I wanted to keep
direct control over the exact SQL shape, and this still fully closes the
vulnerability — the safety doesn't come from which API you call, it
comes from whether the value is bound separately or concatenated into
the string.

### Why this fixes the root cause and not just the symptom

Before, `q` was glued into the SQL text with `+` before the query ever
reached the database — so the query's structure could change depending
on what I typed. Now, `?1` is a fixed placeholder that's part of the
query's structure from the start, and `.setParameter(1, q)` attaches the
actual value through a separate channel, after the query's shape is
already locked in.

The database now receives the query and the value as two different
things, sent through two different mechanisms. No matter what characters
`q` contains — a quote, the word UNION, a `--`, anything — it can only
ever be compared against `title`/`body` as a literal value. It cannot be
re-parsed as new SQL syntax, because by the time it reaches the
database, it's not sitting inside the SQL text at all. The error didn't
just stop happening; the underlying ability for data to change the
query's shape is gone.

### Why I did NOT just block quotes / the word UNION

Blocking specific characters or keywords only defends against the
attacks I already thought of. There's always another way to write the
same idea — different casing, a different SQL keyword that does the
same job, a different encoding — and I can't enumerate all of them in
advance. Blocklisting also breaks legitimate use: a user named O'Brien
typing their own name into search would get blocked or mangled, even
though they did nothing wrong. The parameterized query fixes the actual
cause (data and code were never separated) instead of reacting to one
specific symptom of it.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it:

' UNION SELECT id, email, password_hash FROM users --

Result: 0 results, no error, no leaked data. The entire string is now
treated as a single literal search term — since the query and the value
are bound separately, the database just checks whether any post's title
or body contains that literal text. No post does, so it correctly
returns nothing. No crash, no stack trace, nothing for an attacker to
learn from.

A normal search (pen, color, comic) still returns the right posts:

Yes. Searched pen and color after the fix — both returned the
same correct matching posts as before. The fix only changed how the
search term reaches the database, not what it matches against, so
normal search behavior is unaffected.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_
