# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_
InkFeed is a simple community application where users can view a feed of posts, search through posts, and add comments to posts.
Untrusted input reaches the backend via the following entry points:
1. **Search Endpoint**: `GET /api/posts/search?q=...` -> The query parameter `q` is controlled by the user. It is directly concatenated into a native SQL query:
   `"SELECT id, title, body FROM posts WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'"`
2. **Comments Endpoint**: `POST /api/posts/{postId}/comments` -> The user controls the path variable `postId` (which is parsed as a `Long`) and the JSON request body containing `authorName` and `body`.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

I injected the SQL payload into the search input box (`q` parameter) by making a request to the search API:
```
%' UNION SELECT id, email, password_hash FROM users -- 
```
URL-encoded:
```
http://localhost:8080/api/posts/search?q=%25%27%20UNION%20SELECT%20id%2C%20email%2C%20password_hash%20FROM%20users%20--%20
```

### What each part of it does

* `%'` - Closes the opening single quote of the `LIKE` pattern in the first search condition (`title LIKE '%...%'`). The percent sign matches any text preceding it.
* ` UNION ` - Merges the result set of the original query with the result set of our injected query.
* `SELECT id, email, password_hash FROM users` - Executes a query against the private `users` table, extracting the ID, email, and password hash of every user. Because the frontend expects three columns (`id`, `title`, `body`), we select exactly three columns of matching types (number, string, string) to satisfy the SQL compiler.
* `-- ` - Comments out the rest of the original SQL statement (starting with the `OR body LIKE ...` clause), so the database parser ignores the trailing SQL structure and doesn't throw a syntax error.

### What came back

The API returned a list of all posts combined with the email addresses and password hashes of all registered users. Among them, the admin user credentials were leaked:
```json
[
  ...
  {
    "id": 6,
    "title": "admin@inkfeed.app",
    "body": "$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK"
  }
]
```

---

## 3. Why it worked (root cause)

The backend constructed the SQL query using simple string concatenation:
```java
String sql = "SELECT id, title, body FROM posts WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";
```
This combined SQL commands and developer-defined logic with untrusted user-supplied data in the same raw string. The database parser compiled the final concatenated string as a single query, interpreting SQL keywords (`UNION`) and operators (`--`) injected by the user as executable code rather than plain text data.

---

## 4. The fix

### Which road did I take?

I chose the safe repository method path:
- Switched the native SQL query to use the repository method already defined in PostRepository:
  `List<Post> findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(String title, String body);`

### Why this fixes the root cause and not just the symptom

Spring Data JPA generates a parameterized query (Prepared Statement) under the hood for derived query methods. The SQL structure is pre-compiled by the database engine:
`SELECT id, title, body FROM posts WHERE LOWER(title) LIKE ?1 OR LOWER(body) LIKE ?2`
The search inputs are sent separately as parameter values. When the database executes the query, it treats the parameter values strictly as literal strings (data) and never evaluates them as SQL instructions or syntax modifications, regardless of what special characters (like `'` or `--`) they contain.

### Why I did NOT just block quotes / the word UNION

Blocklisting is an unsafe, incomplete defense strategy:
1. **Bypass Potential**: Attackers can use alternative encodings, casing, or database-specific functions to bypass simple string-matching blocklists.
2. **False Positives**: Blocking quotes or special characters breaks legitimate user inputs (e.g., searching for names like `O'Reilly` or terms like `union-made`).
3. **Complexity**: Maintaining a list of all dangerous characters/keywords is complex and error-prone compared to separating code and data at the database boundary.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:
No database leak occurred. The search returned `0 results` because there are no posts that contain the literal string `%' UNION SELECT id, email, password_hash FROM users -- ` in their title or body.

A normal search (`pen`, `color`, `comic`) still returns the right posts:
**Yes**, searching for normal keywords returns the matching posts as expected.

---

## 6. If I had another hour

If I had more time, I would address the following concerns:
1. **Comment Endpoint Input Validation**: The `POST /api/posts/{postId}/comments` endpoint takes raw inputs and saves them. While SQL injection is not possible here (since Hibernate uses parameterized statements for entity saves), the endpoint lacks protection against HTML/JavaScript injection, which could lead to Cross-Site Scripting (XSS).
2. **Least Privilege for Password Hashes**: The backend loads the `password_hash` column as part of the `User` entity. In a production system, password hashes should be stored in a separate table/entity or excluded from general queries using JPA `@Basic(fetch = FetchType.LAZY)` or `@JsonIgnore` / DTO mapping, ensuring they never reach non-auth controllers.
3. **Authentication & Authorization**: There is no authentication or authorization checks. Anyone can comment under any name (e.g., impersonating an admin).
4. **Blind SQL Injection Check**: In a blind SQL injection scenario, even if search results are not displayed on screen, an attacker can extract information one character at a time by crafting conditional queries that cause a change in response behavior (like a slow response via `WAITFOR DELAY` / `SLEEP` or a difference in HTTP status codes). For example:
   `http://localhost:8080/api/posts/search?q=pen%27%20AND%20(SELECT%20CASE%20WHEN%20(SELECT%20COUNT(*)%20FROM%20users%20WHERE%20email%3D%27admin@inkfeed.app%27)%3E0%20THEN%201%20ELSE%200%20END)%3D1%20--%20`
   By checking whether this returns the normal "pen" posts or empty results, the attacker can verify if the user `admin@inkfeed.app` exists.
