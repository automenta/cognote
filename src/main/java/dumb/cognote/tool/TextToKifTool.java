package dumb.cognote.tool;

import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.Tool;
import dumb.cognote.UI;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static dumb.cognote.Cog.ID_PREFIX_LLM_ITEM;

public class TextToKifTool implements Tool {

    private final Cog cog;

    public TextToKifTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "text_to_kif";
    }

    @Override
    public String description() {
        return "Converts the text content of a note into KIF assertions using the LLM. Input is a JSON object with 'note_id' (string). The LLM should use the 'assert_kif' tool to add the generated KIF.";
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            return CompletableFuture.completedFuture("Error: Missing required parameter 'note_id'.");
        }

        return cog.ui.findNoteById(noteId)
                .map(note -> {
                    var taskId = Cog.id(ID_PREFIX_LLM_ITEM + "text2kif_");
                    var interactionType = "KIF Generation";

                    // Add a UI placeholder for the LLM task
                    // cog.ui.addLlmUiPlaceholder(note.id, interactionType + ": " + note.title);
                    var vm = UI.AttachmentViewModel.forLlm(
                            taskId,
                            note.id, interactionType + ": Starting...", UI.AttachmentType.LLM_INFO,
                            System.currentTimeMillis(), note.id, Cog.TaskStatus.SENDING
                    );
                    cog.events.emit(new Cog.LlmInfoEvent(vm));


                    var promptText = """
                            Convert the following note into a set of concise SUMO KIF assertions (standard Lisp-like syntax, e.g., (instance MyCat Cat)).
                            
                             * standard SUMO predicates like 'instance', 'subclass', 'domain', 'range', 'attribute', 'partOf', etc.
                             * '=' for equality between terms. Use unique names for new entities derived from the note (start with uppercase, use CamelCase).
                             * '(not ...)' for negation where appropriate, e.g., (not (instance Pluto Planet)).
                             * '(forall (?X) (=> (instance ?X Dog) (attribute ?X Canine)))' for universal statements.
                             * '(exists (?Y) (and (instance ?Y Cat) (attribute ?Y BlackColor)))' for existential statements.
                             * Avoid trivial assertions like (instance X X) or (= X X) or (not (= X X)).
                            
                            Note:
                            ```
                            "%s"
                            ```
                            
                            For each assertion you generate, use the `assert_kif` tool with the note ID "%s".
                            Do NOT output the KIF assertions directly in your response. Only use the tool.
                            Generate the KIF Assertions by adding them using the tool:""".formatted(note.text, note.id); // Pass note.id as target_kb_id hint

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
                                return "Error generating KIF: " + cause.getMessage();
                            } else {
                                System.out.println(interactionType + " cancelled for note '" + note.id + "'.");
                                cog.updateTaskStatus(taskId, Cog.TaskStatus.CANCELLED, interactionType + " cancelled.");
                                return "KIF generation cancelled.";
                            }
                        } else {
                            System.out.println(interactionType + " completed for note '" + note.id + "'. KIF assertions should have been added by tool calls.");
                            cog.updateTaskStatus(taskId, Cog.TaskStatus.DONE, interactionType + " completed.");
                            // The LLM should have used the tool. We can optionally log the final text response.
                            var text = chatResponse.text();
                            if (text != null && !text.isBlank()) {
                                System.out.println("LLM final message for KIF generation: " + text);
                            }
                            return "KIF generation completed. Check attachments for added assertions.";
                        }
                    }, cog.events.exe);
                })
                .orElse(CompletableFuture.completedFuture("Error: Note with ID '" + noteId + "' not found."));
    }
}
