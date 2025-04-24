package dumb.cognote;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class Plugins {
    private final Events events;
    private final Logic.Cognition context;
    private final List<Plugin> plugins = new CopyOnWriteArrayList<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    Plugins(Events events, Logic.Cognition context) {
        this.events = events;
        this.context = context;
    }

    public void loadPlugin(Plugin plugin) {
        if (initialized.get()) {
            System.err.println("Cannot load plugin " + plugin.id() + " after initialization.");
            return;
        }
        plugins.add(plugin);
        System.out.println("Plugin loaded: " + plugin.id());
    }

    public void initializeAll() {
        if (!initialized.compareAndSet(false, true)) return;
        System.out.println("Initializing " + plugins.size() + " general plugins...");
        plugins.forEach(plugin -> {
            try {
                plugin.start(events, context);
                System.out.println("Initialized plugin: " + plugin.id());
            } catch (Exception e) {
                System.err.println("Failed to initialize plugin " + plugin.id() + ": " + e.getMessage());
                e.printStackTrace();
                plugins.remove(plugin); // Remove failed plugin
            }
        });
        System.out.println("General plugin initialization complete.");
    }

    public void shutdownAll() {
        System.out.println("Shutting down " + plugins.size() + " general plugins...");
        plugins.forEach(plugin -> {
            try {
                plugin.stop();
                System.out.println("Shutdown plugin: " + plugin.id());
            } catch (Exception e) {
                System.err.println("Error shutting down plugin " + plugin.id() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
        plugins.clear();
        System.out.println("General plugin shutdown complete.");
    }

}
