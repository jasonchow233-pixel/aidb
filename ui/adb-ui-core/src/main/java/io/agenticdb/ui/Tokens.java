package io.agenticdb.ui;

import java.util.List;

/**
 * 设计令牌 —— 由 {@code tools/GenTokens.java} 从 tokens.json 生成。
 *
 * <p>⛔ 不要手改这个文件。改 tokens.json 后重新生成。
 * <p>颜色字面量只允许出现在这里和 theme-*.css。
 */
public final class Tokens {

    public static final int COUNT = 112;

    /** bg-canvas (color) */
    public static final String BG_CANVAS_DARK = "#0d1117";
    public static final String BG_CANVAS_LIGHT = "#ffffff";

    /** bg-surface (color) */
    public static final String BG_SURFACE_DARK = "#161b22";
    public static final String BG_SURFACE_LIGHT = "#f6f8fa";

    /** bg-elevated (color) */
    public static final String BG_ELEVATED_DARK = "#21262d";
    public static final String BG_ELEVATED_LIGHT = "#ffffff";

    /** bg-inset (color) */
    public static final String BG_INSET_DARK = "#010409";
    public static final String BG_INSET_LIGHT = "#f6f8fa";

    /** bg-hover (color) */
    public static final String BG_HOVER_DARK = "rgba(177,186,196,.12)";
    public static final String BG_HOVER_LIGHT = "rgba(31,35,40,.08)";

    /** bg-active (color) */
    public static final String BG_ACTIVE_DARK = "rgba(56,139,253,.15)";
    public static final String BG_ACTIVE_LIGHT = "rgba(56,139,253,.12)";

    /** bg-selected (color) */
    public static final String BG_SELECTED_DARK = "rgba(56,139,253,.22)";
    public static final String BG_SELECTED_LIGHT = "rgba(56,139,253,.18)";

    /** bg-disabled (color) */
    public static final String BG_DISABLED_DARK = "rgba(177,186,196,.06)";
    public static final String BG_DISABLED_LIGHT = "rgba(177,186,196,.06)";

    /** border (color) */
    public static final String BORDER_DARK = "#30363d";
    public static final String BORDER_LIGHT = "#d0d7de";

    /** border-strong (color) */
    public static final String BORDER_STRONG_DARK = "#484f58";
    public static final String BORDER_STRONG_LIGHT = "#afb8c1";

    /** border-focus (color) */
    public static final String BORDER_FOCUS_DARK = "#388bfd";
    public static final String BORDER_FOCUS_LIGHT = "#0969da";

    /** text-primary (color) */
    public static final String TEXT_PRIMARY_DARK = "#e6edf3";
    public static final String TEXT_PRIMARY_LIGHT = "#1f2328";

    /** text-secondary (color) */
    public static final String TEXT_SECONDARY_DARK = "#8b949e";
    public static final String TEXT_SECONDARY_LIGHT = "#656d76";

    /** text-tertiary (color) */
    public static final String TEXT_TERTIARY_DARK = "#6e7681";
    public static final String TEXT_TERTIARY_LIGHT = "#8c959f";

    /** text-disabled (color) */
    public static final String TEXT_DISABLED_DARK = "#484f58";
    public static final String TEXT_DISABLED_LIGHT = "#8c959f";

    /** text-inverse (color) */
    public static final String TEXT_INVERSE_DARK = "#0d1117";
    public static final String TEXT_INVERSE_LIGHT = "#ffffff";

    /** text-link (color) */
    public static final String TEXT_LINK_DARK = "#58a6ff";
    public static final String TEXT_LINK_LIGHT = "#0969da";

    /** accent (color) */
    public static final String ACCENT_DARK = "#388bfd";
    public static final String ACCENT_LIGHT = "#0969da";

    /** accent-hover (color) */
    public static final String ACCENT_HOVER_DARK = "#58a6ff";
    public static final String ACCENT_HOVER_LIGHT = "#0550ae";

    /** accent-subtle (color) */
    public static final String ACCENT_SUBTLE_DARK = "rgba(56,139,253,.12)";
    public static final String ACCENT_SUBTLE_LIGHT = "rgba(9,105,218,.10)";

    /** risk-0-read (color) */
    public static final String RISK_0_READ_DARK = "#388bfd";
    public static final String RISK_0_READ_LIGHT = "#0969da";

    /** risk-1-write (color) */
    public static final String RISK_1_WRITE_DARK = "#d29922";
    public static final String RISK_1_WRITE_LIGHT = "#9a6700";

    /** risk-2-structure (color) */
    public static final String RISK_2_STRUCTURE_DARK = "#f85149";
    public static final String RISK_2_STRUCTURE_LIGHT = "#cf222e";

    /** risk-3-admin (color) */
    public static final String RISK_3_ADMIN_DARK = "#bc4cff";
    public static final String RISK_3_ADMIN_LIGHT = "#8250df";

    /** risk-0-bg (color) */
    public static final String RISK_0_BG_DARK = "rgba(56,139,253,.08)";
    public static final String RISK_0_BG_LIGHT = "rgba(56,139,253,.08)";

    /** risk-0-border (color) */
    public static final String RISK_0_BORDER_DARK = "rgba(56,139,253,.30)";
    public static final String RISK_0_BORDER_LIGHT = "rgba(56,139,253,.30)";

    /** risk-1-bg (color) */
    public static final String RISK_1_BG_DARK = "rgba(210,153,34,.08)";
    public static final String RISK_1_BG_LIGHT = "rgba(210,153,34,.08)";

    /** risk-1-border (color) */
    public static final String RISK_1_BORDER_DARK = "rgba(210,153,34,.30)";
    public static final String RISK_1_BORDER_LIGHT = "rgba(210,153,34,.30)";

    /** risk-2-bg (color) */
    public static final String RISK_2_BG_DARK = "rgba(248,81,73,.08)";
    public static final String RISK_2_BG_LIGHT = "rgba(248,81,73,.08)";

    /** risk-2-border (color) */
    public static final String RISK_2_BORDER_DARK = "rgba(248,81,73,.30)";
    public static final String RISK_2_BORDER_LIGHT = "rgba(248,81,73,.30)";

    /** risk-3-bg (color) */
    public static final String RISK_3_BG_DARK = "rgba(188,76,255,.08)";
    public static final String RISK_3_BG_LIGHT = "rgba(188,76,255,.08)";

    /** risk-3-border (color) */
    public static final String RISK_3_BORDER_DARK = "rgba(188,76,255,.30)";
    public static final String RISK_3_BORDER_LIGHT = "rgba(188,76,255,.30)";

    /** success (color) */
    public static final String SUCCESS_DARK = "#3fb950";
    public static final String SUCCESS_LIGHT = "#1a7f37";

    /** success-bg (color) */
    public static final String SUCCESS_BG_DARK = "rgba(63,185,80,.10)";
    public static final String SUCCESS_BG_LIGHT = "rgba(26,127,55,.10)";

    /** warning (color) */
    public static final String WARNING_DARK = "#d29922";
    public static final String WARNING_LIGHT = "#9a6700";

    /** warning-bg (color) */
    public static final String WARNING_BG_DARK = "rgba(210,153,34,.10)";
    public static final String WARNING_BG_LIGHT = "rgba(154,103,0,.10)";

    /** error (color) */
    public static final String ERROR_DARK = "#f85149";
    public static final String ERROR_LIGHT = "#cf222e";

    /** error-bg (color) */
    public static final String ERROR_BG_DARK = "rgba(248,81,73,.10)";
    public static final String ERROR_BG_LIGHT = "rgba(207,34,46,.10)";

    /** info (color) */
    public static final String INFO_DARK = "#58a6ff";
    public static final String INFO_LIGHT = "#0969da";

    /** info-bg (color) */
    public static final String INFO_BG_DARK = "rgba(88,166,255,.10)";
    public static final String INFO_BG_LIGHT = "rgba(9,105,218,.10)";

    /** trust-confirmed (color) */
    public static final String TRUST_CONFIRMED_DARK = "#3fb950";
    public static final String TRUST_CONFIRMED_LIGHT = "#3fb950";

    /** trust-learned (color) */
    public static final String TRUST_LEARNED_DARK = "#58a6ff";
    public static final String TRUST_LEARNED_LIGHT = "#58a6ff";

    /** trust-inferred (color) */
    public static final String TRUST_INFERRED_DARK = "#d29922";
    public static final String TRUST_INFERRED_LIGHT = "#d29922";

    /** syn-keyword (color) */
    public static final String SYN_KEYWORD_DARK = "#ff7b72";
    public static final String SYN_KEYWORD_LIGHT = "#ff7b72";

    /** syn-string (color) */
    public static final String SYN_STRING_DARK = "#a5d6ff";
    public static final String SYN_STRING_LIGHT = "#a5d6ff";

    /** syn-number (color) */
    public static final String SYN_NUMBER_DARK = "#79c0ff";
    public static final String SYN_NUMBER_LIGHT = "#79c0ff";

    /** syn-comment (color) */
    public static final String SYN_COMMENT_DARK = "#8b949e";
    public static final String SYN_COMMENT_LIGHT = "#6e7781";

    /** syn-function (color) */
    public static final String SYN_FUNCTION_DARK = "#d2a8ff";
    public static final String SYN_FUNCTION_LIGHT = "#d2a8ff";

    /** syn-type (color) */
    public static final String SYN_TYPE_DARK = "#ffa657";
    public static final String SYN_TYPE_LIGHT = "#ffa657";

    /** syn-ident (color) */
    public static final String SYN_IDENT_DARK = "#c9d1d9";
    public static final String SYN_IDENT_LIGHT = "#c9d1d9";

    /** syn-param (color) */
    public static final String SYN_PARAM_DARK = "#79c0ff";
    public static final String SYN_PARAM_LIGHT = "#79c0ff";

    /** syn-error (color) */
    public static final String SYN_ERROR_DARK = "#f85149";
    public static final String SYN_ERROR_LIGHT = "#f85149";

    /** fs-xs (size) */
    public static final int FS_XS_DARK = 11;
    public static final int FS_XS_LIGHT = 11;

    /** fs-sm (size) */
    public static final int FS_SM_DARK = 12;
    public static final int FS_SM_LIGHT = 12;

    /** fs-base (size) */
    public static final int FS_BASE_DARK = 13;
    public static final int FS_BASE_LIGHT = 13;

    /** fs-md (size) */
    public static final int FS_MD_DARK = 14;
    public static final int FS_MD_LIGHT = 14;

    /** fs-lg (size) */
    public static final int FS_LG_DARK = 16;
    public static final int FS_LG_LIGHT = 16;

    /** fs-xl (size) */
    public static final int FS_XL_DARK = 20;
    public static final int FS_XL_LIGHT = 20;

    /** fs-2xl (size) */
    public static final int FS_2XL_DARK = 24;
    public static final int FS_2XL_LIGHT = 24;

    /** lh-tight (number) */
    public static final double LH_TIGHT_DARK = 1.25;
    public static final double LH_TIGHT_LIGHT = 1.25;

    /** lh-normal (number) */
    public static final double LH_NORMAL_DARK = 1.5;
    public static final double LH_NORMAL_LIGHT = 1.5;

    /** lh-dense (number) */
    public static final double LH_DENSE_DARK = 1.2;
    public static final double LH_DENSE_LIGHT = 1.2;

    /** fw-normal (number) */
    public static final int FW_NORMAL_DARK = 400;
    public static final int FW_NORMAL_LIGHT = 400;

    /** fw-medium (number) */
    public static final int FW_MEDIUM_DARK = 500;
    public static final int FW_MEDIUM_LIGHT = 500;

    /** fw-bold (number) */
    public static final int FW_BOLD_DARK = 600;
    public static final int FW_BOLD_LIGHT = 600;

    /** sp-1 (size) */
    public static final int SP_1_DARK = 4;
    public static final int SP_1_LIGHT = 4;

    /** sp-2 (size) */
    public static final int SP_2_DARK = 8;
    public static final int SP_2_LIGHT = 8;

    /** sp-3 (size) */
    public static final int SP_3_DARK = 12;
    public static final int SP_3_LIGHT = 12;

    /** sp-4 (size) */
    public static final int SP_4_DARK = 16;
    public static final int SP_4_LIGHT = 16;

    /** sp-5 (size) */
    public static final int SP_5_DARK = 20;
    public static final int SP_5_LIGHT = 20;

    /** sp-6 (size) */
    public static final int SP_6_DARK = 24;
    public static final int SP_6_LIGHT = 24;

    /** sp-8 (size) */
    public static final int SP_8_DARK = 32;
    public static final int SP_8_LIGHT = 32;

    /** sp-10 (size) */
    public static final int SP_10_DARK = 40;
    public static final int SP_10_LIGHT = 40;

    /** r-sm (size) */
    public static final int R_SM_DARK = 4;
    public static final int R_SM_LIGHT = 4;

    /** r-md (size) */
    public static final int R_MD_DARK = 6;
    public static final int R_MD_LIGHT = 6;

    /** r-lg (size) */
    public static final int R_LG_DARK = 8;
    public static final int R_LG_LIGHT = 8;

    /** r-xl (size) */
    public static final int R_XL_DARK = 12;
    public static final int R_XL_LIGHT = 12;

    /** r-full (size) */
    public static final int R_FULL_DARK = 999;
    public static final int R_FULL_LIGHT = 999;

    /** h-titlebar (size) */
    public static final int H_TITLEBAR_DARK = 36;
    public static final int H_TITLEBAR_LIGHT = 36;

    /** h-toolbar (size) */
    public static final int H_TOOLBAR_DARK = 36;
    public static final int H_TOOLBAR_LIGHT = 36;

    /** h-tabbar (size) */
    public static final int H_TABBAR_DARK = 36;
    public static final int H_TABBAR_LIGHT = 36;

    /** h-statusbar (size) */
    public static final int H_STATUSBAR_DARK = 26;
    public static final int H_STATUSBAR_LIGHT = 26;

    /** h-input (size) */
    public static final int H_INPUT_DARK = 28;
    public static final int H_INPUT_LIGHT = 28;

    /** h-input-md (size) */
    public static final int H_INPUT_MD_DARK = 32;
    public static final int H_INPUT_MD_LIGHT = 32;

    /** h-btn-sm (size) */
    public static final int H_BTN_SM_DARK = 24;
    public static final int H_BTN_SM_LIGHT = 24;

    /** h-btn (size) */
    public static final int H_BTN_DARK = 28;
    public static final int H_BTN_LIGHT = 28;

    /** h-btn-md (size) */
    public static final int H_BTN_MD_DARK = 32;
    public static final int H_BTN_MD_LIGHT = 32;

    /** w-sidebar-min (size) */
    public static final int W_SIDEBAR_MIN_DARK = 160;
    public static final int W_SIDEBAR_MIN_LIGHT = 160;

    /** w-sidebar (size) */
    public static final int W_SIDEBAR_DARK = 240;
    public static final int W_SIDEBAR_LIGHT = 240;

    /** w-sidebar-max (size) */
    public static final int W_SIDEBAR_MAX_DARK = 480;
    public static final int W_SIDEBAR_MAX_LIGHT = 480;

    /** w-ai-panel (size) */
    public static final int W_AI_PANEL_DARK = 380;
    public static final int W_AI_PANEL_LIGHT = 380;

    /** row-compact (size) */
    public static final int ROW_COMPACT_DARK = 26;
    public static final int ROW_COMPACT_LIGHT = 26;

    /** row-normal (size) */
    public static final int ROW_NORMAL_DARK = 32;
    public static final int ROW_NORMAL_LIGHT = 32;

    /** row-comfort (size) */
    public static final int ROW_COMFORT_DARK = 40;
    public static final int ROW_COMFORT_LIGHT = 40;

    /** shadow-sm (font) */
    public static final String SHADOW_SM_DARK = "0 1px 3px rgba(0,0,0,.12)";
    public static final String SHADOW_SM_LIGHT = "0 1px 3px rgba(0,0,0,.12)";

    /** shadow-md (font) */
    public static final String SHADOW_MD_DARK = "0 4px 12px rgba(0,0,0,.16)";
    public static final String SHADOW_MD_LIGHT = "0 4px 12px rgba(0,0,0,.16)";

    /** shadow-lg (font) */
    public static final String SHADOW_LG_DARK = "0 12px 32px rgba(0,0,0,.24)";
    public static final String SHADOW_LG_LIGHT = "0 12px 32px rgba(0,0,0,.24)";

    /** bw (size) */
    public static final int BW_DARK = 1;
    public static final int BW_LIGHT = 1;

    /** bw-focus (size) */
    public static final int BW_FOCUS_DARK = 2;
    public static final int BW_FOCUS_LIGHT = 2;

    /** z-dropdown (number) */
    public static final int Z_DROPDOWN_DARK = 1000;
    public static final int Z_DROPDOWN_LIGHT = 1000;

    /** z-drawer (number) */
    public static final int Z_DRAWER_DARK = 1100;
    public static final int Z_DRAWER_LIGHT = 1100;

    /** z-dialog (number) */
    public static final int Z_DIALOG_DARK = 1200;
    public static final int Z_DIALOG_LIGHT = 1200;

    /** z-toast (number) */
    public static final int Z_TOAST_DARK = 1300;
    public static final int Z_TOAST_LIGHT = 1300;

    /** z-tooltip (number) */
    public static final int Z_TOOLTIP_DARK = 1400;
    public static final int Z_TOOLTIP_LIGHT = 1400;

    /** dur-instant (duration) */
    public static final int DUR_INSTANT_DARK = 80;
    public static final int DUR_INSTANT_LIGHT = 80;

    /** dur-fast (duration) */
    public static final int DUR_FAST_DARK = 120;
    public static final int DUR_FAST_LIGHT = 120;

    /** dur-base (duration) */
    public static final int DUR_BASE_DARK = 150;
    public static final int DUR_BASE_LIGHT = 150;

    /** dur-slow (duration) */
    public static final int DUR_SLOW_DARK = 220;
    public static final int DUR_SLOW_LIGHT = 220;

    /** ease-out (easing) */
    public static final String EASE_OUT_DARK = "cubic-bezier(.16,1,.3,1)";
    public static final String EASE_OUT_LIGHT = "cubic-bezier(.16,1,.3,1)";

    /** ease-in-out (easing) */
    public static final String EASE_IN_OUT_DARK = "cubic-bezier(.4,0,.2,1)";
    public static final String EASE_IN_OUT_LIGHT = "cubic-bezier(.4,0,.2,1)";

    /** sidebar-width (size) */
    public static final int SIDEBAR_WIDTH_DARK = 240;
    public static final int SIDEBAR_WIDTH_LIGHT = 240;

    /** sidebar-width-collapsed (size) */
    public static final int SIDEBAR_WIDTH_COLLAPSED_DARK = 48;
    public static final int SIDEBAR_WIDTH_COLLAPSED_LIGHT = 48;

    public static final List<String> ALL_KEYS = List.of(
            "bg-canvas", "bg-surface", "bg-elevated", "bg-inset", "bg-hover", "bg-active",
            "bg-selected", "bg-disabled", "border", "border-strong", "border-focus", "text-primary",
            "text-secondary", "text-tertiary", "text-disabled", "text-inverse", "text-link", "accent",
            "accent-hover", "accent-subtle", "risk-0-read", "risk-1-write", "risk-2-structure", "risk-3-admin",
            "risk-0-bg", "risk-0-border", "risk-1-bg", "risk-1-border", "risk-2-bg", "risk-2-border",
            "risk-3-bg", "risk-3-border", "success", "success-bg", "warning", "warning-bg",
            "error", "error-bg", "info", "info-bg", "trust-confirmed", "trust-learned",
            "trust-inferred", "syn-keyword", "syn-string", "syn-number", "syn-comment", "syn-function",
            "syn-type", "syn-ident", "syn-param", "syn-error", "fs-xs", "fs-sm",
            "fs-base", "fs-md", "fs-lg", "fs-xl", "fs-2xl", "lh-tight",
            "lh-normal", "lh-dense", "fw-normal", "fw-medium", "fw-bold", "sp-1",
            "sp-2", "sp-3", "sp-4", "sp-5", "sp-6", "sp-8",
            "sp-10", "r-sm", "r-md", "r-lg", "r-xl", "r-full",
            "h-titlebar", "h-toolbar", "h-tabbar", "h-statusbar", "h-input", "h-input-md",
            "h-btn-sm", "h-btn", "h-btn-md", "w-sidebar-min", "w-sidebar", "w-sidebar-max",
            "w-ai-panel", "row-compact", "row-normal", "row-comfort", "shadow-sm", "shadow-md",
            "shadow-lg", "bw", "bw-focus", "z-dropdown", "z-drawer", "z-dialog",
            "z-toast", "z-tooltip", "dur-instant", "dur-fast", "dur-base", "dur-slow",
            "ease-out", "ease-in-out", "sidebar-width", "sidebar-width-collapsed"
    );

    private Tokens() { }
}
