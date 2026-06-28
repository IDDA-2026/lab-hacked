# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_

The app serves a public feed of posts and allows anonymous comments. The attacker controls the search query parameter `q` for `/api/posts/search` and the comment body and author name for `/api/posts/{postId}/comments`.
The search endpoint uses `entityManager.createNativeQuery(...)` with a string built from `q`, so search input reaches the database directly.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

```
/api/posts/search?q=x'%20UNION%20SELECT%20id,%20email,%20password_hash%20FROM%20users--%20
```

### What each part of it does

- `x'` closes the original `LIKE '%...%'` string that the endpoint builds.
- `UNION SELECT id, email, password_hash FROM users` appends rows from the `users` table using the same 3-column shape as the original `SELECT id, title, body`.
- `-- ` comments out the remaining part of the original query (`%' OR body LIKE '%...`), so the injected query becomes valid SQL.
- The column count must match because the original query returns 3 columns; `UNION` requires the same number of columns and compatible types.

_Your notes:_

This payload turned the search into a union query that leaks rows from `users` instead of only searching `posts`.

### What came back

The result contained the admin email and password hash from the secret table, for example:

- `admin@inkfeed.app`
- `$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK`

_Your notes:_

The search endpoint returned data from the `users` table even though it is supposed to only return post titles and bodies.

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_

The backend concatenated the user-controlled `q` directly into a raw SQL string inside `PostController.search()`. Because `q` was not treated as a query parameter, the database parsed the injected text as part of the SQL command. This allowed `UNION SELECT` to add rows from `users` and `--` to hide the rest of the original search condition.

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

_Your notes:_

I used the safe repository method `findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)` in `PostController`.

### Why this fixes the root cause and not just the symptom

_Your notes:_

The repository method is translated by Spring Data into a parameterized query. The search value is bound as a data parameter, so `q` cannot change SQL syntax or inject `UNION` or comment markers. The database sees the literal string, not executable SQL text.

### Why I did NOT just block quotes / the word UNION

_Your notes:_

Blocking specific characters or keywords is unreliable and can be bypassed. The secure fix is to stop building SQL by string concatenation and use parameter binding instead.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

_Your notes:_

The same payload no longer leaks user data. It only performs a normal search for the literal string and returns no admin email or password hash.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_(yes / no, and anything you noticed)_

yes, the normal keyword searches still return expected posts and the search endpoint works as before.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_

The comment endpoint is open to anonymous posting and could be abused for spam or reflected XSS if not sanitized. The app also stores password hashes in the same database that public endpoints can query, so any remaining injection bug could expose credentials. The public search API should never use raw native queries with user input.

