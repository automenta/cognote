package dumb.cognote;

public class Protocol {

    // Unified Signal Types
    public static final String SIGNAL_TYPE_REQUEST = "request"; // Client -> Server
    public static final String SIGNAL_TYPE_UPDATE = "update";   // Server -> Client

    // Update Types (used within SIGNAL_TYPE_UPDATE payload)
    public static final String UPDATE_TYPE_RESPONSE = "response"; // Response to a specific request
    public static final String UPDATE_TYPE_EVENT = "event";       // Backend event notification
    public static final String UPDATE_TYPE_INITIAL_STATE = "initialState"; // Initial system snapshot
    public static final String UPDATE_TYPE_DIALOGUE_REQUEST = "dialogueRequest"; // Request for user input

    // Request Commands (used within SIGNAL_TYPE_REQUEST payload)
    public static final String COMMAND_ASSERT_KIF = "assertKif";
    public static final String COMMAND_RUN_TOOL = "runTool";
    public static final String COMMAND_RUN_QUERY = "query";
    public static final String COMMAND_WAIT = "wait";
    public static final String COMMAND_RETRACT = "retract";
    public static final String COMMAND_CANCEL_DIALOGUE = "cancelDialogue";
    public static final String COMMAND_GET_INITIAL_STATE = "initialStateRequest";
    public static final String COMMAND_ADD_NOTE = "addNote";
    public static final String COMMAND_UPDATE_NOTE = "updateNote"; // Unified update command
    public static final String COMMAND_DELETE_NOTE = "deleteNote";
    public static final String COMMAND_CLONE_NOTE = "cloneNote";
    public static final String COMMAND_CLEAR_ALL = "clearAll";
    public static final String COMMAND_UPDATE_SETTINGS = "updateSettings";
    public static final String COMMAND_DIALOGUE_RESPONSE = "dialogueResponse"; // Client sending response


    // Response Statuses (used within UPDATE_TYPE_RESPONSE payload)
    public static final String RESPONSE_STATUS_SUCCESS = "success";
    public static final String RESPONSE_STATUS_FAILURE = "failure";
    public static final String RESPONSE_STATUS_ERROR = "error";

    // Internal KB Identifiers (remain as they are part of the knowledge model)
    public static final String KB_UI_ACTIONS = "kb://ui-actions"; // Still used for backend-initiated UI actions
    public static final String KB_USER_FEEDBACK = "kb://user-feedback";
    public static final String KB_MINDMAP = "kb://mindmap";
    public static final String KB_CLIENT_INPUT = "kb://client-input"; // Deprecated for direct requests
    public static final String KB_LLM_TASKS = "kb://llm-tasks";
    public static final String KB_DIALOGUE_STATE = "kb://dialogue-state";

    // Internal Predicates (remain as they are part of the knowledge model)
    public static final String PRED_REQUEST = "request"; // Deprecated for direct requests
    public static final String PRED_UI_ACTION = "uiAction"; // Still used for backend-initiated UI actions
    public static final String PRED_USER_FEEDBACK = "userFeedback";

    // UI Action Types (remain as they are used in backend-initiated UI actions)
    public static final String UI_ACTION_HIGHLIGHT_TEXT = "highlight_text";
    public static final String UI_ACTION_DISPLAY_MESSAGE = "display_message";
    public static final String UI_ACTION_UPDATE_NOTE_LIST = "update_note_list"; // May become obsolete with state sync
    public static final String UI_ACTION_UPDATE_ATTACHMENT_LIST = "update_attachment_list"; // May become obsolete with state sync

    // Dialogue Types (remain as they are part of the dialogue model)
    public static final String DIALOGUE_TYPE_TEXT_INPUT = "text_input";
    public static final String DIALOGUE_TYPE_CHOICE = "choice";

    // Dialogue Response Keys (remain as they are part of the dialogue model)
    public static final String DIALOGUE_RESPONSE_KEY_TEXT = "text";
    public static final String DIALOGUE_RESPONSE_KEY_CHOICE = "choice";

    // KIF Operators (remain as they are part of the logic model)
    public static final String OP_ASK_USER = "ask-user";
}
