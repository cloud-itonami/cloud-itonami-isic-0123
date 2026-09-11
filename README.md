# cloud-itonami-isic-0123

Open Occupation Blueprint for **ISIC Rev. 4 0123**: Growing of citrus
fruits.

This repository implements a forkable OSS **citrus orchard operations
coordinator**: a facility-management robot manages orchard/block record
logging, field-operation (pruning/spraying/irrigation/harvest) scheduling,
and supply procurement under a governor-gated actor, so a citrus-growing
operation (orange, lemon, lime, grapefruit orchards) keeps its own
operational records and maintains full transparency over decisions.

**Maturity: `:implemented`.** `src/citrusops/` implements the
`CitrusOpsAdvisor` (`citrusops.advisor`) and the independent
`CitrusOperationsGovernor` (`citrusops.governor`), composed by
`citrusops.operation` following the itonami actor pattern
(ADR-2607011000): `advise -> govern -> phase-gate -> commit | escalate |
hold`. See [Testing](#testing) below for the current green test count
(`kbb -M:test`).

`citrusops.operation` is a synchronous stub of this flow (see its
docstring) — production wiring into a `langgraph-clj` StateGraph with
`interrupt-before`/checkpoint-based human-in-the-loop resume for escalated
operations is deferred, mirroring `cloud-itonami-isic-0122`'s own
`orchardops.operation`.

## What this does NOT do

This actor coordinates **back-office logistics only**. It explicitly does **NOT**:

- **Direct field-equipment operation** — remains the grower's exclusive authority
- **Spray-application decisions** — remains the agronomist/grower authority
- **Harvest-timing / economic decisions** — economic authority remains human
- **Direct execution of any kind** — any proposal for direct actuation is a hard block

## HARD invariants (always hold, never overridable)

1. **orchard-not-registered** — the request's `orchard-id` must resolve to a
   registered orchard/block in the Store before any proposal can proceed
2. **no-execution** — every proposal's `:effect` must be `:propose` (the governor
   never directly operates field equipment, never finalizes a spray application)
3. **field-equipment-or-spray-blocked** — `:operate-field-equipment` and
   `:finalize-spray-application` proposals are unconditionally, permanently blocked
4. **op-not-allowed** — any op outside the closed allowlist below is rejected
5. **orchard-count-invalid** — `:log-orchard-record` with a non-positive logged
   quantity (trees/plants counted / harvest weight / yield estimate / brix reading)
   is rejected

## Always-escalate operations (human sign-off, regardless of confidence)

- `:flag-crop-health-concern` — any pest (e.g. citrus greening/HLB, citrus
  canker)/fungal-disease/frost-damage concern → automatic escalation
- `:order-supplies` over its category cost threshold (default 500 currency
  units; see `citrusops.facts/supply-categories`)
- Any proposal with confidence below the Governor's floor (0.7)

## Operational requests (closed allowlist, all `:effect :propose`)

```text
:log-orchard-record
  — record planting/harvest-yield/brix-test data
  — requires a registered orchard/block; non-positive quantities are rejected

:schedule-field-operation
  — propose pruning/spraying/irrigation/harvest scheduling
  — does NOT make or finalize a spray-application decision

:flag-crop-health-concern
  — surface a pest (e.g. citrus greening/HLB), fungal disease, or
    frost-damage concern
  — ALWAYS escalates for human review

:order-supplies
  — procurement for seedlings, fertilizer, equipment
  — escalates if cost exceeds its category threshold
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs the
physical domain work**. Here a facility-management robot handles:

- Orchard/block record logging and entry
- Field-operation scheduling and reminders
- Supply inventory and ordering
- Audit ledger maintenance

The **CitrusOperationsGovernor** is the independent safety layer that gates all
proposals before a robot action is executed. The governor never dispatches hardware
directly; `:high`/`:safety-critical` actions (such as escalated crop-health concerns
or high-cost supply orders) require human sign-off.

## Core Contract

```text
operational request (log, schedule, concern, order)
        |
        v
CitrusOpsAdvisor -> CitrusOperationsGovernor -> phase gate -> commit, or escalate for human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated operation can dispatch a robot action the governor refuses, suppress an
operating record, or hide a crop-health concern without governor approval and audit
evidence.

## Module structure

Mirrors `cloud-itonami-isic-0122` (`orchardops.*`) module-for-module:

- `citrusops.facts` — reference data: supply-category cost thresholds, fruit classes
- `citrusops.registry` — pure independent verification functions (cost/count/confidence)
- `citrusops.store` — `Store` protocol + in-memory `MemStore` (orchard/block registration lookup)
- `citrusops.advisor` — `Advisor` protocol + `MockAdvisor` (the sealed LLM/decision node)
- `citrusops.governor` — `CitrusOperationsGovernor`: hard invariants + escalation gates
- `citrusops.phase` — 0→3 rollout phase gate
- `citrusops.operation` — composes advisor → governor → phase into one operation run
- `citrusops.sim` — demo runner (`kbb -M:run`)

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISIC Rev. 4 `0123`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Testing

```bash
kbb -M:test   # run the suite (see raw output for tests/assertions)
kbb -M:lint   # clj-kondo, 0 errors / 0 warnings
kbb -M:run    # demo runner
```

## License

AGPL-3.0-or-later.
