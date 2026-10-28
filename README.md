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
- Transporte `STREAMABLE` ou `SSE` (o `STDIO` não tem HTTP, então não há onde servir a página)

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

Não é preciso nenhuma anotação nem configuração adicional: o starter se registra via
auto-configuração do Spring Boot (`McpDocsAutoConfiguration`).

## 3. Acessar a documentação

Suba a aplicação normalmente e abra:

```
http://localhost:8080/mcp-docs
```

Todas as tools registradas no servidor MCP aparecem automaticamente — tanto as declaradas com
`@McpTool` quanto as registradas como `SyncToolSpecification`/`AsyncToolSpecification`. Por exemplo:

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
| `mcp.docs.enabled`   | `true`                                              | Liga/desliga todo o starter (rotas e assets).                             |
| `mcp.docs.try-it-out`| `true`                                              | Exibe o painel "Testar", que executa as tools pelo navegador.             |
| `mcp.docs.title`     | valor de `spring.ai.mcp.server.name`                | Título exibido na página.                                                 |
| `mcp.docs.transport` | valor de `spring.ai.mcp.server.protocol`            | `streamable` ou `sse`: protocolo usado pelo painel "Testar".              |
| `mcp.docs.endpoint`  | endpoint do transporte configurado no Spring AI     | URL do endpoint MCP (útil atrás de proxy reverso ou gateway).             |
| `mcp.docs.htmx-src`  | `https://unpkg.com/htmx.org@4.0.0/dist/htmx.min.js` | URL do script do htmx (troque para servir de um CDN interno ou local).     |

O `server.servlet.context-path` (WebMVC) e o `spring.webflux.base-path` (WebFlux) são respeitados
automaticamente.

### Exemplos

Desligar a documentação em produção (perfil `prod`):

```properties
# application-prod.properties
mcp.docs.enabled=false
```

Manter a documentação em produção, mas sem permitir executar tools:

```properties
mcp.docs.try-it-out=false
```

Servidor atrás de um gateway que publica o MCP em outra URL:

```properties
mcp.docs.endpoint=https://api.exemplo.com.br/pedidos/mcp
```

Ambientes sem acesso à internet (o htmx é carregado de CDN por padrão):

```properties
mcp.docs.htmx-src=/js/htmx.min.js
```

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

- **`/mcp-docs` retorna 404** — confira se a aplicação é web (WebMVC ou WebFlux), se
  `mcp.docs.enabled` não está `false` e se o starter de servidor MCP do Spring AI está no classpath.
  Com `debug=true`, o relatório de auto-configuração mostra o motivo em `McpDocsAutoConfiguration`.
- **A página aparece sem estilo** — verifique se `/mcp-docs/assets/mcp-docs.css` responde 200; um
  filtro de segurança bloqueando `/mcp-docs/**` é a causa mais comum.
- **Nenhuma tool listada** — a página lista as tools expostas como beans `@McpTool` ou
  `SyncToolSpecification`/`AsyncToolSpecification`. Confira o resultado de `GET /mcp-docs/tools.json`.
- **"Testar" não conecta** — confira `mcp.docs.transport` e `mcp.docs.endpoint`. Se o endpoint estiver
  em outra origem, ele precisa aceitar CORS da origem da página.

## Desenvolvimento do starter

```bash
./mvnw test                    # testes
./mvnw spring-boot:test-run    # sobe a aplicação de exemplo (src/test) em http://localhost:8080/mcp-docs
./mvnw clean install           # instala o starter no repositório Maven local
```

A aplicação de exemplo e suas tools ficam em `src/test/java/io/github/tonyblack10/mcpdoc/example`,
fora do jar publicado.
