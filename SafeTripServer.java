import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
 
import java.io.BufferedReader;
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
            List<ForumPost> sorted = new ArrayList<ForumPost>(forumPosts);
            Collections.sort(sorted, new Comparator<ForumPost>() {
                @Override public int compare(ForumPost a, ForumPost b) { return Long.compare(extractNumber(b.id), extractNumber(a.id)); }
            });
            for (ForumPost post : sorted) if (!post.deleted) body.append(post.id).append('|').append(post.ownerId).append('|').append(post.nick).append('|').append(post.title).append('|').append(post.message).append('|').append(post.anonymous ? "1" : "0").append('|').append(post.upVotes).append('|').append(post.downVotes).append('|').append(countReplies(post.id)).append('\n');
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
            List<ForumReply> sorted = new ArrayList<ForumReply>();
            for (ForumReply reply : forumReplies) if (reply.postId.equals(postId) && !reply.deleted) sorted.add(reply);
            Collections.sort(sorted, new Comparator<ForumReply>() {
                @Override public int compare(ForumReply a, ForumReply b) { return Long.compare(extractNumber(b.id), extractNumber(a.id)); }
            });
            for (ForumReply reply : sorted) body.append(reply.id).append('|').append(reply.ownerId).append('|').append(reply.nick).append('|').append(reply.message).append('|').append(reply.anonymous ? "1" : "0").append('\n');
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
        String postId = cleanId(values.get("postId")); String userId = cleanId(values.get("userId"));
        String vote = cleanId(values.get("vote"));
        if (postId.isEmpty() || userId.isEmpty() || (!vote.equals("up") && !vote.equals("down") && !vote.equals("none"))) { send(ex, "INVALID_FORUM_VOTE"); return; }
        ForumPost post = findForumPost(postId);
        if (post == null || post.deleted) { send(ex, "FORUM_POST_NOT_FOUND"); return; }
        synchronized (forumVotes) {
            Map<String, Integer> users = forumVotes.get(postId);
            if (users == null) { users = new HashMap<String, Integer>(); forumVotes.put(postId, users); }
            Integer old = users.get(userId);
            if (old != null) {
                if (old == 1) post.upVotes--;
                if (old == -1) post.downVotes--;
            }
            if (vote.equals("up")) { users.put(userId, 1); post.upVotes++; }
            else if (vote.equals("down")) { users.put(userId, -1); post.downVotes++; }
            else users.remove(userId);
            if (users.isEmpty()) forumVotes.remove(postId);
        }
        savePersistentData();
        send(ex, post.upVotes + "|" + post.downVotes);
    }

    private static ForumPost findForumPost(String id) {
        synchronized (forumPosts) { for (ForumPost post : forumPosts) if (post.id.equals(id)) return post; }
        return null;
    }

    private static PrivateMessage findPrivateMessage(String id) {
        synchronized (privateChats) {
            for (List<PrivateMessage> list : privateChats.values()) synchronized (list) { for (PrivateMessage pm : list) if (pm.id.equals(id)) return pm; }
        }
        return null;
    }

    private static int countReplies(String postId) {
        int count = 0;
        synchronized (forumReplies) { for (ForumReply reply : forumReplies) if (reply.postId.equals(postId) && !reply.deleted) count++; }
        return count;
    }

    private static long extractNumber(String id) {
        if (id == null) return 0L;
        int dash = id.lastIndexOf('-');
        if (dash < 0) return 0L;
        try { return Long.parseLong(id.substring(dash + 1)); } catch (Exception e) { return 0L; }
    }

    private static void savePersistentData() {
        if (dataFile == null) return;
        synchronized (DATA_LOCK) {
            try {
                Path parent = dataFile.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                List<String> lines = new ArrayList<String>();
                lines.add("VERSION|2");
                lines.add("COUNTERS|" + messageCounter.get() + "|" + forumCounter.get() + "|" + replyCounter.get());
                synchronized (forumPosts) {
                    for (ForumPost p : forumPosts) lines.add("POST|" + enc(p.id) + "|" + enc(p.ownerId) + "|" + enc(p.nick) + "|" + enc(p.title) + "|" + enc(p.message) + "|" + (p.anonymous ? "1" : "0") + "|" + (p.deleted ? "1" : "0") + "|" + p.upVotes + "|" + p.downVotes);
                }
                synchronized (forumReplies) {
                    for (ForumReply r : forumReplies) lines.add("REPLY|" + enc(r.id) + "|" + enc(r.postId) + "|" + enc(r.ownerId) + "|" + enc(r.nick) + "|" + enc(r.message) + "|" + (r.anonymous ? "1" : "0") + "|" + (r.deleted ? "1" : "0"));
                }
                synchronized (forumVotes) {
                    for (Map.Entry<String, Map<String, Integer>> entry : forumVotes.entrySet()) {
                        for (Map.Entry<String, Integer> vote : entry.getValue().entrySet()) lines.add("VOTE|" + enc(entry.getKey()) + "|" + enc(vote.getKey()) + "|" + vote.getValue());
                    }
                }
                Path temp = Paths.get(dataFile.toString() + ".tmp");
                Files.write(temp, lines, StandardCharsets.UTF_8);
                try { Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (Exception e) { Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING); }
            } catch (Exception e) { System.out.println("Speichern fehlgeschlagen: " + e.getMessage()); }
        }
    }

    private static void loadPersistentData() {
        if (dataFile == null || !Files.exists(dataFile)) return;
        synchronized (DATA_LOCK) {
            try {
                List<String> lines = Files.readAllLines(dataFile, StandardCharsets.UTF_8);
                synchronized (forumPosts) { forumPosts.clear(); }
                synchronized (forumReplies) { forumReplies.clear(); }
                synchronized (forumVotes) { forumVotes.clear(); }
                long maxMessage = 0L, maxForum = 0L, maxReply = 0L;
                for (String line : lines) {
                    if (line.startsWith("COUNTERS|")) {
                        String[] p = line.split("\\|", -1);
                        if (p.length >= 4) {
                            try { messageCounter.set(Long.parseLong(p[1])); } catch (Exception ignored) { }
                            try { forumCounter.set(Long.parseLong(p[2])); } catch (Exception ignored) { }
                            try { replyCounter.set(Long.parseLong(p[3])); } catch (Exception ignored) { }
                        }
                    } else if (line.startsWith("POST|")) {
                        String[] p = line.split("\\|", -1);
                        if (p.length >= 10) {
                            ForumPost post = new ForumPost(dec(p[1]), dec(p[2]), dec(p[3]), dec(p[4]), dec(p[5]), "1".equals(p[6]));
                            post.deleted = "1".equals(p[7]);
                            try { post.upVotes = Integer.parseInt(p[8]); } catch (Exception ignored) { }
                            try { post.downVotes = Integer.parseInt(p[9]); } catch (Exception ignored) { }
                            forumPosts.add(post); maxForum = Math.max(maxForum, extractNumber(post.id));
                        }
                    } else if (line.startsWith("REPLY|")) {
                        String[] p = line.split("\\|", -1);
                        if (p.length >= 8) {
                            ForumReply reply = new ForumReply(dec(p[1]), dec(p[2]), dec(p[3]), dec(p[4]), dec(p[5]), "1".equals(p[6]));
                            reply.deleted = "1".equals(p[7]);
                            forumReplies.add(reply); maxReply = Math.max(maxReply, extractNumber(reply.id));
                        }
                    } else if (line.startsWith("VOTE|")) {
                        String[] p = line.split("\\|", -1);
                        if (p.length >= 4) {
                            Map<String, Integer> users = forumVotes.get(dec(p[1]));
                            if (users == null) { users = new HashMap<String, Integer>(); forumVotes.put(dec(p[1]), users); }
                            try { users.put(dec(p[2]), Integer.parseInt(p[3])); } catch (Exception ignored) { }
                        }
                    }
                }
                if (forumCounter.get() <= maxForum) forumCounter.set(maxForum + 1);
                if (replyCounter.get() <= maxReply) replyCounter.set(maxReply + 1);
                if (messageCounter.get() <= maxMessage) messageCounter.set(maxMessage + 1);
                rebuildForumVoteCounts();
            } catch (Exception e) { System.out.println("Laden fehlgeschlagen: " + e.getMessage()); }
        }
    }

    private static void rebuildForumVoteCounts() {
        synchronized (forumPosts) { for (ForumPost post : forumPosts) { post.upVotes = 0; post.downVotes = 0; } }
        synchronized (forumVotes) {
            for (Map.Entry<String, Map<String, Integer>> entry : forumVotes.entrySet()) {
                ForumPost post = findForumPost(entry.getKey());
                if (post == null) continue;
                for (Integer vote : entry.getValue().values()) {
                    if (vote != null && vote == 1) post.upVotes++;
                    else if (vote != null && vote == -1) post.downVotes++;
                }
            }
        }
    }

    private static String enc(String value) {
        if (value == null) value = "";
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String value) {
        if (value == null || value.isEmpty()) return "";
        try { return new String(java.util.Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (Exception e) { return value; }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }

    private static Map<String, String> parseForm(String body) {
        return parseQuery(body);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<String, String>();
        if (query == null || query.isEmpty()) return result;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            try { key = URLDecoder.decode(key, "UTF-8"); value = URLDecoder.decode(value, "UTF-8"); } catch (Exception ignored) { }
            result.put(key, value);
        }
        return result;
    }

    private static String getRoomFromQuery(String query) {
        String room = cleanMessage(parseQuery(query).get("room"));
        return room.isEmpty() ? "Community" : room;
    }

    private static String privateChatKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private static String cleanId(String value) {
        if (value == null) return "";
        return value.trim().replace("|", "").replace("\n", "").replace("\r", "");
    }

    private static String cleanMessage(String value) {
        if (value == null) return "";
        return value.replace("|", " ").replace("\r", "").replace("\n", " ").trim();
    }

    private static void send(HttpExchange ex, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }

    private static class PrivateMessage {
        final String id;
        final String senderId;
        final String recipientId;
        final String senderNick;
        String text;
        boolean edited;
        boolean deleted;
        PrivateMessage(String id, String senderId, String recipientId, String senderNick, String text) {
            this.id = id; this.senderId = senderId; this.recipientId = recipientId; this.senderNick = senderNick; this.text = text;
        }
    }

    private static class ForumPost {
        final String id;
        final String ownerId;
        final String nick;
        String title;
        String message;
        final boolean anonymous;
        boolean deleted;
        int upVotes;
        int downVotes;
        ForumPost(String id, String ownerId, String nick, String title, String message, boolean anonymous) {
            this.id = id; this.ownerId = ownerId; this.nick = nick; this.title = title; this.message = message; this.anonymous = anonymous;
        }
    }

    private static class ForumReply {
        final String id;
        final String postId;
        final String ownerId;
        final String nick;
        final String message;
        final boolean anonymous;
        boolean deleted;
        ForumReply(String id, String postId, String ownerId, String nick, String message, boolean anonymous) {
            this.id = id; this.postId = postId; this.ownerId = ownerId; this.nick = nick; this.message = message; this.anonymous = anonymous;
        }
    }
}
