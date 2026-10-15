package spike.s1servlet.support;

import spike.s1servlet.SpikeServer;

import java.nio.file.Path;

/** Starts one embedded server per test class, with its own Tomcat base directory under target/. */
public final class ServerFixture {

    private ServerFixture() {
    }

    public static SpikeServer start(String dataAdminSid, String name) throws Exception {
        Path base = Path.of("target", "tomcat-" + name + "-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        return SpikeServer.start(0, dataAdminSid, base);
    }
}
