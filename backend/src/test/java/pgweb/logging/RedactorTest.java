package pgweb.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import pgweb.testsupport.Seeds;

import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    private static String randomSecret(Random r) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!$%*+-=?@^_~";
        StringBuilder b = new StringBuilder("S");
        int n = 10 + r.nextInt(30);
        for (int i = 0; i < n; i++) {
            b.append(chars.charAt(r.nextInt(chars.length())));
        }
        return b.toString();
    }

    /** Ways a secret could appear in a log line; each must be masked. */
    private static final List<Function<String, String>> FORMS = List.of(
            s -> "password=" + s,
            s -> "connecting with Password: " + s + " to host",
            s -> "jdbc:postgresql://db:5432/x?user=a&password=" + s + "&ssl=true",
            s -> "{\"user\":\"a\",\"password\":\"" + s + "\"}",
            s -> "{\"token\": \"" + s + "\", \"x\": 1}",
            s -> "audit.hmacKey=" + s,
            s -> "server.tls.keystorePassword = " + s,
            s -> "sonar.token=" + s,
            s -> "secret: " + s,
            s -> "PGPASSWORD=" + s + " psql -h x");

    @RepeatedTest(5)
    void randomSecretsNeverSurviveRedaction(RepetitionInfo info) {
        Random r = Seeds.random("redact-" + info.getCurrentRepetition());
        for (Function<String, String> form : FORMS) {
            String secret = randomSecret(r);
            String prefix = r.nextBoolean() ? "INFO something happened: " : "";
            String out = Redactor.redact(prefix + form.apply(secret) + (r.nextBoolean() ? " trailing words" : ""));
            assertThat(out).as(form.apply("<secret>")).doesNotContain(secret).contains(Redactor.MASK);
        }
    }

    @Test
    void authorizationHeadersKeepTheSchemeOnly() {
        Random r = Seeds.random("auth-header");
        byte[] token = new byte[40 + r.nextInt(200)];
        r.nextBytes(token);
        String b64 = Base64.getEncoder().encodeToString(token);
        for (String scheme : List.of("Negotiate", "NTLM", "Basic", "Bearer")) {
            String out = Redactor.redact("Authorization: " + scheme + " " + b64);
            assertThat(out).isEqualTo("Authorization: " + scheme + " ***");
        }
        assertThat(Redactor.redact("WWW-Authenticate: Negotiate " + b64)).isEqualTo("WWW-Authenticate: Negotiate ***");
    }

    @Test
    void dpapiBlobsAreShortened() {
        assertThat(Redactor.redact("audit.password=dpapi:AQAAANCMnd8BFdERjHoAwE/Cl+sBAAAA")).doesNotContain("AQAAANCMnd8");
    }

    @Test
    void ordinaryTextIsUnchanged() {
        for (String s : List.of("", "user logged in", "SELECT 1", "password policy updated", "token count 5 of 10",
                "Session created for CORP\\alice")) {
            assertThat(Redactor.redact(s)).isEqualTo(s);
        }
        assertThat(Redactor.redact(null)).isNull();
    }

    @Test
    void logbackConvertersRedactMessagesAndStackTraces() {
        LoggerContext ctx = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        Logger logger = ctx.getLogger("redaction-test");
        String secret = randomSecret(Seeds.random("converters"));
        LoggingEvent event = new LoggingEvent("x", logger, Level.ERROR, "connect failed password={}", null, new Object[] {secret});
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("url jdbc:postgresql://h/d?password=" + secret)));

        RedactingMessageConverter msg = new RedactingMessageConverter();
        msg.setContext(ctx);
        msg.start();
        RedactingThrowableConverter ex = new RedactingThrowableConverter();
        ex.setContext(ctx);
        ex.start();
        assertThat(msg.convert(event)).isEqualTo("connect failed password=***");
        assertThat(ex.convert(event)).contains("IllegalStateException").doesNotContain(secret);
    }
}
