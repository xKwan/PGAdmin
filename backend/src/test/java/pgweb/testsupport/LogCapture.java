package pgweb.testsupport;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures what the root logger writes, formatted with the production pattern (redacting converters included), so
 * tests can assert that no secret reaches a log line.
 */
public final class LogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final List<String> lines = new ArrayList<>();
    private final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    private final Logger root;

    public LogCapture() {
        LoggerContext ctx = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        // The rmsg/rex conversion rules are registered by logback-test.xml, exactly as logback.xml does in production.
        encoder.setContext(ctx);
        encoder.setPattern("%-5level [%X{requestId:-}] %logger - %rmsg%n%rex");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        setContext(ctx);
        start();
        root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(this);
    }

    @Override
    protected synchronized void append(ILoggingEvent e) {
        lines.add(new String(encoder.encode(e), StandardCharsets.UTF_8));
    }

    public synchronized String text() {
        return String.join("", lines);
    }

    @Override
    public void close() {
        root.detachAppender(this);
        stop();
    }
}
