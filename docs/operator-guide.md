# Operator Guide: Citrus Orchard Operations Coordinator

## Overview

The Citrus Orchard Operations Coordinator is a facility-management robot that:

1. **Logs operational data** — planting counts, harvest weights, yield, brix readings
2. **Schedules coordination** — pruning/spraying/irrigation/harvest field operations, supply orders
3. **Escalates concerns** — any crop health, pest (e.g. citrus greening/HLB), fungal disease, or frost-damage issue
4. **Maintains transparency** — audit ledger traces all decisions

The robot is **not** the decision-maker. The grower/agronomist make all
decisions about spray application, crop health response, and economic
choices. The robot **proposes** actions and escalates when human input is
needed.

## Operating the Actor

### Prerequisites

1. **Orchard/Block Registration** — your orchard/block must be registered in
   the system before any operation can proceed
2. **Authorized User** — operator must be authenticated and authorized
3. **Clear Request Type** — specify what you're doing:
   - `:log-orchard-record` — record planting/harvest-yield/brix-test data
   - `:schedule-field-operation` — arrange pruning/spraying/irrigation/harvest
   - `:flag-crop-health-concern` — report a pest/fungal-disease/frost concern
   - `:order-supplies` — procurement request

### Workflow

1. **Submit Request**
   ```clojure
   {:orchard-id "orchard-001"
    :op :log-orchard-record
    :record-type "harvest"
    :count 500
    :notes "healthy yield"}
   ```

2. **Actor Processes** (`operation/run-operation store request context`)
   - `:advise` — `CitrusOpsAdvisor` proposes an action (`citrusops.advisor`)
   - `:govern` — `CitrusOperationsGovernor` checks hard invariants and escalation gates (`citrusops.governor`)
   - phase gate — rollout-phase constraints applied on top of the Governor's verdict (`citrusops.phase`)

3. **Outcomes** (`:disposition` on the return value)
   - **`:commit`** — operation logged, robot proceeds (`:record` is present)
   - **`:escalate`** — operation held pending human decision (audit fact `:t :approval-requested`)
   - **`:hold`** — operation blocked, hard violation (audit fact `:t :governor-hold`, cites `:violations`)

### Escalation Scenarios

**Automatic escalation (always human sign-off):**
- `:flag-crop-health-concern` — any pest (e.g. citrus greening/HLB)/fungal-disease/frost-damage issue
- Supply orders over cost threshold (default 500 currency units)
- Low confidence operations (< 0.7)

**Hard blocks (no override):**
- `:operate-field-equipment` — direct equipment operation is grower authority
- `:finalize-spray-application` — spray-application decisions are agronomist/grower authority
- Missing/unregistered orchard/block — must register first

### Resuming Escalated Operations

`citrusops.operation` is currently a synchronous stub (see its docstring):
one call to `(operation/run-operation store request context)` runs the full
`advise -> govern -> phase-gate` flow and returns immediately with a
`:disposition` of `:commit`, `:escalate`, or `:hold`. There is **no
persisted pause/resume yet** — that requires the deferred `langgraph-clj`
StateGraph integration (`interrupt-before` + checkpoint-based resume,
mirroring `cloud-itonami-isic-0122`). Until then, an `:escalate`
disposition means: **do not commit** — the caller (production
integration layer) is responsible for holding the proposal for human
review and re-submitting a follow-up operation once approved.

## Audit & Transparency

Every operation run returns an `:audit` vector containing an
advisor-proposal trace and a disposition fact (`:committed`,
`:governor-hold`, or `:approval-requested`). Production integration is
responsible for appending these facts to an append-only ledger (the
reference implementation does not include a ledger-writer — that's a
backend-integration concern, same seam point as the `Store`).

- Every proposal produces a trace, regardless of outcome
- Every hold cites the specific Governor rule(s) violated (`:violations`)
- Every escalation cites its `:reason` (always-escalate op / high cost / low confidence)

## Integration

The actor provides a standard protocol (`citrusops.store/Store`) for backend
integration:

- **Orchard/block lookup** — `(store/registered-orchard store orchard-id)`

Implementations include in-memory `MemStore` (testing, `citrusops.store`),
and future Datomic/kotoba-server backends (the same seam point all
cloud-itonami actors use). Record-commit and ledger-append are integration
responsibilities on top of `operation/run-operation`'s return value, not
part of the `Store` protocol itself.

## Safety Guarantees

- **No unsupervised decisions** — no spray-application or crop-health
  response decision is made by the robot
- **No suppressed concerns** — crop health concerns cannot be hidden or delayed
- **No unlogged operations** — every action is recorded in the audit ledger
- **No direct execution** — the governor gates every robot action

The robot is safe because:
1. It never decides — it proposes
2. It always escalates when needed
3. It never hides information
4. Every action is auditable
