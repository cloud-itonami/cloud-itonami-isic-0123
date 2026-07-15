# Contributing

**Maturity: `:implemented`** — `src/citrusops/` implements the reference
CitrusOpsAdvisor / CitrusOperationsGovernor actor as a synchronous stub
(langgraph-clj StateGraph wiring deferred, see `operation.cljc`).
Contributions that extend coverage are welcome: langgraph-clj StateGraph
integration (real `interrupt-before`/checkpoint-based human-in-the-loop
resume for escalated operations), a Datomic/kotoba-server `Store` backend,
a real LLM `Advisor` implementation, additional Governor rules, and
citrus-species/varietal reference-data expansion in `citrusops.facts`. Open
an issue or PR. License: AGPL-3.0-or-later.
