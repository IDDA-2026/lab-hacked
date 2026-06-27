# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_
- The public frontend calls `/api/posts` for the feed and `/api/posts/search?q=...` for search.
- The only attacker-controlled input is the search query parameter `q`.
- The backend concatenated `q` directly into SQL in the controller, so untrusted text could alter the query.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

`' OR 1=1 UNION SELECT id, email, password FROM users -- `

### What each part of it does

Break your payload into pieces and explain each one. For example: what closes the
original string, what pulls in the other table, what hides the rest of the query.

_Your notes:_
- `'` closes the original string inside the `LIKE '%...%'` clause.
- `OR 1=1` makes the original WHERE clause always true so the SQL remains valid.
- `UNION SELECT id, email, password FROM users` appends rows from the private `users` table with the same three columns that the frontend expects.
- `-- ` comments out the rest of the backend query after the injection so the original `OR body LIKE ...` does not break the statement.

### What came back

What data appeared that should never have been there? Paste
a line or two. A screenshot is ideal.

_Your notes:_
- The search returned `admin@inkfeed.app` and a password hash in the result cards.
- Those values came from the private `users` table, not from public posts.

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_
- The database received a SQL string where the search term was interpolated directly into the query.
- Because the query and the data were mixed together, the injected payload was interpreted as SQL code rather than as a search string.
- That allowed the attacker to change the query structure and union in data from a private table.

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

_Your notes:_
- I took the safe repository method road by using `PostRepository.findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)`.

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

_Your notes:_
- Spring Data turns the search into a parameterized query and binds the search term as data.
- Because the query shape is fixed and the search term is never concatenated into the SQL text, an attacker cannot close the string or inject `UNION`.

### Why I did NOT just block quotes / the word UNION

_Your notes:_
- Blocklisting is brittle and can be bypassed with different encodings or alternate syntax.
- It also rejects legitimate input like names with apostrophes.
- The safe fix is to separate code from data, not to try to filter the data.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

_Your notes:_
- The same payload now returns an empty result set instead of leaking user rows.
- The `users` table no longer appears in public search output.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_(yes / no, and anything you noticed)_
- yes, normal searches still return matching posts.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_
- The comment endpoint accepts arbitrary text and could be a stored XSS risk if the UI ever renders it without escaping.
- The open API surface is broad and unauthenticated, so any additional endpoints would be high-risk.
- The backend can see password hashes and the H2 console is exposed in development, so reducing debug/admin access would be important.
