package io.github.tonyblack10.mcpdoc.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class McpdocApplicationTests {

	@Autowired
	MockMvc mvc;

	@Test
	void servesDocsPage() throws Exception {
		mvc.perform(get("/mcp-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("mcpdoc-example")));
	}

	@Test
	void servesToolsJson() throws Exception {
		mvc.perform(get("/mcp-docs/tools.json"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tools[*].name", org.hamcrest.Matchers.hasItems("sum", "create_task")));
	}

	@Test
	void servesAssetsFromStarterJar() throws Exception {
		mvc.perform(get("/mcp-docs/assets/mcp-docs.css")).andExpect(status().isOk());
		mvc.perform(get("/mcp-docs/assets/mcp-client.js")).andExpect(status().isOk());
	}

	@Test
	void doesNotExposeTemplates() throws Exception {
		mvc.perform(get("/mcp-docs/assets/../page.html")).andExpect(status().is4xxClientError());
	}

}
