package com.fileman.app;

import java.util.ArrayList;

/** Undo / redo as small diffs (start, removed text, inserted text) with a hard memory limit. */
final class UndoLog {
    private static final long MAX_CHARS = 1_000_000L;
    private static final long MERGE_MS = 900L;
    private static final int MAX_STEPS = 4000;

    static final class Diff {
        int start;
        String removed;
        String added;
        long at;

        Diff(int start, String removed, String added, long at) {
            this.start = start;
            this.removed = removed;
            this.added = added;
            this.at = at;
        }

        int size() {
            return removed.length() + added.length();
        }
    }

    private final ArrayList<Diff> undo = new ArrayList<>();
    private final ArrayList<Diff> redo = new ArrayList<>();
    private long chars;

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    void clear() {
        undo.clear();
        redo.clear();
        chars = 0;
    }

    /** Records one text change. Consecutive typing or deleting is merged into one step. */
    void record(int start, String removed, String added, long now) {
        if (removed.isEmpty() && added.isEmpty()) return;
        for (Diff d : redo) chars -= d.size();
        redo.clear();
        if (!undo.isEmpty()) {
            Diff last = undo.get(undo.size() - 1);
            if (now - last.at <= MERGE_MS) {
                if (removed.isEmpty() && last.removed.isEmpty() && start == last.start + last.added.length()
                        && last.added.length() < 2000) {
                    last.added = last.added + added;   // typing forward
                    last.at = now;
                    chars += added.length();
                    trim();
                    return;
                }
                if (added.isEmpty() && last.added.isEmpty() && start + removed.length() == last.start
                        && last.removed.length() < 2000) {
                    last.removed = removed + last.removed;   // backspace
                    last.start = start;
                    last.at = now;
                    chars += removed.length();
                    trim();
                    return;
                }
            }
        }
        Diff d = new Diff(start, removed, added, now);
        undo.add(d);
        chars += d.size();
        trim();
    }

    private void trim() {
        while ((chars > MAX_CHARS || undo.size() > MAX_STEPS) && undo.size() > 1) {
            chars -= undo.remove(0).size();
        }
    }

    Diff popUndo() {
        if (undo.isEmpty()) return null;
        Diff d = undo.remove(undo.size() - 1);
        redo.add(d);
        return d;
    }

    Diff popRedo() {
        if (redo.isEmpty()) return null;
        Diff d = redo.remove(redo.size() - 1);
        undo.add(d);
        return d;
    }
}
