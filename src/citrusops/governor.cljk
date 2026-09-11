(ns citrusops.governor
  "Citrus Operations Governor -- the independent compliance layer that
  earns the CitrusOpsAdvisor the right to commit. The LLM has no notion
  of:
    - Whether the orchard/block a proposal targets is actually registered
    - Whether a proposal is a real actuation (`:effect :propose` only --
      this actor NEVER directly operates field equipment or finalizes a
      spray-application decision)
    - Whether an op is inside this actor's closed coordination allowlist
    - Whether a logged orchard-record quantity is a plausible positive
      observation
    - Whether a supply-order's cost exceeds the escalation threshold

  This MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  This actor is a back-office OPERATIONS COORDINATOR only -- direct field
  equipment operation and spray-application decisions are categorically
  outside its authority (grower/agronomist exclusive). The Governor
  enforces that boundary structurally, not by trusting the advisor's
  judgment.

  CRITICAL: Any proposal to flag a crop health concern (citrus greening/
  HLB, canker, frost-damage) ALWAYS escalates to a human (grower/
  agronomist) for final sign-off. The LLM's confidence is never
  sufficient for crop-health decisions.

  Hard violations (always HOLD, no override, permanent):
    1. Orchard/block not registered (orchard-id missing or unknown to
       Store)
    2. Proposal `:effect` is not `:propose` (no direct execution, ever)
    3. Op is `:operate-field-equipment` or `:finalize-spray-application` --
       direct field-equipment operation and finalizing a spray-application
       decision are PERMANENTLY blocked regardless of proposal content or
       confidence
    4. Op is outside the closed proposal-op allowlist
    5. `:log-orchard-record` with a non-positive logged quantity

  Soft gates (always escalate for human):
    - `:flag-crop-health-concern` -- ALWAYS escalates
    - `:order-supplies` above its category cost threshold
    - Low confidence

  This design mirrors `orchardops.governor` (cloud-itonami-isic-0122) but
  specializes citrus-fruit orchard back-office coordination concerns
  (orchard/block registration, closed op allowlist, field-equipment/
  spray-application exclusion, cost threshold) rather than tropical/
  subtropical-fruit concerns."
  (:require [citrusops.facts :as facts]
            [citrusops.registry :as registry]
            [citrusops.store :as store]))

(def confidence-floor 0.7)

(def blocked-ops
  "Direct field-equipment operation and finalizing a spray-application
  decision sit outside this actor's coordination-only authority. ALWAYS a
  hard, permanent block -- never escalate, never override, regardless of
  confidence or cites."
  #{:operate-field-equipment :finalize-spray-application})

(def known-ops
  "The closed allowlist of proposal ops this actor may make -- all
  `:effect :propose` (see ADR domain design)."
  #{:log-orchard-record :schedule-field-operation
    :flag-crop-health-concern :order-supplies})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off even when the Governor finds no
  hard violation and confidence is high. Flagging a crop health concern is
  never something this actor resolves autonomously."
  #{:flag-crop-health-concern})

(def all-recognized-ops
  "known-ops (allowed to proceed) union blocked-ops (recognized but
  permanently forbidden). Anything outside this union is an unknown op --
  a HARD violation, not a silent no-op."
  (into known-ops blocked-ops))

;; ----------------------------- checks -----------------------------

(defn- orchard-violations
  "A proposal referencing an unregistered (or absent) orchard-id is a
  HARD violation -- never act on behalf of an orchard/block this actor
  cannot independently verify."
  [{:keys [orchard-id]} st]
  (when-not (store/registered-orchard st orchard-id)
    [{:rule :orchard-not-registered
      :detail (str "orchard-id " (pr-str orchard-id) " は登録済み区画として確認できない -- 区画登録前の提案は進められない")}]))

(defn- execution-violations
  "This actor never executes directly. Any proposal whose `:effect` isn't
  `:propose` is a HARD violation, independent of what op it claims."
  [proposal]
  (when-not (= :propose (:effect proposal))
    [{:rule :no-execution
      :detail "提案の :effect は :propose でなければならない -- governor は直接実行/作動を許可しない"}]))

(defn- field-equipment-or-spray-violations
  "Direct field-equipment operation and finalizing a spray-application
  decision are a HARD, permanent block -- agronomic and mechanical
  authority remains exclusively human."
  [proposal]
  (when (contains? blocked-ops (:op proposal))
    [{:rule :field-equipment-or-spray-blocked
      :detail (str (:op proposal) " は圃場設備の直接操作または散布適用の最終決定であり、恒久的にブロックされる -- 栽培責任者/圃場管理者の専権事項")}]))

(defn- unknown-op-violations
  "Enforce the closed proposal-op allowlist independently of the
  advisor's claim -- an op outside `all-recognized-ops` is a HARD
  violation, never a silent pass-through."
  [proposal]
  (when-not (contains? all-recognized-ops (:op proposal))
    [{:rule :op-not-allowed
      :detail (str (:op proposal) " はクローズドallowlist外の操作")}]))

(defn- orchard-count-invalid-violations
  "For `:log-orchard-record`, INDEPENDENTLY verify the logged quantity
  (trees/plants counted / harvest weight / yield estimate / brix reading)
  is a plausible positive observation via
  `registry/orchard-count-non-positive?`. Evaluated only when a `:count`
  is present on the proposal."
  [proposal]
  (when (and (= :log-orchard-record (:op proposal))
             (contains? proposal :count)
             (registry/orchard-count-non-positive? (:count proposal)))
    [{:rule :orchard-count-invalid
      :detail (str "数量 " (:count proposal) " は正の数でなければならない -- 記録提案は進められない")}]))

(defn- cost-threshold-for
  "Resolve the escalation threshold for a supply-order proposal: the
  category-specific threshold from `citrusops.facts` if the category is
  known, else the conservative default."
  [proposal]
  (let [category (get-in proposal [:value :category])
        c (and category (facts/supply-category-by-id category))]
    (or (:cost-threshold c) facts/default-cost-threshold)))

(defn check
  "Censors a CitrusOpsAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (orchard-violations request st)
                           (execution-violations proposal)
                           (field-equipment-or-spray-violations proposal)
                           (unknown-op-violations proposal)
                           (orchard-count-invalid-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (registry/confidence-below-floor? conf confidence-floor)
        cost (:cost proposal)
        high-cost? (boolean (and cost (registry/cost-exceeds-threshold?
                                        cost (cost-threshold-for proposal))))
        always-escalate? (contains? always-escalate-ops (:op proposal))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not high-cost?) (not always-escalate?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? high-cost? always-escalate?))
     :high-stakes? (boolean (or high-cost? always-escalate?))}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:orchard-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
