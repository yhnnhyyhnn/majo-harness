package io.majo.harness.agent.loop;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-session dual inbox (dsh next-turn / next-step analog). Turn-starting
 * entries ({@code followup}, and {@code steer} offered while idle) wake or
 * chain a turn; note entries ({@code steer} into a running turn,
 * {@code inject}) are delivered at step boundaries or turn openings and never
 * start a turn.
 */
final class AgentInbox {

    enum Kind { STEER, INJECT }

    record Note(Kind kind, String text) {
    }

    private final Queue<String> turnStarters = new ConcurrentLinkedQueue<>();
    private final Queue<Note> notes = new ConcurrentLinkedQueue<>();

    void offerTurnStart(String text) {
        turnStarters.add(text);
    }

    void offerNote(Note note) {
        notes.add(note);
    }

    /** Pops the next turn-starting entry, or {@code null} when none. */
    String pollTurnStart() {
        return turnStarters.poll();
    }

    /** Drains every queued note (delivery order preserved). */
    Queue<Note> drainNotes() {
        Queue<Note> delivered = new ConcurrentLinkedQueue<>();
        Note note;
        while ((note = notes.poll()) != null) {
            delivered.add(note);
        }
        return delivered;
    }

    boolean hasTurnWork() {
        return !turnStarters.isEmpty();
    }

    /** Turn starters plus undelivered notes. */
    int size() {
        return turnStarters.size() + notes.size();
    }

    boolean isEmpty() {
        return turnStarters.isEmpty() && notes.isEmpty();
    }
}
