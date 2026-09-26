# Heddle policy schema v1

Heddle accepts UTF-8 JSON with `schemaVersion: 1`. JSON is intentional for the first vertical slice: strict parsing, stable diffs, schema validation, and useful line/field diagnostics take precedence over a bespoke syntax. Unknown fields are errors. See `src/main/resources/schemas/policy-v1.schema.json` for the machine-readable shape.

## Typed nodes

- `roles` are named nodes with `inherits` edges and a required `ceiling` array of capability patterns. A role-bound allow is legal only if its entire action pattern is conservatively proven to be contained by one capability pattern in its source role's ceiling and by every descendant role ceiling along an inheritance path. The ceiling is a capability set, not a rank label. A narrower child ceiling that cannot contain an inherited grant is a compile error; the author must narrow the inherited rule, widen the child ceiling intentionally, or declare a valid explicit override. Inherited grants retain their source role.
- `rules` are stable, unique IDs with an action pattern, effect, band, authority, optional role or canonical subject UUID, priority, guard, and optional budget.
- `guard` is a conjunction of context values and lifecycle predicates. `schedule` has `zone`, `days`, `start`, and `end` in 24-hour local time.
- `overrides` is reserved for explicit inherited-rule overrides. In v1, references are typechecked and must name a rule inherited through the declaring role; a rule's normal compiled precedence still decides the result.
- `meta` contains labels only. Labels never grant authority.

`schemaVersion` upgrades are explicit: a later compiler must ship a versioned upgrader and preserve a before/after diff. A policy is never silently reinterpreted.

## Example

See the survival pack. It demonstrates role ceilings, a region guard, explicit denial, and a scoped budget. The package's executable corpus tests are authoritative; examples do not imply a server adapter exists.

## Diagnostics

Diagnostics have stable codes, severity, JSON pointer, and actionable text. A middle `**` is a compile error. Broad patterns, identical shadowed rules, and suspicious region unions are lint findings. Heddle never repairs an invalid policy by guessing.
