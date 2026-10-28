package io.github.tonyblack10.mcpdoc.example;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Atrás de um API gateway que remove o prefixo da rota (/pedidos/mcp-docs chega como /mcp-docs),
 * os links, os assets e o endpoint MCP da página precisam continuar com o prefixo público.
 */
class GatewayPrefixTests {

	@Nested
	@SpringBootTest(properties = "server.forward-headers-strategy=framework")
	@AutoConfigureMockMvc
	class WithForwardedPrefixHeader {

		@Autowired
		MockMvcTester mvc;

		@Test
		void pageUsesForwardedPrefix() {
			assertThat(mvc.get().uri("/mcp-docs").header("X-Forwarded-Prefix", "/pedidos")).hasStatusOk()
					.bodyText()
					.contains("href=\"/pedidos/mcp-docs/assets/mcp-docs.css")
					.contains("hx-get=\"/pedidos/mcp-docs/fragments/list\"")
					.contains("href=\"/pedidos/mcp-docs/tools/sum\"")
					.contains("\"contextPath\":\"/pedidos\"")
					.contains("<code id=\"ep\">/pedidos/mcp</code>");
		}

		@Test
		void fragmentsUseForwardedPrefix() {
			assertThat(mvc.get().uri("/mcp-docs/fragments/list").header("X-Forwarded-Prefix", "/pedidos")).hasStatusOk()
					.bodyText().contains("hx-get=\"/pedidos/mcp-docs/tools/sum\"");
		}

		@Test
		void withoutHeaderUsesRoot() {
			assertThat(mvc.get().uri("/mcp-docs")).hasStatusOk()
					.bodyText().contains("href=\"/mcp-docs/assets/mcp-docs.css").contains("\"contextPath\":\"\"");
		}
	}

	@Nested
	@SpringBootTest(properties = "mcp.docs.base-path=/api/pedidos/")
	@AutoConfigureMockMvc
	class WithBasePathProperty {

		@Autowired
		MockMvcTester mvc;

		@Test
		void pageUsesConfiguredBasePath() {
			assertThat(mvc.get().uri("/mcp-docs")).hasStatusOk()
					.bodyText()
					.contains("href=\"/api/pedidos/mcp-docs/assets/mcp-docs.css")
					.contains("href=\"/api/pedidos/mcp-docs/tools/sum\"")
					.contains("\"contextPath\":\"/api/pedidos\"");
		}
	}

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	class WithServletContextPath {

		@Autowired
		MockMvcTester mvc;

		@Test
		void pageUsesRequestContextPath() {
			assertThat(mvc.get().uri("/app/mcp-docs").contextPath("/app")).hasStatusOk()
					.bodyText()
					.contains("href=\"/app/mcp-docs/assets/mcp-docs.css")
					.contains("\"contextPath\":\"/app\"");
		}

		@Test
		void forwardedPrefixIsIgnoredWithoutFrameworkStrategy() {
			assertThat(mvc.get().uri("/mcp-docs").header("X-Forwarded-Prefix", "/evil")).hasStatusOk()
					.bodyText().doesNotContain("/evil");
		}
	}
}
