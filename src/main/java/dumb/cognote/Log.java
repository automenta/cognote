package dumb.cognote;

import static java.util.Objects.requireNonNull;

public class Log {

    private static Events events;

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

    public static void message(String message, LogLevel level) {
        if (events != null) {
            events.emit(new Events.LogMessageEvent(message, level));
        } else {
            System.out.println("[" + level + "] " + message);
        }
    }

    public enum LogLevel {
        INFO, WARNING, ERROR
    }
}
