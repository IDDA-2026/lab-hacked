# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_

InkFeed is a public drawing-community app. Strangers control:

- **Search box** (`GET /api/posts/search?q=...`) — the main attack surface. Whatever you type is sent straight to the backend as the `q` parameter.
- **Comment form** (`POST /api/posts/{id}/comments`) — `authorName` and `body` in the JSON body. No login required.
- **Post ID in the comment URL** — a path variable (`postId`), but Spring binds it as a `Long`, not as raw SQL.

The users table (emails + password hashes) is never exposed by design. The feed only shows post titles, bodies, author display names, and avatars.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

```
' UNION SELECT id, email, password_hash FROM users --
```

(Pasted into the search box on http://localhost:3000, or sent as `q` to `GET /api/posts/search`.)

### What each part of it does

Break your payload into pieces and explain each one. For example: what closes the
original string, what pulls in the other table, what hides the rest of the query.

_Your notes:_

| Piece | What it does |
|-------|----------------|
| `'` | Closes the opening `'%` string in `LIKE '%…%'` so the database stops treating the rest as a search keyword. |
| ` UNION SELECT id, email, password_hash FROM users` | **UNION** stacks a second query onto the first. The original query returns 3 columns (`id`, `title`, `body`). This second query also returns 3 columns, so H2 happily merges the rows. We map `email` → title and `password_hash` → body so they show up on the result cards. |
| `--` | SQL line comment. Everything after it (`%' OR body LIKE …`) is ignored, so the broken original query does not cause a syntax error. |

The vulnerable line in `PostController` was:

```java
String sql = "SELECT id, title, body FROM posts " +
        "WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";
```

User input was glued directly into the SQL string. The database could not tell data apart from commands.

### What came back

What data appeared that should never have been there? Paste
a line or two. A screenshot is ideal.

_Your notes:_

The search results page showed **every row from the `users` table**, rendered like normal posts:

| title (email) | body (password hash) |
|---------------|----------------------|
| maya.thompson@inkfeed.app | `$2a$10$Qe7kI3yE8mZ1cT0vJ9oQ1uPb5sYxKfM2nR4hVjLwA6dCtB8oNqW2` |
| leo.fernandez@inkfeed.app | `$2a$10$9aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789abcdefghijklmnoPq` |
| priya.nair@inkfeed.app | `$2a$10$kLmNoPqRsTuVwXyZ0123456abCDefGHijKLmnOPqrSTuvWXyz12abcd` |
| samuel.okafor@inkfeed.app | `$2a$10$ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210zyxwvuTSRQponmLKjih` |
| hana.kim@inkfeed.app | `$2a$10$AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrSt` |
| **admin@inkfeed.app** | **`$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK`** |

Screenshot tip for the portal: run the attack **before** the fix (or temporarily revert), paste the payload in the search box, and screenshot the page showing `admin@inkfeed.app` and its hash in the result cards.

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_

The backend built SQL by **string concatenation**. The search term was inserted inside `LIKE '%…%'` with no escaping and no parameter binding. Closing the quote turned my input from “search text” into “SQL code”. **UNION** then let me attach a completely different `SELECT` against the `users` table. Because the frontend only cares about three columns (`id`, `title`, `body`), stolen credentials looked like ordinary search hits.

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

_Your notes:_

**The safe repository method** — `PostRepository.findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(keyword, keyword)`.

It was already in the project, unused. Spring Data JPA generates a parameterized query and binds `keyword` as data, not as SQL structure.

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

_Your notes:_

The query shape is fixed at compile time. The `?` placeholders are filled in by the driver **after** the query is parsed, so a quote or `UNION` in the search box stays a literal string inside `LIKE`. Data can no longer be interpreted as code because the database never sees user input as part of the SQL grammar.

I also removed `EntityManager` and native SQL from the search endpoint entirely, so that code path can no longer reach arbitrary tables.

### Why I did NOT just block quotes / the word UNION

_Your notes:_

Blocklisting is fragile: encodings, case tricks, comments, and new keywords always show up. It also breaks legitimate input (e.g. a user named `O'Brien` searching their posts). The fix belongs at the boundary where SQL is built — parameterized queries — not in a list of forbidden characters.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

_Your notes:_

**Zero user rows.** The UNION payload returns only genuine post matches (if any), or an empty list. No emails, no password hashes. The attack is dead.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_(yes / no, and anything you noticed)_

**Yes.** `pen` returns 2 posts (the fineliner post + the color-theory post). `color` and `comic` return the expected posts. Commenting still works. No stack traces in the backend during normal use.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_

### Comment endpoint — is it safe?

**Safe from SQL injection.** Comments use `commentRepository.save(comment)` — JPA/Hibernate with bound parameters. `authorName` and `body` are never concatenated into SQL strings.

Still not “safe” in a broader sense: no authentication (anyone can impersonate any display name), no rate limiting, and stored comment text could be a stored-XSS risk if the frontend ever rendered HTML unsafely (this React app uses text nodes, so we are OK for now).

### Blind SQL injection (bonus)

Without reading emails on screen, you can confirm whether `admin@inkfeed.app` exists using **boolean-based** blind injection on the vulnerable endpoint:

**Admin exists (returns 5 post rows):**
```
' AND (SELECT COUNT(*) FROM users WHERE email='admin@inkfeed.app')>0 AND '1'='1' --
```

**Fake email (returns 0 rows):**
```
' AND (SELECT COUNT(*) FROM users WHERE email='nobody@fake.com')>0 AND '1'='1' --
```

If the subquery count is `> 0`, the `AND` chain passes and `LIKE '%'` matches every post → many results. If the user does not exist, the condition fails → empty results. True/false without ever seeing the `users` table on screen.

### Least privilege (bonus hardening)

The search fix stops **exfiltration**, but JPA still loads full `User` entities (including `password_hash`) when building the feed, because `Post.author` is eager. The hash is not sent to the browser, but it sits in server memory.

Extra hardening applied:

- `@JsonIgnore` on `User.passwordHash` so accidental JSON serialization can never leak it.
- Removed native SQL / `EntityManager` from the search controller so that endpoint cannot query arbitrary tables.

Ideal next step in production: split credentials into a separate store or use column-level DB permissions so the feed API role literally cannot `SELECT password_hash`.

### Input validation — second layer (bonus)

Added server-side checks **on top of** parameterized queries (never instead of):

- Search `q`: trim, max 200 characters.
- Comment `body`: required, max 1000 characters (matches DB column).
- Comment `authorName`: max 100 characters, defaults to `Anonymous`.

Validation catches abuse and bad UX early; it does not replace parameter binding for SQL safety.
