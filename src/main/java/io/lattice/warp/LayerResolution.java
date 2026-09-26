package io.lattice.warp;

import io.lattice.thread.TraceStep;

import java.util.List;

/** Internal result from one independently evaluated authority. */
public record LayerResolution(Verdict verdict, ReasonCode reason, CompiledRule winner, List<TraceStep> trace) {
    public LayerResolution {
        trace = List.copyOf(trace);
    }

    public static LayerResolution abstain(List<TraceStep> trace) {
        return new LayerResolution(Verdict.ABSTAIN, ReasonCode.ABSTAIN, null, trace);
    }
}
