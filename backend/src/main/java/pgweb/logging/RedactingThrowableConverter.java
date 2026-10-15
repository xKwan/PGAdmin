package pgweb.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;

/** Logback {@code %ex} replacement: exception messages (for example a JDBC URL with a password) are redacted too. */
public class RedactingThrowableConverter extends ThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return Redactor.redact(super.throwableProxyToString(tp));
    }
}
