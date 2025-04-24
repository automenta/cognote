package dumb.cognote.tools;

import dev.langchain4j.data.message.UserMessage;
import dumb.cognote.Cog;
import dumb.cognote.Logic;
import dumb.cognote.UI;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

import static dumb.cognote.Cog.ID_PREFIX_LLM_ITEM;
import static dumb.cognote.Logic.PRED_NOTE_QUESTION;

public class GenerateQuestionsTool implements BaseTool {

    private final Cog cog;

    public GenerateQuestionsTool(Cog cog) {
        this.cog = cog;
    }

    @Override
    public String name() {
        return "generate_questions";
    }

    @Override
    public String description() {
        return "Generates insightful questions based on a note using the LLM. Input is a JSON object with 'note_id' (string). Adds questions as KIF assertions to the note's KB.";
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> parameters) {
        var noteId = (String) parameters.get("note_id");

        if (noteId == null || noteId.isBlank()) {
            return CompletableFuture.completedFuture("Error: Missing required parameter 'note_id'.");
        }

        return cog.ui.findNoteById(noteId)
                .map(note -> {
                    var taskId = Cog.generateId(ID_PREFIX_LLM_ITEM + "questions_");
                    var interactionType = "Question Generation";

                    // Add a UI placeholder for the LLM task
                    // cog.ui.addLlmUiPlaceholder(note.id, interactionType + ": " + note.title);
                    var vm = UI.AttachmentViewModel.forLlm(
                            taskId,
                            note.id, interactionType + ": Starting...", UI.AttachmentType.LLM_INFO,
                            System.currentTimeMillis(), note.id, UI.LlmStatus.SENDING
                    );
                    cog.events.emit(new Cog.LlmInfoEvent(vm));


                    var promptText = """
                            Based on the following note, generate 1-3 insightful questions that could lead to further exploration or clarification. Output ONLY the questions, each on a new line starting with '- '.
                            
                            Note:
                            "%s"
                            
                            Questions:""".formatted(note.text);
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
                                cog.updateLlmItemStatus(taskId, UI.LlmStatus.ERROR, interactionType + " failed: " + cause.getMessage());
                                return "Error generating questions: " + cause.getMessage();
                            } else {
                                System.out.println(interactionType + " cancelled for note '" + note.id + "'.");
                                cog.updateLlmItemStatus(taskId, UI.LlmStatus.CANCELLED, interactionType + " cancelled.");
                                return "Question generation cancelled.";
                            }
                        } else {
                            System.out.println(interactionType + " completed for note '" + note.id + "'.");
                            cog.updateLlmItemStatus(taskId, UI.LlmStatus.DONE, interactionType + " completed.");

                            var questionsText = chatResponse.text();
                            if (questionsText != null && !questionsText.isBlank()) {
                                // Add each question as a KIF assertion to the note's KB
                                questionsText.lines()
                                        .map(String::trim)
                                        .filter(Predicate.not(String::isEmpty))
                                        .filter(q -> q.startsWith("- ")) // Filter for expected format
                                        .map(q -> q.substring(2).trim()) // Remove "- " prefix
                                        .filter(Predicate.not(String::isEmpty))
                                        .forEach(question -> {
                                            var kif = String.format("(%s \"%s\" \"%s\")", PRED_NOTE_QUESTION, note.id, question.replace("\"", "\\\""));
                                            try {
                                                var terms = Logic.KifParser.parseKif(kif);
                                                if (terms.size() == 1 && terms.getFirst() instanceof Logic.KifList list) {
                                                    cog.events.emit(new Cog.ExternalInputEvent(list, "llm-question-tool:" + note.id, note.id));
                                                } else {
                                                    System.err.println("Generated question KIF was not a single list: " + kif);
                                                }
                                            } catch (Logic.KifParser.ParseException e) {
                                                System.err.println("Error parsing generated question KIF: " + e.getMessage() + " | KIF: " + kif);
                                            }
                                        });
                                return "Questions generated and added as assertions.";
                            } else {
                                System.out.println("LLM question generation for note " + note.id + " returned empty content.");
                                return "LLM returned empty question list.";
                            }
                        }
                    }, cog.events.exe);
                })
                .orElse(CompletableFuture.completedFuture("Error: Note with ID '" + noteId + "' not found."));
    }
}
