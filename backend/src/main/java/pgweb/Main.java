package pgweb;

/**
 * Entry point of the pgweb service (embedded Tomcat, ADR-0001). The server itself is added in Phase 3; this class
 * exists so the packaging, manifest and offline build can be verified first (Phase 2).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println(BuildInfo.describe());
    }
}
