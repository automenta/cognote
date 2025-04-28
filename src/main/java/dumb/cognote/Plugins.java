package dumb.cognote;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static dumb.cognote.Log.error;
import static dumb.cognote.Log.message;

public class Plugins {
    private final Events events;
    private final Logic.Cognition context;
    private final List<Plugin> plugins = new CopyOnWriteArrayList<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    Plugins(Events events, Logic.Cognition context) {
        this.events = events;
        this.context = context;
    }

    public void add(Plugin plugin) {
        if (initialized.get()) {
            error("Cannot load plugin " + plugin.id() + " after initialization.");
            return;
        }
        plugins.add(plugin);
        message("Plugin loaded: " + plugin.id());
    }

    public void initializeAll() {
        if (!initialized.compareAndSet(false, true)) return;
        message("Initializing " + plugins.size() + " general plugins...");
        plugins.forEach(plugin -> {
            try {
                plugin.start(events, context);
                message("Initialized plugin: " + plugin.id());
            } catch (Exception e) {
                error("Failed to initialize plugin " + plugin.id() + ": " + e.getMessage());
                e.printStackTrace();
                plugins.remove(plugin);
            }
        });
        message("General plugin initialization complete.");
    }

    public void shutdownAll() {
        message("Shutting down " + plugins.size() + " general plugins...");
        plugins.forEach(plugin -> {
            try {
                plugin.stop();
                message("Shutdown plugin: " + plugin.id());
            } catch (Exception e) {
                error("Error shutting down plugin " + plugin.id() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
        plugins.clear();
        message("General plugin shutdown complete.");
    }

}
