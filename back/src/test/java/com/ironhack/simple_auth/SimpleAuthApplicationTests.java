package com.ironhack.simple_auth;

import com.ironhack.simple_auth.controller.PostController;
import com.ironhack.simple_auth.dto.CommentRequest;
import com.ironhack.simple_auth.dto.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SimpleAuthApplicationTests {

    @Autowired
    private PostController postController;

    @Test
    void contextLoads() {
    }

    @Test
    void unionInjectionPayloadCannotReadUsers() {
        String payload = "' UNION SELECT id, email, password_hash FROM users -- ";

        assertThat(postController.search(payload))
                .extracting(SearchResult::title)
                .doesNotContain("admin@inkfeed.app")
                .isEmpty();
    }

    @Test
    void normalSearchStillFindsPosts() {
        assertThat(postController.search("pen"))
                .extracting(SearchResult::title)
                .containsExactlyInAnyOrder(
                        "What pen do you swear by?",
                        "Color theory broke my brain (in a good way)");
        assertThat(postController.search("color"))
                .extracting(SearchResult::title)
                .containsExactly("Color theory broke my brain (in a good way)");
        assertThat(postController.search("comic"))
                .extracting(SearchResult::title)
                .containsExactly("Looking for feedback on my comic panels");
    }

    @Test
    void commentTreatsSqlLikeTextAsData() {
        var comment = postController.addComment(
                1L,
                new CommentRequest("Security test", "' UNION remains plain text"));

        assertThat(comment.authorName()).isEqualTo("Security test");
        assertThat(comment.body()).isEqualTo("' UNION remains plain text");
    }
}
