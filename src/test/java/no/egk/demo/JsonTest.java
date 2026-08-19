package no.egk.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the deliberately tiny, flat JSON writer. */
class JsonTest {

    @Test
    @DisplayName("an empty map serializes to an empty object")
    void emptyMap() {
        assertEquals("{}", Json.object(Map.of()));
    }

    @Test
    @DisplayName("string values are quoted")
    void stringValue() {
        assertEquals("{\"greeting\":\"hello\"}", Json.object(Map.of("greeting", "hello")));
    }

    @Test
    @DisplayName("numbers and booleans are written unquoted")
    void numberAndBooleanValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", 3);
        values.put("ratio", 1.5);
        values.put("active", true);
        assertEquals("{\"count\":3,\"ratio\":1.5,\"active\":true}", Json.object(values));
    }

    @Test
    @DisplayName("null values are written as the JSON null literal")
    void nullValue() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("value", null);
        assertEquals("{\"value\":null}", Json.object(values));
    }

    @Test
    @DisplayName("keys and values keep insertion order")
    void preservesInsertionOrder() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("b", 1);
        values.put("a", 2);
        assertEquals("{\"b\":1,\"a\":2}", Json.object(values));
    }

    @Test
    @DisplayName("quotes, backslashes and whitespace escapes are applied")
    void escapesSpecialCharacters() {
        String raw = "quote\" backslash\\ newline\n tab\t";
        String expected = "{\"text\":\"quote\\\" backslash\\\\ newline\\n tab\\t\"}";
        assertEquals(expected, Json.object(Map.of("text", raw)));
    }

    @Test
    @DisplayName("other control characters are escaped as \\u sequences")
    void escapesControlCharacterAsUnicodeSequence() {
        char bell = (char) 7; // BEL - built at runtime to avoid a literal escape in source
        String raw = "bell" + bell + "end";
        String backslash = String.valueOf('\\');
        String expectedEscape = backslash + "u0007";
        assertEquals("{\"text\":\"bell" + expectedEscape + "end\"}", Json.object(Map.of("text", raw)));
    }

    @Test
    @DisplayName("non-primitive values are stringified rather than nested")
    void nonPrimitiveValuesAreStringified() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("list", List.of(1, 2));
        assertEquals("{\"list\":\"[1, 2]\"}", Json.object(values));
    }
}
