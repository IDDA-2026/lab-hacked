# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

The app lets anyone search for drawing posts. The search box is the place where a stranger can type anything and send it straight to the database.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

```
In the search box, I typed:
`' UNION SELECT 1, email, password FROM users --`
```

### What each part of it does

Break your payload into pieces and explain each one. For example: what closes the
original string, what pulls in the other table, what hides the rest of the query.

- `'` : Breaks the normal search script so I can start writing my own database commands.
- `UNION SELECT` : Forces the database to join the secret user table with the public post table.
- `1, email, password` : Fits the 3-column design of the website so the data shows up neatly.
- `FROM users` : Tells the database to steal from the user table.
- `--` : Deletes/ignores the rest of the original code so the system does not crash.

### What came back

What data appeared that should never have been there? Paste
a line or two. A screenshot is ideal.

_Your notes:_

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)
I used the safe repository method from Spring Data.

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

Instead of gluing text strings with `+`, the new method passes the search word separately as a pure value. The database now treats everything the user types strictly as plain text, never as a command.

### Why I did NOT just block quotes / the word UNION

Blocking words is easy to bypass and breaks the site for normal users. For example, if we ban the quote symbol, a user cannot search for words like `don't` or names like `O'Brien`.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:
When I try the attack now, it returns **0 results** because the database safely looks for the literal text instead of running it. 
Normal searches like `pen` and `color` still work perfectly.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_(yes / no, and anything you noticed)_

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

Understand the problem more deeply and make changes depending on how it will act. I had to do it fast so I asked AI to help me with the lab. I think if I had time to think about whole system I could find better approach maybe.
