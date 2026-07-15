# Business Model: Citrus Orchard Operations Coordinator

## Classification

- Repository: `cloud-itonami-isic-0123`
- ISIC Rev. 4: `0123`
- Industry: Growing of citrus fruits
- Social impact: food-security, rural-employment, agricultural-sustainability

## Customer

- Small-to-medium orange, lemon, lime, and grapefruit growers
- Citrus orchard management companies
- Cooperative packhouses/exporters needing grower-side records
- Contract-farming operations coordinating multiple smallholder orchards

## Offer

- Orchard/block record-keeping (planting, harvest yield, brix testing)
- Field-operation coordination (pruning/spraying/irrigation/harvest scheduling)
- Crop health and pest/disease tracking (e.g. citrus greening/HLB, canker)
- Supply procurement coordination
- Audit trail and transparency

## Revenue

- SaaS subscription (per-hectare-per-month pricing)
- Supply chain integration fees
- API access for agronomist partners
- Data analytics and reporting add-ons

## Trust Controls

- No direct field-equipment operation without human sign-off
- No finalizing spray-application decisions
- All field-operation recommendations are proposals, not commands
- Orchard/block registration is required before any operation
- All crop health concerns are automatically escalated
- High-cost supply orders require approval
- Audit ledger is append-only and never editable

## What we NOT do

- **Spray-application decisions** — the agronomist/grower decides application
- **Crop health/welfare decisions** — the grower decides response actions
- **Economic decisions** (harvest timing, replanting) — remain human authority
- **Direct field-equipment operation** — the robot manages records and logistics only

## Supported Operations

### Orchard Record Logging
- Planting counts and block layout
- Harvest weight and yield tracking
- Brix (sugar content) testing
- Health status notes (logging only, not decision-making)

### Field-Operation Coordination
- Schedule pruning, spraying, irrigation, harvest
- Track completed field-operation results
- Propose follow-up field work (not order it directly)

### Crop Health Concern Escalation
- Flag suspected pest pressure (e.g. citrus greening/HLB)
- Report fungal disease, canker, or frost-damage observations
- Automatic escalation to grower/agronomist

### Supply Procurement
- Seedling orders
- Fertilizer orders
- Equipment procurement
- Cost threshold escalation for large orders
