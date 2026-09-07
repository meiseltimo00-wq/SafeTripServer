import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SafeTripServer {

    private static final Map<String, List<String>> rooms =
            Collections.synchronizedMap(
                    new HashMap<String, List<String>>()
            );

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8080")
        );

        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(port),
                        0
                );

        server.createContext(
                "/messages",
                SafeTripServer::getMessages
        );

        server.createContext(
                "/message",
                SafeTripServer::postMessage
        );

        server.setExecutor(null);
        server.start();

        System.out.println(
                "SafeTrip Server läuft auf Port " + port
        );
    }

    private static void getMessages(
            HttpExchange ex
    ) throws IOException {

        String room =
                getRoomFromQuery(
                        ex.getRequestURI().getQuery()
                );

        List<String> messages =
                rooms.get(room);

        String body;

        if (messages == null || messages.isEmpty()) {

            body = "NO_MESSAGES";

        } else {

            synchronized (messages) {

                body =
                        String.join(
                                "\n",
                                messages
                        );
            }
        }

        send(ex, body);
    }

    private static void postMessage(
            HttpExchange ex
    ) throws IOException {

        if (!ex.getRequestMethod()
                .equalsIgnoreCase("POST")) {

            send(ex, "ONLY_POST");
            return;
        }

        String data =
                new String(
                        ex.getRequestBody()
                                .readAllBytes(),
                        StandardCharsets.UTF_8
                );

        Map<String, String> values =
                parseForm(data);

        String room =
                values.get("room");

        String message =
                values.get("message");

        if (room == null ||
                room.trim().isEmpty()) {

            room = "Community";
        }

        if (message != null &&
                !message.trim().isEmpty()) {

            List<String> messages =
                    rooms.get(room);

            if (messages == null) {

                messages =
                        Collections.synchronizedList(
                                new ArrayList<String>()
                        );

                rooms.put(
                        room,
                        messages
                );
            }

            messages.add(
                    message.trim()
            );

            System.out.println(
                    "Saved [" +
                    room +
                    "]: " +
                    message
            );
        }

        send(
                ex,
                "MESSAGE_SAVED"
        );
    }

    private static Map<String, String> parseForm(
            String data
    ) {

        Map<String, String> values =
                new HashMap<String, String>();

        String[] parts =
                data.split("&");

        for (String part : parts) {

            int pos =
                    part.indexOf('=');

            if (pos <= 0) {
                continue;
            }

            try {

                String key =
                        URLDecoder.decode(
                                part.substring(
                                        0,
                                        pos
                                ),
                                "UTF-8"
                        );

                String value =
                        URLDecoder.decode(
                                part.substring(
                                        pos + 1
                                ),
                                "UTF-8"
                        );

                values.put(
                        key,
                        value
                );

            } catch (Exception ignored) {
            }
        }

        return values;
    }

    private static String getRoomFromQuery(
            String query
    ) {

        if (query == null ||
                query.isEmpty()) {

            return "Community";
        }

        String[] parts =
                query.split("&");

        for (String part : parts) {

            int pos =
                    part.indexOf('=');

            if (pos <= 0) {
                continue;
            }

            try {

                String key =
                        URLDecoder.decode(
                                part.substring(
                                        0,
                                        pos
                                ),
                                "UTF-8"
                        );

                String value =
                        URLDecoder.decode(
                                part.substring(
                                        pos + 1
                                ),
                                "UTF-8"
                        );

                if (key.equals("room") &&
                        !value.trim().isEmpty()) {

                    return value;
                }

            } catch (Exception ignored) {
            }
        }

        return "Community";
    }

    private static void send(
            HttpExchange ex,
            String text
    ) throws IOException {

        byte[] bytes =
                text.getBytes(
                        StandardCharsets.UTF_8
                );

        ex.getResponseHeaders().add(
                "Content-Type",
                "text/plain; charset=UTF-8"
        );

        ex.getResponseHeaders().add(
                "Access-Control-Allow-Origin",
                "*"
        );

        ex.sendResponseHeaders(
                200,
                bytes.length
        );

        OutputStream out =
                ex.getResponseBody();

        out.write(bytes);
        out.close();
    }
}
