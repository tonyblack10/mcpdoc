package io.github.tonyblack10.mcpdoc;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lógica de JSON Schema usada pela documentação: resolve $ref/anyOf/allOf, lista campos
 * (com aninhamento) e gera valores de exemplo. Não depende do Spring.
 */
final class SchemaDocs {

    record Row(String path, String type, boolean required, String desc, List<String> extra) {}

    private final ObjectMapper mapper;
    private final ObjectWriter pretty;

    SchemaDocs(ObjectMapper mapper) {
        this.mapper = mapper;
        this.pretty = mapper.writer().with(new JsPrinter());
    }

    // ---------- JSON ----------

    /** Equivalente a JSON.stringify(value). */
    String compact(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    /** Equivalente a JSON.stringify(value, null, 2). */
    String pretty(Object value) {
        try {
            return pretty.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    /** Pretty printer no estilo do JavaScript: "chave": valor, {} e [] vazios sem espaço. */
    private static final class JsPrinter extends DefaultPrettyPrinter {
        JsPrinter() {
            DefaultIndenter indent = new DefaultIndenter("  ", "\n");
            indentObjectsWith(indent);
            indentArraysWith(indent);
        }

        JsPrinter(JsPrinter base) {
            super(base);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new JsPrinter(this);
        }

        @Override
        public void writeObjectNameValueSeparator(JsonGenerator g) {
            g.writeRaw(": ");
        }

        @Override
        public void writeEndObject(JsonGenerator g, int nrOfEntries) {
            if (!_objectIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfEntries > 0) {
                _objectIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw('}');
        }

        @Override
        public void writeEndArray(JsonGenerator g, int nrOfValues) {
            if (!_arrayIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfValues > 0) {
                _arrayIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw(']');
        }
    }

    // ---------- helpers de tipo ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    // ---------- normalização ----------

    /** Resolve $ref, escolhe a primeira alternativa de oneOf/anyOf e mescla allOf. */
    static Map<String, Object> norm(Object schema, Map<String, Object> root) {
        return norm(schema, root, 0);
    }

    private static Map<String, Object> norm(Object schema, Map<String, Object> root, int depth) {
        Map<String, Object> s = map(schema);
        if (depth > 12) {
            return s;
        }
        if (s.get("$ref") instanceof String ref) {
            Object t = root;
            for (String part : ref.replaceFirst("^#/?", "").split("/")) {
                if (part.isEmpty()) {
                    continue;
                }
                t = t instanceof Map<?, ?> m ? m.get(part.replace("~1", "/").replace("~0", "~")) : null;
            }
            Map<String, Object> merged = new LinkedHashMap<>(map(t));
            s.forEach((k, v) -> {
                if (!k.equals("$ref")) {
                    merged.put(k, v);
                }
            });
            return norm(merged, root, depth + 1);
        }
        List<Object> alt = !list(s.get("oneOf")).isEmpty() ? list(s.get("oneOf")) : list(s.get("anyOf"));
        if (!alt.isEmpty()) {
            Map<String, Object> rest = new LinkedHashMap<>(s);
            rest.remove("oneOf");
            rest.remove("anyOf");
            Object pick = alt.stream().filter(x -> !"null".equals(map(x).get("type"))).findFirst().orElse(alt.get(0));
            rest.putAll(norm(pick, root, depth + 1));
            return rest;
        }
        List<Object> all = list(s.get("allOf"));
        if (!all.isEmpty()) {
            Map<String, Object> merged = new LinkedHashMap<>(s);
            merged.remove("allOf");
            Map<String, Object> props = new LinkedHashMap<>(map(merged.get("properties")));
            List<Object> required = new ArrayList<>(list(merged.get("required")));
            for (Object part : all) {
                Map<String, Object> n = norm(part, root, depth + 1);
                props.putAll(map(n.get("properties")));
                required.addAll(list(n.get("required")));
                if (merged.get("type") == null && n.get("type") != null) {
                    merged.put("type", n.get("type"));
                }
                if (merged.get("description") == null && n.get("description") != null) {
                    merged.put("description", n.get("description"));
                }
            }
            merged.put("properties", props);
            merged.put("required", required);
            return merged;
        }
        return s;
    }

    /** Tipo principal: ignora "null" em tipos múltiplos e infere object/array quando falta "type". */
    static String baseType(Map<String, Object> s) {
        Object t = s.get("type");
        if (t instanceof List<?> l) {
            t = l.stream().filter(x -> !"null".equals(x)).findFirst().orElse(null);
        }
        if (t == null) {
            return s.get("properties") != null ? "object" : s.get("items") != null ? "array" : null;
        }
        return String.valueOf(t);
    }

    static String typeLabel(Object schema, Map<String, Object> root) {
        return typeLabel(schema, root, 0);
    }

    private static String typeLabel(Object schema, Map<String, Object> root, int depth) {
        Map<String, Object> s = norm(schema, root);
        String t = baseType(s);
        if ("array".equals(t)) {
            if (depth > 5) {
                return "array<…>";
            }
            return "array<" + (s.get("items") != null ? typeLabel(s.get("items"), root, depth + 1) : "any") + ">";
        }
        if (s.get("enum") != null && t == null) {
            return "enum";
        }
        return t != null ? t : "any";
    }

    // ---------- tabela de campos ----------

    List<String> extras(Map<String, Object> p) {
        List<String> out = new ArrayList<>();
        if (p.get("enum") instanceof List<?> e) {
            out.add("valores: " + String.join(" | ", e.stream().map(String::valueOf).toList()));
        }
        if (p.containsKey("default")) {
            out.add("padrão: " + compact(p.get("default")));
        }
        if (p.get("format") != null) {
            out.add("formato: " + p.get("format"));
        }
        if (p.get("minimum") != null) {
            out.add("mín: " + p.get("minimum"));
        }
        if (p.get("maximum") != null) {
            out.add("máx: " + p.get("maximum"));
        }
        if (p.get("minLength") != null) {
            out.add("tamanho mín: " + p.get("minLength"));
        }
        if (p.get("maxLength") != null) {
            out.add("tamanho máx: " + p.get("maxLength"));
        }
        if (p.get("pattern") != null) {
            out.add("padrão regex: " + p.get("pattern"));
        }
        if (p.containsKey("example")) {
            out.add("exemplo: " + compact(p.get("example")));
        }
        return out;
    }

    List<Row> rows(Object schema, Map<String, Object> root) {
        List<Row> out = new ArrayList<>();
        rows(schema, root, "", out, 0);
        return out;
    }

    List<Row> rows(Object schema, Map<String, Object> root, String prefix) {
        List<Row> out = new ArrayList<>();
        rows(schema, root, prefix, out, 0);
        return out;
    }

    private void rows(Object schema, Map<String, Object> root, String prefix, List<Row> out, int depth) {
        Map<String, Object> s = norm(schema, root);
        List<Object> req = list(s.get("required"));
        for (Map.Entry<String, Object> e : map(s.get("properties")).entrySet()) {
            Map<String, Object> p = norm(e.getValue(), root);
            String path = prefix + e.getKey();
            out.add(new Row(path, typeLabel(p, root), req.contains(e.getKey()),
                    p.get("description") == null ? "" : String.valueOf(p.get("description")), extras(p)));
            if (depth < 4) {
                String t = baseType(p);
                if ("object".equals(t) || p.get("properties") != null) {
                    rows(p, root, path + ".", out, depth + 1);
                } else if ("array".equals(t) && p.get("items") != null) {
                    Map<String, Object> it = norm(p.get("items"), root);
                    if ("object".equals(baseType(it)) || it.get("properties") != null) {
                        rows(it, root, path + "[].", out, depth + 1);
                    }
                }
            }
        }
    }

    // ---------- exemplo ----------

    Object sample(Object schema, Map<String, Object> root) {
        return sample(schema, root, 0);
    }

    private Object sample(Object schema, Map<String, Object> root, int depth) {
        Map<String, Object> s = norm(schema, root);
        if (s.containsKey("example")) {
            return s.get("example");
        }
        if (list(s.get("examples")).size() > 0) {
            return list(s.get("examples")).get(0);
        }
        if (s.containsKey("default")) {
            return s.get("default");
        }
        if (s.containsKey("const")) {
            return s.get("const");
        }
        if (!list(s.get("enum")).isEmpty()) {
            return list(s.get("enum")).get(0);
        }
        String t = baseType(s);
        if (t == null) {
            t = "string";
        }
        if (depth > 4) {
            return "array".equals(t) ? new ArrayList<>() : "object".equals(t) ? new LinkedHashMap<>() : null;
        }
        switch (t) {
            case "object": {
                Map<String, Object> o = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : map(s.get("properties")).entrySet()) {
                    o.put(e.getKey(), sample(e.getValue(), root, depth + 1));
                }
                return o;
            }
            case "array": {
                List<Object> a = new ArrayList<>();
                if (s.get("items") != null) {
                    a.add(sample(s.get("items"), root, depth + 1));
                }
                return a;
            }
            case "integer":
            case "number":
                return s.get("minimum") != null ? s.get("minimum") : (Object) 0;
            case "boolean":
                return false;
            default:
                if ("date-time".equals(s.get("format"))) {
                    return Instant.now().toString();
                }
                if ("date".equals(s.get("format"))) {
                    return LocalDate.now(ZoneOffset.UTC).toString();
                }
                return "string";
        }
    }
}
