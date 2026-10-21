package io.agenticdb.ui;

import java.util.List;

/**
 * 设计令牌 —— 由 {@code tools/GenTokens.java} 从 tokens.json 生成。
 *
 * <p>⛔ 不要手改这个文件。改 tokens.json 后重新生成。
 * <p>颜色字面量只允许出现在这里和 theme-*.css。
 */
public final class Tokens {

    public static final int COUNT = 60;

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

    /** accent (color) */
    public static final String ACCENT_DARK = "#388bfd";
    public static final String ACCENT_LIGHT = "#0969da";

    /** accent-hover (color) */
    public static final String ACCENT_HOVER_DARK = "#58a6ff";
    public static final String ACCENT_HOVER_LIGHT = "#0550ae";

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

    /** w-sidebar-min (size) */
    public static final int W_SIDEBAR_MIN_DARK = 160;
    public static final int W_SIDEBAR_MIN_LIGHT = 160;

    /** w-sidebar (size) */
    public static final int W_SIDEBAR_DARK = 240;
    public static final int W_SIDEBAR_LIGHT = 240;

    /** w-sidebar-max (size) */
    public static final int W_SIDEBAR_MAX_DARK = 480;
    public static final int W_SIDEBAR_MAX_LIGHT = 480;

    /** w-sidebar-collapsed (size) */
    public static final int W_SIDEBAR_COLLAPSED_DARK = 48;
    public static final int W_SIDEBAR_COLLAPSED_LIGHT = 48;

    /** row-compact (size) */
    public static final int ROW_COMPACT_DARK = 26;
    public static final int ROW_COMPACT_LIGHT = 26;

    /** row-normal (size) */
    public static final int ROW_NORMAL_DARK = 32;
    public static final int ROW_NORMAL_LIGHT = 32;

    /** row-comfort (size) */
    public static final int ROW_COMFORT_DARK = 40;
    public static final int ROW_COMFORT_LIGHT = 40;

    /** dur-toast (duration) */
    public static final int DUR_TOAST_DARK = 1800;
    public static final int DUR_TOAST_LIGHT = 1800;

    /** dur-toast-action (duration) */
    public static final int DUR_TOAST_ACTION_DARK = 6000;
    public static final int DUR_TOAST_ACTION_LIGHT = 6000;

    /** dur-toast-result (duration) */
    public static final int DUR_TOAST_RESULT_DARK = 4000;
    public static final int DUR_TOAST_RESULT_LIGHT = 4000;

    public static final List<String> ALL_KEYS = List.of(
            "bg-canvas", "bg-surface", "bg-elevated", "bg-inset", "bg-hover", "bg-active",
            "bg-selected", "bg-disabled", "border", "border-strong", "border-focus", "text-primary",
            "text-secondary", "text-tertiary", "text-disabled", "text-inverse", "accent", "accent-hover",
            "risk-0-read", "risk-1-write", "risk-2-structure", "risk-3-admin", "risk-0-bg", "risk-0-border",
            "risk-1-bg", "risk-1-border", "risk-2-bg", "risk-2-border", "risk-3-bg", "risk-3-border",
            "success", "success-bg", "warning", "warning-bg", "error", "error-bg",
            "info", "syn-keyword", "syn-string", "syn-number", "syn-comment", "syn-function",
            "syn-type", "syn-ident", "syn-param", "syn-error", "h-toolbar", "h-tabbar",
            "h-statusbar", "h-input", "w-sidebar-min", "w-sidebar", "w-sidebar-max", "w-sidebar-collapsed",
            "row-compact", "row-normal", "row-comfort", "dur-toast", "dur-toast-action", "dur-toast-result"
    );

    private Tokens() { }
}
