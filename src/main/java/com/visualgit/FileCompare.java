package com.visualgit;

import org.eclipse.swt.SWT;

import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.ExtendedModifyEvent;
import org.eclipse.swt.custom.ExtendedModifyListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.ShellAdapter;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileCompare {

    private StyledText leftText;
    private StyledText rightText;
    private Canvas lineNumberLeft;
    private Canvas lineNumberRight;
    private Canvas gutter;
    private Font monoFont;
    private Font iconFont;
    private Font labelFont;
    private Color navIconColor;
    private boolean updating = false;
    private int lastTopPixelLeft = -1;
    private int lastTopPixelRight = -1;
    private int currentChangeIndex = -1; // tracks which gutterBlock we're at
    private int computedLineHeight;      // actual per-line pixel height (set in recomputeDiff)
    private String leftFilePath;
    private String rightFilePath;
    private Label leftLabel;
    private Label rightLabel;
    private Label changeIndicator;
    private Label statusBar;
    private Color selectedBorder;
    private boolean leftDirty, rightDirty;
    private String leftOriginal = "", rightOriginal = "";
    private Composite saveButton;
    private Canvas saveIcon;
    private boolean saveArmed;     // true when there are unsaved edits (drives the Save button look)
    private Color dirtyHeaderBg;

    // Ignore whitespace
    private boolean ignoreWhitespace = false;
    private Composite ignoreWsButton;

    // Find
    private Composite findBar;
    private org.eclipse.swt.widgets.Text findField;
    private Label findCountLabel;
    private List<int[]> leftFindMatches = new ArrayList<>();
    private List<int[]> rightFindMatches = new ArrayList<>();
    private int currentFindMatchIndex = -1;
    private StyledText lastFocusedText;
    private Color findHighlightBg;
    private Color findCurrentBg;

    // Collapse equal sections
    private boolean collapseEqual = false;
    private String fullLeftText;
    private String fullRightText;
    private int[] leftDisplayToOrig;
    private int[] rightDisplayToOrig;
    private Composite collapseButton;

    // Unified view
    private boolean unifiedMode = false;
    private SashForm mainSash;
    private Composite unifiedPanel;
    private StyledText unifiedText;
    private Canvas lineNumberUnified;
    private Composite unifiedToggle;
    private DiffType[] unifiedLineTypes;
    private int[] unifiedHunkLineIndex;
    private int[] unifiedOrigLeft;   // orig left-file line index per display line (-1 = ADDED/HIDDEN)
    private int[] unifiedOrigRight;  // orig right-file line index per display line (-1 = REMOVED/HIDDEN)

    // Gutter inline merge arrows
    private int hoveredGutterBlock = -1;

    // Colors
    private Color addedBg;
    private Color removedBg;
    private Color gutterBg;
    private Color gutterLine;
    private Color changedBgL;      // light yellow for left-side changed lines
    private Color paddingBg;       // neutral gray for empty padding (both sides)
    private Color foldMarkerBg;    // distinct bg for "⋯ N unchanged ⋯" fold marker lines
    private Color foldLinkFg;      // blue link color for fold marker text
    private Color innerChangedL;   // darker yellow for inner-line changes (left side)
    private Color innerChangedR;   // darker green for inner-line changes (right side)
    private Color pressedBg;       // toolbar button background while held down
    private Color btnActiveFg;     // toolbar label text when the button can act
    private Color btnFadedFg;      // toolbar icon/label when the button is disabled
    private Color saveActiveFg;    // Save icon tint when there are unsaved changes

    // Diff data: maps display line index → type (EQUAL, ADDED, REMOVED, CHANGED)
    private DiffType[] leftDiffTypes;
    private DiffType[] rightDiffTypes;
    // Inner-line highlight ranges per display line
    private List<int[]> leftInnerRanges = new ArrayList<>();   // each: [startCol, endCol, ...]
    private List<int[]> rightInnerRanges = new ArrayList<>();
    // Gutter connections: each entry links a block on left to a block on right
    private List<GutterBlock> gutterBlocks = new ArrayList<>();

    private enum DiffType { EQUAL, ADDED, REMOVED, CHANGED, HIDDEN }

    // A block connecting left lines [leftStart..leftEnd) to right lines [rightStart..rightEnd)
    private static class GutterBlock {
        int leftStart, leftEnd, rightStart, rightEnd;
        DiffType type;
        GutterBlock(int ls, int le, int rs, int re, DiffType t) {
            leftStart = ls; leftEnd = le; rightStart = rs; rightEnd = re; type = t;
        }
    }

    // Minimum widths; the real widths grow with the file's digit count (see updateLineNumberWidths)
    private static final int LINE_NUMBER_MIN_WIDTH = 50;
    private static final int GUTTER_WIDTH = 40;
    private static final int ICON_CANVAS_SIZE = 20;   // drawing box for vector toolbar icons
    private static final int UNIFIED_LN_MIN_WIDTH = 96; // two columns: old(44) | sep(8) | new(44)
    private static final int LN_PAD_RIGHT = 8;  // gap between the number and the text area
    private static final int LN_PAD_LEFT = 4;   // gap on the far side of the number
    private static final int UNI_LN_PAD = 4;    // padding inside each unified column

    private int lineNumberWidth = LINE_NUMBER_MIN_WIDTH;
    private int unifiedLnWidth = UNIFIED_LN_MIN_WIDTH;
    private int digitWidth;                     // cached advance width of one digit in monoFont

    public static void main(String[] args) {
        new FileCompare(args).run();
    }

    public FileCompare(String[] args) {
        if (args.length >= 2) {
            leftFilePath = args[0];
            rightFilePath = args[1];
        }
    }

    public FileCompare(String left, String right, Display sharedDisplay) {
        this(left, right, sharedDisplay, null);
    }

    public FileCompare(String left, String right, Display sharedDisplay, Shell parentShell) {
        this.leftFilePath = (left != null && !left.isEmpty()) ? left : null;
        this.rightFilePath = (right != null && !right.isEmpty()) ? right : null;
        this.sharedDisplay = sharedDisplay;
        this.parentShell = parentShell;
    }

    private Display sharedDisplay;
    private Shell parentShell;

    public void run() {
        Display.setAppName("FileCompare");
        Display display = new Display();
        AppTheme.init(display);
        Shell shell = createShell(display);
        shell.addShellListener(new ShellAdapter() {
            @Override
            public void shellClosed(ShellEvent e) {
                if (leftDirty || rightDirty) {
                    MessageBox mb = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO | SWT.CANCEL);
                    mb.setText("Unsaved Changes");
                    mb.setMessage("You have unsaved changes. Save before closing?");
                    int result = mb.open();
                    if (result == SWT.YES) {
                        saveFiles();
                    } else if (result == SWT.CANCEL) {
                        e.doit = false;
                    }
                }
            }
        });
        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        disposeResources();
        AppTheme.dispose();
        display.dispose();
    }

    public void openWindow() {
        Shell shell = createShell(sharedDisplay);
        shell.addShellListener(new ShellAdapter() {
            @Override
            public void shellClosed(ShellEvent e) {
                if (leftDirty || rightDirty) {
                    MessageBox mb = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO | SWT.CANCEL);
                    mb.setText("Unsaved Changes");
                    mb.setMessage("You have unsaved changes. Save before closing?");
                    int result = mb.open();
                    if (result == SWT.YES) {
                        saveFiles();
                    } else if (result == SWT.CANCEL) {
                        e.doit = false;
                        return;
                    }
                }
                disposeResources();
            }
        });
        shell.open();
    }

    /** Create the diff view as an embedded panel inside the given parent Composite. */
    public void createPanel(Composite parent) {
        Display display = parent.getDisplay();
        initColors(display);
        monoFont = new Font(display, AppTheme.MONO_FONT, 13, SWT.NORMAL);
        createEmbeddedUI(parent);
        loadContent();
    }

    /** Update the diff panel content programmatically (for embedded use). */
    public void setContent(String leftContent, String rightContent, String leftLabelText, String rightLabelText) {
        if (leftLabel != null && !leftLabel.isDisposed()) leftLabel.setText("  " + leftLabelText);
        if (rightLabel != null && !rightLabel.isDisposed()) rightLabel.setText("  " + rightLabelText);
        updating = true;
        String left = leftContent != null ? leftContent.replace("\r\n", "\n").replace("\r", "\n") : "";
        String right = rightContent != null ? rightContent.replace("\r\n", "\n").replace("\r", "\n") : "";
        leftText.setText(left);
        rightText.setText(right);
        updating = false;
        leftOriginal = left;
        rightOriginal = right;
        leftDirty = false;
        rightDirty = false;
        recomputeDiff();
    }

    private Shell createShell(Display display) {
        Shell shell = new Shell(display);
        if (leftFilePath != null) {
            shell.setText("[" + Paths.get(leftFilePath).getFileName() + "] - File Compare");
        } else {
            shell.setText("File Compare");
        }
        org.eclipse.swt.graphics.Rectangle screen = getTargetMonitor(display).getBounds();
        int w = (int)(screen.width * 0.9);
        int h = (int)(screen.height * 0.9);
        shell.setSize(w, h);
        shell.setLocation(screen.x + (screen.width - w) / 2, screen.y + (screen.height - h) / 2);
        shell.setLayout(new FillLayout());

        initColors(display);
        monoFont = new Font(display, AppTheme.MONO_FONT, 13, SWT.NORMAL);

        createUI(shell);
        loadContent();
        return shell;
    }

    /** Return the monitor where the parent shell lives, or primary monitor as fallback. */
    private Monitor getTargetMonitor(Display display) {
        if (parentShell != null && !parentShell.isDisposed()) {
            org.eclipse.swt.graphics.Rectangle sb = parentShell.getBounds();
            int cx = sb.x + sb.width / 2;
            int cy = sb.y + sb.height / 2;
            for (Monitor m : display.getMonitors()) {
                if (m.getBounds().contains(cx, cy)) return m;
            }
        }
        return display.getPrimaryMonitor();
    }

    public void disposeResources() {
        if (monoFont != null && !monoFont.isDisposed()) monoFont.dispose();
        if (iconFont != null && !iconFont.isDisposed()) iconFont.dispose();
        if (labelFont != null && !labelFont.isDisposed()) labelFont.dispose();
        if (navIconColor != null && !navIconColor.isDisposed()) navIconColor.dispose();
        disposeColors();
    }

    private void initColors(Display display) {
        addedBg    = new Color(display, 199, 239, 194);   // #C7EFC2 light green
        removedBg  = new Color(display, 255, 215, 215);   // light pink
        gutterBg   = new Color(display, 245, 245, 245);   // light gray
        gutterLine = new Color(display, 180, 180, 180);
        changedBgL    = new Color(display, 255, 248, 195);   // light yellow for changed lines
        paddingBg     = new Color(display, 230, 230, 230);   // neutral gray for empty padding
        foldMarkerBg  = new Color(display, 210, 225, 245);   // light blue for fold marker lines
        foldLinkFg    = new Color(display, 50, 100, 200);    // blue link text for fold markers
        innerChangedL = new Color(display, 255, 220, 100);   // darker yellow highlight
        innerChangedR = new Color(display, 150, 195, 240);   // darker blue highlight
        selectedBorder = new Color(display, 60, 120, 220);   // blue border for selected change
        dirtyHeaderBg = new Color(display, 254, 249, 231);   // yellow tint for dirty pane header
        findHighlightBg = new Color(display, 255, 235, 120); // yellow for find matches
        findCurrentBg = new Color(display, 255, 165, 0);     // orange for current find match
        pressedBg     = new Color(display, 190, 208, 238);   // deeper than hoverBg: button held down
        btnActiveFg   = new Color(display, 45, 45, 45);      // label text of an actionable button
        btnFadedFg    = new Color(display, 193, 193, 193);   // icon + label of a disabled button
        saveActiveFg  = new Color(display, 30, 106, 205);    // Save icon when there is work to save
    }

    private void disposeColors() {
        addedBg.dispose();
        removedBg.dispose();
        gutterBg.dispose();
        gutterLine.dispose();
        changedBgL.dispose();
        paddingBg.dispose();
        foldMarkerBg.dispose();
        foldLinkFg.dispose();
        innerChangedL.dispose();
        innerChangedR.dispose();
        selectedBorder.dispose();
        dirtyHeaderBg.dispose();
        findHighlightBg.dispose();
        findCurrentBg.dispose();
        pressedBg.dispose();
        btnActiveFg.dispose();
        btnFadedFg.dispose();
        saveActiveFg.dispose();
    }

    /** Embedded UI: headers + diff panels only (no toolbar, no status bar). */
    private void createEmbeddedUI(Composite parent) {
        Composite main = new Composite(parent, SWT.NONE);
        GridLayout mainLayout = new GridLayout(1, false);
        mainLayout.marginHeight = 0;
        mainLayout.marginWidth = 0;
        mainLayout.verticalSpacing = 0;
        main.setLayout(mainLayout);
        main.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        if (labelFont == null) labelFont = new Font(main.getDisplay(), AppTheme.SANS_FONT, 11, SWT.NORMAL);
        if (iconFont == null) iconFont = new Font(main.getDisplay(), AppTheme.MONO_FONT, 16, SWT.BOLD);
        if (navIconColor == null) navIconColor = new Color(main.getDisplay(), 0, 0, 180);

        // File path headers
        Composite headers = new Composite(main, SWT.NONE);
        headers.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout hdrLayout = new GridLayout(10, false);
        hdrLayout.marginHeight = 2;
        hdrLayout.marginWidth = 4;
        headers.setLayout(hdrLayout);

        leftLabel = new Label(headers, SWT.NONE);
        leftLabel.setText(leftFilePath != null ? "  " + leftFilePath : "  Left file");
        leftLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label gutterLabel = new Label(headers, SWT.CENTER);
        gutterLabel.setText("");
        GridData gutterGd2 = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        gutterGd2.widthHint = GUTTER_WIDTH;
        gutterLabel.setLayoutData(gutterGd2);

        rightLabel = new Label(headers, SWT.NONE);
        rightLabel.setText(rightFilePath != null ? "  " + rightFilePath : "  Right file");
        rightLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Prev / Next hunk navigation
        createToolbarButton(headers, "\u2191", "Prev", iconFont, labelFont, navIconColor,
                this::goToPrevChange);
        changeIndicator = new Label(headers, SWT.CENTER);
        changeIndicator.setFont(labelFont);
        changeIndicator.setForeground(AppTheme.lineNumFg);
        GridData ciGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        ciGd.widthHint = 60;
        changeIndicator.setLayoutData(ciGd);
        createToolbarButton(headers, "\u2193", "Next", iconFont, labelFont, navIconColor,
                this::goToNextChange);

        createWsToggle(headers);
        createCollapseToggle(headers);
        createContextButton(headers);
        createUnifiedToggle(headers);

        // Separator below headers
        Label sep2 = new Label(main, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Find bar (hidden by default)
        createFindBar(main);

        // SashForm with 2 children
        mainSash = new SashForm(main, SWT.HORIZONTAL);
        mainSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        SashForm sash = mainSash;

        // Left panel: line numbers + styled text
        Composite leftPanel = new Composite(sash, SWT.NONE);
        GridLayout lpLayout = new GridLayout(2, false);
        lpLayout.marginHeight = 0; lpLayout.marginWidth = 0; lpLayout.horizontalSpacing = 0;
        leftPanel.setLayout(lpLayout);

        lineNumberLeft = new Canvas(leftPanel, SWT.DOUBLE_BUFFERED);
        GridData lnLeftGd = new GridData(SWT.RIGHT, SWT.FILL, false, true);
        lnLeftGd.widthHint = lineNumberWidth;
        lineNumberLeft.setLayoutData(lnLeftGd);
        lineNumberLeft.setBackground(AppTheme.lineNumBg);

        leftText = createStyledText(leftPanel);
        leftText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Right panel: gutter + line numbers + styled text
        Composite rightPanel = new Composite(sash, SWT.NONE);
        GridLayout rpLayout = new GridLayout(3, false);
        rpLayout.marginHeight = 0; rpLayout.marginWidth = 0; rpLayout.horizontalSpacing = 0;
        rightPanel.setLayout(rpLayout);

        gutter = new Canvas(rightPanel, SWT.DOUBLE_BUFFERED);
        GridData gutterGd = new GridData(SWT.LEFT, SWT.FILL, false, true);
        gutterGd.widthHint = GUTTER_WIDTH;
        gutter.setLayoutData(gutterGd);
        gutter.setBackground(gutterBg);

        lineNumberRight = new Canvas(rightPanel, SWT.DOUBLE_BUFFERED);
        GridData lnRightGd = new GridData(SWT.LEFT, SWT.FILL, false, true);
        lnRightGd.widthHint = lineNumberWidth;
        lineNumberRight.setLayoutData(lnRightGd);
        lineNumberRight.setBackground(AppTheme.lineNumBg);

        rightText = createStyledText(rightPanel);
        rightText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        sash.setWeights(new int[] { 50, 50 });

        // Unified view panel (hidden by default)
        unifiedPanel = new Composite(main, SWT.NONE);
        GridLayout uniLayout = new GridLayout(2, false);
        uniLayout.marginHeight = 0; uniLayout.marginWidth = 0; uniLayout.horizontalSpacing = 0;
        unifiedPanel.setLayout(uniLayout);
        GridData uniGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        uniGd.exclude = true;
        unifiedPanel.setLayoutData(uniGd);
        unifiedPanel.setVisible(false);

        lineNumberUnified = new Canvas(unifiedPanel, SWT.DOUBLE_BUFFERED);
        GridData lnUniGd = new GridData(SWT.RIGHT, SWT.FILL, false, true);
        lnUniGd.widthHint = unifiedLnWidth;
        lineNumberUnified.setLayoutData(lnUniGd);
        lineNumberUnified.setBackground(AppTheme.lineNumBg);

        unifiedText = new StyledText(unifiedPanel, SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        unifiedText.setFont(monoFont);
        unifiedText.setEditable(false);
        unifiedText.setWordWrap(false);
        unifiedText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        lineNumberUnified.addPaintListener(this::paintLineNumbersUnified);
        unifiedText.addPaintListener(e -> lineNumberUnified.redraw());

        // Paint listeners
        lineNumberLeft.addPaintListener(e -> paintLineNumbers(e, leftText));
        lineNumberRight.addPaintListener(e -> paintLineNumbers(e, rightText));
        gutter.addPaintListener(this::paintGutter);
        setupGutterMouseListeners();

        // Scroll sync
        leftText.addPaintListener(e -> {
            int top = leftText.getTopPixel();
            if (top != lastTopPixelLeft) {
                lastTopPixelLeft = top;
                lineNumberLeft.redraw();
                gutter.redraw();
                if (!updating) {
                    updating = true;
                    rightText.setTopPixel(top);
                    lastTopPixelRight = rightText.getTopPixel();
                    lineNumberRight.redraw();
                    updating = false;
                }
            }
            drawGapLines(e.gc, leftText, true);
        });
        rightText.addPaintListener(e -> {
            int top = rightText.getTopPixel();
            if (top != lastTopPixelRight) {
                lastTopPixelRight = top;
                lineNumberRight.redraw();
                gutter.redraw();
                if (!updating) {
                    updating = true;
                    leftText.setTopPixel(top);
                    lastTopPixelLeft = leftText.getTopPixel();
                    lineNumberLeft.redraw();
                    updating = false;
                }
            }
            drawGapLines(e.gc, rightText, false);
        });
        setupFoldClickListeners();
    }

    private void createUI(Shell shell) {
        Composite main = new Composite(shell, SWT.NONE);
        GridLayout mainLayout = new GridLayout(1, false);
        mainLayout.marginHeight = 0;
        mainLayout.marginWidth = 0;
        mainLayout.verticalSpacing = 0;
        main.setLayout(mainLayout);

        // Toolbar
        Composite toolbar = new Composite(main, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout tbLayout = new GridLayout(16, false);
        tbLayout.marginHeight = 4;
        tbLayout.marginWidth = 8;
        tbLayout.horizontalSpacing = 0;
        toolbar.setLayout(tbLayout);

        iconFont = new Font(main.getDisplay(), AppTheme.MONO_FONT, 16, SWT.BOLD);
        labelFont = new Font(main.getDisplay(), AppTheme.SANS_FONT, 11, SWT.NORMAL);
        navIconColor = new Color(main.getDisplay(), 0, 0, 180);
        createToolbarButton(toolbar, "\u2191", "Prev. Change", iconFont, labelFont, navIconColor,
            this::goToPrevChange);
        createToolbarButton(toolbar, "\u2193", "Next Change", iconFont, labelFont, navIconColor,
            this::goToNextChange);

        changeIndicator = new Label(toolbar, SWT.CENTER);
        changeIndicator.setFont(labelFont);
        changeIndicator.setForeground(AppTheme.lineNumFg);
        GridData ciGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        ciGd.widthHint = 100;
        changeIndicator.setLayoutData(ciGd);

        Label vsep = new Label(toolbar, SWT.SEPARATOR | SWT.VERTICAL);
        GridData vsepGd = new GridData(SWT.CENTER, SWT.FILL, false, true);
        vsepGd.heightHint = 40;
        vsep.setLayoutData(vsepGd);

        createToolbarButton(toolbar, "\u00BB", "Take Left", iconFont, labelFont, navIconColor,
            this::takeLeft);
        createToolbarButton(toolbar, "\u00AB", "Take Right", iconFont, labelFont, navIconColor,
            this::takeRight);
        createToolbarButton(toolbar, "\u21C4", "Swap", iconFont, labelFont, navIconColor,
            this::swapSides);

        Label vsep2 = new Label(toolbar, SWT.SEPARATOR | SWT.VERTICAL);
        GridData vsep2Gd = new GridData(SWT.CENTER, SWT.FILL, false, true);
        vsep2Gd.heightHint = 40;
        vsep2.setLayoutData(vsep2Gd);

        saveButton = createToolbarIconButton(toolbar,
            (gc, canvas) -> paintSaveIcon(gc, canvas, saveArmed ? saveActiveFg : btnFadedFg),
            "Save", labelFont, this::saveFiles);
        saveIcon = (Canvas) saveButton.getChildren()[0];
        setSaveButtonEnabled(false);

        Label vsep3 = new Label(toolbar, SWT.SEPARATOR | SWT.VERTICAL);
        GridData vsep3Gd = new GridData(SWT.CENTER, SWT.FILL, false, true);
        vsep3Gd.heightHint = 40;
        vsep3.setLayoutData(vsep3Gd);

        createWsToggle(toolbar);
        createCollapseToggle(toolbar);
        createContextButton(toolbar);
        createUnifiedToggle(toolbar);

        Label spacer = new Label(toolbar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createToolbarButton(toolbar, "\u2026", "Open Left", iconFont, labelFont, navIconColor,
            this::openLeftFile);
        createToolbarButton(toolbar, "\u2026", "Open Right", iconFont, labelFont, navIconColor,
            this::openRightFile);

        // Separator below toolbar
        Label sep = new Label(main, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // File path headers
        Composite headers = new Composite(main, SWT.NONE);
        headers.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout hdrLayout = new GridLayout(3, false);
        hdrLayout.marginHeight = 2;
        hdrLayout.marginWidth = 4;
        headers.setLayout(hdrLayout);

        leftLabel = new Label(headers, SWT.NONE);
        leftLabel.setText(leftFilePath != null ? "  " + leftFilePath : "  Left file");
        leftLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label gutterLabel = new Label(headers, SWT.CENTER);
        gutterLabel.setText("");
        GridData gutterGd2 = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        gutterGd2.widthHint = GUTTER_WIDTH;
        gutterLabel.setLayoutData(gutterGd2);

        rightLabel = new Label(headers, SWT.NONE);
        rightLabel.setText(rightFilePath != null ? "  " + rightFilePath : "  Right file");
        rightLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Separator below headers
        Label sep2 = new Label(main, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Find bar (hidden by default)
        createFindBar(main);

        // SashForm with 2 children: single draggable divider
        mainSash = new SashForm(main, SWT.HORIZONTAL);
        mainSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        SashForm sash = mainSash;

        // Left panel: line numbers + styled text
        Composite leftPanel = new Composite(sash, SWT.NONE);
        GridLayout lpLayout = new GridLayout(2, false);
        lpLayout.marginHeight = 0; lpLayout.marginWidth = 0; lpLayout.horizontalSpacing = 0;
        leftPanel.setLayout(lpLayout);

        lineNumberLeft = new Canvas(leftPanel, SWT.DOUBLE_BUFFERED);
        GridData lnLeftGd = new GridData(SWT.RIGHT, SWT.FILL, false, true);
        lnLeftGd.widthHint = lineNumberWidth;
        lineNumberLeft.setLayoutData(lnLeftGd);
        lineNumberLeft.setBackground(AppTheme.lineNumBg);

        leftText = createStyledText(leftPanel);
        leftText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Right panel: gutter + line numbers + styled text
        Composite rightPanel = new Composite(sash, SWT.NONE);
        GridLayout rpLayout = new GridLayout(3, false);
        rpLayout.marginHeight = 0; rpLayout.marginWidth = 0; rpLayout.horizontalSpacing = 0;
        rightPanel.setLayout(rpLayout);

        gutter = new Canvas(rightPanel, SWT.DOUBLE_BUFFERED);
        GridData gutterGd = new GridData(SWT.LEFT, SWT.FILL, false, true);
        gutterGd.widthHint = GUTTER_WIDTH;
        gutter.setLayoutData(gutterGd);
        gutter.setBackground(gutterBg);

        lineNumberRight = new Canvas(rightPanel, SWT.DOUBLE_BUFFERED);
        GridData lnRightGd = new GridData(SWT.LEFT, SWT.FILL, false, true);
        lnRightGd.widthHint = lineNumberWidth;
        lineNumberRight.setLayoutData(lnRightGd);
        lineNumberRight.setBackground(AppTheme.lineNumBg);

        rightText = createStyledText(rightPanel);
        rightText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // SashForm weights: left 50%, right 50%
        sash.setWeights(new int[] { 50, 50 });

        // Unified view panel (hidden by default)
        unifiedPanel = new Composite(main, SWT.NONE);
        GridLayout uniLayout2 = new GridLayout(2, false);
        uniLayout2.marginHeight = 0; uniLayout2.marginWidth = 0; uniLayout2.horizontalSpacing = 0;
        unifiedPanel.setLayout(uniLayout2);
        GridData uniGd2 = new GridData(SWT.FILL, SWT.FILL, true, true);
        uniGd2.exclude = true;
        unifiedPanel.setLayoutData(uniGd2);
        unifiedPanel.setVisible(false);

        lineNumberUnified = new Canvas(unifiedPanel, SWT.DOUBLE_BUFFERED);
        GridData lnUniGd2 = new GridData(SWT.RIGHT, SWT.FILL, false, true);
        lnUniGd2.widthHint = unifiedLnWidth;
        lineNumberUnified.setLayoutData(lnUniGd2);
        lineNumberUnified.setBackground(AppTheme.lineNumBg);

        unifiedText = new StyledText(unifiedPanel, SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        unifiedText.setFont(monoFont);
        unifiedText.setEditable(false);
        unifiedText.setWordWrap(false);
        unifiedText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        lineNumberUnified.addPaintListener(this::paintLineNumbersUnified);
        unifiedText.addPaintListener(e -> lineNumberUnified.redraw());

        // Status bar
        Label sep3 = new Label(main, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep3.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        statusBar = new Label(main, SWT.NONE);
        statusBar.setFont(labelFont);
        statusBar.setForeground(AppTheme.lineNumFg);
        GridData sbGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sbGd.horizontalIndent = 8;
        statusBar.setLayoutData(sbGd);

        // Update status bar on caret movement
        leftText.addCaretListener(e -> updateStatusBar(leftText));
        rightText.addCaretListener(e -> updateStatusBar(rightText));

        // Keyboard shortcuts
        main.getDisplay().addFilter(SWT.KeyDown, event -> {
            if ((event.stateMask & SWT.MOD1) != 0) {
                switch (event.keyCode) {
                    case SWT.ARROW_UP:   goToPrevChange(); event.doit = false; break;
                    case SWT.ARROW_DOWN: goToNextChange(); event.doit = false; break;
                    case SWT.ARROW_LEFT: takeRight();      event.doit = false; break;
                    case SWT.ARROW_RIGHT:takeLeft();       event.doit = false; break;
                    case 's':            saveFiles();       event.doit = false; break;
                    case 'f':            showFind();        event.doit = false; break;
                    case 'g':            goToLine();        event.doit = false; break;
                }
            }
        });

        // Paint listeners
        lineNumberLeft.addPaintListener(e -> paintLineNumbers(e, leftText));
        lineNumberRight.addPaintListener(e -> paintLineNumbers(e, rightText));
        gutter.addPaintListener(this::paintGutter);
        setupGutterMouseListeners();

        // Sync scroll and redraw line numbers/gutter only when scroll position changes.
        // Covers all scroll sources: scrollbar, mouse wheel, keyboard (Page Up/Down, arrows).
        // Tracking lastTopPixel prevents cascading redraws.
        leftText.addPaintListener(e -> {
            int top = leftText.getTopPixel();
            if (top != lastTopPixelLeft) {
                lastTopPixelLeft = top;
                lineNumberLeft.redraw();
                gutter.redraw();
                if (!updating) {
                    updating = true;
                    rightText.setTopPixel(top);
                    lastTopPixelRight = rightText.getTopPixel();
                    lineNumberRight.redraw();
                    updating = false;
                }
            }
            drawGapLines(e.gc, leftText, true);
        });
        rightText.addPaintListener(e -> {
            int top = rightText.getTopPixel();
            if (top != lastTopPixelRight) {
                lastTopPixelRight = top;
                lineNumberRight.redraw();
                gutter.redraw();
                if (!updating) {
                    updating = true;
                    leftText.setTopPixel(top);
                    lastTopPixelLeft = leftText.getTopPixel();
                    lineNumberLeft.redraw();
                    updating = false;
                }
            }
            drawGapLines(e.gc, rightText, false);
        });
        setupFoldClickListeners();
    }

    /** Draws a toolbar icon into its canvas, in the tint the button's current state calls for. */
    private interface IconPainter {
        void paint(GC gc, Canvas canvas);
    }

    /**
     * Thin-stroke floppy-disk save icon (Feather "save" geometry): rounded body with the top-right
     * corner chamfered, a shutter open at the top and a label open at the bottom. Replaces the
     * U+1F4BE emoji, which macOS always renders as a fixed-colour glyph and so could never show
     * enabled vs. disabled state. All coordinates are given on an 18-unit grid and scaled to fit.
     */
    private static void paintSaveIcon(GC gc, Canvas canvas, Color fg) {
        Rectangle area = canvas.getClientArea();
        gc.setBackground(canvas.getBackground());
        gc.fillRectangle(area);
        gc.setAntialias(SWT.ON);

        int s = Math.min(area.width, area.height) - 3;
        if (s < 8) return;
        float ox = area.x + (area.width - s) / 2f;
        float oy = area.y + (area.height - s) / 2f;
        float k = s / 18f;              // grid unit → pixels
        float r = 2 * k;                // corner radius
        float cut = 5 * k;              // chamfer on the top-right corner

        gc.setForeground(fg);
        gc.setLineAttributes(new LineAttributes(Math.max(1.3f, 1.7f * k),
            SWT.CAP_ROUND, SWT.JOIN_ROUND));

        Display disp = canvas.getDisplay();
        org.eclipse.swt.graphics.Path body = new org.eclipse.swt.graphics.Path(disp);
        body.moveTo(ox + r, oy);
        body.lineTo(ox + s - cut, oy);
        body.lineTo(ox + s, oy + cut);                                   // chamfer
        body.lineTo(ox + s, oy + s - r);
        body.addArc(ox + s - 2 * r, oy + s - 2 * r, 2 * r, 2 * r, 0, -90);
        body.lineTo(ox + r, oy + s);
        body.addArc(ox, oy + s - 2 * r, 2 * r, 2 * r, 270, -90);
        body.lineTo(ox, oy + r);
        body.addArc(ox, oy, 2 * r, 2 * r, 180, -90);
        body.close();
        gc.drawPath(body);
        body.dispose();

        // Shutter (open at the top)
        org.eclipse.swt.graphics.Path shutter = new org.eclipse.swt.graphics.Path(disp);
        shutter.moveTo(ox + 4 * k, oy);
        shutter.lineTo(ox + 4 * k, oy + 5 * k);
        shutter.lineTo(ox + 12 * k, oy + 5 * k);
        gc.drawPath(shutter);
        shutter.dispose();

        // Label (open at the bottom)
        org.eclipse.swt.graphics.Path label = new org.eclipse.swt.graphics.Path(disp);
        label.moveTo(ox + 4 * k, oy + s);
        label.lineTo(ox + 4 * k, oy + 10 * k);
        label.lineTo(ox + 14 * k, oy + 10 * k);
        label.lineTo(ox + 14 * k, oy + s);
        gc.drawPath(label);
        label.dispose();

        gc.setLineAttributes(new LineAttributes(1));
    }

    private Composite createToolbarButton(Composite parent, String icon, String label,
            Font iconFont, Font labelFont, Color iconColor, Runnable action) {
        Composite btn = newToolbarButtonShell(parent);

        Label iconLbl = new Label(btn, SWT.CENTER);
        iconLbl.setText(icon);
        iconLbl.setFont(iconFont);
        iconLbl.setForeground(iconColor);
        iconLbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        return finishToolbarButton(btn, iconLbl, label, labelFont, action);
    }

    /**
     * Same as {@link #createToolbarButton} but the icon is vector-drawn instead of a glyph, so it
     * stays crisp and can be tinted to reflect the button's state (emoji glyphs ignore foreground).
     */
    private Composite createToolbarIconButton(Composite parent, IconPainter painter, String label,
            Font labelFont, Runnable action) {
        Composite btn = newToolbarButtonShell(parent);

        Canvas iconCanvas = new Canvas(btn, SWT.DOUBLE_BUFFERED);
        GridData icGd = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        icGd.widthHint = ICON_CANVAS_SIZE;
        icGd.heightHint = ICON_CANVAS_SIZE;
        iconCanvas.setLayoutData(icGd);
        iconCanvas.addPaintListener(e -> painter.paint(e.gc, iconCanvas));

        return finishToolbarButton(btn, iconCanvas, label, labelFont, action);
    }

    private Composite newToolbarButtonShell(Composite parent) {
        Composite btn = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 2;
        gl.marginWidth = 8;
        gl.verticalSpacing = 0;
        btn.setLayout(gl);
        return btn;
    }

    /** Adds the caption and wires hover / pressed / click behaviour shared by all toolbar buttons. */
    private Composite finishToolbarButton(Composite btn, Control iconCtl, String label,
            Font labelFont, Runnable action) {
        Label textLbl = new Label(btn, SWT.CENTER);
        textLbl.setText(label);
        textLbl.setFont(labelFont);
        textLbl.setForeground(AppTheme.lineNumFg);
        textLbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        btn.setCursor(btn.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        Control[] parts = { btn, iconCtl, textLbl };
        boolean[] armed = { false };   // mouse went down on this button and hasn't been released

        MouseTrackAdapter hoverListener = new MouseTrackAdapter() {
            @Override
            public void mouseEnter(MouseEvent e) {
                if (!btn.isEnabled()) return;
                setToolbarButtonBg(parts, armed[0] ? pressedBg : AppTheme.hoverBg);
            }
            @Override
            public void mouseExit(MouseEvent e) {
                setToolbarButtonBg(parts, null);
            }
        };
        for (Control c : parts) c.addMouseTrackListener(hoverListener);

        if (action != null) {
            MouseAdapter clickListener = new MouseAdapter() {
                @Override
                public void mouseDown(MouseEvent e) {
                    if (e.button != 1 || !btn.isEnabled()) return;
                    armed[0] = true;
                    setToolbarButtonBg(parts, pressedBg);
                }
                @Override
                public void mouseUp(MouseEvent e) {
                    if (e.button != 1) return;
                    boolean wasArmed = armed[0];
                    armed[0] = false;
                    if (!btn.isEnabled()) return;
                    // Releasing outside the button cancels the click, like a native button
                    boolean inside = btn.getBounds().contains(
                        btn.getDisplay().map((Control) e.widget, btn.getParent(), e.x, e.y));
                    setToolbarButtonBg(parts, inside ? AppTheme.hoverBg : null);
                    if (wasArmed && inside) action.run();
                }
            };
            for (Control c : parts) c.addMouseListener(clickListener);
        }

        return btn;
    }

    private static void setToolbarButtonBg(Control[] parts, Color bg) {
        for (Control c : parts) {
            if (c.isDisposed()) continue;
            c.setBackground(bg);
            if (c instanceof Canvas) c.redraw();
        }
    }

    private void goToNextChange() {
        if (gutterBlocks.isEmpty()) return;
        currentChangeIndex++;
        if (currentChangeIndex >= gutterBlocks.size()) currentChangeIndex = 0; // wrap around
        scrollToChange(currentChangeIndex);
        updateChangeIndicator();
    }

    private void goToPrevChange() {
        if (gutterBlocks.isEmpty()) return;
        currentChangeIndex--;
        if (currentChangeIndex < 0) currentChangeIndex = gutterBlocks.size() - 1; // wrap around
        scrollToChange(currentChangeIndex);
        updateChangeIndicator();
    }

    private void scrollToChange(int index) {
        if (unifiedMode && unifiedText != null && !unifiedText.isDisposed()) {
            if (unifiedHunkLineIndex != null && index < unifiedHunkLineIndex.length) {
                int targetLine = Math.max(0, unifiedHunkLineIndex[index] - 3);
                if (targetLine < unifiedText.getLineCount()) {
                    int pixel = unifiedText.getLinePixel(targetLine) + unifiedText.getTopPixel();
                    unifiedText.setTopPixel(Math.max(0, pixel));
                    lineNumberUnified.redraw();
                }
            }
            return;
        }
        GutterBlock block = gutterBlocks.get(index);
        updating = true;
        // Convert to display line when collapsed
        int displayLine = block.leftStart;
        if (collapseEqual && leftDisplayToOrig != null) {
            for (int d = 0; d < leftDisplayToOrig.length; d++) {
                if (leftDisplayToOrig[d] == block.leftStart) { displayLine = d; break; }
            }
        }
        // With vertical indents aligning both sides, use same topPixel
        int target = Math.max(0, displayLine - 3);
        int pixel = leftText.getLinePixel(target) + leftText.getTopPixel();
        pixel = Math.max(0, pixel);
        leftText.setTopPixel(pixel);
        rightText.setTopPixel(pixel);
        // Place caret at the start of the change
        if (displayLine < leftText.getLineCount()) {
            leftText.setCaretOffset(leftText.getOffsetAtLine(displayLine));
        }
        updating = false;
        redrawAll();
    }

    private void updateChangeIndicator() {
        if (changeIndicator == null || changeIndicator.isDisposed()) return;
        if (gutterBlocks.isEmpty()) {
            changeIndicator.setText("No changes");
        } else if (currentChangeIndex < 0) {
            changeIndicator.setText(gutterBlocks.size() + " changes");
        } else {
            changeIndicator.setText((currentChangeIndex + 1) + " / " + gutterBlocks.size());
        }
        changeIndicator.getParent().layout(true);
    }

    private void updateStatusBar(StyledText widget) {
        if (statusBar == null || statusBar.isDisposed()) return;
        int offset = widget.getCaretOffset();
        int line = widget.getLineAtOffset(offset) + 1;
        int col = offset - widget.getOffsetAtLine(line - 1) + 1;
        String side = (widget == leftText) ? "Left" : "Right";

        int added = 0, removed = 0, changed = 0;
        if (leftDiffTypes != null) {
            for (DiffType dt : leftDiffTypes) {
                if (dt == DiffType.REMOVED) removed++;
                else if (dt == DiffType.CHANGED) changed++;
            }
        }
        if (rightDiffTypes != null) {
            for (DiffType dt : rightDiffTypes) {
                if (dt == DiffType.ADDED) added++;
            }
        }

        String dirtyInfo = "";
        if (leftDirty || rightDirty) {
            dirtyInfo = "    |    Modified (unsaved)";
        }
        statusBar.setText(side + "  Ln " + line + ", Col " + col
            + "    |    " + gutterBlocks.size() + " changes: +" + added + " / -" + removed + " / ~" + changed
            + dirtyInfo);
    }

    private void updateDirtyState() {
        if (leftText == null || leftText.isDisposed()) return;
        boolean wasLeftDirty = leftDirty, wasRightDirty = rightDirty;
        leftDirty = !leftText.getText().equals(leftOriginal);
        rightDirty = !rightText.getText().equals(rightOriginal);
        if (wasLeftDirty == leftDirty && wasRightDirty == rightDirty) return;

        // Update title bar
        Shell shell = leftText.getShell();
        if (shell != null && !shell.isDisposed()) {
            String title = shell.getText();
            boolean anyDirty = leftDirty || rightDirty;
            if (anyDirty && !title.startsWith("* ")) {
                shell.setText("* " + title);
            } else if (!anyDirty && title.startsWith("* ")) {
                shell.setText(title.substring(2));
            }
        }

        // Update header labels
        if (leftLabel != null && !leftLabel.isDisposed()) {
            String base = leftFilePath != null ? leftFilePath : "Left file";
            leftLabel.setText(leftDirty ? "  * " + base : "  " + base);
            leftLabel.getParent().setBackground(leftDirty ? dirtyHeaderBg : null);
        }
        if (rightLabel != null && !rightLabel.isDisposed()) {
            String base = rightFilePath != null ? rightFilePath : "Right file";
            rightLabel.setText(rightDirty ? "  * " + base : "  " + base);
        }

        // Update save button
        setSaveButtonEnabled(leftDirty || rightDirty);

        // Refresh status bar
        if (statusBar != null && !statusBar.isDisposed()) {
            updateStatusBar(leftText);
        }
    }

    private void setSaveButtonEnabled(boolean enabled) {
        if (saveButton == null || saveButton.isDisposed()) return;
        saveArmed = enabled;
        // Enabled: blue icon + near-black caption, so pending changes are obvious.
        // Disabled: both washed out to a faint gray.
        if (saveIcon != null && !saveIcon.isDisposed()) saveIcon.redraw();
        Control[] children = saveButton.getChildren();
        if (children.length >= 2 && children[1] instanceof Label) {
            ((Label) children[1]).setForeground(enabled ? btnActiveFg : btnFadedFg);
        }
        if (!enabled) {
            // Saving usually happens with the pointer still on the button: drop the hover/pressed
            // tint so it doesn't stay highlighted after going inactive.
            Control[] parts = new Control[children.length + 1];
            parts[0] = saveButton;
            System.arraycopy(children, 0, parts, 1, children.length);
            setToolbarButtonBg(parts, null);
        }
        saveButton.setEnabled(enabled);
        saveButton.setCursor(enabled
            ? saveButton.getDisplay().getSystemCursor(SWT.CURSOR_HAND)
            : saveButton.getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
    }

    // Take Left (»): copy left side content into right side for current change
    private void takeLeft() {
        if (gutterBlocks.isEmpty() || currentChangeIndex < 0) return;
        GutterBlock block = gutterBlocks.get(currentChangeIndex);
        String replacement = getLinesText(leftText, block.leftStart, block.leftEnd);
        replaceLinesText(rightText, block.rightStart, block.rightEnd, replacement);
    }

    // Take Right («): copy right side content into left side for current change
    private void takeRight() {
        if (gutterBlocks.isEmpty() || currentChangeIndex < 0) return;
        GutterBlock block = gutterBlocks.get(currentChangeIndex);
        String replacement = getLinesText(rightText, block.rightStart, block.rightEnd);
        replaceLinesText(leftText, block.leftStart, block.leftEnd, replacement);
    }

    private void openLeftFile() {
        FileDialog dialog = new FileDialog(leftText.getShell(), SWT.OPEN);
        dialog.setText("Open Left File");
        String path = dialog.open();
        if (path != null) {
            leftFilePath = path;
            leftLabel.setText("  " + leftFilePath);
            try {
                String content = Files.readString(Path.of(path)).replace("\r\n", "\n").replace("\r", "\n");
                updating = true;
                leftText.setText(content);
                updating = false;
                leftOriginal = content;
                leftDirty = false;
            } catch (IOException e) {
                updating = true;
                leftText.setText("Error reading file: " + path + "\n" + e.getMessage());
                updating = false;
                leftOriginal = leftText.getText();
                leftDirty = false;
            }
            recomputeDiff();
            updateDirtyState();
            leftText.getShell().setText("[" + Paths.get(leftFilePath).getFileName() + "] - File Compare");
        }
    }

    private void openRightFile() {
        FileDialog dialog = new FileDialog(rightText.getShell(), SWT.OPEN);
        dialog.setText("Open Right File");
        String path = dialog.open();
        if (path != null) {
            rightFilePath = path;
            rightLabel.setText("  " + rightFilePath);
            try {
                String content = Files.readString(Path.of(path)).replace("\r\n", "\n").replace("\r", "\n");
                updating = true;
                rightText.setText(content);
                updating = false;
                rightOriginal = content;
                rightDirty = false;
            } catch (IOException e) {
                updating = true;
                rightText.setText("Error reading file: " + path + "\n" + e.getMessage());
                updating = false;
                rightOriginal = rightText.getText();
                rightDirty = false;
            }
            recomputeDiff();
            updateDirtyState();
            String titleFile = leftFilePath != null ? leftFilePath : rightFilePath;
            rightText.getShell().setText("[" + Paths.get(titleFile).getFileName() + "] - File Compare");
        }
    }

    private void saveFiles() {
        boolean saved = false;
        if (leftFilePath != null) {
            try {
                Files.writeString(Path.of(leftFilePath), leftText.getText());
                saved = true;
            } catch (IOException e) {
                if (statusBar != null && !statusBar.isDisposed())
                    statusBar.setText("Error saving " + leftFilePath + ": " + e.getMessage());
                return;
            }
        }
        if (rightFilePath != null) {
            try {
                Files.writeString(Path.of(rightFilePath), rightText.getText());
                saved = true;
            } catch (IOException e) {
                if (statusBar != null && !statusBar.isDisposed())
                    statusBar.setText("Error saving " + rightFilePath + ": " + e.getMessage());
                return;
            }
        }
        if (saved) {
            leftOriginal = leftText.getText();
            rightOriginal = rightText.getText();
            // Leave leftDirty/rightDirty alone: updateDirtyState() only refreshes the title,
            // headers and Save button when it sees the flags *change*, so clearing them here
            // would make it exit early and leave the whole UI still looking unsaved.
            updateDirtyState();
            if (statusBar != null && !statusBar.isDisposed()) {
                statusBar.setText("Saved.");
            }
        }
    }

    private String getLinesText(StyledText widget, int lineStart, int lineEnd) {
        if (lineStart >= lineEnd) return "";
        int startOffset = widget.getOffsetAtLine(lineStart);
        int endOffset = lineEnd < widget.getLineCount()
            ? widget.getOffsetAtLine(lineEnd)
            : widget.getCharCount();
        return widget.getText(startOffset, endOffset - 1);
    }

    private void replaceLinesText(StyledText widget, int lineStart, int lineEnd, String replacement) {
        int startOffset = lineStart < widget.getLineCount()
            ? widget.getOffsetAtLine(lineStart)
            : widget.getCharCount();
        int endOffset = lineEnd < widget.getLineCount()
            ? widget.getOffsetAtLine(lineEnd)
            : widget.getCharCount();
        widget.replaceTextRange(startOffset, endOffset - startOffset, replacement);
    }

    private StyledText createStyledText(Composite parent) {
        StyledText st = new StyledText(parent, SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        st.setFont(monoFont);
        st.setEditable(true);
        st.setWordWrap(false);
        st.addExtendedModifyListener(e -> {
            if (!updating) {
                st.getDisplay().asyncExec(() -> {
                    try {
                        recomputeDiff();
                        updateDirtyState();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });
            }
        });
        st.addListener(SWT.FocusIn, e -> lastFocusedText = st);
        st.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) != 0) {
                if (e.keyCode == 'f') { showFind(); e.doit = false; }
                else if (e.keyCode == 'g') { goToLine(); e.doit = false; }
            }
        });
        return st;
    }

    /**
     * Compute and apply all syntax highlighting + inner-line change styles
     * for the entire widget in a single pass. Called from recomputeDiff().
     * We use setStyleRanges() instead of LineStyleListener so that
     * setLineVerticalIndent() works (it silently no-ops with a listener).
     */
    private void applySyntaxStyles(StyledText widget, boolean isLeft) {
        int lineCount = widget.getLineCount();
        List<StyleRange> allStyles = new ArrayList<>();
        boolean inComment = false;

        List<int[]> innerRanges = isLeft ? leftInnerRanges : rightInnerRanges;
        Color innerColor = isLeft ? innerChangedL : innerChangedR;

        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            int lineOffset = widget.getOffsetAtLine(lineIndex);
            String line = widget.getLine(lineIndex);
            List<StyleRange> lineStyles = new ArrayList<>();

            String trimmed = line.trim();

            if (inComment) {
                int closeIdx = line.indexOf("*/");
                if (closeIdx >= 0) {
                    int commentEnd = closeIdx + 2;
                    lineStyles.add(makeStyle(lineOffset, commentEnd, AppTheme.commentColor, false, true));
                    SyntaxHighlighter.highlightLine(line, lineOffset, commentEnd, line.length(), lineStyles);
                } else {
                    lineStyles.add(makeStyle(lineOffset, line.length(), AppTheme.commentColor, false, true));
                }
            } else if (trimmed.startsWith("#")) {
                lineStyles.add(makeStyle(lineOffset, line.length(), AppTheme.preprocColor, false, true));
            } else {
                SyntaxHighlighter.highlightLine(line, lineOffset, 0, line.length(), lineStyles);
            }

            // Update block comment state for next line
            inComment = endsInBlockComment(line, inComment);

            // Inner-line change highlights
            if (innerRanges != null && lineIndex < innerRanges.size()) {
                int[] ranges = innerRanges.get(lineIndex);
                if (ranges != null) {
                    for (int r = 0; r < ranges.length; r += 2) {
                        int start = ranges[r];
                        int end = ranges[r + 1];
                        if (end > start) {
                            StyleRange sr = new StyleRange();
                            sr.start = lineOffset + start;
                            sr.length = end - start;
                            sr.background = innerColor;
                            lineStyles.add(sr);
                        }
                    }
                }
            }

            allStyles.addAll(mergeStyles(lineStyles));
        }

        // Filter out invalid ranges and remove overlaps (required by setStyleRanges)
        int charCount = widget.getCharCount();
        allStyles.removeIf(sr -> sr.start < 0 || sr.length <= 0 || sr.start + sr.length > charCount);
        allStyles.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(a.length, b.length));
        List<StyleRange> cleaned = new ArrayList<>();
        int lastEnd = 0;
        for (StyleRange sr : allStyles) {
            if (sr.start < lastEnd) {
                // Overlaps with previous — trim or skip
                int newStart = lastEnd;
                int newLen = sr.length - (newStart - sr.start);
                if (newLen <= 0) continue;
                sr.start = newStart;
                sr.length = newLen;
            }
            cleaned.add(sr);
            lastEnd = sr.start + sr.length;
        }
        widget.setStyleRanges(cleaned.toArray(new StyleRange[0]));
    }

    /** Track whether a line ends inside a block comment */
    private boolean endsInBlockComment(String line, boolean startedInComment) {
        boolean inComment = startedInComment;
        int i = 0;
        while (i < line.length()) {
            if (inComment) {
                if (line.charAt(i) == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    inComment = false;
                    i += 2;
                } else {
                    i++;
                }
            } else {
                char c = line.charAt(i);
                if (c == '"') { i = findStringEnd(line, i); continue; }
                if (c == '\'') { i = findCharEnd(line, i); continue; }
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                    inComment = true;
                    i += 2;
                    continue;
                }
                i++;
            }
        }
        return inComment;
    }

    // Merge overlapping styles: when both syntax (fg) and inner-line (bg) exist,
    // combine them into one StyleRange with both fg and bg
    private List<StyleRange> mergeStyles(List<StyleRange> styles) {
        if (styles.size() <= 1) return styles;

        // Separate: bg-only (inner-line) vs fg (syntax)
        List<StyleRange> bgStyles = new ArrayList<>();
        List<StyleRange> fgStyles = new ArrayList<>();
        for (StyleRange sr : styles) {
            if (sr.foreground == null && sr.background != null && sr.fontStyle == 0)
                bgStyles.add(sr);
            else
                fgStyles.add(sr);
        }

        if (bgStyles.isEmpty()) return fgStyles;

        // For each fg style, check if it overlaps with any bg style and set bg
        for (StyleRange fg : fgStyles) {
            for (StyleRange bg : bgStyles) {
                if (fg.start < bg.start + bg.length && fg.start + fg.length > bg.start) {
                    fg.background = bg.background;
                }
            }
        }

        // Add bg ranges that have no overlapping fg style (plain text with bg)
        for (StyleRange bg : bgStyles) {
            boolean covered = false;
            for (StyleRange fg : fgStyles) {
                if (fg.start <= bg.start && fg.start + fg.length >= bg.start + bg.length) {
                    covered = true; break;
                }
            }
            if (!covered) {
                fgStyles.add(bg);
            }
        }

        fgStyles.sort((a, b) -> Integer.compare(a.start, b.start));
        return fgStyles;
    }

    private int findCharEnd(String text, int start) {
        int i = start + 1;
        if (i >= text.length()) return start + 1;
        if (text.charAt(i) == '\\') {
            i++; // skip backslash
            if (i >= text.length()) return start + 1;
            if (text.charAt(i) == 'u') {
                // Unicode escape: e.g. backslash-u0041
                i++;
                for (int d = 0; d < 4 && i < text.length() && isHexDigit(text.charAt(i)); d++) i++;
            } else {
                i++; // skip escaped char (\n, \\, \', \t, etc.)
            }
        } else {
            i++; // skip the character
        }
        if (i < text.length() && text.charAt(i) == '\'') {
            return i + 1;
        }
        return start + 1; // not a valid char literal, treat as apostrophe
    }

    private boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private int findStringEnd(String line, int start) {
        boolean escaped = false;
        for (int i = start + 1; i < line.length(); i++) {
            if (escaped) {
                escaped = false;
                continue;
            }
            if (line.charAt(i) == '\\') {
                escaped = true;
                continue;
            }
            if (line.charAt(i) == '"') {
                return i + 1;
            }
        }
        return line.length();
    }

    private StyleRange makeStyle(int start, int length, Color fg, boolean bold, boolean italic) {
        StyleRange sr = new StyleRange();
        sr.start = start;
        sr.length = length;
        sr.foreground = fg;
        if (bold) sr.fontStyle |= SWT.BOLD;
        if (italic) sr.fontStyle |= SWT.ITALIC;
        return sr;
    }

    /**
     * Resize the line-number canvases so the largest line number actually fits.
     * The numbers are right-aligned, so a fixed width silently clipped anything
     * wider than it (with Consolas 13 a 4-digit number landed at x = -14, i.e.
     * files over 999 lines lost their leading digits).
     */
    private void updateLineNumberWidths() {
        if (lineNumberLeft == null || lineNumberLeft.isDisposed()) return;

        if (digitWidth <= 0) {
            GC gc = new GC(lineNumberLeft);
            gc.setFont(monoFont);
            digitWidth = gc.textExtent("0").x;
            gc.dispose();
        }

        int maxLines = Math.max(leftText.getLineCount(), rightText.getLineCount());
        int digits = String.valueOf(Math.max(maxLines, 1)).length();
        int numWidth = digits * digitWidth;

        int newSide = Math.max(LINE_NUMBER_MIN_WIDTH, numWidth + LN_PAD_LEFT + LN_PAD_RIGHT);
        // Unified draws two columns, each right-aligned inside its own half.
        int newUnified = Math.max(UNIFIED_LN_MIN_WIDTH, 2 * (numWidth + UNI_LN_PAD * 2));

        if (newSide == lineNumberWidth && newUnified == unifiedLnWidth) return;
        lineNumberWidth = newSide;
        unifiedLnWidth = newUnified;

        applyWidthHint(lineNumberLeft, lineNumberWidth);
        applyWidthHint(lineNumberRight, lineNumberWidth);
        applyWidthHint(lineNumberUnified, unifiedLnWidth);

        if (lineNumberLeft.getParent() != null) lineNumberLeft.getParent().layout(true, true);
        if (lineNumberRight.getParent() != null) lineNumberRight.getParent().layout(true, true);
        if (lineNumberUnified != null && !lineNumberUnified.isDisposed()
                && lineNumberUnified.getParent() != null) {
            lineNumberUnified.getParent().layout(true, true);
        }
    }

    private void applyWidthHint(Canvas canvas, int width) {
        if (canvas == null || canvas.isDisposed()) return;
        Object ld = canvas.getLayoutData();
        if (ld instanceof GridData) {
            ((GridData) ld).widthHint = width;
        }
    }

    private void paintLineNumbers(PaintEvent e, StyledText text) {
        GC gc = e.gc;
        gc.setFont(monoFont);
        gc.setForeground(AppTheme.lineNumFg);
        gc.setBackground(AppTheme.lineNumBg);

        int lineHeight = computedLineHeight > 0 ? computedLineHeight : text.getLineHeight();
        int topLine = text.getLineIndex(0);  // line at top of visible client area
        int canvasHeight = ((Canvas) e.widget).getBounds().height;
        int visibleLines = (canvasHeight / lineHeight) + 2;

        for (int i = 0; i < visibleLines; i++) {
            int lineIndex = topLine + i;
            if (lineIndex >= text.getLineCount()) break;

            // getLinePixel returns top of indent area; text starts at next line's pixel - lineHeight
            int y = text.getLinePixel(lineIndex + 1) - lineHeight;

            // Use line map when collapsed
            int origLine = lineIndex;
            if (collapseEqual) {
                int[] map = (text == leftText) ? leftDisplayToOrig : rightDisplayToOrig;
                if (map != null && lineIndex < map.length) {
                    origLine = map[lineIndex];
                    if (origLine < 0) {
                        gc.setForeground(gutterLine);
                        gc.drawString("\u22EF", 8, y, true);
                        gc.setForeground(AppTheme.lineNumFg);
                        continue;
                    }
                }
            }

            String num = String.valueOf(origLine + 1);
            int textWidth = gc.textExtent(num).x;
            gc.drawString(num, Math.max(0, lineNumberWidth - textWidth - LN_PAD_RIGHT), y, true);
        }
    }

    /**
     * Appends a smooth S-curve from (xFrom, y0) to (xTo, y1). Both control points sit on the
     * horizontal midpoint, so the curve leaves and enters the panel edges perfectly horizontal
     * and does all its bending in the middle.
     */
    private static void curveTo(org.eclipse.swt.graphics.Path path,
                                float xFrom, float xTo, float y0, float y1) {
        float mid = (xFrom + xTo) * 0.5f;
        path.cubicTo(mid, y0, mid, y1, xTo, y1);
    }

    private void paintGutter(PaintEvent e) {
        GC gc = e.gc;
        gc.setBackground(gutterBg);
        gc.fillRectangle(e.x, e.y, e.width, e.height);

        if (gutterBlocks.isEmpty()) return;

        int gutterWidth = gutter.getBounds().width;
        int gutterHeight = gutter.getBounds().height;
        int lineHeight = computedLineHeight > 0 ? computedLineHeight : leftText.getLineHeight();

        for (int idx = 0; idx < gutterBlocks.size(); idx++) {
            GutterBlock block = gutterBlocks.get(idx);
            boolean isSelected = (idx == currentChangeIndex);
            Color leftColor, rightColor;
            switch (block.type) {
                case ADDED:   leftColor = paddingBg; rightColor = addedBg; break;
                case REMOVED: leftColor = removedBg; rightColor = paddingBg; break;
                case CHANGED: leftColor = changedBgL; rightColor = addedBg; break;
                default: continue;
            }

            // Get pixel positions from StyledText line positions
            int leftTopY, leftBotY, rightTopY, rightBotY;

            if (block.type == DiffType.ADDED) {
                // Gap on left: collapse left side to bottom gap line (wedge shape)
                int gapBot = leftText.getLinePixel(block.leftStart)
                           + (block.rightEnd - block.rightStart) * lineHeight;
                leftTopY = gapBot;
                leftBotY = gapBot;
                rightTopY = rightText.getLinePixel(block.rightStart);
                rightBotY = rightText.getLinePixel(block.rightEnd);
            } else if (block.type == DiffType.REMOVED) {
                // Gap on right: collapse right side to bottom gap line (wedge shape)
                leftTopY = leftText.getLinePixel(block.leftStart);
                leftBotY = leftText.getLinePixel(block.leftEnd);
                int gapBot = rightText.getLinePixel(block.rightStart)
                           + (block.leftEnd - block.leftStart) * lineHeight;
                rightTopY = gapBot;
                rightBotY = gapBot;
            } else {
                // CHANGED: both sides have lines, indent makes them align
                leftTopY = leftText.getLinePixel(block.leftStart);
                leftBotY = leftText.getLinePixel(block.leftEnd);
                rightTopY = rightText.getLinePixel(block.rightStart);
                rightBotY = rightText.getLinePixel(block.rightEnd);
            }

            // Skip blocks entirely outside the visible area
            int minY = Math.min(leftTopY, rightTopY);
            int maxY = Math.max(leftBotY, rightBotY);
            if (maxY < 0 || minY > gutterHeight) continue;

            gc.setAlpha(isSelected ? 200 : 140);
            gc.setAntialias(SWT.ON);
            float gw = gutterWidth;
            Display disp = gutter.getDisplay();

            // All block types use the same smooth S-curve ribbon; ADDED/REMOVED are just
            // the degenerate case where one side collapses to a point (wedge).
            org.eclipse.swt.graphics.Path fillPath = new org.eclipse.swt.graphics.Path(disp);
            fillPath.moveTo(0, leftTopY);
            curveTo(fillPath, 0, gw, leftTopY, rightTopY);
            fillPath.lineTo(gw, rightBotY);
            curveTo(fillPath, gw, 0, rightBotY, leftBotY);
            fillPath.close();
            // Use content-side color: ADDED→right (green), REMOVED/CHANGED→left
            gc.setBackground(block.type == DiffType.ADDED ? rightColor : leftColor);
            gc.fillPath(fillPath);
            fillPath.dispose();

            gc.setAlpha(isSelected ? 255 : 200);
            gc.setForeground(isSelected ? selectedBorder : gutterLine);
            gc.setLineWidth(isSelected ? 2 : 1);

            // Stroke only the two curved edges, not the vertical sides at the panel borders
            org.eclipse.swt.graphics.Path topLine = new org.eclipse.swt.graphics.Path(disp);
            topLine.moveTo(0, leftTopY);
            curveTo(topLine, 0, gw, leftTopY, rightTopY);
            gc.drawPath(topLine);
            topLine.dispose();

            org.eclipse.swt.graphics.Path botLine = new org.eclipse.swt.graphics.Path(disp);
            botLine.moveTo(0, leftBotY);
            curveTo(botLine, 0, gw, leftBotY, rightBotY);
            gc.drawPath(botLine);
            botLine.dispose();

            gc.setLineWidth(1);
            gc.setAlpha(255);

            // Draw inline merge arrow buttons when hovering this block
            if (idx == hoveredGutterBlock && !collapseEqual) {
                int centerY = (minY + maxY) / 2;
                int btnSize = 16;
                int btnY = centerY - btnSize / 2;
                Color white = disp.getSystemColor(SWT.COLOR_WHITE);

                // Left→Right button (▸): copy left to right
                gc.setAlpha(220);
                gc.setBackground(selectedBorder);
                gc.fillRoundRectangle(2, btnY, btnSize, btnSize, 6, 6);
                gc.setAlpha(255);
                gc.setBackground(white);
                int cx1 = 2 + btnSize / 2;
                gc.fillPolygon(new int[] { cx1 - 3, btnY + 4, cx1 + 4, centerY, cx1 - 3, btnY + btnSize - 4 });

                // Right→Left button (◂): copy right to left
                gc.setAlpha(220);
                gc.setBackground(selectedBorder);
                gc.fillRoundRectangle(gutterWidth - btnSize - 2, btnY, btnSize, btnSize, 6, 6);
                gc.setAlpha(255);
                gc.setBackground(white);
                int cx2 = gutterWidth - btnSize - 2 + btnSize / 2;
                gc.fillPolygon(new int[] { cx2 + 3, btnY + 4, cx2 - 4, centerY, cx2 + 3, btnY + btnSize - 4 });
            }
        }
    }

    private void drawGapLines(GC gc, StyledText widget, boolean isLeft) {
        if (gutterBlocks == null || gutterBlocks.isEmpty()) return;
        int width = widget.getClientArea().width;
        int height = widget.getClientArea().height;
        int lineHeight = computedLineHeight > 0 ? computedLineHeight : widget.getLineHeight();
        gc.setForeground(gutterLine);
        gc.setLineWidth(1);
        for (GutterBlock block : gutterBlocks) {
            // Draw lines only on the padding side
            boolean drawHere = (isLeft && block.type == DiffType.ADDED)
                            || (!isLeft && block.type == DiffType.REMOVED);
            if (!drawHere) continue;

            int lineIdx = isLeft ? block.leftStart : block.rightStart;
            int otherCount = isLeft
                ? (block.rightEnd - block.rightStart)
                : (block.leftEnd - block.leftStart);
            int topY = widget.getLinePixel(lineIdx);
            int botY = topY + otherCount * lineHeight;
            if (botY < 0 || topY > height) continue;

            // Only draw the bottom line of the gap (top line is omitted for cleaner look)
            gc.setLineWidth(2);
            gc.drawLine(0, botY, width, botY);
            gc.setLineWidth(1);
        }
    }

    private void redrawAll() {
        // Reset tracking so paint listeners will trigger a full redraw
        lastTopPixelLeft = -1;
        lastTopPixelRight = -1;
        lineNumberLeft.redraw();
        lineNumberRight.redraw();
        gutter.redraw();
    }

    // --- Gutter inline merge arrows ---

    private void setupGutterMouseListeners() {
        gutter.addMouseMoveListener(e -> {
            int old = hoveredGutterBlock;
            updateGutterHover(e.x, e.y);
            if (old != hoveredGutterBlock) {
                gutter.redraw();
            }
            boolean overButton = hoveredGutterBlock >= 0 && !collapseEqual
                && (e.x <= 18 || e.x >= gutter.getBounds().width - 18);
            gutter.setCursor(overButton
                ? gutter.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
        });
        gutter.addMouseTrackListener(new MouseTrackAdapter() {
            @Override public void mouseExit(MouseEvent e) {
                if (hoveredGutterBlock >= 0) {
                    hoveredGutterBlock = -1;
                    gutter.setCursor(null);
                    gutter.redraw();
                }
            }
        });
        gutter.addMouseListener(new MouseAdapter() {
            @Override public void mouseUp(MouseEvent e) {
                if (hoveredGutterBlock >= 0) {
                    int gw = gutter.getBounds().width;
                    if (!collapseEqual && e.x <= 18) {
                        takeLeftForBlock(hoveredGutterBlock);
                    } else if (!collapseEqual && e.x >= gw - 18) {
                        takeRightForBlock(hoveredGutterBlock);
                    } else {
                        currentChangeIndex = hoveredGutterBlock;
                        scrollToChange(currentChangeIndex);
                        updateChangeIndicator();
                    }
                }
            }
        });
    }

    private void updateGutterHover(int mouseX, int mouseY) {
        hoveredGutterBlock = -1;
        if (collapseEqual || gutterBlocks.isEmpty()) return;
        int gutterHeight = gutter.getBounds().height;
        int lineHeight = computedLineHeight > 0 ? computedLineHeight : leftText.getLineHeight();

        for (int idx = 0; idx < gutterBlocks.size(); idx++) {
            GutterBlock block = gutterBlocks.get(idx);
            int leftTopY, leftBotY, rightTopY, rightBotY;

            if (block.type == DiffType.ADDED) {
                int gapBot = leftText.getLinePixel(block.leftStart)
                           + (block.rightEnd - block.rightStart) * lineHeight;
                leftTopY = gapBot; leftBotY = gapBot;
                rightTopY = rightText.getLinePixel(block.rightStart);
                rightBotY = rightText.getLinePixel(block.rightEnd);
            } else if (block.type == DiffType.REMOVED) {
                leftTopY = leftText.getLinePixel(block.leftStart);
                leftBotY = leftText.getLinePixel(block.leftEnd);
                int gapBot = rightText.getLinePixel(block.rightStart)
                           + (block.leftEnd - block.leftStart) * lineHeight;
                rightTopY = gapBot; rightBotY = gapBot;
            } else {
                leftTopY = leftText.getLinePixel(block.leftStart);
                leftBotY = leftText.getLinePixel(block.leftEnd);
                rightTopY = rightText.getLinePixel(block.rightStart);
                rightBotY = rightText.getLinePixel(block.rightEnd);
            }

            int minY = Math.min(leftTopY, rightTopY);
            int maxY = Math.max(leftBotY, rightBotY);
            if (maxY < 0 || minY > gutterHeight) continue;

            if (mouseY >= minY && mouseY <= maxY) {
                hoveredGutterBlock = idx;
                break;
            }
        }
    }

    private void takeLeftForBlock(int blockIndex) {
        if (blockIndex < 0 || blockIndex >= gutterBlocks.size()) return;
        GutterBlock block = gutterBlocks.get(blockIndex);
        String replacement = getLinesText(leftText, block.leftStart, block.leftEnd);
        hoveredGutterBlock = -1;
        replaceLinesText(rightText, block.rightStart, block.rightEnd, replacement);
    }

    private void takeRightForBlock(int blockIndex) {
        if (blockIndex < 0 || blockIndex >= gutterBlocks.size()) return;
        GutterBlock block = gutterBlocks.get(blockIndex);
        String replacement = getLinesText(rightText, block.rightStart, block.rightEnd);
        hoveredGutterBlock = -1;
        replaceLinesText(leftText, block.leftStart, block.leftEnd, replacement);
    }

    private void loadContent() {
        String leftContent = "";
        String rightContent = "";

        if (leftFilePath != null && rightFilePath != null) {
            try {
                leftContent = Files.readString(Path.of(leftFilePath)).replace("\r\n", "\n").replace("\r", "\n");
            } catch (IOException e) {
                leftContent = "Error reading file: " + leftFilePath + "\n" + e.getMessage();
            }
            try {
                rightContent = Files.readString(Path.of(rightFilePath)).replace("\r\n", "\n").replace("\r", "\n");
            } catch (IOException e) {
                rightContent = "Error reading file: " + rightFilePath + "\n" + e.getMessage();
            }
        }

        updating = true;
        leftText.setText(leftContent);
        rightText.setText(rightContent);
        updating = false;
        leftOriginal = leftText.getText();
        rightOriginal = rightText.getText();
        leftDirty = false;
        rightDirty = false;
        recomputeDiff();
    }

    private void recomputeDiff() {
        if (updating) return;
        updating = true;
        try {
            String[] leftLines = leftText.getText().split("\n", -1);
            String[] rightLines = rightText.getText().split("\n", -1);

            updateLineNumberWidths();

            String[] leftForDiff = leftLines;
            String[] rightForDiff = rightLines;
            if (ignoreWhitespace) {
                leftForDiff = new String[leftLines.length];
                rightForDiff = new String[rightLines.length];
                for (int i = 0; i < leftLines.length; i++) leftForDiff[i] = normalizeWhitespace(leftLines[i]);
                for (int i = 0; i < rightLines.length; i++) rightForDiff[i] = normalizeWhitespace(rightLines[i]);
            }

            List<DiffEdit> edits = myersDiff(leftForDiff, rightForDiff);

            leftDiffTypes = new DiffType[leftLines.length];
            rightDiffTypes = new DiffType[rightLines.length];
            Arrays.fill(leftDiffTypes, DiffType.EQUAL);
            Arrays.fill(rightDiffTypes, DiffType.EQUAL);

            int prevLeftStart = -1;
            if (currentChangeIndex >= 0 && currentChangeIndex < gutterBlocks.size()) {
                prevLeftStart = gutterBlocks.get(currentChangeIndex).leftStart;
            }
            gutterBlocks.clear();
            leftInnerRanges.clear();
            rightInnerRanges.clear();
            for (int i = 0; i < leftLines.length; i++) leftInnerRanges.add(null);
            for (int i = 0; i < rightLines.length; i++) rightInnerRanges.add(null);

            for (DiffEdit edit : edits) {
                int delCount = edit.leftEnd - edit.leftStart;
                int insCount = edit.rightEnd - edit.rightStart;

                if (delCount > 0 && insCount > 0) {
                    int paired = Math.min(delCount, insCount);
                    for (int p = 0; p < paired; p++) {
                        int li = edit.leftStart + p;
                        int ri = edit.rightStart + p;
                        leftDiffTypes[li] = DiffType.CHANGED;
                        rightDiffTypes[ri] = DiffType.CHANGED;
                        if (!leftLines[li].isEmpty() && !rightLines[ri].isEmpty()) {
                            int[][] ranges = computeInnerLineChanges(leftLines[li], rightLines[ri]);
                            leftInnerRanges.set(li, ranges[0]);
                            rightInnerRanges.set(ri, ranges[1]);
                        }
                    }
                    for (int p = paired; p < delCount; p++)
                        leftDiffTypes[edit.leftStart + p] = DiffType.REMOVED;
                    for (int p = paired; p < insCount; p++)
                        rightDiffTypes[edit.rightStart + p] = DiffType.ADDED;

                    gutterBlocks.add(new GutterBlock(edit.leftStart, edit.leftEnd,
                        edit.rightStart, edit.rightEnd, DiffType.CHANGED));
                } else if (delCount > 0) {
                    for (int d = 0; d < delCount; d++)
                        leftDiffTypes[edit.leftStart + d] = DiffType.REMOVED;
                    gutterBlocks.add(new GutterBlock(edit.leftStart, edit.leftEnd,
                        edit.rightStart, edit.rightEnd, DiffType.REMOVED));
                } else {
                    for (int a = 0; a < insCount; a++)
                        rightDiffTypes[edit.rightStart + a] = DiffType.ADDED;
                    gutterBlocks.add(new GutterBlock(edit.leftStart, edit.leftEnd,
                        edit.rightStart, edit.rightEnd, DiffType.ADDED));
                }
            }

            // Clear old backgrounds, apply new ones
            leftText.setLineBackground(0, leftText.getLineCount(), null);
            rightText.setLineBackground(0, rightText.getLineCount(), null);
            for (int i = 0; i < leftDiffTypes.length; i++) {
                Color bg = diffTypeToColor(leftDiffTypes[i], true);
                if (bg != null) leftText.setLineBackground(i, 1, bg);
            }
            for (int i = 0; i < rightDiffTypes.length; i++) {
                Color bg = diffTypeToColor(rightDiffTypes[i], false);
                if (bg != null) rightText.setLineBackground(i, 1, bg);
            }
            // Set vertical indents to align equal sections across both sides.
            // No LineStyleListener attached, so setLineVerticalIndent works directly.
            computedLineHeight = leftText.getLineHeight();
            leftText.setRedraw(false);
            rightText.setRedraw(false);
            for (int li = 0; li < leftText.getLineCount(); li++)
                leftText.setLineVerticalIndent(li, 0);
            for (int ri = 0; ri < rightText.getLineCount(); ri++)
                rightText.setLineVerticalIndent(ri, 0);
            for (DiffEdit edit : edits) {
                int leftCount = edit.leftEnd - edit.leftStart;
                int rightCount = edit.rightEnd - edit.rightStart;
                int diff = rightCount - leftCount;
                if (diff > 0 && edit.leftEnd < leftText.getLineCount()) {
                    leftText.setLineVerticalIndent(edit.leftEnd, diff * computedLineHeight);
                } else if (diff < 0 && edit.rightEnd < rightText.getLineCount()) {
                    rightText.setLineVerticalIndent(edit.rightEnd, (-diff) * computedLineHeight);
                }
            }
            // Apply syntax highlighting via setStyleRanges (not LineStyleListener)
            applySyntaxStyles(leftText, true);
            applySyntaxStyles(rightText, false);
            leftText.setRedraw(true);
            rightText.setRedraw(true);

            // Restore currentChangeIndex to nearest block after recompute
            if (prevLeftStart >= 0 && !gutterBlocks.isEmpty()) {
                currentChangeIndex = 0;
                int minDist = Integer.MAX_VALUE;
                for (int i = 0; i < gutterBlocks.size(); i++) {
                    int dist = Math.abs(gutterBlocks.get(i).leftStart - prevLeftStart);
                    if (dist < minDist) { minDist = dist; currentChangeIndex = i; }
                }
            } else {
                currentChangeIndex = -1;
            }

            // Force visual refresh on all widgets
            leftText.redraw();
            rightText.redraw();
            redrawAll();
            updateChangeIndicator();
            if (unifiedMode) refreshUnifiedView();

            // Re-apply find highlights if find is active
            if (findBar != null && !findBar.isDisposed() && findBar.isVisible()) {
                searchForMatches();
                applyFindHighlightsOnly();
                updateFindCount();
            }
        } finally {
            updating = false;
        }
    }

    // --- Ignore Whitespace ---

    private void toggleIgnoreWhitespace() {
        ignoreWhitespace = !ignoreWhitespace;
        updateIgnoreWsVisual();
        if (collapseEqual) {
            unfold();
            applyFolding();
        } else {
            recomputeDiff();
        }
    }

    private void updateIgnoreWsVisual() {
        if (ignoreWsButton == null || ignoreWsButton.isDisposed()) return;
        Display d = ignoreWsButton.getDisplay();
        if (ignoreWhitespace) {
            ignoreWsButton.setBackground(selectedBorder);
            for (org.eclipse.swt.widgets.Control child : ignoreWsButton.getChildren()) {
                child.setBackground(selectedBorder);
                if (child instanceof Label)
                    ((Label) child).setForeground(d.getSystemColor(SWT.COLOR_WHITE));
            }
        } else {
            ignoreWsButton.setBackground(null);
            for (org.eclipse.swt.widgets.Control child : ignoreWsButton.getChildren()) {
                child.setBackground(null);
                if (child instanceof Label)
                    ((Label) child).setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
            }
        }
    }

    private String normalizeWhitespace(String line) {
        return line.trim().replaceAll("\\s+", " ");
    }

    private void createWsToggle(Composite parent) {
        ignoreWsButton = new Composite(parent, SWT.NONE);
        GridLayout wsLayout = new GridLayout(1, false);
        wsLayout.marginHeight = 2;
        wsLayout.marginWidth = 8;
        ignoreWsButton.setLayout(wsLayout);
        ignoreWsButton.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        ignoreWsButton.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        Label wsIcon = new Label(ignoreWsButton, SWT.CENTER);
        wsIcon.setText("\u2248");
        if (iconFont != null) wsIcon.setFont(iconFont);
        wsIcon.setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
        wsIcon.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Label wsLbl = new Label(ignoreWsButton, SWT.CENTER);
        wsLbl.setText("Ignore WS");
        if (labelFont != null) wsLbl.setFont(labelFont);
        wsLbl.setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
        wsLbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        wsLbl.setToolTipText("Toggle Ignore Whitespace");

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { toggleIgnoreWhitespace(); }
        };
        ignoreWsButton.addMouseListener(click);
        wsIcon.addMouseListener(click);
        wsLbl.addMouseListener(click);

        MouseTrackAdapter hover = new MouseTrackAdapter() {
            @Override
            public void mouseEnter(MouseEvent e) {
                if (!ignoreWhitespace) {
                    ignoreWsButton.setBackground(AppTheme.hoverBg);
                    wsIcon.setBackground(AppTheme.hoverBg);
                    wsLbl.setBackground(AppTheme.hoverBg);
                }
            }
            @Override
            public void mouseExit(MouseEvent e) {
                if (!ignoreWhitespace) {
                    ignoreWsButton.setBackground(null);
                    wsIcon.setBackground(null);
                    wsLbl.setBackground(null);
                }
            }
        };
        ignoreWsButton.addMouseTrackListener(hover);
        wsIcon.addMouseTrackListener(hover);
        wsLbl.addMouseTrackListener(hover);
    }

    // --- Find ---

    private void createFindBar(Composite parent) {
        findBar = new Composite(parent, SWT.NONE);
        GridData findBarGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        findBarGd.exclude = true;
        findBar.setLayoutData(findBarGd);
        findBar.setVisible(false);

        GridLayout fbLayout = new GridLayout(6, false);
        fbLayout.marginHeight = 3;
        fbLayout.marginWidth = 8;
        fbLayout.horizontalSpacing = 4;
        findBar.setLayout(fbLayout);

        Label findLabel = new Label(findBar, SWT.NONE);
        findLabel.setText("Find:");
        if (labelFont != null) findLabel.setFont(labelFont);

        findField = new org.eclipse.swt.widgets.Text(findBar, SWT.BORDER | SWT.SINGLE);
        if (labelFont != null) findField.setFont(labelFont);
        GridData fieldGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        fieldGd.minimumWidth = 200;
        findField.setLayoutData(fieldGd);

        Label prevBtn = new Label(findBar, SWT.CENTER);
        prevBtn.setText(" \u2191 ");
        if (labelFont != null) prevBtn.setFont(labelFont);
        prevBtn.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        prevBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { goToPrevFindMatch(); }
        });

        Label nextBtn = new Label(findBar, SWT.CENTER);
        nextBtn.setText(" \u2193 ");
        if (labelFont != null) nextBtn.setFont(labelFont);
        nextBtn.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        nextBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { goToNextFindMatch(); }
        });

        findCountLabel = new Label(findBar, SWT.NONE);
        findCountLabel.setText("");
        if (labelFont != null) findCountLabel.setFont(labelFont);
        findCountLabel.setForeground(AppTheme.lineNumFg);
        GridData countGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        countGd.widthHint = 100;
        findCountLabel.setLayoutData(countGd);

        Label closeBtn = new Label(findBar, SWT.CENTER);
        closeBtn.setText(" \u00D7 ");
        if (labelFont != null) closeBtn.setFont(labelFont);
        closeBtn.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        closeBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { hideFind(); }
        });

        findField.addListener(SWT.Modify, e -> performFind());
        findField.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                if ((e.stateMask & SWT.SHIFT) != 0) goToPrevFindMatch();
                else goToNextFindMatch();
                e.doit = false;
            } else if (e.keyCode == SWT.ESC) {
                hideFind();
                e.doit = false;
            }
        });
    }

    private void showFind() {
        if (findBar == null || findBar.isDisposed()) return;
        GridData gd = (GridData) findBar.getLayoutData();
        if (!gd.exclude) {
            findField.setFocus();
            findField.selectAll();
            return;
        }
        gd.exclude = false;
        findBar.setVisible(true);
        findBar.getParent().layout(true, true);
        findField.setFocus();
        if (findField.getText().length() > 0) {
            findField.selectAll();
            performFind();
        }
    }

    private void hideFind() {
        if (findBar == null || findBar.isDisposed()) return;
        GridData gd = (GridData) findBar.getLayoutData();
        gd.exclude = true;
        findBar.setVisible(false);
        findBar.getParent().layout(true, true);

        leftFindMatches.clear();
        rightFindMatches.clear();
        currentFindMatchIndex = -1;
        refreshFindHighlights();

        if (lastFocusedText != null && !lastFocusedText.isDisposed()) {
            lastFocusedText.setFocus();
        }
    }

    private void performFind() {
        if (findField == null || findField.isDisposed()) return;
        searchForMatches();
        refreshFindHighlights();
        scrollToCurrentMatch();
        updateFindCount();
    }

    private void searchForMatches() {
        leftFindMatches.clear();
        rightFindMatches.clear();
        currentFindMatchIndex = -1;

        if (findField == null || findField.isDisposed()) return;
        String query = findField.getText();
        if (query.isEmpty()) return;

        String queryLower = query.toLowerCase();

        String leftLower = leftText.getText().toLowerCase();
        int idx = 0;
        while ((idx = leftLower.indexOf(queryLower, idx)) != -1) {
            leftFindMatches.add(new int[] { idx, query.length() });
            idx += Math.max(1, query.length());
        }

        String rightLower = rightText.getText().toLowerCase();
        idx = 0;
        while ((idx = rightLower.indexOf(queryLower, idx)) != -1) {
            rightFindMatches.add(new int[] { idx, query.length() });
            idx += Math.max(1, query.length());
        }

        if (lastFocusedText == rightText && !rightFindMatches.isEmpty()) {
            currentFindMatchIndex = 0;
        } else if (!leftFindMatches.isEmpty()) {
            currentFindMatchIndex = 0;
            lastFocusedText = leftText;
        } else if (!rightFindMatches.isEmpty()) {
            currentFindMatchIndex = 0;
            lastFocusedText = rightText;
        }
    }

    private void refreshFindHighlights() {
        if (leftText == null || leftText.isDisposed()) return;
        applySyntaxStyles(leftText, true);
        applySyntaxStyles(rightText, false);
        applyFindHighlightsOnly();
    }

    private void applyFindHighlightsOnly() {
        if (leftFindMatches.isEmpty() && rightFindMatches.isEmpty()) return;
        boolean leftIsCurrent = (lastFocusedText != rightText);
        highlightFindMatches(leftText, leftFindMatches,
            leftIsCurrent ? currentFindMatchIndex : -1);
        highlightFindMatches(rightText, rightFindMatches,
            !leftIsCurrent ? currentFindMatchIndex : -1);
    }

    private void highlightFindMatches(StyledText widget, List<int[]> matches, int currentIdx) {
        if (matches.isEmpty()) return;
        int charCount = widget.getCharCount();

        for (int i = 0; i < matches.size(); i++) {
            int[] match = matches.get(i);
            int offset = match[0];
            int length = match[1];
            if (offset < 0 || offset + length > charCount) continue;

            Color bg = (i == currentIdx) ? findCurrentBg : findHighlightBg;

            StyleRange[] existing = widget.getStyleRanges(offset, length);
            if (existing.length == 0) {
                widget.setStyleRange(new StyleRange(offset, length, null, bg));
            } else {
                int pos = offset;
                int end = offset + length;
                for (StyleRange sr : existing) {
                    if (sr.start > pos) {
                        widget.setStyleRange(new StyleRange(pos, sr.start - pos, null, bg));
                    }
                    int overlapStart = Math.max(pos, sr.start);
                    int overlapEnd = Math.min(end, sr.start + sr.length);
                    if (overlapEnd > overlapStart) {
                        StyleRange highlight = new StyleRange();
                        highlight.start = overlapStart;
                        highlight.length = overlapEnd - overlapStart;
                        highlight.foreground = sr.foreground;
                        highlight.background = bg;
                        highlight.fontStyle = sr.fontStyle;
                        widget.setStyleRange(highlight);
                    }
                    pos = Math.max(pos, sr.start + sr.length);
                }
                if (pos < end) {
                    widget.setStyleRange(new StyleRange(pos, end - pos, null, bg));
                }
            }
        }
    }

    private void goToNextFindMatch() {
        List<int[]> matches = getCurrentFindMatches();
        if (matches.isEmpty()) return;
        if (currentFindMatchIndex < 0 || currentFindMatchIndex >= matches.size()) {
            currentFindMatchIndex = 0;
        } else {
            currentFindMatchIndex = (currentFindMatchIndex + 1) % matches.size();
        }
        refreshFindHighlights();
        scrollToCurrentMatch();
        updateFindCount();
    }

    private void goToPrevFindMatch() {
        List<int[]> matches = getCurrentFindMatches();
        if (matches.isEmpty()) return;
        if (currentFindMatchIndex <= 0) {
            currentFindMatchIndex = matches.size() - 1;
        } else {
            currentFindMatchIndex--;
        }
        refreshFindHighlights();
        scrollToCurrentMatch();
        updateFindCount();
    }

    private void scrollToCurrentMatch() {
        List<int[]> matches = getCurrentFindMatches();
        if (currentFindMatchIndex < 0 || currentFindMatchIndex >= matches.size()) return;
        StyledText widget = getCurrentFindWidget();
        int[] match = matches.get(currentFindMatchIndex);
        widget.setCaretOffset(match[0]);
        widget.showSelection();
    }

    private void updateFindCount() {
        if (findCountLabel == null || findCountLabel.isDisposed()) return;
        int total = leftFindMatches.size() + rightFindMatches.size();
        if (total == 0) {
            findCountLabel.setText(findField != null && !findField.getText().isEmpty()
                ? "No matches" : "");
        } else {
            List<int[]> current = getCurrentFindMatches();
            if (currentFindMatchIndex >= 0 && currentFindMatchIndex < current.size()) {
                int absIdx = (lastFocusedText == rightText)
                    ? leftFindMatches.size() + currentFindMatchIndex + 1
                    : currentFindMatchIndex + 1;
                findCountLabel.setText(absIdx + " / " + total);
            } else {
                findCountLabel.setText(total + " found");
            }
        }
        findCountLabel.getParent().layout(true);
    }

    private List<int[]> getCurrentFindMatches() {
        return (lastFocusedText == rightText) ? rightFindMatches : leftFindMatches;
    }

    private StyledText getCurrentFindWidget() {
        return (lastFocusedText == rightText) ? rightText : leftText;
    }

    // --- Swap Sides ---

    private void swapSides() {
        boolean wasCollapsed = collapseEqual;
        if (wasCollapsed) { collapseEqual = false; unfold(); }

        String tempPath = leftFilePath;
        leftFilePath = rightFilePath;
        rightFilePath = tempPath;

        updating = true;
        String tempText = leftText.getText();
        leftText.setText(rightText.getText());
        rightText.setText(tempText);
        updating = false;

        String tempOrig = leftOriginal;
        leftOriginal = rightOriginal;
        rightOriginal = tempOrig;

        boolean tempDirty = leftDirty;
        leftDirty = rightDirty;
        rightDirty = tempDirty;

        leftLabel.setText(leftFilePath != null ? "  " + leftFilePath : "  Left file");
        rightLabel.setText(rightFilePath != null ? "  " + rightFilePath : "  Right file");

        recomputeDiff();
        updateDirtyState();

        if (wasCollapsed) { collapseEqual = true; applyFolding(); }
    }

    // --- Go to Line ---

    private void goToLine() {
        StyledText target = (lastFocusedText == rightText) ? rightText : leftText;
        Shell parent = target.getShell();
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText("Go to Line");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 10; gl.marginWidth = 10;
        dialog.setLayout(gl);

        Label label = new Label(dialog, SWT.NONE);
        label.setText("Line:");
        if (labelFont != null) label.setFont(labelFont);

        org.eclipse.swt.widgets.Text input = new org.eclipse.swt.widgets.Text(dialog, SWT.BORDER | SWT.SINGLE);
        if (labelFont != null) input.setFont(labelFont);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 120;
        input.setLayoutData(gd);

        input.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                try {
                    int line = Integer.parseInt(input.getText().trim()) - 1;
                    int actualLine = line;
                    if (collapseEqual && leftDisplayToOrig != null) {
                        int[] map = (target == rightText) ? rightDisplayToOrig : leftDisplayToOrig;
                        actualLine = -1;
                        for (int d = 0; d < map.length; d++) {
                            if (map[d] == line) { actualLine = d; break; }
                            if (map[d] > line && map[d] >= 0) { actualLine = d; break; }
                        }
                        if (actualLine < 0) actualLine = 0;
                    }
                    if (actualLine >= 0 && actualLine < target.getLineCount()) {
                        target.setTopIndex(Math.max(0, actualLine - 3));
                        target.setCaretOffset(target.getOffsetAtLine(actualLine));
                    }
                } catch (NumberFormatException ex) { /* ignore */ }
                dialog.close();
            } else if (e.keyCode == SWT.ESC) {
                dialog.close();
            }
        });

        dialog.pack();
        org.eclipse.swt.graphics.Rectangle parentBounds = parent.getBounds();
        org.eclipse.swt.graphics.Point size = dialog.getSize();
        dialog.setLocation(
            parentBounds.x + (parentBounds.width - size.x) / 2,
            parentBounds.y + (parentBounds.height - size.y) / 3);
        dialog.open();
    }

    // --- Unified View ---

    private void createUnifiedToggle(Composite parent) {
        unifiedToggle = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 2; layout.marginWidth = 8;
        unifiedToggle.setLayout(layout);
        unifiedToggle.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        unifiedToggle.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        Label icon = new Label(unifiedToggle, SWT.CENTER);
        icon.setText("\u2261");
        if (iconFont != null) icon.setFont(iconFont);
        icon.setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
        icon.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Label lbl = new Label(unifiedToggle, SWT.CENTER);
        lbl.setText("Unified");
        if (labelFont != null) lbl.setFont(labelFont);
        lbl.setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
        lbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { toggleUnifiedMode(); }
        };
        unifiedToggle.addMouseListener(click);
        icon.addMouseListener(click);
        lbl.addMouseListener(click);

        MouseTrackAdapter hover = new MouseTrackAdapter() {
            @Override
            public void mouseEnter(MouseEvent e) {
                if (!unifiedMode) {
                    unifiedToggle.setBackground(AppTheme.hoverBg);
                    icon.setBackground(AppTheme.hoverBg);
                    lbl.setBackground(AppTheme.hoverBg);
                }
            }
            @Override
            public void mouseExit(MouseEvent e) {
                if (!unifiedMode) {
                    unifiedToggle.setBackground(null);
                    icon.setBackground(null);
                    lbl.setBackground(null);
                }
            }
        };
        unifiedToggle.addMouseTrackListener(hover);
        icon.addMouseTrackListener(hover);
        lbl.addMouseTrackListener(hover);
    }

    private void updateUnifiedToggleVisual() {
        if (unifiedToggle == null || unifiedToggle.isDisposed()) return;
        Display d = unifiedToggle.getDisplay();
        if (unifiedMode) {
            unifiedToggle.setBackground(selectedBorder);
            for (org.eclipse.swt.widgets.Control c : unifiedToggle.getChildren()) {
                c.setBackground(selectedBorder);
                if (c instanceof Label)
                    ((Label) c).setForeground(d.getSystemColor(SWT.COLOR_WHITE));
            }
        } else {
            unifiedToggle.setBackground(null);
            for (org.eclipse.swt.widgets.Control c : unifiedToggle.getChildren()) {
                c.setBackground(null);
                if (c instanceof Label)
                    ((Label) c).setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
            }
        }
    }

    private void toggleUnifiedMode() {
        unifiedMode = !unifiedMode;

        GridData sashGd = (GridData) mainSash.getLayoutData();
        sashGd.exclude = unifiedMode;
        mainSash.setVisible(!unifiedMode);

        GridData uniGd = (GridData) unifiedPanel.getLayoutData();
        uniGd.exclude = !unifiedMode;
        unifiedPanel.setVisible(unifiedMode);

        if (unifiedMode) refreshUnifiedView();

        mainSash.getParent().layout(true, true);
        updateUnifiedToggleVisual();
    }

    private void refreshUnifiedView() {
        if (unifiedText == null || unifiedText.isDisposed()) return;
        String[] leftLines  = leftText.getText().split("\n", -1);
        String[] rightLines = rightText.getText().split("\n", -1);

        List<String>   lines    = new ArrayList<>();
        List<DiffType> types    = new ArrayList<>();
        List<Integer>  origLeft = new ArrayList<>();
        List<Integer>  origRight= new ArrayList<>();
        unifiedHunkLineIndex = new int[gutterBlocks.size()];

        int leftIdx = 0, rightIdx = 0;
        for (int b = 0; b < gutterBlocks.size(); b++) {
            GutterBlock block = gutterBlocks.get(b);
            emitUnifiedEqualRegion(leftLines, rightLines,
                leftIdx, block.leftStart, rightIdx,
                lines, types, origLeft, origRight);

            unifiedHunkLineIndex[b] = lines.size();
            for (int i = block.leftStart; i < block.leftEnd; i++) {
                lines.add(i < leftLines.length ? leftLines[i] : "");
                types.add(DiffType.REMOVED);
                origLeft.add(i); origRight.add(-1);
            }
            for (int j = block.rightStart; j < block.rightEnd; j++) {
                lines.add(j < rightLines.length ? rightLines[j] : "");
                types.add(DiffType.ADDED);
                origLeft.add(-1); origRight.add(j);
            }
            leftIdx  = block.leftEnd;
            rightIdx = block.rightEnd;
        }
        emitUnifiedEqualRegion(leftLines, rightLines,
            leftIdx, leftLines.length, rightIdx,
            lines, types, origLeft, origRight);

        unifiedLineTypes  = types.toArray(new DiffType[0]);
        unifiedOrigLeft   = origLeft.stream().mapToInt(Integer::intValue).toArray();
        unifiedOrigRight  = origRight.stream().mapToInt(Integer::intValue).toArray();

        unifiedText.setRedraw(false);
        unifiedText.setText(String.join("\n", lines));
        unifiedText.setLineBackground(0, unifiedText.getLineCount(), null);
        for (int i = 0; i < unifiedLineTypes.length && i < unifiedText.getLineCount(); i++) {
            Color bg = null;
            if      (unifiedLineTypes[i] == DiffType.REMOVED) bg = removedBg;
            else if (unifiedLineTypes[i] == DiffType.ADDED)   bg = addedBg;
            else if (unifiedLineTypes[i] == DiffType.HIDDEN)  bg = foldMarkerBg;
            if (bg != null) unifiedText.setLineBackground(i, 1, bg);
        }
        // Style HIDDEN marker lines — blue bold underline (looks clickable)
        for (int i = 0; i < unifiedLineTypes.length && i < unifiedText.getLineCount(); i++) {
            if (unifiedLineTypes[i] == DiffType.HIDDEN) {
                int off = unifiedText.getOffsetAtLine(i);
                int len = unifiedText.getLine(i).length();
                if (len > 0) {
                    StyleRange sr = new StyleRange(off, len, foldLinkFg, foldMarkerBg);
                    sr.fontStyle = SWT.BOLD;
                    sr.underline = true;
                    unifiedText.setStyleRange(sr);
                }
            }
        }
        unifiedText.setRedraw(true);
        lineNumberUnified.redraw();
    }

    private void emitUnifiedEqualRegion(String[] leftLines, String[] rightLines,
            int leftStart, int leftEnd, int rightStart,
            List<String> lines, List<DiffType> types,
            List<Integer> origLeft, List<Integer> origRight) {
        int count = leftEnd - leftStart;
        if (count <= 0) return;
        if (collapseEqual && count > COLLAPSE_THRESHOLD) {
            int ctx      = Math.min(foldContext, count / 2);
            int hidStart = leftStart + ctx;
            int hidden   = count - 2 * ctx;
            boolean expanded = expandedUnifiedFoldRegions.contains(hidStart);
            for (int i = 0; i < ctx; i++) {
                lines.add(leftStart + i < leftLines.length ? leftLines[leftStart + i] : "");
                types.add(DiffType.EQUAL);
                origLeft.add(leftStart + i); origRight.add(rightStart + i);
            }
            if (hidden > 0 && !expanded) {
                lines.add("  \u22EF  " + hidden + " unchanged lines  \u22EF  (click to show)");
                types.add(DiffType.HIDDEN);
                // Encode hidStart so click handler can identify the region
                origLeft.add(-(hidStart + 2)); origRight.add(-(hidStart + 2));
            }
            for (int i = count - ctx; i < count; i++) {
                lines.add(leftStart + i < leftLines.length ? leftLines[leftStart + i] : "");
                types.add(DiffType.EQUAL);
                origLeft.add(leftStart + i); origRight.add(rightStart + i);
            }
        } else {
            for (int i = 0; i < count; i++) {
                lines.add(leftStart + i < leftLines.length ? leftLines[leftStart + i] : "");
                types.add(DiffType.EQUAL);
                origLeft.add(leftStart + i); origRight.add(rightStart + i);
            }
        }
    }

    private void paintLineNumbersUnified(PaintEvent e) {
        if (unifiedText == null || unifiedText.isDisposed() || unifiedLineTypes == null) return;
        GC gc = e.gc;
        gc.setFont(monoFont);
        gc.setBackground(AppTheme.lineNumBg);

        // Layout: [  old num  |  new num  ]
        final int SEP = unifiedLnWidth / 2;
        final int PAD = UNI_LN_PAD;

        int lineHeight = unifiedText.getLineHeight();
        int topLine    = unifiedText.getLineIndex(0);
        int canvasH    = ((Canvas) e.widget).getBounds().height;
        int visible    = (canvasH / lineHeight) + 2;

        // Draw vertical separator line
        gc.setForeground(gutterLine);
        gc.drawLine(SEP, 0, SEP, canvasH);

        for (int i = 0; i < visible; i++) {
            int lineIndex = topLine + i;
            if (lineIndex >= unifiedText.getLineCount()) break;
            if (lineIndex >= unifiedLineTypes.length) continue;
            int y = unifiedText.getLinePixel(lineIndex + 1) - lineHeight;

            DiffType t = unifiedLineTypes[lineIndex];

            if (t == DiffType.HIDDEN) {
                // Draw "⋯" centred across both columns
                gc.setForeground(gutterLine);
                String s = "\u22EF";
                int tw = gc.textExtent(s).x;
                gc.drawString(s, (unifiedLnWidth - tw) / 2, y, true);
                continue;
            }

            // Old line number (left column) — EQUAL and REMOVED
            int ol = (unifiedOrigLeft  != null && lineIndex < unifiedOrigLeft.length)
                     ? unifiedOrigLeft[lineIndex] : -1;
            int nr = (unifiedOrigRight != null && lineIndex < unifiedOrigRight.length)
                     ? unifiedOrigRight[lineIndex] : -1;

            if (ol >= 0) {
                gc.setForeground(t == DiffType.REMOVED ? removedBg : AppTheme.lineNumFg);
                String s = String.valueOf(ol + 1);
                gc.drawString(s, SEP - gc.textExtent(s).x - PAD, y, true);
            }
            // New line number (right column) — EQUAL and ADDED
            if (nr >= 0) {
                gc.setForeground(t == DiffType.ADDED ? addedBg : AppTheme.lineNumFg);
                String s = String.valueOf(nr + 1);
                gc.drawString(s, unifiedLnWidth - gc.textExtent(s).x - PAD, y, true);
            }
        }
        gc.setForeground(AppTheme.lineNumFg);
    }

    // --- Collapse Equal Sections ---

    private static final int COLLAPSE_THRESHOLD = 8;
    private int foldContext = 3;
    private Composite contextButton;
    // Per-section expand: encoded as -(hiddenStart + 2) in displayToOrig map
    private final Set<Integer> expandedFoldRegions = new HashSet<>();
    private final Set<Integer> expandedUnifiedFoldRegions = new HashSet<>();

    private void createCollapseToggle(Composite parent) {
        collapseButton = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 2; layout.marginWidth = 8;
        collapseButton.setLayout(layout);
        collapseButton.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        collapseButton.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        Label icon = new Label(collapseButton, SWT.CENTER);
        icon.setText("\u22EF");
        if (iconFont != null) icon.setFont(iconFont);
        icon.setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
        icon.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Label lbl = new Label(collapseButton, SWT.CENTER);
        lbl.setText("Collapse");
        if (labelFont != null) lbl.setFont(labelFont);
        lbl.setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
        lbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { toggleCollapse(); }
        };
        collapseButton.addMouseListener(click);
        icon.addMouseListener(click);
        lbl.addMouseListener(click);

        MouseTrackAdapter hover = new MouseTrackAdapter() {
            @Override
            public void mouseEnter(MouseEvent e) {
                if (!collapseEqual) {
                    collapseButton.setBackground(AppTheme.hoverBg);
                    icon.setBackground(AppTheme.hoverBg);
                    lbl.setBackground(AppTheme.hoverBg);
                }
            }
            @Override
            public void mouseExit(MouseEvent e) {
                if (!collapseEqual) {
                    collapseButton.setBackground(null);
                    icon.setBackground(null);
                    lbl.setBackground(null);
                }
            }
        };
        collapseButton.addMouseTrackListener(hover);
        icon.addMouseTrackListener(hover);
        lbl.addMouseTrackListener(hover);
    }

    private void updateCollapseVisual() {
        if (collapseButton == null || collapseButton.isDisposed()) return;
        Display d = collapseButton.getDisplay();
        if (collapseEqual) {
            collapseButton.setBackground(selectedBorder);
            for (org.eclipse.swt.widgets.Control c : collapseButton.getChildren()) {
                c.setBackground(selectedBorder);
                if (c instanceof Label)
                    ((Label) c).setForeground(d.getSystemColor(SWT.COLOR_WHITE));
            }
        } else {
            collapseButton.setBackground(null);
            for (org.eclipse.swt.widgets.Control c : collapseButton.getChildren()) {
                c.setBackground(null);
                if (c instanceof Label)
                    ((Label) c).setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
            }
        }
    }

    private void toggleCollapse() {
        collapseEqual = !collapseEqual;
        // Always start fresh when toggling (clear any per-section expand state)
        foldContext = 3;
        expandedFoldRegions.clear();
        expandedUnifiedFoldRegions.clear();
        updateCollapseVisual();
        if (unifiedMode) {
            refreshUnifiedView();
        } else if (collapseEqual) {
            applyFolding();
        } else {
            unfold();
        }
    }

    private void applyFolding() {
        if (leftText == null || leftText.isDisposed()) return;

        // Use stored full text when re-folding after a per-section expand
        String leftSource  = (fullLeftText  != null) ? fullLeftText  : leftText.getText();
        String rightSource = (fullRightText != null) ? fullRightText : rightText.getText();
        String[] leftLines  = leftSource.split("\n", -1);
        String[] rightLines = rightSource.split("\n", -1);
        fullLeftText  = leftSource;
        fullRightText = rightSource;

        List<String> leftDisplay = new ArrayList<>();
        List<String> rightDisplay = new ArrayList<>();
        List<Integer> leftMap = new ArrayList<>();
        List<Integer> rightMap = new ArrayList<>();

        int prevLeftEnd = 0, prevRightEnd = 0;

        for (GutterBlock block : gutterBlocks) {
            emitEqualRegion(leftLines, rightLines,
                prevLeftEnd, block.leftStart, prevRightEnd, block.rightStart,
                leftDisplay, rightDisplay, leftMap, rightMap);

            for (int i = block.leftStart; i < block.leftEnd; i++) {
                leftDisplay.add(leftLines[i]);
                leftMap.add(i);
            }
            for (int i = block.rightStart; i < block.rightEnd; i++) {
                rightDisplay.add(rightLines[i]);
                rightMap.add(i);
            }

            prevLeftEnd = block.leftEnd;
            prevRightEnd = block.rightEnd;
        }

        emitEqualRegion(leftLines, rightLines,
            prevLeftEnd, leftLines.length, prevRightEnd, rightLines.length,
            leftDisplay, rightDisplay, leftMap, rightMap);

        // Check if anything was actually folded (all regions were expanded or too short)
        if (leftDisplay.size() == leftLines.length) {
            expandedFoldRegions.clear();
            expandedUnifiedFoldRegions.clear();
            collapseEqual = false;
            updateCollapseVisual();
            unfold(); // restore full text properly (was not updated yet)
            return;
        }

        leftDisplayToOrig = leftMap.stream().mapToInt(Integer::intValue).toArray();
        rightDisplayToOrig = rightMap.stream().mapToInt(Integer::intValue).toArray();

        // Build reverse mappings
        int[] leftOrigToDisp = new int[leftLines.length];
        int[] rightOrigToDisp = new int[rightLines.length];
        Arrays.fill(leftOrigToDisp, -1);
        Arrays.fill(rightOrigToDisp, -1);
        for (int d = 0; d < leftDisplayToOrig.length; d++)
            if (leftDisplayToOrig[d] >= 0) leftOrigToDisp[leftDisplayToOrig[d]] = d;
        for (int d = 0; d < rightDisplayToOrig.length; d++)
            if (rightDisplayToOrig[d] >= 0) rightOrigToDisp[rightDisplayToOrig[d]] = d;

        // Save scroll position so expand/collapse doesn't jump to top
        int savedTopLeft  = leftText.getTopPixel();
        int savedTopRight = rightText.getTopPixel();

        updating = true;
        leftText.setRedraw(false);
        rightText.setRedraw(false);

        leftText.setText(String.join("\n", leftDisplay));
        rightText.setText(String.join("\n", rightDisplay));
        leftText.setEditable(false);
        rightText.setEditable(false);

        // Backgrounds
        leftText.setLineBackground(0, leftText.getLineCount(), null);
        rightText.setLineBackground(0, rightText.getLineCount(), null);
        for (int d = 0; d < leftDisplayToOrig.length && d < leftText.getLineCount(); d++) {
            int orig = leftDisplayToOrig[d];
            if (orig < -1) {
                leftText.setLineBackground(d, 1, foldMarkerBg);
            } else if (orig == -1) {
                leftText.setLineBackground(d, 1, paddingBg);
            } else if (orig < leftDiffTypes.length) {
                Color bg = diffTypeToColor(leftDiffTypes[orig], true);
                if (bg != null) leftText.setLineBackground(d, 1, bg);
            }
        }
        for (int d = 0; d < rightDisplayToOrig.length && d < rightText.getLineCount(); d++) {
            int orig = rightDisplayToOrig[d];
            if (orig < -1) {
                rightText.setLineBackground(d, 1, foldMarkerBg);
            } else if (orig == -1) {
                rightText.setLineBackground(d, 1, paddingBg);
            } else if (orig < rightDiffTypes.length) {
                Color bg = diffTypeToColor(rightDiffTypes[orig], false);
                if (bg != null) rightText.setLineBackground(d, 1, bg);
            }
        }

        // Vertical indents
        computedLineHeight = leftText.getLineHeight();
        for (int li = 0; li < leftText.getLineCount(); li++)
            leftText.setLineVerticalIndent(li, 0);
        for (int ri = 0; ri < rightText.getLineCount(); ri++)
            rightText.setLineVerticalIndent(ri, 0);

        for (GutterBlock block : gutterBlocks) {
            int leftCount = block.leftEnd - block.leftStart;
            int rightCount = block.rightEnd - block.rightStart;
            int diff = rightCount - leftCount;
            if (diff > 0 && block.leftEnd < leftOrigToDisp.length) {
                int dl = leftOrigToDisp[block.leftEnd];
                if (dl >= 0 && dl < leftText.getLineCount())
                    leftText.setLineVerticalIndent(dl, diff * computedLineHeight);
            } else if (diff < 0 && block.rightEnd < rightOrigToDisp.length) {
                int dr = rightOrigToDisp[block.rightEnd];
                if (dr >= 0 && dr < rightText.getLineCount())
                    rightText.setLineVerticalIndent(dr, (-diff) * computedLineHeight);
            }
        }

        // Syntax + fold marker styles
        applySyntaxStyles(leftText, true);
        applySyntaxStyles(rightText, false);

        for (int d = 0; d < leftDisplayToOrig.length && d < leftText.getLineCount(); d++) {
            int orig = leftDisplayToOrig[d];
            if (orig < 0) {
                int off = leftText.getOffsetAtLine(d);
                int len = leftText.getLine(d).length();
                if (len > 0) {
                    if (orig < -1) {
                        // Fold marker: blue underlined bold — looks clickable
                        StyleRange sr = new StyleRange(off, len, foldLinkFg, foldMarkerBg);
                        sr.fontStyle = SWT.BOLD;
                        sr.underline = true;
                        leftText.setStyleRange(sr);
                    } else {
                        leftText.setStyleRange(new StyleRange(off, len, gutterLine, paddingBg, SWT.ITALIC));
                    }
                }
            }
        }
        for (int d = 0; d < rightDisplayToOrig.length && d < rightText.getLineCount(); d++) {
            int orig = rightDisplayToOrig[d];
            if (orig < 0) {
                int off = rightText.getOffsetAtLine(d);
                int len = rightText.getLine(d).length();
                if (len > 0) {
                    if (orig < -1) {
                        StyleRange sr = new StyleRange(off, len, foldLinkFg, foldMarkerBg);
                        sr.fontStyle = SWT.BOLD;
                        sr.underline = true;
                        rightText.setStyleRange(sr);
                    } else {
                        rightText.setStyleRange(new StyleRange(off, len, gutterLine, paddingBg, SWT.ITALIC));
                    }
                }
            }
        }

        // Restore scroll position
        leftText.setTopPixel(savedTopLeft);
        rightText.setTopPixel(savedTopRight);

        leftText.setRedraw(true);
        rightText.setRedraw(true);
        updating = false;
        redrawAll();
    }

    private void emitEqualRegion(String[] leftLines, String[] rightLines,
            int leftStart, int leftEnd, int rightStart, int rightEnd,
            List<String> leftDisplay, List<String> rightDisplay,
            List<Integer> leftMap, List<Integer> rightMap) {
        int count = leftEnd - leftStart;
        if (count <= 0) return;

        if (count > COLLAPSE_THRESHOLD) {
            int ctx = Math.min(foldContext, count / 2);
            int hidStart = leftStart + ctx;
            int hidden   = count - 2 * ctx;
            // If this region was individually expanded, show it all
            boolean expanded = expandedFoldRegions.contains(hidStart);
            for (int i = 0; i < ctx; i++) {
                leftDisplay.add(leftLines[leftStart + i]);
                leftMap.add(leftStart + i);
                rightDisplay.add(rightLines[rightStart + i]);
                rightMap.add(rightStart + i);
            }
            if (hidden > 0 && !expanded) {
                // Encode hidden-start in map so click handler can identify region
                int markerVal = -(hidStart + 2);
                String marker = "  \u22EF  " + hidden + " unchanged lines  \u22EF  (click to show)";
                leftDisplay.add(marker);  leftMap.add(markerVal);
                rightDisplay.add(marker); rightMap.add(markerVal);
                for (int i = count - ctx; i < count; i++) {
                    leftDisplay.add(leftLines[leftStart + i]);
                    leftMap.add(leftStart + i);
                    rightDisplay.add(rightLines[rightStart + i]);
                    rightMap.add(rightStart + i);
                }
            } else {
                for (int i = ctx; i < count; i++) {
                    leftDisplay.add(leftLines[leftStart + i]);
                    leftMap.add(leftStart + i);
                    rightDisplay.add(rightLines[rightStart + i]);
                    rightMap.add(rightStart + i);
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                leftDisplay.add(leftLines[leftStart + i]);
                leftMap.add(leftStart + i);
                rightDisplay.add(rightLines[rightStart + i]);
                rightMap.add(rightStart + i);
            }
        }
    }

    private void unfold() {
        if (fullLeftText == null) return;
        updating = true;
        leftText.setText(fullLeftText);
        rightText.setText(fullRightText);
        leftText.setEditable(true);
        rightText.setEditable(true);
        leftDisplayToOrig = null;
        rightDisplayToOrig = null;
        fullLeftText = null;
        fullRightText = null;
        updating = false;
        recomputeDiff();
    }

    private void createContextButton(Composite parent) {
        contextButton = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 2; gl.marginWidth = 6;
        contextButton.setLayout(gl);
        contextButton.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        contextButton.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        Label lbl = new Label(contextButton, SWT.CENTER);
        lbl.setText("+1");
        if (labelFont != null) lbl.setFont(labelFont);
        lbl.setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
        lbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Label sub = new Label(contextButton, SWT.CENTER);
        sub.setText("Context");
        if (labelFont != null) sub.setFont(labelFont);
        sub.setForeground(navIconColor != null ? navIconColor : AppTheme.lineNumFg);
        sub.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { expandContext(); }
        };
        contextButton.addMouseListener(click);
        lbl.addMouseListener(click);
        sub.addMouseListener(click);

        MouseTrackAdapter hover = new MouseTrackAdapter() {
            @Override
            public void mouseEnter(MouseEvent e) {
                contextButton.setBackground(AppTheme.hoverBg);
                lbl.setBackground(AppTheme.hoverBg);
                sub.setBackground(AppTheme.hoverBg);
            }
            @Override
            public void mouseExit(MouseEvent e) {
                contextButton.setBackground(null);
                lbl.setBackground(null);
                sub.setBackground(null);
            }
        };
        contextButton.addMouseTrackListener(hover);
        lbl.addMouseTrackListener(hover);
        sub.addMouseTrackListener(hover);
    }

    private void expandContext() {
        if (!collapseEqual) return;
        foldContext++;
        if (unifiedMode) {
            refreshUnifiedView();
        } else {
            unfold();
            collapseEqual = true;
            applyFolding();
        }
    }

    // --- Per-section expand ---

    /** Expand a single collapsed equal region identified by its hidden-start line index. */
    private void expandFoldRegion(int hidStart) {
        expandedFoldRegions.add(hidStart);
        applyFolding();
    }

    /** Expand a single collapsed equal region in unified view. */
    private void expandUnifiedFoldRegion(int hidStart) {
        expandedUnifiedFoldRegions.add(hidStart);
        refreshUnifiedView();
    }

    /** Add click + cursor listeners for per-section expand on fold marker lines. */
    private void setupFoldClickListeners() {
        // Side-by-side: lineNumberLeft canvas (fromCanvas=true → coordinate convert)
        lineNumberLeft.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { handleFoldClick(e.y, true); }
        });
        lineNumberLeft.addMouseMoveListener(e -> {
            boolean onMarker = isFoldMarkerAt(e.y, true);
            lineNumberLeft.setCursor(onMarker
                ? lineNumberLeft.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
        });

        // Side-by-side: leftText widget
        leftText.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { handleFoldClick(e.y, false); }
        });
        leftText.addMouseMoveListener(e -> {
            boolean onMarker = isFoldMarkerAt(e.y, false);
            leftText.setCursor(onMarker
                ? leftText.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
        });

        // Side-by-side: lineNumberRight canvas
        lineNumberRight.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) {
                // Convert to leftText coords (same layout row, same y mapping)
                handleFoldClick(leftText.toControl(lineNumberRight.toDisplay(0, e.y)).y, false);
            }
        });
        lineNumberRight.addMouseMoveListener(e -> {
            int textY = leftText.toControl(lineNumberRight.toDisplay(0, e.y)).y;
            boolean onMarker = isFoldMarkerAt(textY, false);
            lineNumberRight.setCursor(onMarker
                ? lineNumberRight.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
        });

        // Side-by-side: rightText widget
        rightText.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) {
                int textY = leftText.toControl(rightText.toDisplay(0, e.y)).y;
                handleFoldClick(textY, false);
            }
        });
        rightText.addMouseMoveListener(e -> {
            int textY = leftText.toControl(rightText.toDisplay(0, e.y)).y;
            boolean onMarker = isFoldMarkerAt(textY, false);
            rightText.setCursor(onMarker
                ? rightText.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
        });

        // Unified: lineNumberUnified canvas
        if (lineNumberUnified != null) {
            lineNumberUnified.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseUp(MouseEvent e) { handleUnifiedFoldClick(e.y, true); }
            });
            lineNumberUnified.addMouseMoveListener(e -> {
                boolean onMarker = isUnifiedFoldMarkerAt(e.y, true);
                lineNumberUnified.setCursor(onMarker
                    ? lineNumberUnified.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
            });
        }

        // Unified: unifiedText widget
        if (unifiedText != null) {
            unifiedText.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseUp(MouseEvent e) { handleUnifiedFoldClick(e.y, false); }
            });
            unifiedText.addMouseMoveListener(e -> {
                boolean onMarker = isUnifiedFoldMarkerAt(e.y, false);
                unifiedText.setCursor(onMarker
                    ? unifiedText.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
            });
        }
    }

    /** Convert a y-pixel from any widget in the same row as leftText into a leftText line index. */
    private int yToLeftLine(int y) {
        try { return leftText.getLineIndex(y); }
        catch (Exception ex) { return -1; }
    }

    /** Convert a y-pixel from any widget in the same row as unifiedText into a unifiedText line index. */
    private int yToUnifiedLine(int y) {
        try { return unifiedText.getLineIndex(y); }
        catch (Exception ex) { return -1; }
    }

    private boolean isFoldMarkerAt(int widgetY, boolean fromCanvas) {
        if (!collapseEqual || leftDisplayToOrig == null) return false;
        int y = fromCanvas ? leftText.toControl(lineNumberLeft.toDisplay(0, widgetY)).y : widgetY;
        int line = yToLeftLine(y);
        return line >= 0 && line < leftDisplayToOrig.length && leftDisplayToOrig[line] < -1;
    }

    private void handleFoldClick(int widgetY, boolean fromCanvas) {
        if (!collapseEqual || leftDisplayToOrig == null) return;
        int y = fromCanvas ? leftText.toControl(lineNumberLeft.toDisplay(0, widgetY)).y : widgetY;
        int line = yToLeftLine(y);
        if (line < 0 || line >= leftDisplayToOrig.length) return;
        int mapVal = leftDisplayToOrig[line];
        if (mapVal < -1) expandFoldRegion(-(mapVal + 2));
    }

    private boolean isUnifiedFoldMarkerAt(int widgetY, boolean fromCanvas) {
        if (!collapseEqual || unifiedLineTypes == null || unifiedOrigLeft == null) return false;
        if (unifiedText == null || unifiedText.isDisposed()) return false;
        int y = fromCanvas ? unifiedText.toControl(lineNumberUnified.toDisplay(0, widgetY)).y : widgetY;
        int line = yToUnifiedLine(y);
        return line >= 0 && line < unifiedLineTypes.length
            && unifiedLineTypes[line] == DiffType.HIDDEN;
    }

    private void handleUnifiedFoldClick(int widgetY, boolean fromCanvas) {
        if (!collapseEqual || unifiedLineTypes == null || unifiedOrigLeft == null) return;
        if (unifiedText == null || unifiedText.isDisposed()) return;
        int y = fromCanvas ? unifiedText.toControl(lineNumberUnified.toDisplay(0, widgetY)).y : widgetY;
        int line = yToUnifiedLine(y);
        if (line < 0 || line >= unifiedLineTypes.length) return;
        if (unifiedLineTypes[line] == DiffType.HIDDEN) {
            int ol = unifiedOrigLeft[line];
            if (ol < -1) expandUnifiedFoldRegion(-(ol + 2));
        }
    }

    // --- Token-based inner-line diff ---

    private static class Token {
        String text;
        int start; // char offset in original line
        int end;   // char offset end (exclusive)
        Token(String text, int start, int end) {
            this.text = text; this.start = start; this.end = end;
        }
    }

    private List<Token> tokenize(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                // Whitespace token
                int start = i;
                while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
                tokens.add(new Token(line.substring(start, i), start, i));
            } else if (c == '"') {
                // String literal token
                int start = i;
                i++;
                while (i < line.length()) {
                    if (line.charAt(i) == '\\' && i + 1 < line.length()) { i += 2; continue; }
                    if (line.charAt(i) == '"') { i++; break; }
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), start, i));
            } else if (c == '\'' ) {
                // Char literal token
                int start = i;
                i++;
                while (i < line.length()) {
                    if (line.charAt(i) == '\\' && i + 1 < line.length()) { i += 2; continue; }
                    if (line.charAt(i) == '\'') { i++; break; }
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), start, i));
            } else if (Character.isJavaIdentifierStart(c)) {
                // Identifier/keyword token
                int start = i;
                while (i < line.length() && Character.isJavaIdentifierPart(line.charAt(i))) i++;
                tokens.add(new Token(line.substring(start, i), start, i));
            } else if (Character.isDigit(c)) {
                // Number token (including hex, float suffixes)
                int start = i;
                while (i < line.length() && (Character.isDigit(line.charAt(i))
                        || line.charAt(i) == '.' || line.charAt(i) == 'f'
                        || line.charAt(i) == 'x' || line.charAt(i) == 'X'
                        || (line.charAt(i) >= 'a' && line.charAt(i) <= 'f')
                        || (line.charAt(i) >= 'A' && line.charAt(i) <= 'F'))) i++;
                tokens.add(new Token(line.substring(start, i), start, i));
            } else {
                // Operator/punctuation: group consecutive non-alnum, non-space chars
                int start = i;
                while (i < line.length() && !Character.isWhitespace(line.charAt(i))
                        && !Character.isJavaIdentifierStart(line.charAt(i))
                        && !Character.isDigit(line.charAt(i))
                        && line.charAt(i) != '"' && line.charAt(i) != '\'') i++;
                tokens.add(new Token(line.substring(start, i), start, i));
            }
        }
        return tokens;
    }

    // Token-level LCS diff to find changed spans within a line pair
    private int[][] computeInnerLineChanges(String left, String right) {
        List<Token> leftTokens = tokenize(left);
        List<Token> rightTokens = tokenize(right);
        int n = leftTokens.size(), m = rightTokens.size();

        // LCS on tokens
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (leftTokens.get(i - 1).text.equals(rightTokens.get(j - 1).text)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Backtrack to find which tokens are equal
        boolean[] leftEqual = new boolean[n];
        boolean[] rightEqual = new boolean[m];
        int i = n, j = m;
        while (i > 0 && j > 0) {
            if (leftTokens.get(i - 1).text.equals(rightTokens.get(j - 1).text)) {
                leftEqual[i - 1] = true;
                rightEqual[j - 1] = true;
                i--; j--;
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                i--;
            } else {
                j--;
            }
        }

        // Map changed tokens back to character column ranges
        return new int[][] {
            extractTokenRanges(leftTokens, leftEqual),
            extractTokenRanges(rightTokens, rightEqual)
        };
    }

    private int[] extractTokenRanges(List<Token> tokens, boolean[] equal) {
        List<Integer> ranges = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            if (!equal[i]) {
                int startCol = tokens.get(i).start;
                while (i < tokens.size() && !equal[i]) i++;
                int endCol = tokens.get(i - 1).end;
                ranges.add(startCol);
                ranges.add(endCol);
            } else {
                i++;
            }
        }
        return ranges.stream().mapToInt(Integer::intValue).toArray();
    }

    private Color diffTypeToColor(DiffType type, boolean isLeft) {
        switch (type) {
            case ADDED:   return isLeft ? paddingBg : addedBg;
            case REMOVED: return isLeft ? removedBg : paddingBg;
            case CHANGED: return isLeft ? changedBgL : addedBg;
            default:      return null;
        }
    }

    // --- Myers Diff Algorithm ---

    private static class DiffEdit {
        int leftStart, leftEnd;   // lines deleted from left [leftStart, leftEnd)
        int rightStart, rightEnd; // lines inserted in right [rightStart, rightEnd)
        DiffEdit(int ls, int le, int rs, int re) {
            leftStart = ls; leftEnd = le; rightStart = rs; rightEnd = re;
        }
    }

    private List<DiffEdit> myersDiff(String[] a, String[] b) {
        int n = a.length, m = b.length;
        int max = n + m;
        int[] v = new int[2 * max + 1];
        Arrays.fill(v, -1);
        v[max + 1] = 0; // v[1] = 0 (offset by max)

        List<int[]> trace = new ArrayList<>();

        for (int d = 0; d <= max; d++) {
            trace.add(Arrays.copyOf(v, v.length));
            for (int k = -d; k <= d; k += 2) {
                int idx = k + max;
                int x;
                if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    x = v[idx + 1]; // move down
                } else {
                    x = v[idx - 1] + 1; // move right
                }
                int y = x - k;
                // Follow diagonal (equal lines)
                while (x < n && y < m && a[x].equals(b[y])) {
                    x++; y++;
                }
                v[idx] = x;
                if (x >= n && y >= m) {
                    return backtrack(trace, a, b, n, m, max);
                }
            }
        }
        return new ArrayList<>();
    }

    private List<DiffEdit> backtrack(List<int[]> trace, String[] a, String[] b, int n, int m, int max) {
        List<DiffEdit> edits = new ArrayList<>();
        int x = n, y = m;

        for (int d = trace.size() - 1; d > 0; d--) {
            int[] v = trace.get(d);  // state before step d (= after step d-1)
            int k = x - y;

            int prevK;
            if (k == -d || (k != d && v[k - 1 + max] < v[k + 1 + max])) {
                prevK = k + 1;  // moved down (insert)
            } else {
                prevK = k - 1;  // moved right (delete)
            }
            int prevX = v[prevK + max];
            int prevY = prevX - prevK;

            // Walk back the diagonal (equal lines)
            while (x > prevX && y > prevY) {
                x--; y--;
            }

            // Emit edit
            if (x == prevX) {
                // Insert: b[y-1] was added
                edits.add(0, new DiffEdit(x, x, y - 1, y));
            } else {
                // Delete: a[x-1] was removed
                edits.add(0, new DiffEdit(x - 1, x, y, y));
            }

            x = prevX;
            y = prevY;
        }

        // Merge adjacent edits into change blocks
        return mergeEdits(edits);
    }

    private List<DiffEdit> mergeEdits(List<DiffEdit> edits) {
        if (edits.isEmpty()) return edits;
        List<DiffEdit> merged = new ArrayList<>();
        DiffEdit current = edits.get(0);
        for (int i = 1; i < edits.size(); i++) {
            DiffEdit next = edits.get(i);
            // Merge if they are adjacent (touching)
            if (current.leftEnd >= next.leftStart && current.rightEnd >= next.rightStart) {
                current = new DiffEdit(
                    current.leftStart, Math.max(current.leftEnd, next.leftEnd),
                    current.rightStart, Math.max(current.rightEnd, next.rightEnd)
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}
