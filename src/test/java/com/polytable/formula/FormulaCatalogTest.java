package com.polytable.formula;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void catalogEntriesHaveOfficialSourcesAndUniqueNames() throws Exception {
        JsonNode catalog = mapper.readTree(Files.newInputStream(
                Path.of("src/main/resources/formula/bitable-formula-catalog.json")));
        assertTrue(catalog.path("asOf").isTextual());
        assertTrue(catalog.path("functions").isArray());
        assertTrue(catalog.path("operators").isArray());

        Set<String> sourceIds = new HashSet<>();
        for (JsonNode source : catalog.path("sources")) {
            String id = source.path("id").asText();
            String url = source.path("url").asText();
            assertFalse(id.isBlank());
            assertTrue(url.startsWith("https://www.feishu.cn/") || url.startsWith("https://www.larksuite.com/"));
            sourceIds.add(id);
        }
        assertFalse(sourceIds.isEmpty());

        Set<String> functionNames = new HashSet<>();
        for (JsonNode function : catalog.path("functions")) {
            String name = function.path("name").asText();
            assertFalse(name.isBlank());
            assertTrue(functionNames.add(name.toUpperCase()), "duplicate function: " + name);
            assertTrue(function.path("signatures").isArray());
            assertTrue(function.path("sourceIds").isArray());
            assertFalse(function.path("sourceIds").isEmpty());
            for (JsonNode sourceId : function.path("sourceIds")) {
                assertTrue(sourceIds.contains(sourceId.asText()), "unknown source id for " + name);
            }
        }
        assertTrue(functionNames.size() >= 119);
        for (JsonNode operator : catalog.path("operators")) {
            assertFalse(operator.path("symbol").asText().isBlank());
            assertFalse(operator.path("sourceIds").isEmpty());
            for (JsonNode sourceId : operator.path("sourceIds")) {
                assertTrue(sourceIds.contains(sourceId.asText()), "unknown source id for operator " + operator.path("symbol").asText());
            }
        }
    }

    @Test
    void goldenCasesHaveAnExpectedValueOrError() throws Exception {
        JsonNode cases = read("/formula/bitable-formula-golden.json");
        assertTrue(cases.isArray());
        assertFalse(cases.isEmpty());
        for (JsonNode testCase : cases) {
            assertFalse(testCase.path("id").asText().isBlank());
            assertFalse(testCase.path("expression").asText().isBlank());
            assertTrue(testCase.path("fields").isObject());
            assertTrue(testCase.has("expectedValue") || testCase.has("expectedError"));
            assertFalse(testCase.path("sourceIds").isEmpty());
        }
    }

    private JsonNode read(String resource) throws Exception {
        InputStream stream = getClass().getResourceAsStream(resource);
        assertNotNull(stream, "missing test resource " + resource);
        try (stream) {
            return mapper.readTree(stream);
        }
    }
}
