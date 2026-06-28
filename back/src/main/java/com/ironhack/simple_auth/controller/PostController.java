package com.ironhack.simple_auth.controller;

import com.ironhack.simple_auth.dto.CommentRequest;
import com.ironhack.simple_auth.dto.CommentView;
import com.ironhack.simple_auth.dto.PostView;
import com.ironhack.simple_auth.dto.SearchResult;
import com.ironhack.simple_auth.model.Comment;
import com.ironhack.simple_auth.model.Post;
import com.ironhack.simple_auth.repository.CommentRepository;
import com.ironhack.simple_auth.repository.PostRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    @PersistenceContext
    private EntityManager entityManager;

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
     * FIX: the query text is now a fixed string with a bound parameter (?1),
     * never built by concatenating user input. The search term is sent to
     * the database separately, as a value, not as part of the SQL itself.
     * No matter what characters `q` contains (quotes, UNION, --, anything),
     * the database can only ever compare it against title/body as data. It
     * has no way to reinterpret it as a new piece of SQL syntax, because the
     * query's shape was already fixed before the value was even attached.
     */
    @GetMapping("/search")
    @SuppressWarnings("unchecked")
    public List<SearchResult> search(@RequestParam(name = "q", defaultValue = "") String q) {
        String sql = "SELECT id, title, body FROM posts " +
                "WHERE title LIKE CONCAT('%', ?1, '%') OR body LIKE CONCAT('%', ?1, '%')";

        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter(1, q)
                .getResultList();

        return rows.stream()
                .map(row -> new SearchResult(
                        row[0],
                        row[1] != null ? row[1].toString() : null,
                        row[2] != null ? row[2].toString() : null))
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