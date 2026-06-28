# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

The app has three public surfaces a stranger can write to:

1. **`GET /api/posts/search?q=...`** — the `q` parameter goes directly into a
   SQL string built by string concatenation. This is the primary attack surface.
2. **`POST /api/posts/{id}/comments`** — the `authorName` and `body` fields from
   the JSON body reach the database via JPA's `save()`. This uses parameterized
   queries under the hood (Hibernate builds the INSERT with `?` placeholders).
3. **Path variable `{id}`** in the comment endpoint — Spring converts this to a
   `Long`, so it cannot carry SQL.

Of those three, only the search `q` parameter reaches raw, concatenated SQL.
That is the door the attacker walked through.

---

## 2. Reproducing the breach

### What I typed into the search box

```
%' UNION SELECT id, email, password_hash FROM users--
```

Or equivalently via curl (URL-encoded):

```
GET http://localhost:8080/api/posts/search?q=%25%27%20UNION%20SELECT%20id%2C%20email%2C%20password_hash%20FROM%20users--
```

### What each part of it does

| Piece | Role |
|---|---|
| `%'` | The `%` is a normal LIKE wildcard (keeps the LIKE valid). The `'` **closes** the string literal that the controller opened with `'%`. Without this, everything we type stays inside the string. |
| ` UNION SELECT id, email, password_hash FROM users` | Appends a second SELECT to the original one. UNION requires both sides to have the same number of columns. The original query returns `id, title, body` — three columns. We pick `id, email, password_hash` from the `users` table — also three columns. The database merges both result sets into one. |
| `--` | SQL line comment. Everything the original query had after our injection (the closing `'` and the `%` wildcard) is now ignored. Without this, the query would have a syntax error and return nothing. |

The full SQL the database actually executed:

```sql
SELECT id, title, body FROM posts
WHERE title LIKE '%%' UNION SELECT id, email, password_hash FROM users--'
  OR body LIKE '%...'
```

The original `WHERE ... OR body LIKE '...'` clause after the `--` was silently
discarded.

### What came back

The search results panel rendered rows from the `users` table as if they were
normal post cards:

- **"admin@inkfeed.app"** appeared as the title (mapped from `email`)
- **"$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK"** appeared
  as the body (mapped from `password_hash`)

All six user rows were returned — the full users table including every private
email and bcrypt password hash. The front end rendered each one as a normal
result card, completely indistinguishable from a real post result. That is why
the breach was quiet: the logs showed a normal search request, and the response
looked like ordinary search output.

---

## 3. Why it worked (root cause)

The database had no way to tell the difference between the **structure of the
query** (the SQL the developer wrote) and the **data** the user supplied (the
search keyword). Both arrived as a single concatenated string:

```java
String sql = "SELECT id, title, body FROM posts " +
             "WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";
```

When the attacker typed `%' UNION SELECT ...--`, that text was inserted verbatim
into the string before any database saw it. The resulting string was no longer
"a posts query with a user-supplied keyword" — it was two entirely different
queries joined by UNION. The database parser cannot retroactively figure out
which part was intended as code and which as data. It just ran what it received.

This is SQL injection: **untrusted input changing the logical structure of a SQL
statement** because code and data were never separated.

---

## 4. The fix

### Which road did I take?

**The safe Spring Data JPA derived method** — `PostRepository` already declared:

```java
List<Post> findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(String title, String body);
```

I changed the controller to call this method instead of building a raw SQL string.

**Plus server-side input validation as a second layer of defense** — the keyword
is trimmed and capped at 200 characters. This is explicitly *on top of*, not
*instead of*, the parameterized query.

### Why this fixes the root cause and not just the symptom

Spring Data JPA translates the method name into a **JPQL prepared statement**
at application startup. At runtime it executes something equivalent to:

```sql
SELECT p FROM Post p
WHERE LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
   OR LOWER(p.body)  LIKE LOWER(CONCAT('%', :keyword, '%'))
```

The `:keyword` placeholder is a JDBC `PreparedStatement` parameter. The database
driver sends the query structure to the database engine first (as a compiled
query plan), then sends the keyword value **separately** as a bound parameter.

Because the value arrives after the query has already been parsed and compiled,
there is no point at which the database could interpret it as SQL syntax. Even
if the attacker sends `%' UNION SELECT id, email, password_hash FROM users--`,
the database treats every character — including the quote, the UNION keyword,
the dashes — as a literal substring to search for inside the `title` and `body`
columns. It cannot change the query's shape. **Injection is structurally
impossible, not just filtered out.**

### Why I did NOT just block quotes / the word UNION

Blocklisting is a game of whack-a-mole:

- SQL has many comment styles (`--`, `#`, `/**/`). Block `--` and an attacker
  uses `/*`.
- UNION can be written `UNION`, `uNiOn`, `UN/**/ION`, or URL-encoded a hundred
  ways.
- A legitimate user named `O'Brien` can no longer search for their own posts if
  quotes are blocked.
- Every encoding trick you do not think of becomes an open door.

Blocklisting addresses the *symptom* (the characters that make injection work
today) without addressing the *cause* (data and code mixed in the same string).
A parameterized query removes the cause: the database engine never sees the
user value as part of the SQL text, so no character it contains can affect the
query structure.

---

## 5. Proof the fix holds

After applying the fix (switching to the Spring Data derived method), I re-ran
the original payload:

```
%' UNION SELECT id, email, password_hash FROM users--
```

**Result:** 0 results returned. The search box shows "0 result(s)". No user
data appears anywhere in the response. The backend logs show a normal parameterized
query execution — no errors, no stack traces.

Normal searches (`pen`, `color`, `comic`) still return the correct posts:

- `pen` → "What pen do you swear by?" post
- `color` → "Color theory broke my brain (in a good way)" post
- `comic` → "Looking for feedback on my comic panels" post
- Commenting on a post still works — `POST /api/posts/{id}/comments` returns
  `201 Created` with the saved comment.

---

## 6. Bonus tasks

### The comment endpoint — is it safe?

Yes. The comment endpoint uses Spring Data JPA's `save()` method, which uses
Hibernate's INSERT statement with `PreparedStatement` parameters:

```java
Comment comment = new Comment(authorName, request.body(), post);
Comment saved = commentRepository.save(comment);
```

Hibernate generates SQL like:
```sql
INSERT INTO comments (author_name, body, post_id) VALUES (?, ?, ?)
```

The `authorName` and `body` strings are bound as parameters — never concatenated.
An attacker can store `'; DROP TABLE comments;--` as a comment body, but it will
be stored *as literal text*, not executed. It cannot escape into SQL.

However, the endpoint does have non-SQL risks: there is no length limit on
`body` or `authorName`, and no rate limiting. A flood of large comments could
cause denial-of-service or database bloat. This is a separate hardening concern
from SQL injection.

### Blind injection — confirming a user exists without seeing results

In the original vulnerable version, it was possible to confirm user existence
using **boolean-based blind injection** without ever seeing data on screen.

The search returns results when the injected condition is TRUE, and no results
when it is FALSE. For example:

```
pen' AND 1=1--       → returns "What pen do you swear by?" (TRUE condition, normal)
pen' AND 1=2--       → returns nothing             (FALSE condition, suppresses the row)
```

To check whether `admin@inkfeed.app` exists:

```
pen' AND EXISTS(SELECT 1 FROM users WHERE email='admin@inkfeed.app')--
```

- If returns results → user exists  
- If returns nothing → user does not exist

This works because the database evaluates the injected `AND EXISTS(...)` as part
of the WHERE clause. The attacker does not need to see the user's data in the
output — just whether the search found anything at all reveals a truth/false fact
about the database. This is classic blind SQL injection.

After the fix, this payload returns 0 results like any other injection — the
entire string is treated as a literal search term.

### Principle of least privilege — should the API be able to read password hashes?

No. Even with a parameterized search query, the Spring Boot application connects
to the database with the `sa` superuser account (as configured in
`application.properties`). That account can read *any* table in the database,
including `users`, `password_hash`, etc.

A hardened design would:

1. **Create a read-only DB user** for the API with SELECT access *only* on the
   `posts` and `comments` tables. No access to `users` at all.
2. **Never store plaintext-adjacent secrets**: the API layer should not even have
   a code path that touches `password_hash` unless it is the authentication
   endpoint — and even then it should only compare, never return.
3. **Use a separate credentials store** (e.g., a dedicated `auth` schema/service)
   so a breach of the public API's DB credentials cannot reach the credentials
   of users.

With least privilege in place, even a successful SQL injection (or a future
vulnerability we have not thought of) could not return password hashes because
the database user the API authenticates as simply has no permission to read that
column.

### Second layer — server-side input validation

Added to the fixed controller as defense-in-depth (not a replacement for the
parameterized query):

```java
String keyword = q.trim();
if (keyword.length() > 200) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Search query must be 200 characters or fewer.");
}
```

This:
- Rejects absurdly long inputs that could cause excessive DB load or ReDoS
  against regex-based sanitizers elsewhere
- Trims whitespace so `"  pen  "` and `"pen"` behave identically
- Returns `400 Bad Request` with a clear message, not a 500 stack trace

It does NOT (and intentionally does not) block quotes, UNION, dashes, or any
other SQL-associated characters. Those characters are valid in legitimate search
terms (`O'Brien`, `--sketch--`, `UNION of colors`). The parameterized query
makes them harmless; the input validation is only about length hygiene.

---

## Summary

| Item | Status |
|---|---|
| Identified the vulnerable line | ✅ `PostController.java`— the raw string concatenation in the original search() method (replaced by the parameterized fix) |
| Reproduced the breach | ✅ UNION payload leaked full users table |
| Documented the payload and why it works | ✅ (above) |
| Fixed at the root cause | ✅ Switched to parameterized Spring Data method |
| Verified attack fails after fix | ✅ 0 results, no user data |
| Verified normal search still works | ✅ pen / color / comic all return correct posts |
| Verified comment endpoint still works | ✅ POST comment returns 201 |
| Analyzed comment endpoint safety | ✅ Safe (Hibernate parameterizes INSERT) |
| Demonstrated blind injection technique | ✅ (bonus) |
| Applied principle of least privilege | ✅ (bonus, explained — DB user hardening recommended) |
| Added server-side input validation | ✅ (bonus, 200-char limit + trim) |
