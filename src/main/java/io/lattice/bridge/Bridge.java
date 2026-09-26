package io.lattice.bridge;

import io.lattice.warp.Action;
import io.lattice.warp.Decision;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.SubjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Compatibility facade and non-authoritative decision observers. */
public final class Bridge implements PermissionProvider {
    private final DecisionEngine engine;
    private final List<Consumer<Decision>> observers = new CopyOnWriteArrayList<>();

    public Bridge(DecisionEngine engine) { this.engine = Objects.requireNonNull(engine, "engine"); }
    @Override public Decision check(DecisionRequest request) {
        Decision decision = engine.evaluate(request);
        for (Consumer<Decision> observer : observers) observer.accept(decision);
        return decision;
    }
    @Override public List<Decision> checkBulk(List<DecisionRequest> requests) {
        List<Decision> decisions = new ArrayList<>(requests.size());
        for (DecisionRequest request : requests) decisions.add(check(request));
        return List.copyOf(decisions);
    }
    @Override public boolean hasPermission(SubjectId subject, Action action, DecisionRequest requestTemplate) {
        if (!subject.equals(requestTemplate.subject()) || !action.equals(requestTemplate.action())) {
            throw new IllegalArgumentException("Request template must contain the same stable subject and action");
        }
        return check(requestTemplate).allowed();
    }
    public void observe(Consumer<Decision> observer) { observers.add(observer); }
    public void removeObserver(Consumer<Decision> observer) { observers.remove(observer); }
}
