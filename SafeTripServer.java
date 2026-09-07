import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class SafeTripServer {

    private static final Map<String, List<String>> rooms =
            Collections.synchronizedMap(new HashMap<String, List<String>>());

    private static final Map<String, List<PrivateMessage>> privateChats =
            Collections.synchronizedMap(new HashMap<String, List<PrivateMessage>>());

    private static final Map<String, Long> presence =
            Collections.synchronizedMap(new HashMap<String, Long>());

    private static final long PRESENCE_TIMEOUT = 15000L;

    // ===== FORUM =====
    private static final List<ForumPost> forumPosts =
            Collections.synchronizedList(new ArrayList<ForumPost>());

    private static final AtomicLong forumCounter = new AtomicLong(1);
    // =================

    private static final AtomicLong messageCounter = new AtomicLong(1);

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/messages", SafeTripServer::getMessages);
        server.createContext("/message", SafeTripServer::postMessage);

        server.createContext("/private-messages", SafeTripServer::getPrivateMessages);
        server.createContext("/private-message", SafeTripServer::postPrivateMessage);
        server.createContext("/private-message-edit", SafeTripServer::editPrivateMessage);
        server.createContext("/private-message-delete", SafeTripServer::deletePrivateMessage);

        // ===== FORUM =====
        server.createContext("/forum-posts", SafeTripServer::getForumPosts);
        server.createContext("/forum-post", SafeTripServer::postForumPost);
        server.createContext("/forum-post-edit", SafeTripServer::editForumPost);
        server.createContext("/forum-post-delete", SafeTripServer::deleteForumPost);
        // =================

        server.createContext("/presence", SafeTripServer::presence);

        server.setExecutor(null);
        server.start();
        System.out.println("SafeTrip Server läuft auf Port " + port);
    }

    // =====================================================
    // Community Chat
    // =====================================================
    private static void getMessages(HttpExchange ex) throws IOException {
        String room = getRoomFromQuery(ex.getRequestURI().getQuery());
        List<String> messages = rooms.get(room);
        String body;
        if (messages == null || messages.isEmpty()) {
            body = "NO_MESSAGES";
        } else {
            synchronized (messages) {
                body = String.join("\n", messages);
            }
        }
        send(ex, body);
    }

    private static void postMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        String data = new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8);
        Map<String, String> values = parseForm(data);

        String room = values.get("room");
        String message = values.get("message");

        if (room == null || room.trim().isEmpty()) {
            room = "Community";
        }

        if (message != null && !message.trim().isEmpty()) {
            message = cleanMessage(message);
            List<String> messages = rooms.get(room);
            if (messages == null) {
                messages = Collections.synchronizedList(new ArrayList<String>());
                rooms.put(room, messages);
            }
            messages.add(message);
        }
        send(ex, "MESSAGE_SAVED");
    }

    // =====================================================
    // Private Chat
    // =====================================================
    private static void postPrivateMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        String data = new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8);
        Map<String, String> values = parseForm(data);

        String sender = cleanId(values.get("sender"));
        String recipient = cleanId(values.get("recipient"));
        String senderNick = cleanMessage(values.get("senderNick"));
        String message = cleanMessage(values.get("message"));

        if (sender.isEmpty() || recipient.isEmpty() || message.isEmpty() || sender.equals(recipient)) {
            send(ex, "INVALID_PRIVATE_MESSAGE");
            return;
        }

        if (senderNick.isEmpty()) {
            senderNick = "Ich";
        }

        String chatKey = privateChatKey(sender, recipient);
        List<PrivateMessage> messages = privateChats.get(chatKey);
        if (messages == null) {
            messages = Collections.synchronizedList(new ArrayList<PrivateMessage>());
            privateChats.put(chatKey, messages);
        }

        String id = "PM-" + messageCounter.getAndIncrement();
        messages.add(new PrivateMessage(id, sender, recipient, senderNick, message));
        send(ex, "PRIVATE_MESSAGE_SAVED");
    }

    private static void getPrivateMessages(HttpExchange ex) throws IOException {
        Map<String, String> values = parseQuery(ex.getRequestURI().getQuery());

        String sender = cleanId(values.get("sender"));
        String recipient = cleanId(values.get("recipient"));

        if (sender.isEmpty() || recipient.isEmpty() || sender.equals(recipient)) {
            send(ex, "INVALID_PRIVATE_CHAT");
            return;
        }

        String chatKey = privateChatKey(sender, recipient);
        List<PrivateMessage> messages = privateChats.get(chatKey);

        if (messages == null || messages.isEmpty()) {
            send(ex, "NO_MESSAGES");
            return;
        }

        StringBuilder body = new StringBuilder();
        synchronized (messages) {
            for (PrivateMessage pm : messages) {
                if (pm.deleted) continue;
                body.append(pm.id).append("|");
                body.append(pm.senderId).append("|");
                body.append(pm.senderNick).append("|");
                body.append(pm.text).append("\n");
            }
        }

        if (body.length() == 0) {
            send(ex, "NO_MESSAGES");
            return;
        }

        body.setLength(body.length() - 1);
        send(ex, body.toString());
    }

    private static void editPrivateMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        String data = new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8);
        Map<String, String> values = parseForm(data);

        String sender = cleanId(values.get("sender"));
        String messageId = cleanId(values.get("messageId"));
        String newText = cleanMessage(values.get("message"));

        if (sender.isEmpty() || messageId.isEmpty() || newText.isEmpty()) {
            send(ex, "INVALID_EDIT");
            return;
        }

        PrivateMessage pm = findPrivateMessage(messageId);
        if (pm == null) {
            send(ex, "MESSAGE_NOT_FOUND");
            return;
        }

        synchronized (pm) {
            if (!pm.senderId.equals(sender)) {
                send(ex, "NOT_ALLOWED");
                return;
            }
            if (pm.deleted) {
                send(ex, "MESSAGE_DELETED");
                return;
            }
            pm.text = newText;
            pm.edited = true;
        }
        send(ex, "MESSAGE_EDITED");
    }

    private static void deletePrivateMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        String data = new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8);
        Map<String, String> values = parseForm(data);

        String sender = cleanId(values.get("sender"));
        String messageId = cleanId(values.get("messageId"));

        if (sender.isEmpty() || messageId.isEmpty()) {
            send(ex, "INVALID_DELETE");
            return;
        }

        PrivateMessage pm = findPrivateMessage(messageId);
        if (pm == null) {
            send(ex, "MESSAGE_NOT_FOUND");
            return;
        }

        synchronized (pm) {
            if (!pm.senderId.equals(sender)) {
                send(ex, "NOT_ALLOWED");
                return;
            }
            pm.deleted = true;
        }
        send(ex, "MESSAGE_DELETED");
    }

    // =====================================================
    // Presence
    // =====================================================
    private static void presence(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (method.equalsIgnoreCase("POST")) {
            setPresence(ex);
            return;
        }
        if (method.equalsIgnoreCase("GET")) {
            getPresence(ex);
            return;
        }
        send(ex, "ONLY_GET_OR_POST");
    }

    // === ERGÄNZUNG (fehlte im Original) ===
    private static void setPresence(HttpExchange ex) throws IOException {
        String data = new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8);
        Map<String, String> values = parseForm(data);
        String userId = cleanId(values.get("userId"));
        if (userId.isEmpty()) {
            send(ex, "INVALID_PRESENCE");
            return;
        }
        presence.put(userId, System.currentTimeMillis());
        send(ex, "PRESENCE_OK");
    }

    // === ERGÄNZUNG (fehlte im Original) ===
    private static void getPresence(HttpExchange ex) throws IOException {
        long now = System.currentTimeMillis();
        StringBuilder body = new StringBuilder();
        synchronized (presence) {
            for (Map.Entry<String, Long> entry : presence.entrySet()) {
                if (now - entry.getValue() <= PRESENCE_TIMEOUT) {
                    if (body.length() > 0) body.append("\n");
                    body.append(entry.getKey());
                }
            }
        }
        if (body.length() == 0) {
            send(ex, "NO_PRESENCE");
        } else {
            send(ex, body.toString());
        }
    }

    // =====================================================
    // FORUM
    // =====================================================
    private static void postForumPost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        Map<String, String> v = parseForm(
                new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8)
        );

        String userId = cleanId(v.get("userId"));
        String nick = cleanMessage(v.get("nick"));
        String title = cleanMessage(v.get("title"));
        String message = cleanMessage(v.get("message"));
        boolean anonymous = "true".equalsIgnoreCase(v.get("anonymous"))
                || "1".equals(v.get("anonymous"));

        if (title.isEmpty() || message.isEmpty()) {
            send(ex, "INVALID_FORUM_POST");
            return;
        }

        if (anonymous) {
            nick = "Anonym";
        } else if (nick.isEmpty()) {
            nick = "Unbekannt";
        }

        String id = "FP-" + forumCounter.getAndIncrement();
        forumPosts.add(new ForumPost(id, userId, nick, title, message, anonymous));
        send(ex, id);
    }

    private static void editForumPost(HttpExchange ex) throws IOException {
        Map<String, String> v = parseForm(
                new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8)
        );

        String id = cleanId(v.get("id"));
        String userId = cleanId(v.get("userId"));

        synchronized (forumPosts) {
            for (ForumPost p : forumPosts) {
                if (!p.id.equals(id)) continue;
                if (!p.userId.equals(userId)) {
                    send(ex, "NOT_OWNER");
                    return;
                }
                p.title = cleanMessage(v.get("title"));
                p.message = cleanMessage(v.get("message"));
                send(ex, "OK");
                return;
            }
        }
        send(ex, "NOT_FOUND");
    }

    private static void deleteForumPost(HttpExchange ex) throws IOException {
        Map<String, String> v = parseForm(
                new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8)
        );

        String id = cleanId(v.get("id"));
        String userId = cleanId(v.get("userId"));

        synchronized (forumPosts) {
            for (int i = 0; i < forumPosts.size(); i++) {
                ForumPost p = forumPosts.get(i);
                if (!p.id.equals(id)) continue;
                if (!p.userId.equals(userId)) {
                    send(ex, "NOT_OWNER");
                    return;
                }
                forumPosts.remove(i);
                send(ex, "OK");
                return;
            }
        }
        send(ex, "NOT_FOUND");
    }

    private static void getForumPosts(HttpExchange ex) throws IOException {
        if (forumPosts.isEmpty()) {
            send(ex, "NO_FORUM_POSTS");
            return;
        }

        StringBuilder body = new StringBuilder();
        synchronized (forumPosts) {
            for (int i = forumPosts.size() - 1; i >= 0; i--) {
                ForumPost p = forumPosts.get(i);
                body.append(p.id).append("|");
                body.append(p.userId).append("|");
                body.append(p.nick).append("|");
                body.append(p.title).append("|");
                body.append(p.message).append("|");
                body.append(p.anonymous ? "1" : "0");
                body.append("\n");
            }
        }

        if (body.length() > 0) {
            body.setLength(body.length() - 1);
        }
        send(ex, body.toString());
    }

    // =====================================================
    // HILFSMETHODEN
    // =====================================================
    private static byte[] readBody(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static PrivateMessage findPrivateMessage(String messageId) {
        synchronized (privateChats) {
            for (List<PrivateMessage> list : privateChats.values()) {
                synchronized (list) {
                    for (PrivateMessage pm : list) {
                        if (pm.id.equals(messageId)) {
                            return pm;
                        }
                    }
                }
            }
        }
        return null;
    }

    // === ERGÄNZUNG (fehlte im Original) ===
    private static String privateChatKey(String a, String b) {
        if (a.compareTo(b) < 0) {
            return a + "|" + b;
        }
        return b + "|" + a;
    }

    // =====================================================
    // DATENKLASSEN
    // =====================================================
    private static class ForumPost {
        String id;
        String userId;
        String nick;
        String title;
        String message;
        boolean anonymous;

        ForumPost(String id, String userId, String nick, String title, String message, boolean anonymous) {
            this.id = id;
            this.userId = userId;
            this.nick = nick;
            this.title = title;
            this.message = message;
            this.anonymous = anonymous;
        }
    }

    private static class PrivateMessage {
        String id;
        String senderId;
        String recipientId;
        String senderNick;
        String text;
        boolean edited;
        boolean deleted;

        PrivateMessage(String id, String senderId, String recipientId, String senderNick, String text) {
            this.id = id;
            this.senderId = senderId;
            this.recipientId = recipientId;
            this.senderNick = senderNick;
            this.text = text;
            this.edited = false;
            this.deleted = false;
        }
    }

    // =====================================================
    // PARSER
    // =====================================================
    private static Map<String, String> parseForm(String data) {
        Map<String, String> values = new HashMap<String, String>();
        if (data == null || data.isEmpty()) {
            return values;
        }
        String[] parts = data.split("&");
        for (String part : parts) {
            int pos = part.indexOf('=');
            if (pos <= 0) continue;
            try {
                String key = URLDecoder.decode(part.substring(0, pos), "UTF-8");
                String value = URLDecoder.decode(part.substring(pos + 1), "UTF-8");
                values.put(key, value);
            } catch (Exception ignored) {
            }
        }
        return values;
    }

    private static Map<String, String> parseQuery(String query) {
        return parseForm(query == null ? "" : query);
    }

    private static String getRoomFromQuery(String query) {
        Map<String, String> values = parseQuery(query);
        String room = values.get("room");
        if (room == null || room.trim().isEmpty()) {
            return "Community";
        }
        return room;
    }

    private static String cleanId(String id) {
        if (id == null) return "";
        return id.replace("|", "_").replace("\n", "_").replace("\r", "_").trim();
    }

    private static String cleanMessage(String message) {
        if (message == null) return "";
        return message.replace("|", " ").replace("\r", " ").replace("\n", " ").trim();
    }

    private static void send(HttpExchange ex, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        OutputStream out = ex.getResponseBody();
        out.write(bytes);
        out.close();
    }
}
