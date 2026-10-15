package spike.s1servlet.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 client on one TCP connection. NTLM (inside Negotiate) is connection-bound: every leg of the
 * handshake must use the same socket, which general-purpose clients do not guarantee.
 */
public final class RawHttp implements AutoCloseable {

    public record Response(int status, Map<String, List<String>> headers, String body) {
        public List<String> header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final int port;

    public RawHttp(int port) throws IOException {
        this.port = port;
        this.socket = new Socket(InetAddress.getLoopbackAddress(), port);
        this.socket.setSoTimeout(15_000);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public Response get(String path, Map<String, String> headers) throws IOException {
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: localhost:").append(port).append("\r\n");
        req.append("Connection: keep-alive\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        req.append("\r\n");
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return read();
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
        }
        if (c == -1 && b.size() == 0) {
            throw new IOException("connection closed by server");
        }
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    private Response read() throws IOException {
        String status = readLine();
        String[] parts = status.split(" ", 3);
        int code = Integer.parseInt(parts[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String line = readLine(); !line.isEmpty(); line = readLine()) {
            int colon = line.indexOf(':');
            headers.computeIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(line.substring(colon + 1).trim());
        }
        byte[] body;
        List<String> te = headers.getOrDefault("transfer-encoding", List.of());
        List<String> cl = headers.getOrDefault("content-length", List.of());
        if (te.stream().anyMatch(v -> v.equalsIgnoreCase("chunked"))) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            while (true) {
                int size = Integer.parseInt(readLine().split(";")[0].trim(), 16);
                if (size == 0) {
                    readLine();
                    break;
                }
                b.write(in.readNBytes(size));
                readLine();
            }
            body = b.toByteArray();
        } else if (!cl.isEmpty()) {
            body = in.readNBytes(Integer.parseInt(cl.get(0)));
        } else {
            body = new byte[0];
        }
        return new Response(code, headers, new String(body, StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
