package io.lattice.quanta;

/** Named service boundary for budget reservation. */
public final class Quanta {
    private final BudgetService budgets;
    public Quanta(BudgetService budgets) { this.budgets = budgets; }
    public BudgetService budgets() { return budgets; }
}
