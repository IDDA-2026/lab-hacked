# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_ I think putting the exact query text as it is to SQL query is the biggest and boldest problem here. Like it screams SQL Injection.

--- 

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

```
(paste the exact text you put here)
%' UNION SELECT id, email, password_hash FROM users -- 

I injected the SQL payload into the search input box (`q` parameter) by making a request to the search API:
```

### What each part of it does

Break your payload into pieces and explain each one. For example: what closes the
original string, what pulls in the other table, what hides the rest of the query.

_Your notes:_ * `%'` - Closes the opening single quote of the `LIKE
` UNION ` - Merges the result set of the original query with the result set of our injected query

`SELECT id, email, password_hash FROM users` - Executes a query against the private `users` table

`-- ` - Comments out the rest of the original SQL statement (starting with the `OR body LIKE ...` clause), so the database parser ignores the trailing SQL structure and doesn't throw an error

### What came back

What data appeared that should never have been there? Paste
a line or two. A screenshot is ideal.

_Your notes:_ The API returns a list of all posts combined with leaked data:

{
    "id": 6,
    "title": "admin@inkfeed.app",
    "body": "$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK"
  }
---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_ String sql = "SELECT id, title, body FROM posts WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";
```
This combined SQL commands and developer-defined logic with untrusted user-supplied data in the same raw string. The database parser compiled the final concatenated string as a single query, interpreting SQL keywords (`UNION`) and operators (`--`) injected by the user as executable code rather than plain text data.

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

_Your notes:_

- Switched the native SQL query to use the repository method already defined in PostRepository:
  `List<Post> findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(String title, String body);`

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

_Your notes:_
Spring Data JPA uses parameterized queries (Prepared Statements) internally for derived query methods. The SQL statement structure is compiled separately by the database engine:

SELECT id, title, body
FROM posts
WHERE LOWER(title) LIKE ?1
   OR LOWER(body) LIKE ?2

### Why I did NOT just block quotes / the word UNION

_Your notes:_

The user-provided search terms are passed to the database as parameter values rather than being directly inserted into the SQL query string. During execution, the database treats these parameters strictly as plain data and never interprets them as SQL commands or syntax, regardless of any special characters they may contain, such as ' or --

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

_Your notes:_
No database leak occurred. The search returned `0 results` because there are no posts that contain the literal string `%' UNION SELECT id, email, password_hash FROM users -- ` in their title or body.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_(yes / no, and anything you noticed)_

---
Yes they work as they should

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_
1. Remove the hashed password from returning with User entity
2. I would add a devent Authentication and Authorization 