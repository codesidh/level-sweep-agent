---
name: architecture-diagrams
description: Design system for LevelSweepAgent architecture diagrams and enterprise documents. Locks the colour palette, typography, iconography, layout grid, and SVG conventions used by every diagram in `docs/architecture/`. Use when (a) generating or editing any SVG, PNG, or HTML/CSS architecture diagram for LevelSweepAgent, (b) producing a new enterprise architecture document, slide deck, or executive one-pager, or (c) reviewing a diagram for visual consistency before it ships to Enterprise Architecture / CIO. Triggers on "architecture diagram", "tech stack diagram", "EA deck", "CIO review", "system context", "architecture HTML", "C4 diagram", "Netflix-style stack".
---

# LevelSweepAgent — Architecture Diagram Design System

**Inspired by**: Netflix Tech Stack visual language (`references/netflix-tech-stack.png`) — dark canvas, dashed white "rooms", coloured tech badges, red flow arrows.
**Adapted for**: regulated trading-system audience (Enterprise Architecture, CIO, compliance reviewers, broker-dealer counsel).

> Use this skill **every time** a diagram or formal document is produced for `docs/architecture/`. The point is uniformity — a CIO who has seen one of our diagrams should recognise the next one before reading a single label.

---

## 1. Hard Rules

1. **Self-contained artifacts.** Every architecture deliverable in `docs/architecture/` must render with no external CDN, font, or asset call. Inline all CSS, all SVG, base64-embed any raster.
2. **Dark canvas.** All diagrams use the `Canvas Black` background (#0B0E1A) — never white. Reason: matches the Netflix reference and makes coloured badges pop on the projector.
3. **Dashed-white "rooms".** Logical groupings (DevOps, Hot Path, Data, etc.) are enclosed in a `1.5px stroke-dasharray="6 6"` white rectangle, 24px corner radius, 32px internal padding. Every room has a bold white sans-serif label inside the upper-left, ~28px font.
4. **Red is reserved for flow arrows.** Never use `#E63946` for fills, badges, or section labels — it is the visual "this thing flows that way" signal. Backwards/feedback flows use the same red but with `stroke-dasharray="4 4"`.
5. **No emoji in formal documents.** Glyphs only. The artifact may be re-keyed by a compliance vendor and emoji rendering is unreliable.
6. **A4-print safe.** All HTML documents include a `@page { size: A4; margin: 18mm 16mm; }` rule and use page-break-aware sectioning so a print-to-PDF gives a properly paginated PDF.

---

## 2. Colour Palette

| Role | Hex | Use |
|---|---|---|
| Canvas Black | `#0B0E1A` | Diagram and document background |
| Panel | `#131829` | Card / table-row alt / sidebar fill |
| Border Subtle | `#2A3148` | Table grid, panel borders, dividers |
| Border Strong | `#FFFFFF` | Dashed "room" outlines, header underline |
| Text Primary | `#F0F4F8` | Body text, room labels |
| Text Secondary | `#94A3B8` | Captions, footnotes, axis labels |
| Text Muted | `#64748B` | Disabled / out-of-scope items |
| Accent Cyber Blue | `#00D4FF` | Hot path, latency budgets, primary CTAs |
| Accent Gold | `#FFB800` | Finance / money flow / risk budget |
| Accent Bull | `#00C896` | OK, healthy CB, profit, ALLOW decisions |
| Accent Bear | `#FF4757` | Halt, breached, loss, VETO decisions |
| Flow Red | `#E63946` | All directional arrows (Netflix parity) |
| Brand Indigo | `#6366F1` | Multi-tenant boundaries, AI overlay |
| Phase A Tint | `#00C89622` | Phase A scope shading (12% opacity) |
| Phase B Tint | `#6366F122` | Phase B scope shading (12% opacity) |

CSS variables to copy verbatim:

```css
:root {
  --canvas: #0B0E1A; --panel: #131829;
  --border-subtle: #2A3148; --border-strong: #FFFFFF;
  --text: #F0F4F8; --text-secondary: #94A3B8; --text-muted: #64748B;
  --cyber: #00D4FF; --gold: #FFB800;
  --bull: #00C896; --bear: #FF4757;
  --flow: #E63946; --indigo: #6366F1;
}
```

---

## 3. Typography

| Token | Stack | Weight | Use |
|---|---|---|---|
| `display` | `"Inter", "Segoe UI", system-ui, sans-serif` | 800 | Cover title, section H1 |
| `heading` | `"Inter", "Segoe UI", system-ui, sans-serif` | 700 | H2 / H3, room labels |
| `body` | `"Inter", "Segoe UI", system-ui, sans-serif` | 400/500 | Paragraph, captions |
| `mono` | `"JetBrains Mono", "Cascadia Code", Consolas, monospace` | 500 | Code, FSM transitions, latency tables |

System-fallback only — no Google Fonts request. Weights and sizes:

| Element | Size | Line-height |
|---|---|---|
| Cover title | 72px | 1.05 |
| H1 (section) | 32px | 1.2 |
| H2 | 22px | 1.3 |
| H3 | 17px | 1.4 |
| Body | 14px | 1.55 |
| Caption | 12px | 1.5 |
| Diagram room label | 28px | 1 |
| Diagram badge text | 13–15px | 1 |

---

## 4. Iconography & Tech Badges

Replace third-party logos with **stylised glyph badges** to keep the artifact self-contained and trademark-clean.

**Badge anatomy**:

```
┌─────────────────────────┐
│  [glyph]   Label text   │  ← 56–64px tall, 12px corner radius
│            (sublabel)   │
└─────────────────────────┘
```

| Component class | Glyph | Fill | Reason |
|---|---|---|---|
| Java/JVM service | barred-J monogram | gold on panel | Recognisable, copyright-safe |
| Quarkus hot service | lightning-bolt + "Q" | cyber blue | Hot path = blue throughout |
| Spring Boot cold service | leaf | bull green | Spring's leaf, abstracted |
| Kafka topic | three stacked bars | flow red | Topic = stream of events |
| MS SQL | cylinder + grid | gold | "System of record" = money colour |
| MongoDB | leaf cylinder | bull green | Document = organic / read model |
| SQLite | mini cylinder | text-secondary | Ephemeral / per-pod |
| Anthropic Claude | star burst | indigo | AI overlay = indigo throughout |
| Alpaca (broker) | mountain peak (alpaca silhouette) | gold | Broker = money colour |
| Auth0 | shield + key | indigo | Identity = indigo |
| Azure resource | hexagon | cyber blue | Cloud primitive |
| Kubernetes | seven-spoke wheel | cyber blue | Infra primitive |
| Terraform | T-block | indigo | IaC |
| GitHub Actions | rounded square + tick | bull green | CI/CD success-state |

Icon source: hand-rolled SVG paths included inline. Do not pull from CDNs / icon libraries — copyright-clean only.

---

## 5. Diagram Types & When to Use

| Type | When | Anchor |
|---|---|---|
| **Tech-stack pyramid** | Executive overview, the "Netflix-style" centerpiece | Always slide 1 of an EA deck |
| **System context (C4-L1)** | Showing the product + its external dependencies (broker, IDP, AI vendor, calendar) | Section 2 of EA doc |
| **Container view (C4-L2)** | Service decomposition with topology lines | Section 3 — primary detail |
| **Hot-path sequence** | Bar-tick → indicator → signal → veto → order; with latency budget overlay | Latency-budget chapter |
| **Trade-saga choreography** | Saga steps + compensations, FSM transitions | Resilience chapter |
| **Data flow (event-storm style)** | Topics, retention, key-by-tenant boundaries | Data chapter |
| **Deployment topology** | Azure regions, AKS node pools, networking | Operations chapter |
| **Phased roadmap (gantt-like)** | Phase 0 → 11 with soak gates | Closing chapter |
| **Risk heatmap (4×4)** | Likelihood × Impact, mitigation owner | Risk chapter |

Every diagram must include in the lower-right caption strip:
`v{document_version} · {section_short_name} · {YYYY-MM-DD}`

---

## 6. Layout Grid

- 12-column grid, 16px gutter, max content width 1180px on screen / 178mm in print.
- Diagrams render full-bleed at 1180×width with `viewBox` in absolute pixels so they scale crisply when printed.
- Inter-room spacing: 24px vertical, 16px horizontal.
- Rooms span columns 1–6 / 7–12 / 1–12; never an odd fraction (CIO eyes catch off-grid items).

---

## 7. Document Structure (every formal doc must hit these)

A LevelSweepAgent enterprise architecture deliverable contains, in order:

1. **Cover** — title, subtitle, version, classification banner ("Restricted — Phase A"), document owner, date.
2. **Document control** — version history, approvers, distribution list, related ADRs.
3. **Executive summary** — ≤ 1 page, the "what / why / how / when / cost / risk" in 6 paragraphs.
4. **Business context** — the strategic driver, regulatory backdrop, target operating model.
5. **Architecture principles** — bulleted, each with one-sentence rationale.
6. **Tech stack diagram** (the Netflix-style centerpiece).
7. **Logical architecture** — system context + container view.
8. **Component catalog** — table of services with tier / tech / storage / SLO.
9. **Domain views** — AI, Data, Hot Path / Latency, Resilience, Security, Compliance, Observability, DevOps, Deployment.
10. **Phased roadmap** — phases with code & soak gates.
11. **Cost model** — Phase A monthly, Phase B per-tenant unit economics.
12. **Risk register** — top 10 risks with owner + mitigation + residual.
13. **Glossary**.
14. **Appendices** — ADR index, reference docs, change log.

---

## 8. Phase-Aware Visual Conventions

A diagram must visually distinguish what is **active in Phase A** from what is **gated for Phase B**. Conventions:

| Element | Phase A | Phase B (gated) |
|---|---|---|
| Component fill | Solid panel + bull-green status pip | 12% indigo wash + lock-glyph in corner |
| Text label | Normal | Italic + " (Phase B)" suffix |
| Connection line | Solid flow-red | Dashed flow-red, 4-4 dash array |
| Room outline | White dashed | Indigo dashed |

Reason: stakeholder must be able to glance at a diagram and answer "what runs today?" without reading captions.

---

## 9. Compliance Watermark (if doc is shared externally)

Add to `<header>`:

```
RESTRICTED · Phase A · Single-tenant operation · Pre-RIA review
Distribution: Owner, Enterprise Architecture, CIO Office, External Counsel
```

Watermark on print: 6° rotation, 96px Inter 800 at 6% opacity diagonal across each page.

---

## 10. Quality Checklist Before Shipping

Before any architecture artifact is sent to EA / CIO:

- [ ] Renders correctly with internet **disabled** (no external font/CDN call)
- [ ] Prints to PDF on A4 with no clipped diagrams
- [ ] Every diagram has the version/section/date caption
- [ ] No emoji, no orange `box-shadow`, no rainbow gradients
- [ ] Tenant-isolation boundaries drawn explicitly on every container view
- [ ] Phase A vs Phase B distinguished per §8
- [ ] At least one diagram shows the **AI's lack of order-write capability** — this is the headline guardrail and must be visually obvious
- [ ] At least one diagram shows the **fail-closed Risk FSM HALTED** path
- [ ] Every external dependency has a circuit-breaker icon on its connection
- [ ] Glossary includes every acronym used in any diagram label
- [ ] ADRs referenced where an architectural choice would otherwise look arbitrary (JDK distro, broker, AI provider…)

---

## 11. Output Formats

| Audience | Format | Size target |
|---|---|---|
| EA deep-read | `LevelSweepAgent-Architecture-v{N}.html` (single file) | ≤ 600 KB inc. inline SVGs |
| CIO 30-min review | Print-to-PDF the HTML | ≤ 25 pages |
| Walking deck | Subset of diagrams exported as `.svg` to `docs/architecture/assets/` | n/a |
| Slack / email tease | The cover + executive summary as a 2-page extract | ≤ 200 KB |

The single-HTML format is the source of truth — everything else is derived.

---

## 12. When NOT To Use This Skill

- Writing user-facing UI mockups → that is product design, not enterprise architecture
- Internal hot-path tuning notes → those live as ADRs, not diagrams
- Sequence diagrams in a code review → use mermaid in markdown, this skill is overkill
