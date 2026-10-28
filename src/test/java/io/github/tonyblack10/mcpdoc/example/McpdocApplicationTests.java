package io.github.tonyblack10.mcpdoc.example;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class McpdocApplicationTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	McpSyncServer server;

	@Test
	void servesDocsPage() {
		assertThat(mvc.get().uri("/mcp-docs")).hasStatusOk()
				.bodyText().contains("mcpdoc-example").contains("create_task");
	}

	@Test
	void servesToolsJson() {
		assertThat(mvc.get().uri("/mcp-docs/tools.json")).hasStatusOk()
				.bodyJson().extractingPath("$.tools[*].name").asArray().contains("sum", "create_task");
	}

	@Test
	void listsToolsAddedAtRuntime() {
		var tool = McpSchema.Tool.builder().name("runtime_tool").description("Adicionada em runtime")
				.inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, null, null, null)).build();
		server.addTool(McpServerFeatures.SyncToolSpecification.builder().tool(tool)
				.callHandler((exchange, request) -> McpSchema.CallToolResult.builder().addTextContent("ok").build())
				.build());
		try {
			assertThat(mvc.get().uri("/mcp-docs/tools.json")).hasStatusOk()
					.bodyJson().extractingPath("$.tools[*].name").asArray().contains("runtime_tool");
			assertThat(mvc.get().uri("/mcp-docs/tools/runtime_tool")).hasStatusOk()
					.bodyText().contains("Adicionada em runtime");
		} finally {
			server.removeTool("runtime_tool");
		}
	}

	@Test
	void servesAssetsFromStarterJar() {
		assertThat(mvc.get().uri("/mcp-docs/assets/mcp-docs.css")).hasStatusOk();
		assertThat(mvc.get().uri("/mcp-docs/assets/mcp-client.js")).hasStatusOk();
	}

	@Test
	void doesNotExposeTemplates() {
		assertThat(mvc.get().uri("/mcp-docs/assets/../page.html")).hasStatus4xxClientError();
	}

}
