package io.github.tonyblack10.mcpdoc.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tools MCP de exemplo, para ver a documentação em /mcp-docs funcionando.
 */
@Component
public class ExampleTools {

    enum Priority { LOW, MEDIUM, HIGH }

    record Assignee(
            @JsonPropertyDescription("Nome do responsável") String name,
            @JsonPropertyDescription("E-mail do responsável") String email) {}

    record Task(
            @JsonPropertyDescription("Título curto da tarefa") String title,
            @JsonPropertyDescription("Descrição detalhada da tarefa") @Nullable String description,
            @JsonPropertyDescription("Prioridade da tarefa") Priority priority,
            @JsonProperty(value = "due_date", required = true) @JsonPropertyDescription("Data limite para conclusão (ISO-8601, ex.: 2026-12-31)") LocalDate dueDate,
            @JsonPropertyDescription("Etiquetas para classificar a tarefa") List<String> tags,
            @JsonPropertyDescription("Pessoa responsável pela tarefa") @Nullable Assignee assignee) {}

    record CreatedTask(
            @JsonProperty(value = "task_id", required = true) @JsonPropertyDescription("ID da tarefa criada (\"dry-run\" quando dryRun=true)") String id,
            @JsonPropertyDescription("Dados da tarefa, como foram recebidos") Task task) {}

    record SumResult(@JsonPropertyDescription("Resultado da soma (a + b)") double result) {}

    @McpTool(name = "sum", description = "Soma dois números.", generateOutputSchema = true)
    public SumResult sum(
            @McpToolParam(description = "Primeiro número") double a,
            @McpToolParam(description = "Segundo número") double b) {
        return new SumResult(a + b);
    }

    @McpTool(name = "create_task", description = "Cria uma tarefa e devolve o id gerado.", generateOutputSchema = true)
    public CreatedTask createTask(
            @McpToolParam(description = "Dados da tarefa") Task task,
            @McpToolParam(description = "Se true, apenas valida sem criar", required = false) Boolean dryRun) {
        String id = Boolean.TRUE.equals(dryRun) ? "dry-run" : UUID.randomUUID().toString();
        return new CreatedTask(id, task);
    }
}
