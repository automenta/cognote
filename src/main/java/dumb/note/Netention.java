package dumb.note;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

public class Netention {

    static {
        System.setProperty("org.slf44j.simpleLogger.defaultLogLevel", "info");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss:SSS Z");

        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ex) {
            System.err.println("Failed to initialize LaF: " + ex.getMessage());
        }
    }

    public enum FieldType {TEXT_FIELD, TEXT_AREA, COMBO_BOX, CHECK_BOX, PASSWORD_FIELD}

    public enum ContentType {
        TEXT_PLAIN("text/plain"), TEXT_HTML("text/html");
        private final String value;

        ContentType(String value) {
            this.value = value;
        }

        public static ContentType fromString(String text) {
            return Stream.of(values()).filter(ct -> ct.value.equalsIgnoreCase(text)).findFirst().orElse(TEXT_PLAIN);
        }

        public String getValue() {
            return value;
        }
    }

    public enum SystemTag {
        SYSTEM_EVENT("#system_event"), SYSTEM_PROCESS_HANDLER("#system_process_handler"), SYSTEM_NOTE("#system_note"), CONFIG("config"), GOAL_WITH_PLAN("#goal_with_plan"), NOSTR_FEED("nostr_feed"), CONTACT("contact"), NOSTR_CONTACT("nostr_contact"), CHAT("chat"), TEMPLATE("#template"), PERSISTENT_QUERY("#persistent_query"), NOSTR_RELAY("#nostr_relay"), MY_PROFILE("#my_profile"), FRIEND_REQUEST("#friend_request");
        public final String value;

        SystemTag(String value) {
            this.value = value;
        }

    }

    public enum NoteProperty {
        ID, TITLE, TEXT, CONTENT_TYPE, TAGS, LINKS, METADATA, CONTENT, CREATED_AT, UPDATED_AT;

        public String getKey() {
            return name().toLowerCase();
        }
    }

    public enum Metadata {
        PLAN_STATUS, PLAN_START_TIME, PLAN_END_TIME, NOSTR_EVENT_ID, NOSTR_PUB_KEY_HEX, NOSTR_RAW_EVENT, CREATED_AT_FROM_EVENT, NOSTR_PUB_KEY, LAST_SEEN, PROFILE_LAST_UPDATED_AT, LLM_SUMMARY("llm:summary"), LLM_DECOMPOSITION("llm:decomposition"), UNREAD_MESSAGES_COUNT("unread_messages_count");
        public final String key;

        Metadata() {
            this.key = name().toLowerCase();
        }

        Metadata(String key) {
            this.key = key;
        }

    }

    public enum ContentKey {
        TITLE, TEXT, CONTENT_TYPE, PLAN_STEPS, EVENT_TYPE, PAYLOAD, STATUS, MESSAGES, PROFILE_NAME, PROFILE_ABOUT, PROFILE_PICTURE_URL, RESULTS, LAST_RUN, RELAY_URL, RELAY_ENABLED, RELAY_READ, RELAY_WRITE;

        public String getKey() {
            return name().toLowerCase();
        }
    }

    public enum ToolParam {
        MESSAGE, NOTE_ID, PROPERTY_PATH, FAIL_IF_NOT_FOUND, DEFAULT_VALUE, JSON_STRING, ID, TITLE, TEXT, AS_HTML, TAGS, CONTENT, METADATA, CONTENT_UPDATE, NOSTR_PUB_KEY_HEX, PROFILE_DATA, CONDITION, TRUE_STEPS, FALSE_STEPS, EVENT_PAYLOAD_MAP, PARTNER_PUB_KEY_HEX, SENDER_PUB_KEY_HEX, MESSAGE_CONTENT, TIMESTAMP_EPOCH_SECONDS, EVENT_TYPE, EVENT_DATA, GOAL_TEXT, PAYLOAD, DELAY_SECONDS, TAG, LIST, LOOP_VAR, LOOP_STEPS, QUERY_TEXT, MIN_SIMILARITY, MAX_RESULTS, SOURCE_NOTE_ID, LINKS, STALL_THRESHOLD_SECONDS, CONFIG_TYPE, STATE_MAP, PROMPT, CALLBACK_KEY, PLAN_NOTE_ID, FRIEND_REQUEST_SENDER_NPUB, ACTIONABLE_ITEM_ID, RECIPIENT_NPUB;

        public String getKey() {
            return name().toLowerCase();
        }
    }

    public enum PlanState {PENDING, RUNNING, COMPLETED, FAILED, STUCK, PARSING, FAILED_PARSING, FAILED_NO_STEPS}

    public enum PlanStepState {PENDING, RUNNING, COMPLETED, FAILED, WAITING_FOR_USER, PENDING_RETRY}

    public enum PlanStepKey {
        ID, DESCRIPTION, TOOL_NAME, TOOL_PARAMS, DEPENDS_ON_STEP_IDS, STATUS, RESULT, OUTPUT_NOTE_ID, START_TIME, END_TIME, ALTERNATIVES, RETRY_COUNT, MAX_RETRIES, CURRENT_ALTERNATIVE_INDEX;

        public String getKey() {
            return name().toLowerCase();
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Field {
        String label();

        String tooltip() default "";

        FieldType type() default FieldType.TEXT_FIELD;

        String[] choices() default {};

        String group() default "General";
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Note {
        public final List<String> tags = new CopyOnWriteArrayList<>();
        public final Map<String, Object> content = new ConcurrentHashMap<>();
        public final Map<String, Object> meta = new ConcurrentHashMap<>();
        public final List<Link> links = new CopyOnWriteArrayList<>();
        public String id = UUID.randomUUID().toString();
        public int version = 1;
        public Instant createdAt, updatedAt;
        public float[] embeddingV1;

        public Note() {
            createdAt = updatedAt = Instant.now();
            content.put(Netention.ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_PLAIN.getValue());
        }

        public Note(String t, String txt) {
            this();
            this.content.putAll(Map.of(Netention.ContentKey.TITLE.getKey(), t, Netention.ContentKey.TEXT.getKey(), txt, Netention.ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_PLAIN.getValue()));
        }

        public String getTitle() {
            return (String) content.getOrDefault(Netention.ContentKey.TITLE.getKey(), "Untitled");
        }

        public void setTitle(String t) {
            content.put(Netention.ContentKey.TITLE.getKey(), t);
        }

        public String getText() {
            return (String) content.getOrDefault(Netention.ContentKey.TEXT.getKey(), "");
        }

        public void setText(String t) {
            content.put(Netention.ContentKey.TEXT.getKey(), t);
            if (!ContentType.TEXT_HTML.getValue().equals(content.get(Netention.ContentKey.CONTENT_TYPE.getKey()))) {
                content.put(Netention.ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_PLAIN.getValue());
            }
        }

        public ContentType getContentTypeEnum() {
            return ContentType.fromString((String) content.getOrDefault(Netention.ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_PLAIN.getValue()));
        }

        public String getContentType() {
            return getContentTypeEnum().getValue();
        }

        public float[] getEmbeddingV1() {
            return embeddingV1;
        }

        public void setEmbeddingV1(float[] e) {
            this.embeddingV1 = e;
        }

        public void setHtmlText(String html) {
            content.put(Netention.ContentKey.TEXT.getKey(), html);
            content.put(Netention.ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_HTML.getValue());
        }

        public String getContentForEmbedding() {
            var textContent = getText();
            if (ContentType.TEXT_HTML.equals(getContentTypeEnum())) {
                try {
                    var pane = new JTextPane();
                    pane.setContentType(ContentType.TEXT_HTML.getValue());
                    pane.setText(textContent);
                    textContent = pane.getDocument().getText(0, pane.getDocument().getLength());
                } catch (BadLocationException e) {
                }
            }
            return getTitle() + "\n" + textContent;
        }

        @Override
        public String toString() {
            return getTitle();
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o != null && getClass() == o.getClass() && id.equals(((Note) o).id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Link {
        public final Map<String, Object> properties = new HashMap<>();
        public String targetNoteId;
        public String relationType;

        public Link() {
        }

        public Link(String t, String r) {
            this.targetNoteId = t;
            this.relationType = r;
        }
    }

    public static class Planner {
        private static final Logger logger = LoggerFactory.getLogger(Planner.class);
        private static final int DEFAULT_MAX_RETRIES = 2;
        private final Core core;
        private final Map<String, PlanExecution> active = new ConcurrentHashMap<>();
        private final ObjectMapper json = Core.createObjectMapper();

        public Planner(Core core) {
            this.core = core;
        }

        @SuppressWarnings("unchecked")
        public void execute(Note goal, Map<String, Object> initialContext) {
            if (goal == null) {
                logger.warn("Goal note is null, cannot execute plan.");
                return;
            }

            var exe = active.compute(goal.id, (k, existingExec) -> (existingExec != null && Netention.PlanState.RUNNING.equals(existingExec.currentStatus)) ? existingExec : new PlanExecution(goal.id, initialContext));
            if (exe.context.isEmpty() && !initialContext.isEmpty()) exe.context.putAll(initialContext);


            if (!Netention.PlanState.RUNNING.equals(exe.currentStatus) || exe.steps.isEmpty()) {
                exe.currentStatus = Netention.PlanState.PARSING;
                goal.meta.putAll(Map.of(Metadata.PLAN_STATUS.key, exe.currentStatus.name(), Metadata.PLAN_START_TIME.key, Instant.now().toString()));
                core.saveNote(goal);

                var planStepsData = goal.content.get(ContentKey.PLAN_STEPS.getKey());
                if (planStepsData instanceof List<?> rawStepsList && exe.steps.isEmpty()) {
                    try {
                        for (var i = 0; i < rawStepsList.size(); i++) {
                            if (rawStepsList.get(i) instanceof Map rawStepObj) {
                                var step = json.convertValue(rawStepObj, PlanStep.class);
                                if (step.id == null || step.id.isEmpty() || step.id.matches("step\\d+_id_placeholder")) {
                                    step.id = "step_" + i + "_" + UUID.randomUUID().toString().substring(0, 4);
                                }
                                exe.steps.add(step);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Failed to parse plan steps for note {}", goal.id, e);
                        exe.currentStatus = Netention.PlanState.FAILED_PARSING;
                        goal.meta.put(Metadata.PLAN_STATUS.key, exe.currentStatus.name());
                        core.saveNote(goal);
                        active.remove(goal.id);
                        return;
                    }
                }

                if (exe.steps.isEmpty() && goal.tags.contains(SystemTag.GOAL_WITH_PLAN.value)) {
                    try {
                        var initialSteps = (List<PlanStep>) core.executeTool(Core.Tool.SUGGEST_PLAN_STEPS, Map.of(Netention.ToolParam.GOAL_TEXT.getKey(), goal.getText().isEmpty() ? goal.getTitle() : goal.getText()));
                        if (initialSteps != null && !initialSteps.isEmpty()) exe.steps.addAll(initialSteps);
                        else addDefaultInitialStep(exe, goal);
                    } catch (Exception e) {
                        logger.warn("Failed to get initial plan steps from LM for {}: {}", goal.id, e.getMessage());
                        addDefaultInitialStep(exe, goal);
                    }
                } else if (exe.steps.isEmpty()) {
                    logger.warn("Plan {} has no steps defined and is not a typical user goal for LM suggestion.", goal.id);
                    exe.currentStatus = Netention.PlanState.FAILED_NO_STEPS;
                    goal.meta.put(Metadata.PLAN_STATUS.key, exe.currentStatus.name());
                    core.saveNote(goal);
                    active.remove(goal.id);
                    return;
                }

                goal.content.put(ContentKey.PLAN_STEPS.getKey(), exe.steps.stream().map(s -> json.convertValue(s, Map.class)).collect(Collectors.toList()));
                exe.currentStatus = Netention.PlanState.RUNNING;
                goal.meta.put(Metadata.PLAN_STATUS.key, exe.currentStatus.name());
                core.saveNote(goal);
            }
            core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, exe);
            tick();
        }

        public void execute(Note goal) {
            execute(goal, new HashMap<>());
        }

        private void addDefaultInitialStep(PlanExecution execution, Note goalNote) {
            var initialStep = new PlanStep();
            initialStep.description = "Initial analysis of goal: " + goalNote.getTitle();
            initialStep.toolName = Core.Tool.LOG_MESSAGE.name();
            initialStep.toolParams = Map.of(Netention.ToolParam.MESSAGE.getKey(), "Starting plan for: " + goalNote.getTitle());
            execution.steps.add(initialStep);
        }

        public synchronized void tick() {
            active.values().stream().filter(exec -> Netention.PlanState.RUNNING.equals(exec.currentStatus) || exec.steps.stream().anyMatch(s -> Netention.PlanStepState.PENDING_RETRY.equals(s.status))).forEach(this::processExecution);
        }

        private void processExecution(PlanExecution exec) {
            nextRunnableStep(exec).ifPresentOrElse(currentStep -> {
                if (Netention.PlanStepState.PENDING.equals(currentStep.status) || Netention.PlanStepState.PENDING_RETRY.equals(currentStep.status)) {
                    currentStep.status = Netention.PlanStepState.RUNNING;
                    currentStep.startTime = Instant.now();
                    core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, exec);
                    executeStep(exec, currentStep);
                }
            }, () -> updateOverallPlanStatus(exec));
        }

        private void updateOverallPlanStatus(PlanExecution exec) {
            var allCompleted = exec.steps.stream().allMatch(s -> Netention.PlanStepState.COMPLETED.equals(s.status));
            var anyFailedNoAlternativesOrRetries = exec.steps.stream().anyMatch(s -> Netention.PlanStepState.FAILED.equals(s.status) && s.retryCount >= s.maxRetries && (s.alternatives.isEmpty() || s.currentAlternativeIndex >= s.alternatives.size() - 1));
            var anyRunningOrWaiting = exec.steps.stream().anyMatch(s -> Set.of(Netention.PlanStepState.RUNNING, Netention.PlanStepState.WAITING_FOR_USER, Netention.PlanStepState.PENDING_RETRY).contains(s.status));

            if (allCompleted && !anyRunningOrWaiting) exec.currentStatus = Netention.PlanState.COMPLETED;
            else if (anyFailedNoAlternativesOrRetries && !anyRunningOrWaiting)
                exec.currentStatus = Netention.PlanState.FAILED;
            else if (!anyRunningOrWaiting && exec.steps.stream().noneMatch(s -> Netention.PlanStepState.PENDING.equals(s.status) || Netention.PlanStepState.PENDING_RETRY.equals(s.status))) {
                exec.currentStatus = Netention.PlanState.STUCK;
            }

            if (!Netention.PlanState.RUNNING.equals(exec.currentStatus) && exec.steps.stream().noneMatch(s -> Netention.PlanStepState.PENDING_RETRY.equals(s.status))) {
                core.notes.get(exec.planNoteId).ifPresent(n -> {
                    n.meta.put(Metadata.PLAN_STATUS.key, exec.currentStatus.name());
                    if (Set.of(Netention.PlanState.COMPLETED, Netention.PlanState.FAILED, Netention.PlanState.STUCK).contains(exec.currentStatus)) {
                        n.meta.put(Metadata.PLAN_END_TIME.key, Instant.now().toString());
                    }
                    core.saveNote(n);
                });
                if (Set.of(Netention.PlanState.COMPLETED, Netention.PlanState.FAILED, Netention.PlanState.STUCK).contains(exec.currentStatus)) {
                    active.remove(exec.planNoteId);
                }
                core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, exec);
            }
        }

        private Optional<PlanStep> nextRunnableStep(PlanExecution exec) {
            return exec.steps.stream().filter(step -> Netention.PlanStepState.PENDING.equals(step.status) || Netention.PlanStepState.PENDING_RETRY.equals(step.status)).filter(step -> step.dependsOnStepIds.isEmpty() || step.dependsOnStepIds.stream().allMatch(depId -> exec.getStepById(depId).map(depStep -> Netention.PlanStepState.COMPLETED.equals(depStep.status)).orElseGet(() -> {
                logger.warn("Dependency step {} not found for step {}", depId, step.id);
                return false;
            }))).findFirst();
        }

        private Object traversePath(Object current, String[] parts, int startIndex, PlanStep currentStepContext) {
            Object c = current;
            for (var i = startIndex; i < parts.length; i++) {
                if (c == null) return null;
                var part = parts[i];
                int I = i;
                c = switch (c) {
                    case PlanStep ps when "result".equals(part) && I == startIndex && ps == currentStepContext ->
                            ps.result;
                    case Map map -> map.get(part);
                    case JsonNode jn -> jn.has(part) ? jn.get(part) : null;
                    default -> null;
                };
            }
            return c;
        }

        public Object resolveContextValue(String path, PlanExecution planExec) {
            if (path == null || !path.startsWith("$")) return path;
            var key = path.substring(1);
            var parts = key.split("\\.");
            Object currentValue;

            if ("trigger".equals(parts[0])) {
                currentValue = traversePath(planExec.context.get(parts[0]), parts, 1, null);
            } else {
                var stepOpt = planExec.getStepById(parts[0]);
                if (stepOpt.isPresent()) {
                    currentValue = traversePath(stepOpt.get(), parts, 1, stepOpt.get());
                } else {
                    currentValue = planExec.context.get(key);
                }
            }

            if (currentValue instanceof JsonNode jn && jn.isValueNode()) {
                if (jn.isTextual()) return jn.asText();
                if (jn.isNumber()) return jn.numberValue();
                if (jn.isBoolean()) return jn.asBoolean();
                return jn.toString();
            }
            return (currentValue != null) ? currentValue : path;
        }

        private void executeStep(PlanExecution planExec, PlanStep step) {
            new Thread(() -> {
                var currentToolNameStr = step.toolName;
                if (currentToolNameStr == null) return;
                var currentToolParams = step.toolParams;

                if (step.currentAlternativeIndex >= 0 && step.currentAlternativeIndex < step.alternatives.size()) {
                    var alt = step.alternatives.get(step.currentAlternativeIndex);
                    currentToolNameStr = alt.toolName();
                    currentToolParams = alt.toolParams();
                    logger.info("Executing alternative {} for step: {}", step.currentAlternativeIndex, step.description);
                } else {
                    logger.info("Executing primary for step: {}", step.description);
                }

                try {
                    Map<String, Object> resolvedParams = new HashMap<>();
                    if (currentToolParams != null) {
                        for (var entry : currentToolParams.entrySet()) {
                            var value = entry.getValue();
                            resolvedParams.put(entry.getKey(), (value instanceof String valStr && valStr.startsWith("$")) ? resolveContextValue(valStr, planExec) : value);
                        }
                    }
                    var currentTool = Core.Tool.fromString(currentToolNameStr);
                    if (Core.Tool.USER_INTERACTION.equals(currentTool)) {
                        var callbackKey = planExec.planNoteId + "_" + step.id;
                        planExec.waitingCallbacks.put(callbackKey, step);
                        step.status = Netention.PlanStepState.WAITING_FOR_USER;
                        core.fireCoreEvent(Core.CoreEventType.USER_INTERACTION_REQUESTED, Map.of(Netention.ToolParam.PROMPT.getKey(), resolvedParams.getOrDefault(Netention.ToolParam.PROMPT.getKey(), "Provide input:"), Netention.ToolParam.CALLBACK_KEY.getKey(), callbackKey, Netention.ToolParam.PLAN_NOTE_ID.getKey(), planExec.planNoteId));
                        core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, planExec);
                        return;
                    }

                    var result = core.executeTool(currentTool, resolvedParams);
                    step.result = result;
                    if (step.id != null && result != null) planExec.context.put(step.id + ".result", result);
                    step.status = Netention.PlanStepState.COMPLETED;
                    logger.info("Step {} (Tool: {}) completed successfully.", step.description, currentToolNameStr);
                } catch (Exception e) {
                    logger.error("Step {} (Tool: {}) failed: {}", step.description, currentToolNameStr, e.getMessage(), e);
                    if (step.currentAlternativeIndex < step.alternatives.size() - 1) {
                        step.currentAlternativeIndex++;
                        step.status = Netention.PlanStepState.PENDING_RETRY;
                        logger.info("Will try next alternative for step {}", step.description);
                    } else if (step.retryCount < step.maxRetries) {
                        step.retryCount++;
                        step.currentAlternativeIndex = -1;
                        step.status = Netention.PlanStepState.PENDING_RETRY;
                        logger.info("Will retry (attempt {}) step {}", step.retryCount, step.description);
                    } else {
                        step.status = Netention.PlanStepState.FAILED;
                        step.result = e.getMessage();
                    }
                } finally {
                    if (Set.of(Netention.PlanStepState.COMPLETED, Netention.PlanStepState.FAILED).contains(step.status))
                        step.endTime = Instant.now();
                    core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, planExec);
                    SwingUtilities.invokeLater(this::tick);
                }
            }).start();
        }

        public void postUserInteractionResult(String callbackKey, Object result) {
            active.values().stream().filter(exec -> exec.waitingCallbacks.containsKey(callbackKey)).findFirst().ifPresent(exec -> {
                var step = exec.waitingCallbacks.remove(callbackKey);
                if (step != null) {
                    step.result = result;
                    step.status = (result == null || (result instanceof String s && s.isEmpty())) ? Netention.PlanStepState.FAILED : Netention.PlanStepState.COMPLETED;
                    logger.info("User interaction for step {} {}.", step.description, step.status == Netention.PlanStepState.COMPLETED ? "completed" : "failed (empty input)");
                    if (step.id != null && result != null) exec.context.put(step.id + ".result", result);
                    step.endTime = Instant.now();
                    core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, exec);
                    SwingUtilities.invokeLater(this::tick);
                }
            });
        }

        public Optional<PlanExecution> getPlanExecution(String planNoteId) {
            return ofNullable(active.get(planNoteId));
        }

        public Map<String, PlanExecution> getActive() {
            return Collections.unmodifiableMap(active);
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class PlanStep {
            public final List<String> dependsOnStepIds = new ArrayList<>();
            public final List<AlternativeExecution> alternatives = new ArrayList<>();
            public final int maxRetries = DEFAULT_MAX_RETRIES;
            public String id = UUID.randomUUID().toString();
            public String description;
            public String toolName;
            public Map<String, Object> toolParams = new HashMap<>();
            public Netention.PlanStepState status = Netention.PlanStepState.PENDING;
            public Object result;
            public String outputNoteId;
            public Instant startTime, endTime;
            public int retryCount = 0;
            public int currentAlternativeIndex = -1;
        }

        public static class PlanExecution {
            public final String planNoteId;
            public final List<PlanStep> steps = new CopyOnWriteArrayList<>();
            public final Map<String, Object> context = new ConcurrentHashMap<>();
            public final Map<String, PlanStep> waitingCallbacks = new ConcurrentHashMap<>();
            public Netention.PlanState currentStatus = Netention.PlanState.PENDING;

            public PlanExecution(String planNoteId) {
                this.planNoteId = planNoteId;
            }

            public PlanExecution(String planNoteId, Map<String, Object> initialContext) {
                this.planNoteId = planNoteId;
                this.context.putAll(initialContext);
            }

            public Optional<PlanStep> getStepById(String id) {
                return steps.stream().filter(s -> s.id.equals(id)).findFirst();
            }
        }

        public record AlternativeExecution(String toolName, Map<String, Object> toolParams, double confidenceScore,
                                           String rationale) {
        }
    }

    public static class Core {
        public static final Logger logger = LoggerFactory.getLogger(Core.class);
        public final Notes notes;
        public final Config cfg;
        public final Nostr net;
        public final LM lm;
        public final Planner planner;
        public final Map<Tool, BiFunction<Core, Map<String, Object>, Object>> tools = new ConcurrentHashMap<>();
        public final ObjectMapper json = createObjectMapper();
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "NetentionCoreScheduler");
            t.setDaemon(true);
            return t;
        });
        private final List<Consumer<CoreEvent>> coreEventListeners = new CopyOnWriteArrayList<>();

        public Core() {
            var dDir = Paths.get(System.getProperty("user.home"), ".netention", "data");
            try {
                Files.createDirectories(dDir);
            } catch (IOException e) {
                logger.error("Failed to create data dir {}", dDir, e);
                throw new RuntimeException("Init failed: data dir error.", e);
            }

            this.notes = new Notes(dDir);
            this.cfg = new Config(notes, this);
            this.planner = new Planner(this);
            Tools.registerAllTools(tools);
            bootstrapSystemNotes();

            Stream.of("nostr", "ui", "llm").forEach(typeKey -> {
                var noteId = Config.CONFIG_NOTE_PREFIX + typeKey;
                if (notes.get(noteId).isEmpty()) {
                    logger.info("Config note {} not found after bootstrap. Creating with defaults.", noteId);
                    var configInstance = switch (typeKey) {
                        case "nostr" -> cfg.net;
                        case "ui" -> cfg.ui;
                        case "llm" -> cfg.lm;
                        default -> null;
                    };
                    cfg.saveConfigObjectToNote(configInstance, typeKey);
                }
            });

            fireCoreEvent(CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(ToolParam.EVENT_TYPE.getKey(), SystemEventType.LOAD_ALL_CONFIGS_REQUESTED.name(), ToolParam.PAYLOAD.getKey(), Collections.emptyMap(), ContentKey.STATUS.getKey(), PlanState.PENDING.name()));
            this.lm = new LM(cfg);
            this.net = new Nostr(cfg, this, this::handleRawNostrEvent);
            scheduler.schedule(() -> fireCoreEvent(CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(ToolParam.EVENT_TYPE.getKey(), SystemEventType.EVALUATE_PERSISTENT_QUERIES.name(), ToolParam.PAYLOAD.getKey(), Collections.emptyMap(), ContentKey.STATUS.getKey(), PlanState.PENDING.name())), 30, TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Netention stop...");
                scheduler.shutdownNow();
                if (net.isEnabled()) net.setEnabled(false);
                logger.info("Netention shutdown complete.");
            }));
            logger.info("NetentionCore initialized.");
        }

        public static ObjectMapper createObjectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).configure(SerializationFeature.INDENT_OUTPUT, true).setSerializationInclusion(JsonInclude.Include.NON_NULL);
        }

        static Object getNoteSpecificProperty(Note n, String propertyNameStr) {
            var propertyName = NoteProperty.valueOf(propertyNameStr.toUpperCase());
            return switch (propertyName) {
                case ID -> n.id;
                case TITLE -> n.getTitle();
                case TEXT -> n.getText();
                case CONTENT_TYPE -> n.getContentType();
                case TAGS -> new ArrayList<>(n.tags);
                case LINKS -> new ArrayList<>(n.links);
                case METADATA -> new HashMap<>(n.meta);
                case CONTENT -> new HashMap<>(n.content);
                case CREATED_AT -> n.createdAt;
                case UPDATED_AT -> n.updatedAt;
            };
        }

        public void addCoreEventListener(Consumer<CoreEvent> listener) {
            coreEventListeners.add(listener);
        }

        public void removeCoreEventListener(Consumer<CoreEvent> listener) {
            coreEventListeners.remove(listener);
        }

        public void fireCoreEvent(CoreEventType type, Object data) {
            var event = new CoreEvent(type, data);
            if (type == CoreEventType.SYSTEM_EVENT_REQUESTED && data instanceof Map<?, ?> eventDetailsMap) {
                var details = (Map<String, Object>) eventDetailsMap;
                var systemEventNote = new Note();
                systemEventNote.tags.add(SystemTag.SYSTEM_EVENT.value);
                var eventTypeVal = details.get(ToolParam.EVENT_TYPE.getKey());
                systemEventNote.content.put(ContentKey.EVENT_TYPE.getKey(), Objects.requireNonNullElse(eventTypeVal, SystemEventType.UNKNOWN_EVENT_TYPE.name()).toString());
                if (eventTypeVal == null)
                    logger.error("SYSTEM_EVENT_REQUESTED fired with null eventType in data: {}", data);
                systemEventNote.content.put(ContentKey.PAYLOAD.getKey(), Objects.requireNonNullElse(details.get(ToolParam.PAYLOAD.getKey()), Collections.emptyMap()));
                systemEventNote.content.put(ContentKey.STATUS.getKey(), PlanState.PENDING.name());
                saveNote(systemEventNote);
            } else {
                coreEventListeners.forEach(l -> SwingUtilities.invokeLater(() -> l.accept(event)));
            }
        }

        public Note saveNote(Note note) {
            if (note == null) return null;
            var savedNote = notes.save(note);
            fireCoreEvent(savedNote.version == 1 ? CoreEventType.NOTE_ADDED : CoreEventType.NOTE_UPDATED, savedNote);
            checkForSystemTriggers(savedNote);
            return savedNote;
        }

        public boolean deleteNote(String noteId) {
            if (notes.delete(noteId)) {
                fireCoreEvent(Core.CoreEventType.NOTE_DELETED, noteId);
                return true;
            }
            return false;
        }

        private void handleRawNostrEvent(Nostr.NostrEvent event) {
            logger.debug("Queueing Nostr event as System Event Note: kind={}, id={}", event.kind, event.id);
            try {
                var eventType = switch (event.kind) {
                    case 0 -> SystemEventType.NOSTR_KIND0_RECEIVED;
                    case 1 -> SystemEventType.NOSTR_KIND1_RECEIVED;
                    case 4 -> SystemEventType.NOSTR_KIND4_RECEIVED;
                    default -> SystemEventType.NOSTR_KIND_UNKNOWN_RECEIVED;
                };
                fireCoreEvent(CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(ToolParam.EVENT_TYPE.getKey(), eventType.name(), ToolParam.PAYLOAD.getKey(), json.convertValue(event, Map.class), ContentKey.STATUS.getKey(), PlanState.PENDING.name(), "originalKind", event.kind));
            } catch (Exception e) {
                logger.error("Failed to process raw NostrEvent for system event note: {}", e.getMessage(), e);
            }
        }

        private void checkForSystemTriggers(Note triggeredNote) {
            if (!triggeredNote.tags.contains(SystemTag.SYSTEM_EVENT.value)) return;
            var handlers = notes.getAll(n -> n.tags.contains(SystemTag.SYSTEM_PROCESS_HANDLER.value));
            for (var handlerNote : handlers) {
                var handlerContent = handlerNote.content;
                var expectedEventType = (String) handlerContent.get("triggerEventType");
                var expectedStatus = (String) handlerContent.get("triggerStatus");
                var eventNoteContent = triggeredNote.content;
                var eventTypeMatches = expectedEventType != null && expectedEventType.equals(eventNoteContent.get(ContentKey.EVENT_TYPE.getKey()));
                var statusMatches = expectedStatus == null || expectedStatus.equals(eventNoteContent.get(ContentKey.STATUS.getKey()));

                if (eventTypeMatches && statusMatches) {
                    logger.info("System trigger matched for handler {} by event note {}", handlerNote.id, triggeredNote.id);
                    var existingExecution = planner.getPlanExecution(handlerNote.id).orElse(null);
                    if (existingExecution != null && PlanState.RUNNING.equals(existingExecution.currentStatus)) {
                        logger.warn("Handler plan {} is already running. Skipping new trigger by {}.", handlerNote.id, triggeredNote.id);
                        continue;
                    }
                    Map<String, Object> planContext = new HashMap<>();
                    planContext.put("trigger", Map.of("sourceEventNoteId", triggeredNote.id, "eventContent", new HashMap<>(triggeredNote.content)));
                    planner.execute(handlerNote, planContext);
                }
            }
        }

        private void bootstrapSystemNotes() {
            logger.info("Bootstrapping system notes...");
            PlanDefBuilder.create("system_listener_nostr_kind0_handler").title("System Listener: Nostr Kind 0 (Profile) Handler").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.NOSTR_KIND0_RECEIVED, PlanState.PENDING).step("s0_get_payload", Tool.GET_NOTE_PROPERTY, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.PROPERTY_PATH, "content.payload")).step("s0a_get_self_info", Tool.GET_SELF_NOSTR_INFO, Map.of()).step("s1_parse_profile", Tool.PARSE_JSON, Map.of(ToolParam.JSON_STRING, "$s0_get_payload.result.content"), "s0_get_payload").step("s2_if_self_profile", Tool.IF_ELSE, Map.of(ToolParam.CONDITION, "$s0_get_payload.result.pubkey == $s0a_get_self_info.result.pubKeyHex", ToolParam.TRUE_STEPS, List.of(Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.MODIFY_NOTE_CONTENT.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.NOTE_ID.getKey(), "$s0a_get_self_info.result.myProfileNoteId", ToolParam.CONTENT_UPDATE.getKey(), Map.of(ContentKey.PROFILE_NAME.getKey(), "$s1_parse_profile.result.name", ContentKey.PROFILE_ABOUT.getKey(), "$s1_parse_profile.result.about", ContentKey.PROFILE_PICTURE_URL.getKey(), "$s1_parse_profile.result.picture", "metadataUpdate", Map.of(Metadata.PROFILE_LAST_UPDATED_AT.key, "NOW")))), Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.LOG_MESSAGE.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.MESSAGE.getKey(), "Updated own Nostr profile from Kind 0 event."))), ToolParam.FALSE_STEPS, List.of(Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.ADD_CONTACT.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.NOSTR_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey", ToolParam.PROFILE_DATA.getKey(), "$s1_parse_profile.result")))), "s0_get_payload", "s0a_get_self_info", "s1_parse_profile").step("s3_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s2_if_self_profile").bootstrap(this);

            PlanDefBuilder.create("system_listener_nostr_kind1_handler").title("System Listener: Nostr Kind 1 Handler").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.NOSTR_KIND1_RECEIVED, PlanState.PENDING).step("s0_get_payload", Tool.GET_NOTE_PROPERTY, "Get Nostr event object", Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.PROPERTY_PATH, "content.payload")).step("s1_check_exists", Tool.GET_NOTE_PROPERTY, "Check if Nostr event note already exists", Map.of(ToolParam.NOTE_ID, "nostr_event_$s0_get_payload.result.id", ToolParam.PROPERTY_PATH, NoteProperty.ID.getKey(), ToolParam.FAIL_IF_NOT_FOUND, false), "s0_get_payload").step("s2_if_else", Tool.IF_ELSE, "Process if new, else log", Map.of(ToolParam.CONDITION, "$s1_check_exists.result == null", ToolParam.TRUE_STEPS, List.of(Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.CREATE_NOTE.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.ID.getKey(), "nostr_event_$s0_get_payload.result.id", ToolParam.TITLE.getKey(), "Nostr: $s0_get_payload.result.content_substring_30", ToolParam.TEXT.getKey(), "$s0_get_payload.result.content", ToolParam.TAGS.getKey(), List.of(SystemTag.NOSTR_FEED.value), ToolParam.METADATA.getKey(), Map.of(Metadata.NOSTR_EVENT_ID.key, "$s0_get_payload.result.id", Metadata.NOSTR_PUB_KEY_HEX.key, "$s0_get_payload.result.pubkey", Metadata.NOSTR_PUB_KEY.key, "$s0_get_payload.result.pubkey_npub", Metadata.NOSTR_RAW_EVENT.key, "$s0_get_payload.result", Metadata.CREATED_AT_FROM_EVENT.key, "$s0_get_payload.result.created_at"))), Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.ADD_CONTACT.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.NOSTR_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey"))), ToolParam.FALSE_STEPS, List.of(Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.LOG_MESSAGE.name(), ToolParam.TOOL_PARAMS.getKey(), Map.of(ToolParam.MESSAGE.getKey(), "Skipping duplicate Nostr Kind 1 event: $s0_get_payload.result.id")))), "s0_get_payload", "s1_check_exists").step("s3_mark_processed", Tool.MODIFY_NOTE_CONTENT, "Mark system event as processed", Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s2_if_else").bootstrap(this);

            PlanDefBuilder.create("system_listener_nostr_kind4_handler").title("System Listener: Nostr Kind 4 (DM) Handler").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.NOSTR_KIND4_RECEIVED, PlanState.PENDING).step("s0_get_payload", Tool.GET_NOTE_PROPERTY, "Get Nostr event object", Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.PROPERTY_PATH, "content.payload")).step("s1_decrypt_dm", Tool.DECRYPT_NOSTR_DM, "Decrypt DM content", Map.of(ToolParam.EVENT_PAYLOAD_MAP, "$s0_get_payload.result"), "s0_get_payload").step("s2_if_friend_request", Tool.IF_ELSE, "Check if friend request", Map.of(ToolParam.CONDITION, "$s1_decrypt_dm.result.isFriendRequest == true", ToolParam.TRUE_STEPS, List.of(Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.FIRE_CORE_EVENT.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.EVENT_TYPE.getKey(), CoreEventType.SYSTEM_EVENT_REQUESTED.name(), ToolParam.PAYLOAD.getKey(), Map.of(ToolParam.EVENT_TYPE.getKey(), SystemEventType.FRIEND_REQUEST_RECEIVED.name(), ToolParam.FRIEND_REQUEST_SENDER_NPUB.getKey(), "$s0_get_payload.result.pubkey_npub", "sourceEventId", "$s0_get_payload.result.id")))), ToolParam.FALSE_STEPS, List.of(Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.UPDATE_CHAT_NOTE.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.PARTNER_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey", ToolParam.SENDER_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey", ToolParam.MESSAGE_CONTENT.getKey(), "$s1_decrypt_dm.result.decryptedText", ToolParam.TIMESTAMP_EPOCH_SECONDS.getKey(), "$s0_get_payload.result.created_at")), Map.of(PlanStepKey.TOOL_NAME.getKey(), Tool.ADD_CONTACT.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.NOSTR_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey")))), "s1_decrypt_dm").step("s3_mark_processed", Tool.MODIFY_NOTE_CONTENT, "Mark system event as processed", Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s2_if_friend_request").bootstrap(this);

            PlanDefBuilder.create("system_listener_friend_request_received_handler").title("System Listener: Friend Request Received").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.FRIEND_REQUEST_RECEIVED, PlanState.PENDING).step("s0_get_payload", Tool.GET_NOTE_PROPERTY, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.PROPERTY_PATH, "content.payload")).step("s1_create_actionable_item", Tool.FIRE_CORE_EVENT, Map.of(ToolParam.EVENT_TYPE, CoreEventType.ACTIONABLE_ITEM_ADDED.name(), ToolParam.EVENT_DATA, Map.of("id", "friend_request_$s0_get_payload.result.friendRequestSenderNpub", "type", "FRIEND_REQUEST", "description", "Friend request from $s0_get_payload.result.friendRequestSenderNpub_npub", "sourceEventId", "$s0_get_payload.result.sourceEventId", "data", Map.of("senderNpub", "$s0_get_payload.result.friendRequestSenderNpub"))), "s0_get_payload").step("s2_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s1_create_actionable_item").bootstrap(this);

            PlanDefBuilder.create("system_listener_accept_friend_request_handler").title("System Listener: Accept Friend Request").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.ACCEPT_FRIEND_REQUEST, PlanState.PENDING).step("s0_get_payload", Tool.GET_NOTE_PROPERTY, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.PROPERTY_PATH, "content.payload")).step("s1_add_contact", Tool.ADD_CONTACT, Map.of(ToolParam.NOSTR_PUB_KEY_HEX, "$s0_get_payload.result.senderNpub"), "s0_get_payload").step("s2_send_confirmation_dm", Tool.SEND_DM, Map.of(ToolParam.RECIPIENT_NPUB, "$s0_get_payload.result.senderNpub", ToolParam.MESSAGE, "Friend request accepted! Let's chat."), "s1_add_contact").step("s3_remove_actionable_item", Tool.FIRE_CORE_EVENT, Map.of(ToolParam.EVENT_TYPE, CoreEventType.ACTIONABLE_ITEM_REMOVED.name(), ToolParam.EVENT_DATA, "$s0_get_payload.result.actionableItemId"), "s2_send_confirmation_dm").step("s4_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s3_remove_actionable_item").bootstrap(this);

            PlanDefBuilder.create("system_listener_reject_friend_request_handler").title("System Listener: Reject Friend Request").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.REJECT_FRIEND_REQUEST, PlanState.PENDING).step("s0_get_payload", Tool.GET_NOTE_PROPERTY, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.PROPERTY_PATH, "content.payload")).step("s1_remove_actionable_item", Tool.FIRE_CORE_EVENT, Map.of(ToolParam.EVENT_TYPE, CoreEventType.ACTIONABLE_ITEM_REMOVED.name(), ToolParam.EVENT_DATA, "$s0_get_payload.result.actionableItemId"), "s0_get_payload").step("s2_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s1_remove_actionable_item").bootstrap(this);


            for (var type : new String[]{"nostr", "ui", "llm"}) {
                var eventType = SystemEventType.valueOf("SAVE_" + type.toUpperCase() + "_CONFIG_REQUESTED");
                PlanDefBuilder.create("system_listener_save_" + type + "_config_handler").title("System Listener: Save " + type.toUpperCase() + " Config").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(eventType, PlanState.PENDING).step("s1_get_state", Tool.GET_CONFIG_STATE, Map.of(ToolParam.CONFIG_TYPE, type)).step("s2_save_to_note", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, Config.CONFIG_NOTE_PREFIX + type, ToolParam.CONTENT_UPDATE, "$s1_get_state.result"), "s1_get_state").step("s3_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s2_save_to_note").bootstrap(this);
            }

            var loadAllConfigsBuilder = PlanDefBuilder.create("system_listener_load_all_configs_handler").title("System Listener: Load All Configurations").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.LOAD_ALL_CONFIGS_REQUESTED, PlanState.PENDING);
            List<String> lastApplyStepIds = new ArrayList<>();
            for (var type : List.of("nostr", "ui", "llm")) {
                var getId = "s_load_" + type + "_get_content";
                var applyId = "s_load_" + type + "_apply";
                loadAllConfigsBuilder.step(getId, Tool.GET_NOTE_PROPERTY, Map.of(ToolParam.NOTE_ID, Config.CONFIG_NOTE_PREFIX + type, ToolParam.PROPERTY_PATH, NoteProperty.CONTENT.getKey(), ToolParam.FAIL_IF_NOT_FOUND, false));
                loadAllConfigsBuilder.step(applyId, Tool.APPLY_CONFIG_STATE, Map.of(ToolParam.CONFIG_TYPE, type, ToolParam.STATE_MAP, "$" + getId + ".result"), getId);
                lastApplyStepIds.add(applyId);
            }
            loadAllConfigsBuilder.step("s_load_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), lastApplyStepIds.toArray(String[]::new)).bootstrap(this);


            PlanDefBuilder.create("system_listener_persistent_query_handler").title("System Listener: Persistent Query Handler").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.EVALUATE_PERSISTENT_QUERIES, PlanState.PENDING).step("s1_log_start", Tool.LOG_MESSAGE, Map.of(ToolParam.MESSAGE, "Evaluating persistent queries...")).step("s2_find_queries", Tool.FIND_NOTES_BY_TAG, Map.of(ToolParam.TAG, SystemTag.PERSISTENT_QUERY.value)).step("s3_foreach_query", Tool.FOR_EACH, Map.of(ToolParam.LIST, "$s2_find_queries.result", ToolParam.LOOP_VAR, "queryNote", ToolParam.LOOP_STEPS, List.of(Map.of(PlanStepKey.ID.getKey(), "s3_1_get_content", PlanStepKey.TOOL_NAME.getKey(), Tool.GET_NOTE_PROPERTY.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.NOTE_ID.getKey(), "$queryNote.id", ToolParam.PROPERTY_PATH.getKey(), NoteProperty.CONTENT.getKey())), Map.of(PlanStepKey.ID.getKey(), "s3_2_exec_query", PlanStepKey.TOOL_NAME.getKey(), Tool.EXECUTE_SEMANTIC_QUERY.name(), PlanStepKey.TOOL_PARAMS.getKey(), "$s3_1_get_content.result"), Map.of(PlanStepKey.ID.getKey(), "s3_3_update_note", PlanStepKey.TOOL_NAME.getKey(), Tool.MODIFY_NOTE_CONTENT.name(), PlanStepKey.TOOL_PARAMS.getKey(), Map.of(ToolParam.NOTE_ID.getKey(), "$queryNote.id", ToolParam.CONTENT_UPDATE.getKey(), Map.of(ContentKey.RESULTS.getKey(), "$s3_2_exec_query.result", ContentKey.LAST_RUN.getKey(), "NOW"))))), "s2_find_queries").step("s4_reschedule", Tool.SCHEDULE_SYSTEM_EVENT, Map.of(ToolParam.EVENT_TYPE, SystemEventType.EVALUATE_PERSISTENT_QUERIES.name(), ToolParam.DELAY_SECONDS, 3600L), "s3_foreach_query").step("s5_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s4_reschedule").bootstrap(this);

            PlanDefBuilder.create("system_listener_stalled_plan_handler").title("System Listener: Stalled Plan Handler").tags(SystemTag.SYSTEM_PROCESS_HANDLER.value, SystemTag.SYSTEM_NOTE.value).trigger(SystemEventType.STALLED_PLAN_DETECTED, PlanState.PENDING).step("s1_get_plan_id", Tool.GET_NOTE_PROPERTY, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.PROPERTY_PATH, "content.payload.planNoteId")).step("s2_log_stalled", Tool.LOG_MESSAGE, Map.of(ToolParam.MESSAGE, "Stalled plan detected: $s1_get_plan_id.result. Consider user notification or automated actions."), "s1_get_plan_id").step("s4_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", ToolParam.CONTENT_UPDATE, Map.of(ContentKey.STATUS.getKey(), "PROCESSED")), "s2_log_stalled").bootstrap(this);
        }

        public Object executeTool(Tool tool, Map<String, Object> params) {
            if (tool == null || !tools.containsKey(tool)) throw new IllegalArgumentException("Tool not found: " + tool);
            logger.info("Executing tool: {} with params: {}", tool.name(), params);
            try {
                return tools.get(tool).apply(this, params);
            } catch (Exception e) {
                logger.error("Error executing tool {}: {}", tool.name(), e.getMessage(), e);
                throw e;
            }
        }

        public List<Note> findRelatedNotes(Note sourceNote, int maxResults, double minSimilarity) {
            if (sourceNote == null || sourceNote.getEmbeddingV1() == null || !lm.isReady())
                return Collections.emptyList();
            var sourceEmbedding = sourceNote.getEmbeddingV1();
            return notes.getAllNotes().stream().filter(n -> {
                if (n.id.equals(sourceNote.id) || n.getEmbeddingV1() == null || n.getEmbeddingV1().length != sourceEmbedding.length)
                    return false;
                return !n.tags.contains(SystemTag.CONFIG.value);
            }).map(candidateNote -> new AbstractMap.SimpleEntry<>(candidateNote, LM.cosineSimilarity(sourceEmbedding, candidateNote.getEmbeddingV1()))).filter(entry -> entry.getValue() > minSimilarity).sorted(Map.Entry.<Note, Double>comparingByValue().reversed()).limit(maxResults).map(Map.Entry::getKey).collect(Collectors.toList());
        }

        public enum CoreEventType {
            NOTE_ADDED, NOTE_UPDATED, NOTE_DELETED, PLAN_UPDATED, USER_INTERACTION_REQUESTED, DISTRIBUTED_LM_RESULT, CONFIG_CHANGED, STATUS_MESSAGE, CHAT_MESSAGE_ADDED, ACTIONABLE_ITEM_ADDED, ACTIONABLE_ITEM_REMOVED, SYSTEM_EVENT_REQUESTED
        }

        public enum SystemEventType {
            NOSTR_KIND0_RECEIVED, NOSTR_KIND1_RECEIVED, NOSTR_KIND4_RECEIVED, NOSTR_KIND_UNKNOWN_RECEIVED, SAVE_NOSTR_CONFIG_REQUESTED, SAVE_UI_CONFIG_REQUESTED, SAVE_LLM_CONFIG_REQUESTED, LOAD_ALL_CONFIGS_REQUESTED, EVALUATE_PERSISTENT_QUERIES, STALLED_PLAN_DETECTED, UNKNOWN_EVENT_TYPE, FRIEND_REQUEST_RECEIVED, ACCEPT_FRIEND_REQUEST, REJECT_FRIEND_REQUEST
        }

        public enum Tool {
            LOG_MESSAGE, USER_INTERACTION, GET_NOTE_PROPERTY, PARSE_JSON, CREATE_NOTE, MODIFY_NOTE_CONTENT, DELETE_NOTE, ADD_CONTACT, IF_ELSE, DECRYPT_NOSTR_DM, UPDATE_CHAT_NOTE, FIRE_CORE_EVENT, SUGGEST_PLAN_STEPS, SCHEDULE_SYSTEM_EVENT, FIND_NOTES_BY_TAG, FOR_EACH, EXECUTE_SEMANTIC_QUERY, CREATE_LINKS, GET_PLAN_GRAPH_CONTEXT, GET_SYSTEM_HEALTH_METRICS, IDENTIFY_STALLED_PLANS, GET_CONFIG_STATE, APPLY_CONFIG_STATE, GET_SELF_NOSTR_INFO, ACCEPT_FRIEND_REQUEST, REJECT_FRIEND_REQUEST, SEND_FRIEND_REQUEST, DECOMPOSE_GOAL, PLAN, GET_PLAN_DEPENDENCIES, ASSERT_KIF, QUERY, RETRACT, API, ECHO, FILE_OPERATIONS, GENERATE_TASK_LOGIC, INSPECT, EVAL_EXPR, GENERATE, REFLECT, REASON, DEFINE_CONCEPT, EXEC, GRAPH_SEARCH, CODE_WRITING, CODE_EXECUTION, FIND_ASSERTIONS, IDENTIFY_CONCEPTS, SUMMARIZE, ENHANCE, SEND_DM, CREATE_OR_UPDATE_CONTACT_NOTE;

            public static Tool fromString(String text) {
                return Stream.of(values()).filter(t -> t.name().equalsIgnoreCase(text)).findFirst().orElseThrow(() -> new IllegalArgumentException("No enum constant Core.Tool." + text));
            }
        }

        private static class PlanDefBuilder {
            private final String noteId;
            private final List<String> tags = new ArrayList<>();
            private final Map<String, Object> contentProperties = new HashMap<>();
            private final List<Map<String, Object>> steps = new ArrayList<>();
            private String title;

            private PlanDefBuilder(String noteId) {
                this.noteId = noteId;
            }

            public static PlanDefBuilder create(String noteId) {
                return new PlanDefBuilder(noteId);
            }

            public PlanDefBuilder title(String title) {
                this.title = title;
                return this;
            }

            public PlanDefBuilder tags(String... tags) {
                this.tags.addAll(List.of(tags));
                return this;
            }

            public PlanDefBuilder trigger(SystemEventType eventType, PlanState status) {
                this.contentProperties.put("triggerEventType", eventType.name());
                this.contentProperties.put("triggerStatus", status.name());
                return this;
            }

            public PlanDefBuilder step(String id, Tool tool, Map<ToolParam, Object> params, String... dependsOn) {
                Map<String, Object> stepMap = new HashMap<>();
                stepMap.put(PlanStepKey.ID.getKey(), id);
                stepMap.put(PlanStepKey.TOOL_NAME.getKey(), tool.name());
                stepMap.put(PlanStepKey.TOOL_PARAMS.getKey(), params.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().getKey(), Map.Entry::getValue)));
                if (dependsOn.length > 0) stepMap.put(PlanStepKey.DEPENDS_ON_STEP_IDS.getKey(), List.of(dependsOn));
                this.steps.add(stepMap);
                return this;
            }

            public PlanDefBuilder step(String id, Tool tool, String description, Map<ToolParam, Object> params, String... dependsOn) {
                Map<String, Object> stepMap = new HashMap<>();
                stepMap.put(PlanStepKey.ID.getKey(), id);
                stepMap.put(PlanStepKey.DESCRIPTION.getKey(), description);
                stepMap.put(PlanStepKey.TOOL_NAME.getKey(), tool.name());
                stepMap.put(PlanStepKey.TOOL_PARAMS.getKey(), params.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().getKey(), Map.Entry::getValue)));
                if (dependsOn.length > 0) stepMap.put(PlanStepKey.DEPENDS_ON_STEP_IDS.getKey(), List.of(dependsOn));
                this.steps.add(stepMap);
                return this;
            }

            public void bootstrap(Core core) {
                if (core.notes.get(noteId).isPresent()) {
                    logger.debug("System plan {} already exists, skipping bootstrap.", noteId);
                    return;
                }
                var handlerNote = new Note();
                handlerNote.id = noteId;
                handlerNote.setTitle(title);
                handlerNote.tags.addAll(tags);
                handlerNote.content.putAll(contentProperties);
                handlerNote.content.put(ContentKey.PLAN_STEPS.getKey(), steps);
                core.notes.save(handlerNote, true);
                logger.info("Bootstrapped system note: {}", noteId);
            }
        }

        public record CoreEvent(CoreEventType type, Object data) {
        }

        private static class Tools {
            private static final Logger logger = LoggerFactory.getLogger(Tools.class);

            public static void registerAllTools(Map<Tool, BiFunction<Core, Map<String, Object>, Object>> tools) {
                tools.put(Tool.LOG_MESSAGE, Tools::logMessage);
                tools.put(Tool.GET_NOTE_PROPERTY, Tools::getNoteProperty);
                tools.put(Tool.PARSE_JSON, Tools::parseJson);
                tools.put(Tool.CREATE_NOTE, Tools::createNote);
                tools.put(Tool.MODIFY_NOTE_CONTENT, Tools::modifyNoteContent);
                tools.put(Tool.DELETE_NOTE, Tools::deleteNote);
                tools.put(Tool.ADD_CONTACT, Tools::addContact);
                tools.put(Tool.IF_ELSE, Tools::ifElse);
                tools.put(Tool.DECRYPT_NOSTR_DM, Tools::decryptNostrDm);
                tools.put(Tool.UPDATE_CHAT_NOTE, Tools::updateChatNote);
                tools.put(Tool.FIRE_CORE_EVENT, Tools::fireCoreEvent);
                tools.put(Tool.SUGGEST_PLAN_STEPS, Tools::suggestPlanSteps);
                tools.put(Tool.SCHEDULE_SYSTEM_EVENT, Tools::scheduleSystemEvent);
                tools.put(Tool.FIND_NOTES_BY_TAG, Tools::findNotesByTag);
                tools.put(Tool.FOR_EACH, Tools::forEach);
                tools.put(Tool.EXECUTE_SEMANTIC_QUERY, Tools::executeSemanticQuery);
                tools.put(Tool.GET_SYSTEM_HEALTH_METRICS, Tools::getSystemHealthMetrics);
                tools.put(Tool.GET_CONFIG_STATE, Tools::getConfigState);
                tools.put(Tool.APPLY_CONFIG_STATE, Tools::applyConfigState);
                tools.put(Tool.GET_SELF_NOSTR_INFO, Tools::getSelfNostrInfo);
                tools.put(Tool.ACCEPT_FRIEND_REQUEST, Tools::acceptFriendRequest);
                tools.put(Tool.REJECT_FRIEND_REQUEST, Tools::rejectFriendRequest);
                tools.put(Tool.SEND_FRIEND_REQUEST, Tools::sendFriendRequest);
            }

            private static Object logMessage(Core core, Map<String, Object> params) {
                logger.info("TOOL_LOG: {}", params.get(ToolParam.MESSAGE.getKey()));
                return null;
            }

            private static Object getNoteProperty(Core core, Map<String, Object> params) {
                var noteId = (String) params.get(ToolParam.NOTE_ID.getKey());
                var propertyPath = (String) params.get(ToolParam.PROPERTY_PATH.getKey());
                var failIfNotFound = (Boolean) params.getOrDefault(ToolParam.FAIL_IF_NOT_FOUND.getKey(), true);
                var defaultValue = params.get(ToolParam.DEFAULT_VALUE.getKey());

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

            private static Object parseJson(Core core, Map<String, Object> params) {
                var jsonString = (String) params.get(ToolParam.JSON_STRING.getKey());
                try {
                    return core.json.readTree(jsonString);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new RuntimeException("Failed to parse JSON: " + e.getMessage(), e);
                }
            }

            private static Object createNote(Core core, Map<String, Object> params) {
                var note = new Note();
                ofNullable((String) params.get(ToolParam.ID.getKey())).ifPresent(id -> note.id = id);
                ofNullable((String) params.get(ToolParam.TITLE.getKey())).ifPresent(note::setTitle);
                ofNullable((String) params.get(ToolParam.TEXT.getKey())).ifPresent(text -> {
                    if (Boolean.TRUE.equals(params.get(ToolParam.AS_HTML.getKey()))) note.setHtmlText(text);
                    else note.setText(text);
                });
                ofNullable((List<String>) params.get(ToolParam.TAGS.getKey())).ifPresent(note.tags::addAll);
                ofNullable((Map<String, Object>) params.get(ToolParam.CONTENT.getKey())).ifPresent(note.content::putAll);
                ofNullable((Map<String, Object>) params.get(ToolParam.METADATA.getKey())).ifPresent(note.meta::putAll);
                return core.saveNote(note);
            }

            private static Object modifyNoteContent(Core core, Map<String, Object> params) {
                var noteId = (String) params.get(ToolParam.NOTE_ID.getKey());
                var contentUpdate = (Map<String, Object>) params.get(ToolParam.CONTENT_UPDATE.getKey());
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

            private static Object deleteNote(Core core, Map<String, Object> params) {
                var noteId = (String) params.get(ToolParam.NOTE_ID.getKey());
                return core.deleteNote(noteId);
            }

            private static Note upsertContactAndChatNote(Core core, String nostrPubKeyHex, Map<String, Object> profileData) {
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

                var contactNoteOpt = core.notes.getAll(n -> n.tags.contains(SystemTag.CONTACT.value) && nostrPubKeyHex.equals(n.meta.get(Metadata.NOSTR_PUB_KEY_HEX.key))).stream().findFirst();

                var contactNote = contactNoteOpt.orElseGet(() -> {
                    var newNote = new Note();
                    newNote.tags.add(SystemTag.CONTACT.value);
                    newNote.tags.add(SystemTag.NOSTR_CONTACT.value);
                    newNote.meta.put(Metadata.NOSTR_PUB_KEY_HEX.key, nostrPubKeyHex);
                    newNote.meta.put(Metadata.NOSTR_PUB_KEY.key, finalNpub);
                    newNote.setTitle(profileData != null && profileData.containsKey("name") ? (String) profileData.get("name") : finalNpub.substring(0, 12) + "...");
                    newNote.setText(profileData != null && profileData.containsKey("about") ? (String) profileData.get("about") : "");
                    return newNote;
                });

                if (profileData != null) {
                    ofNullable((String) profileData.get("name")).ifPresent(contactNote::setTitle);
                    ofNullable((String) profileData.get("about")).ifPresent(contactNote::setText);
                    ofNullable((String) profileData.get("picture")).ifPresent(pic -> contactNote.content.put(ContentKey.PROFILE_PICTURE_URL.getKey(), pic));
                }
                contactNote.meta.put(Metadata.LAST_SEEN.key, Instant.now().toString());

                var chatNoteOpt = core.notes.getAll(n -> n.tags.contains(SystemTag.CHAT.value) && nostrPubKeyHex.equals(n.meta.get(Metadata.NOSTR_PUB_KEY_HEX.key))).stream().findFirst();

                if (chatNoteOpt.isEmpty()) {
                    var chatNote = new Note("Chat with " + contactNote.getTitle(), "");
                    chatNote.tags.add(SystemTag.CHAT.value);
                    chatNote.meta.put(Metadata.NOSTR_PUB_KEY_HEX.key, nostrPubKeyHex);
                    chatNote.meta.put(Metadata.NOSTR_PUB_KEY.key, finalNpub);
                    chatNote.content.put(ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                    core.saveNote(chatNote);
                    logger.info("Created new chat note for contact {}.", contactNote.getTitle());
                }

                return core.saveNote(contactNote);
            }

            private static Object addContact(Core core, Map<String, Object> params) {
                var nostrPubKeyHex = (String) params.get(ToolParam.NOSTR_PUB_KEY_HEX.getKey());
                var profileData = (Map<String, Object>) params.get(ToolParam.PROFILE_DATA.getKey());
                if (nostrPubKeyHex == null || nostrPubKeyHex.isEmpty()) {
                    throw new IllegalArgumentException("NOSTR_PUB_KEY_HEX is required for ADD_CONTACT.");
                }
                return upsertContactAndChatNote(core, nostrPubKeyHex, profileData);
            }

            private static Object ifElse(Core core, Map<String, Object> params) {
                var condition = (Boolean) params.get(ToolParam.CONDITION.getKey());
                var trueSteps = (List<Map<String, Object>>) params.get(ToolParam.TRUE_STEPS.getKey());
                var falseSteps = (List<Map<String, Object>>) params.get(ToolParam.FALSE_STEPS.getKey());

                List<Map<String, Object>> stepsToExecute = condition ? trueSteps : falseSteps;
                if (stepsToExecute != null && !stepsToExecute.isEmpty()) {
                    logger.warn("IF_ELSE tool does not directly execute nested steps. Steps: {}", stepsToExecute);
                }
                return condition;
            }

            private static Object decryptNostrDm(Core core, Map<String, Object> params) {
                var eventPayload = (Map<String, Object>) params.get(ToolParam.EVENT_PAYLOAD_MAP.getKey());
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

            private static Object updateChatNote(Core core, Map<String, Object> params) {
                var partnerPubKeyHex = (String) params.get(ToolParam.PARTNER_PUB_KEY_HEX.getKey());
                var senderPubKeyHex = (String) params.get(ToolParam.SENDER_PUB_KEY_HEX.getKey());
                var messageContent = (String) params.get(ToolParam.MESSAGE_CONTENT.getKey());
                var timestampEpochSeconds = (Long) params.get(ToolParam.TIMESTAMP_EPOCH_SECONDS.getKey());

                var selfNpubHex = core.net.getPublicKeyXOnlyHex();
                if (selfNpubHex == null || selfNpubHex.isEmpty()) {
                    logger.warn("Cannot update chat note: self Nostr public key not available.");
                    return null;
                }

                var chatNoteOpt = core.notes.getAll(n -> n.tags.contains(SystemTag.CHAT.value) && partnerPubKeyHex.equals(n.meta.get(Metadata.NOSTR_PUB_KEY_HEX.key))).stream().findFirst();

                var chatNote = chatNoteOpt.orElseGet(() -> {
                    var newChatNote = new Note();
                    newChatNote.tags.add(SystemTag.CHAT.value);
                    newChatNote.meta.put(Metadata.NOSTR_PUB_KEY_HEX.key, partnerPubKeyHex);
                    try {
                        newChatNote.meta.put(Metadata.NOSTR_PUB_KEY.key, Crypto.Bech32.nip19Encode("npub", Crypto.hexToBytes(partnerPubKeyHex)));
                    } catch (Exception e) {
                        logger.warn("Failed to encode npub for new chat note: {}", e.getMessage());
                        newChatNote.meta.put(Metadata.NOSTR_PUB_KEY.key, "npub_error_" + partnerPubKeyHex.substring(0, 8));
                    }
                    core.notes.getAll(n -> n.tags.contains(SystemTag.CONTACT.value) && partnerPubKeyHex.equals(n.meta.get(Metadata.NOSTR_PUB_KEY_HEX.key))).stream().findFirst().ifPresentOrElse(contact -> newChatNote.setTitle("Chat with " + contact.getTitle()), () -> newChatNote.setTitle("Chat with " + partnerPubKeyHex.substring(0, 12) + "..."));
                    newChatNote.content.put(ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());
                    return newChatNote;
                });

                var messages = (List<Map<String, String>>) chatNote.content.getOrDefault(ContentKey.MESSAGES.getKey(), new ArrayList<Map<String, String>>());

                final String finalSenderPubKeyHex = senderPubKeyHex;
                final Instant messageTimestamp = Instant.ofEpochSecond(timestampEpochSeconds);
                boolean isDuplicate = messages.stream().anyMatch(msg -> finalSenderPubKeyHex.equals(msg.get("sender")) && messageContent.equals(msg.get("text")) && messageTimestamp.equals(Instant.parse(msg.get("timestamp"))));

                if (!isDuplicate) {
                    var messageEntry = new HashMap<String, String>();
                    messageEntry.put("sender", senderPubKeyHex);
                    messageEntry.put("text", messageContent);
                    messageEntry.put("timestamp", Instant.ofEpochSecond(timestampEpochSeconds).toString());
                    messages.add(messageEntry);
                    chatNote.content.put(ContentKey.MESSAGES.getKey(), messages);

                    if (!senderPubKeyHex.equals(selfNpubHex)) {
                        int unreadCount = (Integer) chatNote.meta.getOrDefault(Metadata.UNREAD_MESSAGES_COUNT.key, 0);
                        chatNote.meta.put(Metadata.UNREAD_MESSAGES_COUNT.key, unreadCount + 1);
                    }

                    core.saveNote(chatNote);
                    core.fireCoreEvent(CoreEventType.CHAT_MESSAGE_ADDED, Map.of("chatNoteId", chatNote.id, "sender", senderPubKeyHex, "message", messageContent));
                    logger.info("Added message to chat with {}: {}", partnerPubKeyHex.substring(0, 8), messageContent.substring(0, Math.min(messageContent.length(), 50)));
                } else {
                    logger.debug("Skipping duplicate message for chat with {}: {}", partnerPubKeyHex.substring(0, 8), messageContent.substring(0, Math.min(messageContent.length(), 50)));
                }
                return chatNote.id;
            }

            private static Object fireCoreEvent(Core core, Map<String, Object> params) {
                var eventType = CoreEventType.valueOf((String) params.get(ToolParam.EVENT_TYPE.getKey()));
                var eventData = params.get(ToolParam.EVENT_DATA.getKey());
                core.fireCoreEvent(eventType, eventData);
                return null;
            }

            private static Object suggestPlanSteps(Core core, Map<String, Object> params) {
                var goalText = (String) params.get(ToolParam.GOAL_TEXT.getKey());
                if (core.lm.isReady()) {
                    return core.lm.decomposeTask(goalText).orElse(Collections.emptyList()).stream().map(stepDesc -> {
                        var step = new Planner.PlanStep();
                        step.description = stepDesc;
                        step.toolName = Tool.LOG_MESSAGE.name();
                        step.toolParams = Map.of(ToolParam.MESSAGE.getKey(), "Executing: " + stepDesc);
                        return step;
                    }).collect(Collectors.toList());
                }
                return Collections.emptyList();
            }

            private static Object scheduleSystemEvent(Core core, Map<String, Object> params) {
                var eventType = SystemEventType.valueOf((String) params.get(ToolParam.EVENT_TYPE.getKey()));
                var delaySeconds = ((Number) params.getOrDefault(ToolParam.DELAY_SECONDS.getKey(), 0L)).longValue();
                var payload = (Map<String, Object>) params.getOrDefault(ToolParam.PAYLOAD.getKey(), Collections.emptyMap());

                core.scheduler.schedule(() -> core.fireCoreEvent(CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(ToolParam.EVENT_TYPE.getKey(), eventType.name(), ToolParam.PAYLOAD.getKey(), payload, ContentKey.STATUS.getKey(), PlanState.PENDING.name())), delaySeconds, TimeUnit.SECONDS);
                return null;
            }

            private static Object findNotesByTag(Core core, Map<String, Object> params) {
                var tag = (String) params.get(ToolParam.TAG.getKey());
                return core.notes.getAll(n -> n.tags.contains(tag));
            }

            private static Object forEach(Core core, Map<String, Object> params) {
                var list = (List<Object>) params.get(ToolParam.LIST.getKey());
                var loopVarName = (String) params.get(ToolParam.LOOP_VAR.getKey());
                var loopSteps = (List<Map<String, Object>>) params.get(ToolParam.LOOP_STEPS.getKey());

                if (list == null || loopSteps == null || loopSteps.isEmpty()) {
                    logger.warn("FOR_EACH called with null list or empty loop steps.");
                    return null;
                }

                logger.info("FOR_EACH: Iterating {} items with loop variable '{}'. Steps: {}", list.size(), loopVarName, loopSteps.size());
                return null;
            }

            private static Object executeSemanticQuery(Core core, Map<String, Object> params) {
                var queryText = (String) params.get(ToolParam.QUERY_TEXT.getKey());
                var minSimilarity = ((Number) params.getOrDefault(ToolParam.MIN_SIMILARITY.getKey(), 0.7)).doubleValue();
                var maxResults = ((Number) params.getOrDefault(ToolParam.MAX_RESULTS.getKey(), 5)).intValue();

                if (!core.lm.isReady()) {
                    throw new RuntimeException("LLM service not ready for semantic query.");
                }

                return core.lm.generateEmbedding(queryText).map(queryEmb -> core.notes.getAllNotes().stream().filter(n -> n.getEmbeddingV1() != null && n.getEmbeddingV1().length == queryEmb.length).map(n -> new AbstractMap.SimpleEntry<>(n, LM.cosineSimilarity(queryEmb, n.getEmbeddingV1()))).filter(entry -> entry.getValue() >= minSimilarity).sorted(Map.Entry.<Note, Double>comparingByValue().reversed()).limit(maxResults).map(Map.Entry::getKey).collect(Collectors.toList())).orElse(Collections.emptyList());
            }

            private static Object getSystemHealthMetrics(Core core, Map<String, Object> params) {
                long pendingSystemEvents = core.notes.getAll(n -> n.tags.contains(SystemTag.SYSTEM_EVENT.value) && PlanState.PENDING.name().equals(n.content.get(ContentKey.STATUS.getKey()))).size();
                long activePlans = core.planner.getActive().size();
                long failedPlanStepsInActivePlans = core.planner.getActive().values().stream().flatMap(exec -> exec.steps.stream()).filter(step -> PlanStepState.FAILED.equals(step.status)).count();

                return Map.of("pendingSystemEvents", pendingSystemEvents, "activePlans", activePlans, "failedPlanStepsInActivePlans", failedPlanStepsInActivePlans);
            }

            private static Object getConfigState(Core core, Map<String, Object> params) {
                var configType = (String) params.get(ToolParam.CONFIG_TYPE.getKey());
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

            private static Object applyConfigState(Core core, Map<String, Object> params) {
                var configType = (String) params.get(ToolParam.CONFIG_TYPE.getKey());
                var stateMap = (Map<String, Object>) params.get(ToolParam.STATE_MAP.getKey());
                try {
                    switch (configType) {
                        case "nostr" -> {
                            var newConfig = core.json.convertValue(stateMap, Config.NostrSettings.class);
                            core.cfg.net.privateKeyBech32 = newConfig.privateKeyBech32;
                            core.cfg.net.publicKeyBech32 = newConfig.publicKeyBech32;
                            core.cfg.net.myProfileNoteId = newConfig.myProfileNoteId;
                            core.net.loadIdentity();
                            core.net.setEnabled(core.net.isEnabled());
                            core.fireCoreEvent(CoreEventType.CONFIG_CHANGED, "nostr_status_changed");
                        }
                        case "ui" -> {
                            var newConfig = core.json.convertValue(stateMap, Config.UISettings.class);
                            core.cfg.ui.theme = newConfig.theme;
                            core.fireCoreEvent(CoreEventType.CONFIG_CHANGED, "ui_theme_updated");
                        }
                        case "llm" -> {
                            var newConfig = core.json.convertValue(stateMap, Config.LMSettings.class);
                            core.cfg.lm.provider = newConfig.provider;
                            core.cfg.lm.ollamaBaseUrl = newConfig.ollamaBaseUrl;
                            core.cfg.lm.ollamaChatModelName = newConfig.ollamaChatModelName;
                            core.cfg.lm.ollamaEmbeddingModelName = newConfig.ollamaEmbeddingModelName;
                            core.lm.init();
                            core.fireCoreEvent(CoreEventType.CONFIG_CHANGED, "llm_status_changed");
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

            private static Object getSelfNostrInfo(Core core, Map<String, Object> params) {
                return Map.of("pubKeyHex", core.net.getPublicKeyXOnlyHex(), "pubKeyNpub", core.net.getPublicKeyBech32(), "myProfileNoteId", core.cfg.net.myProfileNoteId);
            }

            private static Object acceptFriendRequest(Core core, Map<String, Object> params) {
                var senderNpub = (String) params.get(ToolParam.FRIEND_REQUEST_SENDER_NPUB.getKey());
                var actionableItemId = (String) params.get(ToolParam.ACTIONABLE_ITEM_ID.getKey());

                if (senderNpub == null || senderNpub.isEmpty()) {
                    throw new IllegalArgumentException("FRIEND_REQUEST_SENDER_NPUB is required for ACCEPT_FRIEND_REQUEST.");
                }

                try {
                    var senderPubKeyHex = Crypto.bytesToHex(Crypto.Bech32.nip19Decode(senderNpub));
                    upsertContactAndChatNote(core, senderPubKeyHex, null);

                    core.net.sendDirectMessage(senderNpub, "Friend request accepted! Let's chat.");

                    core.fireCoreEvent(CoreEventType.ACTIONABLE_ITEM_REMOVED, actionableItemId);

                    logger.info("Accepted friend request from {}", senderNpub);
                    return true;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to accept friend request: " + e.getMessage(), e);
                }
            }

            private static Object rejectFriendRequest(Core core, Map<String, Object> params) {
                var actionableItemId = (String) params.get(ToolParam.ACTIONABLE_ITEM_ID.getKey());

                if (actionableItemId == null || actionableItemId.isEmpty()) {
                    throw new IllegalArgumentException("ACTIONABLE_ITEM_ID is required for REJECT_FRIEND_REQUEST.");
                }

                core.fireCoreEvent(CoreEventType.ACTIONABLE_ITEM_REMOVED, actionableItemId);

                logger.info("Rejected friend request (actionable item removed): {}", actionableItemId);
                return true;
            }

            private static Object sendFriendRequest(Core core, Map<String, Object> params) {
                var recipientNpub = (String) params.get(ToolParam.RECIPIENT_NPUB.getKey());
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
    }

    public static class Notes {
        private static final Logger logger = LoggerFactory.getLogger(Notes.class);
        private final Path dir;
        private final ObjectMapper json = Core.createObjectMapper();
        private final Map<String, Note> cache = new ConcurrentHashMap<>();

        public Notes(Path dir) {
            this.dir = dir;
            load();
        }

        private void load() {
            if (!Files.exists(dir)) {
                logger.warn("Data dir {} not exist.", dir);
                return;
            }
            try (var ps = Files.walk(dir)) {
                ps.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json")).forEach(this::loadFromFile);
                logger.info("Loaded {} notes.", cache.size());
            } catch (IOException e) {
                logger.error("Error walking data dir {}: {}", dir, e.getMessage(), e);
            }
        }

        private void loadFromFile(Path fp) {
            try {
                var n = json.readValue(fp.toFile(), Note.class);
                n.content.computeIfAbsent(ContentKey.CONTENT_TYPE.getKey(), k -> ContentType.TEXT_PLAIN.getValue());
                cache.put(n.id, n);
            } catch (IOException e) {
                logger.error("Failed to load note from {}: {}", fp, e.getMessage(), e);
            }
        }

        public Note save(Note n, boolean internalOperation) {
            n.updatedAt = Instant.now();
            var isNew = !cache.containsKey(n.id) || cache.get(n.id).version == 0;
            if (isNew) {
                n.createdAt = Objects.requireNonNullElse(n.createdAt, Instant.now());
                n.version = 1;
            } else {
                n.version = cache.get(n.id).version + 1;
            }
            n.content.computeIfAbsent(ContentKey.CONTENT_TYPE.getKey(), k -> ContentType.TEXT_PLAIN.getValue());
            cache.put(n.id, n);
            try {
                json.writeValue(dir.resolve(n.id + ".json").toFile(), n);
            } catch (IOException e) {
                logger.error("Failed to save note {}: {}", n.id, e.getMessage(), e);
            }
            return n;
        }

        public Note save(Note n) {
            return save(n, false);
        }

        public Optional<Note> get(String id) {
            return ofNullable(cache.get(id));
        }

        public List<Note> getAllNotes() {
            return new ArrayList<>(cache.values());
        }

        public List<Note> getAll(Predicate<Note> f) {
            return cache.values().stream().filter(f).collect(Collectors.toList());
        }

        public boolean delete(String id) {
            if (!cache.containsKey(id)) {
                logger.warn("Attempted delete non-existent note {}", id);
                return false;
            }
            cache.remove(id);
            try {
                Files.deleteIfExists(dir.resolve(id + ".json"));
                logger.info("🗑️ Deleted note {}", id);
                return true;
            } catch (IOException e) {
                logger.error("Failed to delete note file for {}: {}", id, e.getMessage(), e);
                return false;
            }
        }
    }

    public static class Config {
        public static final String CONFIG_NOTE_PREFIX = "netention_config_";
        private static final Logger logger = LoggerFactory.getLogger(Config.class);
        public final NostrSettings net = new NostrSettings();
        public final UISettings ui = new UISettings();
        public final LMSettings lm = new LMSettings();
        private final Notes notes;
        private final Core coreRef;

        public Config(Notes notes, Core core) {
            this.notes = notes;
            this.coreRef = core;
            logger.info("Config object initialized. Plan-driven loading will occur via Core.");
        }

        public void saveConfigObjectToNote(Object configInstance, String typeKey) {
            var noteId = CONFIG_NOTE_PREFIX + typeKey;
            var cfgNote = notes.get(noteId).orElse(new Note());
            cfgNote.id = noteId;
            cfgNote.setTitle("Config: " + typeKey);
            cfgNote.tags.clear();
            cfgNote.tags.addAll(List.of(SystemTag.CONFIG.value, typeKey + "_config", SystemTag.SYSTEM_NOTE.value));
            cfgNote.content.clear();
            cfgNote.content.putAll(coreRef.json.convertValue(configInstance, new TypeReference<Map<String, Object>>() {
            }));
            notes.save(cfgNote, true);
            logger.info("Directly saved {} config to note {} (e.g. initial bootstrap)", typeKey, noteId);
        }

        public void saveAllConfigs() {
            if (coreRef == null) {
                logger.error("Core reference not available in Config.");
                return;
            }
            Stream.of(Core.SystemEventType.SAVE_NOSTR_CONFIG_REQUESTED, Core.SystemEventType.SAVE_UI_CONFIG_REQUESTED, Core.SystemEventType.SAVE_LLM_CONFIG_REQUESTED).forEach(eventType -> coreRef.fireCoreEvent(Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(ToolParam.EVENT_TYPE.getKey(), eventType.name())));
            logger.info("Fired events to save all configurations via plans.");
        }

        public String generateNewNostrKeysAndUpdateConfig() {
            try {
                var privKeyRaw = Crypto.generatePrivateKey();
                var pubKeyXOnlyRaw = Crypto.getPublicKeyXOnly(privKeyRaw);
                net.privateKeyBech32 = Crypto.Bech32.nip19Encode("nsec", privKeyRaw);
                net.publicKeyBech32 = Crypto.Bech32.nip19Encode("npub", pubKeyXOnlyRaw);
                var pubKeyHex = Crypto.bytesToHex(pubKeyXOnlyRaw);

                var profileNote = (net.myProfileNoteId != null && !net.myProfileNoteId.isEmpty()) ? notes.get(net.myProfileNoteId).orElse(null) : null;
                if (profileNote == null) {
                    profileNote = new Note("My Nostr Profile", "Edit your profile details here.");
                    profileNote.id = "my_profile_" + pubKeyHex;
                    net.myProfileNoteId = profileNote.id;
                }
                profileNote.tags.clear();
                profileNote.tags.addAll(Arrays.asList(SystemTag.CONTACT.value, SystemTag.NOSTR_CONTACT.value, SystemTag.MY_PROFILE.value));
                profileNote.meta.putAll(Map.of(Metadata.NOSTR_PUB_KEY.key, net.publicKeyBech32, Metadata.NOSTR_PUB_KEY_HEX.key, pubKeyHex));
                profileNote.content.putIfAbsent(ContentKey.PROFILE_NAME.getKey(), "Anonymous");
                profileNote.content.putIfAbsent(ContentKey.PROFILE_ABOUT.getKey(), "");
                profileNote.content.putIfAbsent(ContentKey.PROFILE_PICTURE_URL.getKey(), "");
                notes.save(profileNote);

                if (coreRef != null)
                    coreRef.fireCoreEvent(Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(ToolParam.EVENT_TYPE.getKey(), Core.SystemEventType.SAVE_NOSTR_CONFIG_REQUESTED.name()));
                else saveConfigObjectToNote(net, "nostr");
                return "nsec: " + net.privateKeyBech32 + "\nnpub: " + net.publicKeyBech32;
            } catch (Exception e) {
                logger.error("Failed to generate Nostr keys", e);
                return "Error: " + e.getMessage();
            }
        }

        public static class NostrSettings {
            @Field(label = "Private Key (nsec)", tooltip = "Nostr secret key (nsec...)", type = FieldType.PASSWORD_FIELD, group = "Identity")
            public String privateKeyBech32 = "";
            public String publicKeyBech32 = "";
            public String myProfileNoteId = "";
        }

        public static class UISettings {
            @Field(label = "Minimize to System Tray", tooltip = "If enabled, closing the window minimizes to tray instead of exiting.", group = "Behavior")
            public final boolean minimizeToTray = true;
            @Field(label = "Theme", type = FieldType.COMBO_BOX, choices = {"Nimbus (Dark)", "System"}, group = "Appearance")
            public String theme = "Nimbus (Dark)";
        }

        public static class LMSettings {
            @Field(label = "Provider", type = FieldType.COMBO_BOX, choices = {"NONE", "OLLAMA"})
            public String provider = "NONE";
            @Field(label = "Base URL", group = "Ollama")
            public String ollamaBaseUrl = "http://localhost:11434";
            @Field(label = "Chat Model", group = "Ollama")
            public String ollamaChatModelName = "llama3";
            @Field(label = "Embedding Model", group = "Ollama")
            public String ollamaEmbeddingModelName = "nomic-embed-text";
        }
    }

}
