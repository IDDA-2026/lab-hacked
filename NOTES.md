### What each part of it does

| Piece | What it does |
|---|---|
| `'` | Closes the single-quoted string that was already open inside the LIKE clause. Without this the database would see a syntax error. |
| ` UNION SELECT id, email, password FROM users` | Appends a second, completely separate query onto the first one. UNION stacks the rows of both queries together as if they came from one table. The column count must match — the original query selected `id, title, body` (3 columns), so we select 3 columns from users too. |
| ` --` | Everything after `--` is a SQL comment. This hides the `%'` that was left over from the original query string, so the database does not choke on a dangling fragment. |

The full string the database actually received looked like this:

```sql
SELECT id, title, body FROM posts
WHERE title LIKE '%' UNION SELECT id, email, password FROM users --%'
OR body LIKE '%' UNION SELECT id, email, password FROM users --%'
```

### What came back

The InkFeed search results page showed what looked like normal post cards, but
the title and body fields contained the admin's email and password hash pulled
straight from the users table — data that no public endpoint is supposed to
return:

- Email: `admin@inkfeed.app`
- Password hash: `$2a$10$...` (bcrypt hash visible in the result card)

This confirmed the breach. The frontend rendered the stolen row as if it were a
normal search result, which is exactly why it went unnoticed.

---

## 3. Why it worked (root cause)

The backend built the SQL query by gluing the user's input directly into a
string:

```java
String sql = "SELECT id, title, body FROM posts " +
        "WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";
```

The database received one big string and had no way to tell which part was the
intended query and which part was data typed by a stranger. When the payload
arrived, the database parsed the entire thing as valid SQL and executed it
faithfully — including the attacker's UNION clause. The database was not
compromised; it did exactly what it was told. The problem was that a stranger
got to decide what it was told.

---

## 4. The fix

### Which road did I take?

The safe repository method that was already in `PostRepository.java`:

```java
List<Post> findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(String title, String body);
```

The controller now calls this instead of building any SQL string at all:

```java
@GetMapping("/search")
public List<SearchResult> search(@RequestParam(name = "q", defaultValue = "") String q) {
    return postRepository
            .findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)
            .stream()
            .map(post -> new SearchResult(post.getId(), post.getTitle(), post.getBody()))
            .toList();
}
```

### Why this fixes the root cause and not just the symptom

Spring Data JPA generates a parameterized query from the method name. The
database receives the query shape — the fixed SQL structure — compiled and
finalized before any user value is involved. The value of `q` is then passed in
separately as a bound parameter, treated as pure data at all times.

This means the database never parses the user's input as SQL. It does not matter
what `q` contains — a quote, the word UNION, an entire SQL statement — none of
it can change the structure of the query because the structure was locked before
the value arrived. Injection is not just unlikely; it is structurally impossible.

### Why I did NOT just block quotes or the word UNION

Blocklisting is the wrong fix for two reasons:

1. **It breaks legitimate use.** A member named O'Brien cannot search for their
   own posts. A post titled "The UNION of Colors" disappears from results. The
   app becomes less useful for real users while remaining breakable for
   determined attackers.

2. **It does not fix the root cause.** The root cause is that data was
   indistinguishable from code at the database level. Stripping characters or
   keywords patches one known payload while leaving the structure of the problem
   intact. Attackers use alternate encodings, multi-step injections, and
   combinations of characters that no blocklist anticipates. The parameterized
   approach eliminates the entire class of attack, not just the examples we
   thought of today.

---

## 5. Proof the fix holds

After applying the fix and restarting the backend, I re-ran the original payload: