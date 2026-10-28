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
	void joinsSseBaseUrlAndEndpoint() {
		assertThat(McpDocsController.joinPath("", "/sse")).isEqualTo("/sse");
		assertThat(McpDocsController.joinPath("/api/", "/sse")).isEqualTo("/api/sse");
		assertThat(McpDocsController.joinPath("https://host/mcp", "sse")).isEqualTo("https://host/mcp/sse");
	}

}
