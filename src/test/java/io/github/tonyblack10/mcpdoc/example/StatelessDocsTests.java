package io.github.tonyblack10.mcpdoc.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.mcp.server.protocol=STATELESS")
@AutoConfigureMockMvc
class StatelessDocsTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void listsToolsOfStatelessServer() {
		assertThat(mvc.get().uri("/mcp-docs/tools.json")).hasStatusOk()
				.bodyJson().extractingPath("$.tools[*].name").asArray().contains("sum", "create_task");
	}

}
