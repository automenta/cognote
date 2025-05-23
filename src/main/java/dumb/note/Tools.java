package dumb.note;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

class Tools {
    private static final Logger logger = LoggerFactory.getLogger(Tools.class);

    public static void registerAllTools(Map<Netention.Core.Tool, BiFunction<Netention.Core, Map<String, Object>, Object>> tools) {
        tools.put(Netention.Core.Tool.LOG_MESSAGE, Tools::logMessage);
        tools.put(Netention.Core.Tool.GET_NOTE_PROPERTY, Tools::getNoteProperty);
        tools.put(Netention.Core.Tool.PARSE_JSON, Tools::parseJson);
        tools.put(Netention.Core.Tool.CREATE_NOTE, Tools::createNote);
        tools.put(Netention.Core.Tool.MODIFY_NOTE_CONTENT, Tools::modifyNoteContent);
        tools.put(Netention.Core.Tool.DELETE_NOTE, Tools::deleteNote);
        tools.put(Netention.Core.Tool.ADD_CONTACT, Tools::addContact);
        tools.put(Netention.Core.Tool.IF_ELSE, Tools::ifElse);
        tools.put(Netention.Core.Tool.DECRYPT_NOSTR_DM, Tools::decryptNostrDm);
        tools.put(Netention.Core.Tool.UPDATE_CHAT_NOTE, Tools::updateChatNote);
        tools.put(Netention.Core.Tool.FIRE_CORE_EVENT, Tools::fireCoreEvent);
        tools.put(Netention.Core.Tool.SUGGEST_PLAN_STEPS, Tools::suggestPlanSteps);
        tools.put(Netention.Core.Tool.SCHEDULE_SYSTEM_EVENT, Tools::scheduleSystemEvent);
        tools.put(Netention.Core.Tool.FIND_NOTES_BY_TAG, Tools::findNotesByTag);
        tools.put(Netention.Core.Tool.FOR_EACH, Tools::forEach);
        tools.put(Netention.Core.Tool.EXECUTE_SEMANTIC_QUERY, Tools::executeSemanticQuery);
        tools.put(Netention.Core.Tool.GET_SYSTEM_HEALTH_METRICS, Tools::getSystemHealthMetrics);
        tools.put(Netention.Core.Tool.GET_CONFIG_STATE, Tools::getConfigState);
        tools.put(Netention.Core.Tool.APPLY_CONFIG_STATE, Tools::applyConfigState);
        tools.put(Netention.Core.Tool.GET_SELF_NOSTR_INFO, Tools::getSelfNostrInfo);
        tools.put(Netention.Core.Tool.ACCEPT_FRIEND_REQUEST, Tools::acceptFriendRequest);
        tools.put(Netention.Core.Tool.REJECT_FRIEND_REQUEST, Tools::rejectFriendRequest);
        tools.put(Netention.Core.Tool.SEND_FRIEND_REQUEST, Tools::sendFriendRequest);
        tools.put(Netention.Core.Tool.REMOVE_CONTACT, Tools::removeContact);
    }

    private static Object logMessage(Netention.Core core, Map<String, Object> params) {
        logger.info("TOOL_LOG: {}", params.get(Netention.ToolParam.MESSAGE.getKey()));
        return null;
    }

    private static Object getNoteProperty(Netention.Core core, Map<String, Object> params) {
        var noteId = (String) params.get(Netention.ToolParam.NOTE_ID.getKey());
        var propertyPath = (String) params.get(Netention.ToolParam.PROPERTY_PATH.getKey());
        var failIfNotFound = (Boolean) params.getOrDefault(Netention.ToolParam.FAIL_IF_NOT_FOUND.getKey(), true);
        var defaultValue = params.get(Netention.ToolParam.DEFAULT_VALUE.getKey());

        return core.notes.get(noteId).map(n -> {
            try {
                var parts = propertyPath.split("\\.");
                Object current = n;
                for (var i = 0; i < parts.length; i++) {
                    var part = parts[i];
                    if (current == null) break;
                    if (i == 0) {
                        current = switch (part) {
                            case "id" -> n.id;
                            case "title" -> n.getTitle();
                            case "text" -> n.getText();
                            case "tags" -> n.tags;
                            case "content" -> n.content;
                            case "meta" -> n.meta;
                            case "links" -> n.links;
                            case "createdAt" -> n.createdAt;
                            case "updatedAt" -> n.updatedAt;
                            default -> null;
                        };
                    } else if (current instanceof Map map) {
                        current = map.get(part);
                    } else if (current instanceof JsonNode jsonNode) {
                        current = jsonNode.has(part) ? jsonNode.get(part) : null;
                    } else {
                        current = null;
                    }
                }
                if (current == null && failIfNotFound)
                    throw new RuntimeException("Property path '" + propertyPath + "' not found for note " + noteId);
                if (current instanceof JsonNode jn && jn.isValueNode()) {
                    if (jn.isTextual()) return jn.asText();
                    if (jn.isNumber()) return jn.numberValue();
                    if (jn.isBoolean()) return jn.asBoolean();
                    return jn.toString();
                }
                return Objects.requireNonNullElse(current, defaultValue);
            } catch (Exception e) {
                logger.error("Error getting property {} from note {}: {}", propertyPath, noteId, e.getMessage());
                if (failIfNotFound) throw new RuntimeException("Failed to get property: " + e.getMessage(), e);
                return defaultValue;
            }
        }).orElseGet(() -> {
            if (failIfNotFound) throw new RuntimeException("Note with ID " + noteId + " not found.");
            return defaultValue;
        });
    }

    private static Object parseJson(Netention.Core core, Map<String, Object> params) {
        var jsonString = (String) params.get(Netention.ToolParam.JSON_STRING.getKey());
        try {
            return core.json.readTree(jsonString);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    private static Object createNote(Netention.Core core, Map<String, Object> params) {
        var note = new Netention.Note();
        ofNullable((String) params.get(Netention.ToolParam.ID.getKey())).ifPresent(id -> note.id = id);
        ofNullable((String) params.get(Netention.ToolParam.TITLE.getKey())).ifPresent(note::setTitle);
        ofNullable((String) params.get(Netention.ToolParam.TEXT.getKey())).ifPresent(text -> {
            if (Boolean.TRUE.equals(params.get(Netention.ToolParam.AS_HTML.getKey()))) note.setHtmlText(text);
            else note.setText(text);
        });
        ofNullable((List<String>) params.get(Netention.ToolParam.TAGS.getKey())).ifPresent(note.tags::addAll);
        ofNullable((Map<String, Object>) params.get(Netention.ToolParam.CONTENT.getKey())).ifPresent(note.content::putAll);
        ofNullable((Map<String, Object>) params.get(Netention.ToolParam.METADATA.getKey())).ifPresent(note.meta::putAll);
        return core.saveNote(note);
    }

    private static Object modifyNoteContent(Netention.Core core, Map<String, Object> params) {
        var noteId = (String) params.get(Netention.ToolParam.NOTE_ID.getKey());
        var contentUpdate = (Map<String, Object>) params.get(Netention.ToolParam.CONTENT_UPDATE.getKey());
        return core.notes.get(noteId).map(n -> {
            contentUpdate.forEach((key, v) -> {
                if ("metadataUpdate".equals(key) && v instanceof Map<?, ?> metaUpdates) {
                    metaUpdates.forEach((metaKey, metaValue) -> {
                        if ("NOW".equals(metaValue)) n.meta.put((String) metaKey, Instant.now().toString());
                        else n.meta.put((String) metaKey, metaValue);
                    });
                } else if ("tagsAdd".equals(key) && v instanceof List<?> tagsToAdd) {
                    tagsToAdd.forEach(tag -> n.tags.add(String.valueOf(tag)));
                } else if ("tagsRemove".equals(key) && v instanceof List<?> tagsToRemove) {
                    tagsToRemove.forEach(tag -> n.tags.remove(String.valueOf(tag)));
                } else if ("title".equals(key)) {
                    n.setTitle(String.valueOf(v));
                } else if ("text".equals(key)) {
                    n.setText(String.valueOf(v));
                } else {
                    n.content.put(key, v);
                }
            });
            return core.saveNote(n);
        }).orElseThrow(() -> new RuntimeException("Note " + noteId + " not found for modification."));
    }

    private static Object deleteNote(Netention.Core core, Map<String, Object> params) {
        var noteId = (String) params.get(Netention.ToolParam.NOTE_ID.getKey());
        return core.deleteNote(noteId);
    }

    private static Netention.Note upsertContactAndChatNote(Netention.Core core, String nostrPubKeyHex, Map<String, Object> profileData) {
        if (nostrPubKeyHex == null || nostrPubKeyHex.isEmpty()) {
            throw new IllegalArgumentException("NOSTR_PUB_KEY_HEX is required for contact operations.");
        }

        var npub = "";
        try {
            npub = Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(nostrPubKeyHex));
        } catch (Exception e) {
            logger.warn("Could not encode npub for hex {}: {}", nostrPubKeyHex, e.getMessage());
            npub = "npub_error_" + nostrPubKeyHex.substring(0, 8);
        }
        final String finalNpub = npub;

        var contactNoteOpt = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.CONTACT.value) && nostrPubKeyHex.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key))).stream().findFirst();

        var contactNote = contactNoteOpt.orElseGet(() -> {
            var newNote = new Netention.Note();
            newNote.tags.add(Netention.SystemTag.CONTACT.value);
            newNote.tags.add(Netention.SystemTag.NOSTR_CONTACT.value);
            newNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY_HEX.key, nostrPubKeyHex);
            newNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, finalNpub);
            newNote.setTitle(profileData != null && profileData.containsKey("name") ? (String) profileData.get("name") : "Unknown Contact (" + finalNpub.substring(0, 8) + "...)");
            newNote.setText(profileData != null && profileData.containsKey("about") ? (String) profileData.get("about") : "");
            return newNote;
        });

        if (profileData != null) {
            ofNullable((String) profileData.get("name")).ifPresent(contactNote::setTitle);
            ofNullable((String) profileData.get("about")).ifPresent(contactNote::setText);
            ofNullable((String) profileData.get("picture")).ifPresent(pic -> contactNote.content.put(Netention.ContentKey.PROFILE_PICTURE_URL.getKey(), pic));
        }
        contactNote.meta.put(Netention.Metadata.LAST_SEEN.key, Instant.now().toString());

        var chatNoteOpt = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.CHAT.value) && nostrPubKeyHex.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key))).stream().findFirst();

        if (chatNoteOpt.isEmpty()) {
            var chatNote = new Netention.Note("Chat with " + contactNote.getTitle(), "");
            chatNote.tags.add(Netention.SystemTag.CHAT.value);
            chatNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY_HEX.key, nostrPubKeyHex);
            chatNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, finalNpub);
            chatNote.content.put(Netention.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
            core.saveNote(chatNote);
            logger.info("Created new chat note for contact {}.", contactNote.getTitle());
        }

        return core.saveNote(contactNote);
    }

    private static Object addContact(Netention.Core core, Map<String, Object> params) {
        var nostrPubKeyHex = (String) params.get(Netention.ToolParam.NOSTR_PUB_KEY_HEX.getKey());
        var profileData = (Map<String, Object>) params.get(Netention.ToolParam.PROFILE_DATA.getKey());
        if (nostrPubKeyHex == null || nostrPubKeyHex.isEmpty()) {
            throw new IllegalArgumentException("NOSTR_PUB_KEY_HEX is required for ADD_CONTACT.");
        }
        return upsertContactAndChatNote(core, nostrPubKeyHex, profileData);
    }

    private static Object removeContact(Netention.Core core, Map<String, Object> params) {
        var nostrPubKeyHex = (String) params.get(Netention.ToolParam.NOSTR_PUB_KEY_HEX.getKey());
        if (nostrPubKeyHex == null || nostrPubKeyHex.isEmpty()) {
            throw new IllegalArgumentException("NOSTR_PUB_KEY_HEX is required for REMOVE_CONTACT.");
        }

        boolean removedContact = false;
        boolean removedChat = false;

        // Find and delete contact note
        Optional<Netention.Note> contactNoteOpt = core.notes.getAll(n ->
                n.tags.contains(Netention.SystemTag.CONTACT.value) &&
                        nostrPubKeyHex.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key))
        ).stream().findFirst();

        if (contactNoteOpt.isPresent()) {
            core.deleteNote(contactNoteOpt.get().id);
            removedContact = true;
            logger.info("Removed contact note for hex: {}", nostrPubKeyHex.substring(0, 8));
        } else {
            logger.warn("No contact note found for hex: {}", nostrPubKeyHex.substring(0, 8));
        }

        // Find and delete chat note
        Optional<Netention.Note> chatNoteOpt = core.notes.getAll(n ->
                n.tags.contains(Netention.SystemTag.CHAT.value) &&
                        nostrPubKeyHex.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key))
        ).stream().findFirst();

        if (chatNoteOpt.isPresent()) {
            core.deleteNote(chatNoteOpt.get().id);
            removedChat = true;
            logger.info("Removed chat note for hex: {}", nostrPubKeyHex.substring(0, 8));
        } else {
            logger.warn("No chat note found for hex: {}", nostrPubKeyHex.substring(0, 8));
        }

        return removedContact || removedChat;
    }

    private static Object ifElse(Netention.Core core, Map<String, Object> params) {
        var condition = (Boolean) params.get(Netention.ToolParam.CONDITION.getKey());
        var trueSteps = (List<Map<String, Object>>) params.get(Netention.ToolParam.TRUE_STEPS.getKey());
        var falseSteps = (List<Map<String, Object>>) params.get(Netention.ToolParam.FALSE_STEPS.getKey());

        List<Map<String, Object>> stepsToExecute = condition ? trueSteps : falseSteps;
        if (stepsToExecute != null && !stepsToExecute.isEmpty()) {
            logger.warn("IF_ELSE tool does not directly execute nested steps. Steps: {}", stepsToExecute);
        }
        return condition;
    }

    private static Object decryptNostrDm(Netention.Core core, Map<String, Object> params) {
        var eventPayload = (Map<String, Object>) params.get(Netention.ToolParam.EVENT_PAYLOAD_MAP.getKey());
        var content = (String) eventPayload.get("content");
        var pubkey = (String) eventPayload.get("pubkey");
        var tags = (List<List<String>>) eventPayload.get("tags");

        String recipientPubKeyHex = null;
        for (var tag : tags) {
            if ("p".equals(tag.get(0)) && tag.size() > 1) {
                recipientPubKeyHex = tag.get(1);
                break;
            }
        }

        if (recipientPubKeyHex == null) {
            throw new IllegalArgumentException("DM event missing 'p' tag for recipient.");
        }

        try {
            var selfNpubHex = core.net.getPublicKeyXOnlyHex();
            byte[] decryptedBytes;
            String partnerPubKeyHex;

            if (pubkey.equals(selfNpubHex)) {
                partnerPubKeyHex = recipientPubKeyHex;
            } else if (recipientPubKeyHex.equals(selfNpubHex)) {
                partnerPubKeyHex = pubkey;
            } else {
                throw new RuntimeException("DM is neither sent by nor to me. Pubkey: " + pubkey + ", Recipient: " + recipientPubKeyHex + ", Self: " + selfNpubHex);
            }

            decryptedBytes = Crypto.nip04Decrypt(content, Crypto.getSharedSecretWithRetry(core.net.privateKeyRaw, Crypto.hexToBytes(partnerPubKeyHex))).getBytes();
            var decryptedText = new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);

            boolean isFriendRequest = decryptedText.equalsIgnoreCase("Hello! I'd like to connect on Netention.");

            Map<String, Object> result = new HashMap<>();
            result.put("decryptedText", decryptedText);
            result.put("isLmResult", decryptedText.startsWith("{\"type\":\"netention_lm_result\""));
            result.put("isFriendRequest", isFriendRequest);
            if (result.get("isLmResult") instanceof Boolean && (Boolean) result.get("isLmResult")) {
                try {
                    result.put("lmResultPayload", core.json.readValue(decryptedText, new TypeReference<Map<String, Object>>() {
                    }));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    logger.warn("Failed to parse LM result payload: {}", e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt Nostr DM: " + e.getMessage(), e);
        }
    }

    private static Object updateChatNote(Netention.Core core, Map<String, Object> params) {
        var partnerPubKeyHex = (String) params.get(Netention.ToolParam.PARTNER_PUB_KEY_HEX.getKey());
        var senderPubKeyHex = (String) params.get(Netention.ToolParam.SENDER_PUB_KEY_HEX.getKey());
        var messageContent = (String) params.get(Netention.ToolParam.MESSAGE_CONTENT.getKey());
        var timestampEpochSeconds = (Long) params.get(Netention.ToolParam.TIMESTAMP_EPOCH_SECONDS.getKey());

        var selfNpubHex = core.net.getPublicKeyXOnlyHex();
        if (selfNpubHex == null || selfNpubHex.isEmpty()) {
            logger.warn("Cannot update chat note: self Nostr public key not available.");
            return null;
        }

        var chatNoteOpt = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.CHAT.value) && partnerPubKeyHex.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key))).stream().findFirst();

        var chatNote = chatNoteOpt.orElseGet(() -> {
            var newChatNote = new Netention.Note();
            newChatNote.tags.add(Netention.SystemTag.CHAT.value);
            newChatNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY_HEX.key, partnerPubKeyHex);
            try {
                newChatNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(partnerPubKeyHex)));
            } catch (Exception e) {
                logger.warn("Failed to encode npub for new chat note: {}", e.getMessage());
                newChatNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, "npub_error_" + partnerPubKeyHex.substring(0, 8));
            }
            core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.CONTACT.value) && partnerPubKeyHex.equals(n.meta.get(Netention.Metadata.NOSTR_PUB_KEY_HEX.key))).stream().findFirst().ifPresentOrElse(contact -> newChatNote.setTitle("Chat with " + contact.getTitle()), () -> newChatNote.setTitle("Chat with " + partnerPubKeyHex.substring(0, 12) + "..."));
            newChatNote.content.put(Netention.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
            return newChatNote;
        });

        var messages = (List<Map<String, String>>) chatNote.content.getOrDefault(Netention.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());

        final String finalSenderPubKeyHex = senderPubKeyHex;
        final Instant messageTimestamp = Instant.ofEpochSecond(timestampEpochSeconds);
        boolean isDuplicate = messages.stream().anyMatch(msg -> finalSenderPubKeyHex.equals(msg.get("sender")) && messageContent.equals(msg.get("text")) && messageTimestamp.equals(Instant.parse(msg.get("timestamp"))));

        if (!isDuplicate) {
            var messageEntry = new HashMap<String, String>();
            messageEntry.put("sender", senderPubKeyHex);
            messageEntry.put("text", messageContent);
            messageEntry.put("timestamp", Instant.ofEpochSecond(timestampEpochSeconds).toString());
            messages.add(messageEntry);
            chatNote.content.put(Netention.ContentKey.MESSAGES.getKey(), messages);

            if (!senderPubKeyHex.equals(selfNpubHex)) {
                int unreadCount = (Integer) chatNote.meta.getOrDefault(Netention.Metadata.UNREAD_MESSAGES_COUNT.key, 0);
                chatNote.meta.put(Netention.Metadata.UNREAD_MESSAGES_COUNT.key, unreadCount + 1);
            }

            core.saveNote(chatNote);
            core.fireCoreEvent(Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED, Map.of("chatNoteId", chatNote.id, "sender", senderPubKeyHex, "message", messageContent));
            logger.info("Added message to chat with {}: {}", partnerPubKeyHex.substring(0, 8), messageContent.substring(0, Math.min(messageContent.length(), 50)));
        } else {
            logger.debug("Skipping duplicate message for chat with {}: {}", partnerPubKeyHex.substring(0, 8), messageContent.substring(0, Math.min(messageContent.length(), 50)));
        }
        return chatNote.id;
    }

    private static Object fireCoreEvent(Netention.Core core, Map<String, Object> params) {
        var eventType = Netention.Core.CoreEventType.valueOf((String) params.get(Netention.ToolParam.EVENT_TYPE.getKey()));
        var eventData = params.get(Netention.ToolParam.EVENT_DATA.getKey());
        core.fireCoreEvent(eventType, eventData);
        return null;
    }

    private static Object suggestPlanSteps(Netention.Core core, Map<String, Object> params) {
        var goalText = (String) params.get(Netention.ToolParam.GOAL_TEXT.getKey());
        if (core.lm.isReady()) {
            return core.lm.decomposeTask(goalText).orElse(Collections.emptyList()).stream().map(stepDesc -> {
                var step = new Netention.Planner.PlanStep();
                step.description = stepDesc;
                step.toolName = Netention.Core.Tool.LOG_MESSAGE.name();
                step.toolParams = Map.of(Netention.ToolParam.MESSAGE.getKey(), "Executing: " + stepDesc);
                return step;
            }).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private static Object scheduleSystemEvent(Netention.Core core, Map<String, Object> params) {
        var eventType = Netention.Core.SystemEventType.valueOf((String) params.get(Netention.ToolParam.EVENT_TYPE.getKey()));
        var delaySeconds = ((Number) params.getOrDefault(Netention.ToolParam.DELAY_SECONDS.getKey(), 0L)).longValue();
        var payload = (Map<String, Object>) params.getOrDefault(Netention.ToolParam.PAYLOAD.getKey(), Collections.emptyMap());

        core.scheduler.schedule(() -> core.fireCoreEvent(Netention.Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(Netention.ToolParam.EVENT_TYPE.getKey(), eventType.name(), Netention.ToolParam.PAYLOAD.getKey(), payload, Netention.ContentKey.STATUS.getKey(), Netention.PlanState.PENDING.name())), delaySeconds, TimeUnit.SECONDS);
        return null;
    }

    private static Object findNotesByTag(Netention.Core core, Map<String, Object> params) {
        var tag = (String) params.get(Netention.ToolParam.TAG.getKey());
        return core.notes.getAll(n -> n.tags.contains(tag));
    }

    private static Object forEach(Netention.Core core, Map<String, Object> params) {
        var list = (List<Object>) params.get(Netention.ToolParam.LIST.getKey());
        var loopVarName = (String) params.get(Netention.ToolParam.LOOP_VAR.getKey());
        var loopSteps = (List<Map<String, Object>>) params.get(Netention.ToolParam.LOOP_STEPS.getKey());

        if (list == null || loopSteps == null || loopSteps.isEmpty()) {
            logger.warn("FOR_EACH called with null list or empty loop steps.");
            return null;
        }

        logger.info("FOR_EACH: Iterating {} items with loop variable '{}'. Steps: {}", list.size(), loopVarName, loopSteps.size());
        return null;
    }

    private static Object executeSemanticQuery(Netention.Core core, Map<String, Object> params) {
        var queryText = (String) params.get(Netention.ToolParam.QUERY_TEXT.getKey());
        var minSimilarity = ((Number) params.getOrDefault(Netention.ToolParam.MIN_SIMILARITY.getKey(), 0.7)).doubleValue();
        var maxResults = ((Number) params.getOrDefault(Netention.ToolParam.MAX_RESULTS.getKey(), 5)).intValue();

        if (!core.lm.isReady()) {
            throw new RuntimeException("LLM service not ready for semantic query.");
        }

        return core.lm.generateEmbedding(queryText).map(queryEmb -> core.notes.getAllNotes().stream().filter(n -> n.getEmbeddingV1() != null && n.getEmbeddingV1().length == queryEmb.length).map(n -> new AbstractMap.SimpleEntry<>(n, LM.cosineSimilarity(queryEmb, n.getEmbeddingV1()))).filter(entry -> entry.getValue() >= minSimilarity).sorted(Map.Entry.<Netention.Note, Double>comparingByValue().reversed()).limit(maxResults).map(Map.Entry::getKey).collect(Collectors.toList())).orElse(Collections.emptyList());
    }

    private static Object getSystemHealthMetrics(Netention.Core core, Map<String, Object> params) {
        long pendingSystemEvents = core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.SYSTEM_EVENT.value) && Netention.PlanState.PENDING.name().equals(n.content.get(Netention.ContentKey.STATUS.getKey()))).size();
        long activePlans = core.planner.getActive().size();
        long failedPlanStepsInActivePlans = core.planner.getActive().values().stream().flatMap(exec -> exec.steps.stream()).filter(step -> Netention.PlanStepState.FAILED.equals(step.status)).count();

        return Map.of("pendingSystemEvents", pendingSystemEvents, "activePlans", activePlans, "failedPlanStepsInActivePlans", failedPlanStepsInActivePlans);
    }

    private static Object getConfigState(Netention.Core core, Map<String, Object> params) {
        var configType = (String) params.get(Netention.ToolParam.CONFIG_TYPE.getKey());
        return switch (configType) {
            case "nostr" -> core.json.convertValue(core.cfg.net, new TypeReference<Map<String, Object>>() {
            });
            case "ui" -> core.json.convertValue(core.cfg.ui, new TypeReference<Map<String, Object>>() {
            });
            case "llm" -> core.json.convertValue(core.cfg.lm, new TypeReference<Map<String, Object>>() {
            });
            default -> throw new IllegalArgumentException("Unknown config type: " + configType);
        };
    }

    private static Object applyConfigState(Netention.Core core, Map<String, Object> params) {
        var configType = (String) params.get(Netention.ToolParam.CONFIG_TYPE.getKey());
        var stateMap = (Map<String, Object>) params.get(Netention.ToolParam.STATE_MAP.getKey());
        try {
            switch (configType) {
                case "nostr" -> {
                    var newConfig = core.json.convertValue(stateMap, Netention.Config.NostrSettings.class);
                    core.cfg.net.privateKeyBech32 = newConfig.privateKeyBech32;
                    core.cfg.net.publicKeyBech32 = newConfig.publicKeyBech32;
                    core.cfg.net.myProfileNoteId = newConfig.myProfileNoteId;
                    core.net.loadIdentity();
                    core.net.setEnabled(core.net.isEnabled());
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "nostr_status_changed");
                }
                case "ui" -> {
                    var newConfig = core.json.convertValue(stateMap, Netention.Config.UISettings.class);
                    core.cfg.ui.theme = newConfig.theme;
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "ui_theme_updated");
                }
                case "llm" -> {
                    var newConfig = core.json.convertValue(stateMap, Netention.Config.LMSettings.class);
                    core.cfg.lm.provider = newConfig.provider;
                    core.cfg.lm.ollamaBaseUrl = newConfig.ollamaBaseUrl;
                    core.cfg.lm.ollamaChatModelName = newConfig.ollamaChatModelName;
                    core.cfg.lm.ollamaEmbeddingModelName = newConfig.ollamaEmbeddingModelName;
                    core.lm.init();
                    core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "llm_status_changed");
                }
                default -> throw new IllegalArgumentException("Unknown config type: " + configType);
            }
            logger.info("Applied {} config state.", configType);
            return true;
        } catch (Exception e) {
            logger.error("Failed to apply {} config state: {}", configType, e.getMessage(), e);
            throw new RuntimeException("Failed to apply config: " + e.getMessage(), e);
        }
    }

    private static Object getSelfNostrInfo(Netention.Core core, Map<String, Object> params) {
        return Map.of("pubKeyHex", core.net.getPublicKeyXOnlyHex(), "pubKeyNpub", core.net.getPublicKeyBech32(), "myProfileNoteId", core.cfg.net.myProfileNoteId);
    }

    private static Object acceptFriendRequest(Netention.Core core, Map<String, Object> params) {
        var senderNpub = (String) params.get(Netention.ToolParam.FRIEND_REQUEST_SENDER_NPUB.getKey());
        var actionableItemId = (String) params.get(Netention.ToolParam.ACTIONABLE_ITEM_ID.getKey());

        if (senderNpub == null || senderNpub.isEmpty()) {
            throw new IllegalArgumentException("FRIEND_REQUEST_SENDER_NPUB is required for ACCEPT_FRIEND_REQUEST.");
        }

        try {
            var senderPubKeyHex = Crypto.bytesToHex(Crypto.Bech32.nip19Decode(senderNpub));
            upsertContactAndChatNote(core, senderPubKeyHex, null);

            core.net.sendDirectMessage(senderNpub, "Friend request accepted! Let's chat.");

            core.fireCoreEvent(Netention.Core.CoreEventType.ACTIONABLE_ITEM_REMOVED, actionableItemId);

            logger.info("Accepted friend request from {}", senderNpub);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to accept friend request: " + e.getMessage(), e);
        }
    }

    private static Object rejectFriendRequest(Netention.Core core, Map<String, Object> params) {
        var actionableItemId = (String) params.get(Netention.ToolParam.ACTIONABLE_ITEM_ID.getKey());

        if (actionableItemId == null || actionableItemId.isEmpty()) {
            throw new IllegalArgumentException("ACTIONABLE_ITEM_ID is required for REJECT_FRIEND_REQUEST.");
        }

        core.fireCoreEvent(Netention.Core.CoreEventType.ACTIONABLE_ITEM_REMOVED, actionableItemId);

        logger.info("Rejected friend request (actionable item removed): {}", actionableItemId);
        return true;
    }

    private static Object sendFriendRequest(Netention.Core core, Map<String, Object> params) {
        var recipientNpub = (String) params.get(Netention.ToolParam.RECIPIENT_NPUB.getKey());
        if (recipientNpub == null || recipientNpub.isEmpty()) {
            throw new IllegalArgumentException("RECIPIENT_NPUB is required for SEND_FRIEND_REQUEST.");
        }
        try {
            core.net.sendFriendRequest(recipientNpub);
            logger.info("Sent friend request to {}", recipientNpub);

            var recipientPubKeyHex = Crypto.bytesToHex(Crypto.Bech32.nip19Decode(recipientNpub));
            upsertContactAndChatNote(core, recipientPubKeyHex, null);

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to send friend request: " + e.getMessage(), e);
        }
    }
}
//public class Tools {
//    public static void registerAllTools(Map<Netention.Core.Tool, BiFunction<Netention.Core, Map<String, Object>, Object>> toolMap) {
//        Stream.of(Tools.class.getDeclaredMethods())
//                .filter(method -> method.isAnnotationPresent(ToolAction.class) && java.lang.reflect.Modifier.isStatic(method.getModifiers()))
//                .forEach(method -> {
//                    var annotation = method.getAnnotation(ToolAction.class);
//                    var toolEnum = annotation.tool();
//                    toolMap.put(toolEnum, (coreInstance, paramsMap) -> {
//                        try {
//                            return method.invoke(null, coreInstance, paramsMap);
//                        } catch (IllegalAccessException | InvocationTargetException e) {
//                            var cause = e.getCause() != null ? e.getCause() : e;
//                            Netention.Core.logger.error("Error invoking tool {}: {}", toolEnum, cause.getMessage(), cause);
//                            if (cause instanceof RuntimeException re) throw re;
//                            throw new RuntimeException("Error invoking tool " + toolEnum, cause);
//                        }
//                    });
//                });
//
//        /*
//        Stream.of(Netention.Core.Tool.values())
//                .filter(Netention.Core.Tool::isPlaceholder)
//                .filter(toolEnum -> !toolMap.containsKey(toolEnum))
//                .forEach(toolEnum -> toolMap.put(toolEnum, (c, p) -> toolEnum.name() + " (placeholder). Params: " + p));
//         */
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.LOG_MESSAGE)
//    public static Object logMessage(Netention.Core core, Map<String, Object> params) {
//        var message = Objects.toString(params.get(Netention.ToolParam.MESSAGE.getKey()), "No message provided.");
//        Netention.Core.logger.info("TOOL_LOG: {}", message);
//        return "Logged: " + message;
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.USER_INTERACTION)
//    public static Object userInteraction(Netention.Core core, Map<String, Object> params) {
//        return "This tool is handled specially by the Planner via events.";
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.PARSE_JSON)
//    public static Object parseJson(Netention.Core core, Map<String, Object> params) {
//        var jsonString = Objects.toString(params.get(Netention.ToolParam.JSON_STRING.getKey()), null);
//        if (jsonString == null)
//            throw new IllegalArgumentException("jsonString parameter is required for ParseJson tool.");
//        try {
//            return core.json.readTree(jsonString);
//        } catch (JsonProcessingException e) {
//            throw new RuntimeException("Failed to parse JSON: " + e.getMessage(), e);
//        }
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.GET_NOTE_PROPERTY)
//    public static Object getNoteProperty(Netention.Core core, Map<String, Object> params) {
//        var noteId = (String) params.get(Netention.ToolParam.NOTE_ID.getKey());
//        var propertyPath = (String) params.get(Netention.ToolParam.PROPERTY_PATH.getKey());
//        var fifnRaw = params.get(Netention.ToolParam.FAIL_IF_NOT_FOUND.getKey());
//        var failIfNotFound = fifnRaw instanceof Boolean b ? b : (!(fifnRaw instanceof String s) || Boolean.parseBoolean(s));
//        var defaultValue = params.get(Netention.ToolParam.DEFAULT_VALUE.getKey());
//
//        var noteOpt = core.notes.get(noteId);
//        if (noteOpt.isEmpty()) {
//            if (failIfNotFound) throw new RuntimeException("Note not found: " + noteId);
//            return defaultValue;
//        }
//        var note = noteOpt.get();
//        try {
//            Object value = note;
//            var parts = propertyPath.split("\\.");
//            label:
//            for (var part : parts) {
//                switch (value) {
//                    case null -> {
//                        break label;
//                    }
//                    case Netention.Note n -> value = Netention.Core.getNoteSpecificProperty(n, part);
//                    case Map m -> value = m.get(part);
//                    case JsonNode jn -> value = jn.has(part) ? jn.get(part) : null;
//                    default -> {
//                        value = null;
//                        break label;
//                    }
//                }
//            }
//            if (value instanceof JsonNode jn && jn.isValueNode()) {
//                return jn.isTextual() ? jn.asText() : (jn.isNumber() ? jn.numberValue() : (jn.isBoolean() ? jn.asBoolean() : jn.toString()));
//            }
//            if (value == null) {
//                if (failIfNotFound && defaultValue == null)
//                    throw new RuntimeException("Property not found: " + propertyPath + " in note " + noteId);
//                return defaultValue;
//            }
//            return value;
//        } catch (Exception e) {
//            if (failIfNotFound && defaultValue == null)
//                throw new RuntimeException("Error accessing property " + propertyPath + ": " + e.getMessage(), e);
//            return defaultValue;
//        }
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.CREATE_NOTE)
//    public static Object createNote(Netention.Core core, Map<String, Object> params) {
//        var newNote = new Netention.Note();
//        if (params.containsKey(Netention.ToolParam.ID.getKey()))
//            newNote.id = (String) params.get(Netention.ToolParam.ID.getKey());
//
//        var titleTemplate = (String) params.getOrDefault(Netention.ToolParam.TITLE.getKey(), "New Note from Plan");
//        var titleMatcher = Pattern.compile("\\$([a-zA-Z0-9_.]+)_substring_(\\d+)").matcher(titleTemplate);
//        if (titleMatcher.find()) {
//            var prefix = titleTemplate.substring(0, titleMatcher.start());
//            var varName = titleMatcher.group(1);
//            var len = Integer.parseInt(titleMatcher.group(2));
//            var suffix = titleTemplate.substring(titleMatcher.end());
//            var resolvedVar = params.get(varName); // This assumes params map contains direct values, not context paths
//            if (resolvedVar instanceof String contentVal) {
//                var actualTitle = contentVal.substring(0, Math.min(contentVal.length(), len)) + (contentVal.length() > len ? "..." : "");
//                newNote.setTitle(prefix + actualTitle + suffix);
//            } else newNote.setTitle(titleTemplate);
//        } else newNote.setTitle(titleTemplate);
//
//        var text = (String) params.getOrDefault(Netention.ToolParam.TEXT.getKey(), "");
//        if (Boolean.TRUE.equals(params.get(Netention.ToolParam.AS_HTML.getKey()))) newNote.setHtmlText(text);
//        else newNote.setText(text);
//
//        if (params.get(Netention.ToolParam.TAGS.getKey()) instanceof List)
//            newNote.tags.addAll((List<String>) params.get(Netention.ToolParam.TAGS.getKey()));
//        if (params.get(Netention.ToolParam.CONTENT.getKey()) instanceof Map)
//            newNote.content.putAll((Map<String, Object>) params.get(Netention.ToolParam.CONTENT.getKey()));
//
//        Map<String, Object> metadata = params.get(Netention.ToolParam.METADATA.getKey()) instanceof Map ? new HashMap<>((Map<String, Object>) params.get(Netention.ToolParam.METADATA.getKey())) : new HashMap<>();
//        if (metadata.containsKey(Netention.Metadata.CREATED_AT_FROM_EVENT.key)) {
//            var tsValue = metadata.remove(Netention.Metadata.CREATED_AT_FROM_EVENT.key);
//            var eventTime = (tsValue instanceof Number n) ? Instant.ofEpochSecond(n.longValue()) : ((tsValue instanceof Instant i) ? i : null);
//            if (eventTime != null) {
//                newNote.createdAt = eventTime;
//                newNote.updatedAt = eventTime;
//            }
//        }
//        if (metadata.containsKey(Netention.Metadata.NOSTR_RAW_EVENT.key)) {
//            var rawEventObj = metadata.remove(Netention.Metadata.NOSTR_RAW_EVENT.key);
//            try {
//                newNote.meta.put(Netention.Metadata.NOSTR_RAW_EVENT.key, rawEventObj instanceof String s ? s : core.json.writeValueAsString(rawEventObj));
//            } catch (JsonProcessingException e) {
//                Netention.Core.logger.warn("Could not serialize nostrRawEvent for note metadata.");
//            }
//        }
//        if (metadata.containsKey(Netention.Metadata.NOSTR_PUB_KEY.key)) {
//            if (metadata.get(Netention.Metadata.NOSTR_PUB_KEY.key) instanceof String pubkeyHex) {
//                try {
//                    newNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(pubkeyHex)));
//                } catch (Exception ex) {
//                    Netention.Core.logger.warn("Failed to encode npub from hex in CreateNote metadata: {}", pubkeyHex);
//                }
//            }
//        }
//        newNote.meta.putAll(metadata);
//        core.saveNote(newNote);
//        return newNote.id;
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.MODIFY_NOTE_CONTENT)
//    public static Object modifyNoteContent(Netention.Core core, Map<String, Object> params) {
//        var noteId = (String) params.get(Netention.ToolParam.NOTE_ID.getKey());
//        var contentUpdate = (Map<String, Object>) params.get(Netention.ToolParam.CONTENT_UPDATE.getKey());
//        if (noteId == null || contentUpdate == null)
//            throw new IllegalArgumentException("noteId and contentUpdate are required.");
//
//        return core.notes.get(noteId).map(note -> {
//            Map<String, Object> updateCopy = new HashMap<>(contentUpdate); // Work on a copy
//            if ("NOW".equals(updateCopy.get(Netention.ContentKey.LAST_RUN.getKey())))
//                updateCopy.put(Netention.ContentKey.LAST_RUN.getKey(), Instant.now().toString());
//
//            if (updateCopy.get("metadataUpdate") instanceof Map metadataUpdatesRaw) {
//                Map<String, Object> metadataUpdates = new HashMap<>(metadataUpdatesRaw);
//                if ("NOW".equals(metadataUpdates.get(Netention.Metadata.PROFILE_LAST_UPDATED_AT.key))) {
//                    metadataUpdates.put(Netention.Metadata.PROFILE_LAST_UPDATED_AT.key, Instant.now().toString());
//                }
//                note.meta.putAll(metadataUpdates);
//                updateCopy.remove("metadataUpdate");
//            }
//            note.content.putAll(updateCopy);
//            core.saveNote(note);
//            return true;
//        }).orElse(false);
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.DELETE_NOTE)
//    public static Object deleteNote(Netention.Core core, Map<String, Object> params) {
//        return core.deleteNote((String) params.get(Netention.ToolParam.NOTE_ID.getKey()));
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.CREATE_LINKS)
//    public static Object createLinks(Netention.Core core, Map<String, Object> params) {
//        var sourceNoteId = (String) params.get(Netention.ToolParam.SOURCE_NOTE_ID.getKey());
//        var linksToAdd = (List<Map<String, String>>) params.get(Netention.ToolParam.LINKS.getKey());
//        if (sourceNoteId == null || linksToAdd == null)
//            throw new IllegalArgumentException("sourceNoteId and links required.");
//        core.notes.get(sourceNoteId).ifPresent(sourceNote -> {
//            linksToAdd.forEach(linkMap -> sourceNote.links.add(new Netention.Link(linkMap.get("targetNoteId"), linkMap.get("relationType"))));
//            core.saveNote(sourceNote);
//        });
//        return "Links added to " + sourceNoteId;
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.CREATE_OR_UPDATE_CONTACT_NOTE)
//    public static Object createOrUpdateContactNote(Netention.Core core, Map<String, Object> params) {
//        var nostrPubKeyHex = (String) params.get(Netention.ToolParam.NOSTR_PUB_KEY_HEX.getKey());
//        var profileData = (Map<String, Object>) params.get(Netention.ToolParam.PROFILE_DATA.getKey());
//        if (nostrPubKeyHex == null) throw new IllegalArgumentException("nostrPubKeyHex is required.");
//        try {
//            var nostrPubKeyNpub = Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(nostrPubKeyHex));
//            var contactNoteId = "contact_" + nostrPubKeyHex;
//            var contactNote = core.notes.get(contactNoteId).orElseGet(() -> {
//                var n = new Netention.Note("Contact: " + nostrPubKeyNpub.substring(0, Math.min(nostrPubKeyNpub.length(), 12)) + "...", "");
//                n.id = contactNoteId;
//                n.tags.addAll(Arrays.asList(Netention.SystemTag.CONTACT.value, Netention.SystemTag.NOSTR_CONTACT.value));
//                n.meta.putAll(Map.of(Netention.Metadata.NOSTR_PUB_KEY.key, nostrPubKeyNpub, Netention.Metadata.NOSTR_PUB_KEY_HEX.key, nostrPubKeyHex));
//                Netention.Core.logger.info("Created new contact note for {}", nostrPubKeyNpub);
//                return n;
//            });
//            contactNote.meta.put(Netention.Metadata.LAST_SEEN.key, Instant.now().toString());
//            if (profileData != null) {
//                ofNullable(profileData.get("name")).ifPresent(name -> contactNote.content.put(Netention.ContentKey.PROFILE_NAME.getKey(), name));
//                ofNullable(profileData.get("about")).ifPresent(about -> contactNote.content.put(Netention.ContentKey.PROFILE_ABOUT.getKey(), about));
//                ofNullable(profileData.get("picture")).ifPresent(pic -> contactNote.content.put(Netention.ContentKey.PROFILE_PICTURE_URL.getKey(), pic));
//                contactNote.meta.put(Netention.Metadata.PROFILE_LAST_UPDATED_AT.key, Instant.now().toString());
//            }
//            core.saveNote(contactNote);
//            return contactNote.id;
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to create/update contact note: " + e.getMessage(), e);
//        }
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.DECRYPT_NOSTR_DM)
//    public static Object decryptNostrDM(Netention.Core core, Map<String, Object> params) {
//        if (!(params.get(Netention.ToolParam.EVENT_PAYLOAD_MAP.getKey()) instanceof Map eventMap))
//            throw new IllegalArgumentException("eventPayloadMap (Map) required for DecryptNostrDM.");
//        var content = (String) eventMap.get("content");
//        var pubkey = (String) eventMap.get("pubkey");
//        if (content == null || pubkey == null)
//            throw new IllegalArgumentException("Nostr event content and pubkey required.");
//        if (core.net.getPrivateKeyBech32() == null || core.net.getPrivateKeyBech32().isEmpty())
//            throw new RuntimeException("Nostr private key not available for decryption.");
//        try {
//            var senderPubKeyXOnlyBytes = Crypto.hexToBytes(pubkey);
//            var decryptedText = Crypto.nip04Decrypt(content, Crypto.getSharedSecretWithRetry(Crypto.Bech32.nip19Decode(core.net.getPrivateKeyBech32()), senderPubKeyXOnlyBytes));
//            if (decryptedText.contains("\"type\": \"netention_lm_result\"")) {
//                return Map.of("decryptedText", decryptedText, "isLmResult", true, "lmResultPayload", core.json.readValue(decryptedText, new TypeReference<Map<String, Object>>() {
//                }));
//            }
//            return Map.of("decryptedText", decryptedText, "isLmResult", false);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to decrypt Nostr DM: " + e.getMessage(), e);
//        }
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.UPDATE_CHAT_NOTE)
//    public static Object updateChatNote(Netention.Core core, Map<String, Object> params) {
//        var partnerPubKeyHex = (String) params.get(Netention.ToolParam.PARTNER_PUB_KEY_HEX.getKey());
//        var senderPubKeyHex = (String) params.get(Netention.ToolParam.SENDER_PUB_KEY_HEX.getKey());
//        var messageContent = (String) params.get(Netention.ToolParam.MESSAGE_CONTENT.getKey());
//        var tsEpochObj = params.get(Netention.ToolParam.TIMESTAMP_EPOCH_SECONDS.getKey());
//        if (partnerPubKeyHex == null || senderPubKeyHex == null || messageContent == null || tsEpochObj == null)
//            throw new IllegalArgumentException("Missing params for UpdateChatNote");
//
//        var timestampEpoch = (tsEpochObj instanceof Number n) ? n.longValue() : Long.parseLong(tsEpochObj.toString());
//        try {
//            var partnerNpub = Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(partnerPubKeyHex));
//            var senderNpub = Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(senderPubKeyHex));
//            var selfNpub = core.net.getPublicKeyBech32();
//            var chatId = "chat_" + (selfNpub != null && selfNpub.equals(senderNpub) ? partnerNpub : senderNpub);
//
//            var chatNote = core.notes.get(chatId).orElseGet(() -> {
//                var nCN = new Netention.Note("Chat with " + (selfNpub != null && selfNpub.equals(senderNpub) ? partnerNpub : senderNpub).substring(0, 10) + "...", "");
//                nCN.id = chatId;
//                nCN.tags.addAll(List.of(Netention.SystemTag.CHAT.value, "nostr"));
//                nCN.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, (selfNpub != null && selfNpub.equals(senderNpub) ? partnerNpub : senderNpub));
//                nCN.content.put(Netention.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
//                Netention.Core.logger.info("Created new chat note for {}", partnerNpub);
//                return nCN;
//            });
//            var messages = (List<Map<String, String>>) chatNote.content.computeIfAbsent(Netention.ContentKey.MESSAGES.getKey(), k -> new ArrayList<>());
//            messages.add(Map.of("sender", senderNpub, "timestamp", Instant.ofEpochSecond(timestampEpoch).toString(), "text", messageContent));
//            core.saveNote(chatNote);
//            core.fireCoreEvent(Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED, Map.of("chatNoteId", chatId, "message", messages.getLast()));
//            return chatNote.id;
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to update chat note: " + e.getMessage(), e);
//        }
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.GET_SELF_NOSTR_INFO)
//    public static Object getSelfNostrInfo(Netention.Core core, Map<String, Object> params) {
//        return Map.of("pubKeyHex", Objects.toString(core.net.getPublicKeyXOnlyHex(), ""),
//                "myProfileNoteId", Objects.toString(core.cfg.net.myProfileNoteId, ""));
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.SUGGEST_PLAN_STEPS)
//    public static Object suggestPlanSteps(Netention.Core core, Map<String, Object> params) {
//        var goalText = (String) params.get(Netention.ToolParam.GOAL_TEXT.getKey());
//        if (goalText == null || goalText.trim().isEmpty() || !core.lm.isReady()) return Collections.emptyList();
//        return core.lm.decomposeTask(goalText).map(tasks -> tasks.stream().map(taskDesc -> {
//            var step = new Netention.Planner.PlanStep();
//            step.description = taskDesc;
//            step.toolName = Netention.Core.Tool.USER_INTERACTION.name();
//            step.toolParams = Map.of(Netention.ToolParam.PROMPT.getKey(), "Define tool for: " + taskDesc);
//            step.alternatives.add(new Netention.Planner.AlternativeExecution(Netention.Core.Tool.LOG_MESSAGE.name(), Map.of(Netention.ToolParam.MESSAGE.getKey(), "Alt log: " + taskDesc), 0.5, "Fallback"));
//            return step;
//        }).collect(Collectors.toList())).orElse(Collections.emptyList());
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.GET_PLAN_GRAPH_CONTEXT)
//    public static Object getPlanGraphContext(Netention.Core core, Map<String, Object> params) {
//        var noteId = (String) params.get(Netention.ToolParam.NOTE_ID.getKey());
//        Map<String, Object> rootNode = new HashMap<>();
//        core.notes.get(noteId).ifPresent(n -> {
//            rootNode.putAll(Map.of(Netention.NoteProperty.ID.getKey(), n.id, Netention.NoteProperty.TITLE.getKey(), n.getTitle(), Netention.ContentKey.STATUS.getKey(), n.meta.get(Netention.Metadata.PLAN_STATUS.key)));
//            var children = n.links.stream()
//                    .filter(l -> "plan_subgoal_of".equals(l.relationType) || "plan_depends_on".equals(l.relationType))
//                    .flatMap(l -> core.notes.get(l.targetNoteId).stream())
//                    .map(childNote -> Map.of(Netention.NoteProperty.ID.getKey(), childNote.id, Netention.NoteProperty.TITLE.getKey(), childNote.getTitle(), Netention.ContentKey.STATUS.getKey(), childNote.meta.get(Netention.Metadata.PLAN_STATUS.key), "relation", childNote.links.stream().filter(cl -> cl.targetNoteId.equals(n.id)).findFirst().map(cl -> cl.relationType).orElse("unknown"))).collect(Collectors.toList());
//            rootNode.put("children", children);
//            var parents = core.notes.getAll(otherNote -> otherNote.links.stream().anyMatch(l -> l.targetNoteId.equals(noteId) && ("plan_subgoal_of".equals(l.relationType) || "plan_depends_on".equals(l.relationType))))
//                    .stream().flatMap(parentNote -> parentNote.links.stream().filter(l -> l.targetNoteId.equals(noteId) && ("plan_subgoal_of".equals(l.relationType) || "plan_depends_on".equals(l.relationType))).findFirst().stream().map(relevantLink -> Map.of(Netention.NoteProperty.ID.getKey(), parentNote.id, Netention.NoteProperty.TITLE.getKey(), parentNote.getTitle(), Netention.ContentKey.STATUS.getKey(), parentNote.meta.get(Netention.Metadata.PLAN_STATUS.key), "relation", relevantLink.relationType))).collect(Collectors.toList());
//            rootNode.put("parents", parents);
//        });
//        return rootNode;
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.IF_ELSE)
//    public static Object ifElse(Netention.Core core, Map<String, Object> params) {
//        var conditionResult = params.get(Netention.ToolParam.CONDITION.getKey());
//        var trueSteps = (List<Map<String, Object>>) params.get(Netention.ToolParam.TRUE_STEPS.getKey());
//        var falseSteps = (List<Map<String, Object>>) params.get(Netention.ToolParam.FALSE_STEPS.getKey());
//        var stepsToExecute = Boolean.TRUE.equals(conditionResult) ? trueSteps : falseSteps;
//        Object lastResult = null;
//
//        if (stepsToExecute != null) {
//            var currentPlanExecOpt = core.planner.getActive().values().stream()
//                    .filter(pe -> pe.steps.stream().anyMatch(s -> Netention.Core.Tool.IF_ELSE.name().equals(s.toolName) && Netention.PlanStepState.RUNNING.equals(s.status) && Objects.equals(s.toolParams, params)))
//                    .findFirst();
//            if (currentPlanExecOpt.isEmpty())
//                Netention.Core.logger.warn("IfElse tool could not determine current plan execution context.");
//
//            for (var stepDef : stepsToExecute) {
//                var toolNameStr = (String) stepDef.get(Netention.PlanStepKey.TOOL_NAME.getKey());
//                var toolParams = (Map<String, Object>) stepDef.get(Netention.PlanStepKey.TOOL_PARAMS.getKey());
//                Map<String, Object> resolvedSubParams = new HashMap<>();
//                if (toolParams != null) {
//                    if (currentPlanExecOpt.isPresent()) {
//                        var currentPlanExec = currentPlanExecOpt.get();
//                        for (var entry : toolParams.entrySet()) {
//                            var value = entry.getValue();
//                            resolvedSubParams.put(entry.getKey(), value instanceof String valStr && valStr.startsWith("$") ? core.planner.resolveContextValue(valStr, currentPlanExec) : value);
//                        }
//                    } else resolvedSubParams.putAll(toolParams);
//                }
//                try {
//                    lastResult = core.executeTool(Netention.Core.Tool.fromString(toolNameStr), resolvedSubParams);
//                } catch (Exception e) {
//                    throw new RuntimeException("Error in IfElse sub-step " + toolNameStr + ": " + e.getMessage(), e);
//                }
//            }
//        }
//        return lastResult;
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.FOR_EACH)
//    public static Object forEach(Netention.Core core, Map<String, Object> params) {
//        var itemListRaw = params.get(Netention.ToolParam.LIST.getKey());
//        var loopVarName = (String) params.get(Netention.ToolParam.LOOP_VAR.getKey());
//        var loopStepsDef = (List<Map<String, Object>>) params.get(Netention.ToolParam.LOOP_STEPS.getKey());
//
//        if (!(itemListRaw instanceof List<?> itemList))
//            throw new ClassCastException("Parameter 'list' must be a List.");
//        if (loopVarName == null || loopStepsDef == null)
//            throw new IllegalArgumentException("list, loopVar, loopSteps required.");
//
//        List<Object> results = new ArrayList<>();
//        var currentPlanExecOpt = core.planner.getActive().values().stream()
//                .filter(pe -> pe.steps.stream().anyMatch(s -> Netention.Core.Tool.FOR_EACH.name().equals(s.toolName) && Netention.PlanStepState.RUNNING.equals(s.status) && Objects.equals(s.toolParams, params)))
//                .findFirst();
//        if (currentPlanExecOpt.isEmpty() && !itemList.isEmpty())
//            Netention.Core.logger.warn("ForEach tool could not determine current plan execution context.");
//
//        Object lastSubResult;
//        for (var item : itemList) {
//            var tempExecForLoopVar = new Netention.Planner.PlanExecution("temp_for_loopvar_resolution");
//            currentPlanExecOpt.ifPresent(execution -> tempExecForLoopVar.context.putAll(execution.context));
//            tempExecForLoopVar.context.put(loopVarName, item);
//
//            lastSubResult = null;
//            for (var stepDef : loopStepsDef) {
//                var toolNameStr = (String) stepDef.get(Netention.PlanStepKey.TOOL_NAME.getKey());
//                var toolParamsObj = stepDef.get(Netention.PlanStepKey.TOOL_PARAMS.getKey());
//                Map<String, Object> resolvedSubParams;
//
//                switch (toolParamsObj) {
//                    case String paramSourceStr when paramSourceStr.startsWith("$") -> {
//                        var resolvedParamSource = "$previousStep.result".equals(paramSourceStr) ? lastSubResult : core.planner.resolveContextValue(paramSourceStr, tempExecForLoopVar);
//                        if (!(resolvedParamSource instanceof Map))
//                            throw new RuntimeException("ForEach: Resolved param source " + paramSourceStr + " for tool " + toolNameStr + " is not a Map.");
//                        resolvedSubParams = (Map<String, Object>) resolvedParamSource;
//                    }
//                    case Map map -> {
//                        resolvedSubParams = new HashMap<>();
//                        for (var paramEntry : ((Map<String, Object>) map).entrySet()) {
//                            var valStr = Objects.toString(paramEntry.getValue(), null);
//                            resolvedSubParams.put(paramEntry.getKey(), "$previousStep.result".equals(valStr) ? lastSubResult : (valStr != null && valStr.startsWith("$") ? core.planner.resolveContextValue(valStr, tempExecForLoopVar) : paramEntry.getValue()));
//                        }
//                    }
//                    case null -> resolvedSubParams = Collections.emptyMap();
//                    default ->
//                            throw new RuntimeException("ForEach: toolParams for tool " + toolNameStr + " is invalid type.");
//                }
//
//                try {
//                    var currentSubStepResult = core.executeTool(Netention.Core.Tool.fromString(toolNameStr), resolvedSubParams);
//                    lastSubResult = currentSubStepResult;
//                    if (stepDef.containsKey(Netention.PlanStepKey.ID.getKey()))
//                        tempExecForLoopVar.context.put(stepDef.get(Netention.PlanStepKey.ID.getKey()) + ".result", currentSubStepResult);
//                } catch (Exception e) {
//                    throw new RuntimeException("Error in ForEach sub-step " + toolNameStr + " for item " + item + ": " + e.getMessage(), e);
//                }
//            }
//            results.add(lastSubResult);
//        }
//        return results;
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.FIND_NOTES_BY_TAG)
//    public static Object findNotesByTag(Netention.Core core, Map<String, Object> params) {
//        var tag = (String) params.get(Netention.ToolParam.TAG.getKey());
//        if (tag == null) throw new IllegalArgumentException("tag parameter required.");
//        return core.notes.getAll(n -> n.tags.contains(tag)).stream()
//                .map(n -> Map.of(Netention.NoteProperty.ID.getKey(), n.id, Netention.NoteProperty.TITLE.getKey(), n.getTitle()))
//                .collect(Collectors.toList());
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.EXECUTE_SEMANTIC_QUERY)
//    public static Object executeSemanticQuery(Netention.Core core, Map<String, Object> params) {
//        var queryText = (String) params.get(Netention.ToolParam.QUERY_TEXT.getKey());
//        if (queryText == null || !core.lm.isReady()) return Collections.emptyList();
//        var queryEmb = core.lm.generateEmbedding(queryText);
//        if (queryEmb.isEmpty()) return Collections.emptyList();
//
//        var minSimilarity = ((Number) params.getOrDefault(Netention.ToolParam.MIN_SIMILARITY.getKey(), 0.6)).doubleValue();
//        var maxResults = ((Number) params.getOrDefault(Netention.ToolParam.MAX_RESULTS.getKey(), 5)).longValue();
//
//        return core.notes.getAllNotes().stream()
//                .filter(n -> {
//                    if (n.getEmbeddingV1() == null || n.getEmbeddingV1().length != queryEmb.get().length)
//                        return false;
//                    return !n.tags.contains(Netention.SystemTag.CONFIG.value);
//                })
//                .map(candidateNote -> new AbstractMap.SimpleEntry<>(candidateNote, LM.cosineSimilarity(queryEmb.get(), candidateNote.getEmbeddingV1())))
//                .filter(entry -> entry.getValue() > minSimilarity)
//                .sorted(Map.Entry.<Netention.Note, Double>comparingByValue().reversed())
//                .limit(maxResults)
//                .map(entry -> Map.of(Netention.NoteProperty.ID.getKey(), entry.getKey().id, Netention.NoteProperty.TITLE.getKey(), entry.getKey().getTitle(), "similarity", entry.getValue()))
//                .collect(Collectors.toList());
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.GET_CONFIG_STATE)
//    public static Object getConfigState(Netention.Core core, Map<String, Object> params) {
//        var type = (String) params.get(Netention.ToolParam.CONFIG_TYPE.getKey());
//        var configInstance = switch (type) {
//            case "nostr" -> core.cfg.net;
//            case "ui" -> core.cfg.ui;
//            case "llm" -> core.cfg.lm;
//            default -> throw new IllegalArgumentException("Unknown configType: " + type);
//        };
//        return core.json.convertValue(configInstance, new TypeReference<Map<String, Object>>() {
//        });
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.APPLY_CONFIG_STATE)
//    public static Object applyConfigState(Netention.Core core, Map<String, Object> params) {
//        var type = (String) params.get(Netention.ToolParam.CONFIG_TYPE.getKey());
//        var stateMap = (Map<String, Object>) params.get(Netention.ToolParam.STATE_MAP.getKey());
//        if (stateMap == null) {
//            Netention.Core.logger.warn("ApplyConfigState called with null stateMap for type {}, skipping.", type);
//            return "Skipped: null stateMap";
//        }
//
//        var configInstance = switch (type) {
//            case "nostr" -> core.cfg.net;
//            case "ui" -> core.cfg.ui;
//            case "llm" -> core.cfg.lm;
//            default -> throw new IllegalArgumentException("Unknown configType: " + type);
//        };
//        var targetClass = configInstance.getClass();
//        var updatedInstance = core.json.convertValue(stateMap, targetClass);
//        for (var field : targetClass.getDeclaredFields()) {
//            try {
//                field.setAccessible(true);
//                field.set(configInstance, field.get(updatedInstance));
//            } catch (Exception e) {
//                Netention.Core.logger.error("Error applying config field {}: {}", field.getName(), e.getMessage());
//            }
//        }
//        switch (type) {
//            case "nostr" -> {
//                if (core.net.isEnabled()) {
//                    core.net.setEnabled(false);
//                    core.net.setEnabled(true);
//                } else core.net.loadIdentity();
//            }
//            case "llm" -> core.lm.init();
//            case "ui" -> core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "ui_theme_updated");
//        }
//        return type + " config applied and services re-initialized if applicable.";
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.FIRE_CORE_EVENT)
//    public static Object fireCoreEventTool(Netention.Core core, Map<String, Object> params) {
//        var eventTypeStr = (String) params.get(Netention.ToolParam.EVENT_TYPE.getKey());
//        var eventData = (Map<String, Object>) params.get(Netention.ToolParam.EVENT_DATA.getKey());
//        if (eventTypeStr == null)
//            throw new IllegalArgumentException("eventType is required for FireCoreEvent tool.");
//        try {
//            var eventType = Netention.Core.CoreEventType.valueOf(eventTypeStr);
//            core.fireCoreEvent(eventType, eventData);
//            return "Event " + eventType + " fired directly.";
//        } catch (IllegalArgumentException e) {
//            throw new RuntimeException("Invalid CoreEventType: " + eventTypeStr, e);
//        }
//    }
//
//    @SuppressWarnings("unchecked")
//    @ToolAction(tool = Netention.Core.Tool.SCHEDULE_SYSTEM_EVENT)
//    public static Object scheduleSystemEvent(Netention.Core core, Map<String, Object> params) {
//        var eventType = (String) params.get(Netention.ToolParam.EVENT_TYPE.getKey());
//        var payload = (Map<String, Object>) params.get(Netention.ToolParam.PAYLOAD.getKey());
//        var delaySecondsNum = (Number) params.get(Netention.ToolParam.DELAY_SECONDS.getKey());
//        if (eventType == null || delaySecondsNum == null)
//            throw new IllegalArgumentException("eventType and delaySeconds required.");
//        var delaySeconds = delaySecondsNum.longValue();
//        core.scheduler.schedule(() -> core.fireCoreEvent(Netention.Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
//                Netention.ToolParam.EVENT_TYPE.getKey(), eventType,
//                Netention.ToolParam.PAYLOAD.getKey(), Objects.requireNonNullElse(payload, Collections.emptyMap()),
//                Netention.ContentKey.STATUS.getKey(), Netention.PlanState.PENDING.name()
//        )), delaySeconds, TimeUnit.SECONDS);
//        return "System event " + eventType + " scheduled in " + delaySeconds + "s.";
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.GET_SYSTEM_HEALTH_METRICS)
//    public static Object getSystemHealthMetrics(Netention.Core core, Map<String, Object> params) {
//        Map<String, Object> metrics = new HashMap<>();
//        metrics.put("pendingSystemEvents", core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.SYSTEM_EVENT.value) && Netention.PlanState.PENDING.name().equals(n.content.get(Netention.ContentKey.STATUS.getKey()))).size());
//        metrics.put("activePlans", core.planner.getActive().size());
//        metrics.put("failedPlanStepsInActivePlans", core.planner.getActive().values().stream().flatMap(exec -> exec.steps.stream()).filter(step -> Netention.PlanStepState.FAILED.equals(step.status)).count());
//        metrics.put("nostrStatus", core.net.isEnabled() ? "ENABLED" : "DISABLED");
//        metrics.put("lmStatus", core.lm.isReady() ? "READY" : "NOT_READY");
//        return metrics;
//    }
//
//    @ToolAction(tool = Netention.Core.Tool.IDENTIFY_STALLED_PLANS)
//    public static Object identifyStalledPlans(Netention.Core core, Map<String, Object> params) {
//        var stallThresholdSeconds = ((Number) params.getOrDefault(Netention.ToolParam.STALL_THRESHOLD_SECONDS.getKey(), 3600L)).longValue();
//        List<String> stalledPlanNoteIds = new ArrayList<>();
//        core.planner.getActive().forEach((planNoteId, exec) -> {
//            if (Netention.PlanState.RUNNING.equals(exec.currentStatus) || Netention.PlanState.STUCK.equals(exec.currentStatus)) {
//                var recentProgress = exec.steps.stream().anyMatch(step -> step.endTime != null && Duration.between(step.endTime, Instant.now()).getSeconds() < stallThresholdSeconds);
//                if (!recentProgress) {
//                    var stuckRunningStep = exec.steps.stream().anyMatch(step -> Netention.PlanStepState.RUNNING.equals(step.status) && step.startTime != null && Duration.between(step.startTime, Instant.now()).getSeconds() > stallThresholdSeconds);
//                    if (stuckRunningStep || Netention.PlanState.STUCK.equals(exec.currentStatus)) {
//                        stalledPlanNoteIds.add(planNoteId);
//                        core.fireCoreEvent(Netention.Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
//                                Netention.ToolParam.EVENT_TYPE.getKey(), Netention.Core.SystemEventType.STALLED_PLAN_DETECTED.name(),
//                                Netention.ToolParam.PAYLOAD.getKey(), Map.of(Netention.ToolParam.PLAN_NOTE_ID.getKey(), planNoteId, Netention.Metadata.PLAN_STATUS.key, exec.currentStatus.name()),
//                                Netention.ContentKey.STATUS.getKey(), Netention.PlanState.PENDING.name()
//                        ));
//                    }
//                }
//            }
//        });
//        return Map.of("identifiedStalledPlanNoteIds", stalledPlanNoteIds);
//    }
//
//    @Retention(RetentionPolicy.RUNTIME)
//    @Target(ElementType.METHOD)
//    public @interface ToolAction {
//        Netention.Core.Tool tool();
//    }
//}
