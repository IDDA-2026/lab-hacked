package com.ironhack.simple_auth.controller;

import com.ironhack.simple_auth.dto.CommentRequest;
import com.ironhack.simple_auth.dto.CommentView;
import com.ironhack.simple_auth.dto.PostView;
import com.ironhack.simple_auth.dto.SearchResult;
import com.ironhack.simple_auth.model.Comment;
import com.ironhack.simple_auth.model.Post;
import com.ironhack.simple_auth.repository.CommentRepository;
import com.ironhack.simple_auth.repository.PostRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    public PostController(PostRepository postRepository, CommentRepository commentRepository) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
    }

    /** The full feed. Newest stuff first would be nicer, I know, but this is fine for the lab. */
    @GetMapping
    public List<PostView> all() {
        return postRepository.findAll().stream().map(PostView::from).toList();
    }

    /**
     * Search posts by keyword.
     *
     * FIX: The original implementation built a raw SQL string by concatenating the
     * user-supplied query parameter directly into the query:
     *
     *   "SELECT id, title, body FROM posts WHERE title LIKE '%" + q + "%' ..."
     *
     * This allowed an attacker to escape the LIKE string and inject arbitrary SQL,
     * including a UNION SELECT that read the private users table.
     *
     * The fix delegates to the Spring Data JPA derived method
     * findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(). Spring Data
     * compiles this into a parameterized prepared statement, where the keyword is
     * always bound as a literal value. The database receives the query structure
     * and the data separately, so the data can never alter the query's shape —
     * injection is structurally impossible, not just unlikely.
     *
     * As a second layer of defense (defense-in-depth), the keyword is trimmed and
     * capped at 200 characters. This does NOT replace the parameterized query —
     * the parameterized query is the real fix. Blocking long inputs simply reduces
     * the surface area for other abuse (e.g., ReDoS, excessive result sets).
     */
    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam(name = "q", defaultValue = "") String q) {
        // --- Second-layer input validation (defense-in-depth, NOT the primary fix) ---
        String keyword = q.trim();
        if (keyword.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Search query must be 200 characters or fewer.");
        }

        // --- Safe parameterized query via Spring Data JPA derived method ---
        // Spring Data translates this method name into:
        //   SELECT p FROM Post p WHERE LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
        //      OR LOWER(p.body) LIKE LOWER(CONCAT('%', :keyword, '%'))
        // The keyword is bound as a PreparedStatement parameter — never interpolated
        // into the SQL string. A UNION injection payload is treated as a literal
        // substring to search for, not as SQL to execute.
        return postRepository
                .findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(keyword, keyword)
                .stream()
                .map(post -> new SearchResult(post.getId(), post.getTitle(), post.getBody()))
                .toList();
    }

    /** Add a comment to a post. No login, no checks. Anyone can drop one. */
    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView addComment(@PathVariable Long postId, @RequestBody CommentRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        String authorName = (request.authorName() == null || request.authorName().isBlank())
                ? "Anonymous" : request.authorName();

        Comment comment = new Comment(authorName, request.body(), post);
        Comment saved = commentRepository.save(comment);
        return new CommentView(saved.getId(), saved.getAuthorName(), saved.getBody());
    }
}

