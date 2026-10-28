package io.github.tonyblack10.mcpdoc;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gera o HTML da documentação (página completa e fragmentos para o htmx).
 * Não depende do Spring: o controller só traduz requisições em chamadas a esta classe.
 *
 * Contrato com o htmx (todos os atributos ficam no próprio elemento, sem herança):
 *  - lista de tools: links com hx-get/hx-target="#main"/hx-push-url; a resposta traz o detalhe
 *    da tool e, fora de banda (hx-swap-oob), a lista com o item ativo atualizado;
 *  - busca: o input faz hx-get em /fragments/list e troca o conteúdo de #list;
 *  - sem htmx os links funcionam como navegação normal (página completa).
 */
final class McpDocsPages {

    record Meta(String title, String version, String instructions, String transport, String endpoint,
                boolean tryItOut, String contextPath, String htmxSrc) {
        String base() {
            return contextPath + "/mcp-docs";
        }
    }

    record Reply(int status, String contentType, String body) {}

    static final String HTML = "text/html;charset=UTF-8";

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Z_]+)}}");
    private static final Pattern TOOL_IN_URL = Pattern.compile("/tools/([^/?#]+)");

    private final ObjectMapper mapper;
    private final SchemaDocs docs;
    private final Meta meta;
    private final Supplier<List<Map<String, Object>>> toolSource;
    private final String pageTemplate = resource("/mcp-docs/page.html");
    private final String dialogHtml = resource("/mcp-docs/dialog.html");
    // vai no ?v= dos assets: muda quando o CSS/JS muda, e o navegador não usa versão antiga do cache
    private final String assetVersion = Integer.toHexString(
            (resource("/mcp-docs/assets/mcp-client.js") + resource("/mcp-docs/assets/mcp-docs.css")).hashCode());

    McpDocsPages(ObjectMapper mapper, Meta meta, Supplier<List<Map<String, Object>>> toolSource) {
        this.mapper = mapper;
        this.docs = new SchemaDocs(mapper);
        this.meta = meta;
        this.toolSource = toolSource;
    }

    // ---------- pontos de entrada (um por rota) ----------

    /** GET /mcp-docs */
    Reply page(String q) {
        List<Map<String, Object>> all = tools();
        String selected = all.isEmpty() ? null : str(all.get(0).get("name"));
        Map<String, Object> first = selected == null ? null : find(all, selected);
        String main = first != null ? toolHtml(first) : "<p class=\"muted\">Nenhuma tool registrada neste servidor.</p>";
        return new Reply(200, HTML, fullPage(q, selected, main));
    }

    /** GET /mcp-docs/tools/{name}; hxTarget é o header HX-Target enviado pelo htmx. */
    Reply tool(String name, String q, String hxTarget) {
        List<Map<String, Object>> all = tools();
        Map<String, Object> t = find(all, name);
        // htmx 2 envia o id ("main"); htmx 4 envia "tag#id" ("main#main")
        boolean fragment = hxTarget != null && ("main".equals(hxTarget) || hxTarget.endsWith("#main"));
        if (t == null) {
            String msg = "<p class=\"muted\">Tool não encontrada: <code>" + esc(name) + "</code></p>";
            return new Reply(fragment ? 200 : 404, HTML, fragment ? msg : fullPage(q, null, msg));
        }
        if (!fragment) {
            return new Reply(200, HTML, fullPage(q, name, toolHtml(t)));
        }
        String oob = "<div id=\"list\" hx-swap-oob=\"true\">" + listInner(filter(all, q), name) + "</div>";
        return new Reply(200, HTML, toolHtml(t) + oob);
    }

    /** GET /mcp-docs/fragments/list; currentUrl é o header HX-Current-URL (para marcar o item ativo). */
    Reply list(String q, String currentUrl) {
        return new Reply(200, HTML, listInner(filter(tools(), q), selectedFromUrl(currentUrl)));
    }

    /** GET /mcp-docs/tools.json */
    Map<String, Object> toolsJson() {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("title", meta.title());
        server.put("version", meta.version());
        server.put("instructions", meta.instructions());
        server.put("transport", meta.transport());
        server.put("endpoint", meta.endpoint());
        server.put("tryItOut", meta.tryItOut());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("server", server);
        body.put("tools", tools());
        return body;
    }

    // ---------- página ----------

    private String fullPage(String q, String selected, String mainHtml) {
        List<Map<String, Object>> all = tools();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("base", meta.base());
        config.put("contextPath", meta.contextPath());
        config.put("endpoint", meta.endpoint());
        config.put("transport", meta.transport());
        config.put("tryItOut", meta.tryItOut());

        Map<String, String> v = new LinkedHashMap<>();
        v.put("TITLE", esc(meta.title()));
        v.put("VERSION", meta.version() == null || meta.version().isBlank() ? "" : "v" + esc(meta.version()));
        v.put("COUNT", all.size() + " tools");
        v.put("ENDPOINT", esc(meta.endpoint()));
        v.put("INSTRUCTIONS", esc(meta.instructions()));
        v.put("BASE", esc(meta.base()));
        v.put("HTMX_SRC", esc(meta.htmxSrc()));
        v.put("ASSET_VERSION", assetVersion);
        v.put("CONFIG", docs.compact(config).replace("<", "\\u003c"));
        v.put("TRY_HIDDEN", meta.tryItOut() ? "" : " hidden");
        v.put("SEARCH_VALUE", esc(q));
        v.put("LIST", listInner(filter(all, q), selected));
        v.put("MAIN", mainHtml);
        v.put("DIALOG", meta.tryItOut() ? dialogHtml : "");
        return fill(pageTemplate, v);
    }

    /** Substitui {{TOKEN}} em uma única passada (o conteúdo inserido não é reprocessado). */
    private static String fill(String template, Map<String, String> values) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String val = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(val == null ? m.group() : val));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---------- lista ----------

    private String listInner(List<Map<String, Object>> tools, String selected) {
        if (tools.isEmpty()) {
            return "<p class=\"muted\">Nenhuma tool encontrada.</p>";
        }
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> t : tools) {
            String name = str(t.get("name"));
            String url = esc(meta.base() + "/tools/" + pathSegment(name));
            boolean active = name.equals(selected);
            b.append("<a class=\"item").append(active ? " active\" aria-current=\"page\"" : "\"")
                    .append(" href=\"").append(url).append("\"")
                    .append(" hx-get=\"").append(url).append("\"")
                    // a rolagem para o topo é feita em mcp-client.js (htmx:after:swap)
                    .append(" hx-target=\"#main\" hx-swap=\"innerHTML\"")
                    .append(" hx-push-url=\"").append(url).append("\"")
                    .append(" hx-include=\"#q\">")
                    .append("<strong>").append(esc(name)).append("</strong>")
                    .append("<span class=\"muted\">").append(esc(firstLine(str(t.get("description"))))).append("</span>")
                    .append("</a>");
        }
        return b.toString();
    }

    private static String firstLine(String s) {
        String line = s.split("\n", 2)[0];
        return line.length() > 90 ? line.substring(0, 90) : line;
    }

    private static List<Map<String, Object>> filter(List<Map<String, Object>> tools, String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (needle.isEmpty()) {
            return tools;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            if (str(t.get("name")).toLowerCase().contains(needle)
                    || str(t.get("title")).toLowerCase().contains(needle)
                    || str(t.get("description")).toLowerCase().contains(needle)) {
                out.add(t);
            }
        }
        return out;
    }

    static String selectedFromUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = TOOL_IN_URL.matcher(url);
        if (!m.find()) {
            return null;
        }
        return URLDecoder.decode(m.group(1).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    // ---------- detalhe da tool ----------

    private String toolHtml(Map<String, Object> t) {
        Map<String, Object> a = SchemaDocs.map(t.get("annotations"));
        String name = str(t.get("name"));
        String title = !str(t.get("title")).isBlank() ? str(t.get("title"))
                : !str(a.get("title")).isBlank() ? str(a.get("title")) : name;
        String desc = str(t.get("description"));
        Map<String, Object> inSchema = SchemaDocs.map(t.get("inputSchema"));

        StringBuilder b = new StringBuilder();
        b.append("<h2>").append(esc(title)).append("</h2>");
        b.append("<p><code>").append(esc(name)).append("</code></p>");
        b.append("<div class=\"badges\">");
        // nomes originais das annotations do MCP; a explicação aparece no tooltip
        if (Boolean.TRUE.equals(a.get("readOnlyHint"))) b.append(badge("readOnlyHint", "ok",
                "Somente leitura: a tool não modifica o ambiente, apenas consulta dados."));
        if (Boolean.TRUE.equals(a.get("destructiveHint"))) b.append(badge("destructiveHint", "bad",
                "Destrutiva: pode apagar ou sobrescrever dados, em vez de apenas acrescentar."));
        if (Boolean.TRUE.equals(a.get("idempotentHint"))) b.append(badge("idempotentHint", "info",
                "Idempotente: repetir a chamada com os mesmos argumentos não tem efeito adicional."));
        if (Boolean.TRUE.equals(a.get("openWorldHint"))) b.append(badge("openWorldHint", "warn",
                "Mundo aberto: interage com sistemas externos (web, APIs de terceiros), não só com dados internos."));
        b.append("</div>");
        b.append("<p class=\"desc\">").append(esc(desc.isBlank() ? "Sem descrição." : desc)).append("</p>");

        b.append("<h3>Parâmetros</h3>").append(paramTable(inSchema, "Sem parâmetros."));
        b.append(outputSection(t));

        Object sampleArgs = docs.sample(inSchema, inSchema);
        Map<String, Object> rpc = new LinkedHashMap<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", sampleArgs);
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", 1);
        rpc.put("method", "tools/call");
        rpc.put("params", params);

        if (meta.tryItOut()) {
            b.append(trySection(name, docs.pretty(sampleArgs)));
        }
        b.append(sectionStart("Exemplo de chamada"))
                .append("<p class=\"muted small\">Corpo JSON-RPC gerado a partir do schema. Fora daqui, o cliente precisa ter feito "
                        + "initialize antes; o curl vale para o transporte streamable HTTP.</p>")
                .append(codeBlock(docs.pretty(rpc), ""))
                .append(codeBlock(fallbackCurl(docs.compact(rpc)), " class=\"curl\" data-body=\"" + esc(docs.compact(rpc)) + "\""))
                .append("</details>");

        b.append("<details><summary>Input schema (JSON)</summary>")
                .append(codeBlock(docs.pretty(t.get("inputSchema") == null ? Map.of() : t.get("inputSchema")), ""))
                .append("</details>");
        if (t.get("outputSchema") != null) {
            b.append("<details><summary>Output schema (JSON)</summary>")
                    .append(codeBlock(docs.pretty(t.get("outputSchema")), ""))
                    .append("</details>");
        }
        return b.toString();
    }

    private String fallbackCurl(String rpcCompact) {
        return "curl -X POST '<URL_DO_SERVIDOR>" + meta.endpoint() + "' \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -H 'Accept: application/json, text/event-stream' \\\n"
                + "  -H 'Mcp-Session-Id: <SESSION_ID>' \\\n"
                + "  -d '" + rpcCompact.replace("'", "'\\''") + "'";
    }

    private String trySection(String name, String sampleJson) {
        int rows = Math.min(14, Math.max(4, sampleJson.split("\n", -1).length + 1));
        return sectionStart("Testar")
                + "<p class=\"muted small\">Chama a tool de verdade no servidor MCP, direto do seu navegador. "
                + "A sessão é inicializada automaticamente se ainda não existir.</p>"
                + "<div class=\"try\" data-tool=\"" + esc(name) + "\">"
                + "<label>Argumentos (JSON)<textarea class=\"args\" rows=\"" + rows + "\" spellcheck=\"false\" data-sample=\""
                + esc(sampleJson) + "\">" + esc(sampleJson) + "</textarea></label>"
                + "<label>Metadata _meta (JSON, opcional)<textarea class=\"meta\" rows=\"2\" spellcheck=\"false\" "
                + "placeholder=\"{&quot;chave&quot;:&quot;valor&quot;}  (opcional)\"></textarea></label>"
                + "<div class=\"row\">"
                + "<button class=\"btn primary\" type=\"button\" data-action=\"run\">Executar</button>"
                + "<button class=\"btn\" type=\"button\" data-action=\"reset\">Restaurar exemplo</button>"
                + "<button class=\"btn\" type=\"button\" data-action=\"conn\">Conexão e autenticação</button>"
                + "</div><div class=\"out\"></div></div>"
                + "</details>";
    }

    /** Seção recolhível: começa fechada e, como o htmx troca o HTML a cada tool, fecha de novo ao trocar de tool. */
    private static String sectionStart(String title) {
        return "<details class=\"section\"><summary><h3>" + esc(title) + "</h3></summary>";
    }

    private String outputSection(Map<String, Object> t) {
        StringBuilder b = new StringBuilder("<h3>Retorno (outputSchema)</h3>");
        Map<String, Object> os = SchemaDocs.map(t.get("outputSchema"));
        if (os.isEmpty()) {
            return b.append("<p class=\"muted\">Esta tool não declara outputSchema: o resultado chega apenas em content "
                    + "(texto livre). No Spring AI, @McpTool(generateOutputSchema = true) publica o schema do tipo de retorno "
                    + "(confira na sua versão).</p>").toString();
        }
        Map<String, Object> s = SchemaDocs.norm(os, os);
        if (s.get("description") != null) {
            b.append("<p class=\"desc\">").append(esc(s.get("description"))).append("</p>");
        }
        b.append(paramTable(os, "O schema não declara propriedades."));
        b.append("<p class=\"muted small\">Exemplo de structuredContent (gerado a partir do schema):</p>")
                .append(codeBlock(docs.pretty(docs.sample(os, os)), ""));
        return b.toString();
    }

    private String paramTable(Map<String, Object> root, String emptyText) {
        Map<String, Object> s = SchemaDocs.norm(root, root);
        String t0 = SchemaDocs.baseType(s);
        List<SchemaDocs.Row> rows = docs.rows(root, root);
        String note = "";
        if (rows.isEmpty() && "array".equals(t0) && s.get("items") != null) {
            rows = docs.rows(s.get("items"), root, "[].");
            if (!rows.isEmpty()) {
                note = "Array de objetos, cada item com os campos:";
            }
        }
        if (rows.isEmpty()) {
            String text = t0 != null && !t0.equals("object") ? "Tipo: " + SchemaDocs.typeLabel(s, root) : emptyText;
            return "<p class=\"muted\">" + esc(text) + "</p>";
        }
        StringBuilder b = new StringBuilder();
        if (!note.isEmpty()) {
            b.append("<div><p class=\"muted small\">").append(esc(note)).append("</p>");
        }
        b.append("<div class=\"tablewrap\"><table><thead><tr><th>Nome</th><th>Tipo</th><th>Obrig.</th><th>Descrição</th></tr></thead><tbody>");
        for (SchemaDocs.Row r : rows) {
            b.append("<tr><td><code>").append(esc(r.path())).append("</code></td>")
                    .append("<td>").append(esc(r.type())).append("</td>")
                    .append("<td>").append(r.required() ? "sim" : "não").append("</td>")
                    .append("<td>").append(esc(r.desc()));
            for (String e : r.extra()) {
                b.append("<div class=\"muted small\">").append(esc(e)).append("</div>");
            }
            b.append("</td></tr>");
        }
        b.append("</tbody></table></div>");
        if (!note.isEmpty()) {
            b.append("</div>");
        }
        return b.toString();
    }

    /** Badge com tooltip (CSS, via data-tip): aparece no hover e no foco pelo teclado. */
    private static String badge(String text, String kind, String tip) {
        return "<span class=\"badge " + kind + "\" tabindex=\"0\" data-tip=\"" + esc(tip) + "\""
                + " aria-label=\"" + esc(text + ": " + tip) + "\">" + esc(text) + "</span>";
    }

    private static String codeBlock(String text, String preAttrs) {
        return "<div class=\"code\"><button class=\"copy\" type=\"button\" data-action=\"copy\">Copiar</button>"
                + "<pre" + preAttrs + ">" + esc(text) + "</pre></div>";
    }

    // ---------- utilidades ----------

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> list = new ArrayList<>(toolSource.get());
        list.sort(Comparator.comparing(t -> str(t.get("name"))));
        return list;
    }

    private static Map<String, Object> find(List<Map<String, Object>> tools, String name) {
        for (Map<String, Object> t : tools) {
            if (str(t.get("name")).equals(name)) {
                return t;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static String pathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String esc(Object o) {
        if (o == null) {
            return "";
        }
        String s = String.valueOf(o);
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String resource(String path) {
        try (InputStream in = McpDocsPages.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Recurso não encontrado no classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
