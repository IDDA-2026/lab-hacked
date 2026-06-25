# InkFeed SQL Injection Investigation

## 1. Vulnerability

The search endpoint was vulnerable to SQL injection because the user-provided
search value was concatenated directly into a native SQL query.

The database could not distinguish between normal search data and SQL commands.

## 2. Initial test

I first entered a single quote into the search input:

    '

This caused an SQL syntax error in the backend, which showed that the input
was being inserted directly into the SQL query.

## 3. Payload used

The payload used to reproduce the data leak was:

    ' UNION SELECT id, email, password FROM users -- 

## 4. Payload explanation

- The first quote closes the string used by the original LIKE condition.
- UNION appends the output of another SELECT query to the post search results.
- SELECT id, email, password FROM users reads data from the private users table.
- The users columns match the three columns expected by the original result:
  id, title, and body.
- The double dash comments out the remaining part of the original query.

As a result, user emails and password hashes were rendered as normal search
results in the frontend.

## 5. Root cause

The root cause was SQL query construction through string concatenation.

Untrusted input was added directly to the SQL command, allowing the search
value to change the structure of the query.

## 6. Fix

I replaced the dynamically constructed native SQL query with the Spring Data
repository method:

    findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)

I selected this solution because Spring binds the input as data instead of
inserting it into the SQL command.

The input can no longer modify the structure of the query.

## 7. Verification

After applying the fix:

- The original SQL injection payload returned no user data.
- Searches for pen, color, and comic continued to work.
- Adding comments to posts continued to work.
- No user email or password hash was exposed.