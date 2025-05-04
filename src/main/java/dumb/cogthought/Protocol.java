package dumb.cogthought;

// This class will be refactored into the ApiGateway
public class Protocol {

    public static final String SIGNAL_TYPE_REQUEST = "request";
    public static final String SIGNAL_TYPE_UPDATE = "update";

    public static final String UPDATE_TYPE_RESPONSE = "response";
    public static final String UPDATE_TYPE_EVENT = "event";
    public static final String UPDATE_TYPE_INITIAL_STATE = "initialState";
    public static final String UPDATE_TYPE_DIALOGUE_REQUEST = "dialogueRequest";

    public static final String COMMAND_ASSERT_KIF = "assertKif";
    public static final String COMMAND_RUN_TOOL = "runTool";
    public static final String COMMAND_RUN_QUERY = "query";
    public static final String COMMAND_WAIT = "wait";
    public static final String COMMAND_RETRACT = "retract";
    public static final String COMMAND_CANCEL_DIALOGUE = "cancelDialogue";
    public static final String COMMAND_GET_INITIAL_STATE = "initialStateRequest";
    public static final String COMMAND_ADD_NOTE = "addNote";
    public static final String COMMAND_UPDATE_NOTE = "updateNote";
    public static final String COMMAND_DELETE_NOTE = "deleteNote";
    public static final String COMMAND_CLONE_NOTE = "cloneNote";
    public static final String COMMAND_CLEAR_ALL = "clearAll";
    public static final String COMMAND_UPDATE_SETTINGS = "updateSettings";
    public static final String COMMAND_DIALOGUE_RESPONSE = "dialogueResponse";

    public static final String RESPONSE_STATUS_SUCCESS = "success";
    public static final String RESPONSE_STATUS_FAILURE = "failure";
    public static final String RESPONSE_STATUS_ERROR = "error";

    public static final String KB_UI_ACTIONS = "kb://ui-actions"; // These will likely become Ontology terms
    public static final String KB_USER_FEEDBACK = "kb://user-feedback";
    public static final String KB_MINDMAP = "kb://mindmap";
    public static final String KB_LLM_TASKS = "kb://llm-tasks";
    public static final String KB_DIALOGUE_STATE = "kb://dialogue-state";

    public static final String PRED_UI_ACTION = "uiAction"; // These will likely become Ontology terms
    public static final String PRED_USER_FEEDBACK = "userFeedback";

    public static final String UI_ACTION_HIGHLIGHT_TEXT = "highlight_text"; // These will likely become Ontology terms
    public static final String UI_ACTION_DISPLAY_MESSAGE = "display_message";
    public static final String UI_ACTION_UPDATE_NOTE_LIST = "update_note_list";
    public static final String UI_ACTION_UPDATE_ATTACHMENT_LIST = "update_attachment_list";

    public static final String DIALOGUE_TYPE_TEXT_INPUT = "text_input"; // These will likely become Ontology terms
    public static final String DIALOGUE_TYPE_CHOICE = "choice";

    public static final String DIALOGUE_RESPONSE_KEY_TEXT = "text"; // These will likely become Ontology terms
    public static final String DIALOGUE_RESPONSE_KEY_CHOICE = "choice";

    public static final String OP_ASK_USER = "ask-user"; // This will likely become a Primitive Tool name
    public static final String PRED_REQUEST = "pred-request"; // This will likely become an Ontology term
}
