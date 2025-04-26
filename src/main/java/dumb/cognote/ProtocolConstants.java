package dumb.cognote;

public class ProtocolConstants {

    // Signal Types
    public static final String SIGNAL_TYPE_COMMAND = "command";
    public static final String SIGNAL_TYPE_EVENT = "event";
    public static final String SIGNAL_TYPE_RESPONSE = "response";
    public static final String SIGNAL_TYPE_INITIAL_STATE = "initial_state";
    public static final String SIGNAL_TYPE_UI_ACTION = "ui_action";
    public static final String SIGNAL_TYPE_DIALOGUE_REQUEST = "dialogue_request";
    public static final String SIGNAL_TYPE_DIALOGUE_RESPONSE = "dialogue_response";
    public static final String SIGNAL_TYPE_INTERACTION_FEEDBACK = "interaction_feedback";

    // Response Status
    public static final String RESPONSE_STATUS_SUCCESS = "success";
    public static final String RESPONSE_STATUS_FAILURE = "failure";
    public static final String RESPONSE_STATUS_ERROR = "error";

    // Command Types
    public static final String COMMAND_ADD_NOTE = "add_note";
    public static final String COMMAND_REMOVE_NOTE = "remove_note";
    public static final String COMMAND_START_NOTE = "start_note";
    public static final String COMMAND_PAUSE_NOTE = "pause_note";
    public static final String COMMAND_COMPLETE_NOTE = "complete_note";
    public static final String COMMAND_RUN_TOOL = "run_tool";
    public static final String COMMAND_RUN_QUERY = "run_query";
    public static final String COMMAND_CLEAR_ALL = "clear_all";
    public static final String COMMAND_SET_CONFIG = "set_config";
    public static final String COMMAND_GET_INITIAL_STATE = "get_initial_state";
    public static final String COMMAND_SAVE_NOTES = "save_notes";

    // Interaction Feedback Types
    public static final String FEEDBACK_USER_ASSERTED_KIF = "user_asserted_kif";
    public static final String FEEDBACK_USER_EDITED_NOTE_TEXT = "user_edited_note_text";
    public static final String FEEDBACK_USER_EDITED_NOTE_TITLE = "user_edited_note_title";
    public static final String FEEDBACK_USER_CLICKED = "user_clicked";

    // UI Action Types
    public static final String UI_ACTION_HIGHLIGHT_TEXT = "highlight_text";
    public static final String UI_ACTION_DISPLAY_MESSAGE = "display_message";
    public static final String UI_ACTION_UPDATE_NOTE_LIST = "update_note_list";
    public static final String UI_ACTION_UPDATE_ATTACHMENT_LIST = "update_attachment_list";

    // KB IDs for Protocol Interaction
    public static final String KB_UI_ACTIONS = "kb://ui-actions";
    public static final String KB_USER_FEEDBACK = "kb://user-feedback";

    // Predicates for UI Actions
    public static final String PRED_UI_ACTION = "uiAction";
    public static final String PRED_HIGHLIGHT_TEXT = "highlightText";
    public static final String PRED_DISPLAY_MESSAGE = "displayMessage";

    // Predicates for User Feedback
    public static final String PRED_USER_FEEDBACK = "userFeedback";
    public static final String PRED_USER_ASSERTED_KIF = "userAssertedKif";
    public static final String PRED_USER_EDITED_NOTE_TEXT = "userEditedNoteText";
    public static final String PRED_USER_EDITED_NOTE_TITLE = "userEditedNoteTitle";
    public static final String PRED_USER_CLICKED = "userClicked";

    // Dialogue Request Types
    public static final String DIALOGUE_TYPE_TEXT_INPUT = "text_input";
    public static final String DIALOGUE_TYPE_CHOICE = "choice";

    // Dialogue Response Data Keys
    public static final String DIALOGUE_RESPONSE_KEY_TEXT = "text";
    public static final String DIALOGUE_RESPONSE_KEY_CHOICE = "choice";
}
