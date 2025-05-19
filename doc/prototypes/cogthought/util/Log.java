package dumb.cogthought.util;

import static java.util.Objects.requireNonNull;
import dumb.cogthought.util.Events; // Updated import

// This class provides logging utility and will be refactored to assert logs into KB
public class Log {

    private static Events events; // Keep for now, will be replaced by KnowledgeBase access

    public static void setEvents(Events events) {
        Log.events = requireNonNull(events);
    }

    public static void message(String message) {
        message(message, LogLevel.INFO);
    }

    public static void error(String message) {
        message(message, LogLevel.ERROR);
    }

    public static void warning(String message) {
        message(message, LogLevel.WARNING);
    }

    // This method will be refactored to assert log messages into the KnowledgeBase
    public static void message(String message, LogLevel level) {
        if (events != null) {
            events.emit(new Events.LogMessageEvent(message, level));
        } else {
            System.out.println("[" + level + "] " + message);
        }
    }

    // Keep for now, LogLevel will likely become an Ontology term
    public enum LogLevel {
        INFO, WARNING, ERROR
    }
}
