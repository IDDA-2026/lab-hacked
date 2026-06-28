# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_
The app is a basic feed application allowing users to view, search, and comment on posts without authentication. A stranger controls the search input parameter (`q`) in the `/api/posts/search` endpoint and the comment body/author fields in the `/api/posts/{postId}/comments` endpoint.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

```
' UNION SELECT id, email, password_hash FROM users -- 
```

### What each part of it does

Break your payload into pieces and explain each one. For example: what closes the
original string, what pulls in the other table, what hides the rest of the query.

_Your notes:_
- `'` : Closes the opening single quote in the original SQL query string (`LIKE '%`).
- ` UNION SELECT id, email, password_hash FROM users ` : Combines the results of the original query with the results from the `users` table. We fetch `id`, `email`, and `password_hash` to match the exact number of columns (3) expected by the original query (id, title, body).
- `-- ` : A SQL comment that effectively hides the rest of the original query (the rest of the LIKE clause and OR condition), so the query syntax remains valid and doesn't cause an error.

### What came back

What data appeared that should never have been there? Paste
a line or two. A screenshot is ideal.

_Your notes:_
The search results displayed user accounts instead of post titles and bodies. For example, one result showed `admin@inkfeed.app` as the title and a bcrypt hash as the body.

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_
The database was willing to run it because the search input was concatenated directly into the SQL query string in `PostController.java`. The database has no way to distinguish between the developer's original SQL logic and the attacker's input, treating the injected payload as an executable part of the SQL statement.

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

_Your notes:_
I used the safe repository method provided by Spring Data JPA (`findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase`).

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

_Your notes:_
This fixes the root cause because the repository method uses parameterized queries under the hood. The database separates the query structure from the data (parameters). Even if the parameter contains SQL keywords like `UNION` or quotes, the database treats them strictly as literal string values, not as executable commands.

### Why I did NOT just block quotes / the word UNION

_Your notes:_
Blocklisting is a flawed defense mechanism because attackers can often bypass filters using different encodings, alternative keywords, or other tricks. Additionally, it can break legitimate functionality (e.g., a user searching for a post titled "O'Brien" or containing the word "union").

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

_Your notes:_
The payload returned zero results because the application literally searched for posts containing the exact string `UNION SELECT...` in their title or body.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_(yes / no, and anything you noticed)_
Yes, it still works properly and returns the relevant posts.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_
- The `User` entity includes a `passwordHash` field that might be mistakenly exposed through JSON serialization if the `User` object is ever returned from an API. It's better to exclude it or decouple auth entities from public responses.
- The comment endpoint accepts any author name and body without sanitization or rate limiting, which could lead to XSS (if the frontend doesn't escape HTML) and spam.
- There is no authentication or authorization mechanism, anyone can spam posts or comments.
