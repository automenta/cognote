package dumb.cognote.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static dumb.cognote.util.Log.error;

public class Json {

    public static final ObjectMapper the = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    public static String str(Object obj) {
        try {
            return the.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            error("Error serializing object to JSON: " + e.getMessage());
            e.printStackTrace();
            return "{}";
        }
    }

    public static JsonNode node(Object obj) {
        try {
            return the.valueToTree(obj);
        } catch (IllegalArgumentException e) {
            error("Error converting object to JsonNode: " + e.getMessage());
            e.printStackTrace();
            return the.createObjectNode();
        }
    }

    public static <T> T obj(String json, Class<T> valueType) throws JsonProcessingException {
        return the.readValue(json, valueType);
    }

    public static <T> T obj(JsonNode json, Class<T> valueType) throws JsonProcessingException {
        return the.treeToValue(json, valueType);
    }

    public static ObjectNode node() {
        return the.createObjectNode();
    }
}
