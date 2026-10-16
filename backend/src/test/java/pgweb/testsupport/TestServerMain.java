package pgweb.testsupport;

import pgweb.Application;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Properties;

/**
 * <b>Test scope only.</b> Runs pgweb in its own process with the mock sign-in and the password connector, so a
 * test can kill the process and observe what PostgreSQL does (plan section 8.1: app-process kill).
 * <pre>java -cp &lt;test classpath&gt; pgweb.testsupport.TestServerMain app.properties roles.properties</pre>
 * Prints {@code READY <port>} on stdout once it serves requests.
 */
public final class TestServerMain {

    private TestServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Properties config = load(Path.of(args[0]));
        Properties roles = load(Path.of(args[1]));
        PasswordTestConnector connector = new PasswordTestConnector();
        roles.stringPropertyNames().forEach(r -> connector.password(r, roles.getProperty(r)));
        Application app = Application.start(TestConfig.load(config), new Application.Options(new MockAuthFilter(), r -> { }, Clock.systemUTC(), connector));
        System.out.println("READY " + app.port()); // the parent process reads this line
        System.out.flush();
        Thread.currentThread().join(); // until killed
    }

    private static Properties load(Path file) throws Exception {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }
}
