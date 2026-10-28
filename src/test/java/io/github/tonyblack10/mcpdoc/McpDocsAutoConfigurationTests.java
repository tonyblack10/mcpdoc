package io.github.tonyblack10.mcpdoc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class McpDocsAutoConfigurationTests {

	private final WebApplicationContextRunner web = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, McpDocsAutoConfiguration.class))
			.withPropertyValues("mcp.docs.enabled=true");

	@Test
	void enabledByPropertyInWebApplication() {
		web.run(ctx -> assertThat(ctx).hasSingleBean(McpDocsController.class));
	}

	@Test
	void disabledByDefault() {
		new WebApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, McpDocsAutoConfiguration.class))
				.run(ctx -> assertThat(ctx).doesNotHaveBean(McpDocsController.class));
	}

	@Test
	void disabledByProperty() {
		web.withPropertyValues("mcp.docs.enabled=false")
				.run(ctx -> assertThat(ctx).doesNotHaveBean(McpDocsController.class));
	}

	@Test
	void disabledWhenMcpServerIsDisabled() {
		web.withPropertyValues("spring.ai.mcp.server.enabled=false")
				.run(ctx -> assertThat(ctx).doesNotHaveBean(McpDocsController.class));
	}

	@Test
	void disabledWithStdioTransport() {
		web.withPropertyValues("spring.ai.mcp.server.stdio=true")
				.run(ctx -> assertThat(ctx).doesNotHaveBean(McpDocsController.class));
	}

	@Test
	void disabledOutsideWebApplication() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, McpDocsAutoConfiguration.class))
				.withPropertyValues("mcp.docs.enabled=true")
				.run(ctx -> assertThat(ctx).doesNotHaveBean(McpDocsController.class));
	}

	@Test
	void sseUrlKeepsOnlyAbsoluteBaseUrl() {
		assertThat(McpDocsController.sseUrl("", "/sse")).isEqualTo("/sse");
		// baseUrl só com caminho não entra: o Spring AI mapeia o sse-endpoint sem ele
		assertThat(McpDocsController.sseUrl("/api/", "/sse")).isEqualTo("/sse");
		assertThat(McpDocsController.sseUrl("https://host/mcp/", "sse")).isEqualTo("https://host/mcp/sse");
	}

	@Test
	void normalizesPublicPath() {
		assertThat(McpDocsController.normalizePath(null)).isEmpty();
		assertThat(McpDocsController.normalizePath("/")).isEmpty();
		assertThat(McpDocsController.normalizePath("pedidos/")).isEqualTo("/pedidos");
		assertThat(McpDocsController.normalizePath("/api//pedidos")).isEqualTo("/api/pedidos");
		// "//host" seria uma URL de outro host em href/src
		assertThat(McpDocsController.normalizePath("//evil.example")).isEqualTo("/evil.example");
	}

}
