package gsb.model;

/** Append-only consensus log event kinds. The consensus layer never edits input masks. */
public enum EventType {
    /** A new region decision (accept a source, hand-drawn correction, mark pending). */
    CREATE,
    /** A correction that supersedes an earlier decision within the same region. */
    REVISE,
    /** Reverse event produced by undoing CREATE/REVISE; the prior event is retained. */
    UNDO,
    /** Re-applies a previously undone decision by appending a forward event. */
    REDO
}
