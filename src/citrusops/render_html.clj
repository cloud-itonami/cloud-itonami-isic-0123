(ns citrusops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was no demo page and no generator at all.

  EVERY id, number, disposition, hold reason and phase cell on the page
  is REAL output of this repo's own actor stack, executed at build time:
  `citrusops.operation/build` -> `citrusops.advisor` (mock advisor, the
  sealed decision node) -> `citrusops.governor/check` ->
  `citrusops.phase/gate` -> `citrusops.store`. Nothing on the page is a
  hand-typed copy of what those namespaces are believed to return.

  Two things about this repo shape the code below:

    1. `citrusops.store` has NO ledger and NO `demo-data` -- it only
       answers \"is this orchard/block registered\". So `run-demo!`
       seeds a fresh `store/mem-store` with the blocks the scenario
       uses (the same thing `citrusops.sim` does) and accumulates the
       audit facts the actor actually returns (`:audit` of each
       `run-operation` result) into an in-process ledger.
    2. `citrusops.operation` does not (yet) wire a langgraph StateGraph
       -- `build` returns the real synchronous actor fn, and this repo
       carries no langgraph dependency. So the actor is driven through
       its own real entry point rather than `langgraph.graph/run*`.
       There is likewise no approval/resume seam, so an escalation is
       rendered as what it really is: a pending human sign-off request.

  The scenario is deterministic (fixed inputs, no timestamps, no
  randomness, every set iterated in sorted order), so two consecutive
  runs produce byte-identical output -- verify by diffing them.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [citrusops.facts :as facts]
            [citrusops.governor :as governor]
            [citrusops.operation :as operation]
            [citrusops.phase :as phase]
            [citrusops.store :as store]
            [jp-go-dds.skin]))

;; ----------------------------- scenario -----------------------------

(def ^:private seeded-orchards
  "The registered orchard/block directory this scenario runs against.
  `citrusops.store` ships no demo data, so the scenario seeds the same
  way `citrusops.sim` does. Fruit classes are the real ids from
  `citrusops.facts/fruit-classes`."
  {"orchard-ehime-01"     {:id "orchard-ehime-01"     :name "愛媛 第一区画"   :fruit-class "orange"}
   "orchard-hiroshima-02" {:id "orchard-hiroshima-02" :name "広島 第二区画"   :fruit-class "lemon"}
   "orchard-okinawa-03"   {:id "orchard-okinawa-03"   :name "沖縄 第三区画"   :fruit-class "lime"}
   "orchard-shizuoka-04"  {:id "orchard-shizuoka-04"  :name "静岡 第四区画"   :fruit-class "grapefruit"}})

(def ^:private unregistered-orchard
  "Deliberately NOT seeded -- exercises the `:orchard-not-registered`
  HARD hold, which never reaches a human."
  "orchard-unregistered-99")

(def ^:private supervised
  "Phase 2 (reduced supervision): a Governor-clean routine op commits;
  always-escalate ops and soft-gate hits still reach a human."
  {:actor-id "citrus-ops-01" :role :orchard-operator :phase :phase-2})

(def ^:private simulation
  "Phase 0 (simulation): NOTHING autonomously commits, even when the
  Governor is clean."
  {:actor-id "citrus-ops-01" :role :orchard-operator :phase :phase-0})

(def ^:private scenario
  "[label request context] triples, executed in order. Covers one full
  clean lifecycle, every HARD hold this Governor can raise, the
  always-escalate crop-health gate, the supply cost threshold and the
  phase-0 rollout gate."
  [;; --- orchard-ehime-01: one full clean lifecycle -------------------
   ["e01-record"
    {:op :log-orchard-record :orchard-id "orchard-ehime-01"
     :record-type "harvest" :count 480 :notes "第一期 収穫量記録"}
    supervised]
   ["e01-schedule"
    {:op :schedule-field-operation :orchard-id "orchard-ehime-01"
     :operation-type "pruning" :requested-date "2026-09-01"}
    supervised]
   ["e01-supplies"
    {:op :order-supplies :orchard-id "orchard-ehime-01"
     :category "fertilizer" :cost 320}
    supervised]
   ["e01-health"
    {:op :flag-crop-health-concern :orchard-id "orchard-ehime-01"
     :concern "カンキツグリーニング病(HLB)の疑い"}
    supervised]

   ;; --- HARD holds: none of these ever reaches a human ---------------
   ["unreg-record"
    {:op :log-orchard-record :orchard-id unregistered-orchard
     :record-type "harvest" :count 150 :notes "未登録区画からの記録提出"}
    supervised]
   ["h02-bad-count"
    {:op :log-orchard-record :orchard-id "orchard-hiroshima-02"
     :record-type "harvest" :count 0 :notes "計量エラー"}
    supervised]
   ["o03-equipment"
    {:op :operate-field-equipment :orchard-id "orchard-okinawa-03"
     :notes "散布機の直接操作要求"}
    supervised]
   ["s04-spray"
    {:op :finalize-spray-application :orchard-id "orchard-shizuoka-04"
     :notes "散布適用の最終決定要求"}
    supervised]
   ["h02-unknown-op"
    {:op :apply-agrochemical-directly :orchard-id "orchard-hiroshima-02"
     :notes "allowlist外の操作要求"}
    supervised]

   ;; --- soft gates: reach a human --------------------------------------
   ["o03-supplies-over"
    {:op :order-supplies :orchard-id "orchard-okinawa-03"
     :category "equipment" :cost 1800}
    supervised]
   ["s04-phase0-record"
    {:op :log-orchard-record :orchard-id "orchard-shizuoka-04"
     :record-type "brix-test" :count 62 :notes "phase-0 試行"}
    simulation]])

(def ^:private phases [:phase-0 :phase-1 :phase-2 :phase-3])

(def ^:private probe-requests
  "One clean, registered-orchard request per recognized op, used to probe
  what each rollout phase actually does. Every parameter is inside the
  Governor's limits (positive quantity, under-threshold cost) so the only
  variables are the op and the phase."
  {:log-orchard-record       {:op :log-orchard-record :orchard-id "orchard-ehime-01"
                              :record-type "harvest" :count 100}
   :schedule-field-operation {:op :schedule-field-operation :orchard-id "orchard-ehime-01"
                              :operation-type "pruning" :requested-date "2026-09-01"}
   :flag-crop-health-concern {:op :flag-crop-health-concern :orchard-id "orchard-ehime-01"
                              :concern "定期観察"}
   :order-supplies           {:op :order-supplies :orchard-id "orchard-ehime-01"
                              :category "seedling" :cost 100}
   :operate-field-equipment  {:op :operate-field-equipment :orchard-id "orchard-ehime-01"}
   :finalize-spray-application {:op :finalize-spray-application :orchard-id "orchard-ehime-01"}})

(defn- phase-probe!
  "Runs the REAL actor once per (op, phase) pair and records the
  disposition and phase/governor reason it actually returned. This is not
  a re-description of `citrusops.phase` -- it is 24 more actor runs. They
  are kept out of the audit ledger because they are a capability probe,
  not part of the operating scenario. Safe to run against the same store:
  this actor's Store is read-only (nothing is written back to it)."
  [st]
  (let [actor (operation/build st)]
    (into (sorted-map)
          (for [op (sort-by name governor/all-recognized-ops)]
            [op (into (sorted-map)
                      (for [ph phases]
                        (let [request (get probe-requests op)
                              {:keys [disposition audit]} (actor request (assoc supervised :phase ph))
                              f (last audit)]
                          [ph {:disposition disposition
                               :reason (or (:reason f) (:phase-reason f))}])))]))))

(defn run-demo!
  "Seeds a fresh store, builds the REAL actor (`citrusops.operation/build`)
  and executes `scenario` through it.

  `orchard-ehime-01` clears a full clean lifecycle at phase 2: an
  orchard record commits, a pruning schedule commits, a within-threshold
  fertilizer order commits, and a citrus-greening (HLB) crop-health flag
  ALWAYS escalates to a human (`citrusops.governor/always-escalate-ops`)
  -- the actor never resolves a crop-health call itself.

  Five proposals are HARD-held by the Governor and never reach a human:
  a record for an orchard block that is not registered in the Store; a
  record whose logged quantity is not positive; a request to directly
  operate field equipment; a request to finalize a spray application;
  and an op outside the closed allowlist. Two more reach a human by soft
  gate: an equipment order above its category cost threshold, and a
  Governor-clean record submitted at phase 0, where nothing may commit.

  Returns {:store :runs :ledger :subjects} -- `:ledger` is the
  concatenation of the `:audit` facts each real run returned, in order."
  []
  (let [st (store/mem-store {:initial-orchards seeded-orchards})
        actor (operation/build st)
        runs (vec (map-indexed
                   (fn [i [label request context]]
                     (assoc (actor request context)
                            :step (inc i) :label label
                            :request request :context context))
                   scenario))]
    {:store st
     :runs runs
     :ledger (vec (mapcat :audit runs))
     :phase-probe (phase-probe! st)
     ;; subjects in first-appearance order -- taken from what actually ran
     :subjects (vec (distinct (map #(get-in % [:request :orchard-id]) runs)))}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-name [v] (if (keyword? v) (name v) (str v)))

(defn- td [& cells] (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- fact-subject [f] (or (:subject f) (:orchard-id f)))

(defn- last-fact-for [ledger subject]
  (last (filter #(and (= subject (fact-subject %))
                      (not= :advisor-proposal (:t %)))
                ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (case (:t f)
      :committed "<span class=\"ok\">committed</span>"
      :governor-hold (str "<span class=\"critical\">HARD hold &middot; "
                          (esc (kw-name (or (first (:basis f)) :unknown))) "</span>")
      :approval-requested (str "<span class=\"warn\">awaiting human approval &middot; "
                               (esc (kw-name (or (:reason f) :unspecified))) "</span>")
      "<span class=\"muted\">no activity</span>")))

(defn- registration-cell [st subject]
  (if-let [o (store/registered-orchard st subject)]
    (str "<span class=\"ok\">registered</span> &middot; " (esc (:name o)))
    "<span class=\"critical\">not registered</span>"))

(defn- fruit-cell [st subject]
  (if-let [o (store/registered-orchard st subject)]
    (let [c (facts/fruit-class-by-id (:fruit-class o))]
      (str (esc (:name c)) " <code>" (esc (:fruit-class o)) "</code>"))
    "<span class=\"muted\">&mdash;</span>"))

(defn- orchard-row [st ledger subject]
  (td (str "<code>" (esc subject) "</code>")
      (registration-cell st subject)
      (fruit-cell st subject)
      (status-cell ledger subject)))

;; --- audit ledger ---------------------------------------------------

(defn- fact-detail [{:keys [t basis reason violations proposal-summary summary]}]
  (case t
    :advisor-proposal (esc proposal-summary)
    :governor-hold (str "<span class=\"critical\">"
                        (esc (str/join ", " (map kw-name basis))) "</span> &middot; "
                        (esc (str/join " / " (map :detail violations))))
    :approval-requested (str "<code>" (esc (kw-name reason)) "</code>")
    :committed (str (esc summary)
                    (when (seq basis)
                      (str " &middot; <code>" (esc (str/join ", " (map kw-name basis))) "</code>")))
    ""))

(defn- ledger-row [{:keys [t op confidence] :as f}]
  (td (str "<code>" (esc (kw-name t)) "</code>")
      (str "<code>" (esc (kw-name (or op :n-a))) "</code>")
      (str "<code>" (esc (fact-subject f)) "</code>")
      (if (some? confidence) (esc confidence) "<span class=\"muted\">&mdash;</span>")
      (fact-detail f)))

;; --- committed records ----------------------------------------------

(defn- value-cell [v]
  (if (map? v)
    (str/join "<br>" (map (fn [[k val*]] (str "<code>" (esc (kw-name k)) "</code> " (esc val*)))
                          (sort-by (comp kw-name key) v)))
    (esc v)))

(defn- record-row [{:keys [request record]}]
  (td (str "<code>" (esc (kw-name (:op request))) "</code>")
      (str "<code>" (esc (str/join "/" (:path record))) "</code>")
      (str "<code>" (esc (kw-name (:effect record))) "</code>")
      (value-cell (:value record))))

;; --- gates, read out of the live governor/facts vars ------------------

(defn- gate-label [op]
  (cond
    (contains? governor/blocked-ops op)
    "<span class=\"critical\">HARD block &middot; permanent, never escalates</span>"
    (contains? governor/always-escalate-ops op)
    "<span class=\"warn\">ALWAYS human sign-off &middot; never auto at any phase</span>"
    :else
    "<span class=\"ok\">auto-commit when the Governor is clean (phase &ge; 1)</span>"))

(defn- gate-row [op]
  (td (str "<code>" (esc (kw-name op)) "</code>") (gate-label op)))

(defn- threshold-row [{:keys [id name cost-threshold]}]
  (td (str "<code>" (esc id) "</code>") (esc name) (esc cost-threshold)))

;; --- rollout phase matrix, from the real per-phase actor probe --------

(defn- phase-cell [{:keys [disposition reason]}]
  (str (case disposition
         :commit "<span class=\"ok\">commit</span>"
         :escalate "<span class=\"warn\">escalate</span>"
         :hold "<span class=\"critical\">HARD hold</span>"
         (str "<span class=\"critical\">" (esc (kw-name disposition)) "</span>"))
       (when reason (str "<br><code>" (esc (kw-name reason)) "</code>"))))

(defn- phase-row [[op by-phase]]
  (apply td (str "<code>" (esc (kw-name op)) "</code>")
         (map #(phase-cell (get by-phase %)) phases)))

;; --- document ---------------------------------------------------------

(defn- section [title lead headers rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (apply str (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn render
  "Renders the full operator-console document from the result of
  `run-demo!` (or any other real scenario run through the actor)."
  [{:keys [store ledger runs subjects phase-probe]}]
  (let [recognized (sort-by kw-name governor/all-recognized-ops)
        committed (filter :record runs)
        holds (filter #(= :governor-hold (:t %)) ledger)]
    (str
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-0123 &middot; citrus-fruit-growing operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Growing of citrus fruits (ISIC 0123) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · crop-health concerns always reach a human · field-equipment operation and spray-application finalization permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"
     "  <p class=\"muted\">Build-time snapshot generated by <code>citrusops.render-html</code>"
     " (<code>clojure -M:dev:render-html</code>) by actually executing "
     "<code>citrusops.operation</code> → <code>citrusops.governor</code> → "
     "<code>citrusops.phase</code> against a freshly seeded <code>citrusops.store</code>. "
     "This run produced <strong>" (count runs) "</strong> operations, <strong>"
     (count ledger) "</strong> audit facts, <strong>" (count committed)
     "</strong> committed records and <strong>" (count holds)
     "</strong> HARD Governor holds. Deterministic — no timestamps, no randomness.</p>\n"

     (section "Orchard blocks in this run"
              (str "Registration is answered by the real <code>citrusops.store</code>; "
                   "a block that is not registered can never be proposed against "
                   "(<code>orchard-not-registered</code> is a HARD hold).")
              ["Orchard/block" "Registration" "Fruit class" "Last disposition"]
              (map (partial orchard-row store ledger) subjects))

     (section "Action gate (Citrus Operations Governor)"
              (str "Read from the live <code>citrusops.governor</code> vars, not re-typed. "
                   "HARD holds cannot be overridden and never escalate. Confidence floor: <code>"
                   (esc governor/confidence-floor) "</code>.")
              ["Op" "Gate"]
              (map gate-row recognized))

     (section "Supply cost thresholds"
              (str "From <code>citrusops.facts/supply-categories</code>. An order proposal "
                   "above its category threshold escalates for grower/orchard-manager "
                   "sign-off; an unknown category falls back to <code>"
                   (esc facts/default-cost-threshold) "</code>.")
              ["Category" "Name" "Escalation threshold"]
              (map threshold-row (sort-by :id (vals facts/supply-categories))))

     (section "Rollout phase gate"
              (str "Every cell is the disposition the REAL actor returned when the same "
                   "clean, registered-orchard request was run at that phase — "
                   (* (count recognized) (count phases))
                   " extra actor runs at build time, not a description of "
                   "<code>citrusops.phase</code>. Note that no phase ever unlocks a HARD "
                   "block, and a crop-health concern escalates at every phase because the "
                   "Governor, not the phase gate, marks it high-stakes. Default phase: "
                   "<code>" (esc (kw-name phase/default-phase)) "</code>.")
              (cons "Op" (map #(str "<code>" (esc (kw-name %)) "</code>") phases))
              (map phase-row phase-probe))

     (section "Committed records (this run)"
              "What each auto-committed operation actually wrote to the SSoT."
              ["Op" "Path" "Effect" "Value"]
              (if (seq committed)
                (map record-row committed)
                [(td "<span class=\"muted\">no committed records in this run</span>" "" "" "")]))

     (section "Audit ledger (this run)"
              (str "Append-only decision-fact log — every advisor proposal, Governor hold, "
                   "approval request and commit this scenario produced, in order. "
                   "Shown verbatim: <code>citrusops.operation</code> currently labels "
                   "<em>every</em> high-stakes escalation <code>always-escalate</code>, so the "
                   "over-threshold <code>order-supplies</code> on <code>orchard-okinawa-03</code> "
                   "carries that reason rather than a cost-specific one. That is the actor's "
                   "real output and is reproduced here rather than corrected.")
              ["Fact" "Op" "Orchard/block" "Confidence" "Detail"]
              (map ledger-row ledger))

     "</main>\n"
     "<footer class=\"muted\">cloud-itonami-isic-0123 · Citrus Orchard Operations Coordination · "
     "AGPL-3.0-or-later · sample data, no real orchard records</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "operations,"
             (count (:ledger result)) "audit facts,"
             (count (filter :record (:runs result))) "committed records,"
             (count (filter #(= :governor-hold (:t %)) (:ledger result))) "HARD holds )")))
