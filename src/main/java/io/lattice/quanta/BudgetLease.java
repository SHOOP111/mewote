package io.lattice.quanta;

import java.util.concurrent.atomic.AtomicReference;

/** A reservation has exactly one terminal transition: commit or refund. */
public final class BudgetLease implements AutoCloseable {
    public enum State { RESERVED, COMMITTED, RELEASED }
    interface Settlement { boolean finish(boolean commit); }

    private final Settlement settlement;
    private final AtomicReference<State> state = new AtomicReference<>(State.RESERVED);

    BudgetLease(Settlement settlement) { this.settlement = settlement; }

    public boolean commit() { return settle(State.COMMITTED, true); }
    public boolean release() { return settle(State.RELEASED, false); }
    public State state() { return state.get(); }

    private boolean settle(State terminal, boolean commit) {
        if (!state.compareAndSet(State.RESERVED, terminal)) return false;
        if (settlement.finish(commit)) return true;
        throw new IllegalStateException("Budget reservation settlement lost its bucket accounting");
    }

    /** Uncompleted work is refunded on scope exit. */
    @Override public void close() { release(); }
}
