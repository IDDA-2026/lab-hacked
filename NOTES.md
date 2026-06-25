# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

The app has **three public-facing inputs** a stranger controls:

1. **Search box** → `GET /api/posts/search?q=<input>` – the value of `q` reaches the backend and is used to query the database.
2. **Comment form (author name field)** → `POST /api/posts/{id}/comments` – `authorName` is stored in the DB.
3. **Comment form (body field)** → same endpoint, `body` is stored.

The comment endpoint uses Spring Data JPA's `commentRepository.save(...)`, which goes through the ORM and is therefore parameterized automatically.

The **search endpoint**, however, was the dangerous one. Looking at `PostController.java` before the fix:

```java
String sql = "SELECT id, title, body FROM posts " +
        "WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";
entityManager.createNativeQuery(sql).getResultList();
```

The user's text `q` is **glued directly into the SQL string**. The database receives one big string and parses it, treating the user's text as part of the query language.

---

## 2. Reproducing the breach

### What I typed into the search box

```
' UNION SELECT id, email, password_hash FROM users --
```

### What each part of it does

| Part | Role |
|------|------|
| `'` | Closes the single-quoted string that was open in `LIKE '%...`. Now we're outside the string literal. |
| `UNION SELECT id, email, password_hash FROM users` | Appends a second SELECT to the first. UNION requires matching column counts — the original query returns 3 columns (`id, title, body`), so we pick 3 matching columns from the `users` table. `email` maps to the `title` slot and `password_hash` maps to the `body` slot. |
| `--` | SQL line comment. Everything after this (the original `%' OR body LIKE '%...` tail) is ignored, so the database never sees a syntax error from the leftover query fragment. |

The full SQL the database actually ran:

```sql
SELECT id, title, body FROM posts
WHERE title LIKE '%' UNION SELECT id, email, password_hash FROM users --%' OR body LIKE '%%'
```

Which simplifies to:

```sql
SELECT id, title, body FROM posts WHERE title LIKE '%'
UNION
SELECT id, email, password_hash FROM users
```

### What came back

The InkFeed search results page rendered cards that looked exactly like normal posts, but the title and body fields contained data from the `users` table:

| Rendered "title" (= email) | Rendered "body" (= password_hash) |
|---|---|
| `maya.thompson@inkfeed.app` | `$2a$10$Qe7kI3yE8mZ1cT0vJ9oQ1uPb5sYxKfM2nR4hVjLwA6dCtB8oNqW2` |
| `leo.fernandez@inkfeed.app` | `$2a$10$9aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789abcdefghijklmnoPq` |
| `priya.nair@inkfeed.app` | `$2a$10$kLmNoPqRsTuVwXyZ0123456abCDefGHijKLmnOPqrSTuvWXyz12abcd` |
| `samuel.okafor@inkfeed.app` | `$2a$10$ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210zyxwvuTSRQponmLKjih` |
| `hana.kim@inkfeed.app` | `$2a$10$AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrSt` |
| **`admin@inkfeed.app`** | **`$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK`** |

The admin row — the trophy — appeared as a regular search result card in the browser. The front-end rendered it faithfully because it just receives JSON with `title` and `body` fields; it has no way to know those values came from the wrong table.

---

## 3. Why it worked (root cause)

The query was assembled by **string concatenation of untrusted input**:

```java
String sql = "SELECT id, title, body FROM posts " +
        "WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";
```

When the database's SQL parser receives a string, it cannot tell which parts are *query structure* (intended by the developer) and which parts are *data* (typed by the user). Everything is just characters. Our payload used a `'` to close the developer's open string literal, escaping from "data mode" into "command mode", and then wrote new SQL directly into the query. The database followed instructions — it doesn't care who wrote them.

This is the root cause of SQL injection: **data and code share the same channel**. The database was willing to run our extended query because it looked syntactically valid, and it had no mechanism to question whether the `UNION SELECT` clause was authorised.

---

## 4. The fix

### Which road did I take?

**The safe Spring Data JPA derived query method** — `PostRepository.findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)` — which was already waiting in [`PostRepository.java`](back/src/main/java/com/ironhack/simple_auth/repository/PostRepository.java), unused, with a comment pointing out the irony.

The new `search` method in [`PostController.java`](back/src/main/java/com/ironhack/simple_auth/controller/PostController.java):

```java
@GetMapping("/search")
public List<SearchResult> search(@RequestParam(name = "q", defaultValue = "") String q) {
    return postRepository
            .findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)
            .stream()
            .map(post -> new SearchResult(post.getId(), post.getTitle(), post.getBody()))
            .toList();
}
```

The `EntityManager` field and its `@PersistenceContext` annotation were also removed — they are no longer used.

### Why this fixes the root cause and not just the symptom

Spring Data JPA translates the method name into a **JPQL query** and then compiles that into a **parameterized prepared statement** before the first request ever arrives. When `search` is called, Hibernate sends something like:

```sql
SELECT p FROM Post p
WHERE LOWER(p.title) LIKE LOWER(?)
   OR LOWER(p.body)  LIKE LOWER(?)
```

The `?` placeholders are filled by the JDBC driver at the protocol level, **after** the database has already parsed and planned the query. The database never sees the user's text mixed into the SQL source; it only sees a parameter value to bind. There is no moment at which our `'` or `UNION` keywords can influence the query's structure, because the structure was fixed at startup time.

Data can no longer be interpreted as code because the two are **separated at the protocol boundary**: the query travels one way, the parameter values travel another, and the database processes them independently.

### Why I did NOT just block quotes / the word UNION

Blocklisting is the wrong fix for three reasons:

1. **It does not address the root cause.** The root cause is that data and code share the same channel. Removing a `'` from the input patches one symptom of that design flaw; it does not fix the flaw itself.
2. **It is bypassable.** SQL has many quoting styles, encoding tricks, and comment syntaxes. An attacker with time will find the one you forgot. Parameterized queries have no such bypass — there is no encoding trick that makes a bound parameter escape its placeholder.
3. **It breaks legitimate input.** A user named `O'Brien` searching for their own posts would get zero results. Banning `UNION` would break any search for posts about "the European Union".

The correct fix changes the *architecture* — separating data from code — not the *data itself*.

---

## 5. Proof the fix holds

**Re-running the original payload** `' UNION SELECT id, email, password_hash FROM users --` against the fixed backend returns **zero results**. The search term is passed as a literal string to the `LIKE` clause. The database looks for posts whose title or body contains the text `' UNION SELECT id, email, password_hash FROM users --` verbatim. No posts contain that string, so nothing comes back. The `users` table is never touched.

**Normal searches still work:**

| Query | Expected result |
|-------|----------------|
| `pen` | "What pen do you swear by?" (Leo's post) |
| `color` | "Color theory broke my brain (in a good way)" (Priya's post) |
| `comic` | "Looking for feedback on my comic panels" (Hana's post) |
| `ink` | "Sketchbook tour: 30 days of ink" (Maya's post) |

All return the correct posts. No user data leaks. Commenting on a post still works (that endpoint was not affected by the fix).

---

## 6. If I had another hour

**The comment endpoint:** `authorName` and `body` are stored via `commentRepository.save(...)`, which goes through JPA/Hibernate. They are parameterized automatically. However, they are stored raw and later rendered on the page — if the front end ever rendered them as HTML without escaping, this would be a stored XSS vector. Worth checking.

**Password hashes in the API layer:** Even after the fix, the backend *can* read `password_hash` from the database. If a different bug ever exposed that query, or if someone added a new endpoint carelessly, the hashes would be at risk. The principle of least privilege says: the API should not be able to select `password_hash` at all. A database view that excludes that column, or a Spring Security row-level policy, would add a second layer of defence even if the application code is wrong.

**The open API:** No authentication, no rate limiting. An attacker can call `/api/posts/search` in a tight loop. Even the safe parameterized version can be abused for denial-of-service without rate limiting.

**Blind injection check:** Before I had the UNION payload, I could confirm `admin@inkfeed.app` exists by probing response size. A query like `' OR email='admin@inkfeed.app' AND '1'='1` — if the users table were joinable — would return all posts vs. none, leaking a boolean answer without any result text. This shows injection can be dangerous even when results are invisible.
