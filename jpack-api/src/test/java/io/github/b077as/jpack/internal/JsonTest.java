package io.github.b077as.jpack.internal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void anObjectIsReadWithItsTypesAndItsOrder() {
        Map<String, Object> value = Json.parseObject(
                " {\"b\": 1, \"a\": -2.5, \"text\": \"x\\n\\\"y\\\" \\u00e9\", \"yes\": true, \"no\": false, \"none\": null, \"list\": [1, [2], {}], \"big\": 5000000000} ");

        assertEquals(List.of("b", "a", "text", "yes", "no", "none", "list", "big"), List.copyOf(value.keySet()));
        assertEquals(1L, value.get("b"));
        assertEquals(-2.5, value.get("a"));
        assertEquals("x\n\"y\" \u00e9", value.get("text"));
        assertEquals(true, value.get("yes"));
        assertEquals(false, value.get("no"));
        assertNull(value.get("none"));
        assertEquals(List.of(1L, List.of(2L), Map.of()), value.get("list"));
        assertEquals(5_000_000_000L, value.get("big"));
    }

    @Test
    void whatIsWrittenReadsBackTheSame() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "Caf\u00e9 \"quoted\" \\ back\tslash\u0001");
        value.put("size", 12345678901L);
        value.put("ratio", 0.5);
        value.put("flag", true);
        value.put("nothing", null);
        value.put("items", List.of("a", 2, List.of(), Map.of("k", "v")));
        value.put("empty", Map.of());

        Map<String, Object> expected = new LinkedHashMap<>(value);
        expected.put("items", List.of("a", 2L, List.of(), Map.of("k", "v")));

        assertEquals(expected, Json.parse(Json.write(value)));
        assertEquals(expected, Json.parse(Json.writePretty(value)));
        assertTrue(Json.write(value).indexOf('\n') < 0, "one line, as the stub's channel needs");
    }

    @Test
    void theWrittenFileIsIndentedForPeople() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a", 1);
        value.put("b", List.of("x"));
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    \"x\"\n  ]\n}\n", Json.writePretty(value));
    }

    @Test
    void textThatIsNotJsonIsRefused() {
        for (String bad : List.of("", "{", "{\"a\"}", "{\"a\":1,}", "[1,]", "\"open", "tru", "{\"a\":1} x", "{a:1}", "\"\\q\"", "\"\\u12\"", "1.2.3", "-")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
    }

    @Test
    void textNestedAbsurdlyDeepIsRefusedAndDoesNotOverflowTheStack() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100_000)));
    }
}
