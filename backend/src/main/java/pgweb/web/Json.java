package pgweb.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * The one JSON mapper (plan Phase 3: request validation). Strict on input: unknown properties, trailing tokens,
 * duplicate keys, coercion of scalars and polymorphic typing are all refused.
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    static {
        MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(16 * 1024 * 1024).maxNumberLength(64).build());
    }

    private static final String BAD_JSON = "BAD_JSON";

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Parses a request body into {@code type}; any problem becomes a 400 with a message safe to show. */
    public static <T> T read(InputStream in, Class<T> type) {
        try {
            T value = MAPPER.readValue(in, type);
            if (value == null) {
                throw ApiException.badRequest(BAD_JSON, "Request body is required");
            }
            return value;
        } catch (UnrecognizedPropertyException e) {
            throw ApiException.badRequest("UNKNOWN_FIELD", "Unknown field: " + e.getPropertyName());
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest(BAD_JSON, "Malformed or invalid JSON request body");
        } catch (IOException e) {
            throw ApiException.badRequest(BAD_JSON, "Could not read the request body");
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("response serialisation failed", e);
        }
    }
}
