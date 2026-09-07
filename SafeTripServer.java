 
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class SafeTripServer {

    private static final Map<String, List<String>> rooms =
            Collections.synchronizedMap(
                    new HashMap<String, List<String>>()
            );

    private static final Map<String, List<PrivateMessage>> privateChats =
            Collections.synchronizedMap(
                    new HashMap<String, List<PrivateMessage>>()
            );

    private static final AtomicLong messageCounter =
            new AtomicLong(1);

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

        server.createContext(
                "/private-message-edit",
                SafeTripServer::editPrivateMessage
        );

        server.createContext(
                "/private-message-delete",
                SafeTripServer::deletePrivateMessage
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
     * COMMUNITY
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
        }

        send(
                ex,
                "MESSAGE_SAVED"
        );
    }

    /*
     * ==========================================
     * PRIVATE SEND
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
                cleanId(
                        values.get("sender")
                );

        String recipient =
                cleanId(
                        values.get("recipient")
                );

        String senderNick =
                cleanMessage(
                        values.get("senderNick")
                );

        String message =
                cleanMessage(
                        values.get("message")
                );

        if (sender.isEmpty() ||
                recipient.isEmpty() ||
                message.isEmpty() ||
                sender.equals(recipient)) {

            send(
                    ex,
                    "INVALID_PRIVATE_MESSAGE"
            );

            return;
        }

        if (senderNick.isEmpty()) {
            senderNick = "Ich";
        }

        String chatKey =
                privateChatKey(
                        sender,
                        recipient
                );

        List<PrivateMessage> messages =
                privateChats.get(chatKey);

        if (messages == null) {

            messages =
                    Collections.synchronizedList(
                            new ArrayList<PrivateMessage>()
                    );

            privateChats.put(
                    chatKey,
                    messages
            );
        }

        String id =
                "PM-" +
                messageCounter.getAndIncrement();

        PrivateMessage pm =
                new PrivateMessage(
                        id,
                        sender,
                        recipient,
                        senderNick,
                        message
                );

        messages.add(pm);

        System.out.println(
                "Private message saved: " +
                id
        );

        send(
                ex,
                "PRIVATE_MESSAGE_SAVED"
        );
    }

    /*
     * ==========================================
     * PRIVATE LOAD
     * ==========================================
     */

    private static void getPrivateMessages(
            HttpExchange ex
    ) throws IOException {

        Map<String, String> values =
                parseQuery(
                        ex.getRequestURI()
                                .getQuery()
                );

        String sender =
                cleanId(
                        values.get("sender")
                );

        String recipient =
                cleanId(
                        values.get("recipient")
                );

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

        List<PrivateMessage> messages =
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

            for (PrivateMessage pm : messages) {

                if (pm.deleted) {

                    continue;
                }

                body.append(
                        pm.id
                );

                body.append("|");

                body.append(
                        pm.senderId
                );

                body.append("|");

                body.append(
                        pm.senderNick
                );

                body.append("|");

                body.append(
                        pm.text
                );

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
     * PRIVATE EDIT
     * ==========================================
     */

    private static void editPrivateMessage(
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
                cleanId(
                        values.get("sender")
                );

        String messageId =
                cleanId(
                        values.get("messageId")
                );

        String newText =
                cleanMessage(
                        values.get("message")
                );

        if (sender.isEmpty() ||
                messageId.isEmpty() ||
                newText.isEmpty()) {

            send(
                    ex,
                    "INVALID_EDIT"
            );

            return;
        }

        PrivateMessage pm =
                findPrivateMessage(
                        messageId
                );

        if (pm == null) {

            send(
                    ex,
                    "MESSAGE_NOT_FOUND"
            );

            return;
        }

        synchronized (pm) {

            if (!pm.senderId.equals(sender)) {

                send(
                        ex,
                        "NOT_ALLOWED"
                );

                return;
            }

            if (pm.deleted) {

                send(
                        ex,
                        "MESSAGE_DELETED"
                );

                return;
            }

            pm.text =
                    newText;

            pm.edited =
                    true;
        }

        send(
                ex,
                "MESSAGE_EDITED"
        );
    }

    /*
     * ==========================================
     * PRIVATE DELETE
     * ==========================================
     */

    private static void deletePrivateMessage(
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
                cleanId(
                        values.get("sender")
                );

        String messageId =
                cleanId(
                        values.get("messageId")
                );

        if (sender.isEmpty() ||
                messageId.isEmpty()) {

            send(
                    ex,
                    "INVALID_DELETE"
            );

            return;
        }

        PrivateMessage pm =
                findPrivateMessage(
                        messageId
                );

        if (pm == null) {

            send(
                    ex,
                    "MESSAGE_NOT_FOUND"
            );

            return;
        }

        synchronized (pm) {

            if (!pm.senderId.equals(sender)) {

                send(
                        ex,
                        "NOT_ALLOWED"
                );

                return;
            }

            pm.deleted =
                    true;
        }

        send(
                ex,
                "MESSAGE_DELETED"
        );
    }

    /*
     * ==========================================
     * FIND MESSAGE
     * ==========================================
     */

    private static PrivateMessage findPrivateMessage(
            String messageId
    ) {

        synchronized (privateChats) {

            for (List<PrivateMessage> list :
                    privateChats.values()) {

                synchronized (list) {

                    for (PrivateMessage pm :
                            list) {

                        if (pm.id.equals(
                                messageId
                        )) {

                            return pm;
                        }
                    }
                }
            }
        }

        return null;
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
     * MESSAGE OBJECT
     * ==========================================
     */

    private static class PrivateMessage {

        String id;
        String senderId;
        String recipientId;
        String senderNick;
        String text;

        boolean edited;
        boolean deleted;

        PrivateMessage(
                String id,
                String senderId,
                String recipientId,
                String senderNick,
                String text
        ) {

            this.id = id;
            this.senderId = senderId;
            this.recipientId = recipientId;
            this.senderNick = senderNick;
            this.text = text;
            this.edited = false;
            this.deleted = false;
        }
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
     * CLEAN
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
                .replace("|", " ")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
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
