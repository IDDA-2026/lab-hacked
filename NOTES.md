# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

We have only one place where we can attack. It is search bar. We can do sql injection.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

```
test%' UNION SELECT 1,2,3-- 
```
I typed it and website displays 2,3 numbers. So we can say that website has vulnerability.

### What each part of it does

Break your payload into pieces and explain each one. For example: what closes the
original string, what pulls in the other table, what hides the rest of the query.

Payload: `%' UNION SELECT CAST(id AS VARCHAR), email || ' : ' || password_hash, role FROM users--`

#### 1. `%'` (Close Original String)
* **Purpose:** Closes the application's underlying SQL query wrapper.
* **Mechanism:** Completes the opening single quote injected by code like `LIKE '%USER_INPUT%'`.

#### 2. `UNION` (Combine Queries)
* **Purpose:** Appends the results of a new query to the original one.
* **Mechanism:** Merges dataset rows. Requires the exact same number and compatible data types of columns as the initial query.

#### 3. `SELECT CAST(id AS VARCHAR), email || ' : ' || password_hash, role` (Data Retrieval & Formatting)
* **Purpose:** Selects and shapes the target data into 3 columns.
* **Mechanism:** * `CAST(id AS VARCHAR)`: Prevents type mismatch errors by converting numbers to text.
  * `email || ' : ' || password_hash`: Uses `||` operator to concatenate multiple fields into a single column to fit display limitations.
  * `role`: Populates the final required column slot.

#### 4. `FROM users` (Target Table)
* **Purpose:** Specifies where to pull the sensitive data from.
* **Mechanism:** Points directly to the `users` table mapped in the database.

#### 5. `--` (Comment Out Remainder)
* **Purpose:** Hides and neutralizes the rest of the original query.
* **Mechanism:** Treats everything after it as a comment, preventing syntax errors from the application's trailing code (like the closing `%'`).

### What came back

What data appeared that should never have been there? Paste
a line or two. A screenshot is ideal.

> Maya Thompson | maya.thompson@inkfeed.app | $2a$10$Qe7kI3yE8mZ1cT0vJ9oQ1uPb5sYxKfM2nR4hVjLwA6dCtB8oNqW2
user

> Leo Fernandez | leo.fernandez@inkfeed.app | $2a$10$9aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789abcdefghijklmnoPq
user

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

Mixing Code and Data: The code uses string concatenation (+) to build the SQL query.

Lack of Separation: The database engine receives a single raw string and cannot distinguish between the developer's original code and the user's input (q).

Altering Structure: The single quote (') in the payload prematurely closes the text wrapper, tricking the database parser into interpreting the user input as actual executable SQL commands (UNION SELECT) rather than a plain search text.

---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

(the safe repository method)

Why this road was chosen: Instead of maintaining raw native SQL queries (entityManager.createNativeQuery) inside the controller layer, the search logic was refactored to use a safe, derived query method natively provided by Spring Data JPA in PostRepository. Specifically, switching to a built-in method like findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q) completely delegates query creation to the JPA framework.

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

This road completely eliminates the vulnerability because it enforces a strict cryptographic and syntactic barrier between the execution logic (code) and user input (data) by utilizing Parameterized Queries (Prepared Statements) under the hood.

When Spring Data JPA or a parameterized query is executed, the database engine compiles the SQL command structure before the user input is inserted. The user input q is treated strictly as a single literal scalar value within a pre-compiled placeholder (e.g., ?). Even if the input contains single quotes ('), UNION, or comment symbols (--), the database driver passes them safely as text attributes rather than executable directives. The structure of the query is locked, making structural manipulation impossible.

### Why I did NOT just block quotes / the word UNION

Relying on blocklists (filtering out specific characters or keywords) is an insecure methodology for several key reasons:

Bypass Techniques: Attackers can bypass naive string filters using alternative encodings (e.g., URL encoding, hex encoding, or specialized SQL concatenation tricks) that the filter might miss but the SQL parser will still execute.

Breaking Legitimate Features: Blocking single quotes breaks completely valid, non-malicious user queries. For example, a user attempting to search for an article title containing a normal contraction or name (such as O'Brien or buyer's guide) would be blocked or break the application.

Whack-a-Mole Defenses: It addresses a structural architectural flaw with cosmetic input filtering, leaving the underlying interpreter vulnerable to future variations of the exploit.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

0 results returned (No posts found).

The application no longer crashes, and it does not display any information from the users table. Instead, it securely looks for an actual post whose title or body contains the literal text string: %' UNION SELECT CAST(id AS VARCHAR), email || ' : ' || password_hash, role FROM users--. Since no post contains that exact text, it safely returns an empty list.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

Yes. Standard search terms continue to function normally and return exactly the relevant matching entries from the posts table, confirming that the application functionality remains completely intact while remaining secure.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

What else in this app worries you?

The Comment Endpoint Access Control: The addComment endpoint (POST /api/posts/{postId}/comments) explicitly contains the developer comment: "No login, no checks. Anyone can drop one." This endpoint completely lacks authentication and authorization checks. It allows anonymous, untrusted users to write to the database, leaving the application highly vulnerable to automated spam, database storage exhaustion, or stored Cross-Site Scripting (XSS) if the comment body is not strictly sanitized before rendering.

Exposing Raw Entity Objects (Mass Assignment/Over-posting): The addComment method binds input data using a DTO, but the endpoint lacks rate-limiting or anti-automation mechanisms. A lack of structural authorization checks across the controller endpoints could allow unauthenticated entities to perform arbitrary state changes.

Sensitive Database Attributes Availability: The fact that the application's domain layers handle active fields like passwordHash in plaintext-accessible strings inside entities that could accidentally leak via standard serialization calls (like an accidental return of a full User object) poses an architectural risk. Data mapping abstractions should ensure that fields containing sensitive hashes never get combined into shared execution contexts unless explicitly validated.