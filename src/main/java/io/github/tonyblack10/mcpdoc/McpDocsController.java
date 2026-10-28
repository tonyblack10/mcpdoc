package io.github.tonyblack10.mcpdoc;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Documentação das tools MCP deste servidor, renderizada no servidor e navegada com htmx:
 *   GET /mcp-docs                  página completa
 *   GET /mcp-docs/tools/{name}     detalhe da tool (fragmento para o htmx; página completa sem htmx)
 *   GET /mcp-docs/fragments/list   lista filtrada (busca)
 *   GET /mcp-docs/tools.json       definição das tools (mesmo conteúdo do tools/list)
 *   GET /mcp-docs/assets/**        CSS e JS (src/main/resources/mcp-docs/assets, servidos via McpDocsAutoConfiguration)
 *
 * As tools vêm do próprio servidor MCP (listTools() do McpSyncServer, McpAsyncServer ou das versões stateless),
 * então a página mostra o mesmo que o tools/list, inclusive tools registradas em runtime.
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
 * Funciona com WebMVC e WebFlux, com transporte STREAMABLE, STATELESS ou SSE.
 */
@RestController
@RequestMapping("/mcp-docs")
public class McpDocsController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final McpDocsPages pages;
    private final ObjectMapper mapper;
    private final ObjectProvider<McpSyncServer> syncServer;
    private final ObjectProvider<McpAsyncServer> asyncServer;
    private final ObjectProvider<McpStatelessSyncServer> statelessSyncServer;
    private final ObjectProvider<McpStatelessAsyncServer> statelessAsyncServer;

    public McpDocsController(
            ObjectProvider<McpSyncServer> syncServer,
            ObjectProvider<McpAsyncServer> asyncServer,
            ObjectProvider<McpStatelessSyncServer> statelessSyncServer,
            ObjectProvider<McpStatelessAsyncServer> statelessAsyncServer,
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

        this.mapper = mapper;
        this.syncServer = syncServer;
        this.asyncServer = asyncServer;
        this.statelessSyncServer = statelessSyncServer;
        this.statelessAsyncServer = statelessAsyncServer;

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
                ? sseProps.stream().map(p -> joinPath(p.getBaseUrl(), p.getSseEndpoint())).findFirst().orElse("/sse")
                : streamableProps.stream().map(McpServerStreamableHttpProperties::getMcpEndpoint).findFirst().orElse("/mcp");
        String ctx = contextPath == null || contextPath.isBlank() || "/".equals(contextPath)
                ? "" : (contextPath.startsWith("/") ? contextPath : "/" + contextPath);

        McpDocsPages.Meta meta = new McpDocsPages.Meta(title, version, instructions, kind, ep, tryItOut, ctx, htmxSrc);
        this.pages = new McpDocsPages(mapper, meta);
    }

    /** baseUrl do SSE + endpoint, sem barra duplicada (baseUrl vazio devolve só o endpoint). */
    static String joinPath(String base, String path) {
        String b = base == null ? "" : base.trim();
        String p = path == null || path.isBlank() ? "/sse" : path.trim();
        if (b.isEmpty() || "/".equals(b)) {
            return p;
        }
        return b.replaceAll("/+$", "") + (p.startsWith("/") ? p : "/" + p);
    }

    @GetMapping
    public Mono<ResponseEntity<String>> index(@RequestParam(name = "q", required = false) String q) {
        return tools().map(tools -> html(pages.page(q, tools)));
    }

    @GetMapping("/tools/{name:.+}")
    public Mono<ResponseEntity<String>> tool(
            @PathVariable("name") String name,
            @RequestParam(name = "q", required = false) String q,
            @RequestHeader(name = "HX-Target", required = false) String hxTarget) {
        return tools().map(tools -> html(pages.tool(name, q, hxTarget, tools)));
    }

    @GetMapping("/fragments/list")
    public Mono<ResponseEntity<String>> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestHeader(name = "HX-Current-URL", required = false) String currentUrl) {
        return tools().map(tools -> html(pages.list(q, currentUrl, tools)));
    }

    @GetMapping(value = "/tools.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> toolsJson() {
        return tools().map(pages::toolsJson);
    }

    /**
     * Tools do servidor MCP ativo (só um dos quatro tipos existe, conforme spring.ai.mcp.server.protocol/type).
     * O listTools() síncrono roda fora do event loop: no WebFlux, bloquear a thread do Netty é proibido.
     */
    private Mono<List<Map<String, Object>>> tools() {
        Mono<List<McpSchema.Tool>> tools;
        McpSyncServer sync = syncServer.getIfAvailable();
        McpAsyncServer async = asyncServer.getIfAvailable();
        McpStatelessSyncServer statelessSync = statelessSyncServer.getIfAvailable();
        McpStatelessAsyncServer statelessAsync = statelessAsyncServer.getIfAvailable();
        if (sync != null) {
            tools = blocking(sync::listTools);
        } else if (async != null) {
            tools = async.listTools().collectList();
        } else if (statelessSync != null) {
            tools = blocking(statelessSync::listTools);
        } else if (statelessAsync != null) {
            tools = statelessAsync.listTools().collectList();
        } else {
            tools = Mono.just(List.of());
        }
        // Serializa com Jackson para não depender dos getters de cada versão do SDK
        return tools.map(list -> list.stream().map(tool -> mapper.convertValue(tool, MAP_TYPE)).toList());
    }

    private static Mono<List<McpSchema.Tool>> blocking(Callable<List<McpSchema.Tool>> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
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
