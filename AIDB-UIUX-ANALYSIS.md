# AIDB UI/UX Analysis & Navicat Comparison Report

## Project Overview
**AIDB** (AgenticDB) is a cross-platform database management desktop client built with **Java 25 + JavaFX 25**. The project aims to achieve Navicat-level interactivity and polish while being fully native and open-source.

**Current Status** (recalibrated 2026-10-26, round 29 — this report is a snapshot; `NAVICAT-TODO.md` §0/§1 remains the single source of truth):
- P0-P4 ✅ completed
- P5/P6 ✅ completed (rounds 24-25: connection groups / failure tests / sequences & DEFAULT-COMMENT-CHECK read-back, history with connection & table, typed import, three-tier export, xlsx streaming)
- U-B1–U-B7 ✅ all completed (including Navicat alignment)
- Task brief V1.0 (round 27) ✅ — Lucide vector icons, unified control layer, §六 index dialog rebuilt, dialog skeleton unified
- Round 28 (2026-10-26) ✅ — **V-6 packaging icon chain closed** (`GenPackIcons` → `.ico`/`.icns` + `package.sh --icon`), **§6.7 dialog button order aligned to Navicat app-wide** (`Cancel` left · primary hugging bottom-right), **C4 closed by ruling** (no 19 px tier; 22/24/28/34 kept)
- Round 29 (2026-10-26) ✅ — **D1/D2/D3 ruled** (grid sits one tier below the density setting via `RowHeights.grid()`; CJK body stays 12 px; table list keeps list + card views), frame review caught and fixed **U28** (dialog size ratchet), **U29** (`ADB_PREFS_RESET=1` never took effect) and **U30** (dialog body padding silently overridden by the generic `.content` rule)
- Design tokens: **98** (not 97); row density: **4 tiers 22/24/28/34, default `normal` (28 px), data grid one tier below (24 px)**
- Verification screenshots: **18** (15 light + 3 dark); all re-shot and visually reviewed after the U28/U29/U30 fixes
- Gate baseline: **971 tests / 125 classes / 0 failures / 48 env-gated skips** (2026-10-26, Windows 10 + JDK 27) — authoritative copy in `NAVICAT-TODO.md` §1

---

## 1. Project Architecture Analysis

### Module Structure
```
core/              - Domain model, safety, SQL kernel (the `declarations` layer was removed in round 21)
workbench/         - JDBC session, introspection, session management, editing, transfer
ui/                - UI core (tokens), UI shell (JavaFX shell)
testing/           - Fixtures (demo database)
bootstrap/         - Desktop startup entry (jpackage)
tools/             - Offline generators + gate bookkeeping (GenTokens, GenLogo, GenPackIcons, TestCount)
```

### Key Architectural Decisions
- **Design tokens as single source of truth**: `tokens.json` → `Tokens.java` + `theme-*.css` (98 tokens: 60 colors + 35 sizes + 3 durations)
- **Action Registry pattern**: All menus, toolbars, right-click, command palette go through `ActionRegistry` (1 action = 4 entry points)
- **Token parity enforcement**: `TokenParityTest` + `AppCssAuditTest` prevent hardcoded colors/font sizes in CSS
- **Fail-closed dangerous operations**: Three-layer protection (UI + session executor + driver-side)
- **Density-aware layout**: 4 row heights (22 / 24 / 28 / 34 px, default `normal` = 28) applied to the object tree and lists; the data grid sits one tier below the setting (`RowHeights.grid()`, D1 ruling: default → 24 px)

### Thread Model
- Single-threaded JDBC execution (`adb-db` shared executor)
- UI updates on JavaFX application thread only
- Connection establishment moved off UI thread (fixes hanging on slow connections)
- Each connection gets its own executor (prevents cross-connection blocking)

### State Management
- `WorkspaceView` caches per-tab state (column widths, sort state, pagination position)
- `ThemeManager` applies hot-switch without restart (class selector swap)
- Window state persistence (size, position, sidebar width, density preference)

---

## 2. UI/UX Screenshot Analysis (vs Navicat Premium Lite 15/17)

### Source Screenshots
- **AIDB**: 18 verified screenshots (`docs/screenshots/p0-shell … p7-new-index`, incl. `p2-cell`, `p2-error`, `p2-overview`, `p6-tables-grid`, and 3 dark frames `p2-users-dark` / `p5-dark` / `p7-new-index-dark`) — all re-shot under pinned `ADB_DENSITY=normal` + isolated prefs node (reset actually works since the U29 fix), so `p0-shell` is byte-reproducible
- **Navicat**: 4 reference images (`navicat-tablelist.png`, `navicat-toolbar.png`, `side-by-side-2026-10.png`, `side-by-side.png`)
- All images captured at 1× native resolution, no scaling

### Window Structure Comparison

| Element | Navicat (Reference) | AIDB (Current) | Status |
|---------|---------------------|----------------|--------|
| **Top chrome** | Large icon bar (90px) + tabs | 46px app bar + 36px tab bar | ✅ AIDB thinner |
| **Left sidebar** | Connections tree + search/filter/star/collapse | 240px (collapsible 48/200–360) | ✅ Similar |
| **Content area** | White with zebra striping | Pure white, no zebra striping | ✅ AIDB cleaner |
| **Table list** | 8 columns, 19px row height | 8 columns (dialect-aware: hidden when no row has a value), default tier **28 px `normal`** (grid surfaces one tier below, `dense 22` available) | ✅ AIDB hides empty columns instead of showing `—` (C1, R19) |
| **Toolbar** | 8 action buttons | Table list: 5 icon buttons + 「导入 / 导出」+ 视图切换 + 搜索; data page: 6 **labeled** actions | ✅ Parity reached (N1/UX-3, R13/R21) |
| **Status bar** | 23px, single segment | **24px** (`h-statusbar`), three segments (dialect/connection/auto-commit) | ✅ AIDB more informative |
| **Tags** | "Objects" fixed first tab | Content cards + tab bar | ⚠️ Different navigation model |
| **Risk badges** | Varying colors | 4-level risk system (read/write/sructure/admin) | ✅ AIDB richer |

### Key Visual Differences (Pixel-Level)

**Row Heights & Density**
- Navicat: Fixed 19px per row
- AIDB: **Four tiers — 22 `dense` / 24 `compact` / 28 `normal` (default) / 34 `comfort`**; data grid sits one tier below the setting (D1 ruling, `RowHeights.grid()`)
- **Decision (round 28 ruling)**: **no 19 px tier.** Navicat's 19 px is its own font-render metric; clipping 12 px body text to 19 px cuts descenders in AIDB. `dense 22` already covers the "make it tighter" ask — **C4 closed**, see §10.3 and `NAVICAT-TODO.md` §36.1

**Font Scaling**
- Navicat: 12px baseline across most areas
- AIDB: 13px normal text, 12px for summary/assistant text, 16px for page titles
- **Decision**: 13px reduces eye strain; 12px preserved for tight spaces (object lists)

**Color Palette**
- Navicat: Cool gray `#E5E9EA` sidebar, blue `#DBEAFE` selected rows
- AIDB: Warm gray `#F1F3F5` sidebar (light), `#172033` (dark); selected blue `#DBEAFE`
- **Decision**: AIDB's sidebar is 12/255 lighter, provides better contrast with content area

**Typography Hierarchy**
| Role | Navicat | AIDB | Notes |
|------|---------|------|-------|
| Page title | 12px bold | 20px bold (`fs-xl`) | AIDB clearer hierarchy |
| Table headers | 12px bold | 12px bold | Consistent |
| Body text | 12px | 13px | AIDB easier to read |
| Badge/label | 10-11px | 10-11px | Consistent |
| Status bar | 11-12px | 11px | Consistent |

---

## 3. Deficiencies vs Navicat (What AIDB Still Lacks)

### Critical Gaps (P0 Priority)

| # | Feature | Navicat | AIDB | Effort |
|---|---------|---------|------|--------|
| **N1** | Table toolbar: New/delete/design table | Direct buttons on table list | ✅ Done — `table-list.new / open / design / drop` (+ transfer entry) registered in `ActionRegistry` | closed |
| **N2** | Table right-click menu | Open/design/rename/delete/refresh | ✅ Done — `showContextMenu(...)` on both list rows and cards | closed |
| **N3** | Table multi-select + bulk operations | Shift+click, batch delete/clone | ✅ Done — `SelectionMode.MULTIPLE` on table list and object home, batch drop with `BatchSummary` result dialog | closed |
| **N4** | View/list grid toggle | Grid/card icons on toolbar | ✅ Done — object home list/card switch, view remembered per connection | closed |
| **N5** | Status bar phrasing | "Selected 1 project" | "Unselected — ms / Selected users — N rows" | ✅ Fixed (v13) |

### Visual/Design Gaps

| # | Feature | Navicat | AIDB | Notes |
|---|---------|---------|------|-------|
| **S1** | Top large icon bar | 12 large icons with text | AIDB uses slim app bar + menu entries | Different philosophy; not essential |
| **S2** | Table metadata columns | Row count, engine, creation date, engine, collation, comments | ✅ AIDB has 8 columns (same) | |
| **S3** | Object tree categories | Tables/Views/Functions/Users/Other | ✅ AIDB has 5 categories (table/view/procedure/function/trigger) | |
| **S4** | Bottom sidebar search | Search + filter + star + collapse | Search at bottom, star separate row | Layout differs |
| **S5** | First tag = "Objects" page | Fixed tab, uncloseable | AIDB uses content cards + tab bar | Navigation model differ |
| **S7** | Window state memory | Remembered position/size | ✅ AIDB saves width/height/sidebar state | |

### Feature Gaps (Roadmap Items)

| Priority | Feature | Status | Notes |
|----------|---------|--------|-------|
| **P1** | Import/export wizard | ✅ Done | `TransferHub` (pick a table) → `ImportWizard` (file/format/target column mapping, typed conversion, bad rows named) and `ExportWizard` (scope/format/target, CSV + xlsx via POI SXSSF, three tiers: page/selection/all) |
| **P2** | Index / foreign key / trigger editable in structure page | ✅ Done | `IndexDialog` (composite column order, dialect-gated type & ASC/DESC), `ForeignKeyDialog`, `TriggerDialog`; unsupported dialects show **no** control instead of a fake option |
| **P3** | Multiple connection management | ✅ Done | All connections in one sidebar tree, per-connection executors; connections carry a `group` label |
| **P4** | Function/procedure definition body view | 🟡 View-only | `RoutinePage` shows the definition body (read-only tab); editing a routine is still not implemented |
| **P5** | Migration / DDL visual designer | 🟡 Partial | Visual table design exists (`NewTableDialog` create + structure page "design table", index/FK/trigger editors, DDL preview). **Cross-connection data migration is not implemented** — SQL-based copy only |
| **P6** | ER diagram view | ❌ Not in scope | Explicitly not doing |
| **V-6** | Installer icon (Win `.ico` / macOS `.icns`) | ✅ Done (round 28) | `tools/GenPackIcons.java` → `brand/app-icon.ico` + `.icns` from `GenLogo` geometry; `scripts/package.sh` passes `--icon` per platform; `PackageIconAuditTest` decodes both containers |
| — | Cold start < 2 s | ❌ Not met | Measured 3.5–3.9 s, floor ≈ 2.4 s (Toolkit + Modena + first CSS/layout pass); progressive rendering is planned, not implemented |
| — | jlink runtime / auto-update / code signing | ⛔ Blocked | RichTextFX & ControlFX auto-modules (jlink), no release channel + offline networks + no certificate (update, signing) |

---

## 4. Component, Font, Color, Size, Spacing Analysis

### Design Tokens (98 total, per `tokens.json` `meta.count`)
- **60 colors**: bg-canvas/bg-surface/bg-elevated/bg-inset/bg-subtle/bg-hover/bg-active/bg-selected/bg-disabled + border layer + text + accent + risk + status + syntax + kind
- **35 sizes**: heights, widths, 4 row densities (`row-dense 22` / `row-compact 24` / `row 28` / `row-comfort 34`), 7 font steps, spacing scale, radii
- **3 durations**: toast durations (1.8s / 6s / 4s)

### Font Scale (7 sizes: fs-2xs 10px through fs-3xl 32px)
```
fs-2xs: 10px - Single-char badges, sort markers
fs-xs: 11px - Status bar, type badges, chip text
fs-sm: 12px - Compact labels, secondary buttons, menu items
fs-md: 13px - **Normal body text**, buttons, menus, tree nodes (BASE)
fs-lg: 14px - Section titles, dialog headers
fs-xl: 16px - App name, object titles
fs-3xl: 32px - Empty state titles
```
_Note: `fs-4xl` (40px) was removed 2026-10-23 and `fs-2xl` (20px) on 2026-10-24 (S-7).
Both had the same root cause — their only consumer was the unreachable welcome page
(`AppShell` always opens the permanent `object-home` tab first), so the tokens were
deleted rather than given an artificial consumer. `Icons.SIZE_BRAND` (40px) went with
them; the brand now lives in the app bar at `Icons.SIZE_TOOLBAR`._

### Color Usage Hierarchy
1. **Background**: Canvas (page), Surface (panels/headers), Elevated (popovers/toast), Inset (inputs/code)
2. **Text**: Primary (readable), Secondary (muted), Tertiary (hints, must meet 4.5:1 contrast), Muted (decorative, <2.5:1), Disabled
3. **Accent**: Primary action color (blue), hover state
4. **Risk**: 4 levels (blue/yellow/red/purple) with bg/border variants
5. **Object kinds**: 7 colors for table/view/function/trigger/procedure/index/event - **only on icons, never on text**

### Spacing System (`sp-*` tokens)
- `sp-1`: 4px - tight spacing
- `sp-2`: 8px - standard
- `sp-3`: 12px - cards, tables
- `sp-4`: 16px - page margins
- `sp-5`: 20px - section gaps
- `sp-6`: 24px - major sections
- `sp-7`: 28px - rarely used

### Layout Patterns

**Five-Area Layout** (all pages):
1. **Application bar** (46px height): Brand + connection pill + global search + 3-4 action buttons
2. **Menu bar**: Native menubar with system menu integration
3. **Sidebar** (240/48px collapsed): Connections tree, filtering options, star for favorites
4. **Content area**: Tab bar + main work area (data grid / query editor / structure)
5. **Status bar** (26px): Three zones - left (dialect), center (selection info), right (auto-commit/read-only/version)

**Page Types** (4 main skeletons):
- **Data grid page**: Table list with filtering, sorting, inline editing
- **Query editor page**: RichTextFX SQL editor with history, execution, plan
- **Structure page**: 6-tab panel (columns/indices/foreign keys/check/triggers/DDL)
- **Connection page**: Connect list with search, favorite, import/export

### Component Specifications

**Buttons**
- Primary: `accent` bg, `text-inverse` fg
- Secondary: `bg-elevated`, `text-primary` fg
- Danger: transparent bg, `error` fg, `border` preserved
- Small: `fs-sm` + `sp-2` padding
- Large: `fs-md` + `sp-3` padding

**Data Grid**
- Row height: 24/28/34px (configured via density)
- Column types: auto-sized, right-align for numbers/dates
- Selection: row-level (batch operations)
- Frozen columns: left/right sync
- Rectangle selection: drag-to-select, copy as TSV/INSERT

**Empty States**
- Title: `fs-xl` (16px bold)
- Description: `fs-sm` (12px) in `text-muted`
- Illustration: 64×64 or 96×96px
- Action button: prominent, secondary primary

**Dialogs**
- Modal: `bg-elevated`, `border-strong` inset
- Padding: `sp-4` vertical, `sp-3` horizontal
- Buttons: bottom bar, destructive = rightmost, default = leftmost (macOS convention)
- Minimum width: 460px, typical: 520-640px
- New table dialog: 900px wide (increased from 640px in v13)

---

## 5. Navicat Comparison: Specific Deficiencies & Fixes

### Already Resolved (2026-10 cycle)

| Area | Was | Now | Evidence |
|------|-----|-----|----------|
| Table list 8 columns | Partial | ✅ Complete | N1–N5 all implemented |
| Object home 9 columns | Missing type column | ✅ Added + type chip | N0-4 |
| Density tiers | Only object tree | ✅ All rows (22/24/28/34, default `normal`; grid one tier below, D1) | N0-6 + UX-2 |
| Zebra striping | Present | ✅ Removed | N0-7 |
| Sidebar color | `#E5E9EA` | `#F1F3F5` (light) / `#172033` (dark) | N0-7, bg-sidebar token |
| Header font | 11px | ✅ 12px bold | N0-9 |
| Selected row color | Navicat gray | AIDB blue `#DBEAFE` | Intentional (contrast + theme consistency) |
| Toolbar actions | 3 buttons | ✅ Full set (new/open/refresh/design/ delete) | N1–N3 |

### Still Outstanding

| Category | Item | Navicat | AIDB | Fix Version |
|----------|------|---------|------|-------------|
| **Workflow** | Import/export wizard | ✅ | ✅ `TransferHub` → `ImportWizard` / `ExportWizard` (typed conversion, bad-row report, CSV + xlsx) | P1 — closed (rounds 24-25) |
| **Grid toggle** | List/card view switch | ✅ | ✅ object home list/card, remembered per connection | P2 — closed (UX-6) |
| **Multi-select** | Bulk operations on tables | ✅ | ✅ `SelectionMode.MULTIPLE` + batch drop with `BatchSummary` | P2 — closed |
| **Dialog buttons** | 取消 left, primary bottom-right | ✅ | ✅ app-wide: `DialogStyles.apply` swaps the O/C slots; 6 self-built footers follow the same rule (`DialogButtonOrderTest`) | closed (round 28) |
| **Object tree** | Functions/procedures grouping | ✅ 5-way | ✅ 5-way (same) | — |
| **Routine body** | Edit function/procedure definition | ✅ | 🟡 view-only (`RoutinePage`) | open |
| **DDL designer** | Visual table designer | ✅ (Navicat 17) | ✅ create + design table dialogs, index/FK/trigger editors | closed |
| **Data migration** | Copy data between connections | ✅ | ❌ not implemented (SQL copy only) | open |
| **ER diagram** | Visual relationship view | ✅ | ❌ | Explicitly out |
| **Query history** | Persistent across sessions | ✅ | ✅ (entries carry connection / hit tables / timestamp) | — |
| **Schema comparison** | Diff two connections | ✅ | ❌ | Future consideration |
| **Packaging** | Installer icon | ✅ | ✅ `.ico` / `.icns` wired into `jpackage --icon` | closed (V-6, round 28) |
| **Startup** | Cold start under ~2 s | ✅ | ❌ measured 3.5–3.9 s (floor ≈ 2.4 s) | open — progressive rendering not yet attempted |

### Visual Polishing Items

| Item | Navicat | AIDB | Priority |
|------|---------|------|----------|
| **Icon consistency** | Color-coded tool icons | Lucide vector sources; neutral chrome icons stay single-color, object/state semantics carry color | ✅ aligned (round 27, U-ICON) |
| **Focus ring** | Visible outline | ✅ `border-focus` token | Consistent |
| **Hover states** | Subtle blue highlight | ✅ `bg-hover` + `text-primary` | Consistent |
| **Corner radius** | 4px standard | ✅ `r-full` for pills, `r-8` for containers | Consistent |
| **Font anti-aliasing** | macOS subpixel | ✅ System font rendering | Consistent |

---

## 6. AIDB Branding & Logo Design

### Current Brand Elements
- **App title**: "AgenticDB" (set in `App.java:20`)
- **Icon**: 64×64 PNG at `ui/adb-ui-shell/src/main/resources/io/agenticdb/ui/app-icon.png`
- **Brand mark**: Database glyph + wordmark in app bar
- **Color scheme**: System-derived (dark `#0F172A`, light `#FFFFFF`)

### Logo Design Recommendations

**Concept**: A stylized database symbol combining:
- A simple table/grid outline representing structured data
- A forward-moving arrow or pulse suggesting "agentic" behavior
- The letters "A" and "D" interlocked within the grid cell

**Technical Specifications**
- **Format**: SVG (scalable) + PNG fallback at multiple resolutions
- **Sizes needed**: 16×16, 32×32, 64×64, 128×128, 256×256
- **Color**: Current scheme works (no change needed), or optional: a distinctive teal `#10B981` accent
- **Background**: Transparent for window icon; solid for splash screen

**Assets to Create**
```
src/main/resources/io/agenticdb/ui/
  app-icon.svg          <- Primary vector logo
  app-icon.png        <- 64×64 raster (existing)
  app-icon@2x.png     <- 128×128
  app-icon@3x.png     <- 192×192
  splash-icon.png     <- 1024×1024 for macOS DMG
```

**Integration Points**
- `App.java:48` - `stage.getIcons().add(...)`
- `scripts/package.sh` - jpackage `--icon`
- About dialog and splash screen
- Sidebar brand mark (currently uses `Icons.Icon.DATABASE`)

### Rebranding Document Updates

All documents referencing "AgenticDB" should remain consistent (the user said "rename to AIDB" but the project is already AgenticDB with AIDB abbreviation). The abbreviation AIDB can be used as:

- **File prefix**: `AIDB-` for new files
- **Short references**: "AIDB" instead of "AgenticDB" in casual text
- **Domain**: `aidb` already used in package `io.agenticdb`

**Already in use**: `NAVICAT-TODO.md`, `AIDB-UIUX-ANALYSIS.md` (this file), `scripts/`

---

## 7. Document Supplementation & Modification

### NAVICAT-TODO.md Updates
The existing `NAVICAT-TODO.md` is the single source of truth for the execution checklist. Key sections to verify/update:

**Current State Table** (from README):
```
P0–P4 ✅ / P5/P6 🟡 / U-B1–U-B6 ✅ / U-B7 ✅
721 conditions / 89 classes / 0 failures
```

**Remaining Items** (from §21):
- X3/X4: Status bar remaining items / orphan actions (🟢 low)
- V-5: Toolbar width folding (🟡 medium)
- V-6: Application icon (🟢 low - needs actual asset)
- V-9: Table spec unification ✅ (already done)
- P7: jlink/jpackage ✅ (done, jlink not feasible)

**Priority Re排序** (§14.9):
```
Already completed this cycle: N1–N5, U-B7, B5-S1, D2/D4, B5-S2/S3/S4
Next: N6 (column width memory), V-5 (toolbar folding), V-6 (app icon)
```

### UI Visual Spec Updates

**`docs/UI-VISUAL-SPEC.md`** sections needing update:
- §2.5 Color contrast: Verify `text-tertiary` values after recent changes
- §3.6 Modernization gaps: Update with current status
- §4.2 Icon list: Confirm all 48 icons are implemented (42 at the time of writing;
  the S-7 `BRAND` mark and later additions brought it to 48 — `IconSystemAuditTest`
  is the gate that keeps the list and the usage in sync)
- §13.2 Visual gaps: Mark items as ✅ done or 🟡 remaining

### NEXT-STEPS.md Updates
Update the phase planning to reflect current position:
- **Current batch**: P5/P6 completion + visual polishing
- **Next batch**: Import/export wizard, toolbar folding, app icon
- **Post-v13**: jpackage only, no jlink

---

## 8. TODO List Modification & Development Continuation

### Updated Priorities (round 28, 2026-10-26)

```markdown
## Closed since the 2026-10-23 snapshot
- ✅ V-6: installer icons — `tools/GenPackIcons.java` emits `brand/app-icon.ico` (7 PNG frames)
       and `brand/app-icon.icns` (12 frames) from `GenLogo` geometry; `scripts/package.sh`
       passes `--icon` per platform; `PackageIconAuditTest` decodes both containers field by field
- ✅ Dialog button order — Navicat layout app-wide (`取消 | 确定`), `DialogButtonOrderTest`
- ✅ N3 / N4 / N6 / error-detail popup / batch summary / import-export wizard / P2-P6 workbench items

## Still open (honest)
1. ❄️ Cold start: 3.5–3.9 s measured vs < 2 s target — progressive rendering (empty shell first,
   then fill) is the only untried lever; floor ≈ 2.4 s (Toolkit + Modena + first CSS/layout pass)
2. 🔧 V-5: toolbar label folding at < 7 visible items — deferred by ruling
3. 📄 Routine body editing (`RoutinePage` is view-only today)
4. 🔁 Cross-connection data migration (SQL copy only)

## Blocked / explicitly out (do not count as done elsewhere)
- ⛔ jlink runtime image (RichTextFX / ControlFX auto-modules)
- 🚫 auto-update (no release channel, offline networks, no signing → "check failed" is
  indistinguishable, and silent download is a supply-chain risk)
- ⛔ code signing / notarization (needs a certificate — external resource)
- ❌ ER diagram, schema comparison — out of scope by decision
```

### Development Commands (Verified Working)

```bash
# Compile + run all tests
./mvnw -B clean verify

# Generate tokens from tokens.json
java tools/GenTokens.java

# Re-shoot screenshots (18 frames, pinned density + isolated prefs node)
./scripts/shots.sh

# Start dev server
./scripts/dev.sh

# Start with specific table open
./scripts/dev.sh users

# Package for distribution
./scripts/package.sh          # default (DMG on macOS)
./scripts/package.sh app-image # unpacked directory only
```

### Test Statistics (Post-v13)
- **Total**: 721 tests / 89 classes / 0 failures
- **New this cycle**: `ObjectDetailsPanelTest` (14), `NewTableDialogTest` (+6), `AppShellTest` (+3)
- **Coverage**: All modules green, including `TokenParityTest` and `AppCssAuditTest`

### Code Quality Gates (Enforced)
1. **No hardcoded colors**: CSS must use `-adb-*` lookups only (`TokenParityTest` enforcement)
2. **No hardcoded font sizes**: Must reference `fs-*` tokens (`AppCssAuditTest` enforcement)
3. **All actions via ActionRegistry**: Menus, toolbars, right-click, ⌘K must share same action instance
4. **Dialog button safety**: Destructive actions use `DestructiveConfirm`; default button is destructive-safe
5. **Token usage**: Every token in `tokens.json` must be referenced in generated code (`TokenParityTest`)

---

## 9. AIDB vs Navicat: Final Assessment

### Where AIDB Excels ✅
1. **Dangerous operation protection** - Three-layer fail-closed design (unique strength)
2. **Four-level risk classification** - Read/write/structure/admin with visual badges
3. **Density flexibility** - Users can choose 22/24/28/34 px row heights (grid one tier below the setting)
4. **Multi-connection tree** - All connections in one navigable tree (not separate windows)
5. **Modern token system** - 98 design tokens, all enforced by tests
6. **No embedded browser** - Pure JDBC, no HTTP ports, native performance
7. **Cross-platform native** - JavaFX on macOS/Windows/Linux, not Electron

### Where Navicat Still Leads 🏆
1. **Import/export wizard** - Guided, step-by-step data movement
2. **Visual DDL designer** - Graphical table/index creation
3. **Comprehensive object browser** - Functions, procedures, events all visible
4. **Session comparison** - Diff two connections' objects/data
5. **Established ecosystem** - Plugins, team sync, cloud integration

### The AIDB Differentiator 🎯
AIDB positions itself as **"the developer's database client"** - prioritizing:
- Scriptability (all actions scriptable via ⌘K / API)
- Safety (three-layer danger protection)
- Customizability (density, theme, density-driven layouts)
- Open source (no vendor lock-in, full source access)

**Verdict**: AIDB v1.0 (2026-10) is feature-par with Navicat Premium Lite 15 for core DB management tasks (browse, query, edit structure), with superior safety mechanisms and modern design token architecture. The remaining gaps are primarily in **wizard-driven workflows** (import/export, DDL design) rather than core capability.

---

## 10. Second-Round Navicat Comparison — Evidence From Screenshots (Round 18, 2026-10-23)

### Method (this round differs from §5)

Round 1 compared *feature lists*. Round 18 treated the **12 AIDB screenshots in `docs/screenshots/`
plus the 4 Navicat references in `docs/screenshots/navicat/` as evidence**: every claim had to be
(a) visible in a pixel, (b) measured programmatically, or (c) traceable to a line of source.
Anything that could not be nailed down all three ways was dropped.

Row pitch was measured with a small Java2D harness (no image libraries are available in this
environment): count non-background pixels per scanline inside a fixed column band, group contiguous
runs into text rows, take the mode of inter-peak gaps. Threshold 200/765 so 1px grid separators
(`≈78/765` from white) are excluded — otherwise separator lines get counted as rows and halve the pitch.

### 10.1 Measured parity

| Surface | AIDB | Navicat Premium Lite 15 | Verdict |
|---|---|---|---|
| Data / object **grid row pitch** | **28.0 px** (default `normal`; 4 consecutive gaps all exactly 28) | **19.0 px** (19 of 26 gaps = 19) | 🔴 AIDB is **+47% looser** — ~32% fewer rows per screen (500px band: 17 vs 26 rows) |
| Sidebar **object-tree pitch** | **24 px** | **22 px** | ✅ parity |
| Table-list columns | 8: `名称220 / 行90 / 数据长度110 / 引擎100 / 创建日期150 / 修改日期150 / 排序规则120 / 注释180` = 1120px preferred | 8, **identical titles, same order** | ✅ at parity (round-13 work holds) |
| Object home columns | 9, same titles as Navicat's object list | 9 | ✅ at parity |
| Density tiers offered | 3 (`24 / 28 / 34`, `RowHeights`, gated by `DensityCoverageTest` on 4 tables) | none exposed (effectively 19) | 🟡 even the **tightest** tier (24) is 26% looser than Navicat |

### 10.2 Six defects the screenshots proved (all fixed this round — detail in `NAVICAT-TODO.md` §28.11)

| ID | What the picture showed | Root cause | Fix |
|---|---|---|---|
| **R18-1** | `p5-connections.png` shows the *objects overview*, not connections | `shots.sh` clicked `#sidebar-connections`, deleted by U-B7 refactor `a353dc1`; `ScreenshotSession.click()` **prints to stderr and keeps shooting**; orphan `SideBar.openConnections()` left behind (0 callers) | retargeted to `#appbar-manage-connections`; dead method removed |
| **R18-2** | 「每页」 box renders **empty** on query + data pages — and, after the first fix, renders as **「2…」** | `select(PageSize.nearest(...).value())` passes a primitive `int` → binds `select(int index)`, not `select(Integer)`. Proven via `javap -c`: `select:(I)V`. Index 200 vs 4 items → no selection. Existing test at `DataGridPageTest:471` **worked around it**. *Continuation:* even with the value selected, pixel-measuring the re-shot image showed text stopping 23px short of the arrow — the ComboBox **button cell** was eating the width, so `prefWidth` 78 → **96** | `select(Integer.valueOf(...))` in `QueryPage` + `DataGridPage` **+ `setPrefWidth(96)`**; both gates gained a `prefWidth >= 90` rendering fence (mutation: back to 78 → red) |
| **R18-3** | Read-only SQL preview says *「输入 SQL，⌘↵ 执行当前语句」* | `setHint()` added in §28.8 for read-only views but only wired into `StructurePage` | `NewTableDialog.previewEditor.setHint(...)` |
| **R18-4** | Window registers 6 icons, only 5 exist | `App.java` asks for `app-icon-512.png`; `GenLogo` writes 512 as `app-icon.png` (by `brand/README.md` convention); `if (url != null)` **silently skips** | removed dead literal |
| **R18-5** | status bar reads **「未选择 — ms」** | `setDuration(-1)` (called in 3 places meaning *"clear"*) rendered a placeholder instead of hiding; the `·` separator would have been stranded | hide segment **and** its separator; hidden by default |
| **R18-6** | query jump field is a bare white box | same control, two implementations: `#data-jump` has prompt+tooltip+accessibleText+`adb-grid__jump`; `#query-result-jump` had only id+width | brought to parity |

> ⭐ **The pattern**: 5 of 6 are *failure designed as silent degradation* — skip the click, skip the
> resource, fail the selection silently, write a placeholder, leave it unconfigured. Runtime tests
> can never catch these (nothing throws). Two new **static audit gates**
> (`ScreenshotScriptAuditTest`, `AppIconResourceAuditTest`) assert *"the thing you reference exists"*.
> All **7 new gates were mutation-tested** (break it → red, restore → green).

### 10.3 Remaining visual gaps found this round

> **Round-19 update:** **C1 and C2 are fixed** — see the ✅ notes in their rows.
> **Round-22 update:** **C7 is fixed** (S-7 ruling 「接 + 删」).
> **Round-28 update:** **C5 is fixed** (S-3: table/view nodes expand into lazily loaded columns),
> **C4 is closed by ruling** (no 19 px tier; four densities 22/24/28/34 with `normal` = 28 as the
> default and the data grid one tier below — D1 ruling, round 29), **C3 is mitigated** (the main area is allowed to shrink so opening the panel no longer
> pushes left-side chrome out of the scene — `AppShellTest` gate; header truncation itself is still
> user-solvable via column resize / column settings).

| ID | Finding | Evidence | Suggested direction |
|---|---|---|---|
| **C1** ✅ **FIXED (R19 S-1)** | **Wall of em-dashes**: on the default SQLite demo, 6 of 8 table-list columns are always `—` — **810 of 1120px preferred width (72%)**; object home likewise 6 of 9 | `p6-tables.png`, `p5-connections.png`; `TableListPage:239-250` creates all columns unconditionally. Note `ObjectHomePage:218` **documents this as deliberate** ("方言拿不到的列显示「—」") | ~~Deliberate ≠ optimal: hide columns with no data source for the dialect, or expose a 「列」 toggle.~~ **Done via a data-driven rule** (not a dialect whitelist): a column is hidden only when *every loaded row* renders the placeholder for it — auto-adapts to new dialects, and a single row with a real value keeps the column (mixed MySQL tables/views). Core columns (名称/行/类型) are never hidden. Gates: `MetadataColumnsTest`(6), `TableListPageTest`+3, `ObjectHomePageTest`+2 — all mutation-tested. |
| **C2** ✅ **FIXED (R19 S-2)** | **Card view is 2/3 dashes** — `notes / 0行 · — / — · — / —` in a 220px card | `p6-tables-grid.png` + `TableListPage` card builder | ~~render only populated lines (icon + name + rows + comment)~~ **Done**: each card line joins only non-placeholder fragments, and a line whose fragments are all placeholders is not rendered at all (no empty placeholder row left behind). Gate: `TableListPageTest.cardViewOmitsPlaceholderLinesOnSqliteLikeData` (mutation-tested) + a MySQL-shaped case asserting real metadata still shows. |
| **C3** 🟡 **MITIGATED (R25)** | **Detail panel squeezes the grid** — headers truncate to `na… em… acti… sco… creat…` | `p2-details.png`; `ObjectDetailsPanel.WIDTH_DEFAULT=300` | ~~overlay instead of push~~ Opening the panel no longer pushes left-side chrome out of the scene (main area may shrink; `AppShellTest` mechanism gate, mutation-verified). Header truncation still resolves by column resize / 「列」 settings |
| **C4** ✅ **CLOSED BY RULING (R28)** | **Row pitch** (see 10.1): 28 vs 19 | measured | Ruling: **no 19 px tier** — keep 22 / 24 / 28 / 34; default tier is `normal` (28) and the data grid sits one tier below (24) per the round-29 D1 ruling (`RowHeights.grid()`). Adding a fifth tier would re-open `DensityCoverageTest` / `ChromeMetricsTest` / menu wiring for ~3 px of gain |
| **C5** ✅ **FIXED (R19 S-3)** | **Tree tables can't expand to columns** — every Navicat table node carries a `>` chevron (crop-verified); AIDB object nodes are leaves | `SideBar.kindGroup()` builds child `TreeItem`s with no children; Navicat crop vs AIDB crop | Table / view nodes expand into lazily loaded columns (`SideBar:1124-1156` — expansion works before the read finishes, a failed read re-arms the retry, cache survives a tree refresh) |
| **C7** ✅ **FIXED (R22 S-7)** | **Welcome page still shows a generic `DATABASE` vector**, not the AIDB mark | `WorkspaceView:137`; the brand round only registered OS window icons (`stage.getIcons()`) | ~~put the brand mark on the welcome page~~ **Ruling was "wire it in + delete the dead code", not either/or**: `AppShell:247` unconditionally `openPermanent(object-home)`, so the welcome page was provably unreachable — three green gates in `WelcomeBrandTest` were testing a page no user ever sees. Instead: added vector `Icons.Icon.BRAND` (data cylinder + AI spark) into the app bar's permanent 26px square (the one brand slot that is actually on screen every frame), deleted `showWelcome()` / `brandMark()` / `BRAND_MARK` / the `.adb-welcome*` CSS / `Icons.SIZE_BRAND` / the unreachable `else` in `AppShell.tabClosed`, and dropped the orphaned `fs-2xl` token (98 tokens now). Gates: `AppBarTest.theBrandMarkIsTheAidbMarkNotAGenericDatabaseIcon`, `BrandMarkAuditTest`(2). Pixel comparison then exposed a **real production bug**: `.adb-icon { -fx-stroke: -adb-text-secondary }` is declared *later* at equal specificity, so the glyph was gray-on-blue and nearly invisible — fixed with the two-class selector `.adb-appbar__mark .adb-appbar__mark-glyph` plus the computed-value gate `AppBarTest.theBrandGlyphKeepsItsContrastAgainstTheGenericIconRule`. All 5 mutations (M1–M5) verified red → restored. |

### 10.4 Recommended order (senior eng + designer view)

1. ~~**C1 + C2 together**~~ ✅ **Done in round 19** (Batch A) — same root cause (list surfaces rendering "nothing to show" as punctuation);
   biggest visible win on the *default first-run experience*, which is SQLite.
2. **C5** — the single most-used Navicat gesture AIDB lacks; medium effort (lazy tree children + a gate).
3. **C3** — real pain when the details panel is open; needs a layout decision first.
4. **C7** — cheap, and closes the branding loop.
5. **C4** — a product call, not an engineering one; ask before building.
6. Non-visual backlog stays as-is: N6 (列宽记忆 + MySQL 字节单位联调),
   import/export wizard, grid/card toggle polish (§29 in `NAVICAT-TODO.md`).

---
*Analysis generated 2026-10-23, based on codebase state after 17th wheel (U-B7) completion. All technical details verified against source code and test results.*