package io.agenticdb.ui;

import java.util.List;

/**
 * 设计令牌 —— 由 {@code tools/GenTokens.java} 从 tokens.json 生成。
 *
 * <p>⛔ 不要手改这个文件。改 tokens.json 后重新生成。
 * <p>颜色字面量只允许出现在这里和 theme-*.css。
 */
public final class Tokens {

    public static final int COUNT = 106;

    /** bg-canvas (color) */
    public static final String BG_CANVAS_DARK = "#0B1220";
    public static final String BG_CANVAS_LIGHT = "#FFFFFF";

    /** bg-sidebar (color) */
    public static final String BG_SIDEBAR_DARK = "#172033";
    public static final String BG_SIDEBAR_LIGHT = "#FFFFFF";

    /** bg-chrome (color) */
    public static final String BG_CHROME_DARK = "#1B2534";
    public static final String BG_CHROME_LIGHT = "#F0F0F0";

    /** bg-surface (color) */
    public static final String BG_SURFACE_DARK = "#1E293B";
    public static final String BG_SURFACE_LIGHT = "#FFFFFF";

    /** bg-subtle (color) */
    public static final String BG_SUBTLE_DARK = "#16213A";
    public static final String BG_SUBTLE_LIGHT = "#F5F5F5";

    /** bg-elevated (color) */
    public static final String BG_ELEVATED_DARK = "#1E293B";
    public static final String BG_ELEVATED_LIGHT = "#FFFFFF";

    /** bg-inset (color) */
    public static final String BG_INSET_DARK = "#172033";
    public static final String BG_INSET_LIGHT = "#F1F5F9";

    /** bg-hover (color) */
    public static final String BG_HOVER_DARK = "rgba(148,163,184,.12)";
    public static final String BG_HOVER_LIGHT = "rgba(0,120,215,.07)";

    /** bg-active (color) */
    public static final String BG_ACTIVE_DARK = "rgba(76,158,234,.24)";
    public static final String BG_ACTIVE_LIGHT = "#C0DCF3";

    /** bg-selected (color) */
    public static final String BG_SELECTED_DARK = "rgba(76,158,234,.30)";
    public static final String BG_SELECTED_LIGHT = "#CDE8FF";

    /** bg-selected-grid (color) */
    public static final String BG_SELECTED_GRID_DARK = "rgba(76,158,234,.24)";
    public static final String BG_SELECTED_GRID_LIGHT = "#C4E0F7";

    /** bg-stripe (color) */
    public static final String BG_STRIPE_DARK = "rgba(148,163,184,.035)";
    public static final String BG_STRIPE_LIGHT = "#F9F9F9";

    /** bg-disabled (color) */
    public static final String BG_DISABLED_DARK = "rgba(148,163,184,.08)";
    public static final String BG_DISABLED_LIGHT = "rgba(148,163,184,.14)";

    /** border (color) */
    public static final String BORDER_DARK = "#2C3B52";
    public static final String BORDER_LIGHT = "#D9D9D9";

    /** border-subtle (color) */
    public static final String BORDER_SUBTLE_DARK = "#223046";
    public static final String BORDER_SUBTLE_LIGHT = "#F0F0F0";

    /** border-focus (color) */
    public static final String BORDER_FOCUS_DARK = "#4C9EEA";
    public static final String BORDER_FOCUS_LIGHT = "#0078D7";

    /** text-primary (color) */
    public static final String TEXT_PRIMARY_DARK = "#E2E8F0";
    public static final String TEXT_PRIMARY_LIGHT = "#1A1A1A";

    /** text-secondary (color) */
    public static final String TEXT_SECONDARY_DARK = "#AEBFD4";
    public static final String TEXT_SECONDARY_LIGHT = "#595959";

    /** text-tertiary (color) */
    public static final String TEXT_TERTIARY_DARK = "#8494AA";
    public static final String TEXT_TERTIARY_LIGHT = "#707070";

    /** text-muted (color) */
    public static final String TEXT_MUTED_DARK = "#64748B";
    public static final String TEXT_MUTED_LIGHT = "#999999";

    /** text-disabled (color) */
    public static final String TEXT_DISABLED_DARK = "#64748B";
    public static final String TEXT_DISABLED_LIGHT = "#B3B3B3";

    /** text-inverse (color) */
    public static final String TEXT_INVERSE_DARK = "#0F172A";
    public static final String TEXT_INVERSE_LIGHT = "#FFFFFF";

    /** accent (color) */
    public static final String ACCENT_DARK = "#4C9EEA";
    public static final String ACCENT_LIGHT = "#0078D7";

    /** accent-hover (color) */
    public static final String ACCENT_HOVER_DARK = "#6FB4F2";
    public static final String ACCENT_HOVER_LIGHT = "#1A86E0";

    /** risk-0-read (color) */
    public static final String RISK_0_READ_DARK = "#4C9EEA";
    public static final String RISK_0_READ_LIGHT = "#0067B8";

    /** risk-1-write (color) */
    public static final String RISK_1_WRITE_DARK = "#FBBF24";
    public static final String RISK_1_WRITE_LIGHT = "#B45309";

    /** risk-2-structure (color) */
    public static final String RISK_2_STRUCTURE_DARK = "#F87171";
    public static final String RISK_2_STRUCTURE_LIGHT = "#DC2626";

    /** risk-3-admin (color) */
    public static final String RISK_3_ADMIN_DARK = "#C084FC";
    public static final String RISK_3_ADMIN_LIGHT = "#7C3AED";

    /** risk-0-bg (color) */
    public static final String RISK_0_BG_DARK = "rgba(76,158,234,.12)";
    public static final String RISK_0_BG_LIGHT = "rgba(0,120,215,.08)";

    /** risk-0-border (color) */
    public static final String RISK_0_BORDER_DARK = "rgba(76,158,234,.32)";
    public static final String RISK_0_BORDER_LIGHT = "rgba(0,120,215,.30)";

    /** risk-1-bg (color) */
    public static final String RISK_1_BG_DARK = "rgba(251,191,36,.12)";
    public static final String RISK_1_BG_LIGHT = "rgba(180,83,9,.08)";

    /** risk-1-border (color) */
    public static final String RISK_1_BORDER_DARK = "rgba(251,191,36,.32)";
    public static final String RISK_1_BORDER_LIGHT = "rgba(180,83,9,.30)";

    /** risk-2-bg (color) */
    public static final String RISK_2_BG_DARK = "rgba(248,113,113,.12)";
    public static final String RISK_2_BG_LIGHT = "rgba(220,38,38,.08)";

    /** risk-2-border (color) */
    public static final String RISK_2_BORDER_DARK = "rgba(248,113,113,.32)";
    public static final String RISK_2_BORDER_LIGHT = "rgba(220,38,38,.30)";

    /** risk-3-bg (color) */
    public static final String RISK_3_BG_DARK = "rgba(192,132,252,.12)";
    public static final String RISK_3_BG_LIGHT = "rgba(124,58,237,.08)";

    /** risk-3-border (color) */
    public static final String RISK_3_BORDER_DARK = "rgba(192,132,252,.32)";
    public static final String RISK_3_BORDER_LIGHT = "rgba(124,58,237,.30)";

    /** success (color) */
    public static final String SUCCESS_DARK = "#4ADE80";
    public static final String SUCCESS_LIGHT = "#15803D";

    /** success-bg (color) */
    public static final String SUCCESS_BG_DARK = "rgba(74,222,128,.12)";
    public static final String SUCCESS_BG_LIGHT = "rgba(21,128,61,.10)";

    /** warning (color) */
    public static final String WARNING_DARK = "#FBBF24";
    public static final String WARNING_LIGHT = "#B45309";

    /** warning-bg (color) */
    public static final String WARNING_BG_DARK = "rgba(251,191,36,.12)";
    public static final String WARNING_BG_LIGHT = "rgba(180,83,9,.10)";

    /** dirty (color) */
    public static final String DIRTY_DARK = "#2DD4BF";
    public static final String DIRTY_LIGHT = "#0F766E";

    /** dirty-bg (color) */
    public static final String DIRTY_BG_DARK = "rgba(45,212,191,.12)";
    public static final String DIRTY_BG_LIGHT = "rgba(15,118,110,.08)";

    /** error (color) */
    public static final String ERROR_DARK = "#F87171";
    public static final String ERROR_LIGHT = "#DC2626";

    /** error-bg (color) */
    public static final String ERROR_BG_DARK = "rgba(248,113,113,.12)";
    public static final String ERROR_BG_LIGHT = "rgba(220,38,38,.10)";

    /** info (color) */
    public static final String INFO_DARK = "#4C9EEA";
    public static final String INFO_LIGHT = "#0067B8";

    /** syn-keyword (color) */
    public static final String SYN_KEYWORD_DARK = "#C4B5FD";
    public static final String SYN_KEYWORD_LIGHT = "#7C3AED";

    /** syn-string (color) */
    public static final String SYN_STRING_DARK = "#5EEAD4";
    public static final String SYN_STRING_LIGHT = "#0F766E";

    /** syn-number (color) */
    public static final String SYN_NUMBER_DARK = "#FCD34D";
    public static final String SYN_NUMBER_LIGHT = "#B45309";

    /** syn-comment (color) */
    public static final String SYN_COMMENT_DARK = "#94A3B8";
    public static final String SYN_COMMENT_LIGHT = "#64748B";

    /** syn-function (color) */
    public static final String SYN_FUNCTION_DARK = "#4C9EEA";
    public static final String SYN_FUNCTION_LIGHT = "#0067B8";

    /** syn-type (color) */
    public static final String SYN_TYPE_DARK = "#7DD3FC";
    public static final String SYN_TYPE_LIGHT = "#0369A1";

    /** syn-ident (color) */
    public static final String SYN_IDENT_DARK = "#E2E8F0";
    public static final String SYN_IDENT_LIGHT = "#0F172A";

    /** syn-param (color) */
    public static final String SYN_PARAM_DARK = "#FDA4AF";
    public static final String SYN_PARAM_LIGHT = "#BE123C";

    /** syn-error (color) */
    public static final String SYN_ERROR_DARK = "#F87171";
    public static final String SYN_ERROR_LIGHT = "#DC2626";

    /** h-appbar (size) */
    public static final int H_APPBAR_DARK = 91;
    public static final int H_APPBAR_LIGHT = 91;

    /** h-toolbar (size) */
    public static final int H_TOOLBAR_DARK = 30;
    public static final int H_TOOLBAR_LIGHT = 30;

    /** h-tabbar (size) */
    public static final int H_TABBAR_DARK = 25;
    public static final int H_TABBAR_LIGHT = 25;

    /** h-statusbar (size) */
    public static final int H_STATUSBAR_DARK = 23;
    public static final int H_STATUSBAR_LIGHT = 23;

    /** h-input (size) */
    public static final int H_INPUT_DARK = 28;
    public static final int H_INPUT_LIGHT = 28;

    /** h-button (size) */
    public static final int H_BUTTON_DARK = 28;
    public static final int H_BUTTON_LIGHT = 28;

    /** w-sidebar-min (size) */
    public static final int W_SIDEBAR_MIN_DARK = 200;
    public static final int W_SIDEBAR_MIN_LIGHT = 200;

    /** w-sidebar (size) */
    public static final int W_SIDEBAR_DARK = 260;
    public static final int W_SIDEBAR_LIGHT = 260;

    /** w-sidebar-max (size) */
    public static final int W_SIDEBAR_MAX_DARK = 360;
    public static final int W_SIDEBAR_MAX_LIGHT = 360;

    /** w-sidebar-collapsed (size) */
    public static final int W_SIDEBAR_COLLAPSED_DARK = 48;
    public static final int W_SIDEBAR_COLLAPSED_LIGHT = 48;

    /** row-dense (size) */
    public static final int ROW_DENSE_DARK = 19;
    public static final int ROW_DENSE_LIGHT = 19;

    /** row-compact (size) */
    public static final int ROW_COMPACT_DARK = 20;
    public static final int ROW_COMPACT_LIGHT = 20;

    /** row-normal (size) */
    public static final int ROW_NORMAL_DARK = 21;
    public static final int ROW_NORMAL_LIGHT = 21;

    /** row-comfort (size) */
    public static final int ROW_COMFORT_DARK = 26;
    public static final int ROW_COMFORT_LIGHT = 26;

    /** fs-2xs (size) */
    public static final int FS_2XS_DARK = 10;
    public static final int FS_2XS_LIGHT = 10;

    /** fs-xs (size) */
    public static final int FS_XS_DARK = 11;
    public static final int FS_XS_LIGHT = 11;

    /** fs-sm (size) */
    public static final int FS_SM_DARK = 12;
    public static final int FS_SM_LIGHT = 12;

    /** fs-md (size) */
    public static final int FS_MD_DARK = 13;
    public static final int FS_MD_LIGHT = 13;

    /** fs-lg (size) */
    public static final int FS_LG_DARK = 14;
    public static final int FS_LG_LIGHT = 14;

    /** fs-xl (size) */
    public static final int FS_XL_DARK = 16;
    public static final int FS_XL_LIGHT = 16;

    /** fs-3xl (size) */
    public static final int FS_3XL_DARK = 32;
    public static final int FS_3XL_LIGHT = 32;

    /** sp-1 (size) */
    public static final int SP_1_DARK = 2;
    public static final int SP_1_LIGHT = 2;

    /** sp-1h (size) */
    public static final int SP_1H_DARK = 6;
    public static final int SP_1H_LIGHT = 6;

    /** sp-2 (size) */
    public static final int SP_2_DARK = 4;
    public static final int SP_2_LIGHT = 4;

    /** sp-3 (size) */
    public static final int SP_3_DARK = 8;
    public static final int SP_3_LIGHT = 8;

    /** sp-4 (size) */
    public static final int SP_4_DARK = 12;
    public static final int SP_4_LIGHT = 12;

    /** sp-5 (size) */
    public static final int SP_5_DARK = 16;
    public static final int SP_5_LIGHT = 16;

    /** sp-6 (size) */
    public static final int SP_6_DARK = 24;
    public static final int SP_6_LIGHT = 24;

    /** sp-7 (size) */
    public static final int SP_7_DARK = 32;
    public static final int SP_7_LIGHT = 32;

    /** r-sm (size) */
    public static final int R_SM_DARK = 2;
    public static final int R_SM_LIGHT = 2;

    /** r-md (size) */
    public static final int R_MD_DARK = 3;
    public static final int R_MD_LIGHT = 3;

    /** r-lg (size) */
    public static final int R_LG_DARK = 4;
    public static final int R_LG_LIGHT = 4;

    /** r-xl (size) */
    public static final int R_XL_DARK = 6;
    public static final int R_XL_LIGHT = 6;

    /** r-full (size) */
    public static final int R_FULL_DARK = 999;
    public static final int R_FULL_LIGHT = 999;

    /** dur-toast (duration) */
    public static final int DUR_TOAST_DARK = 1800;
    public static final int DUR_TOAST_LIGHT = 1800;

    /** dur-toast-action (duration) */
    public static final int DUR_TOAST_ACTION_DARK = 6000;
    public static final int DUR_TOAST_ACTION_LIGHT = 6000;

    /** dur-toast-result (duration) */
    public static final int DUR_TOAST_RESULT_DARK = 4000;
    public static final int DUR_TOAST_RESULT_LIGHT = 4000;

    /** kind-table (color) */
    public static final String KIND_TABLE_DARK = "#2DD4BF";
    public static final String KIND_TABLE_LIGHT = "#0F766E";

    /** kind-view (color) */
    public static final String KIND_VIEW_DARK = "#C4B5FD";
    public static final String KIND_VIEW_LIGHT = "#7C3AED";

    /** kind-function (color) */
    public static final String KIND_FUNCTION_DARK = "#FBBF24";
    public static final String KIND_FUNCTION_LIGHT = "#B45309";

    /** kind-trigger (color) */
    public static final String KIND_TRIGGER_DARK = "#FDBA74";
    public static final String KIND_TRIGGER_LIGHT = "#C2410C";

    /** kind-procedure (color) */
    public static final String KIND_PROCEDURE_DARK = "#7DD3FC";
    public static final String KIND_PROCEDURE_LIGHT = "#0369A1";

    /** overlay (color) */
    public static final String OVERLAY_DARK = "rgba(2,6,23,.62)";
    public static final String OVERLAY_LIGHT = "rgba(15,23,42,.45)";

    /** shadow-1 (color) */
    public static final String SHADOW_1_DARK = "rgba(2,6,23,.45)";
    public static final String SHADOW_1_LIGHT = "rgba(15,23,42,.08)";

    /** shadow-2 (color) */
    public static final String SHADOW_2_DARK = "rgba(2,6,23,.60)";
    public static final String SHADOW_2_LIGHT = "rgba(15,23,42,.16)";

    /** kind-index (color) */
    public static final String KIND_INDEX_DARK = "#94A3B8";
    public static final String KIND_INDEX_LIGHT = "#64748B";

    /** kind-event (color) */
    public static final String KIND_EVENT_DARK = "#F9A8D4";
    public static final String KIND_EVENT_LIGHT = "#BE185D";

    /** icon-edge (color) */
    public static final String ICON_EDGE_DARK = "#5AB0EE";
    public static final String ICON_EDGE_LIGHT = "#077ED5";

    /** icon-tone-a (color) */
    public static final String ICON_TONE_A_DARK = "#17395A";
    public static final String ICON_TONE_A_LIGHT = "#CDE8FF";

    /** icon-tone-b (color) */
    public static final String ICON_TONE_B_DARK = "#1E4A71";
    public static final String ICON_TONE_B_LIGHT = "#B8DFFF";

    /** icon-tone-c (color) */
    public static final String ICON_TONE_C_DARK = "#2A628E";
    public static final String ICON_TONE_C_LIGHT = "#96D0FF";

    /** row-tree (size) */
    public static final int ROW_TREE_DARK = 20;
    public static final int ROW_TREE_LIGHT = 20;

    public static final List<String> ALL_KEYS = List.of(
            "bg-canvas", "bg-sidebar", "bg-chrome", "bg-surface", "bg-subtle", "bg-elevated",
            "bg-inset", "bg-hover", "bg-active", "bg-selected", "bg-selected-grid", "bg-stripe",
            "bg-disabled", "border", "border-subtle", "border-focus", "text-primary", "text-secondary",
            "text-tertiary", "text-muted", "text-disabled", "text-inverse", "accent", "accent-hover",
            "risk-0-read", "risk-1-write", "risk-2-structure", "risk-3-admin", "risk-0-bg", "risk-0-border",
            "risk-1-bg", "risk-1-border", "risk-2-bg", "risk-2-border", "risk-3-bg", "risk-3-border",
            "success", "success-bg", "warning", "warning-bg", "dirty", "dirty-bg",
            "error", "error-bg", "info", "syn-keyword", "syn-string", "syn-number",
            "syn-comment", "syn-function", "syn-type", "syn-ident", "syn-param", "syn-error",
            "h-appbar", "h-toolbar", "h-tabbar", "h-statusbar", "h-input", "h-button",
            "w-sidebar-min", "w-sidebar", "w-sidebar-max", "w-sidebar-collapsed", "row-dense", "row-compact",
            "row-normal", "row-comfort", "fs-2xs", "fs-xs", "fs-sm", "fs-md",
            "fs-lg", "fs-xl", "fs-3xl", "sp-1", "sp-1h", "sp-2",
            "sp-3", "sp-4", "sp-5", "sp-6", "sp-7", "r-sm",
            "r-md", "r-lg", "r-xl", "r-full", "dur-toast", "dur-toast-action",
            "dur-toast-result", "kind-table", "kind-view", "kind-function", "kind-trigger", "kind-procedure",
            "overlay", "shadow-1", "shadow-2", "kind-index", "kind-event", "icon-edge",
            "icon-tone-a", "icon-tone-b", "icon-tone-c", "row-tree"
    );

    private Tokens() { }
}
