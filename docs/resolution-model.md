# Warp resolution model (v1 design review)

## Scope and deliberate resolution of the deny/override tension

A hard barrier and an ordinary deny are different effects. `BARRIER` is terminal and absolute within its scope. `DENY` is a soft policy decision. To make soft-deny override possible without hidden exceptions, explicit allows and soft denies share the **explicit arbitration band**. Treating all explicit denies as a band strictly above all explicit grants would make P2 impossible: no grant could ever override one. This is the one deliberate refinement to the suggested band list.

## Bands

Bands are evaluated in this exact order; the first band with an applicable rule wins:

1. `INVARIANT` — compiled-in Selvedge denials only; policy files cannot author this band.
2. `BARRIER` — explicit, absolute policy barriers.
3. `EXPLICIT` — explicit allow and soft-deny rules, arbitrated as below.
4. `INHERITED_GRANT` — role-derived grants.
5. `NAMESPACE_DEFAULT` — namespace fallback rules.
6. If every authority abstains, the system default is deny.

A rule whose subject or guard does not match abstains; it does not consume its band. Role, spatial, network, plugin, and namespace indexes resolve independently. Their non-abstaining outcomes are composed using the exact same compiled rank. An abstaining authority is omitted from composition and cannot change another authority's verdict.

## Total order inside a band

For applicable rules, compare in this order (first difference wins):

1. More-specific action pattern.
2. At equal specificity, soft `DENY` before `ALLOW` (a barrier is not in this band).
3. Higher authority: network, spatial, role, plugin, namespace.
4. Higher signed 32-bit priority.
5. Later source position.
6. Lexicographically smaller canonical rule ID.

Pattern specificity is the tuple `(literal segment count, exact-vs-recursive, segment count)`, descending. A trailing `**` is less specific than an exact pattern with otherwise identical segments. The comparator is total because rule IDs must be unique and canonical. The compiler sorts once and assigns each rule an integer rank. Runtime resolution performs rank comparisons only; it never sorts.

Thus a more-specific explicit allow can override a broader soft deny, while same-specificity peers deny by default. No rule, specificity, priority, authority, or source order can cross the `BARRIER` band. A higher authority only breaks ties after specificity and deny-safety.

## Pattern grammar

An action is one or more dot-separated lowercase segments matching `[a-z0-9_-]+`. A pattern segment is either a literal, `*` (exactly one segment), or `**` (zero or more trailing segments). `**` is legal only as the last segment. Empty segments, embedded wildcard characters, uppercase aliases, and middle-position `**` are compile errors. `foo.**` matches `foo` and every descendant; `foo.*` matches exactly one child. Broad `**` and shallow recursive wildcards are lint warnings.

## Guards and lifecycle

A rule's guard is the conjunction of all declared predicates: world, region, health state, game mode, network persona, plugin-supplied typed values, validity `[notBefore, expiresAt)`, and an optional weekly schedule evaluated in its declared IANA time zone. Schedule intervals are half-open. Overnight schedules include the previous configured day for their after-midnight portion. Missing predicates mean unconstrained; malformed or unknown predicates are compile errors.

## Budgets and decision evidence

For a budgeted allow, Warp selects the policy winner first, then calls Quanta's atomic `reserve` once. A reservation failure is a terminal budget denial; it does not fall through to a less constrained allow. The returned `Decision` contains verdict, canonical Thread record, and optional lease. A lease has one terminal transition: commit (spend) or release (refund). The decision record is independent of localized explanation rendering.

## Failure behavior

- Invalid patterns, duplicate IDs, unknown roles, inheritance cycles, invalid time zones, impossible band/effect pairs, and grants not covered by their source role's capability ceiling fail compilation.
- Missing policy match is deny.
- A clock/context value required by a guard but absent is a guard miss, never a permissive fallback.
- Exhausted budgets deny with a computed retry duration.
- Record serialization is canonical UTF-8 with fixed field order and stable list order; no request UUID or wall-clock read is added implicitly.
