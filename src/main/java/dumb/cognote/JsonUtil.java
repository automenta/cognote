package dumb.cognote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static dumb.cognote.Log.error;

public class JsonUtil {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule()) // Add support for Java 8 Date/Time types
            .enable(SerializationFeature.INDENT_OUTPUT) // Pretty print JSON
            .build();

    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    public static String toJsonString(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            error("Error serializing object to JSON: " + e.getMessage());
            e.printStackTrace();
            return "{}"; // Return empty JSON object on error
        }
    }

    public static JsonNode toJsonNode(Object obj) {
        try {
            return MAPPER.valueToTree(obj);
        } catch (IllegalArgumentException e) {
            error("Error converting object to JsonNode: " + e.getMessage());
            e.printStackTrace();
            return MAPPER.createObjectNode(); // Return empty object node on error
        }
    }

    public static <T> T fromJsonString(String json, Class<T> valueType) throws JsonProcessingException {
        return MAPPER.readValue(json, valueType);
    }

    public static <T> T fromJsonNode(JsonNode json, Class<T> valueType) throws JsonProcessingException {
        return MAPPER.treeToValue(json, valueType);
    }
}
