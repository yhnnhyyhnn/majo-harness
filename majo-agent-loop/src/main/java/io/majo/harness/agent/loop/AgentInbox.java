package io.majo.harness.agent.loop;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-session dual inbox (dsh next-turn / next-step analog). Turn-starting
 * entries ({@code followup}, and {@code steer} offered while idle) wake or
 * chain a turn; note entries ({@code steer} into a running turn,
 * {@code inject}) are delivered at step boundaries or turn openings and never
 * start a turn. Each turn entry carries a producer tag ({@code null} =
 * human, dsh source parity) that lands as the USER_MESSAGE {@code producer}
 * field — goal rounds ride it with their goal/round metadata.
 */
final class AgentInbox {

    enum Kind { STEER, INJECT }

    record Note(Kind kind, String text) {
    }

    /** One queued turn start: the text plus its producer tag + metadata. */
    record TurnEntry(String text, Map<String, Object> producer) {
        TurnEntry {
            producer = producer == null || producer.isEmpty()
                    ? Map.of() : Map.copyOf(producer);
        }
    }

    private final Queue<TurnEntry> turnStarters = new ConcurrentLinkedQueue<>();
    private final Queue<Note> notes = new ConcurrentLinkedQueue<>();

    void offerTurnStart(String text) {
        turnStarters.add(new TurnEntry(text, Map.of()));
    }

    void offerTurnStart(String text, Map<String, Object> producer) {
        turnStarters.add(new TurnEntry(text, producer));
    }

    void offerNote(Note note) {
        notes.add(note);
    }

    /** Pops the next turn-starting entry, or {@code null} when none. */
    TurnEntry pollTurnStart() {
        return turnStarters.poll();
    }

    /**
     * Withdraws queued turn entries matching the predicate (dsh
     * inbox.remove parity — a cancelled goal round must not stay queued
     * ahead of a human prompt). Returns how many entries were removed.
     */
    int removeTurnStartIf(java.util.function.Predicate<TurnEntry> predicate) {
        int removed = 0;
        for (java.util.Iterator<TurnEntry> it = turnStarters.iterator(); it.hasNext();) {
            if (predicate.test(it.next())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Whether any queued turn entry matches the predicate. */
    boolean hasTurnStart(java.util.function.Predicate<TurnEntry> predicate) {
        for (TurnEntry entry : turnStarters) {
            if (predicate.test(entry)) {
                return true;
            }
        }
        return false;
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
