package pgweb.web;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Fails with 413 once more than {@code limit} bytes are read (bodies sent without a Content-Length). */
final class LimitedInputStream extends FilterInputStream {

    private final long limit;
    private long count;

    LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            add(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n > 0) {
            add(n);
        }
        return n;
    }

    private void add(long n) {
        count += n;
        if (count > limit) {
            throw ApiException.tooLarge("Request body exceeds " + limit + " bytes");
        }
    }
}
