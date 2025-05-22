package dumb.note;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

public class Tools {
    public static void registerAllTools(Map<Netention.Core.Tool, BiFunction<Netention.Core, Map<String, Object>, Object>> toolMap) {
        Stream.of(Tools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ToolAction.class) && java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                .forEach(method -> {
                    var annotation = method.getAnnotation(ToolAction.class);
                    var toolEnum = annotation.tool();
                    toolMap.put(toolEnum, (coreInstance, paramsMap) -> {
                        try {
                            return method.invoke(null, coreInstance, paramsMap);
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            var cause = e.getCause() != null ? e.getCause() : e;
                            Netention.Core.logger.error("Error invoking tool {}: {}", toolEnum, cause.getMessage(), cause);
                            if (cause instanceof RuntimeException re) throw re;
                            throw new RuntimeException("Error invoking tool " + toolEnum, cause);
                        }
                    });
                });

        Stream.of(Netention.Core.Tool.values())
                .filter(Netention.Core.Tool::isPlaceholder)
                .filter(toolEnum -> !toolMap.containsKey(toolEnum))
                .forEach(toolEnum -> toolMap.put(toolEnum, (c, p) -> toolEnum.name() + " (placeholder). Params: " + p));
    }

    @ToolAction(tool = Netention.Core.Tool.LOG_MESSAGE)
    public static Object logMessage(Netention.Core core, Map<String, Object> params) {
        var message = Objects.toString(params.get(Netention.Planner.ToolParam.MESSAGE.getKey()), "No message provided.");
        Netention.Core.logger.info("TOOL_LOG: {}", message);
        return "Logged: " + message;
    }

    @ToolAction(tool = Netention.Core.Tool.USER_INTERACTION)
    public static Object userInteraction(Netention.Core core, Map<String, Object> params) {
        return "This tool is handled specially by the Planner via events.";
    }

    @ToolAction(tool = Netention.Core.Tool.PARSE_JSON)
    public static Object parseJson(Netention.Core core, Map<String, Object> params) {
        var jsonString = Objects.toString(params.get(Netention.Planner.ToolParam.JSON_STRING.getKey()), null);
        if (jsonString == null)
            throw new IllegalArgumentException("jsonString parameter is required for ParseJson tool.");
        try {
            return core.json.readTree(jsonString);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.GET_NOTE_PROPERTY)
    public static Object getNoteProperty(Netention.Core core, Map<String, Object> params) {
        var noteId = (String) params.get(Netention.Planner.ToolParam.NOTE_ID.getKey());
        var propertyPath = (String) params.get(Netention.Planner.ToolParam.PROPERTY_PATH.getKey());
        var fifnRaw = params.get(Netention.Planner.ToolParam.FAIL_IF_NOT_FOUND.getKey());
        var failIfNotFound = fifnRaw instanceof Boolean b ? b : (!(fifnRaw instanceof String s) || Boolean.parseBoolean(s));
        var defaultValue = params.get(Netention.Planner.ToolParam.DEFAULT_VALUE.getKey());

        var noteOpt = core.notes.get(noteId);
        if (noteOpt.isEmpty()) {
            if (failIfNotFound) throw new RuntimeException("Note not found: " + noteId);
            return defaultValue;
        }
        var note = noteOpt.get();
        try {
            Object value = note;
            var parts = propertyPath.split("\\.");
            label:
            for (var part : parts) {
                switch (value) {
                    case null -> {
                        break label;
                    }
                    case Netention.Note n -> value = Netention.Core.getNoteSpecificProperty(n, part);
                    case Map m -> value = m.get(part);
                    case JsonNode jn -> value = jn.has(part) ? jn.get(part) : null;
                    default -> {
                        value = null;
                        break label;
                    }
                }
            }
            if (value instanceof JsonNode jn && jn.isValueNode()) {
                return jn.isTextual() ? jn.asText() : (jn.isNumber() ? jn.numberValue() : (jn.isBoolean() ? jn.asBoolean() : jn.toString()));
            }
            if (value == null) {
                if (failIfNotFound && defaultValue == null)
                    throw new RuntimeException("Property not found: " + propertyPath + " in note " + noteId);
                return defaultValue;
            }
            return value;
        } catch (Exception e) {
            if (failIfNotFound && defaultValue == null)
                throw new RuntimeException("Error accessing property " + propertyPath + ": " + e.getMessage(), e);
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.CREATE_NOTE)
    public static Object createNote(Netention.Core core, Map<String, Object> params) {
        var newNote = new Netention.Note();
        if (params.containsKey(Netention.Planner.ToolParam.ID.getKey()))
            newNote.id = (String) params.get(Netention.Planner.ToolParam.ID.getKey());

        var titleTemplate = (String) params.getOrDefault(Netention.Planner.ToolParam.TITLE.getKey(), "New Note from Plan");
        var titleMatcher = Pattern.compile("\\$([a-zA-Z0-9_.]+)_substring_(\\d+)").matcher(titleTemplate);
        if (titleMatcher.find()) {
            var prefix = titleTemplate.substring(0, titleMatcher.start());
            var varName = titleMatcher.group(1);
            var len = Integer.parseInt(titleMatcher.group(2));
            var suffix = titleTemplate.substring(titleMatcher.end());
            var resolvedVar = params.get(varName); // This assumes params map contains direct values, not context paths
            if (resolvedVar instanceof String contentVal) {
                var actualTitle = contentVal.substring(0, Math.min(contentVal.length(), len)) + (contentVal.length() > len ? "..." : "");
                newNote.setTitle(prefix + actualTitle + suffix);
            } else newNote.setTitle(titleTemplate);
        } else newNote.setTitle(titleTemplate);

        var text = (String) params.getOrDefault(Netention.Planner.ToolParam.TEXT.getKey(), "");
        if (Boolean.TRUE.equals(params.get(Netention.Planner.ToolParam.AS_HTML.getKey()))) newNote.setHtmlText(text);
        else newNote.setText(text);

        if (params.get(Netention.Planner.ToolParam.TAGS.getKey()) instanceof List)
            newNote.tags.addAll((List<String>) params.get(Netention.Planner.ToolParam.TAGS.getKey()));
        if (params.get(Netention.Planner.ToolParam.CONTENT.getKey()) instanceof Map)
            newNote.content.putAll((Map<String, Object>) params.get(Netention.Planner.ToolParam.CONTENT.getKey()));

        Map<String, Object> metadata = params.get(Netention.Planner.ToolParam.METADATA.getKey()) instanceof Map ? new HashMap<>((Map<String, Object>) params.get(Netention.Planner.ToolParam.METADATA.getKey())) : new HashMap<>();
        if (metadata.containsKey(Netention.Metadata.CREATED_AT_FROM_EVENT.key)) {
            var tsValue = metadata.remove(Netention.Metadata.CREATED_AT_FROM_EVENT.key);
            var eventTime = (tsValue instanceof Number n) ? Instant.ofEpochSecond(n.longValue()) : ((tsValue instanceof Instant i) ? i : null);
            if (eventTime != null) {
                newNote.createdAt = eventTime;
                newNote.updatedAt = eventTime;
            }
        }
        if (metadata.containsKey(Netention.Metadata.NOSTR_RAW_EVENT.key)) {
            var rawEventObj = metadata.remove(Netention.Metadata.NOSTR_RAW_EVENT.key);
            try {
                newNote.meta.put(Netention.Metadata.NOSTR_RAW_EVENT.key, rawEventObj instanceof String s ? s : core.json.writeValueAsString(rawEventObj));
            } catch (JsonProcessingException e) {
                Netention.Core.logger.warn("Could not serialize nostrRawEvent for note metadata.");
            }
        }
        if (metadata.containsKey(Netention.Metadata.NOSTR_PUB_KEY.key)) {
            if (metadata.get(Netention.Metadata.NOSTR_PUB_KEY.key) instanceof String pubkeyHex) {
                try {
                    newNote.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(pubkeyHex)));
                } catch (Exception ex) {
                    Netention.Core.logger.warn("Failed to encode npub from hex in CreateNote metadata: {}", pubkeyHex);
                }
            }
        }
        newNote.meta.putAll(metadata);
        core.saveNote(newNote);
        return newNote.id;
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.MODIFY_NOTE_CONTENT)
    public static Object modifyNoteContent(Netention.Core core, Map<String, Object> params) {
        var noteId = (String) params.get(Netention.Planner.ToolParam.NOTE_ID.getKey());
        var contentUpdate = (Map<String, Object>) params.get(Netention.Planner.ToolParam.CONTENT_UPDATE.getKey());
        if (noteId == null || contentUpdate == null)
            throw new IllegalArgumentException("noteId and contentUpdate are required.");

        return core.notes.get(noteId).map(note -> {
            Map<String, Object> updateCopy = new HashMap<>(contentUpdate); // Work on a copy
            if ("NOW".equals(updateCopy.get(Netention.ContentKey.LAST_RUN.getKey())))
                updateCopy.put(Netention.ContentKey.LAST_RUN.getKey(), Instant.now().toString());

            if (updateCopy.get("metadataUpdate") instanceof Map metadataUpdatesRaw) {
                Map<String, Object> metadataUpdates = new HashMap<>(metadataUpdatesRaw);
                if ("NOW".equals(metadataUpdates.get(Netention.Metadata.PROFILE_LAST_UPDATED_AT.key))) {
                    metadataUpdates.put(Netention.Metadata.PROFILE_LAST_UPDATED_AT.key, Instant.now().toString());
                }
                note.meta.putAll(metadataUpdates);
                updateCopy.remove("metadataUpdate");
            }
            note.content.putAll(updateCopy);
            core.saveNote(note);
            return true;
        }).orElse(false);
    }

    @ToolAction(tool = Netention.Core.Tool.DELETE_NOTE)
    public static Object deleteNote(Netention.Core core, Map<String, Object> params) {
        return core.deleteNote((String) params.get(Netention.Planner.ToolParam.NOTE_ID.getKey()));
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.CREATE_LINKS)
    public static Object createLinks(Netention.Core core, Map<String, Object> params) {
        var sourceNoteId = (String) params.get(Netention.Planner.ToolParam.SOURCE_NOTE_ID.getKey());
        var linksToAdd = (List<Map<String, String>>) params.get(Netention.Planner.ToolParam.LINKS.getKey());
        if (sourceNoteId == null || linksToAdd == null)
            throw new IllegalArgumentException("sourceNoteId and links required.");
        core.notes.get(sourceNoteId).ifPresent(sourceNote -> {
            linksToAdd.forEach(linkMap -> sourceNote.links.add(new Netention.Link(linkMap.get("targetNoteId"), linkMap.get("relationType"))));
            core.saveNote(sourceNote);
        });
        return "Links added to " + sourceNoteId;
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.CREATE_OR_UPDATE_CONTACT_NOTE)
    public static Object createOrUpdateContactNote(Netention.Core core, Map<String, Object> params) {
        var nostrPubKeyHex = (String) params.get(Netention.Planner.ToolParam.NOSTR_PUB_KEY_HEX.getKey());
        var profileData = (Map<String, Object>) params.get(Netention.Planner.ToolParam.PROFILE_DATA.getKey());
        if (nostrPubKeyHex == null) throw new IllegalArgumentException("nostrPubKeyHex is required.");
        try {
            var nostrPubKeyNpub = Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(nostrPubKeyHex));
            var contactNoteId = "contact_" + nostrPubKeyHex;
            var contactNote = core.notes.get(contactNoteId).orElseGet(() -> {
                var n = new Netention.Note("Contact: " + nostrPubKeyNpub.substring(0, Math.min(nostrPubKeyNpub.length(), 12)) + "...", "");
                n.id = contactNoteId;
                n.tags.addAll(Arrays.asList(Netention.SystemTag.CONTACT.value, Netention.SystemTag.NOSTR_CONTACT.value));
                n.meta.putAll(Map.of(Netention.Metadata.NOSTR_PUB_KEY.key, nostrPubKeyNpub, Netention.Metadata.NOSTR_PUB_KEY_HEX.key, nostrPubKeyHex));
                Netention.Core.logger.info("Created new contact note for {}", nostrPubKeyNpub);
                return n;
            });
            contactNote.meta.put(Netention.Metadata.LAST_SEEN.key, Instant.now().toString());
            if (profileData != null) {
                ofNullable(profileData.get("name")).ifPresent(name -> contactNote.content.put(Netention.ContentKey.PROFILE_NAME.getKey(), name));
                ofNullable(profileData.get("about")).ifPresent(about -> contactNote.content.put(Netention.ContentKey.PROFILE_ABOUT.getKey(), about));
                ofNullable(profileData.get("picture")).ifPresent(pic -> contactNote.content.put(Netention.ContentKey.PROFILE_PICTURE_URL.getKey(), pic));
                contactNote.meta.put(Netention.Metadata.PROFILE_LAST_UPDATED_AT.key, Instant.now().toString());
            }
            core.saveNote(contactNote);
            return contactNote.id;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create/update contact note: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.DECRYPT_NOSTR_DM)
    public static Object decryptNostrDM(Netention.Core core, Map<String, Object> params) {
        if (!(params.get(Netention.Planner.ToolParam.EVENT_PAYLOAD_MAP.getKey()) instanceof Map eventMap))
            throw new IllegalArgumentException("eventPayloadMap (Map) required for DecryptNostrDM.");
        var content = (String) eventMap.get("content");
        var pubkey = (String) eventMap.get("pubkey");
        if (content == null || pubkey == null)
            throw new IllegalArgumentException("Nostr event content and pubkey required.");
        if (core.net.getPrivateKeyBech32() == null || core.net.getPrivateKeyBech32().isEmpty())
            throw new RuntimeException("Nostr private key not available for decryption.");
        try {
            var senderPubKeyXOnlyBytes = Crypto.hexToBytes(pubkey);
            var decryptedText = Crypto.nip04Decrypt(content, Crypto.getSharedSecretWithRetry(Crypto.Bech32.nip19Decode(core.net.getPrivateKeyBech32()), senderPubKeyXOnlyBytes));
            if (decryptedText.contains("\"type\": \"netention_lm_result\"")) {
                return Map.of("decryptedText", decryptedText, "isLmResult", true, "lmResultPayload", core.json.readValue(decryptedText, new TypeReference<Map<String, Object>>() {
                }));
            }
            return Map.of("decryptedText", decryptedText, "isLmResult", false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt Nostr DM: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.UPDATE_CHAT_NOTE)
    public static Object updateChatNote(Netention.Core core, Map<String, Object> params) {
        var partnerPubKeyHex = (String) params.get(Netention.Planner.ToolParam.PARTNER_PUB_KEY_HEX.getKey());
        var senderPubKeyHex = (String) params.get(Netention.Planner.ToolParam.SENDER_PUB_KEY_HEX.getKey());
        var messageContent = (String) params.get(Netention.Planner.ToolParam.MESSAGE_CONTENT.getKey());
        var tsEpochObj = params.get(Netention.Planner.ToolParam.TIMESTAMP_EPOCH_SECONDS.getKey());
        if (partnerPubKeyHex == null || senderPubKeyHex == null || messageContent == null || tsEpochObj == null)
            throw new IllegalArgumentException("Missing params for UpdateChatNote");

        var timestampEpoch = (tsEpochObj instanceof Number n) ? n.longValue() : Long.parseLong(tsEpochObj.toString());
        try {
            var partnerNpub = Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(partnerPubKeyHex));
            var senderNpub = Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(senderPubKeyHex));
            var selfNpub = core.net.getPublicKeyBech32();
            var chatId = "chat_" + (selfNpub != null && selfNpub.equals(senderNpub) ? partnerNpub : senderNpub);

            var chatNote = core.notes.get(chatId).orElseGet(() -> {
                var nCN = new Netention.Note("Chat with " + (selfNpub != null && selfNpub.equals(senderNpub) ? partnerNpub : senderNpub).substring(0, 10) + "...", "");
                nCN.id = chatId;
                nCN.tags.addAll(List.of(Netention.SystemTag.CHAT.value, "nostr"));
                nCN.meta.put(Netention.Metadata.NOSTR_PUB_KEY.key, (selfNpub != null && selfNpub.equals(senderNpub) ? partnerNpub : senderNpub));
                nCN.content.put(Netention.ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                Netention.Core.logger.info("Created new chat note for {}", partnerNpub);
                return nCN;
            });
            var messages = (List<Map<String, String>>) chatNote.content.computeIfAbsent(Netention.ContentKey.MESSAGES.getKey(), k -> new ArrayList<>());
            messages.add(Map.of("sender", senderNpub, "timestamp", Instant.ofEpochSecond(timestampEpoch).toString(), "text", messageContent));
            core.saveNote(chatNote);
            core.fireCoreEvent(Netention.Core.CoreEventType.CHAT_MESSAGE_ADDED, Map.of("chatNoteId", chatId, "message", messages.getLast()));
            return chatNote.id;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update chat note: " + e.getMessage(), e);
        }
    }

    @ToolAction(tool = Netention.Core.Tool.GET_SELF_NOSTR_INFO)
    public static Object getSelfNostrInfo(Netention.Core core, Map<String, Object> params) {
        return Map.of("pubKeyHex", Objects.toString(core.net.getPublicKeyXOnlyHex(), ""),
                "myProfileNoteId", Objects.toString(core.cfg.net.myProfileNoteId, ""));
    }

    @ToolAction(tool = Netention.Core.Tool.SUGGEST_PLAN_STEPS)
    public static Object suggestPlanSteps(Netention.Core core, Map<String, Object> params) {
        var goalText = (String) params.get(Netention.Planner.ToolParam.GOAL_TEXT.getKey());
        if (goalText == null || goalText.trim().isEmpty() || !core.lm.isReady()) return Collections.emptyList();
        return core.lm.decomposeTask(goalText).map(tasks -> tasks.stream().map(taskDesc -> {
            var step = new Netention.Planner.PlanStep();
            step.description = taskDesc;
            step.toolName = Netention.Core.Tool.USER_INTERACTION.name();
            step.toolParams = Map.of(Netention.Planner.ToolParam.PROMPT.getKey(), "Define tool for: " + taskDesc);
            step.alternatives.add(new Netention.Planner.AlternativeExecution(Netention.Core.Tool.LOG_MESSAGE.name(), Map.of(Netention.Planner.ToolParam.MESSAGE.getKey(), "Alt log: " + taskDesc), 0.5, "Fallback"));
            return step;
        }).collect(Collectors.toList())).orElse(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.GET_PLAN_GRAPH_CONTEXT)
    public static Object getPlanGraphContext(Netention.Core core, Map<String, Object> params) {
        var noteId = (String) params.get(Netention.Planner.ToolParam.NOTE_ID.getKey());
        Map<String, Object> rootNode = new HashMap<>();
        core.notes.get(noteId).ifPresent(n -> {
            rootNode.putAll(Map.of(Netention.NoteProperty.ID.getKey(), n.id, Netention.NoteProperty.TITLE.getKey(), n.getTitle(), Netention.ContentKey.STATUS.getKey(), n.meta.get(Netention.Metadata.PLAN_STATUS.key)));
            var children = n.links.stream()
                    .filter(l -> "plan_subgoal_of".equals(l.relationType) || "plan_depends_on".equals(l.relationType))
                    .flatMap(l -> core.notes.get(l.targetNoteId).stream())
                    .map(childNote -> Map.of(Netention.NoteProperty.ID.getKey(), childNote.id, Netention.NoteProperty.TITLE.getKey(), childNote.getTitle(), Netention.ContentKey.STATUS.getKey(), childNote.meta.get(Netention.Metadata.PLAN_STATUS.key), "relation", childNote.links.stream().filter(cl -> cl.targetNoteId.equals(n.id)).findFirst().map(cl -> cl.relationType).orElse("unknown"))).collect(Collectors.toList());
            rootNode.put("children", children);
            var parents = core.notes.getAll(otherNote -> otherNote.links.stream().anyMatch(l -> l.targetNoteId.equals(noteId) && ("plan_subgoal_of".equals(l.relationType) || "plan_depends_on".equals(l.relationType))))
                    .stream().flatMap(parentNote -> parentNote.links.stream().filter(l -> l.targetNoteId.equals(noteId) && ("plan_subgoal_of".equals(l.relationType) || "plan_depends_on".equals(l.relationType))).findFirst().stream().map(relevantLink -> Map.of(Netention.NoteProperty.ID.getKey(), parentNote.id, Netention.NoteProperty.TITLE.getKey(), parentNote.getTitle(), Netention.ContentKey.STATUS.getKey(), parentNote.meta.get(Netention.Metadata.PLAN_STATUS.key), "relation", relevantLink.relationType))).collect(Collectors.toList());
            rootNode.put("parents", parents);
        });
        return rootNode;
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.IF_ELSE)
    public static Object ifElse(Netention.Core core, Map<String, Object> params) {
        var conditionResult = params.get(Netention.Planner.ToolParam.CONDITION.getKey());
        var trueSteps = (List<Map<String, Object>>) params.get(Netention.Planner.ToolParam.TRUE_STEPS.getKey());
        var falseSteps = (List<Map<String, Object>>) params.get(Netention.Planner.ToolParam.FALSE_STEPS.getKey());
        var stepsToExecute = Boolean.TRUE.equals(conditionResult) ? trueSteps : falseSteps;
        Object lastResult = null;

        if (stepsToExecute != null) {
            var currentPlanExecOpt = core.planner.getActivePlans().values().stream()
                    .filter(pe -> pe.steps.stream().anyMatch(s -> Netention.Core.Tool.IF_ELSE.name().equals(s.toolName) && Netention.Planner.PlanStepState.RUNNING.equals(s.status) && Objects.equals(s.toolParams, params)))
                    .findFirst();
            if (currentPlanExecOpt.isEmpty())
                Netention.Core.logger.warn("IfElse tool could not determine current plan execution context.");

            for (var stepDef : stepsToExecute) {
                var toolNameStr = (String) stepDef.get(Netention.Planner.PlanStepKey.TOOL_NAME.getKey());
                var toolParams = (Map<String, Object>) stepDef.get(Netention.Planner.PlanStepKey.TOOL_PARAMS.getKey());
                Map<String, Object> resolvedSubParams = new HashMap<>();
                if (toolParams != null) {
                    if (currentPlanExecOpt.isPresent()) {
                        var currentPlanExec = currentPlanExecOpt.get();
                        for (var entry : toolParams.entrySet()) {
                            var value = entry.getValue();
                            resolvedSubParams.put(entry.getKey(), value instanceof String valStr && valStr.startsWith("$") ? core.planner.resolveContextValue(valStr, currentPlanExec) : value);
                        }
                    } else resolvedSubParams.putAll(toolParams);
                }
                try {
                    lastResult = core.executeTool(Netention.Core.Tool.fromString(toolNameStr), resolvedSubParams);
                } catch (Exception e) {
                    throw new RuntimeException("Error in IfElse sub-step " + toolNameStr + ": " + e.getMessage(), e);
                }
            }
        }
        return lastResult;
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.FOR_EACH)
    public static Object forEach(Netention.Core core, Map<String, Object> params) {
        var itemListRaw = params.get(Netention.Planner.ToolParam.LIST.getKey());
        var loopVarName = (String) params.get(Netention.Planner.ToolParam.LOOP_VAR.getKey());
        var loopStepsDef = (List<Map<String, Object>>) params.get(Netention.Planner.ToolParam.LOOP_STEPS.getKey());

        if (!(itemListRaw instanceof List<?> itemList))
            throw new ClassCastException("Parameter 'list' must be a List.");
        if (loopVarName == null || loopStepsDef == null)
            throw new IllegalArgumentException("list, loopVar, loopSteps required.");

        List<Object> results = new ArrayList<>();
        var currentPlanExecOpt = core.planner.getActivePlans().values().stream()
                .filter(pe -> pe.steps.stream().anyMatch(s -> Netention.Core.Tool.FOR_EACH.name().equals(s.toolName) && Netention.Planner.PlanStepState.RUNNING.equals(s.status) && Objects.equals(s.toolParams, params)))
                .findFirst();
        if (currentPlanExecOpt.isEmpty() && !itemList.isEmpty())
            Netention.Core.logger.warn("ForEach tool could not determine current plan execution context.");

        Object lastSubResult;
        for (var item : itemList) {
            var tempExecForLoopVar = new Netention.Planner.PlanExecution("temp_for_loopvar_resolution");
            currentPlanExecOpt.ifPresent(execution -> tempExecForLoopVar.context.putAll(execution.context));
            tempExecForLoopVar.context.put(loopVarName, item);

            lastSubResult = null;
            for (var stepDef : loopStepsDef) {
                var toolNameStr = (String) stepDef.get(Netention.Planner.PlanStepKey.TOOL_NAME.getKey());
                var toolParamsObj = stepDef.get(Netention.Planner.PlanStepKey.TOOL_PARAMS.getKey());
                Map<String, Object> resolvedSubParams;

                switch (toolParamsObj) {
                    case String paramSourceStr when paramSourceStr.startsWith("$") -> {
                        var resolvedParamSource = "$previousStep.result".equals(paramSourceStr) ? lastSubResult : core.planner.resolveContextValue(paramSourceStr, tempExecForLoopVar);
                        if (!(resolvedParamSource instanceof Map))
                            throw new RuntimeException("ForEach: Resolved param source " + paramSourceStr + " for tool " + toolNameStr + " is not a Map.");
                        resolvedSubParams = (Map<String, Object>) resolvedParamSource;
                    }
                    case Map map -> {
                        resolvedSubParams = new HashMap<>();
                        for (var paramEntry : ((Map<String, Object>) map).entrySet()) {
                            var valStr = Objects.toString(paramEntry.getValue(), null);
                            resolvedSubParams.put(paramEntry.getKey(), "$previousStep.result".equals(valStr) ? lastSubResult : (valStr != null && valStr.startsWith("$") ? core.planner.resolveContextValue(valStr, tempExecForLoopVar) : paramEntry.getValue()));
                        }
                    }
                    case null -> resolvedSubParams = Collections.emptyMap();
                    default ->
                            throw new RuntimeException("ForEach: toolParams for tool " + toolNameStr + " is invalid type.");
                }

                try {
                    var currentSubStepResult = core.executeTool(Netention.Core.Tool.fromString(toolNameStr), resolvedSubParams);
                    lastSubResult = currentSubStepResult;
                    if (stepDef.containsKey(Netention.Planner.PlanStepKey.ID.getKey()))
                        tempExecForLoopVar.context.put(stepDef.get(Netention.Planner.PlanStepKey.ID.getKey()) + ".result", currentSubStepResult);
                } catch (Exception e) {
                    throw new RuntimeException("Error in ForEach sub-step " + toolNameStr + " for item " + item + ": " + e.getMessage(), e);
                }
            }
            results.add(lastSubResult);
        }
        return results;
    }

    @ToolAction(tool = Netention.Core.Tool.FIND_NOTES_BY_TAG)
    public static Object findNotesByTag(Netention.Core core, Map<String, Object> params) {
        var tag = (String) params.get(Netention.Planner.ToolParam.TAG.getKey());
        if (tag == null) throw new IllegalArgumentException("tag parameter required.");
        return core.notes.getAll(n -> n.tags.contains(tag)).stream()
                .map(n -> Map.of(Netention.NoteProperty.ID.getKey(), n.id, Netention.NoteProperty.TITLE.getKey(), n.getTitle()))
                .collect(Collectors.toList());
    }

    @ToolAction(tool = Netention.Core.Tool.EXECUTE_SEMANTIC_QUERY)
    public static Object executeSemanticQuery(Netention.Core core, Map<String, Object> params) {
        var queryText = (String) params.get(Netention.Planner.ToolParam.QUERY_TEXT.getKey());
        if (queryText == null || !core.lm.isReady()) return Collections.emptyList();
        var queryEmb = core.lm.generateEmbedding(queryText);
        if (queryEmb.isEmpty()) return Collections.emptyList();

        var minSimilarity = ((Number) params.getOrDefault(Netention.Planner.ToolParam.MIN_SIMILARITY.getKey(), 0.6)).doubleValue();
        var maxResults = ((Number) params.getOrDefault(Netention.Planner.ToolParam.MAX_RESULTS.getKey(), 5)).longValue();

        return core.notes.getAllNotes().stream()
                .filter(n -> {
                    if (n.getEmbeddingV1() == null || n.getEmbeddingV1().length != queryEmb.get().length)
                        return false;
                    return !n.tags.contains(Netention.SystemTag.CONFIG.value);
                })
                .map(candidateNote -> new AbstractMap.SimpleEntry<>(candidateNote, LM.cosineSimilarity(queryEmb.get(), candidateNote.getEmbeddingV1())))
                .filter(entry -> entry.getValue() > minSimilarity)
                .sorted(Map.Entry.<Netention.Note, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(entry -> Map.of(Netention.NoteProperty.ID.getKey(), entry.getKey().id, Netention.NoteProperty.TITLE.getKey(), entry.getKey().getTitle(), "similarity", entry.getValue()))
                .collect(Collectors.toList());
    }

    @ToolAction(tool = Netention.Core.Tool.GET_CONFIG_STATE)
    public static Object getConfigState(Netention.Core core, Map<String, Object> params) {
        var type = (String) params.get(Netention.Planner.ToolParam.CONFIG_TYPE.getKey());
        var configInstance = switch (type) {
            case "nostr" -> core.cfg.net;
            case "ui" -> core.cfg.ui;
            case "llm" -> core.cfg.lm;
            default -> throw new IllegalArgumentException("Unknown configType: " + type);
        };
        return core.json.convertValue(configInstance, new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.APPLY_CONFIG_STATE)
    public static Object applyConfigState(Netention.Core core, Map<String, Object> params) {
        var type = (String) params.get(Netention.Planner.ToolParam.CONFIG_TYPE.getKey());
        var stateMap = (Map<String, Object>) params.get(Netention.Planner.ToolParam.STATE_MAP.getKey());
        if (stateMap == null) {
            Netention.Core.logger.warn("ApplyConfigState called with null stateMap for type {}, skipping.", type);
            return "Skipped: null stateMap";
        }

        var configInstance = switch (type) {
            case "nostr" -> core.cfg.net;
            case "ui" -> core.cfg.ui;
            case "llm" -> core.cfg.lm;
            default -> throw new IllegalArgumentException("Unknown configType: " + type);
        };
        var targetClass = configInstance.getClass();
        var updatedInstance = core.json.convertValue(stateMap, targetClass);
        for (var field : targetClass.getDeclaredFields()) {
            try {
                field.setAccessible(true);
                field.set(configInstance, field.get(updatedInstance));
            } catch (Exception e) {
                Netention.Core.logger.error("Error applying config field {}: {}", field.getName(), e.getMessage());
            }
        }
        switch (type) {
            case "nostr" -> {
                if (core.net.isEnabled()) {
                    core.net.setEnabled(false);
                    core.net.setEnabled(true);
                } else core.net.loadIdentity();
            }
            case "llm" -> core.lm.init();
            case "ui" -> core.fireCoreEvent(Netention.Core.CoreEventType.CONFIG_CHANGED, "ui_theme_updated");
        }
        return type + " config applied and services re-initialized if applicable.";
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.FIRE_CORE_EVENT)
    public static Object fireCoreEventTool(Netention.Core core, Map<String, Object> params) {
        var eventTypeStr = (String) params.get(Netention.Planner.ToolParam.EVENT_TYPE.getKey());
        var eventData = (Map<String, Object>) params.get(Netention.Planner.ToolParam.EVENT_DATA.getKey());
        if (eventTypeStr == null)
            throw new IllegalArgumentException("eventType is required for FireCoreEvent tool.");
        try {
            var eventType = Netention.Core.CoreEventType.valueOf(eventTypeStr);
            core.fireCoreEvent(eventType, eventData);
            return "Event " + eventType + " fired directly.";
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid CoreEventType: " + eventTypeStr, e);
        }
    }

    @SuppressWarnings("unchecked")
    @ToolAction(tool = Netention.Core.Tool.SCHEDULE_SYSTEM_EVENT)
    public static Object scheduleSystemEvent(Netention.Core core, Map<String, Object> params) {
        var eventType = (String) params.get(Netention.Planner.ToolParam.EVENT_TYPE.getKey());
        var payload = (Map<String, Object>) params.get(Netention.Planner.ToolParam.PAYLOAD.getKey());
        var delaySecondsNum = (Number) params.get(Netention.Planner.ToolParam.DELAY_SECONDS.getKey());
        if (eventType == null || delaySecondsNum == null)
            throw new IllegalArgumentException("eventType and delaySeconds required.");
        var delaySeconds = delaySecondsNum.longValue();
        core.scheduler.schedule(() -> core.fireCoreEvent(Netention.Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
                Netention.Planner.ToolParam.EVENT_TYPE.getKey(), eventType,
                Netention.Planner.ToolParam.PAYLOAD.getKey(), Objects.requireNonNullElse(payload, Collections.emptyMap()),
                Netention.ContentKey.STATUS.getKey(), Netention.Planner.PlanState.PENDING.name()
        )), delaySeconds, TimeUnit.SECONDS);
        return "System event " + eventType + " scheduled in " + delaySeconds + "s.";
    }

    @ToolAction(tool = Netention.Core.Tool.GET_SYSTEM_HEALTH_METRICS)
    public static Object getSystemHealthMetrics(Netention.Core core, Map<String, Object> params) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("pendingSystemEvents", core.notes.getAll(n -> n.tags.contains(Netention.SystemTag.SYSTEM_EVENT.value) && Netention.Planner.PlanState.PENDING.name().equals(n.content.get(Netention.ContentKey.STATUS.getKey()))).size());
        metrics.put("activePlans", core.planner.getActivePlans().size());
        metrics.put("failedPlanStepsInActivePlans", core.planner.getActivePlans().values().stream().flatMap(exec -> exec.steps.stream()).filter(step -> Netention.Planner.PlanStepState.FAILED.equals(step.status)).count());
        metrics.put("nostrStatus", core.net.isEnabled() ? "ENABLED" : "DISABLED");
        metrics.put("lmStatus", core.lm.isReady() ? "READY" : "NOT_READY");
        return metrics;
    }

    @ToolAction(tool = Netention.Core.Tool.IDENTIFY_STALLED_PLANS)
    public static Object identifyStalledPlans(Netention.Core core, Map<String, Object> params) {
        var stallThresholdSeconds = ((Number) params.getOrDefault(Netention.Planner.ToolParam.STALL_THRESHOLD_SECONDS.getKey(), 3600L)).longValue();
        List<String> stalledPlanNoteIds = new ArrayList<>();
        core.planner.getActivePlans().forEach((planNoteId, exec) -> {
            if (Netention.Planner.PlanState.RUNNING.equals(exec.currentStatus) || Netention.Planner.PlanState.STUCK.equals(exec.currentStatus)) {
                var recentProgress = exec.steps.stream().anyMatch(step -> step.endTime != null && Duration.between(step.endTime, Instant.now()).getSeconds() < stallThresholdSeconds);
                if (!recentProgress) {
                    var stuckRunningStep = exec.steps.stream().anyMatch(step -> Netention.Planner.PlanStepState.RUNNING.equals(step.status) && step.startTime != null && Duration.between(step.startTime, Instant.now()).getSeconds() > stallThresholdSeconds);
                    if (stuckRunningStep || Netention.Planner.PlanState.STUCK.equals(exec.currentStatus)) {
                        stalledPlanNoteIds.add(planNoteId);
                        core.fireCoreEvent(Netention.Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
                                Netention.Planner.ToolParam.EVENT_TYPE.getKey(), Netention.Core.SystemEventType.STALLED_PLAN_DETECTED.name(),
                                Netention.Planner.ToolParam.PAYLOAD.getKey(), Map.of(Netention.Planner.ToolParam.PLAN_NOTE_ID.getKey(), planNoteId, Netention.Metadata.PLAN_STATUS.key, exec.currentStatus.name()),
                                Netention.ContentKey.STATUS.getKey(), Netention.Planner.PlanState.PENDING.name()
                        ));
                    }
                }
            }
        });
        return Map.of("identifiedStalledPlanNoteIds", stalledPlanNoteIds);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface ToolAction {
        Netention.Core.Tool tool();
    }
}
