# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

The application allows users to browse posts, search through them, and add comments.
The search functionality sends user input directly to the backend, where it is used in a database query.

The main untrusted input is the search parameter `q`, which is fully controlled by the user and reaches the backend without proper sanitization or separation from SQL logic.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

I entered this in the **search input field** on the frontend (`/api/posts/search?q=`).

---

### What each part of it does

- `'` → closes the original SQL string and allows injection
- `UNION SELECT` → combines original query results with another query
- `id, email, password` → selects sensitive columns from the users table
- `FROM users` → targets the hidden users table
- `--` → comments out the rest of the SQL query to avoid syntax errors

---

### What came back

The search results included sensitive data that should not be accessible:
- user emails (e.g. admin@inkfeed.app)
- password hashes

This confirms a successful SQL injection vulnerability.

---

## 3. Why it worked (root cause)

It worked because user input was directly concatenated into a SQL query string in the backend.

This allowed the database to interpret user input as SQL code instead of data.

As a result, attackers were able to modify the query structure and access unauthorized tables.

---

## 4. The fix

### Which road did I take?

I used a parameterized native query with `EntityManager.setParameter()`.

---

### Why this fixes the root cause and not just the symptom

Parameterized queries separate SQL logic from user input.

This means:
- SQL structure cannot be changed by user input
- input is treated strictly as data
- injection becomes impossible by design

---

### Why I did NOT just block quotes / the word UNION

Input filtering (blocklists) is not a safe solution because:
- attackers can bypass filters with alternative syntax or encoding
- valid user input may break (e.g. names with special characters)
- it does not eliminate the root cause of the issue

The correct solution is separating SQL logic from data.

---

## 5. Proof the fix holds

After applying the fix, the same payload no longer returns any user data.

A normal search (`pen`, `color`, `comic`) still works correctly.

No SQL errors or data leaks occur.

---

## 6. If I had another hour

I would also review the comment endpoint because it accepts raw user input without validation.

Additionally, I would ensure the API follows the principle of least privilege so that sensitive data (like password hashes) is never exposed through public endpoints.