package gsb.model;

/** What the consensus should hold for a region. */
public enum DecisionType {
    /** Copy a specific source's label into the consensus. */
    ACCEPT_SOURCE,
    /** Hand-drawn fix with an explicit canonical class key. */
    CORRECTION,
    /** Region deliberately left undecided. */
    PENDING
}
