package dumb.cognote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.swing.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

public class LM {
    private final Cog cog;
    static final String DEFAULT_LLM_URL = "http://localhost:11434/api/chat";
    // --- End Configuration Defaults ---
    static final String DEFAULT_LLM_MODEL = "llama3";
    static final double LLM_ASSERTION_BASE_PRIORITY = 15.0;
    final Map<String, CompletableFuture<?>> activeLlmTasks = new ConcurrentHashMap<String, CompletableFuture<?>>();
    volatile String llmApiUrl;
    volatile String llmModel;

    public LM(Cog cog) {
        this.cog = cog;
    }

    static Optional<String> extractLlmContent(JSONObject r) {
        return Stream.<Supplier<Optional<String>>>of(
                        () -> ofNullable(r.optJSONObject("message")).map(m -> m.optString("content", null)),
                        () -> ofNullable(r.optString("response", null)),
                        () -> ofNullable(r.optJSONArray("choices")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(c -> c.optJSONObject("message")).map(m -> m.optString("content", null)),
                        () -> ofNullable(r.optJSONArray("results")).filter(Predicate.not(JSONArray::isEmpty)).map(a -> a.optJSONObject(0)).map(res -> res.optJSONObject("candidates")).map(cand -> cand.optJSONObject("content")).map(cont -> cont.optJSONArray("parts")).filter(Predicate.not(JSONArray::isEmpty)).map(p -> p.optJSONObject(0)).map(p -> p.optString("text", null)),
                        () -> findNestedContent(r) // Fallback generic search
                ).map(Supplier::get)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<String> findNestedContent(Object jsonValue) {
        return switch (jsonValue) {
            case JSONObject obj -> obj.keySet().stream()
                    .filter(key -> key.toLowerCase().contains("content") || key.toLowerCase().contains("text") || key.toLowerCase().contains("response"))
                    .map(obj::opt)
                    .flatMap(val -> (val instanceof String s && !s.isBlank()) ? Stream.of(s) : Stream.empty())
                    .findFirst()
                    .or(() -> obj.keySet().stream().map(obj::opt).map(LM::findNestedContent).flatMap(Optional::stream).findFirst());
            case JSONArray arr -> IntStream.range(0, arr.length()).mapToObj(arr::opt)
                    .map(LM::findNestedContent)
                    .flatMap(Optional::stream)
                    .findFirst();
            case String s -> Optional.of(s).filter(Predicate.not(String::isBlank));
            default -> Optional.empty();
        };
    }

    CompletableFuture<String> llmAsync(String taskId, String prompt, String interactionType, String noteId) {
        return CompletableFuture.supplyAsync(() -> {
            cog.waitIfPaused();
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.PROCESSING, interactionType + ": Waiting for LLM...");
            var payload = new JSONObject()
                    .put("model", llmModel)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                    .put("stream", false)
                    .put("options", new JSONObject().put("temperature", 0.2));
            var request = HttpRequest.newBuilder(URI.create(llmApiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(Cog.HTTP_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            try {
                var response = cog.http.send(request, HttpResponse.BodyHandlers.ofString());
                var responseBody = response.body();
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new IOException("LLM API request failed (" + interactionType + "): " + response.statusCode() + " Body: " + responseBody);
                return extractLlmContent(new JSONObject(new JSONTokener(responseBody)))
                        .orElseThrow(() -> new IOException("LLM response missing expected content field. Body: " + responseBody));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException("LLM API communication error (" + interactionType + ")", e);
            } catch (Exception e) {
                throw new CompletionException("LLM response processing error (" + interactionType + ")", e);
            }
        }, cog.events.exe);
    }

    void handleLlmResponse(String taskId, String noteId, String interactionType, String kifPredicate, String response, Throwable ex, BiConsumer<String, String> successHandler) {
        activeLlmTasks.remove(taskId);
        if (ex instanceof CancellationException) {
            cog.updateLlmItemStatus(taskId, UI.LlmStatus.CANCELLED, interactionType + " Cancelled.");
            return;
        }

        if (ex == null && response != null && !response.isBlank()) {
            successHandler.accept(taskId, response);
        } else {
            var errorMsg = (ex != null) ? interactionType + " Error: " + ex.getMessage() : interactionType + " Warning: Empty response.";
            System.err.printf("LLM %s (%s, Note %s): %s%n", (ex != null ? "Error" : "Warning"), interactionType, noteId, errorMsg);
            cog.updateLlmItemStatus(taskId, (ex != null ? UI.LlmStatus.ERROR : UI.LlmStatus.DONE), errorMsg);
        }
    }

    void handleLlmKifResponse(String taskId, String noteId, String kifResult, Throwable ex) {
        handleLlmResponse(taskId, noteId, "KIF Generation", null, kifResult, ex, (id, result) -> {
            var cleanedKif = result.lines()
                    .map(s -> s.replaceAll("(?i)```kif", "").replaceAll("```", "").trim())
                    .filter(line -> line.startsWith("(") && line.endsWith(")") && !line.matches("^\\(\\s*\\)$"))
                    .collect(Collectors.joining("\n"));

            if (!cleanedKif.trim().isEmpty()) {
                System.out.printf("LLM Success (KIF %s): Extracted KIF assertions.%n", noteId);
                try {
                    Logic.KifParser.parseKif(cleanedKif).forEach(term -> cog.events.emit(new Cog.ExternalInputEvent(term, "llm-kif:" + noteId, noteId)));
                    cog.updateLlmItemStatus(id, UI.LlmStatus.DONE, "KIF Generation Complete. Assertions added to KB.");
                } catch (Logic.ParseException parseEx) {
                    System.err.printf("LLM Error (KIF %s): Failed to parse generated KIF: %s%n", noteId, parseEx.getMessage());
                    cog.updateLlmItemStatus(id, UI.LlmStatus.ERROR, "KIF Parse Error: " + parseEx.getMessage());
                }
            } else {
                System.err.printf("LLM Warning (KIF %s): Result contained text but no valid KIF lines found after cleaning.%n", noteId);
                cog.updateLlmItemStatus(id, UI.LlmStatus.DONE, "KIF Generation Warning: No valid KIF found in response.");
            }
        });
    }

    void handleLlmGenericResponse(String taskId, String noteId, String interactionType, String response, Throwable ex, String kifPredicate) {
        handleLlmResponse(taskId, noteId, interactionType, kifPredicate, response, ex, (id, result) -> {
            result.lines()
                    .map(String::trim)
                    .filter(Predicate.not(String::isBlank))
                    .forEach(lineContent -> {
                        var resultId = Cog.generateId(Cog.ID_PREFIX_LLM_RESULT);
                        var kifTerm = new Logic.KifList(Logic.KifAtom.of(kifPredicate), Logic.KifAtom.of(noteId), Logic.KifAtom.of(resultId), Logic.KifAtom.of(lineContent));
                        cog.events.emit(new Cog.ExternalInputEvent(kifTerm, "llm-" + kifPredicate + ":" + noteId, noteId));
                    });
            cog.updateLlmItemStatus(id, UI.LlmStatus.DONE, interactionType + " Complete. Result added to KB.");
        });
    }

    void handleLlmEnhancementResponse(String taskId, String noteId, String response, Throwable ex) {
        handleLlmResponse(taskId, noteId, "Note Enhancement", null, response, ex, (id, result) -> {
            cog.ui.findNoteById(noteId).ifPresent(note -> {
                note.text = result.trim();
                SwingUtilities.invokeLater(() -> {
                    if (note.equals(cog.ui.currentNote)) {
                        cog.ui.noteEditor.setText(note.text);
                        cog.ui.noteEditor.setCaretPosition(0);
                    }
                });
                cog.saveNotesToFile();
                cog.updateLlmItemStatus(id, UI.LlmStatus.DONE, "Note Enhanced and Updated.");
            });
        });
    }

    public CompletableFuture<String> enhanceNoteWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                You are a helpful assistant. Please revise and enhance the following note for clarity, conciseness, and improved structure. Keep the core meaning intact.
                Focus on improving readability and flow. Correct any grammatical errors or awkward phrasing.
                Output ONLY the revised note text, without any introductory or concluding remarks.
                
                Original Note:
                "%s"
                
                Enhanced Note:""".formatted(n.text);
        var future = llmAsync(taskId, finalPrompt, "Note Enhancement", n.id);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmEnhancementResponse(taskId, n.id, response, ex), cog.events.exe);
        return future;
    }

    public CompletableFuture<String> summarizeNoteWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Summarize the following note in one or two concise sentences. Output ONLY the summary.
                
                Note:
                "%s"
                
                Summary:""".formatted(n.text);
        var future = llmAsync(taskId, finalPrompt, "Note Summarization", n.id);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, n.id, "Summary", response, ex, Logic.PRED_NOTE_SUMMARY), cog.events.exe);
        return future;
    }

    public CompletableFuture<String> keyConceptsWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Identify the key concepts or entities mentioned in the following note. List them separated by newlines. Output ONLY the newline-separated list.
                
                Note:
                "%s"
                
                Key Concepts:""".formatted(n.text);
        var future = llmAsync(taskId, finalPrompt, "Key Concept Identification", n.id);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, n.id, "Concepts", response, ex, Logic.PRED_NOTE_CONCEPT), cog.events.exe);
        return future;
    }

    public CompletableFuture<String> generateQuestionsWithLlmAsync(String taskId, Cog.Note n) {
        var finalPrompt = """
                Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.
                
                Note:
                "%s"
                
                Questions:""".formatted(n.text);
        var future = llmAsync(taskId, finalPrompt, "Question Generation", n.id);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((response, ex) -> handleLlmGenericResponse(taskId, n.id, "Question Gen", response, ex, Logic.PRED_NOTE_QUESTION), cog.events.exe);
        return future;
    }
    public CompletableFuture<String> text2kifAsync(String taskId, String noteText, String noteId) {
        var finalPrompt = """
                Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax, e.g., (instance MyCat Cat)).
                Output ONLY the KIF assertions, each on a new line, enclosed in parentheses.
                Use standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', 'attribute', 'partOf', etc.
                Use '=' for equality between terms. Use unique names for new entities derived from the note (start with uppercase, use CamelCase).
                Use '(not ...)' for negation where appropriate, e.g., (not (instance Pluto Planet)).
                Use '(forall (?X) (=> (instance ?X Dog) (attribute ?X Canine)))' for universal statements.
                Use '(exists (?Y) (and (instance ?Y Cat) (attribute ?Y BlackColor)))' for existential statements.
                Avoid trivial assertions like (instance X X) or (= X X) or (not (= X X)).
                Example: (instance Fluffy Cat) (attribute Fluffy OrangeColor) (= (age Fluffy) 3) (not (attribute Fluffy BlackColor)) (exists (?K) (instance ?K Kitten))
                
                Note:
                "%s"
                
                KIF Assertions:""".formatted(noteText);
        var future = llmAsync(taskId, finalPrompt, "KIF Generation", noteId);
        activeLlmTasks.put(taskId, future);
        future.whenCompleteAsync((kifResult, ex) -> handleLlmKifResponse(taskId, noteId, kifResult, ex), cog.events.exe);
        return future;
    }

}