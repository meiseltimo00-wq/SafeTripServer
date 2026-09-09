import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

    private static final List<ForumPost> forumPosts =
            Collections.synchronizedList(new ArrayList<ForumPost>());

    private static final long PRESENCE_TIMEOUT = 15000L;

    private static final AtomicLong messageCounter = new AtomicLong(1);
    private static final AtomicLong forumCounter = new AtomicLong(1);

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/messages", SafeTripServer::getMessages);
        server.createContext("/message", SafeTripServer::postMessage);
        server.createContext("/private-messages", SafeTripServer::getPrivateMessages);
        server.createContext("/private-message", SafeTripServer::postPrivateMessage);
        server.createContext("/private-message-edit", SafeTripServer::editPrivateMessage);
        server.createContext("/private-message-delete", SafeTripServer::deletePrivateMessage);
        server.createContext("/presence", SafeTripServer::presence);
        server.createContext("/forum-posts", SafeTripServer::getForumPosts);
        server.createContext("/forum-post", SafeTripServer::postForumPost);
        server.createContext("/forum-post-edit", SafeTripServer::editForumPost);
        server.createContext("/forum-post-delete", SafeTripServer::deleteForumPost);

        server.setExecutor(null);
        server.start();

        System.out.println("SafeTrip Server läuft auf Port " + port);
    }

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

        Map<String, String> values = parseForm(readBody(ex));
        String room = values.get("room");
        String message = cleanMessage(values.get("message"));

        if (room == null || room.trim().isEmpty()) {
            room = "Community";
        }

        room = cleanMessage(room);

        if (!message.isEmpty()) {
            List<String> messages = rooms.get(room);
            if (messages == null) {
                messages = Collections.synchronizedList(new ArrayList<String>());
                rooms.put(room, messages);
            }
            messages.add(message);
        }

        send(ex, "MESSAGE_SAVED");
    }

    private static void postPrivateMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        Map<String, String> values = parseForm(readBody(ex));
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

        System.out.println("Private message saved: " + id);
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
                if (pm.deleted) {
                    continue;
                }
                body.append(pm.id).append("|")
                        .append(pm.senderId).append("|")
                        .append(pm.senderNick).append("|")
                        .append(pm.text).append("\n");
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

        Map<String, String> values = parseForm(readBody(ex));
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

        Map<String, String> values = parseForm(readBody(ex));
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

    private static void setPresence(HttpExchange ex) throws IOException {
        Map<String, String> values = parseForm(readBody(ex));
        String userId = cleanId(values.get("userId"));
        String status = cleanId(values.get("status"));

        if (userId.isEmpty()) {
            send(ex, "INVALID_USER");
            return;
        }

        if (status.equalsIgnoreCase("online")) {
            presence.put(userId, System.currentTimeMillis());
            System.out.println("Presence: " + userId + " = ONLINE");
            send(ex, "PRESENCE_ONLINE");
            return;
        }

        presence.remove(userId);
        System.out.println("Presence: " + userId + " = OFFLINE");
        send(ex, "PRESENCE_OFFLINE");
    }

    private static void getPresence(HttpExchange ex) throws IOException {
        Map<String, String> values = parseQuery(ex.getRequestURI().getQuery());
        String userId = cleanId(values.get("userId"));

        if (userId.isEmpty()) {
            send(ex, "INVALID_USER");
            return;
        }

        Long lastSeen = presence.get(userId);
        if (lastSeen == null) {
            send(ex, "OFFLINE");
            return;
        }

        long age = System.currentTimeMillis() - lastSeen;
        if (age <= PRESENCE_TIMEOUT) {
            send(ex, "ONLINE");
        } else {
            presence.remove(userId);
            send(ex, "OFFLINE");
        }
    }

    private static void getForumPosts(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send(ex, "ONLY_GET");
            return;
        }

        StringBuilder body = new StringBuilder();

        synchronized (forumPosts) {
            for (ForumPost post : forumPosts) {
                if (post.deleted) {
                    continue;
                }

                body.append(post.id).append("|")
                        .append(post.ownerId).append("|")
                        .append(post.nick).append("|")
                        .append(post.title).append("|")
                        .append(post.message).append("|")
                        .append(post.anonymous ? "1" : "0")
                        .append("\n");
            }
        }

        if (body.length() == 0) {
            send(ex, "NO_FORUM_POSTS");
            return;
        }

        body.setLength(body.length() - 1);
        send(ex, body.toString());
    }

    private static void postForumPost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        Map<String, String> values = parseForm(readBody(ex));
        String ownerId = cleanId(values.get("userId"));
        String nick = cleanMessage(values.get("nick"));
        String title = cleanMessage(values.get("title"));
        String message = cleanMessage(values.get("message"));
        boolean anonymous = "1".equals(values.get("anonymous"))
                || "true".equalsIgnoreCase(values.get("anonymous"));

        if (ownerId.isEmpty() || nick.isEmpty() || title.isEmpty() || message.isEmpty()) {
            send(ex, "INVALID_FORUM_POST");
            return;
        }

        if (nick.length() > 40 || title.length() > 120 || message.length() > 5000) {
            send(ex, "FORUM_POST_TOO_LONG");
            return;
        }

        String id = "FP-" + forumCounter.getAndIncrement();
        ForumPost post = new ForumPost(id, ownerId, nick, title, message, anonymous);
        forumPosts.add(post);

        System.out.println("Forum post saved: " + id);
        send(ex, id);
    }

    private static void editForumPost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        Map<String, String> values = parseForm(readBody(ex));
        String id = cleanId(values.get("id"));
        String userId = cleanId(values.get("userId"));
        String title = cleanMessage(values.get("title"));
        String message = cleanMessage(values.get("message"));

        if (id.isEmpty() || userId.isEmpty() || title.isEmpty() || message.isEmpty()) {
            send(ex, "INVALID_FORUM_EDIT");
            return;
        }

        if (title.length() > 120 || message.length() > 5000) {
            send(ex, "FORUM_POST_TOO_LONG");
            return;
        }

        ForumPost post = findForumPost(id);
        if (post == null || post.deleted) {
            send(ex, "FORUM_POST_NOT_FOUND");
            return;
        }

        synchronized (post) {
            if (!post.ownerId.equals(userId)) {
                send(ex, "NOT_ALLOWED");
                return;
            }

            post.title = title;
            post.message = message;
        }

        send(ex, "FORUM_POST_EDITED");
    }

    private static void deleteForumPost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send(ex, "ONLY_POST");
            return;
        }

        Map<String, String> values = parseForm(readBody(ex));
        String id = cleanId(values.get("id"));
        String userId = cleanId(values.get("userId"));

        if (id.isEmpty() || userId.isEmpty()) {
            send(ex, "INVALID_FORUM_DELETE");
            return;
        }

        ForumPost post = findForumPost(id);
        if (post == null) {
            send(ex, "FORUM_POST_NOT_FOUND");
            return;
        }

        synchronized (post) {
            if (!post.ownerId.equals(userId)) {
                send(ex, "NOT_ALLOWED");
                return;
            }
            post.deleted = true;
        }

        send(ex, "FORUM_POST_DELETED");
    }

    private static ForumPost findForumPost(String id) {
        synchronized (forumPosts) {
            for (ForumPost post : forumPosts) {
                if (post.id.equals(id)) {
                    return post;
                }
            }
        }
        return null;
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

    private static String privateChatKey(String a, String b) {
        if (a.compareTo(b) < 0) {
            return "PRIVATE|" + a + "|" + b;
        }
        return "PRIVATE|" + b + "|" + a;
    }

    private static String getRoomFromQuery(String query) {
        Map<String, String> values = parseQuery(query);
        String room = values.get("room");
        if (room == null || room.trim().isEmpty()) {
            return "Community";
        }
        return cleanMessage(room);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String data) {
        return parseParameters(data);
    }

    private static Map<String, String> parseQuery(String data) {
        return parseParameters(data);
    }

    private static Map<String, String> parseParameters(String data) {
        Map<String, String> result = new HashMap<String, String>();

        if (data == null || data.isEmpty()) {
            return result;
        }

        String[] parts = data.split("&");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }

            int separator = part.indexOf('=');
            String key;
            String value;

            if (separator >= 0) {
                key = part.substring(0, separator);
                value = part.substring(separator + 1);
            } else {
                key = part;
                value = "";
            }

            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8.name());
                value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
            }

            result.put(key, value);
        }

        return result;
    }

    private static String cleanId(String value) {
        if (value == null) {
            return "";
        }

        String result = value.trim();
        result = result.replace("|", "");
        result = result.replace("\r", "");
        result = result.replace("\n", "");
        return result;
    }

    private static String cleanMessage(String value) {
        if (value == null) {
            return "";
        }

        String result = value.trim();
        result = result.replace("|", "");
        result = result.replace("\r", " ");
        result = result.replace("\n", " ");
        return result;
    }

    private static void send(HttpExchange ex, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);

        try (OutputStream output = ex.getResponseBody()) {
            output.write(bytes);
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
        }
    }

    private static class ForumPost {
        String id;
        String ownerId;
        String nick;
        String title;
        String message;
        boolean anonymous;
        boolean deleted;

        ForumPost(String id, String ownerId, String nick, String title, String message, boolean anonymous) {
            this.id = id;
            this.ownerId = ownerId;
            this.nick = nick;
            this.title = title;
            this.message = message;
            this.anonymous = anonymous;
        }
    }
}
