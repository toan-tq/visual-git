package com.visualgit;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

public class AppTheme {

    // Platform detection (done once)
    public static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    public static final boolean IS_MAC = OS_NAME.contains("mac");
    public static final boolean IS_WIN = OS_NAME.contains("win");

    // Font names
    public static final String MONO_FONT = IS_MAC ? "Menlo" : IS_WIN ? "Consolas" : "Monospace";
    public static final String SANS_FONT = IS_MAC ? "Helvetica" : IS_WIN ? "Segoe UI" : "Sans";

    // Syntax colors (used by FileCompare via SyntaxHighlighter)
    public static Color kwColor;
    public static Color typeColor;
    public static Color stringColor;
    public static Color commentColor;
    public static Color numberColor;
    public static Color preprocColor;

    // Markdown colors
    public static Color mdHeadingColor;
    public static Color mdBoldColor;
    public static Color mdCodeColor;
    public static Color mdLinkColor;
    public static Color mdQuoteColor;

    // Shared UI colors
    public static Color lineNumBg;
    public static Color lineNumFg;
    public static Color hoverBg;
    public static Color currentLineBg;
    public static Color statusBg;
    public static Color findHighlightBg;
    public static Color findCurrentBg;

    private static boolean initialized = false;

    public static void init(Display display) {
        if (initialized) return;
        kwColor      = new Color(display, 0, 0, 180);
        typeColor    = new Color(display, 0, 128, 128);
        stringColor  = new Color(display, 163, 21, 21);
        commentColor = new Color(display, 0, 128, 0);
        numberColor  = new Color(display, 180, 0, 0);
        preprocColor = new Color(display, 128, 0, 128);

        mdHeadingColor = new Color(display, 0, 80, 180);
        mdBoldColor    = new Color(display, 50, 50, 50);
        mdCodeColor    = new Color(display, 180, 60, 0);
        mdLinkColor    = new Color(display, 0, 100, 200);
        mdQuoteColor   = new Color(display, 80, 130, 60);

        lineNumBg = new Color(display, 240, 240, 240);
        lineNumFg = new Color(display, 130, 130, 130);
        hoverBg   = new Color(display, 220, 230, 245);

        currentLineBg   = new Color(display, 232, 242, 254);
        statusBg        = new Color(display, 236, 236, 236);
        findHighlightBg = new Color(display, 255, 235, 120);
        findCurrentBg   = new Color(display, 255, 165, 0);

        initialized = true;
    }

    public static void dispose() {
        if (!initialized) return;
        kwColor.dispose();
        typeColor.dispose();
        stringColor.dispose();
        commentColor.dispose();
        numberColor.dispose();
        preprocColor.dispose();
        mdHeadingColor.dispose();
        mdBoldColor.dispose();
        mdCodeColor.dispose();
        mdLinkColor.dispose();
        mdQuoteColor.dispose();
        lineNumBg.dispose();
        lineNumFg.dispose();
        hoverBg.dispose();
        currentLineBg.dispose();
        statusBg.dispose();
        findHighlightBg.dispose();
        findCurrentBg.dispose();
        initialized = false;
    }
}
