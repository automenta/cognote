package dumb.cognote;

public class Protocol {

    public static final String SIGNAL_TYPE_INPUT = "input";
    public static final String SIGNAL_TYPE_COMMAND = "command";
    public static final String SIGNAL_TYPE_EVENT = "event";
    public static final String SIGNAL_TYPE_RESPONSE = "response";
    public static final String SIGNAL_TYPE_INITIAL_STATE_REQUEST = "initial_state_request";
    public static final String SIGNAL_TYPE_INITIAL_STATE = "initial_state";
    public static final String SIGNAL_TYPE_UI_ACTION = "ui_action";
    public static final String SIGNAL_TYPE_DIALOGUE_REQUEST = "dialogue_request";
    public static final String SIGNAL_TYPE_DIALOGUE_RESPONSE = "dialogue_response";
    public static final String SIGNAL_TYPE_INTERACTION_FEEDBACK = "interaction_feedback";

    public static final String RESPONSE_STATUS_SUCCESS = "success";
    public static final String RESPONSE_STATUS_FAILURE = "failure";
    public static final String RESPONSE_STATUS_ERROR = "error";

    public static final String KB_UI_ACTIONS = "kb://ui-actions";
    public static final String KB_USER_FEEDBACK = "kb://user-feedback";
    public static final String KB_MINDMAP = "kb://mindmap";
    public static final String KB_CLIENT_INPUT = "kb://client-input";
    public static final String KB_LLM_TASKS = "kb://llm-tasks";
    public static final String KB_DIALOGUE_STATE = "kb://dialogue-state";

    public static final String PRED_REQUEST = "request";
    public static final String PRED_UI_ACTION = "uiAction";
    public static final String PRED_USER_FEEDBACK = "userFeedback";

    public static final String UI_ACTION_HIGHLIGHT_TEXT = "highlight_text";
    public static final String UI_ACTION_DISPLAY_MESSAGE = "display_message";
    public static final String UI_ACTION_UPDATE_NOTE_LIST = "update_note_list";
    public static final String UI_ACTION_UPDATE_ATTACHMENT_LIST = "update_attachment_list";

    public static final String DIALOGUE_TYPE_TEXT_INPUT = "text_input";
    public static final String DIALOGUE_TYPE_CHOICE = "choice";

    public static final String DIALOGUE_RESPONSE_KEY_TEXT = "text";
    public static final String DIALOGUE_RESPONSE_KEY_CHOICE = "choice";

    public static final String OP_ASK_USER = "ask-user";
}
