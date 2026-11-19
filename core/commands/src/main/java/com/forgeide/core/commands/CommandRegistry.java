package com.forgeide.core.commands;

import com.forgeide.core.util.FuzzyMatcher;
import java.util.*;
import java.util.function.BooleanSupplier;

/** The command palette, menus and keyboard shortcuts execute the same registered actions. */
public final class CommandRegistry {

    public static final class Command {

        public final String id, title, shortcut;
        public final Runnable action;
        public final BooleanSupplier enabled;

        public Command(String id, String title, String shortcut, Runnable action, BooleanSupplier enabled) {
            this.id = id;
            this.title = title;
            this.shortcut = shortcut;
            this.action = action;
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            return title + (shortcut.isEmpty() ? "" : "    " + shortcut);
        }
    }

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(Command command) {
        commands.put(command.id, command);
    }

    public boolean execute(String id) {
        Command c = commands.get(id);
        if (c == null || !c.enabled.getAsBoolean()) return false;
        c.action.run();
        return true;
    }

    public List<Command> search(String query) {
        List<Scored> scored = new ArrayList<>();
        for (Command c : commands.values()) {
            FuzzyMatcher.Match m = c.enabled.getAsBoolean() ? FuzzyMatcher.match(query, c.title) : null;
            if (m != null) scored.add(new Scored(c, m.score));
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.score).reversed());
        List<Command> out = new ArrayList<>();
        for (Scored s : scored) out.add(s.command);
        return out;
    }

    private static final class Scored {

        final Command command;
        final int score;

        Scored(Command command, int score) {
            this.command = command;
            this.score = score;
        }
    }
}
