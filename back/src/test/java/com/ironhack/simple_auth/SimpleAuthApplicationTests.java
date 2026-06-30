package com.ironhack.simple_auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest
class SimpleAuthApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void searchTreatsUnionPayloadAsText() throws Exception {
		String payload = "' UNION SELECT id, email, password_hash FROM users -- ";

		mockMvc.perform(get("/api/posts/search").param("q", payload))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("admin@inkfeed.app"))))
				.andExpect(content().string(not(containsString("password_hash"))))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void normalSearchStillReturnsPosts() throws Exception {
		mockMvc.perform(get("/api/posts/search").param("q", "pen"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(content().string(containsString("What pen do you swear by?")));
	}

	@Test
	void publicCommentEndpointStillWorks() throws Exception {
		mockMvc.perform(post("/api/posts/1/comments")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"authorName":"Lab Tester","body":"Still works after the SQL injection fix."}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.authorName").value("Lab Tester"))
				.andExpect(jsonPath("$.body").value("Still works after the SQL injection fix."));
	}
}
