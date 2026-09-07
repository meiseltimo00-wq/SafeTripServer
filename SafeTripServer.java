
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SafeTripServer {

    private static final List<String> messages =
            Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8080")
        );

        HttpServer server =
                HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/messages", SafeTripServer::getMessages);
        server.createContext("/message", SafeTripServer::postMessage);

        server.setExecutor(null);
        server.start();

        System.out.println("SafeTrip Server läuft auf Port " + port);
    }

    private static void getMessages(HttpExchange ex) throws IOException {

        String body;

        synchronized (messages) {
            body = messages.isEmpty()
                    ? "NO_MESSAGES"
                    : String.join("\n", messages);
        }

        send(ex, body);
    }

    private static void postMessage(HttpExchange ex) throws IOException {

        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        String data = new String(
                ex.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        if (data.startsWith("message=")) {
            data = URLDecoder.decode(data.substring(8), "UTF-8");
        }

        if (!data.trim().isEmpty()) {
            messages.add(data.trim());
            System.out.println("Saved: " + data);
        }

        send(ex, "MESSAGE_SAVED");
    }

    private static void send(HttpExchange ex, String text)
            throws IOException {

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

        ex.sendResponseHeaders(200, bytes.length);

        OutputStream out = ex.getResponseBody();
        out.write(bytes);
        out.close();
    }
}
