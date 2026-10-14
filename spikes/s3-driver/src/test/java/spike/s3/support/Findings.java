package spike.s3.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** Observations for the spike report, one JSON object per line. */
public final class Findings {

    private static final Findings INSTANCE = new Findings();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file = Paths.get("target", "s3-findings.jsonl");

    private Findings() {
    }

    public static Findings instance() {
        return INSTANCE;
    }

    public synchronized void record(String area, String key, Object value) {
        ObjectNode n = JSON.createObjectNode();
        n.put("seed", SpikeExtension.seed());
        n.put("area", area);
        n.put("key", key);
        n.putPOJO("value", value);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(n) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
