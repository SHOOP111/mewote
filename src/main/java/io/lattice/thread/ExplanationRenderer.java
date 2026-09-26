package io.lattice.thread;

import io.lattice.warp.Decision;
import io.lattice.warp.ReasonCode;
import io.lattice.warp.Verdict;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Human rendering is downstream of the record. Public text never includes rule IDs or internal trace. */
public final class ExplanationRenderer {
    public Explanation render(Decision decision, Disclosure disclosure, String accessRequestLink, Locale locale) {
        ReasoningRecord record = decision.record();
        Locale language = locale == null ? Locale.ENGLISH : locale;
        List<Explanation.Segment> segments = new ArrayList<>();
        String severity = decision.verdict() == Verdict.ALLOW ? t(language, "allowed", "ALLOWED", "PERMITIDO")
                : t(language, "denied", "DENIED", "DENEGADO");
        add(segments, "explanation.action", t(language, "action", "Action: ", "Acción: ") + record.action());

        if (disclosure == Disclosure.PUBLIC) {
            String message = publicReason(decision, language);
            add(segments, "explanation.reason.public", message);
            if (decision.verdict() == Verdict.DENY && accessRequestLink != null && !accessRequestLink.isBlank()) {
                add(segments, "explanation.request", t(language, "request", "Request access: ", "Solicitar acceso: ") + accessRequestLink);
            }
        } else if (disclosure == Disclosure.STAFF) {
            add(segments, "explanation.reason.staff", "Reason: " + record.reason().name());
            add(segments, "explanation.winner", "Winning rule: " + valueOrDash(record.winningRuleId())
                    + " | band: " + valueOrDash(record.band() == null ? null : record.band().name()));
            for (TraceStep step : record.trace()) {
                add(segments, "explanation.trace", step.outcome() + " " + valueOrDash(step.ruleId())
                        + " [" + step.authority() + "/" + step.band() + "] " + step.detail());
            }
        } else {
            add(segments, "explanation.audit", record.canonical());
        }
        String plain = segments.stream().map(Explanation.Segment::text).reduce((a, b) -> a + System.lineSeparator() + b).orElse("");
        return new Explanation("[" + severity + "]", segments, "[" + severity + "]" + System.lineSeparator() + plain);
    }

    private String publicReason(Decision decision, Locale locale) {
        if (decision.verdict() == Verdict.ALLOW) return t(locale, "allow", "This action is allowed by the server policy.", "Esta acción está permitida por la política del servidor.");
        return switch (decision.reason()) {
            case BARRIER -> t(locale, "barrier", "This action is blocked by a protection rule in this context.", "Esta acción está bloqueada por una regla de protección en este contexto.");
            case INVARIANT_SELF_ESCALATION -> t(locale, "self", "Authority changes require a separate approver; you cannot grant this access to yourself.", "Los cambios de autoridad requieren otra persona aprobadora; no puedes concederte este acceso.");
            case INVARIANT_CEILING_MUTATION -> t(locale, "ceiling", "Capability ceilings are protected system controls and cannot be changed through this action.", "Los límites de capacidades son controles protegidos del sistema y no se pueden cambiar con esta acción.");
            case BUDGET_EXHAUSTED -> "The action is temporarily rate-limited. " + waitText(decision.retryAfter()) + "";
            case DEFAULT_DENY -> t(locale, "default", "No policy rule grants this action in the current context.", "Ninguna regla permite esta acción en el contexto actual.");
            case RULE_DENY -> t(locale, "deny", "This action is restricted by server policy in the current context.", "La política del servidor restringe esta acción en el contexto actual.");
            case RULE_ALLOW -> t(locale, "deny-fallback", "The action was not authorized.", "La acción no está autorizada.");
            case ABSTAIN -> t(locale, "abstain", "No authority layer made a decision; the system default is deny.", "Ninguna capa de autoridad decidió; el valor predeterminado es denegar.");
        };
    }

    private String waitText(Duration duration) {
        if (duration == null || duration.isZero()) return "Try again shortly.";
        long seconds = Math.max(1, (duration.toMillis() + 999) / 1000);
        return "Try again in about " + seconds + (seconds == 1 ? " second." : " seconds.");
    }

    private static String t(Locale locale, String key, String english, String spanish) {
        return locale.getLanguage().equals("es") ? spanish : english;
    }

    private static void add(List<Explanation.Segment> segments, String key, String text) {
        segments.add(new Explanation.Segment(key, text));
    }

    private static String valueOrDash(String value) { return value == null ? "—" : value; }
}
