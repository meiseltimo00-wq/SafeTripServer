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

    private static final Map<String, List<String>> privateChats =
            Collections.synchronizedMap(
                    new HashMap<String, List<String>>()
            );

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(
                System.getenv().getOrDefault(
                        "PORT",
                        "8080"
                )
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

        server.createContext(
                "/private-messages",
                SafeTripServer::getPrivateMessages
        );

        server.createContext(
                "/private-message",
                SafeTripServer::postPrivateMessage
        );

        server.setExecutor(null);

        server.start();

        System.out.println(
                "SafeTrip Server läuft auf Port " +
                port
        );
    }

    /*
     * ==========================================
     * COMMUNITY CHAT
     * ==========================================
     */

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

        if (messages == null ||
                messages.isEmpty()) {

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

        send(
                ex,
                body
        );
    }

    private static void postMessage(
            HttpExchange ex
    ) throws IOException {

        if (!ex.getRequestMethod()
                .equalsIgnoreCase("POST")) {

            send(
                    ex,
                    "ONLY_POST"
            );

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

            message =
                    cleanMessage(message);

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
                    message
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

    /*
     * ==========================================
     * PRIVATE CHAT
     * ==========================================
     */

    private static void postPrivateMessage(
            HttpExchange ex
    ) throws IOException {

        if (!ex.getRequestMethod()
                .equalsIgnoreCase("POST")) {

            send(
                    ex,
                    "ONLY_POST"
            );

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

        String sender =
                values.get("sender");

        String recipient =
                values.get("recipient");

        String senderNick =
                values.get("senderNick");

        String message =
                values.get("message");

        if (sender == null ||
                sender.trim().isEmpty() ||
                recipient == null ||
                recipient.trim().isEmpty() ||
                message == null ||
                message.trim().isEmpty()) {

            send(
                    ex,
                    "INVALID_PRIVATE_MESSAGE"
            );

            return;
        }

        sender =
                cleanId(sender);

        recipient =
                cleanId(recipient);

        if (sender.isEmpty() ||
                recipient.isEmpty() ||
                sender.equals(recipient)) {

            send(
                    ex,
                    "INVALID_PRIVATE_CHAT"
            );

            return;
        }

        if (senderNick == null ||
                senderNick.trim().isEmpty()) {

            senderNick = "Ich";
        }

        senderNick =
                cleanMessage(senderNick);

        message =
                cleanMessage(message);

        String chatKey =
                privateChatKey(
                        sender,
                        recipient
                );

        List<String> messages =
                privateChats.get(chatKey);

        if (messages == null) {

            messages =
                    Collections.synchronizedList(
                            new ArrayList<String>()
                    );

            privateChats.put(
                    chatKey,
                    messages
            );
        }

        String saved =
                sender +
                "|" +
                senderNick +
                ": " +
                message;

        messages.add(
                saved
        );

        System.out.println(
                "Private [" +
                chatKey +
                "]: " +
                saved
        );

        send(
                ex,
                "PRIVATE_MESSAGE_SAVED"
        );
    }

    private static void getPrivateMessages(
            HttpExchange ex
    ) throws IOException {

        String query =
                ex.getRequestURI()
                        .getQuery();

        Map<String, String> values =
                parseQuery(query);

        String sender =
                values.get("sender");

        String recipient =
                values.get("recipient");

        if (sender == null ||
                recipient == null ||
                sender.trim().isEmpty() ||
                recipient.trim().isEmpty()) {

            send(
                    ex,
                    "INVALID_PRIVATE_CHAT"
            );

            return;
        }

        sender =
                cleanId(sender);

        recipient =
                cleanId(recipient);

        if (sender.isEmpty() ||
                recipient.isEmpty() ||
                sender.equals(recipient)) {

            send(
                    ex,
                    "INVALID_PRIVATE_CHAT"
            );

            return;
        }

        String chatKey =
                privateChatKey(
                        sender,
                        recipient
                );

        List<String> messages =
                privateChats.get(chatKey);

        if (messages == null ||
                messages.isEmpty()) {

            send(
                    ex,
                    "NO_MESSAGES"
            );

            return;
        }

        StringBuilder body =
                new StringBuilder();

        synchronized (messages) {

            for (String msg : messages) {

                int separator =
                        msg.indexOf('|');

                if (separator < 0) {
                    continue;
                }

                String messageSender =
                        msg.substring(
                                0,
                                separator
                        );

                String display =
                        msg.substring(
                                separator + 1
                        );

                if (messageSender.equals(sender)) {

                    body.append(
                            "Ich: "
                    );

                    body.append(
                            removeNickPrefix(
                                    display
                            ));

                } else {

                    body.append(
                            display
                    );
                }

                body.append(
                        "\n"
                );
            }
        }

        if (body.length() == 0) {

            send(
                    ex,
                    "NO_MESSAGES"
            );

            return;
        }

        body.setLength(
                body.length() - 1
        );

        send(
                ex,
                body.toString()
        );
    }

    /*
     * ==========================================
     * PRIVATE CHAT KEY
     * ==========================================
     */

    private static String privateChatKey(
            String a,
            String b
    ) {

        if (a.compareTo(b) < 0) {

            return "PRIVATE|" +
                    a +
                    "|" +
                    b;
        }

        return "PRIVATE|" +
                b +
                "|" +
                a;
    }

    /*
     * ==========================================
     * FORM / QUERY
     * ==========================================
     */

    private static Map<String, String> parseForm(
            String data
    ) {

        Map<String, String> values =
                new HashMap<String, String>();

        if (data == null ||
                data.isEmpty()) {

            return values;
        }

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

    private static Map<String, String> parseQuery(
            String query
    ) {

        return parseForm(
                query == null
                        ? ""
                        : query
        );
    }

    /*
     * ==========================================
     * COMMUNITY ROOM
     * ==========================================
     */

    private static String getRoomFromQuery(
            String query
    ) {

        if (query == null ||
                query.isEmpty()) {

            return "Community";
        }

        Map<String, String> values =
                parseQuery(query);

        String room =
                values.get("room");

        if (room == null ||
                room.trim().isEmpty()) {

            return "Community";
        }

        return room;
    }

    /*
     * ==========================================
     * CLEANING
     * ==========================================
     */

    private static String cleanId(
            String id
    ) {

        if (id == null) {
            return "";
        }

        return id
                .replace("|", "_")
                .replace("\n", "_")
                .replace("\r", "_")
                .trim();
    }

    private static String cleanMessage(
            String message
    ) {

        if (message == null) {
            return "";
        }

        return message
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
    }

    private static String removeNickPrefix(
            String text
    ) {

        if (text == null) {
            return "";
        }

        int pos =
                text.indexOf(": ");

        if (pos >= 0) {

            return text.substring(
                    pos + 2
            );
        }

        return text;
    }

    /*
     * ==========================================
     * RESPONSE
     * ==========================================
     */

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
