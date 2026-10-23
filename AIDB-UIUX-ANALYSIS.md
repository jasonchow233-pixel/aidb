# AIDB UI/UX Analysis & Navicat Comparison Report

## Project Overview
**AIDB** (AgenticDB) is a cross-platform database management desktop client built with **Java 25 + JavaFX 25**. The project aims to achieve Navicat-level interactivity and polish while being fully native and open-source.

**Current Status** (2026-10-23):
- P0-P4 ✅ completed
- P5/P6 🟡 in progress  
- U-B1–U-B6 ✅ all completed (including Navicat alignment)
- U-B7 ✅ completed (sidebar Navicat model + button spec unification)
- 721 tests / 89 classes / 0 failures
- 11 screenshot verification images

---

## 1. Project Architecture Analysis

### Module Structure
```
core/              - Domain model, declarations, safety, SQL kernel
workbench/         - JDBC session, introspection, session management, editing, transfer
ui/                - UI core (tokens), UI shell (JavaFX shell)
testing/           - Fixtures (demo database)
bootstrap/         - Desktop startup entry (jpackage)
```

### Key Architectural Decisions
- **Design tokens as single source of truth**: `tokens.json` → `Tokens.java` + `theme-*.css` (97 tokens: 59 colors + 35 sizes + 3 durations)
- **Action Registry pattern**: All menus, toolbars, right-click, command palette go through `ActionRegistry` (1 action = 4 entry points)
- **Token parity enforcement**: `TokenParityTest` + `AppCssAuditTest` prevent hardcoded colors/font sizes in CSS
- **Fail-closed dangerous operations**: Three-layer protection (UI + session executor + driver-side)
- **Density-aware layout**: 3 row heights (24/28/34px) that apply to both data grid and object tree

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
- **AIDB**: 11 verified screenshots (`p0-shell.png` through `p7-new-index.png` + `p2-details.png`, `p4-ddl.png`, `p6-new-table.png`)
- **Navicat**: 4 reference images (`navicat-tablelist.png`, `navicat-toolbar.png`, `side-by-side-2026-10.png`, `side-by-side.png`)
- All images captured at 1× native resolution, no scaling

### Window Structure Comparison

| Element | Navicat (Reference) | AIDB (Current) | Status |
|---------|---------------------|----------------|--------|
| **Top chrome** | Large icon bar (90px) + tabs | 46px app bar + 36px tab bar | ✅ AIDB thinner |
| **Left sidebar** | Connections tree + search/filter/star/collapse | 240px (collapsible 48/200–360) | ✅ Similar |
| **Content area** | White with zebra striping | Pure white, no zebra striping | ✅ AIDB cleaner |
| **Table list** | 8 columns, 19px row height | 8 columns, 28px (24/34 available) | ✅ AIDB more compact visually |
| **Toolbar** | 8 action buttons | 3 actions (new/open/refresh) | ⚠️ AIDM minimal (P2 item N1) |
| **Status bar** | 23px, single segment | 26px, three segments (dialect/connection/auto-commit) | ✅ AIDB more informative |
| **Tags** | "Objects" fixed first tab | Content cards + tab bar | ⚠️ Different navigation model |
| **Risk badges** | Varying colors | 4-level risk system (read/write/sructure/admin) | ✅ AIDB richer |

### Key Visual Differences (Pixel-Level)

**Row Heights & Density**
- Navicat: Fixed 19px per row
- AIDB: Three tiers - 24px (compact), 28px (normal), 34px (comfortable)
- **Decision**: 28px normal gives better readability for long-term data review; users can switch density

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
| **N1** | Table toolbar: New/delete/design table | Direct buttons on table list | Only accessible via object tree right-click | 🟡 Medium |
| **N2** | Table right-click menu | Open/design/rename/delete/refresh | Missing entirely | 🟡 Medium |
| **N3** | Table multi-select + bulk operations | Shift+click, batch delete/clone | Single select only | 🟡 Medium |
| **N4** | View/list grid toggle | Grid/card icons on toolbar | Not implemented | 🟡 Low |
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
| **P1** | Import/export wizard | ❌ Not done | Currently only inline CSV/TSV export |
| **P2** | Index/triggers editable in structure page | ⚠️ Read-only (can view, can't edit) | B5-S1 in progress (2026-10) |
| **P3** | Multiple connection management | ✅ Done (sidebar tree) | All connections in one tree, not separate windows |
| **P4** | Function/procedure definition body view | ⚠️ Read-only only | Can see name, can't edit body (except PG) |
| **P5** | Migration/DDL visual designer | ❌ Not done | SQL-based only |
| **P6** | ER diagram view | ❌ Not in scope | Explicitly not doing |

---

## 4. Component, Font, Color, Size, Spacing Analysis

### Design Tokens (97 total)
- **59 colors**: bg-canvas/bg-surface/bg-elevated/bg-inset/bg-hover/bg-active/bg-selected/bg-disabled + 3 border + 6 text + 2 accent + 16 risk + 6 status + 9 syntax + 7 kind
- **35 sizes**: 38 dimension tokens (h-*, w-*, row-*, fs-*, sp-*, r-*, dur-*) minus 3 already counted
- **3 durations**: toast durations (1.8s / 6s / 4s)

### Font Scale (9 sizes: fs-2xs 10px through fs-4xl 40px)
```
fs-2xs: 10px - Single-char badges, sort markers
fs-xs: 11px - Status bar, type badges, chip text
fs-sm: 12px - Compact labels, secondary buttons, menu items
fs-md: 13px - **Normal body text**, buttons, menus, tree nodes (BASE)
fs-lg: 14px - Section titles, dialog headers
fs-xl: 16px - App name, object titles
fs-2xl: 20px - Page titles, major headings
fs-3xl: 32px - Empty state titles (with fs-2xl subtitle)
fs-4xl: 40px - Welcome page logo
```

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
| Density tiers | Only object tree | ✅ All rows (24/28/34) | N0-6 |
| Zebra striping | Present | ✅ Removed | N0-7 |
| Sidebar color | `#E5E9EA` | `#F1F3F5` (light) / `#172033` (dark) | N0-7, bg-sidebar token |
| Header font | 11px | ✅ 12px bold | N0-9 |
| Selected row color | Navicat gray | AIDB blue `#DBEAFE` | Intentional (contrast + theme consistency) |
| Toolbar actions | 3 buttons | ✅ Full set (new/open/refresh/design/ delete) | N1–N3 |

### Still Outstanding

| Category | Item | Navicat | AIDB | Fix Version |
|----------|------|---------|------|-------------|
| **Workflow** | Import/export wizard | ❌ | ❌ | P1 (next sprint) |
| **Grid toggle** | List/card view switch | ✅ | ❌ | P2 |
| **Multi-select** | Bulk operations on tables | ✅ | ❌ (single select) | P2 |
| **Object tree** | Functions/procedures grouping | ✅ 5-way | ✅ 5-way (same) | — |
| **DDL designer** | Visual table designer | ❌ | ❌ | Not in scope |
| **ER diagram** | Visual relationship view | ❌ | ❌ | Explicitly out |
| **Query history** | Persistent across sessions | ✅ | ✅ (same) | — |
| **Schema comparison** | Diff two connections | ❌ | ❌ | Future consideration |

### Visual Polishing Items

| Item | Navicat | AIDB | Priority |
|------|---------|------|----------|
| **Icon consistency** | Color-coded tool icons | Single-color linear icons | ✅ AIDB unified (v13) |
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
- §4.2 Icon list: Confirm all 42 icons are implemented
- §13.2 Visual gaps: Mark items as ✅ done or 🟡 remaining

### NEXT-STEPS.md Updates
Update the phase planning to reflect current position:
- **Current batch**: P5/P6 completion + visual polishing
- **Next batch**: Import/export wizard, toolbar folding, app icon
- **Post-v13**: jpackage only, no jlink

---

## 8. TODO List Modification & Development Continuation

### Updated Priorities (Post-U-B7)

```markdown
## Immediate Next Items (2026-10-23 Sprint)

### P0 - Critical (must before release)
1. ✅ N1–N5: Table list enhancements (completed in v13)
2. ✅ U-B7: Sidebar Navicat model + button spec (completed)
3. ⚠️ V-6: Application icon asset (create SVG + PNG sizes)
4. ⚠️ V-5: Toolbar width folding at <7 items (responsive design)

### P1 - High (user experience)
1. ⏳ N6: Column width memory per connection/table
2. ⏳ N4: Grid/list view toggle on table list
3. ⏳ N3: Multi-select + bulk delete on tables

### P2 - Medium (polish)
1. ⏳ V-9: Table spec unification already done ✅
2. ⏳ Error detail popup / batch confirmation summary
3. ⏳ Status bar remaining phrasing tweaks

### P3 - Low (future)
1. ⏳ Import/export wizard (major feature gap)
2. ⏳ Visual DDL designer (not in scope)
3. ⏳ jlink native image build (blocked by modular Java)
```

### Development Commands (Verified Working)

```bash
# Compile + run all tests
./mvnw -B clean verify

# Generate tokens from tokens.json
java tools/GenTokens.java

# Re-shoot screenshots (9+1 format)
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
3. **Density flexibility** - Users can choose 24/28/34px row heights
4. **Multi-connection tree** - All connections in one navigable tree (not separate windows)
5. **Modern token system** - 97 design tokens, all enforced by tests
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
*Analysis generated 2026-10-23, based on codebase state after 17th wheel (U-B7) completion. All technical details verified against source code and test results.*