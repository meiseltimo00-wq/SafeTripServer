import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class SafeTripServer {
    private static final Map<String, List<String>> rooms = Collections.synchronizedMap(new HashMap<String, List<String>>());
    private static final Map<String, List<PrivateMessage>> privateChats = Collections.synchronizedMap(new HashMap<String, List<PrivateMessage>>());
    private static final Map<String, Long> presence = Collections.synchronizedMap(new HashMap<String, Long>());
    private static final List<ForumPost> forumPosts = Collections.synchronizedList(new ArrayList<ForumPost>());
    private static final List<ForumReply> forumReplies = Collections.synchronizedList(new ArrayList<ForumReply>());
    private static final Map<String, Map<String, Integer>> forumVotes = Collections.synchronizedMap(new HashMap<String, Map<String, Integer>>());
    private static final long PRESENCE_TIMEOUT = 15000L;
    private static final AtomicLong messageCounter = new AtomicLong(1);
    private static final AtomicLong forumCounter = new AtomicLong(1);
    private static final AtomicLong replyCounter = new AtomicLong(1);
    private static final Object DATA_LOCK = new Object();
    private static Path dataFile;

    public static void main(String[] args) throws Exception {
        dataFile = resolveDataFile();
        loadPersistentData();

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
        server.createContext("/forum-replies", SafeTripServer::getForumReplies);
        server.createContext("/forum-reply", SafeTripServer::postForumReply);
        server.createContext("/forum-vote", SafeTripServer::voteForumPost);
        server.setExecutor(null);
        server.start();
        System.out.println("SafeTrip Server läuft auf Port " + port);
        System.out.println("SafeTrip-Datenbank: " + dataFile.toAbsolutePath());
    }

    private static Path resolveDataFile() throws IOException {
        String configured = System.getenv("SAFETRIP_DATA_FILE");
        Path path;
        if (configured != null && !configured.trim().isEmpty()) {
            path = Paths.get(configured.trim());
        } else {
            String directory = System.getenv("SAFETRIP_DATA_DIR");
            if (directory != null && !directory.trim().isEmpty()) {
                path = Paths.get(directory.trim(), "safetrip-data.db");
            } else {
                path = Paths.get("/data", "safetrip-data.db");
            }
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        return path;
    }

    private static void getMessages(HttpExchange ex) throws IOException {
        String room = getRoomFromQuery(ex.getRequestURI().getQuery());
        List<String> messages = rooms.get(room);
        if (messages == null || messages.isEmpty()) { send(ex, "NO_MESSAGES"); return; }
        synchronized (messages) { send(ex, String.join("\n", messages)); }
    }

    private static void postMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String room = cleanMessage(values.get("room"));
        String message = cleanMessage(values.get("message"));
        if (room.isEmpty()) room = "Community";
        if (!message.isEmpty()) {
            List<String> messages = rooms.get(room);
            if (messages == null) { messages = Collections.synchronizedList(new ArrayList<String>()); rooms.put(room, messages); }
            messages.add(message);
        }
        send(ex, "MESSAGE_SAVED");
    }

    private static void postPrivateMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String sender = cleanId(values.get("sender"));
        String recipient = cleanId(values.get("recipient"));
        String senderNick = cleanMessage(values.get("senderNick"));
        String message = cleanMessage(values.get("message"));
        if (sender.isEmpty() || recipient.isEmpty() || message.isEmpty() || sender.equals(recipient)) { send(ex, "INVALID_PRIVATE_MESSAGE"); return; }
        if (senderNick.isEmpty()) senderNick = "Ich";
        String key = privateChatKey(sender, recipient);
        List<PrivateMessage> messages = privateChats.get(key);
        if (messages == null) { messages = Collections.synchronizedList(new ArrayList<PrivateMessage>()); privateChats.put(key, messages); }
        String id = "PM-" + messageCounter.getAndIncrement();
        messages.add(new PrivateMessage(id, sender, recipient, senderNick, message));
        send(ex, "PRIVATE_MESSAGE_SAVED");
    }

    private static void getPrivateMessages(HttpExchange ex) throws IOException {
        Map<String, String> values = parseQuery(ex.getRequestURI().getQuery());
        String sender = cleanId(values.get("sender"));
        String recipient = cleanId(values.get("recipient"));
        if (sender.isEmpty() || recipient.isEmpty() || sender.equals(recipient)) { send(ex, "INVALID_PRIVATE_CHAT"); return; }
        List<PrivateMessage> messages = privateChats.get(privateChatKey(sender, recipient));
        if (messages == null || messages.isEmpty()) { send(ex, "NO_MESSAGES"); return; }
        StringBuilder body = new StringBuilder();
        synchronized (messages) {
            for (PrivateMessage pm : messages) if (!pm.deleted) body.append(pm.id).append('|').append(pm.senderId).append('|').append(pm.senderNick).append('|').append(pm.text).append('\n');
        }
        if (body.length() == 0) { send(ex, "NO_MESSAGES"); return; }
        body.setLength(body.length() - 1); send(ex, body.toString());
    }

    private static void editPrivateMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String sender = cleanId(values.get("sender"));
        String id = cleanId(values.get("messageId"));
        String text = cleanMessage(values.get("message"));
        if (sender.isEmpty() || id.isEmpty() || text.isEmpty()) { send(ex, "INVALID_EDIT"); return; }
        PrivateMessage pm = findPrivateMessage(id);
        if (pm == null) { send(ex, "MESSAGE_NOT_FOUND"); return; }
        synchronized (pm) {
            if (!pm.senderId.equals(sender)) { send(ex, "NOT_ALLOWED"); return; }
            if (pm.deleted) { send(ex, "MESSAGE_DELETED"); return; }
            pm.text = text; pm.edited = true;
        }
        send(ex, "MESSAGE_EDITED");
    }

    private static void deletePrivateMessage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String sender = cleanId(values.get("sender"));
        String id = cleanId(values.get("messageId"));
        if (sender.isEmpty() || id.isEmpty()) { send(ex, "INVALID_DELETE"); return; }
        PrivateMessage pm = findPrivateMessage(id);
        if (pm == null) { send(ex, "MESSAGE_NOT_FOUND"); return; }
        synchronized (pm) {
            if (!pm.senderId.equals(sender)) { send(ex, "NOT_ALLOWED"); return; }
            pm.deleted = true;
        }
        send(ex, "MESSAGE_DELETED");
    }

    private static void presence(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equalsIgnoreCase("POST")) { setPresence(ex); return; }
        if (ex.getRequestMethod().equalsIgnoreCase("GET")) { getPresence(ex); return; }
        send(ex, "ONLY_GET_OR_POST");
    }

    private static void setPresence(HttpExchange ex) throws IOException {
        Map<String, String> values = parseForm(readBody(ex));
        String userId = cleanId(values.get("userId"));
        String status = cleanId(values.get("status"));
        if (userId.isEmpty()) { send(ex, "INVALID_USER"); return; }
        if (status.equalsIgnoreCase("online")) { presence.put(userId, System.currentTimeMillis()); send(ex, "PRESENCE_ONLINE"); return; }
        presence.remove(userId); send(ex, "PRESENCE_OFFLINE");
    }

    private static void getPresence(HttpExchange ex) throws IOException {
        String userId = cleanId(parseQuery(ex.getRequestURI().getQuery()).get("userId"));
        if (userId.isEmpty()) { send(ex, "INVALID_USER"); return; }
        Long lastSeen = presence.get(userId);
        if (lastSeen == null) { send(ex, "OFFLINE"); return; }
        if (System.currentTimeMillis() - lastSeen <= PRESENCE_TIMEOUT) send(ex, "ONLINE"); else { presence.remove(userId); send(ex, "OFFLINE"); }
    }

    private static void getForumPosts(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send(ex, "ONLY_GET"); return; }
        StringBuilder body = new StringBuilder();
        synchronized (forumPosts) {
            for (ForumPost post : forumPosts) if (!post.deleted) body.append(post.id).append('|').append(post.ownerId).append('|').append(post.nick).append('|').append(post.title).append('|').append(post.message).append('|').append(post.anonymous ? "1" : "0").append('\n');
        }
        if (body.length() == 0) { send(ex, "NO_FORUM_POSTS"); return; }
        body.setLength(body.length() - 1); send(ex, body.toString());
    }

    private static void postForumPost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String ownerId = cleanId(values.get("userId"));
        String nick = cleanMessage(values.get("nick"));
        String title = cleanMessage(values.get("title"));
        String message = cleanMessage(values.get("message"));
        boolean anonymous = "1".equals(values.get("anonymous")) || "true".equalsIgnoreCase(values.get("anonymous"));
        if (ownerId.isEmpty() || nick.isEmpty() || title.isEmpty() || message.isEmpty()) { send(ex, "INVALID_FORUM_POST"); return; }
        if (nick.length() > 40 || title.length() > 120 || message.length() > 5000) { send(ex, "FORUM_POST_TOO_LONG"); return; }
        String id = "FP-" + forumCounter.getAndIncrement();
        forumPosts.add(new ForumPost(id, ownerId, nick, title, message, anonymous));
        savePersistentData();
        send(ex, id);
    }

    private static void editForumPost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String id = cleanId(values.get("id")); String userId = cleanId(values.get("userId"));
        String title = cleanMessage(values.get("title")); String message = cleanMessage(values.get("message"));
        if (id.isEmpty() || userId.isEmpty() || title.isEmpty() || message.isEmpty()) { send(ex, "INVALID_FORUM_EDIT"); return; }
        if (title.length() > 120 || message.length() > 5000) { send(ex, "FORUM_POST_TOO_LONG"); return; }
        ForumPost post = findForumPost(id);
        if (post == null || post.deleted) { send(ex, "FORUM_POST_NOT_FOUND"); return; }
        synchronized (post) {
            if (!post.ownerId.equals(userId)) { send(ex, "NOT_ALLOWED"); return; }
            post.title = title; post.message = message;
        }
        savePersistentData();
        send(ex, "FORUM_POST_EDITED");
    }

    private static void deleteForumPost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String id = cleanId(values.get("id")); String userId = cleanId(values.get("userId"));
        if (id.isEmpty() || userId.isEmpty()) { send(ex, "INVALID_FORUM_DELETE"); return; }
        ForumPost post = findForumPost(id);
        if (post == null) { send(ex, "FORUM_POST_NOT_FOUND"); return; }
        synchronized (post) {
            if (!post.ownerId.equals(userId)) { send(ex, "NOT_ALLOWED"); return; }
            post.deleted = true;
        }
        savePersistentData();
        send(ex, "FORUM_POST_DELETED");
    }

    private static void getForumReplies(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send(ex, "ONLY_GET"); return; }
        String postId = cleanId(parseQuery(ex.getRequestURI().getQuery()).get("postId"));
        if (postId.isEmpty()) { send(ex, "INVALID_FORUM_POST"); return; }
        if (findForumPost(postId) == null) { send(ex, "FORUM_POST_NOT_FOUND"); return; }
        StringBuilder body = new StringBuilder();
        synchronized (forumReplies) {
            for (ForumReply reply : forumReplies) if (reply.postId.equals(postId) && !reply.deleted) body.append(reply.id).append('|').append(reply.ownerId).append('|').append(reply.nick).append('|').append(reply.message).append('|').append(reply.anonymous ? "1" : "0").append('\n');
        }
        if (body.length() == 0) { send(ex, "NO_FORUM_REPLIES"); return; }
        body.setLength(body.length() - 1); send(ex, body.toString());
    }

    private static void postForumReply(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String postId = cleanId(values.get("postId")); String ownerId = cleanId(values.get("userId"));
        String nick = cleanMessage(values.get("nick")); String message = cleanMessage(values.get("message"));
        boolean anonymous = "1".equals(values.get("anonymous")) || "true".equalsIgnoreCase(values.get("anonymous"));
        if (postId.isEmpty() || ownerId.isEmpty() || nick.isEmpty() || message.isEmpty()) { send(ex, "INVALID_FORUM_REPLY"); return; }
        if (nick.length() > 40 || message.length() > 3000) { send(ex, "FORUM_REPLY_TOO_LONG"); return; }
        ForumPost post = findForumPost(postId);
        if (post == null || post.deleted) { send(ex, "FORUM_POST_NOT_FOUND"); return; }
        String id = "FR-" + replyCounter.getAndIncrement();
        forumReplies.add(new ForumReply(id, postId, ownerId, nick, message, anonymous));
        savePersistentData();
        send(ex, id);
    }

    private static void voteForumPost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, "ONLY_POST"); return; }
        Map<String, String> values = parseForm(readBody(ex));
        String postId = cleanId(values.get("postId"));
        String userId = cleanId(values.get("userId"));
        String voteValue = cleanId(values.get("vote"));
        if (postId.isEmpty() || userId.isEmpty()) { send(ex, "INVALID_FORUM_VOTE"); return; }
        int vote;
        try { vote = Integer.parseInt(voteValue); } catch (Exception e) { send(ex, "INVALID_FORUM_VOTE"); return; }
        if (vote != -1 && vote != 0 && vote != 1) { send(ex, "INVALID_FORUM_VOTE"); return; }
        ForumPost post = findForumPost(postId);
        if (post == null || post.deleted) { send(ex, "FORUM_POST_NOT_FOUND"); return; }
        synchronized (forumVotes) {
            Map<String, Integer> votes = forumVotes.get(postId);
            if (votes == null) { votes = new HashMap<String, Integer>(); forumVotes.put(postId, votes); }
            if (vote == 0) votes.remove(userId); else votes.put(userId, vote);
            rebuildForumVoteCountsLocked();
        }
        savePersistentData();
        send(ex, Integer.toString(post.score));
    }

    private static ForumPost findForumPost(String id) {
        synchronized (forumPosts) { for (ForumPost post : forumPosts) if (post.id.equals(id)) return post; }
        return null;
    }

    private static PrivateMessage findPrivateMessage(String id) {
        synchronized (privateChats) {
            for (List<PrivateMessage> list : privateChats.values()) synchronized (list) {
                for (PrivateMessage pm : list) if (pm.id.equals(id)) return pm;
            }
        }
        return null;
    }

    private static String privateChatKey(String a, String b) { return a.compareTo(b) < 0 ? "PRIVATE|" + a + "|" + b : "PRIVATE|" + b + "|" + a; }
    private static String getRoomFromQuery(String query) { String room = cleanMessage(parseQuery(query).get("room")); return room.isEmpty() ? "Community" : room; }
    private static String readBody(HttpExchange ex) throws IOException { return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); }
    private static Map<String, String> parseForm(String data) { return parseParameters(data); }
    private static Map<String, String> parseQuery(String data) { return parseParameters(data == null ? "" : data); }

    private static Map<String, String> parseParameters(String data) {
        Map<String, String> result = new HashMap<String, String>();
        if (data == null || data.isEmpty()) return result;
        for (String part : data.split("&")) {
            if (part.isEmpty()) continue;
            int p = part.indexOf('=');
            if (p < 0) continue;
            try {
                result.put(URLDecoder.decode(part.substring(0, p), "UTF-8"), URLDecoder.decode(part.substring(p + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static String cleanId(String value) { if (value == null) return ""; return value.replace("|", "_").replace("\n", "_").replace("\r", "_").trim(); }
    private static String cleanMessage(String value) { if (value == null) return ""; return value.replace("|", " ").replace("\r", " ").replace("\n", " ").trim(); }

    private static void send(HttpExchange ex, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        OutputStream out = ex.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private static void savePersistentData() {
        synchronized (DATA_LOCK) {
            if (dataFile == null) return;
            try {
                Path absolute = dataFile.toAbsolutePath();
                Path parent = absolute.getParent();
                if (parent != null) Files.createDirectories(parent);

                StringBuilder data = new StringBuilder();
                data.append("SAFETRIP_DATA_V1\n");
                data.append("COUNTERS|").append(messageCounter.get()).append('|').append(forumCounter.get()).append('|').append(replyCounter.get()).append('\n');

                synchronized (forumPosts) {
                    for (ForumPost post : forumPosts) {
                        data.append("POST|").append(encode(post.id)).append('|').append(encode(post.ownerId)).append('|').append(encode(post.nick)).append('|').append(encode(post.title)).append('|').append(encode(post.message)).append('|').append(post.anonymous ? '1' : '0').append('|').append(post.deleted ? '1' : '0').append('\n');
                    }
                }
                synchronized (forumReplies) {
                    for (ForumReply reply : forumReplies) {
                        data.append("REPLY|").append(encode(reply.id)).append('|').append(encode(reply.postId)).append('|').append(encode(reply.ownerId)).append('|').append(encode(reply.nick)).append('|').append(encode(reply.message)).append('|').append(reply.anonymous ? '1' : '0').append('|').append(reply.deleted ? '1' : '0').append('\n');
                    }
                }
                synchronized (forumVotes) {
                    for (Map.Entry<String, Map<String, Integer>> postEntry : forumVotes.entrySet()) {
                        for (Map.Entry<String, Integer> voteEntry : postEntry.getValue().entrySet()) {
                            data.append("VOTE|").append(encode(postEntry.getKey())).append('|').append(encode(voteEntry.getKey())).append('|').append(voteEntry.getValue()).append('\n');
                        }
                    }
                }

                Path temp = absolute.resolveSibling(absolute.getFileName().toString() + ".tmp");
                Files.write(temp, data.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception ignored) {
                    Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                System.err.println("Fehler beim Speichern der SafeTrip-Daten: " + e.getMessage());
            }
        }
    }

    private static void loadPersistentData() {
        synchronized (DATA_LOCK) {
            if (dataFile == null || !Files.exists(dataFile)) return;
            try {
                List<String> lines = Files.readAllLines(dataFile, StandardCharsets.UTF_8);
                long maxForum = 0;
                long maxReply = 0;
                synchronized (forumPosts) { forumPosts.clear(); }
                synchronized (forumReplies) { forumReplies.clear(); }
                synchronized (forumVotes) { forumVotes.clear(); }

                for (String line : lines) {
                    if (line == null || line.isEmpty()) continue;
                    String[] p = line.split("\\|", -1);
                    if (p.length == 0) continue;
                    if ("COUNTERS".equals(p[0]) && p.length >= 4) {
                        messageCounter.set(Math.max(1, parseLong(p[1])));
                        forumCounter.set(Math.max(1, parseLong(p[2])));
                        replyCounter.set(Math.max(1, parseLong(p[3])));
                    } else if ("POST".equals(p[0]) && p.length >= 8) {
                        ForumPost post = new ForumPost(decode(p[1]), decode(p[2]), decode(p[3]), decode(p[4]), decode(p[5]), "1".equals(p[6]));
                        post.deleted = "1".equals(p[7]);
                        forumPosts.add(post);
                        maxForum = Math.max(maxForum, parseNumericId(post.id, "FP-"));
                    } else if ("REPLY".equals(p[0]) && p.length >= 8) {
                        ForumReply reply = new ForumReply(decode(p[1]), decode(p[2]), decode(p[3]), decode(p[4]), decode(p[5]), "1".equals(p[6]));
                        reply.deleted = "1".equals(p[7]);
                        forumReplies.add(reply);
                        maxReply = Math.max(maxReply, parseNumericId(reply.id, "FR-"));
                    } else if ("VOTE".equals(p[0]) && p.length >= 4) {
                        int vote = parseInt(p[3]);
                        if (vote == -1 || vote == 1) {
                            Map<String, Integer> votes = forumVotes.get(decode(p[1]));
                            if (votes == null) { votes = new HashMap<String, Integer>(); forumVotes.put(decode(p[1]), votes); }
                            votes.put(decode(p[2]), vote);
                        }
                    }
                }

                forumCounter.set(Math.max(forumCounter.get(), maxForum + 1));
                replyCounter.set(Math.max(replyCounter.get(), maxReply + 1));
                rebuildForumVoteCounts();
                System.out.println("SafeTrip-Daten geladen: " + forumPosts.size() + " Beiträge, " + forumReplies.size() + " Antworten.");
            } catch (Exception e) {
                System.err.println("Fehler beim Laden der SafeTrip-Daten: " + e.getMessage());
            }
        }
    }

    private static void rebuildForumVoteCounts() {
        synchronized (forumVotes) { rebuildForumVoteCountsLocked(); }
    }

    private static void rebuildForumVoteCountsLocked() {
        synchronized (forumPosts) {
            for (ForumPost post : forumPosts) {
                int score = 0;
                Map<String, Integer> votes = forumVotes.get(post.id);
                if (votes != null) for (Integer value : votes.values()) if (value != null) score += value;
                post.score = score;
            }
        }
    }

    private static String encode(String value) {
        if (value == null) return "";
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try { return new String(java.util.Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    private static long parseLong(String value) { try { return Long.parseLong(value); } catch (Exception e) { return 0L; } }
    private static int parseInt(String value) { try { return Integer.parseInt(value); } catch (Exception e) { return 0; } }
    private static long parseNumericId(String id, String prefix) { if (id == null) return 0L; return parseLong(id.startsWith(prefix) ? id.substring(prefix.length()) : id); }

    private static class PrivateMessage {
        String id, senderId, recipientId, senderNick, text;
        boolean edited, deleted;
        PrivateMessage(String id, String senderId, String recipientId, String senderNick, String text) {
            this.id = id; this.senderId = senderId; this.recipientId = recipientId; this.senderNick = senderNick; this.text = text;
        }
    }

    private static class ForumPost {
        String id, ownerId, nick, title, message;
        boolean anonymous, deleted;
        int score;
        ForumPost(String id, String ownerId, String nick, String title, String message, boolean anonymous) {
            this.id = id; this.ownerId = ownerId; this.nick = nick; this.title = title; this.message = message; this.anonymous = anonymous;
        }
    }

    private static class ForumReply {
        String id, postId, ownerId, nick, message;
        boolean anonymous, deleted;
        ForumReply(String id, String postId, String ownerId, String nick, String message, boolean anonymous) {
            this.id = id; this.postId = postId; this.ownerId = ownerId; this.nick = nick; this.message = message; this.anonymous = anonymous;
        }
    }
}
