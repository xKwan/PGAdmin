package spike.s1servlet;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class Responses {

    private Responses() {
    }

    static void json(HttpServletResponse res, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        res.setContentType("application/json;charset=UTF-8");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
    }
}
