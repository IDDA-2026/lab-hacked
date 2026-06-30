# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_
InkFeed exposes a public feed, a public search box, and public comment forms.
The inputs a stranger controls are the search query (`GET /api/posts/search?q=...`)
and the comment request body (`authorName` and `body`). The search result shape is
three values: `id`, `title`, and `body`. That made me suspicious because SQL
injection with `UNION` can add rows from another table if I can make the column
count line up.

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
I put that text in the feed search box / `q` parameter.

`'` closes the string inside the original `LIKE '%...%'` clause.

`UNION SELECT` appends the results of a second query to the original post search.

`id, email, password_hash` gives the second query the same three-column shape as
the public search result (`id`, `title`, `body`). The frontend then renders the
email as the title and the password hash as the body.

`FROM users` reads from the private users table.

`-- ` starts a SQL comment so the rest of the original query is ignored instead
of causing a syntax error. The trailing space matters for SQL comments.

### What came back

What data appeared that should never have been there? Paste
a line or two. A screenshot is ideal.

_Your notes:_
The public search response included private user data. The admin row came back as:

```
id: 6
title: admin@inkfeed.app
body: $2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK
```

It also returned the other member emails and hashes, for example
`maya.thompson@inkfeed.app` with its bcrypt hash.

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_
The controller built a native SQL string by concatenating the untrusted `q`
parameter directly into the query:

```
WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'
```

Because the database received one finished SQL string, it could not tell which
characters were supposed to be search text and which characters were SQL
commands. My quote ended the intended string literal, and the rest of my input
became executable SQL.

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

_Your notes:_
I used the safe repository method:

```
findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)
```

Spring Data builds the query and binds `q` as a value.

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

_Your notes:_
The search term is no longer pasted into SQL text. It is passed to Spring Data as
a parameter value, so characters like `'`, `UNION`, and `--` stay inside the
search value. They can affect what text is matched, but they cannot change the
shape of the SQL or add a second query against `users`.

### Why I did NOT just block quotes / the word UNION

_Your notes:_
Blocklisting would only hide this one payload. Attackers can use different
syntax, encodings, casing, comments, or database-specific tricks, and legitimate
searches like `O'Brien` would be broken. Parameter binding fixes the boundary
between data and code instead of trying to guess every dangerous string.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

_Your notes:_
The same payload now returns no user rows. It is treated as literal search text,
not SQL.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_(yes / no, and anything you noticed)_
Yes. `pen`, `color`, and `comic` still return the expected public posts.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_
The comment endpoint still accepts public input, but it saves through
`commentRepository.save(comment)` instead of building SQL strings by hand, so it
is not vulnerable to the same SQL injection pattern. I would still add validation
such as non-empty bodies and length limits.

The bigger design concern is least privilege: the public API process can read
password hashes because it connects as a database user with access to every
table. In a larger system I would separate read models or database permissions so
public feed/search code cannot query credential columns at all.
