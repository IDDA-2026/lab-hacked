# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_

InkFeed is a small community app: a public feed of posts, a search box, and open
commenting with no login required. A stranger controls anything they can type into
the search bar — that value is sent straight to `GET /api/posts/search?q=...` on
the backend.

The search endpoint is handled in a way that breaks basic security practice: the
controller builds a raw SQL string by concatenating the user's input directly into
the query. There is no validation, no parameter binding, and no separation between
data and code.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

```
' UNION SELECT id, email, password_hash FROM users--
```

(Pasted into the search bar on the feed at http://localhost:3000.)

### What each part of it does

Break your payload into pieces and explain each one. For example: what closes the
original string, what pulls in the other table, what hides the rest of the query.

_Your notes:_

- **`'`** — closes the opening quote of the original `LIKE '%...%'` clause, so the
  database stops treating our input as plain search text.
- **`UNION SELECT id, email, password_hash FROM users`** — runs a second query and
  merges its rows with the post search results. The column count matches what the
  endpoint expects (`id`, `title`, `body`), so the frontend renders the leaked
  data as normal search cards.
- **`--`** — starts an SQL line comment, so everything left on that line (the rest
  of the original query) is ignored.

The result is two queries in one: the original post search, plus whatever we
injected from the search bar, unified by `UNION`.

### What came back

What data appeared that should never have been there? Paste
a line or two. A screenshot is ideal.

_Your notes:_

All rows from the `users` table appeared in the search results, including the admin
account:

- **title:** `admin@inkfeed.app`
- **body:** `$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK`

Private emails and password hashes were rendered as if they were ordinary post
titles and bodies.

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_

The controller built SQL with string concatenation:

```java
"WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'"
```

Whatever a user typed in `q` became part of the query text itself. The database
could not tell the difference between the intended SQL structure and attacker
supplied SQL. There were no checks, no escaping, and no parameter binding — external
input was treated as executable code.

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

_Your notes:_

The safe repository method — `findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase`
in `PostRepository`.

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

_Your notes:_

Spring Data JPA generates a parameterized query and binds the search term as a
value, not as part of the SQL string. The query shape is fixed at compile time;
user input can only ever be matched as literal search text. It cannot close a
string, inject a `UNION`, or comment out the rest of the statement, because it is
never interpreted as SQL syntax.

### Why I did NOT just block quotes / the word UNION

_Your notes:_

Blocklisting characters or keywords is a whack-a-mole fix. Attackers can often
bypass filters with encoding, alternate syntax, or keywords you did not think to
ban. It also breaks legitimate searches — for example, a member named `O'Brien`
could no longer search for their own posts if quotes were stripped or rejected.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

_Your notes:_

The same payload (`' UNION SELECT id, email, password_hash FROM users--`) now
returns an empty list. No emails, no password hashes, no rows from the `users`
table appear in the search results. The injection no longer changes the query.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_(yes / no, and anything you noticed)_

**Yes.** Searching for `pen` still returns posts that mention pens. Searching for
`color` and `comic` returns the expected posts too. The fix blocks the attack
without breaking legitimate use.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_

**Comment endpoint** — not vulnerable to SQL injection (JPA saves the comment as
data, no string-built SQL), but it had no server-side validation on the comment
body. The frontend blocked empty submissions, yet a direct API call with
`{"body": ""}` would still create a comment. I added a backend check that rejects
null, empty, or whitespace-only bodies with a 400 response.

**Open API** — anyone can read the feed, search, and post comments with no login.
That is fine for a public community app, but it means there is no rate limiting or
abuse protection on comments.

**Password hashes in the database** — the real problem was not that they exist, but
that the search endpoint could reach them. With the parameterized fix, the public
API no longer has a path to the `users` table through search.

**Least privilege (not applied here)** — in distributed systems it is important to
ensure security and restrict services from direct access to specific tables. In a
system like this, where the whole API is built around one service, there is no
need for those restrictions — they would only limit functionality without a real
benefit. The right fix here was the parameterized query, not splitting database
access across roles.

**Blind SQL injection (conceptual)** — before the fix, a UNION attack was not the
only option. With boolean blind injection, an attacker never sees user-table data on
screen. Instead they inject a condition into the search, e.g.
`' AND (SELECT COUNT(*) FROM users WHERE email='admin@inkfeed.app')>0 AND '1'='1`,
and watch the result count: many posts returned means the email exists, zero results
means it does not. Same vulnerable concatenated query, but the leak is true/false
behaviour rather than rows rendered in the UI. After the parameterized fix, these
payloads are harmless literal search strings.
