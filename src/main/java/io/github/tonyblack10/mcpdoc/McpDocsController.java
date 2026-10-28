package io.github.tonyblack10.mcpdoc;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Documentação das tools MCP deste servidor, renderizada no servidor e navegada com htmx:
 *   GET /mcp-docs                  página completa
 *   GET /mcp-docs/tools/{name}     detalhe da tool (fragmento para o htmx; página completa sem htmx)
 *   GET /mcp-docs/fragments/list   lista filtrada (busca)
 *   GET /mcp-docs/tools.json       definição das tools (mesmo conteúdo do tools/list)
 *   GET /mcp-docs/assets/**        CSS e JS (src/main/resources/mcp-docs/assets, servidos via McpDocsAutoConfiguration)
 *
 * A chamada às tools (initialize/tools/call, bearer token, headers) acontece no navegador,
 * direto no endpoint MCP: este controller não executa tools nem faz proxy de requisições.
 *
 * Propriedades (todas opcionais):
 *   mcp.docs.enabled=true       desliga tudo com false
 *   mcp.docs.try-it-out=true    esconde o painel "Testar" com false
 *   mcp.docs.transport          streamable | sse (padrão: spring.ai.mcp.server.protocol)
 *   mcp.docs.endpoint           URL do endpoint MCP (padrão: o do transporte, lido das propriedades do Spring AI)
 *   mcp.docs.title              título da página (padrão: spring.ai.mcp.server.name)
 *
 * Nome, versão e instruções vêm de spring.ai.mcp.server.* (em application.properties, preenchidos a partir do pom.xml).
 *   mcp.docs.htmx-src           script do htmx (padrão: CDN com versão fixa)
 *
 * Funciona com WebMVC e WebFlux.
 */
@RestController
@RequestMapping("/mcp-docs")
public class McpDocsController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final McpDocsPages pages;

    public McpDocsController(
            ObjectProvider<List<McpServerFeatures.SyncToolSpecification>> syncTools,
            ObjectProvider<List<McpServerFeatures.AsyncToolSpecification>> asyncTools,
            ObjectMapper mapper,
            ObjectProvider<McpServerProperties> serverProps,
            ObjectProvider<McpServerSseProperties> sseProps,
            ObjectProvider<McpServerStreamableHttpProperties> streamableProps,
            @Value("${mcp.docs.title:}") String titleOverride,
            @Value("${mcp.docs.transport:}") String transportOverride,
            @Value("${mcp.docs.endpoint:}") String endpoint,
            @Value("${mcp.docs.try-it-out:true}") boolean tryItOut,
            @Value("${server.servlet.context-path:${spring.webflux.base-path:}}") String contextPath,
            @Value("${mcp.docs.htmx-src:https://unpkg.com/htmx.org@4.0.0/dist/htmx.min.js}") String htmxSrc) {

        // Usa a configuração efetiva do servidor MCP (os mesmos beans que o Spring AI usa para mapear o endpoint)
        McpServerProperties server = serverProps.getIfAvailable();
        String title = !titleOverride.isBlank() ? titleOverride
                : server != null && server.getName() != null ? server.getName() : "MCP Server";
        String version = server != null && server.getVersion() != null ? server.getVersion() : "";
        String instructions = server != null && server.getInstructions() != null ? server.getInstructions() : "";
        String transport = !transportOverride.isBlank() ? transportOverride
                : server != null && server.getProtocol() != null ? server.getProtocol().name() : "STREAMABLE";

        // STREAMABLE e STATELESS usam o mesmo cliente (POST com ou sem sessão)
        String kind = "SSE".equalsIgnoreCase(transport.trim()) ? "sse" : "streamable";
        String ep = !endpoint.isBlank() ? endpoint : "sse".equals(kind)
                ? sseProps.stream().map(McpServerSseProperties::getSseEndpoint).findFirst().orElse("/sse")
                : streamableProps.stream().map(McpServerStreamableHttpProperties::getMcpEndpoint).findFirst().orElse("/mcp");
        String ctx = contextPath == null || contextPath.isBlank() || "/".equals(contextPath)
                ? "" : (contextPath.startsWith("/") ? contextPath : "/" + contextPath);

        McpDocsPages.Meta meta = new McpDocsPages.Meta(title, version, instructions, kind, ep, tryItOut, ctx, htmxSrc);
        this.pages = new McpDocsPages(mapper, meta, () -> {
            Stream<McpSchema.Tool> sync = syncTools.stream()
                    .flatMap(List::stream)
                    .map(McpServerFeatures.SyncToolSpecification::tool);
            Stream<McpSchema.Tool> async = asyncTools.stream()
                    .flatMap(List::stream)
                    .map(McpServerFeatures.AsyncToolSpecification::tool);
            // Serializa com Jackson para não depender dos getters de cada versão do SDK
            Map<String, Map<String, Object>> byName = new TreeMap<>();
            Stream.concat(sync, async)
                    .map(tool -> mapper.convertValue(tool, MAP_TYPE))
                    .forEach(tool -> byName.putIfAbsent(String.valueOf(tool.get("name")), tool));
            return List.copyOf(byName.values());
        });
    }

    @GetMapping
    public ResponseEntity<String> index(@RequestParam(name = "q", required = false) String q) {
        return html(pages.page(q));
    }

    @GetMapping("/tools/{name:.+}")
    public ResponseEntity<String> tool(
            @PathVariable("name") String name,
            @RequestParam(name = "q", required = false) String q,
            @RequestHeader(name = "HX-Target", required = false) String hxTarget) {
        return html(pages.tool(name, q, hxTarget));
    }

    @GetMapping("/fragments/list")
    public ResponseEntity<String> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestHeader(name = "HX-Current-URL", required = false) String currentUrl) {
        return html(pages.list(q, currentUrl));
    }

    @GetMapping(value = "/tools.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> toolsJson() {
        return pages.toolsJson();
    }

    private static ResponseEntity<String> html(McpDocsPages.Reply r) {
        return ResponseEntity.status(r.status())
                .contentType(MediaType.parseMediaType(r.contentType()))
                // a mesma URL devolve fragmento ou página conforme o HX-Target
                .header("Vary", "HX-Target")
                .header("Cache-Control", "no-cache")
                .body(r.body());
    }
}
