package dumb.note;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Nostr {
    private static final Logger logger = LoggerFactory.getLogger(Nostr.class);
    private final Netention.Config.NostrSettings cfg;
    private final Netention.Core coreRef;
    private final List<RelayConnection> relays = new CopyOnWriteArrayList<>();
    private final Queue<NostrAction> queue = new ConcurrentLinkedQueue<>();
    private final Consumer<NostrEvent> rawEventConsumer;
    private final HttpClient http;
    private byte[] privateKeyRaw;
    private String publicKeyXOnlyHex;
    private volatile boolean enabled = false;

    public Nostr(Netention.Config cs, Netention.Core core, Consumer<NostrEvent> rawEventConsumer, Supplier<String> selfNpubSupplier) {
        this.cfg = cs.net;
        this.coreRef = core;
        this.rawEventConsumer = rawEventConsumer;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        loadIdentity();
    }

    public void loadIdentity() {
        if (cfg.privateKeyBech32 == null || cfg.privateKeyBech32.isEmpty()) {
            logger.warn("Nostr private key (nsec) not configured.");
            this.privateKeyRaw = null;
            this.publicKeyXOnlyHex = null;
            cfg.publicKeyBech32 = "";
            return;
        }
        try {
            this.privateKeyRaw = Crypto.Bech32.nip19Decode(cfg.privateKeyBech32);
            var pubKeyXOnlyRaw = Crypto.getPublicKeyXOnly(this.privateKeyRaw);
            this.publicKeyXOnlyHex = Crypto.bytesToHex(pubKeyXOnlyRaw);
            cfg.publicKeyBech32 = Crypto.Bech32.nip19Encode("npub", pubKeyXOnlyRaw);
            logger.info("Nostr identity loaded for pubkey: {}", cfg.publicKeyBech32);
        } catch (Exception e) {
            logger.error("Failed to load Nostr identity from nsec: {}. Nostr unavailable.", e.getMessage(), e);
            this.privateKeyRaw = null;
            this.publicKeyXOnlyHex = null;
            cfg.publicKeyBech32 = "";
        }
    }

    public String getPrivateKeyBech32() {
        return cfg.privateKeyBech32;
    }

    public String getPublicKeyBech32() {
        return cfg.publicKeyBech32;
    }

    public String getPublicKeyXOnlyHex() {
        return publicKeyXOnlyHex;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean shouldEnable) {
        if (shouldEnable == this.enabled) return;
        if (shouldEnable) {
            loadIdentity();
            if (privateKeyRaw == null || publicKeyXOnlyHex == null) {
                logger.error("Cannot enable Nostr: identity not loaded/invalid.");
                this.enabled = false;
                return;
            }
            connectToRelays();
            processQueue();
        } else {
            disconnectFromRelays();
        }
        this.enabled = shouldEnable;
        logger.info("Nostr service {}", this.enabled ? "enabled" : "disabled");
    }

    private void connectToRelays() {
        disconnectFromRelays();
        var relayNotes = coreRef.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.NOSTR_RELAY.value));
        if (relayNotes.isEmpty()) {
            logger.warn("No Nostr relay notes found. Creating default relays.");
            Stream.of("wss://relay.damus.io", "wss://nos.lol").forEach(url -> {
                var relayNote = new Netention.Note("Relay: " + url, "");
                relayNote.tags.add(Netention.Note.SystemTag.NOSTR_RELAY.value);
                relayNote.content.putAll(Map.of(Netention.Note.ContentKey.RELAY_URL.getKey(), url, Netention.Note.ContentKey.RELAY_ENABLED.getKey(), true, Netention.Note.ContentKey.RELAY_READ.getKey(), true, Netention.Note.ContentKey.RELAY_WRITE.getKey(), true));
                coreRef.saveNote(relayNote);
            });
            relayNotes = coreRef.notes.getAll(n -> n.tags.contains(Netention.Note.SystemTag.NOSTR_RELAY.value));
        }
        relayNotes.stream()
                .filter(rn -> (Boolean) rn.content.getOrDefault(Netention.Note.ContentKey.RELAY_ENABLED.getKey(), true))
                .forEach(relayNote -> {
                    var relayUrl = (String) relayNote.content.get(Netention.Note.ContentKey.RELAY_URL.getKey());
                    boolean canRead = (Boolean) relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_READ.getKey(), true);
                    boolean canWrite = (Boolean) relayNote.content.getOrDefault(Netention.Note.ContentKey.RELAY_WRITE.getKey(), true);
                    if (relayUrl != null && !relayUrl.isEmpty()) {
                        try {
                            var conn = new RelayConnection(URI.create(relayUrl), http, this::handleRelayMessage, this.publicKeyXOnlyHex, canRead, canWrite);
                            conn.connect();
                            relays.add(conn);
                        } catch (Exception e) {
                            logger.error("Failed to initiate connection to relay {}: {}", relayUrl, e.getMessage());
                        }
                    }
                });
    }

    private void disconnectFromRelays() {
        relays.forEach(RelayConnection::close);
        relays.clear();
        logger.info("All relay connections closed.");
    }

    private void handleRelayMessage(String relayUri, String message) {
        try {
            var parsedMessage = NostrUtil.fromJson(message, new TypeReference<List<Object>>() {
            });
            if (parsedMessage.isEmpty()) {
                logger.warn("Relay {}: RX empty message array.", relayUri);
                return;
            }
            var type = (String) parsedMessage.get(0);
            switch (type) {
                case "EVENT" -> {
                    if (parsedMessage.size() >= 3 && parsedMessage.get(2) instanceof Map eventMap) {
                        try {
                            var nostrEvent = mapToNostrEvent(eventMap);
                            logger.debug("Relay {}: RX EVENT for sub_id '{}'. Kind: {}, ID: {}, Pubkey: {}", relayUri, parsedMessage.get(1), nostrEvent.kind, nostrEvent.id.substring(0, 8), nostrEvent.pubkey.substring(0, 8));
                            if (rawEventConsumer != null) rawEventConsumer.accept(nostrEvent);
                        } catch (Exception mapEx) {
                            logger.error("Relay {}: Failed to map/process Nostr EVENT for sub_id '{}': {}. Raw: {}", relayUri, parsedMessage.get(1), mapEx.getMessage(), eventMap, mapEx);
                        }
                    } else logger.warn("Relay {}: RX malformed EVENT message: {}", relayUri, message);
                }
                case "NOTICE" ->
                        logger.warn("Relay {}: RX NOTICE: {}", relayUri, parsedMessage.size() >= 2 ? parsedMessage.get(1) : message);
                case "EOSE" ->
                        logger.info("Relay {}: RX EOSE for sub_id '{}'", relayUri, parsedMessage.size() >= 2 ? parsedMessage.get(1) : "N/A");
                case "OK" -> {
                    var eventId = parsedMessage.size() > 1 ? (String) parsedMessage.get(1) : "N/A";
                    var success = parsedMessage.size() > 2 && Boolean.TRUE.equals(parsedMessage.get(2));
                    var okMessage = parsedMessage.size() > 3 ? (String) parsedMessage.get(3) : "";
                    logger.info("Relay {}: RX OK for event_id '{}'. Success: {}. Message: '{}'", relayUri, eventId.substring(0, Math.min(eventId.length(), 8)), success, okMessage);
                }
                default ->
                        logger.debug("Relay {}: RX unhandled/unknown message. Type: '{}', Full: {}", relayUri, type, message.substring(0, Math.min(message.length(), 100)));
            }
        } catch (Exception e) {
            logger.error("Error processing message from relay {}: {}", relayUri, message, e);
        }
    }

    @SuppressWarnings("unchecked")
    private NostrEvent mapToNostrEvent(Map<String, Object> m) {
        var e = new NostrEvent();
        e.id = (String) m.get("id");
        e.pubkey = (String) m.get("pubkey");
        e.created_at = ((Number) m.get("created_at")).longValue();
        e.kind = ((Number) m.get("kind")).intValue();
        e.content = (String) m.get("content");
        e.sig = (String) m.get("sig");
        if (m.get("tags") instanceof List) {
            ((List<?>) m.get("tags")).forEach(tagObj -> {
                if (tagObj instanceof List<?> tagListAsList)
                    e.tags.add(tagListAsList.stream().map(Object::toString).collect(Collectors.toList()));
            });
        }
        return e;
    }

    public void queueAction(NostrAction a) {
        queue.add(a);
        if (enabled) processQueue();
    }

    private void processQueue() {
        if (!enabled || privateKeyRaw == null) return;
        NostrAction action;
        while ((action = queue.poll()) != null) {
            try {
                switch (action.t) {
                    case PUBLISH_NOTE -> publishNoteInternal((Netention.Note) action.p);
                    case SEND_DM -> {
                        @SuppressWarnings("unchecked") var dmParams = (Map<String, String>) action.p;
                        sendDirectMessageInternal(dmParams.get("recipientNpub"), dmParams.get("message"));
                    }
                    case PUBLISH_PROFILE -> publishProfileInternal((Netention.Note) action.p);
                }
            } catch (Exception e) {
                logger.error("Error processing Nostr action {} from queue: {}", action.t, e.getMessage(), e);
            }
        }
    }

    private void publishNoteInternal(Netention.Note note) throws GeneralSecurityException, JsonProcessingException {
        var e = new NostrEvent();
        e.pubkey = this.publicKeyXOnlyHex;
        e.created_at = Instant.now().getEpochSecond();
        e.kind = 1;
        e.content = note.getTitle() + "\n\n" + note.getText();
        note.tags.stream().filter(t -> {
                    if (Stream.of(Netention.Note.SystemTag.values()).noneMatch(st -> st.value.equals(t))) return true;
                    return t.equals(Netention.Note.SystemTag.NOSTR_FEED.value);
                })
                .forEach(t -> e.tags.add(List.of("t", t)));
        e.sign(this.privateKeyRaw, Crypto.generateAuxRand());
        broadcastToRelays(NostrUtil.toJson(List.of("EVENT", e)));
        logger.info("Published Note (Kind 1): {}", e.id.substring(0, 8));
    }

    private void sendDirectMessageInternal(String recipientNpub, String message) throws Exception {
        var e = new NostrEvent();
        e.pubkey = this.publicKeyXOnlyHex;
        e.created_at = Instant.now().getEpochSecond();
        e.kind = 4;
        var recipientXOnlyBytes = Crypto.Bech32.nip19Decode(recipientNpub);
        e.content = Crypto.nip04Encrypt(message, Crypto.getSharedSecretWithRetry(this.privateKeyRaw, recipientXOnlyBytes), recipientXOnlyBytes);
        e.tags.add(List.of("p", Crypto.bytesToHex(recipientXOnlyBytes)));
        e.sign(this.privateKeyRaw, Crypto.generateAuxRand());
        broadcastToRelays(NostrUtil.toJson(List.of("EVENT", e)));
        logger.info("Sent DM (Kind 4) to {}: {}", recipientNpub.substring(0, 8), e.id.substring(0, 8));
    }

    private void publishProfileInternal(Netention.Note profileNote) throws GeneralSecurityException, JsonProcessingException {
        if (!profileNote.tags.contains(Netention.Note.SystemTag.MY_PROFILE.value)) {
            logger.warn("Attempted to publish non-profile note {} as profile.", profileNote.id);
            return;
        }
        var e = new NostrEvent();
        e.pubkey = this.publicKeyXOnlyHex;
        e.created_at = Instant.now().getEpochSecond();
        e.kind = 0;
        e.content = NostrUtil.toJson(Map.of(
                "name", (String) profileNote.content.getOrDefault(Netention.Note.ContentKey.PROFILE_NAME.getKey(), ""),
                "about", (String) profileNote.content.getOrDefault(Netention.Note.ContentKey.PROFILE_ABOUT.getKey(), ""),
                "picture", (String) profileNote.content.getOrDefault(Netention.Note.ContentKey.PROFILE_PICTURE_URL.getKey(), "")
        ));
        e.sign(this.privateKeyRaw, Crypto.generateAuxRand());
        broadcastToRelays(NostrUtil.toJson(List.of("EVENT", e)));
        logger.info("Published Profile (Kind 0): {}", e.id.substring(0, 8));
        profileNote.meta.put(Netention.Note.Metadata.PROFILE_LAST_UPDATED_AT.key, Instant.now().toString());
        coreRef.saveNote(profileNote);
    }

    private void broadcastToRelays(String jsonMessage) {
        logger.debug("Broadcasting to relays: {}", jsonMessage.substring(0, Math.min(jsonMessage.length(), 100)));
        relays.stream().filter(RelayConnection::canWrite).forEach(rc -> rc.send(jsonMessage));
    }

    public void publishNote(Netention.Note n) {
        queueAction(new NostrAction(NostrActionType.PUBLISH_NOTE, n));
    }

    public void sendDirectMessage(String recipientNpub, String message) {
        queueAction(new NostrAction(NostrActionType.SEND_DM, Map.of("recipientNpub", recipientNpub, "message", message)));
    }

    public void sendFriendRequest(String recipientNpub) {
        sendDirectMessage(recipientNpub, "Hello! I'd like to connect on Netention.");
    }

    public void publishProfile(Netention.Note profileNote) {
        queueAction(new NostrAction(NostrActionType.PUBLISH_PROFILE, profileNote));
    }

    public void requestSync() {
        //TODO
    }

    public int getConnectedRelayCount() {
        return (int) relays.stream().filter(r -> r.connected).count();
    }
    public int getConfiguredRelayCount() {
        return relays.size();
    }

    private enum NostrActionType {PUBLISH_NOTE, SEND_DM, PUBLISH_PROFILE}

    private record NostrAction(NostrActionType t, Object p) {
    }

    static class RelayConnection implements WebSocket.Listener {
        private final URI uri;
        private final HttpClient http;
        private final StringBuilder messageBuffer = new StringBuilder();
        private final BiConsumer<String, String> onMessageCallback;
        private final String selfPublicKeyXOnlyHexForReq;
        private final boolean canRead, canWrite;
        private final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();
        private WebSocket socket;
        private volatile boolean connected = false;

        public RelayConnection(URI uri, HttpClient client, BiConsumer<String, String> onMessage, String selfPubKeyXOnlyHex, boolean canRead, boolean canWrite) {
            this.uri = uri;
            this.http = client;
            this.onMessageCallback = onMessage;
            this.selfPublicKeyXOnlyHexForReq = selfPubKeyXOnlyHex;
            this.canRead = canRead;
            this.canWrite = canWrite;
        }

        public boolean canWrite() {
            return canWrite;
        }

        public void connect() {
            if (connected && socket != null && !socket.isOutputClosed()) return;
            logger.info("Relay {}: Initiating connection.", uri);
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, this)
                    .thenAccept(ws -> {
                        logger.info("Relay {}: WebSocket object assigned.", uri);
                        this.socket = ws;
                    })
                    .exceptionally(ex -> {
                        logger.error("Relay {}: Connection attempt failed: {}", uri, ex.getMessage());
                        this.connected = false;
                        return null;
                    });
        }

        @Override
        public void onOpen(WebSocket ws) {
            logger.info("Relay {}: Connection opened.", uri);
            this.connected = true;
            this.socket = ws;
            ws.request(1);
            processPendingMessages();
            if (canRead) {
                try {
                    send(NostrUtil.toJson(List.of("REQ", "publicfeed-" + UUID.randomUUID().toString().substring(0, 8), Map.of("kinds", List.of(0, 1), "limit", 50))));
                    if (selfPublicKeyXOnlyHexForReq != null && !selfPublicKeyXOnlyHexForReq.isEmpty()) {
                        send(NostrUtil.toJson(List.of("REQ", "mydms-" + UUID.randomUUID().toString().substring(0, 8), Map.of("kinds", List.of(4), "#p", List.of(selfPublicKeyXOnlyHexForReq)))));
                    }
                } catch (Exception e) {
                    logger.error("Relay {}: Error sending initial REQs: {}", uri, e.getMessage());
                }
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            messageBuffer.append(data);
            ws.request(1);
            if (last) {
                if (onMessageCallback != null) try {
                    onMessageCallback.accept(uri.toString(), messageBuffer.toString());
                } catch (Exception e) {
                    logger.error("Error in onMessageCallback for relay {}: {}", uri, e.getMessage(), e);
                }
                messageBuffer.setLength(0);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            logger.info("Relay {}: Connection closed. Status: {}, Reason: {}", uri, statusCode, reason);
            this.connected = false;
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            logger.error("Relay {}: WebSocket error: {}", uri, error.getMessage(), error);
            this.connected = false;
            if (socket != null && !socket.isOutputClosed()) socket.abort();
        }

        private void processPendingMessages() {
            String message;
            while (connected && socket != null && !socket.isOutputClosed() && (message = pendingMessages.poll()) != null) {
                socket.sendText(message, true);
            }
        }

        public void send(String message) {
            pendingMessages.offer(message);
            if (connected && socket != null && !socket.isOutputClosed()) processPendingMessages();
            else
                logger.warn("Relay {}: Message queued as not connected. URI: {}", uri, message.substring(0, Math.min(message.length(), 100)));
        }

        public void close() {
            if (socket != null && !socket.isOutputClosed()) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing").orTimeout(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.warn("Relay {}: Exception during sendClose: {}", uri, e.getMessage());
                    socket.abort();
                }
            }
            connected = false;
            logger.info("Relay {}: Connection explicitly closed.", uri);
        }
    }

    static class NostrUtil {
        private static final ObjectMapper jsonMapper = Netention.Core.createObjectMapper();

        public static String toJson(Object o) throws JsonProcessingException {
            return jsonMapper.writeValueAsString(o);
        }

        public static <T> T fromJson(String s, TypeReference<T> tr) throws JsonProcessingException {
            return jsonMapper.readValue(s, tr);
        }
    }

    public static class NostrEvent {
        public final List<List<String>> tags = new ArrayList<>();
        public String id, pubkey, content, sig;
        public long created_at;
        public int kind;

        public String getSerializedForSigning() throws JsonProcessingException {
            return NostrUtil.jsonMapper.writeValueAsString(List.of(0, this.pubkey, this.created_at, this.kind, this.tags, this.content));
        }

        public void calculateId() throws NoSuchAlgorithmException, JsonProcessingException {
            this.id = Crypto.bytesToHex(MessageDigest.getInstance("SHA-256").digest(getSerializedForSigning().getBytes(StandardCharsets.UTF_8)));
        }

        public void sign(byte[] privKeyRaw, byte[] auxRand) throws GeneralSecurityException, JsonProcessingException {
            if (this.id == null) calculateId();
            this.sig = Crypto.bytesToHex(Crypto.Schnorr.sign(Crypto.hexToBytes(this.id), privKeyRaw, auxRand));
        }
    }
}
