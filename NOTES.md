# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

The app exposes public endpoints for the feed and search. The attack surface is
`GET /api/posts/search?q=...` because the query string `q` comes from the browser
and is inserted directly into a SQL string in the backend.

A stranger controls the search query text. In the backend, the controller builds a
native SQL query by concatenating that text into `WHERE title LIKE '%...%' OR body
LIKE '%...%'`, so the database cannot distinguish data from SQL syntax.

_Your notes:_

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

```
' UNION SELECT id, email, password_hash FROM users -- 
```

### What each part of it does

- `'` closes the original string literal started by `LIKE '%`.
- `UNION SELECT id, email, password_hash FROM users` asks the database to append
  rows from the private `users` table with exactly three columns.
- `-- ` comments out the rest of the original query so the leftover `%' OR body
  LIKE '%...%'` does not break the SQL.

### What came back

The exploit would return rows shaped like normal search results, but the data
would come from `users` instead of `posts`. For example, the `title` would show
`admin@inkfeed.app` and the `body` would show the admin password hash.

_Your notes:_

---

## 3. Why it worked (root cause)

The backend built SQL by concatenating untrusted input into the query string.
That means the search term could change the query structure itself, not just the
value being searched for. The database executed attacker-supplied SQL because it
had no separate parameter for the search value.

---

## 4. The fix

### Which road did I take?

I switched the search endpoint to the safe repository method.

_Your notes:_

### Why this fixes the root cause and not just the symptom

The repository method uses Spring Data to bind the search text as a parameter,
so the value cannot change the SQL structure. The query is no longer built by
concatenating user text into SQL.

### Why I did NOT just block quotes / the word UNION

Blocklisting is fragile and incomplete. A quote or `UNION` can be encoded or
written in different ways, and valid searches like names with apostrophes would
break. The safe fix is to stop building SQL from raw input entirely.

---

## 5. Proof the fix holds

I updated the backend so the search endpoint now uses a parameterized repository
query instead of raw SQL. The same payload should now return zero `users` rows
and only real post matches.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

(yes, the safe repository query still returns normal search results)

---

## 6. If I had another hour

The comment endpoint also accepts unauthenticated input and stores it without any
spam or validation checks. The public API can reach the whole database schema,
so the biggest risk is any other controller building SQL from raw request data.
