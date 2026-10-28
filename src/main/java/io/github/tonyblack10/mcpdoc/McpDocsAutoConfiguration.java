package io.github.tonyblack10.mcpdoc;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuração do starter: basta ter o jar no classpath de uma aplicação Spring AI com servidor MCP
 * para expor a documentação em /mcp-docs. Desligue com mcp.docs.enabled=false.
 *
 * Os assets (CSS e JS) ficam em classpath:/mcp-docs/assets/ e são servidos por um resource handler próprio,
 * para não depender da configuração de recursos estáticos da aplicação.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(McpServerProperties.class)
@ConditionalOnProperty(prefix = "mcp.docs", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(McpDocsController.class)
public class McpDocsAutoConfiguration {

    static final String ASSETS_PATTERN = "/mcp-docs/assets/**";
    static final String ASSETS_LOCATION = "classpath:/mcp-docs/assets/";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.config.annotation.WebMvcConfigurer")
    static class ServletAssets implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Override
        public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
            registry.addResourceHandler(ASSETS_PATTERN).addResourceLocations(ASSETS_LOCATION);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.reactive.config.WebFluxConfigurer")
    static class ReactiveAssets implements org.springframework.web.reactive.config.WebFluxConfigurer {
        @Override
        public void addResourceHandlers(org.springframework.web.reactive.config.ResourceHandlerRegistry registry) {
            registry.addResourceHandler(ASSETS_PATTERN).addResourceLocations(ASSETS_LOCATION);
        }
    }
}
