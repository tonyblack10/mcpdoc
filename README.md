# mcpdoc-spring-boot-starter

Starter Spring Boot que adiciona uma **documentação interativa das tools MCP** a qualquer aplicação
Spring AI que expõe um servidor MCP — algo como um "Swagger UI" para MCP.

Depois de adicionar a dependência, a aplicação passa a servir:

| Rota                         | Conteúdo                                                              |
|------------------------------|-----------------------------------------------------------------------|
| `GET /mcp-docs`              | Página com a lista de tools, schemas de entrada/saída e exemplos      |
| `GET /mcp-docs/tools/{name}` | Detalhe de uma tool                                                   |
| `GET /mcp-docs/tools.json`   | Definição das tools em JSON (o mesmo conteúdo do `tools/list`)         |
| `GET /mcp-docs/assets/**`    | CSS e JS da página                                                    |

A página também traz um painel **"Testar"**, que chama as tools de verdade direto do navegador
(com suporte a bearer token e headers customizados). O starter não executa tools nem faz proxy:
as requisições vão do navegador para o endpoint MCP da própria aplicação.

## Requisitos

- Java 25+
- Spring Boot 4.1.x
- Spring AI 2.0.x com um starter de servidor MCP:
  - `spring-ai-starter-mcp-server-webmvc` **ou**
  - `spring-ai-starter-mcp-server-webflux`
- Transporte `STREAMABLE`, `STATELESS` ou `SSE`. Com `STDIO` (`spring.ai.mcp.server.stdio=true`) não há
  HTTP, então a documentação fica desligada; o mesmo vale para `spring.ai.mcp.server.enabled=false`.

## 1. Instalar o starter

O artefato ainda não está publicado em um repositório remoto. Instale-o no repositório Maven local
a partir deste projeto:

```bash
git clone <url-deste-repositório> mcpdoc
cd mcpdoc
./mvnw clean install
```

Isso publica `io.github.tonyblack10:mcpdoc-spring-boot-starter:0.0.1-SNAPSHOT` em `~/.m2/repository`.

> Para uso em equipe ou em CI, publique o artefato no repositório interno da empresa
> (Nexus, Artifactory, GitHub Packages etc.) com `./mvnw deploy`, configurando o
> `<distributionManagement>` no `pom.xml`.

## 2. Adicionar a dependência no seu projeto

### Maven

```xml
<dependency>
    <groupId>io.github.tonyblack10</groupId>
    <artifactId>mcpdoc-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenLocal() // necessário enquanto o starter só estiver instalado localmente
    mavenCentral()
}

dependencies {
    implementation("io.github.tonyblack10:mcpdoc-spring-boot-starter:0.0.1-SNAPSHOT")
}
```

Exemplo de `pom.xml` mínimo de um servidor MCP já com o starter:

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.tonyblack10</groupId>
        <artifactId>mcpdoc-spring-boot-starter</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>2.0.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

O starter se registra via auto-configuração do Spring Boot (`McpDocsAutoConfiguration`), mas vem
**desligado por padrão**: adicionar a dependência não expõe nada até você ligá-lo.

## 3. Ligar e acessar a documentação

Ligue a documentação no `application.properties` (ou só nos perfis em que ela deve aparecer):

```properties
mcp.docs.enabled=true
```

Suba a aplicação e abra:

```
http://localhost:8080/mcp-docs
```

A página mostra exatamente as tools que o servidor MCP devolve em `tools/list`: as declaradas com
`@McpTool`, as registradas como `SyncToolSpecification`/`AsyncToolSpecification` ou `ToolCallbackProvider`
e até as adicionadas em runtime (`McpSyncServer.addTool(...)`). Por exemplo:

```java
@Component
public class CalculadoraTools {

    record Resultado(@JsonPropertyDescription("Resultado da soma") double valor) {}

    @McpTool(name = "sum", description = "Soma dois números.", generateOutputSchema = true)
    public Resultado sum(@McpToolParam(description = "Primeiro número") double a,
                         @McpToolParam(description = "Segundo número") double b) {
        return new Resultado(a + b);
    }
}
```

As descrições de `@McpTool`, `@McpToolParam` e `@JsonPropertyDescription` viram a documentação
dos parâmetros e do retorno na página. Use `generateOutputSchema = true` para documentar também o
formato da resposta.

### Propriedades obrigatórias e opcionais

A coluna "Obrig." reflete o `required` do schema publicado pelo servidor. No Spring AI, **toda
propriedade de um tool MCP é obrigatória por padrão**. Para torná-la opcional, use uma destas anotações
no campo:

| Anotação | Resultado |
|---|---|
| `@Nullable` (JSpecify ou Spring) | opcional |
| `@JsonProperty` (o `required` padrão é `false`) | opcional |
| `@JsonProperty(required = true)` | obrigatória |
| `@Schema(requiredMode = NOT_REQUIRED)` | opcional |
| `@McpToolParam(required = false)` | opcional |
| nenhuma | obrigatória |

```java
record Tarefa(
        @JsonPropertyDescription("Título") String titulo,                        // obrigatória
        @JsonPropertyDescription("Descrição") @Nullable String descricao,        // opcional
        @JsonProperty(value = "due_date", required = true) LocalDate prazo,     // obrigatória, renomeada
        @JsonProperty("cor") String cor) {}                                      // opcional, renomeada
```

Isso vale tanto para os parâmetros quanto para o retorno (`outputSchema`), e é o mesmo schema que
os clientes MCP recebem.

## 4. Configuração

O cabeçalho da página (nome, versão e instruções) e o endpoint MCP vêm das propriedades que você
já usa no Spring AI:

```properties
spring.ai.mcp.server.name=meu-servidor-mcp
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.instructions=Servidor com as tools do time de pedidos.
spring.ai.mcp.server.protocol=STREAMABLE
```

Dica: no Maven, use `@project.artifactId@` e `@project.version@` para preencher nome e versão a
partir do `pom.xml`.

Todas as propriedades do starter são opcionais:

| Propriedade          | Padrão                                              | Descrição                                                                 |
|----------------------|-----------------------------------------------------|---------------------------------------------------------------------------|
| `mcp.docs.enabled`   | `false`                                             | Liga o starter (rotas e assets). Precisa ser `true` para a página existir. |
| `mcp.docs.try-it-out`| `true`                                              | Exibe o painel "Testar", que executa as tools pelo navegador.             |
| `mcp.docs.title`     | valor de `spring.ai.mcp.server.name`                | Título exibido na página.                                                 |
| `mcp.docs.transport` | valor de `spring.ai.mcp.server.protocol`            | `streamable` ou `sse`: protocolo usado pelo painel "Testar".              |
| `mcp.docs.endpoint`  | endpoint do transporte configurado no Spring AI     | Caminho ou URL do endpoint MCP. Um caminho recebe o caminho público da aplicação. |
| `mcp.docs.base-path` | context path da requisição                          | Caminho público completo da aplicação (substitui o context path), para gateways que não enviam `X-Forwarded-Prefix`. |
| `mcp.docs.htmx-src`  | `https://unpkg.com/htmx.org@4.0.0/dist/htmx.min.js` | URL do script do htmx (troque para servir de um CDN interno ou local).     |

O `server.servlet.context-path` (WebMVC) e o `spring.webflux.base-path` (WebFlux) são respeitados
automaticamente. Atrás de um API gateway, veja [Atrás de um API gateway](#atrás-de-um-api-gateway).

### Exemplos

Ligar a documentação só em desenvolvimento (perfil `dev`), mantendo-a fora de produção:

```properties
# application-dev.properties
mcp.docs.enabled=true
```

Manter a documentação em produção, mas sem permitir executar tools:

```properties
mcp.docs.try-it-out=false
```

Endpoint MCP publicado em outro host (o painel "Testar" chama essa URL direto, então ela precisa aceitar
CORS da origem da página):

```properties
mcp.docs.endpoint=https://mcp.exemplo.com.br/pedidos/mcp
```

Ambientes sem acesso à internet (o htmx é carregado de CDN por padrão):

```properties
mcp.docs.htmx-src=/js/htmx.min.js
```

### Atrás de um API gateway

Quando o gateway publica a aplicação com um prefixo e o remove antes de repassar
(`https://api.exemplo.com.br/pedidos/mcp-docs` chega ao backend como `/mcp-docs`), a página precisa
saber o prefixo para montar os links, os assets e o endpoint do painel "Testar". Ele é resolvido a
cada requisição, de uma destas formas:

1. **Header `X-Forwarded-Prefix`** (recomendado): se o gateway envia o header, ligue o suporte do
   Spring a headers de proxy:

   ```properties
   server.forward-headers-strategy=framework
   ```

   Só ligue isso se o gateway sobrescreve os headers `X-Forwarded-*` vindos do cliente. Sem essa
   propriedade, o starter ignora o header.

2. **Propriedade fixa**, quando o gateway não envia o header:

   ```properties
   mcp.docs.base-path=/pedidos
   ```

Com o transporte SSE legado, configure também `spring.ai.mcp.server.base-url=/pedidos`. Assim a URL de
mensagens que o servidor envia aos clientes MCP inclui o prefixo. O painel "Testar" corrige isso sozinho,
mas os outros clientes não.

O gateway também precisa:

- **Não fazer buffering** de respostas `text/event-stream` e ter timeout compatível com as chamadas
  mais longas. O Streamable HTTP pode responder em stream, e o SSE mantém a conexão aberta.
- **Repassar os headers** `Mcp-Session-Id`, `MCP-Protocol-Version`, `Authorization`, `HX-Target`
  e `HX-Current-URL`, e devolver `Mcp-Session-Id` na resposta.
- **Usar sticky session** com STREAMABLE ou SSE e mais de uma instância: a sessão MCP fica na memória
  da instância que a criou. Com `spring.ai.mcp.server.protocol=STATELESS` isso não é necessário.

## 5. Spring Security

Se a aplicação usa Spring Security, libere as rotas da documentação (ou proteja-as como preferir):

```java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/mcp-docs/**").permitAll()
            .anyRequest().authenticated());
    // ...
    return http.build();
}
```

O endpoint MCP continua protegido: no painel "Testar", use o botão **Conexão e autenticação** para
informar o bearer token ou headers necessários. Esses dados ficam apenas no navegador.

Se houver uma Content Security Policy, permita o script do htmx (`unpkg.com`, ou a URL definida em
`mcp.docs.htmx-src`).

## 6. Solução de problemas

- **`/mcp-docs` retorna 404** — confira se `mcp.docs.enabled=true` está definido (o padrão é `false`),
  se a aplicação é web (WebMVC ou WebFlux) e se o starter de servidor MCP do Spring AI está no classpath.
  Com `debug=true`, o relatório de auto-configuração mostra o motivo em `McpDocsAutoConfiguration`.
- **A página aparece sem estilo** — verifique se `/mcp-docs/assets/mcp-docs.css` responde 200; um
  filtro de segurança bloqueando `/mcp-docs/**` é a causa mais comum.
- **Nenhuma tool listada** — a página mostra o mesmo que o `tools/list` do servidor; se ele não lista
  a tool, ela não foi registrada. Com `spring.ai.mcp.server.type=ASYNC`, o Spring AI só registra métodos
  `@McpTool` que retornam `Mono`/`Flux` (os demais são ignorados, com um aviso no log).
- **"Testar" não conecta** — confira `mcp.docs.transport` e `mcp.docs.endpoint`. Se o endpoint estiver
  em outra origem, ele precisa aceitar CORS da origem da página.
- **Atrás do gateway, a página fica sem estilo ou os links dão 404** — o prefixo do gateway não chegou à
  aplicação. Veja [Atrás de um API gateway](#atrás-de-um-api-gateway).
- **"Testar" falha com "sessão expirada" de forma intermitente** — com várias instâncias, a requisição
  caiu numa instância diferente da que criou a sessão. Use sticky session ou o protocolo STATELESS.

## Desenvolvimento do starter

```bash
./mvnw test                    # testes
./mvnw spring-boot:test-run    # sobe a aplicação de exemplo (src/test) em http://localhost:8080/mcp-docs
./mvnw clean install           # instala o starter no repositório Maven local
```

A aplicação de exemplo e suas tools ficam em `src/test/java/io/github/tonyblack10/mcpdoc/example`,
fora do jar publicado.
