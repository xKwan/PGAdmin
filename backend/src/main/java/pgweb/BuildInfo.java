package pgweb;

/** Version information from the JAR manifest. */
public final class BuildInfo {

    static final String UNKNOWN = "development";

    private BuildInfo() {
    }

    /** The {@code Implementation-Version} of the packaged JAR, or {@value #UNKNOWN} when run from classes. */
    public static String version() {
        return versionOf(BuildInfo.class.getPackage().getImplementationVersion());
    }

    static String versionOf(String implementationVersion) {
        return implementationVersion == null || implementationVersion.isBlank() ? UNKNOWN : implementationVersion;
    }

    public static String describe() {
        return "pgweb " + version() + " (Java " + Runtime.version() + ")";
    }
}
