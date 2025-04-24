package dumb.cognote.tools;

import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.UI;
import dumb.cognote.Logic;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;

import static dumb.cognote.Cog.ID_PREFIX_LLM_ITEM;
import static dumb.cognote.Logic.PRED_NOTE_SUMMARY;

public class SummarizeNoteTool implements BaseTool {

    private final Cog cog;

    public SummarizeNoteTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "summarize_note";
    }

    @Override
    public String description() {
        return "Summarizes the content of a specific note using the LLM. Input is a JSON object with 'note_id' (string). Adds the summary as a KIF assertion to the note's KB.";
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> parameters) {
        String noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            return CompletableFuture.completedFuture("Error: Missing required parameter 'note_id'.");
        }

        return cog.ui.findNoteById(noteId)
                .map(note -> {
                    var taskId = Cog.generateId(ID_PREFIX_LLM_ITEM + "summarize_");
                    var interactionType = "Note Summarization";

                    // Add a UI placeholder for the LLM task
                    // This logic is now handled by the tool itself
                    // cog.ui.addLlmUiPlaceholder(note.id, interactionType + ": " + note.title);
                    var vm = UI.AttachmentViewModel.forLlm(
                            taskId,
                            note.id, interactionType + ": Starting...", UI.AttachmentType.LLM_INFO,
                            System.currentTimeMillis(), note.id, UI.LlmStatus.SENDING
                    );
                    cog.events.emit(new Cog.LlmInfoEvent(vm));


                    var promptText = """
                            Summarize the following note in one or two concise sentences. Output ONLY the summary.

                            Note:
                            "%s"

                            Summary:""".formatted(note.text);
                    var history = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
                    history.add(UserMessage.from(promptText));

                    CompletableFuture<dev.langchain4j.data.message.AiMessage> llmFuture = cog.lm.llmAsync(taskId, history, interactionType, note.id);

                    // Handle the result
                    cog.lm.activeLlmTasks.put(taskId, llmFuture);

                    return llmFuture.handleAsync((chatResponse, ex) -> {
                        cog.lm.activeLlmTasks.remove(taskId);
                        if (ex != null) {
                            var cause = (ex instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : ex;
                            if (!(cause instanceof CancellationException)) {
                                System.err.println(interactionType + " failed for note '" + note.id + "': " + cause.getMessage());
                                cog.ui.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, interactionType + " failed: " + cause.getMessage());
                                return "Error summarizing note: " + cause.getMessage();
                            } else {
                                System.out.println(interactionType + " cancelled for note '" + note.id + "'.");
                                cog.ui.updateLlmItemStatus(taskId, UI.LlmStatus.CANCELLED, interactionType + " cancelled.");
                                return "Summarization cancelled.";
                            }
                        } else {
                            System.out.println(interactionType + " completed for note '" + note.id + "'.");
                            cog.ui.updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + " completed.");

                            var summary = chatResponse.text();
                            if (summary != null && !summary.isBlank()) {
                                // Add summary as a KIF assertion to the note's KB
                                var kif = String.format("(%s \"%s\" \"%s\")", PRED_NOTE_SUMMARY, note.id, summary.replace("\"", "\\\""));
                                try {
                                    var terms = Logic.KifParser.parseKif(kif);
                                    if (terms.size() == 1 && terms.getFirst() instanceof Logic.KifList list) {
                                        // Emit as external input, targeting the note's KB
                                        cog.events.emit(new Cog.ExternalInputEvent(list, "llm-summary-tool:" + note.id, note.id));
                                        return "Summary generated and added as assertion.";
                                    } else {
                                        System.err.println("Generated summary KIF was not a single list: " + kif);
                                        return "Summary generated, but failed to parse/add KIF.";
                                    }
                                } catch (Logic.KifParser.ParseException e) {
                                    System.err.println("Error parsing generated summary KIF: " + e.getMessage() + " | KIF: " + kif);
                                    return "Summary generated, but failed to parse/add KIF: " + e.getMessage();
                                }
                            } else {
                                System.out.println("LLM summarization for note " + note.id + " returned empty content.");
                                return "LLM returned empty summary.";
                            }
                        }
                    }, cog.events.exe); // Handle completion on the event executor
                })
                .orElse(CompletableFuture.completedFuture("Error: Note with ID '" + noteId + "' not found."));
    }
}
