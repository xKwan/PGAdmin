package pgweb.config;

import java.util.List;

/** Invalid configuration. Carries every problem found, so an operator can fix them in one go. */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public ConfigException(List<String> problems) {
        super("Invalid configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public ConfigException(String problem) {
        this(List.of(problem));
    }

    public List<String> problems() {
        return problems;
    }
}
