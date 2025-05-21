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
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
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
            content.put(ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_PLAIN.getValue());
        }

        public Note(String t, String txt) {
            this();
            this.content.putAll(Map.of(
                    ContentKey.TITLE.getKey(), t,
                    ContentKey.TEXT.getKey(), txt,
                    ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_PLAIN.getValue()
            ));
        }

        public String getTitle() {
            return (String) content.getOrDefault(ContentKey.TITLE.getKey(), "Untitled");
        }

        public void setTitle(String t) {
            content.put(ContentKey.TITLE.getKey(), t);
        }

        public String getText() {
            return (String) content.getOrDefault(ContentKey.TEXT.getKey(), "");
        }

        public void setText(String t) {
            content.put(ContentKey.TEXT.getKey(), t);
            if (!ContentType.TEXT_HTML.getValue().equals(content.get(ContentKey.CONTENT_TYPE.getKey()))) {
                content.put(ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_PLAIN.getValue());
            }
        }

        public ContentType getContentTypeEnum() {
            return ContentType.fromString((String) content.getOrDefault(ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_PLAIN.getValue()));
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
            content.put(ContentKey.TEXT.getKey(), html);
            content.put(ContentKey.CONTENT_TYPE.getKey(), ContentType.TEXT_HTML.getValue());
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
                    Core.logger.warn("Failed to strip HTML for embedding note {}: {}", id, e.getMessage());
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

        public enum ContentKey {
            TITLE, TEXT, CONTENT_TYPE, PLAN_STEPS, EVENT_TYPE, PAYLOAD, STATUS, MESSAGES,
            PROFILE_NAME, PROFILE_ABOUT, PROFILE_PICTURE_URL, RESULTS, LAST_RUN,
            RELAY_URL, RELAY_ENABLED, RELAY_READ, RELAY_WRITE;

            public String getKey() {
                return name().toLowerCase();
            }
        }

        public enum Metadata {
            PLAN_STATUS, PLAN_START_TIME, PLAN_END_TIME, NOSTR_EVENT_ID, NOSTR_PUB_KEY_HEX,
            NOSTR_RAW_EVENT, CREATED_AT_FROM_EVENT, NOSTR_PUB_KEY, LAST_SEEN,
            PROFILE_LAST_UPDATED_AT, LLM_SUMMARY("llm:summary"), LLM_DECOMPOSITION("llm:decomposition");
            public final String key;

            Metadata() {
                this.key = name().toLowerCase();
            }

            Metadata(String key) {
                this.key = key;
            }

        }

        public enum SystemTag {
            SYSTEM_EVENT("#system_event"), SYSTEM_PROCESS_HANDLER("#system_process_handler"),
            SYSTEM_NOTE("#system_note"), CONFIG("config"), GOAL_WITH_PLAN("#goal_with_plan"),
            NOSTR_FEED("nostr_feed"), CONTACT("contact"), NOSTR_CONTACT("nostr_contact"),
            CHAT("chat"), TEMPLATE("#template"), PERSISTENT_QUERY("#persistent_query"),
            NOSTR_RELAY("#nostr_relay"), MY_PROFILE("#my_profile");
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
        private final Map<String, PlanExecution> activePlans = new ConcurrentHashMap<>();
        private final ObjectMapper objectMapper = Core.createObjectMapper();

        public Planner(Core core) {
            this.core = core;
        }

        @SuppressWarnings("unchecked")
        public void executePlan(Note goal, Map<String, Object> initialContext) {
            if (goal == null) {
                logger.warn("Goal note is null, cannot execute plan.");
                return;
            }

            var exe = activePlans.compute(goal.id, (k, existingExec) ->
                    (existingExec != null && PlanState.RUNNING.equals(existingExec.currentStatus)) ?
                            existingExec : new PlanExecution(goal.id, initialContext)
            );
            if (exe.context.isEmpty() && !initialContext.isEmpty()) exe.context.putAll(initialContext);


            if (!PlanState.RUNNING.equals(exe.currentStatus) || exe.steps.isEmpty()) {
                exe.currentStatus = PlanState.PARSING;
                goal.meta.putAll(Map.of(Note.Metadata.PLAN_STATUS.key, exe.currentStatus.name(),
                        Note.Metadata.PLAN_START_TIME.key, Instant.now().toString()));
                core.saveNote(goal);

                var planStepsData = goal.content.get(Note.ContentKey.PLAN_STEPS.getKey());
                if (planStepsData instanceof List<?> rawStepsList && exe.steps.isEmpty()) {
                    try {
                        for (var i = 0; i < rawStepsList.size(); i++) {
                            if (rawStepsList.get(i) instanceof Map rawStepObj) {
                                var step = objectMapper.convertValue(rawStepObj, PlanStep.class);
                                if (step.id == null || step.id.isEmpty() || step.id.matches("step\\d+_id_placeholder")) {
                                    step.id = "step_" + i + "_" + UUID.randomUUID().toString().substring(0, 4);
                                }
                                exe.steps.add(step);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Failed to parse plan steps for note {}", goal.id, e);
                        exe.currentStatus = PlanState.FAILED_PARSING;
                        goal.meta.put(Note.Metadata.PLAN_STATUS.key, exe.currentStatus.name());
                        core.saveNote(goal);
                        activePlans.remove(goal.id);
                        return;
                    }
                }

                if (exe.steps.isEmpty() && goal.tags.contains(Note.SystemTag.GOAL_WITH_PLAN.value)) {
                    try {
                        var initialSteps = (List<PlanStep>) core.executeTool(Core.Tool.SUGGEST_PLAN_STEPS,
                                Map.of(ToolParam.GOAL_TEXT.getKey(), goal.getText().isEmpty() ? goal.getTitle() : goal.getText()));
                        if (initialSteps != null && !initialSteps.isEmpty()) exe.steps.addAll(initialSteps);
                        else addDefaultInitialStep(exe, goal);
                    } catch (Exception e) {
                        logger.warn("Failed to get initial plan steps from LM for {}: {}", goal.id, e.getMessage());
                        addDefaultInitialStep(exe, goal);
                    }
                } else if (exe.steps.isEmpty()) {
                    logger.warn("Plan {} has no steps defined and is not a typical user goal for LM suggestion.", goal.id);
                    exe.currentStatus = PlanState.FAILED_NO_STEPS;
                    goal.meta.put(Note.Metadata.PLAN_STATUS.key, exe.currentStatus.name());
                    core.saveNote(goal);
                    activePlans.remove(goal.id);
                    return;
                }

                goal.content.put(Note.ContentKey.PLAN_STEPS.getKey(), exe.steps.stream().map(s -> objectMapper.convertValue(s, Map.class)).collect(Collectors.toList()));
                exe.currentStatus = PlanState.RUNNING;
                goal.meta.put(Note.Metadata.PLAN_STATUS.key, exe.currentStatus.name());
                core.saveNote(goal);
            }
            core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, exe);
            tick();
        }

        public void executePlan(Note goalNote) {
            executePlan(goalNote, new HashMap<>());
        }

        private void addDefaultInitialStep(PlanExecution execution, Note goalNote) {
            var initialStep = new PlanStep();
            initialStep.description = "Initial analysis of goal: " + goalNote.getTitle();
            initialStep.toolName = Core.Tool.LOG_MESSAGE.name();
            initialStep.toolParams = Map.of(ToolParam.MESSAGE.getKey(), "Starting plan for: " + goalNote.getTitle());
            execution.steps.add(initialStep);
        }

        public synchronized void tick() {
            activePlans.values().stream()
                    .filter(exec -> PlanState.RUNNING.equals(exec.currentStatus) || exec.steps.stream().anyMatch(s -> PlanStepState.PENDING_RETRY.equals(s.status)))
                    .forEach(this::processExecution);
        }

        private void processExecution(PlanExecution exec) {
            findNextRunnableStep(exec).ifPresentOrElse(currentStep -> {
                if (PlanStepState.PENDING.equals(currentStep.status) || PlanStepState.PENDING_RETRY.equals(currentStep.status)) {
                    currentStep.status = PlanStepState.RUNNING;
                    currentStep.startTime = Instant.now();
                    core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, exec);
                    executeStep(exec, currentStep);
                }
            }, () -> updateOverallPlanStatus(exec));
        }

        private void updateOverallPlanStatus(PlanExecution exec) {
            var allCompleted = exec.steps.stream().allMatch(s -> PlanStepState.COMPLETED.equals(s.status));
            var anyFailedNoAlternativesOrRetries = exec.steps.stream().anyMatch(s -> PlanStepState.FAILED.equals(s.status) && s.retryCount >= s.maxRetries && (s.alternatives.isEmpty() || s.currentAlternativeIndex >= s.alternatives.size() - 1));
            var anyRunningOrWaiting = exec.steps.stream().anyMatch(s -> Set.of(PlanStepState.RUNNING, PlanStepState.WAITING_FOR_USER, PlanStepState.PENDING_RETRY).contains(s.status));

            if (allCompleted && !anyRunningOrWaiting) exec.currentStatus = PlanState.COMPLETED;
            else if (anyFailedNoAlternativesOrRetries && !anyRunningOrWaiting) exec.currentStatus = PlanState.FAILED;
            else if (!anyRunningOrWaiting && exec.steps.stream().noneMatch(s -> PlanStepState.PENDING.equals(s.status) || PlanStepState.PENDING_RETRY.equals(s.status))) {
                exec.currentStatus = PlanState.STUCK;
            }

            if (!PlanState.RUNNING.equals(exec.currentStatus) && exec.steps.stream().noneMatch(s -> PlanStepState.PENDING_RETRY.equals(s.status))) {
                core.notes.get(exec.planNoteId).ifPresent(n -> {
                    n.meta.put(Note.Metadata.PLAN_STATUS.key, exec.currentStatus.name());
                    if (Set.of(PlanState.COMPLETED, PlanState.FAILED, PlanState.STUCK).contains(exec.currentStatus)) {
                        n.meta.put(Note.Metadata.PLAN_END_TIME.key, Instant.now().toString());
                    }
                    core.saveNote(n);
                });
                if (Set.of(PlanState.COMPLETED, PlanState.FAILED, PlanState.STUCK).contains(exec.currentStatus)) {
                    activePlans.remove(exec.planNoteId);
                }
                core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, exec);
            }
        }

        private Optional<PlanStep> findNextRunnableStep(PlanExecution exec) {
            return exec.steps.stream()
                    .filter(step -> PlanStepState.PENDING.equals(step.status) || PlanStepState.PENDING_RETRY.equals(step.status))
                    .filter(step -> step.dependsOnStepIds.isEmpty() || step.dependsOnStepIds.stream().allMatch(depId ->
                            exec.getStepById(depId).map(depStep -> PlanStepState.COMPLETED.equals(depStep.status)).orElseGet(() -> {
                                logger.warn("Dependency step {} not found for step {}", depId, step.id);
                                return false;
                            })
                    ))
                    .findFirst();
        }

        private Object traversePath(Object current, String[] parts, int startIndex, PlanStep currentStepContext) {
            var c = current;
            for (var i = startIndex; i < parts.length; i++) {
                if (c == null) return null;
                var part = parts[i];
                int I = i;
                switch (c) {
                    case PlanStep ps when "result".equals(part) && I == startIndex && ps == currentStepContext ->  // Handle $stepId.result
                            c = ps.result;
                    case Map map -> c = map.get(part);
                    case JsonNode jn -> c = jn.has(part) ? jn.get(part) : null;
                    default -> {
                        return null;
                    }
                }
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
                        step.status = PlanStepState.WAITING_FOR_USER;
                        core.fireCoreEvent(Core.CoreEventType.USER_INTERACTION_REQUESTED, Map.of(
                                ToolParam.PROMPT.getKey(), resolvedParams.getOrDefault(ToolParam.PROMPT.getKey(), "Provide input:"),
                                ToolParam.CALLBACK_KEY.getKey(), callbackKey, ToolParam.PLAN_NOTE_ID.getKey(), planExec.planNoteId
                        ));
                        core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, planExec);
                        return;
                    }

                    var result = core.executeTool(currentTool, resolvedParams);
                    step.result = result;
                    if (step.id != null && result != null) planExec.context.put(step.id + ".result", result);
                    step.status = PlanStepState.COMPLETED;
                    logger.info("Step {} (Tool: {}) completed successfully.", step.description, currentToolNameStr);
                } catch (Exception e) {
                    logger.error("Step {} (Tool: {}) failed: {}", step.description, currentToolNameStr, e.getMessage(), e);
                    if (step.currentAlternativeIndex < step.alternatives.size() - 1) {
                        step.currentAlternativeIndex++;
                        step.status = PlanStepState.PENDING_RETRY;
                        logger.info("Will try next alternative for step {}", step.description);
                    } else if (step.retryCount < step.maxRetries) {
                        step.retryCount++;
                        step.currentAlternativeIndex = -1; // Reset for primary tool retry
                        step.status = PlanStepState.PENDING_RETRY;
                        logger.info("Will retry (attempt {}) step {}", step.retryCount, step.description);
                    } else {
                        step.status = PlanStepState.FAILED;
                        step.result = e.getMessage();
                    }
                } finally {
                    if (Set.of(PlanStepState.COMPLETED, PlanStepState.FAILED).contains(step.status))
                        step.endTime = Instant.now();
                    core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, planExec);
                    SwingUtilities.invokeLater(this::tick);
                }
            }).start();
        }

        public void postUserInteractionResult(String callbackKey, Object result) {
            activePlans.values().stream()
                    .filter(exec -> exec.waitingCallbacks.containsKey(callbackKey))
                    .findFirst().ifPresent(exec -> {
                        var step = exec.waitingCallbacks.remove(callbackKey);
                        if (step != null) {
                            step.result = result;
                            step.status = (result == null || (result instanceof String s && s.isEmpty())) ? PlanStepState.FAILED : PlanStepState.COMPLETED;
                            logger.info("User interaction for step {} {}.", step.description, step.status == PlanStepState.COMPLETED ? "completed" : "failed (empty input)");
                            if (step.id != null && result != null) exec.context.put(step.id + ".result", result);
                            step.endTime = Instant.now();
                            core.fireCoreEvent(Core.CoreEventType.PLAN_UPDATED, exec);
                            SwingUtilities.invokeLater(this::tick);
                        }
                    });
        }

        public Optional<PlanExecution> getPlanExecution(String planNoteId) {
            return ofNullable(activePlans.get(planNoteId));
        }

        public Map<String, PlanExecution> getActivePlans() {
            return Collections.unmodifiableMap(activePlans);
        }

        public enum PlanStepKey {
            ID, DESCRIPTION, TOOL_NAME, TOOL_PARAMS, DEPENDS_ON_STEP_IDS, STATUS, RESULT, OUTPUT_NOTE_ID, START_TIME, END_TIME, ALTERNATIVES, RETRY_COUNT, MAX_RETRIES, CURRENT_ALTERNATIVE_INDEX;

            public String getKey() {
                return name().toLowerCase();
            }
        }

        public enum PlanState {PENDING, RUNNING, COMPLETED, FAILED, STUCK, PARSING, FAILED_PARSING, FAILED_NO_STEPS}

        public enum PlanStepState {PENDING, RUNNING, COMPLETED, FAILED, WAITING_FOR_USER, PENDING_RETRY}

        public enum ToolParam {
            MESSAGE, NOTE_ID, PROPERTY_PATH, FAIL_IF_NOT_FOUND, DEFAULT_VALUE, JSON_STRING, ID, TITLE, TEXT, AS_HTML, TAGS, CONTENT, METADATA, CONTENT_UPDATE,
            NOSTR_PUB_KEY_HEX, PROFILE_DATA, CONDITION, TRUE_STEPS, FALSE_STEPS, EVENT_PAYLOAD_MAP, PARTNER_PUB_KEY_HEX, SENDER_PUB_KEY_HEX,
            MESSAGE_CONTENT, TIMESTAMP_EPOCH_SECONDS, EVENT_TYPE, EVENT_DATA, GOAL_TEXT, PAYLOAD, DELAY_SECONDS, TAG, LIST, LOOP_VAR, LOOP_STEPS,
            QUERY_TEXT, MIN_SIMILARITY, MAX_RESULTS, SOURCE_NOTE_ID, LINKS, STALL_THRESHOLD_SECONDS, CONFIG_TYPE, STATE_MAP, PROMPT, CALLBACK_KEY, PLAN_NOTE_ID;

            public String getKey() {
                return name().toLowerCase();
            }
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
            public PlanStepState status = PlanStepState.PENDING;
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
            public PlanState currentStatus = PlanState.PENDING;

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
        private final List<Consumer<CoreEvent>> coreEventListeners = new CopyOnWriteArrayList<>();
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "NetentionCoreScheduler");
            t.setDaemon(true);
            return t;
        });

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

            fireCoreEvent(CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
                    Planner.ToolParam.EVENT_TYPE.getKey(), SystemEventType.LOAD_ALL_CONFIGS_REQUESTED.name(),
                    Planner.ToolParam.PAYLOAD.getKey(), Collections.emptyMap(),
                    Note.ContentKey.STATUS.getKey(), Planner.PlanState.PENDING.name()
            ));
            this.lm = new LM(cfg);
            this.net = new Nostr(cfg, this, this::handleRawNostrEvent, () -> cfg.net.publicKeyBech32);
            scheduler.schedule(() -> fireCoreEvent(CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
                    Planner.ToolParam.EVENT_TYPE.getKey(), SystemEventType.EVALUATE_PERSISTENT_QUERIES.name(),
                    Planner.ToolParam.PAYLOAD.getKey(), Collections.emptyMap(),
                    Note.ContentKey.STATUS.getKey(), Planner.PlanState.PENDING.name()
            )), 30, TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Netention stop...");
                scheduler.shutdownNow();
                if (net.isEnabled()) net.setEnabled(false);
                logger.info("Netention shutdown complete.");
            }));
            logger.info("NetentionCore initialized.");
        }

        public static ObjectMapper createObjectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(SerializationFeature.INDENT_OUTPUT, true)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        }

        static Object getNoteSpecificProperty(Note n, String propertyNameStr) {
            var propertyName = Note.NoteProperty.valueOf(propertyNameStr.toUpperCase());
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
                @SuppressWarnings("unchecked") var details = (Map<String, Object>) eventDetailsMap;
                var systemEventNote = new Note();
                systemEventNote.tags.add(Note.SystemTag.SYSTEM_EVENT.value);
                var eventTypeVal = details.get(Planner.ToolParam.EVENT_TYPE.getKey());
                systemEventNote.content.put(Note.ContentKey.EVENT_TYPE.getKey(), Objects.requireNonNullElse(eventTypeVal, SystemEventType.UNKNOWN_EVENT_TYPE.name()).toString());
                if (eventTypeVal == null)
                    logger.error("SYSTEM_EVENT_REQUESTED fired with null eventType in data: {}", data);
                systemEventNote.content.put(Note.ContentKey.PAYLOAD.getKey(), Objects.requireNonNullElse(details.get(Planner.ToolParam.PAYLOAD.getKey()), Collections.emptyMap()));
                systemEventNote.content.put(Note.ContentKey.STATUS.getKey(), Planner.PlanState.PENDING.name());
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
                fireCoreEvent(CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(
                        Planner.ToolParam.EVENT_TYPE.getKey(), eventType.name(),
                        Planner.ToolParam.PAYLOAD.getKey(), json.convertValue(event, Map.class),
                        Note.ContentKey.STATUS.getKey(), Planner.PlanState.PENDING.name(),
                        "originalKind", event.kind
                ));
            } catch (Exception e) {
                logger.error("Failed to process raw NostrEvent for system event note: {}", e.getMessage(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private void checkForSystemTriggers(Note triggeredNote) {
            if (!triggeredNote.tags.contains(Note.SystemTag.SYSTEM_EVENT.value)) return;
            var handlers = notes.getAll(n -> n.tags.contains(Note.SystemTag.SYSTEM_PROCESS_HANDLER.value));
            for (var handlerNote : handlers) {
                var handlerContent = handlerNote.content;
                var expectedEventType = (String) handlerContent.get("triggerEventType");
                var expectedStatus = (String) handlerContent.get("triggerStatus");
                var eventNoteContent = triggeredNote.content;
                var eventTypeMatches = expectedEventType != null && expectedEventType.equals(eventNoteContent.get(Note.ContentKey.EVENT_TYPE.getKey()));
                var statusMatches = expectedStatus == null || expectedStatus.equals(eventNoteContent.get(Note.ContentKey.STATUS.getKey()));

                if (eventTypeMatches && statusMatches) {
                    logger.info("System trigger matched for handler {} by event note {}", handlerNote.id, triggeredNote.id);
                    var existingExecution = planner.getPlanExecution(handlerNote.id).orElse(null);
                    if (existingExecution != null && Planner.PlanState.RUNNING.equals(existingExecution.currentStatus)) {
                        logger.warn("Handler plan {} is already running. Skipping new trigger by {}.", handlerNote.id, triggeredNote.id);
                        continue;
                    }
                    Map<String, Object> planContext = new HashMap<>();
                    planContext.put("trigger", Map.of("sourceEventNoteId", triggeredNote.id, "eventContent", new HashMap<>(triggeredNote.content)));
                    planner.executePlan(handlerNote, planContext);
                }
            }
        }

        private void bootstrapSystemNotes() {
            logger.info("Bootstrapping system notes...");
            PlanDefBuilder.create("system_listener_nostr_kind0_handler")
                    .title("System Listener: Nostr Kind 0 (Profile) Handler")
                    .tags(Note.SystemTag.SYSTEM_PROCESS_HANDLER.value, Note.SystemTag.SYSTEM_NOTE.value)
                    .trigger(SystemEventType.NOSTR_KIND0_RECEIVED, Planner.PlanState.PENDING)
                    .step("s0_get_payload", Tool.GET_NOTE_PROPERTY, Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.PROPERTY_PATH, "content.payload"))
                    .step("s0a_get_self_info", Tool.GET_SELF_NOSTR_INFO, Map.of())
                    .step("s1_parse_profile", Tool.PARSE_JSON, Map.of(Planner.ToolParam.JSON_STRING, "$s0_get_payload.result.content"), "s0_get_payload")
                    .step("s2_if_self_profile", Tool.IF_ELSE, Map.of(
                            Planner.ToolParam.CONDITION, "$s0_get_payload.result.pubkey == $s0a_get_self_info.result.pubKeyHex",
                            Planner.ToolParam.TRUE_STEPS, List.of(
                                    Map.of(Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.MODIFY_NOTE_CONTENT.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.NOTE_ID.getKey(), "$s0a_get_self_info.result.myProfileNoteId", Planner.ToolParam.CONTENT_UPDATE.getKey(), Map.of(Note.ContentKey.PROFILE_NAME.getKey(), "$s1_parse_profile.result.name", Note.ContentKey.PROFILE_ABOUT.getKey(), "$s1_parse_profile.result.about", Note.ContentKey.PROFILE_PICTURE_URL.getKey(), "$s1_parse_profile.result.picture", "metadataUpdate", Map.of(Note.Metadata.PROFILE_LAST_UPDATED_AT.key, "NOW")))),
                                    Map.of(Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.LOG_MESSAGE.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.MESSAGE.getKey(), "Updated own Nostr profile from Kind 0 event."))
                            ),
                            Planner.ToolParam.FALSE_STEPS, List.of(Map.of(Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.CREATE_OR_UPDATE_CONTACT_NOTE.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.NOSTR_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey", Planner.ToolParam.PROFILE_DATA.getKey(), "$s1_parse_profile.result")))
                    ), "s0_get_payload", "s0a_get_self_info", "s1_parse_profile")
                    .step("s3_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.CONTENT_UPDATE, Map.of(Note.ContentKey.STATUS.getKey(), "PROCESSED")), "s2_if_self_profile")
                    .bootstrap(this);

            PlanDefBuilder.create("system_listener_nostr_kind1_handler")
                    .title("System Listener: Nostr Kind 1 Handler")
                    .tags(Note.SystemTag.SYSTEM_PROCESS_HANDLER.value, Note.SystemTag.SYSTEM_NOTE.value)
                    .trigger(SystemEventType.NOSTR_KIND1_RECEIVED, Planner.PlanState.PENDING)
                    .step("s0_get_payload", Tool.GET_NOTE_PROPERTY, "Get Nostr event object", Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.PROPERTY_PATH, "content.payload"))
                    .step("s1_check_exists", Tool.GET_NOTE_PROPERTY, "Check if Nostr event note already exists", Map.of(Planner.ToolParam.NOTE_ID, "nostr_event_$s0_get_payload.result.id", Planner.ToolParam.PROPERTY_PATH, Note.NoteProperty.ID.getKey(), Planner.ToolParam.FAIL_IF_NOT_FOUND, false), "s0_get_payload")
                    .step("s2_if_else", Tool.IF_ELSE, "Process if new, else log", Map.of(
                            Planner.ToolParam.CONDITION, "$s1_check_exists.result == null",
                            Planner.ToolParam.TRUE_STEPS, List.of(
                                    Map.of(Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.CREATE_NOTE.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.ID.getKey(), "nostr_event_$s0_get_payload.result.id", Planner.ToolParam.TITLE.getKey(), "Nostr: $s0_get_payload.result.content_substring_30", Planner.ToolParam.TEXT.getKey(), "$s0_get_payload.result.content", Planner.ToolParam.TAGS.getKey(), List.of(Note.SystemTag.NOSTR_FEED.value), Planner.ToolParam.METADATA.getKey(), Map.of(Note.Metadata.NOSTR_EVENT_ID.key, "$s0_get_payload.result.id", Note.Metadata.NOSTR_PUB_KEY_HEX.key, "$s0_get_payload.result.pubkey", Note.Metadata.NOSTR_PUB_KEY.key, "$s0_get_payload.result.pubkey_npub", Note.Metadata.NOSTR_RAW_EVENT.key, "$s0_get_payload.result", Note.Metadata.CREATED_AT_FROM_EVENT.key, "$s0_get_payload.result.created_at"))),
                                    Map.of(Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.CREATE_OR_UPDATE_CONTACT_NOTE.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.NOSTR_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey"))
                            ),
                            Planner.ToolParam.FALSE_STEPS, List.of(Map.of(Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.LOG_MESSAGE.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.MESSAGE.getKey(), "Skipping duplicate Nostr Kind 1 event: $s0_get_payload.result.id")))
                    ), "s0_get_payload", "s1_check_exists")
                    .step("s3_mark_processed", Tool.MODIFY_NOTE_CONTENT, "Mark system event as processed", Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.CONTENT_UPDATE, Map.of(Note.ContentKey.STATUS.getKey(), "PROCESSED")), "s2_if_else")
                    .bootstrap(this);

            PlanDefBuilder.create("system_listener_nostr_kind4_handler")
                    .title("System Listener: Nostr Kind 4 (DM) Handler")
                    .tags(Note.SystemTag.SYSTEM_PROCESS_HANDLER.value, Note.SystemTag.SYSTEM_NOTE.value)
                    .trigger(SystemEventType.NOSTR_KIND4_RECEIVED, Planner.PlanState.PENDING)
                    .step("s0_get_payload", Tool.GET_NOTE_PROPERTY, "Get Nostr event object", Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.PROPERTY_PATH, "content.payload"))
                    .step("s1_decrypt_dm", Tool.DECRYPT_NOSTR_DM, "Decrypt DM content", Map.of(Planner.ToolParam.EVENT_PAYLOAD_MAP, "$s0_get_payload.result"), "s0_get_payload")
                    .step("s2_update_contact", Tool.CREATE_OR_UPDATE_CONTACT_NOTE, "Update contact for sender", Map.of(Planner.ToolParam.NOSTR_PUB_KEY_HEX, "$s0_get_payload.result.pubkey"), "s0_get_payload")
                    .step("s3_if_lm_result", Tool.IF_ELSE, "Handle if LM result or regular DM", Map.of(
                            Planner.ToolParam.CONDITION, "$s1_decrypt_dm.result.isLmResult == true",
                            Planner.ToolParam.TRUE_STEPS, List.of(Map.of(Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.FIRE_CORE_EVENT.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.EVENT_TYPE.getKey(), CoreEventType.DISTRIBUTED_LM_RESULT.name(), Planner.ToolParam.EVENT_DATA.getKey(), "$s1_decrypt_dm.result.lmResultPayload"))),
                            Planner.ToolParam.FALSE_STEPS, List.of(Map.of(Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.UPDATE_CHAT_NOTE.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.PARTNER_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey", Planner.ToolParam.SENDER_PUB_KEY_HEX.getKey(), "$s0_get_payload.result.pubkey", Planner.ToolParam.MESSAGE_CONTENT.getKey(), "$s1_decrypt_dm.result.decryptedText", Planner.ToolParam.TIMESTAMP_EPOCH_SECONDS.getKey(), "$s0_get_payload.result.created_at")))
                    ), "s1_decrypt_dm", "s2_update_contact")
                    .step("s4_mark_processed", Tool.MODIFY_NOTE_CONTENT, "Mark system event as processed", Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.CONTENT_UPDATE, Map.of(Note.ContentKey.STATUS.getKey(), "PROCESSED")), "s3_if_lm_result")
                    .bootstrap(this);

            for (var type : new String[]{"nostr", "ui", "llm"}) {
                var eventType = SystemEventType.valueOf("SAVE_" + type.toUpperCase() + "_CONFIG_REQUESTED");
                PlanDefBuilder.create("system_listener_save_" + type + "_config_handler")
                        .title("System Listener: Save " + type.toUpperCase() + " Config")
                        .tags(Note.SystemTag.SYSTEM_PROCESS_HANDLER.value, Note.SystemTag.SYSTEM_NOTE.value)
                        .trigger(eventType, Planner.PlanState.PENDING)
                        .step("s1_get_state", Tool.GET_CONFIG_STATE, Map.of(Planner.ToolParam.CONFIG_TYPE, type))
                        .step("s2_save_to_note", Tool.MODIFY_NOTE_CONTENT, Map.of(Planner.ToolParam.NOTE_ID, Config.CONFIG_NOTE_PREFIX + type, Planner.ToolParam.CONTENT_UPDATE, "$s1_get_state.result"), "s1_get_state")
                        .step("s3_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.CONTENT_UPDATE, Map.of(Note.ContentKey.STATUS.getKey(), "PROCESSED")), "s2_save_to_note")
                        .bootstrap(this);
            }

            var loadAllConfigsBuilder = PlanDefBuilder.create("system_listener_load_all_configs_handler")
                    .title("System Listener: Load All Configurations")
                    .tags(Note.SystemTag.SYSTEM_PROCESS_HANDLER.value, Note.SystemTag.SYSTEM_NOTE.value)
                    .trigger(SystemEventType.LOAD_ALL_CONFIGS_REQUESTED, Planner.PlanState.PENDING);
            List<String> lastApplyStepIds = new ArrayList<>();
            for (var type : List.of("nostr", "ui", "llm")) {
                var getId = "s_load_" + type + "_get_content";
                var applyId = "s_load_" + type + "_apply";
                loadAllConfigsBuilder.step(getId, Tool.GET_NOTE_PROPERTY, Map.of(Planner.ToolParam.NOTE_ID, Config.CONFIG_NOTE_PREFIX + type, Planner.ToolParam.PROPERTY_PATH, Note.NoteProperty.CONTENT.getKey(), Planner.ToolParam.FAIL_IF_NOT_FOUND, false));
                loadAllConfigsBuilder.step(applyId, Tool.APPLY_CONFIG_STATE, Map.of(Planner.ToolParam.CONFIG_TYPE, type, Planner.ToolParam.STATE_MAP, "$" + getId + ".result"), getId);
                lastApplyStepIds.add(applyId);
            }
            loadAllConfigsBuilder.step("s_load_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.CONTENT_UPDATE, Map.of(Note.ContentKey.STATUS.getKey(), "PROCESSED")), lastApplyStepIds.toArray(String[]::new))
                    .bootstrap(this);


            PlanDefBuilder.create("system_listener_persistent_query_handler")
                    .title("System Listener: Persistent Query Handler")
                    .tags(Note.SystemTag.SYSTEM_PROCESS_HANDLER.value, Note.SystemTag.SYSTEM_NOTE.value)
                    .trigger(SystemEventType.EVALUATE_PERSISTENT_QUERIES, Planner.PlanState.PENDING)
                    .step("s1_log_start", Tool.LOG_MESSAGE, Map.of(Planner.ToolParam.MESSAGE, "Evaluating persistent queries..."))
                    .step("s2_find_queries", Tool.FIND_NOTES_BY_TAG, Map.of(Planner.ToolParam.TAG, Note.SystemTag.PERSISTENT_QUERY.value))
                    .step("s3_foreach_query", Tool.FOR_EACH, Map.of(
                            Planner.ToolParam.LIST, "$s2_find_queries.result", Planner.ToolParam.LOOP_VAR, "queryNote",
                            Planner.ToolParam.LOOP_STEPS, List.of(
                                    Map.of(Planner.PlanStepKey.ID.getKey(), "s3_1_get_content", Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.GET_NOTE_PROPERTY.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.NOTE_ID.getKey(), "$queryNote.id", Planner.ToolParam.PROPERTY_PATH.getKey(), Note.NoteProperty.CONTENT.getKey())),
                                    Map.of(Planner.PlanStepKey.ID.getKey(), "s3_2_exec_query", Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.EXECUTE_SEMANTIC_QUERY.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), "$s3_1_get_content.result"),
                                    Map.of(Planner.PlanStepKey.ID.getKey(), "s3_3_update_note", Planner.PlanStepKey.TOOL_NAME.getKey(), Tool.MODIFY_NOTE_CONTENT.name(), Planner.PlanStepKey.TOOL_PARAMS.getKey(), Map.of(Planner.ToolParam.NOTE_ID.getKey(), "$queryNote.id", Planner.ToolParam.CONTENT_UPDATE.getKey(), Map.of(Note.ContentKey.RESULTS.getKey(), "$s3_2_exec_query.result", Note.ContentKey.LAST_RUN.getKey(), "NOW")))
                            )
                    ), "s2_find_queries")
                    .step("s4_reschedule", Tool.SCHEDULE_SYSTEM_EVENT, Map.of(Planner.ToolParam.EVENT_TYPE, SystemEventType.EVALUATE_PERSISTENT_QUERIES.name(), Planner.ToolParam.DELAY_SECONDS, 3600L), "s3_foreach_query")
                    .step("s5_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.CONTENT_UPDATE, Map.of(Note.ContentKey.STATUS.getKey(), "PROCESSED")), "s4_reschedule")
                    .bootstrap(this);

            PlanDefBuilder.create("system_listener_stalled_plan_handler")
                    .title("System Listener: Stalled Plan Handler")
                    .tags(Note.SystemTag.SYSTEM_PROCESS_HANDLER.value, Note.SystemTag.SYSTEM_NOTE.value)
                    .trigger(SystemEventType.STALLED_PLAN_DETECTED, Planner.PlanState.PENDING)
                    .step("s1_get_plan_id", Tool.GET_NOTE_PROPERTY, Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.PROPERTY_PATH, "content.payload.planNoteId"))
                    .step("s2_log_stalled", Tool.LOG_MESSAGE, Map.of(Planner.ToolParam.MESSAGE, "Stalled plan detected: $s1_get_plan_id.result. Consider user notification or automated actions."), "s1_get_plan_id")
                    .step("s4_mark_processed", Tool.MODIFY_NOTE_CONTENT, Map.of(Planner.ToolParam.NOTE_ID, "$trigger.sourceEventNoteId", Planner.ToolParam.CONTENT_UPDATE, Map.of(Note.ContentKey.STATUS.getKey(), "PROCESSED")), "s2_log_stalled")
                    .bootstrap(this);
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
            return notes.getAllNotes().stream()
                    .filter(n -> {
                        if (n.id.equals(sourceNote.id) || n.getEmbeddingV1() == null || n.getEmbeddingV1().length != sourceEmbedding.length)
                            return false;
                        return !n.tags.contains(Note.SystemTag.CONFIG.value);
                    })
                    .map(candidateNote -> new AbstractMap.SimpleEntry<>(candidateNote, LM.cosineSimilarity(sourceEmbedding, candidateNote.getEmbeddingV1())))
                    .filter(entry -> entry.getValue() > minSimilarity)
                    .sorted(Map.Entry.<Note, Double>comparingByValue().reversed())
                    .limit(maxResults)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }

        public enum CoreEventType {
            NOTE_ADDED, NOTE_UPDATED, NOTE_DELETED, PLAN_UPDATED, USER_INTERACTION_REQUESTED, DISTRIBUTED_LM_RESULT,
            CONFIG_CHANGED, STATUS_MESSAGE, CHAT_MESSAGE_ADDED, ACTIONABLE_ITEM_ADDED, ACTIONABLE_ITEM_REMOVED, SYSTEM_EVENT_REQUESTED
        }

        public enum SystemEventType {
            NOSTR_KIND0_RECEIVED, NOSTR_KIND1_RECEIVED, NOSTR_KIND4_RECEIVED, NOSTR_KIND_UNKNOWN_RECEIVED,
            SAVE_NOSTR_CONFIG_REQUESTED, SAVE_UI_CONFIG_REQUESTED, SAVE_LLM_CONFIG_REQUESTED,
            LOAD_ALL_CONFIGS_REQUESTED, EVALUATE_PERSISTENT_QUERIES, STALLED_PLAN_DETECTED, UNKNOWN_EVENT_TYPE
        }

        public enum Tool {
            LOG_MESSAGE, USER_INTERACTION, GET_NOTE_PROPERTY, PARSE_JSON, CREATE_NOTE, MODIFY_NOTE_CONTENT, DELETE_NOTE,
            CREATE_OR_UPDATE_CONTACT_NOTE, IF_ELSE, DECRYPT_NOSTR_DM, UPDATE_CHAT_NOTE, FIRE_CORE_EVENT,
            SUGGEST_PLAN_STEPS, SCHEDULE_SYSTEM_EVENT, FIND_NOTES_BY_TAG, FOR_EACH, EXECUTE_SEMANTIC_QUERY,
            CREATE_LINKS, GET_PLAN_GRAPH_CONTEXT, GET_SYSTEM_HEALTH_METRICS, IDENTIFY_STALLED_PLANS,
            GET_CONFIG_STATE, APPLY_CONFIG_STATE, GET_SELF_NOSTR_INFO,
            DECOMPOSE_GOAL(true), PLAN(true), GET_PLAN_DEPENDENCIES(true), ASSERT_KIF(true), QUERY(true), RETRACT(true),
            API(true), ECHO(true), FILE_OPERATIONS(true), GENERATE_TASK_LOGIC(true), INSPECT(true), EVAL_EXPR(true),
            GENERATE(true), REFLECT(true), REASON(true), DEFINE_CONCEPT(true), EXEC(true), GRAPH_SEARCH(true),
            CODE_WRITING(true), CODE_EXECUTION(true), FIND_ASSERTIONS(true), IDENTIFY_CONCEPTS(true), SUMMARIZE(true), ENHANCE(true);
            private final boolean placeholder;

            Tool() {
                this(false);
            }

            Tool(boolean placeholder) {
                this.placeholder = placeholder;
            }

            public static Tool fromString(String text) {
                return Stream.of(values()).filter(t -> t.name().equalsIgnoreCase(text)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No enum constant Core.Tool." + text));
            }

            public boolean isPlaceholder() {
                return placeholder;
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

            public PlanDefBuilder trigger(SystemEventType eventType, Planner.PlanState status) {
                this.contentProperties.put("triggerEventType", eventType.name());
                this.contentProperties.put("triggerStatus", status.name());
                return this;
            }

            public PlanDefBuilder step(String id, Tool tool, Map<Planner.ToolParam, Object> params, String... dependsOn) {
                Map<String, Object> stepMap = new HashMap<>();
                stepMap.put(Planner.PlanStepKey.ID.getKey(), id);
                stepMap.put(Planner.PlanStepKey.TOOL_NAME.getKey(), tool.name());
                stepMap.put(Planner.PlanStepKey.TOOL_PARAMS.getKey(), params.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().getKey(), Map.Entry::getValue)));
                if (dependsOn.length > 0)
                    stepMap.put(Planner.PlanStepKey.DEPENDS_ON_STEP_IDS.getKey(), List.of(dependsOn));
                this.steps.add(stepMap);
                return this;
            }

            public PlanDefBuilder step(String id, Tool tool, String description, Map<Planner.ToolParam, Object> params, String... dependsOn) {
                Map<String, Object> stepMap = new HashMap<>();
                stepMap.put(Planner.PlanStepKey.ID.getKey(), id);
                stepMap.put(Planner.PlanStepKey.DESCRIPTION.getKey(), description);
                stepMap.put(Planner.PlanStepKey.TOOL_NAME.getKey(), tool.name());
                stepMap.put(Planner.PlanStepKey.TOOL_PARAMS.getKey(), params.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().getKey(), Map.Entry::getValue)));
                if (dependsOn.length > 0)
                    stepMap.put(Planner.PlanStepKey.DEPENDS_ON_STEP_IDS.getKey(), List.of(dependsOn));
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
                handlerNote.content.put(Note.ContentKey.PLAN_STEPS.getKey(), steps);
                core.notes.save(handlerNote, true);
                logger.info("Bootstrapped system note: {}", noteId);
            }
        }

        public record CoreEvent(CoreEventType type, Object data) {
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
                n.content.computeIfAbsent(Note.ContentKey.CONTENT_TYPE.getKey(), k -> ContentType.TEXT_PLAIN.getValue());
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
            n.content.computeIfAbsent(Note.ContentKey.CONTENT_TYPE.getKey(), k -> ContentType.TEXT_PLAIN.getValue());
            cache.put(n.id, n);
            try {
                json.writeValue(dir.resolve(n.id + ".json").toFile(), n);
                if (!internalOperation)
                    logger.info("💾 Saved note {}(v{}). New:{}", n.id, n.version, isNew && n.version == 1);
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
            cfgNote.tags.addAll(List.of(Note.SystemTag.CONFIG.value, typeKey + "_config", Note.SystemTag.SYSTEM_NOTE.value));
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
            Stream.of(Core.SystemEventType.SAVE_NOSTR_CONFIG_REQUESTED, Core.SystemEventType.SAVE_UI_CONFIG_REQUESTED, Core.SystemEventType.SAVE_LLM_CONFIG_REQUESTED)
                    .forEach(eventType -> coreRef.fireCoreEvent(Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(Planner.ToolParam.EVENT_TYPE.getKey(), eventType.name())));
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
                profileNote.tags.addAll(Arrays.asList(Note.SystemTag.CONTACT.value, Note.SystemTag.NOSTR_CONTACT.value, Note.SystemTag.MY_PROFILE.value));
                profileNote.meta.putAll(Map.of(Note.Metadata.NOSTR_PUB_KEY.key, net.publicKeyBech32, Note.Metadata.NOSTR_PUB_KEY_HEX.key, pubKeyHex));
                profileNote.content.putIfAbsent(Note.ContentKey.PROFILE_NAME.getKey(), "Anonymous");
                profileNote.content.putIfAbsent(Note.ContentKey.PROFILE_ABOUT.getKey(), "");
                profileNote.content.putIfAbsent(Note.ContentKey.PROFILE_PICTURE_URL.getKey(), "");
                notes.save(profileNote);

                if (coreRef != null)
                    coreRef.fireCoreEvent(Core.CoreEventType.SYSTEM_EVENT_REQUESTED, Map.of(Planner.ToolParam.EVENT_TYPE.getKey(), Core.SystemEventType.SAVE_NOSTR_CONFIG_REQUESTED.name()));
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
            @Field(label = "Theme", type = FieldType.COMBO_BOX, choices = {"Nimbus (Dark)", "System"}, group = "Appearance")
            public String theme = "Nimbus (Dark)";
            @Field(label = "Minimize to System Tray", tooltip = "If enabled, closing the window minimizes to tray instead of exiting.", group = "Behavior")
            public final boolean minimizeToTray = true;
        }

        public static class LMSettings {
            @Field(label = "Provider", type = FieldType.COMBO_BOX, choices = {"NONE", "OLLAMA"})
            public final String provider = "NONE";
            @Field(label = "Base URL", group = "Ollama")
            public final String ollamaBaseUrl = "http://localhost:11434";
            @Field(label = "Chat Model", group = "Ollama")
            public final String ollamaChatModelName = "llama3";
            @Field(label = "Embedding Model", group = "Ollama")
            public final String ollamaEmbeddingModelName = "nomic-embed-text";
        }
    }

}
