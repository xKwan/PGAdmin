package spike.s1.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Random;

/**
 * Observations for the spike report, one JSON object per line ({@code target/s1-findings.jsonl}). Values are written
 * as strings, so this works unchanged with Jackson 2 (Boot 3) and Jackson 3 (Boot 4).
 */
public final class Findings {

    private static final Path FILE = Paths.get("target", "s1-findings.jsonl");

    private Findings() {
    }

    public static long seed() {
        String s = System.getProperty("s1.seed", "0");
        return s.isBlank() ? 0L : Long.parseLong(s.trim());
    }

    public static Random random(String testName) {
        return new Random(seed() * 31 + testName.hashCode());
    }

    public static synchronized void record(String area, String key, Object value) {
        String line = "{\"seed\":" + seed() + ",\"springBoot\":\"" + esc(springBootVersion()) + "\",\"area\":\"" + esc(area)
                + "\",\"key\":\"" + esc(key) + "\",\"value\":\"" + esc(String.valueOf(value)) + "\"}" + System.lineSeparator();
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String springBootVersion() {
        return String.valueOf(org.springframework.boot.SpringBootVersion.getVersion());
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
