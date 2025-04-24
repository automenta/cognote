package dumb.cognote.tool;

import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.*;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

import static dumb.cognote.Cog.ID_PREFIX_LLM_ITEM;
import static dumb.cognote.Logic.PRED_NOTE_CONCEPT;

public class IdentifyConceptsTool implements Tool {

    private final Cog cog;

    public IdentifyConceptsTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "identify_concepts";
    }

    @Override
    public String description() {
        return "Identifies key concepts in a note using the LLM. Input is a JSON object with 'note_id' (string). Adds concepts as KIF assertions to the note's KB.";
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            return CompletableFuture.completedFuture("Error: Missing required parameter 'note_id'.");
        }

        return cog.note(noteId).map(note -> {
                    var taskId = Cog.id(ID_PREFIX_LLM_ITEM + "concepts_");
                    var interactionType = "Key Concept Identification";

                    // Add a UI placeholder for the LLM task
                    // cog.ui.addLlmUiPlaceholder(note.id, interactionType + ": " + note.title);
                    cog.events.emit(new Cog.LlmInfoEvent(UI.AttachmentViewModel.forLlm(
                            taskId,
                            note.id, interactionType + ": Starting...", UI.AttachmentType.LLM_INFO,
                            System.currentTimeMillis(), note.id, Cog.TaskStatus.SENDING
                    )));

                    var promptText = """
                            Identify the key concepts or entities mentioned in the following note. List them separated by newlines. Output ONLY the newline-separated list.
                            
                            Note:
                            "%s"
                            
                            Key Concepts:""".formatted(note.text);
                    var history = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
                    history.add(UserMessage.from(promptText));

                    var llmFuture = cog.lm.llmAsync(taskId, history, interactionType, note.id);

                    cog.lm.activeLlmTasks.put(taskId, llmFuture);

                    return llmFuture.handleAsync((chatResponse, ex) -> {
                        cog.lm.activeLlmTasks.remove(taskId);
                        if (ex != null) {
                            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                            if (!(cause instanceof CancellationException)) {
                                System.err.println(interactionType + " failed for note '" + note.id + "': " + cause.getMessage());
                                cog.updateTaskStatus(taskId, Cog.TaskStatus.ERROR, interactionType + " failed: " + cause.getMessage());
                                return "Error identifying concepts: " + cause.getMessage();
                            } else {
                                System.out.println(interactionType + " cancelled for note '" + note.id + "'.");
                                cog.updateTaskStatus(taskId, Cog.TaskStatus.CANCELLED, interactionType + " cancelled.");
                                return "Concept identification cancelled.";
                            }
                        } else {
                            System.out.println(interactionType + " completed for note '" + note.id + "'.");
                            cog.updateTaskStatus(taskId, Cog.TaskStatus.DONE, interactionType + " completed.");

                            var conceptsText = chatResponse.text();
                            if (conceptsText != null && !conceptsText.isBlank()) {
                                // Add each concept as a KIF assertion to the note's KB
                                conceptsText.lines()
                                        .map(String::trim)
                                        .filter(Predicate.not(String::isEmpty))
                                        .forEach(concept -> {
                                            var kif = String.format("(%s \"%s\" \"%s\")", PRED_NOTE_CONCEPT, note.id, concept.replace("\"", "\\\""));
                                            try {
                                                var terms = Logic.KifParser.parseKif(kif);
                                                if (terms.size() == 1 && terms.getFirst() instanceof Term.Lst list) {
                                                    cog.events.emit(new Cog.ExternalInputEvent(list, "llm-concept-tool:" + note.id, note.id));
                                                } else {
                                                    System.err.println("Generated concept KIF was not a single list: " + kif);
                                                }
                                            } catch (Logic.KifParser.ParseException e) {
                                                System.err.println("Error parsing generated concept KIF: " + e.getMessage() + " | KIF: " + kif);
                                            }
                                        });
                                return "Concepts identified and added as assertions.";
                            } else {
                                System.out.println("LLM concept identification for note " + note.id + " returned empty content.");
                                return "LLM returned empty concept list.";
                            }
                        }
                    }, cog.events.exe);
                })
                .orElse(CompletableFuture.completedFuture("Error: Note with ID '" + noteId + "' not found."));
    }
}
