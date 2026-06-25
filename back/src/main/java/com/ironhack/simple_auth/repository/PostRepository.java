package com.ironhack.simple_auth.repository;

import com.ironhack.simple_auth.model.Post;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    // A safe, parameterized search. Spring Data builds this query for us and
    // binds the keyword as a real parameter, so it can never change the query's
    // shape. It is here on purpose. (Is the feed actually using it? Look closely.)
    List<Post> findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(
            String title,
            String body
    );
}
