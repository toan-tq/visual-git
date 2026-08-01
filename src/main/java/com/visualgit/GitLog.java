package com.visualgit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public class GitLog {

    private String repoPath;
    private Display display;
    private Shell shell;

    // Toolbar
    private Combo repoPathCombo;
    private Label commitsLabel;
    private Button refreshBtn;
    private Button fetchBtn, pullBtn, pushBtn, stashToolbarBtn;

    // Branches sidebar
    private Tree branchTree;
    private Map<String, String> branchToCommit = new HashMap<>();
    private Map<String, List<String>> commitToRefs = new HashMap<>();

    // Tables
    private Table commitTable;
    private Table filesTable;

    // Detail area (read-only commit detail)
    private Text detailMsg;
    private Label detailMeta;
    private Composite detailComposite;

    // Commit area (working tree: commit message + buttons)
    private Composite commitArea;
    private Text commitMsgText;
    private Button commitBtn;
    private Button amendCheck;
    private Label commitMeta;

    // Conflict area (merge/cherry-pick/revert in progress)
    private Composite conflictArea;
    private Label conflictLabel;
    private Button abortBtn, continueBtn;
    private String conflictOperation = null;  // "merge", "cherry-pick", "revert", or null
    private boolean conflictedCollapsed;

    // Stage/unstage buttons in files header
    private Button stageAllBtn;
    private Button unstageAllBtn;
    private Label filesHeaderTitle;
    private Label summaryModified, summaryAdded, summaryDeleted;

    // Track working tree state
    private boolean isWorkingTreeSelected;
    private String savedCommitMsg = "";
    private boolean stagedCollapsed;
    private boolean unstagedCollapsed;

    // Embedded FileCompare panel
    private FileCompare embeddedCompare;

    // Main area sash (needed for toolbar alignment)
    private SashForm topSash;

    // Status bar
    private Label statusBarLeft;
    private Label statusBarRight;

    // Font
    private Font appFont;
    private Font boldFont;
    private Font tagFont;

    // Repo history
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".visualgit");
    private static final Path REPO_HISTORY_FILE = CONFIG_DIR.resolve("repo_history.txt");
    private static final int MAX_REPO_HISTORY = 7;
    private List<String> repoHistory = new ArrayList<>();

    // Graph lane colors
    private Color[] laneColors;

    // Layout metrics (computed at runtime from font)
    private int fontHeight;
    private int headerHeight;
    private int toolbarMarginH;
    private int headerMarginH;
    private int toolbarSepHeight;
    private int repoPathWidth;

    // Colors
    private Color modifiedFg;
    private Color addedFg;
    private Color renamedFg;
    private Color additionsFg;
    private Color deletionsFg;
    private Color hashFg;
    private Color detailBg;
    private Color metaFg;
    private Color workingTreeBg;
    private Color searchBorderColor;
    private Color searchBorderFocusColor;
    private Color toolbarBg;
    private Color sectionBg;
    private Color sectionFg;
    private Color commitBtnBg;
    private Color commitBtnFg;
    private Color conflictBg;
    private Color conflictFg;

    // Branch tag colors (inline on commit table)
    private Color tagLocalBg;
    private Color tagLocalFg;
    private Color tagRemoteBg;
    private Color tagRemoteFg;
    private Color tagHeadBg;
    private Color tagHeadFg;
    private Color tagTagBg;
    private Color tagTagFg;
    private Color sidebarBg;
    private Color currentBranchBg;

    // Data
    private List<CommitInfo> allCommits = new ArrayList<>();
    private List<CommitInfo> filteredCommits = new ArrayList<>();
    private List<FileChange> currentFiles = new ArrayList<>();
    private String currentBranch = "";    // actual git HEAD branch
    private String logBranch = "";         // branch shown in commit log (sidebar selection)

    // Files table sort (persisted across sessions)
    private static final Preferences PREFS = Preferences.userNodeForPackage(GitLog.class);
    private String fileSortMode = PREFS.get("fileSortMode", "name");        // "name" | "modified" | "status"
    private boolean fileSortAscending = PREFS.getBoolean("fileSortAsc", true);
    private TableColumn filesColStatus;
    private TableColumn filesColName;
    private TableColumn filesColModified;

    // Status chip icons (solid colored badge with white letter), keyed by git status char
    private Map<Character, Image> statusChips = new HashMap<>();

    // Auto-refresh fingerprints — UI is only rebuilt when these change
    private String lastRefsFingerprint = "";
    private String lastWtFingerprint = "";

    // Output of the last failed git command (for error reporting)
    private String lastGitError = "";

    // Search
    private Text filterText;
    private Combo searchModeCombo;
    private static final int SEARCH_ALL = 0;
    private static final int SEARCH_MESSAGE = 1;
    private static final int SEARCH_AUTHOR = 2;
    private static final int SEARCH_FILE = 3;
    private String activeSearchQuery = "";

    // ── Layout Constants ──────────────────────────────────────────────
    //
    // Main window layout (all sizes in pixels unless noted as % or ratio):
    //
    // ┌──────────────────────────────────────────────────────────────────────┐
    // │ TOOLBAR  h:TOOLBAR_MARGIN_H  w:TOOLBAR_MARGIN_W  sp:TOOLBAR_SPACING│
    // │ [Repo: [____REPO_PATH_WIDTH____] [...] | Branch: [BRANCH_COMBO_W]] │
    // ├──────────────────────────────────────────────────────────────────────┤
    // │                     VERTICAL SASH                                   │
    // │  ┌─────────────────────────┬────────────────────────────────────┐   │
    // │  │                         │                                    │   │
    // │  │  COMMIT_PANEL_WEIGHT    │   FILES_PANEL_WEIGHT               │   │
    // │  │  (60%)                  │   (40%)                            │   │
    // │  │                         │                                    │   │
    // │  │  ┌─HEADER──────────────┐│  ┌─HEADER────────────────────────┐│   │
    // │  │  │COMMITS [combo][search]│  │CHANGED FILES                 ││   │
    // │  │  │  SEARCH_COMBO_WIDTH ││  └───────────────────────────────┘│   │
    // │  │  │  SEARCH_FIELD_WIDTH ││                                    │   │
    // │  │  └─────────────────────┘│  ┌─FILES TABLE───────────────────┐│   │
    // │  │                         │  │ St │ Name(45%) │ Dir(55%) │+│-││   │
    // │  │  ┌─COMMIT TABLE────────┐│  │COL_STATUS  COL_ADD  COL_DEL  ││   │
    // │  │  │Hash │Msg  │Auth│Date││  └───────────────────────────────┘│   │
    // │  │  │ 10% │rest │15% │20% ││                                    │   │
    // │  │  └─────────────────────┘│  ┌─DETAIL AREA───────────────────┐│   │
    // │  │                         │  │ DETAIL_HEIGHT                  ││   │
    // │  │  TOP_ROW_WEIGHT (70%)   │  │ commit message + meta         ││   │
    // │  │                         │  └───────────────────────────────┘│   │
    // │  └─────────────────────────┴────────────────────────────────────┘   │
    // │  ┌──────────────────────────────────────────────────────────────┐   │
    // │  │  BOTTOM: Embedded FileCompare diff panel                    │   │
    // │  │  BOTTOM_ROW_WEIGHT (30%)                                    │   │
    // │  └──────────────────────────────────────────────────────────────┘   │
    // ├──────────────────────────────────────────────────────────────────────┤
    // │ STATUS BAR  h:STATUSBAR_MARGIN_H  w:STATUSBAR_MARGIN_W             │
    // └──────────────────────────────────────────────────────────────────────┘

    // -- Window --
    private static final double WINDOW_SIZE_RATIO = 0.9;         // 90% of screen

    // -- Toolbar (pixel values computed at runtime from font metrics) --
    private static final int TOOLBAR_MARGIN_W   = 10;
    private static final int TOOLBAR_SPACING    = 5;

    // -- Main area sash weights (top:bottom vertical split) --
    private static final int TOP_ROW_WEIGHT    = 70;             // commit log + files
    private static final int BOTTOM_ROW_WEIGHT = 30;             // embedded diff

    // -- Top row sash weights (sidebar:commits:files horizontal split) --
    private static final int SIDEBAR_WEIGHT      = 15;           // branches sidebar
    private static final int COMMIT_PANEL_WEIGHT = 50;           // commit log
    private static final int FILES_PANEL_WEIGHT  = 35;           // files + detail

    // -- Commit panel header (search area) --
    private static final int HEADER_MARGIN_W      = 10;
    private static final int HEADER_SPACING       = 10;
    private static final int SEARCH_BORDER_PAD_W  = 10;
    private static final int SEARCH_BORDER_PAD_H  = 4;
    private static final int SEARCH_BORDER_RADIUS = 14;

    // -- Graph column --
    private static final int LANE_WIDTH    = 18;                // pixels per graph lane
    private static final int GRAPH_PADDING = 6;                 // left/right padding
    private static final int DOT_RADIUS    = 5;                 // commit dot radius

    // -- Commit table column widths (percentage of table width, excluding graph) --
    private static final int COL_HASH_PCT   = 10;               // Hash column
    private static final int COL_AUTHOR_PCT = 15;               // Author column
    private static final int COL_DATE_PCT   = 20;               // Date column
    // Message column gets the remainder (100 - 10 - 15 - 20 = 55%)

    // -- Files table column widths --
    private static final int COL_STATUS_WIDTH = 62;              // checkbox + status chip (M/A/D)
    private static final int COL_ADD_WIDTH    = 50;              // additions (+)
    private static final int COL_DEL_WIDTH    = 50;              // deletions (-)
    private static final int COL_MODIFIED_WIDTH = 140;           // last modification time (fits "yyyy-MM-dd HH:mm")
    private static final int COL_NAME_PCT     = 35;              // Name % of remaining

    // -- Detail area (commit message box below CHANGED FILES) --
    private static final int DETAIL_HEIGHT   = 350;
    private static final int DETAIL_MARGIN_H = 6;
    private static final int DETAIL_MARGIN_W = 10;
    private static final int DETAIL_SPACING  = 4;

    // -- Minimum column widths --
    private static final int MIN_MSG_COL_WIDTH  = 100;
    private static final int MIN_NAME_COL_WIDTH = 80;
    private static final int MIN_DIR_COL_WIDTH  = 80;

    // -- Search border --
    private static final int SEARCH_BORDER_WIDTH = 2;

    // -- Status bar --
    private static final int STATUSBAR_MARGIN_H = 2;
    private static final int STATUSBAR_MARGIN_W = 12;

    public static void main(String[] args) {
        new GitLog(args).run();
    }

    public GitLog(String[] args) {
        if (args.length >= 1) {
            repoPath = args[0];
        } else {
            repoPath = System.getProperty("user.dir");
        }
    }

    public void run() {
        Display.setAppName("GitLog");
        display = new Display();
        shell = new Shell(display);
        shell.setText("GitLog \u2014 " + repoPath);

        // Set window icon — try jar location first, then user.dir
        try {
            String iconPath = null;
            // When running from jpackage, the JAR is in app/ alongside resources/
            try {
                java.net.URL jarUrl = GitLog.class.getProtectionDomain().getCodeSource().getLocation();
                java.nio.file.Path jarDir = Paths.get(jarUrl.toURI()).getParent();
                java.nio.file.Path candidate = jarDir.resolve("resources").resolve("gitlog-icon.png");
                if (java.nio.file.Files.exists(candidate)) iconPath = candidate.toString();
            } catch (Exception ignored) {}
            // Fallback: user.dir (dev mode)
            if (iconPath == null) {
                iconPath = Paths.get(System.getProperty("user.dir"), "resources", "gitlog-icon.png").toString();
            }
            Image icon = new Image(display, iconPath);
            shell.setImage(icon);
            shell.addDisposeListener(e -> icon.dispose());
        } catch (Exception e) {
            // Icon not found — continue without it
        }
        Rectangle screenBounds = display.getPrimaryMonitor().getBounds();
        shell.setSize((int)(screenBounds.width * WINDOW_SIZE_RATIO), (int)(screenBounds.height * WINDOW_SIZE_RATIO));

        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        layout.verticalSpacing = 0;
        shell.setLayout(layout);

        AppTheme.init(display);
        initColors(display);
        int fontSize = AppTheme.IS_MAC ? 14 : AppTheme.IS_WIN ? 10 : 11;
        appFont = new Font(display, AppTheme.SANS_FONT, fontSize, SWT.NORMAL);
        boldFont = new Font(display, AppTheme.SANS_FONT, fontSize, SWT.BOLD);
        int tagFontSize = AppTheme.IS_MAC ? 10 : 8;
        tagFont = new Font(display, AppTheme.SANS_FONT, tagFontSize, SWT.BOLD);
        computeLayoutMetrics();
        initStatusChips();

        createUI(shell);
        loadBranches();
        loadCommits();

        shell.open();

        // Auto-refresh: poll git state on a background thread every 30 seconds
        // and only touch the UI when something actually changed
        final int AUTO_REFRESH_MS = 30_000;
        Runnable autoRefresh = new Runnable() {
            @Override public void run() {
                if (shell.isDisposed()) return;
                final String repo = repoPath;
                Thread poller = new Thread(() -> {
                    final String refsFp = computeRefsFingerprint();
                    final String wtFp = computeWtFingerprint();
                    display.asyncExec(() -> {
                        if (shell.isDisposed()) return;
                        if (repo.equals(repoPath)) {
                            boolean refsChanged = !refsFp.equals(lastRefsFingerprint);
                            boolean wtChanged = !wtFp.equals(lastWtFingerprint);
                            lastRefsFingerprint = refsFp;
                            lastWtFingerprint = wtFp;
                            if (refsChanged) {
                                loadBranches();
                                loadCommits();
                            } else if (wtChanged && isWorkingTreeSelected) {
                                reloadWorkingTree();
                            }
                        }
                        display.timerExec(AUTO_REFRESH_MS, this);
                    });
                }, "gitlog-autorefresh");
                poller.setDaemon(true);
                poller.start();
            }
        };
        display.timerExec(AUTO_REFRESH_MS, autoRefresh);

        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }

        if (embeddedCompare != null) embeddedCompare.disposeResources();
        appFont.dispose();
        boldFont.dispose();
        tagFont.dispose();
        disposeStatusChips();
        disposeColors();
        AppTheme.dispose();
        display.dispose();
    }

    private void initColors(Display display) {
        modifiedFg = new Color(display, 133, 100, 4);
        addedFg    = new Color(display, 21, 87, 36);
        renamedFg  = new Color(display, 35, 100, 180);
        additionsFg = new Color(display, 40, 167, 69);
        deletionsFg = new Color(display, 220, 53, 69);
        hashFg     = new Color(display, 136, 136, 136);
        detailBg   = new Color(display, 246, 246, 246);
        metaFg     = new Color(display, 136, 136, 136);
        workingTreeBg = new Color(display, 255, 255, 220);
        searchBorderColor = new Color(display, 130, 130, 130);
        searchBorderFocusColor = new Color(display, 60, 120, 200);
        toolbarBg = new Color(display, 236, 236, 236);
        sectionBg = new Color(display, 230, 230, 230);
        sectionFg = new Color(display, 80, 80, 80);
        commitBtnBg = new Color(display, 40, 120, 200);
        commitBtnFg = new Color(display, 255, 255, 255);
        conflictBg = new Color(display, 255, 235, 235);
        conflictFg = new Color(display, 180, 0, 0);

        // Branch tag colors
        tagLocalBg = new Color(display, 212, 237, 218);
        tagLocalFg = new Color(display, 21, 87, 36);
        tagRemoteBg = new Color(display, 226, 227, 241);
        tagRemoteFg = new Color(display, 56, 61, 110);
        tagHeadBg = new Color(display, 255, 243, 205);
        tagHeadFg = new Color(display, 133, 100, 4);
        tagTagBg = new Color(display, 254, 243, 205);
        tagTagFg = new Color(display, 138, 109, 11);
        sidebarBg = new Color(display, 247, 247, 247);
        currentBranchBg = new Color(display, 238, 243, 252);

        // Graph lane colors
        laneColors = new Color[]{
            new Color(display, 30, 100, 200),   // blue
            new Color(display, 40, 160, 80),    // green
            new Color(display, 200, 120, 30),   // orange
            new Color(display, 140, 60, 180),   // purple
            new Color(display, 200, 50, 60),    // red
            new Color(display, 30, 160, 160),   // cyan
            new Color(display, 170, 120, 50),   // brown
            new Color(display, 100, 100, 180),  // slate
        };
    }

    private void disposeColors() {
        modifiedFg.dispose();
        addedFg.dispose();
        renamedFg.dispose();
        additionsFg.dispose();
        deletionsFg.dispose();
        hashFg.dispose();
        detailBg.dispose();
        metaFg.dispose();
        workingTreeBg.dispose();
        searchBorderColor.dispose();
        searchBorderFocusColor.dispose();
        toolbarBg.dispose();
        sectionBg.dispose();
        sectionFg.dispose();
        commitBtnBg.dispose();
        commitBtnFg.dispose();
        conflictBg.dispose();
        conflictFg.dispose();
        tagLocalBg.dispose();
        tagLocalFg.dispose();
        tagRemoteBg.dispose();
        tagRemoteFg.dispose();
        tagHeadBg.dispose();
        tagHeadFg.dispose();
        tagTagBg.dispose();
        tagTagFg.dispose();
        sidebarBg.dispose();
        currentBranchBg.dispose();
        for (Color c : laneColors) c.dispose();
    }

    // ── Status chips ─────────────────────────────────────────────────
    // Solid colored badges with a white letter — far more visible than a
    // colored letter on white, and the letter keeps them colorblind-safe.

    private void initStatusChips() {
        statusChips.put('M', renderStatusChip(new RGB(216, 156, 8),   "M"));
        statusChips.put('A', renderStatusChip(new RGB(40, 167, 69),   "A"));
        statusChips.put('D', renderStatusChip(new RGB(220, 53, 69),   "D"));
        statusChips.put('R', renderStatusChip(new RGB(52, 120, 200),  "R"));
        statusChips.put('C', renderStatusChip(new RGB(52, 120, 200),  "C"));
        statusChips.put('?', renderStatusChip(new RGB(140, 140, 140), "?"));
        statusChips.put('U', renderStatusChip(new RGB(180, 0, 0),     "!"));
    }

    private void disposeStatusChips() {
        for (Image img : statusChips.values()) img.dispose();
        statusChips.clear();
    }

    private Image renderStatusChip(RGB rgb, String letter) {
        GC measure = new GC(shell);
        measure.setFont(tagFont);
        Point letterExt = measure.textExtent(letter);
        int wideW = measure.textExtent("M").x; // uniform chip size across letters
        measure.dispose();
        int h = letterExt.y + 4;
        int w = wideW + 16;

        // Draw on a magic-color background, then mark it transparent so the
        // chip composes cleanly over white rows and the selection highlight
        RGB magic = new RGB(255, 0, 255);
        Image img = new Image(display, w, h);
        GC gc = new GC(img);
        Color magicColor = new Color(display, magic);
        gc.setBackground(magicColor);
        gc.fillRectangle(0, 0, w, h);
        Color fill = new Color(display, rgb);
        gc.setBackground(fill);
        gc.fillRoundRectangle(0, 0, w, h, 6, 6);
        gc.setFont(tagFont);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
        Point tExt = gc.textExtent(letter);
        gc.drawText(letter, (w - tExt.x) / 2, (h - tExt.y) / 2, SWT.DRAW_TRANSPARENT);
        gc.dispose();
        fill.dispose();
        magicColor.dispose();

        ImageData data = img.getImageData();
        data.transparentPixel = data.palette.getPixel(magic);
        img.dispose();
        return new Image(display, data);
    }

    private void computeLayoutMetrics() {
        GC gc = new GC(shell);
        gc.setFont(appFont);
        fontHeight = gc.getFontMetrics().getHeight();
        int charWidth = gc.textExtent("n").x;      // average char width
        gc.dispose();

        // Use a test Combo to measure native widget height on this platform
        Combo probe = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);
        probe.setFont(appFont);
        int nativeWidgetHeight = probe.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        probe.dispose();

        // headerHeight must fit the tallest native widget + vertical margins
        toolbarMarginH = Math.max(fontHeight / 4, 3);
        headerMarginH = Math.max(fontHeight / 5, 2);
        headerHeight = nativeWidgetHeight + toolbarMarginH * 2 + 2;
        toolbarSepHeight = nativeWidgetHeight - 4;
        repoPathWidth = charWidth * 50;            // 50 chars
    }

    private void createUI(Shell shell) {
        createToolbar(shell);
        createMainArea(shell);
        createStatusBar(shell);
        createKeyboardShortcuts();
    }

    // ── Toolbar ──────────────────────────────────────────────────────

    private void createToolbar(Shell shell) {
        Composite toolbar = new Composite(shell, SWT.NONE);
        toolbar.setBackground(toolbarBg);
        GridData tbGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tbGd.heightHint = headerHeight;
        toolbar.setLayoutData(tbGd);
        GridLayout tbLayout = new GridLayout(10, false);
        tbLayout.marginHeight = toolbarMarginH;
        tbLayout.marginWidth = TOOLBAR_MARGIN_W;
        tbLayout.horizontalSpacing = TOOLBAR_SPACING;
        toolbar.setLayout(tbLayout);

        // [Repo:] [path] [...]
        Label repoLabel = new Label(toolbar, SWT.NONE);
        repoLabel.setText("Repo:");
        repoLabel.setFont(appFont);
        repoLabel.setBackground(toolbarBg);

        repoPathCombo = new Combo(toolbar, SWT.BORDER | SWT.DROP_DOWN);
        loadRepoHistory();
        for (String h : repoHistory) repoPathCombo.add(h);
        repoPathCombo.setText(repoPath != null ? repoPath : "");
        repoPathCombo.setFont(appFont);
        GridData rpGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        rpGd.minimumWidth = repoPathWidth / 2;
        repoPathCombo.setLayoutData(rpGd);
        repoPathCombo.addTraverseListener(e -> {
            if (e.detail == SWT.TRAVERSE_RETURN) {
                repoPath = repoPathCombo.getText().trim();
                addRepoHistory(repoPath);
                loadBranches();
                loadCommits();
            }
        });
        repoPathCombo.addListener(SWT.Selection, e -> {
            repoPath = repoPathCombo.getText().trim();
            addRepoHistory(repoPath);
            loadBranches();
            loadCommits();
        });

        Button browseBtn = new Button(toolbar, SWT.PUSH);
        browseBtn.setText("...");
        browseBtn.setFont(appFont);
        browseBtn.addListener(SWT.Selection, e -> {
            DirectoryDialog dialog = new DirectoryDialog(shell);
            dialog.setText("Select Git Repository");
            if (repoPath != null) dialog.setFilterPath(repoPath);
            String selected = dialog.open();
            if (selected != null) {
                repoPath = selected;
                repoPathCombo.setText(selected);
                addRepoHistory(selected);
                loadBranches();
                loadCommits();
            }
        });

        // Spacer
        Label spacer = new Label(toolbar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        spacer.setBackground(toolbarBg);

        // [Fetch] [Pull] [Push]
        fetchBtn = new Button(toolbar, SWT.PUSH);
        fetchBtn.setText("\u2193 Fetch");
        fetchBtn.setFont(appFont);
        fetchBtn.addListener(SWT.Selection, e -> doFetch());

        pullBtn = new Button(toolbar, SWT.PUSH);
        pullBtn.setText("\u2193 Pull");
        pullBtn.setFont(appFont);
        pullBtn.addListener(SWT.Selection, e -> doPull());

        pushBtn = new Button(toolbar, SWT.PUSH);
        pushBtn.setText("\u2191 Push");
        pushBtn.setFont(appFont);
        pushBtn.addListener(SWT.Selection, e -> doPush());

        // Separator
        Label sep = new Label(toolbar, SWT.SEPARATOR | SWT.VERTICAL);
        GridData sepGd = new GridData(SWT.CENTER, SWT.FILL, false, false);
        sepGd.heightHint = toolbarSepHeight;
        sep.setLayoutData(sepGd);

        // [Stash]
        stashToolbarBtn = new Button(toolbar, SWT.PUSH);
        stashToolbarBtn.setText("Stash");
        stashToolbarBtn.setFont(appFont);
        stashToolbarBtn.addListener(SWT.Selection, e -> showStashDialog());

        // [↻ Refresh]
        refreshBtn = new Button(toolbar, SWT.PUSH);
        refreshBtn.setText("\u21BB");
        refreshBtn.setFont(appFont);
        refreshBtn.addListener(SWT.Selection, e -> {
            loadBranches();
            loadCommits();
        });
    }

    // ── Main area ────────────────────────────────────────────────────

    private void createMainArea(Shell shell) {
        // Vertical split: top = commit log + files, bottom = embedded diff
        SashForm verticalSash = new SashForm(shell, SWT.VERTICAL);
        verticalSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Top row: [Sidebar | CommitPanel | RightPanel]
        topSash = new SashForm(verticalSash, SWT.HORIZONTAL);
        createSidebar(topSash);
        createCommitPanel(topSash);
        createRightPanel(topSash);
        topSash.setWeights(new int[]{SIDEBAR_WEIGHT, COMMIT_PANEL_WEIGHT, FILES_PANEL_WEIGHT});

        // Bottom row: embedded FileCompare panel
        Composite diffPanel = new Composite(verticalSash, SWT.NONE);
        diffPanel.setLayout(new GridLayout(1, false));
        ((GridLayout) diffPanel.getLayout()).marginHeight = 0;
        ((GridLayout) diffPanel.getLayout()).marginWidth = 0;
        ((GridLayout) diffPanel.getLayout()).verticalSpacing = 0;

        embeddedCompare = new FileCompare(null, null, display);
        embeddedCompare.createPanel(diffPanel);

        verticalSash.setWeights(new int[]{TOP_ROW_WEIGHT, BOTTOM_ROW_WEIGHT});
    }

    // ── Branches sidebar ────────────────────────────────────────────

    private void createSidebar(Composite parent) {
        Composite panel = new Composite(parent, SWT.NONE);
        panel.setBackground(sidebarBg);
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 0;
        gl.marginWidth = 0;
        gl.verticalSpacing = 0;
        panel.setLayout(gl);

        // Header
        Composite header = new Composite(panel, SWT.NONE);
        header.setBackground(toolbarBg);
        GridData hgd = new GridData(SWT.FILL, SWT.FILL, true, false);
        hgd.heightHint = headerHeight;
        header.setLayoutData(hgd);
        GridLayout hgl = new GridLayout(1, false);
        hgl.marginHeight = headerMarginH;
        hgl.marginWidth = HEADER_MARGIN_W;
        header.setLayout(hgl);

        Label title = new Label(header, SWT.NONE);
        title.setText("BRANCHES");
        title.setFont(appFont);
        title.setBackground(toolbarBg);

        // Separator
        Label sep = new Label(panel, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Tree
        branchTree = new Tree(panel, SWT.BORDER | SWT.V_SCROLL | SWT.SINGLE);
        branchTree.setFont(appFont);
        branchTree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Selection → filter commits to selected branch
        branchTree.addListener(SWT.Selection, e -> onSidebarSelection());

        // Double-click → checkout branch
        branchTree.addListener(SWT.DefaultSelection, e -> onSidebarDoubleClick());

        // Context menu
        createSidebarContextMenu();

        // Separator before footer
        Label sep2 = new Label(panel, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Footer: New Branch button
        Composite footer = new Composite(panel, SWT.NONE);
        footer.setBackground(sidebarBg);
        GridData fgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        footer.setLayoutData(fgd);
        GridLayout fgl = new GridLayout(1, false);
        fgl.marginHeight = 4;
        fgl.marginWidth = HEADER_MARGIN_W;
        footer.setLayout(fgl);

        Button newBranchBtn = new Button(footer, SWT.PUSH);
        newBranchBtn.setText("+ New Branch");
        newBranchBtn.setFont(appFont);
        newBranchBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        newBranchBtn.addListener(SWT.Selection, e -> showNewBranchDialog(null));
    }

    private void onSidebarSelection() {
        TreeItem[] sel = branchTree.getSelection();
        if (sel.length == 0) return;
        TreeItem item = sel[0];
        String type = (String) item.getData("type");
        if (type == null) return; // section header

        if ("local".equals(type) || "remote".equals(type)) {
            String branch = (String) item.getData("branch");
            if (branch == null) return;
            logBranch = branch;
            loadCommits();
        } else if ("stash".equals(type)) {
            // Show stash diff — select working tree first
            String stashRef = (String) item.getData("ref");
            if (stashRef != null) {
                showStashDiff(stashRef);
            }
        }
    }

    private void onSidebarDoubleClick() {
        TreeItem[] sel = branchTree.getSelection();
        if (sel.length == 0) return;
        TreeItem item = sel[0];
        String type = (String) item.getData("type");
        if (type == null) return;

        if ("local".equals(type)) {
            String branch = (String) item.getData("branch");
            if (branch == null || branch.equals(currentBranch)) return;
            doCheckout(branch);
        } else if ("remote".equals(type)) {
            String remoteBranch = (String) item.getData("branch");
            if (remoteBranch == null) return;
            String localName = remoteBranch.contains("/")
                    ? remoteBranch.substring(remoteBranch.indexOf('/') + 1)
                    : remoteBranch;
            showCheckoutRemoteDialog(remoteBranch, localName);
        } else if ("stash".equals(type)) {
            String stashRef = (String) item.getData("ref");
            if (stashRef != null) {
                showStashDiff(stashRef);
            }
        }
    }

    private void showStashDiff(String stashRef) {
        // Show stash changes in embedded diff
        if (embeddedCompare == null) return;
        String diff = runGit("stash", "show", "-p", stashRef);
        embeddedCompare.setContent("", diff, "(empty)", stashRef);
        statusBarLeft.setText("Viewing stash: " + stashRef);
    }

    // ── Left: Commit log ─────────────────────────────────────────────

    private void createCommitPanel(Composite parent) {
        Composite panel = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 0;
        gl.marginWidth = 0;
        gl.verticalSpacing = 0;
        panel.setLayout(gl);

        // Panel header: [COMMITS] [spacer] [All▾] [Search...]
        Composite header = new Composite(panel, SWT.NONE);
        header.setBackground(toolbarBg);
        GridData commitHeaderGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        commitHeaderGd.heightHint = headerHeight;
        header.setLayoutData(commitHeaderGd);
        GridLayout hgl = new GridLayout(4, false);
        hgl.marginHeight = headerMarginH;
        hgl.marginWidth = HEADER_MARGIN_W;
        hgl.horizontalSpacing = HEADER_SPACING;
        header.setLayout(hgl);

        commitsLabel = new Label(header, SWT.NONE);
        commitsLabel.setText("COMMITS");
        commitsLabel.setFont(appFont);
        commitsLabel.setBackground(toolbarBg);

        Label spacer = new Label(header, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        spacer.setBackground(toolbarBg);

        // Search mode combo
        searchModeCombo = new Combo(header, SWT.DROP_DOWN | SWT.READ_ONLY);
        searchModeCombo.setItems("All", "Message", "Author", "File");
        searchModeCombo.select(0);
        searchModeCombo.setFont(appFont);
        searchModeCombo.addListener(SWT.Selection, e -> onSearchModeChanged());

        // Search text field
        filterText = new Text(header, SWT.BORDER | SWT.SINGLE | SWT.SEARCH);
        filterText.setMessage("Search commits...");
        filterText.setFont(appFont);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.addModifyListener(e -> {
            if (searchModeCombo.getSelectionIndex() == SEARCH_ALL) {
                applyFilter();
            }
        });
        filterText.addTraverseListener(e -> {
            if (e.detail == SWT.TRAVERSE_RETURN) {
                executeSearch();
            }
        });

        // Separator
        Label sep = new Label(panel, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Commit table
        commitTable = new Table(panel, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL);
        commitTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        commitTable.setHeaderVisible(true);
        commitTable.setLinesVisible(true);
        commitTable.setFont(appFont);

        TableColumn colGraph = new TableColumn(commitTable, SWT.NONE);
        colGraph.setText("");
        colGraph.setWidth(LANE_WIDTH + GRAPH_PADDING * 2);
        colGraph.setResizable(false);

        TableColumn colHash = new TableColumn(commitTable, SWT.LEFT);
        colHash.setText("Hash");
        colHash.setWidth(90);

        TableColumn colMsg = new TableColumn(commitTable, SWT.LEFT);
        colMsg.setText("Message");
        colMsg.setWidth(300);

        TableColumn colAuthor = new TableColumn(commitTable, SWT.LEFT);
        colAuthor.setText("Author");
        colAuthor.setWidth(120);

        TableColumn colDate = new TableColumn(commitTable, SWT.LEFT);
        colDate.setText("Date");
        colDate.setWidth(160);

        // Percentage-based column widths — works across Mac/Linux/Windows
        commitTable.addListener(SWT.Resize, e -> {
            int total = commitTable.getClientArea().width;
            if (total <= 0) return;
            int graphW = commitTable.getColumn(0).getWidth();
            int remaining = total - graphW;
            colHash.setWidth(remaining * COL_HASH_PCT / 100);
            colAuthor.setWidth(remaining * COL_AUTHOR_PCT / 100);
            colDate.setWidth(remaining * COL_DATE_PCT / 100);
            int msgWidth = remaining - colHash.getWidth() - colAuthor.getWidth() - colDate.getWidth();
            if (msgWidth < MIN_MSG_COL_WIDTH) msgWidth = MIN_MSG_COL_WIDTH;
            colMsg.setWidth(msgWidth);
        });

        // Owner-draw for Graph column (0) and Message column (2)
        commitTable.addListener(SWT.MeasureItem, event -> {
            if (event.index != 2) return; // column 2 = Message
            TableItem item = (TableItem) event.item;
            CommitInfo ci = (CommitInfo) item.getData();
            if (ci == null) return;
            List<String> refs = commitToRefs.get(ci.shortHash);
            if (refs == null || refs.isEmpty()) return;
            GC gc = event.gc;
            Font origFont = gc.getFont();
            gc.setFont(tagFont);
            int tagsWidth = 0;
            for (String ref : refs) {
                String label = ref.startsWith("tag:") ? ref.substring(4) : ref;
                tagsWidth += gc.textExtent(label).x + 12 + 4;
            }
            gc.setFont(origFont);
            event.width = Math.max(event.width, tagsWidth + gc.textExtent(ci.message).x + 8);
        });

        commitTable.addListener(SWT.PaintItem, event -> {
            TableItem item = (TableItem) event.item;
            CommitInfo ci = (CommitInfo) item.getData();
            if (ci == null) return;

            if (event.index == 0) {
                // ── Graph column ──
                GC gc = event.gc;
                gc.setAntialias(SWT.ON);
                gc.setLineCap(SWT.CAP_ROUND);
                gc.setLineJoin(SWT.JOIN_ROUND);
                int y = event.y;
                int rowH = event.height;
                int midY = y + rowH / 2;

                // Draw through-lines for active lanes
                for (int laneIdx : ci.activeLaneIndices) {
                    int lx = event.x + laneIdx * LANE_WIDTH + LANE_WIDTH / 2 + GRAPH_PADDING;
                    int colorIdx = ci.laneColorMap.getOrDefault(laneIdx, 0);
                    gc.setForeground(laneColors[colorIdx % laneColors.length]);
                    gc.setLineWidth(2);
                    gc.setLineStyle(SWT.LINE_SOLID);

                    if (laneIdx == ci.lane) {
                        // Commit's own lane: line from top to dot, dot to bottom
                        gc.drawLine(lx, y, lx, midY);
                        if (!ci.parentHashes.isEmpty()) {
                            gc.drawLine(lx, midY, lx, y + rowH);
                        }
                    } else if (ci.mergeLanes.contains(laneIdx)) {
                        // Merging lane: curve from lane top to commit dot
                        int toX = event.x + ci.lane * LANE_WIDTH + LANE_WIDTH / 2 + GRAPH_PADDING;
                        org.eclipse.swt.graphics.Path path = new org.eclipse.swt.graphics.Path(display);
                        path.moveTo(lx, y);
                        path.cubicTo(lx, y + rowH * 0.6f, toX, y + rowH * 0.4f, toX, midY);
                        gc.drawPath(path);
                        path.dispose();
                    } else {
                        // Regular through-line
                        gc.drawLine(lx, y, lx, y + rowH);
                    }
                }

                // New branch start: commit lane not in activeLaneIndices
                if (!ci.activeLaneIndices.contains(ci.lane) && ci.lane >= 0) {
                    int lx = event.x + ci.lane * LANE_WIDTH + LANE_WIDTH / 2 + GRAPH_PADDING;
                    int colorIdx = ci.laneColorMap.getOrDefault(ci.lane, 0);
                    gc.setForeground(laneColors[colorIdx % laneColors.length]);
                    gc.setLineWidth(2);
                    gc.setLineStyle(SWT.LINE_SOLID);
                    if (!ci.parentHashes.isEmpty()) {
                        gc.drawLine(lx, midY, lx, y + rowH);
                    }
                }

                // Fork curves (merge parents opening new lanes)
                for (int forkLane : ci.forkLanes) {
                    int fromX = event.x + ci.lane * LANE_WIDTH + LANE_WIDTH / 2 + GRAPH_PADDING;
                    int toX = event.x + forkLane * LANE_WIDTH + LANE_WIDTH / 2 + GRAPH_PADDING;
                    int colorIdx = ci.laneColorMap.getOrDefault(forkLane, 0);
                    gc.setForeground(laneColors[colorIdx % laneColors.length]);
                    gc.setLineWidth(2);
                    gc.setLineStyle(SWT.LINE_SOLID);
                    org.eclipse.swt.graphics.Path path = new org.eclipse.swt.graphics.Path(display);
                    path.moveTo(fromX, midY);
                    path.cubicTo(fromX, midY + rowH * 0.6f, toX, midY + rowH * 0.4f, toX, y + rowH);
                    gc.drawPath(path);
                    path.dispose();
                }

                // Draw commit dot
                if (ci.lane >= 0) {
                    int cx = event.x + ci.lane * LANE_WIDTH + LANE_WIDTH / 2 + GRAPH_PADDING;
                    int dotR = DOT_RADIUS;
                    int colorIdx = ci.laneColorMap.getOrDefault(ci.lane, 0);
                    Color dotColor = laneColors[colorIdx % laneColors.length];

                    if (ci.isWorkingTree) {
                        gc.setForeground(dotColor);
                        gc.setLineWidth(2);
                        gc.setLineStyle(SWT.LINE_DOT);
                        gc.drawOval(cx - dotR, midY - dotR, dotR * 2, dotR * 2);
                        gc.setLineStyle(SWT.LINE_SOLID);
                    } else {
                        gc.setBackground(dotColor);
                        gc.fillOval(cx - dotR, midY - dotR, dotR * 2, dotR * 2);
                        gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
                        gc.setLineWidth(1);
                        gc.drawOval(cx - dotR, midY - dotR, dotR * 2, dotR * 2);
                    }
                }

            } else if (event.index == 2) {
                // ── Message column with branch tags ──
                GC gc = event.gc;
                int x = event.x + 4;
                int y = event.y;
                int rowH = event.height;

                List<String> refs = commitToRefs.get(ci.shortHash);
                if (refs != null && !refs.isEmpty()) {
                    Font origFont = gc.getFont();
                    gc.setFont(tagFont);

                    boolean isSelected = (event.detail & SWT.SELECTED) != 0;

                    for (String ref : refs) {
                        String label;
                        Color bg, fg;
                        if ("HEAD".equals(ref)) {
                            label = "HEAD";
                            bg = tagHeadBg;
                            fg = tagHeadFg;
                        } else if (ref.startsWith("tag:")) {
                            label = ref.substring(4);
                            bg = tagTagBg;
                            fg = tagTagFg;
                        } else if (ref.contains("/")) {
                            label = ref;
                            bg = tagRemoteBg;
                            fg = tagRemoteFg;
                        } else {
                            label = ref;
                            bg = tagLocalBg;
                            fg = tagLocalFg;
                        }

                        Point tagSize = gc.textExtent(label);
                        int tagW = tagSize.x + 10;
                        int tagH = tagSize.y + 2;
                        int tagY = y + (rowH - tagH) / 2;

                        if (!isSelected) {
                            gc.setBackground(bg);
                            gc.fillRoundRectangle(x, tagY, tagW, tagH, 4, 4);
                        }
                        gc.setForeground(isSelected ? display.getSystemColor(SWT.COLOR_WHITE) : fg);
                        gc.drawText(label, x + 5, tagY + 1, true);
                        x += tagW + 3;
                    }

                    gc.setFont(origFont);
                }

                // Draw message text after tags
                gc.setForeground(item.getForeground(2));
                gc.setFont(appFont);
                gc.drawText(ci.message, x, y + (rowH - gc.textExtent(ci.message).y) / 2, true);
            }
        });

        commitTable.addListener(SWT.EraseItem, event -> {
            if (event.index == 0 || event.index == 2) {
                // Prevent default text drawing — we handle it in PaintItem
                event.detail &= ~SWT.FOREGROUND;
            }
        });

        // Selection listener — update files + detail
        commitTable.addListener(SWT.Selection, e -> onCommitSelected());

        // Commit table context menu
        createCommitContextMenu();
    }

    private void createCommitContextMenu() {
        Menu menu = new Menu(commitTable);
        commitTable.setMenu(menu);

        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();

            int[] indices = commitTable.getSelectionIndices();
            if (indices.length == 0) return;

            // First selected commit (for single-select operations)
            int idx = indices[0];
            if (idx < 0 || idx >= filteredCommits.size()) return;
            CommitInfo ci = filteredCommits.get(idx);

            if (ci.isWorkingTree) return; // no context menu for Working Tree

            boolean multiSelect = indices.length > 1;

            if (multiSelect) {
                // ── Multi-select menu ──
                List<CommitInfo> consecutive = getConsecutiveSelectedCommits();

                MenuItem squash = new MenuItem(menu, SWT.PUSH);
                squash.setText("Squash " + indices.length + " Commits...");
                if (consecutive != null) {
                    squash.addListener(SWT.Selection, ev -> doSquashCommits());
                } else {
                    squash.setEnabled(false);
                    // Show hint why squash is disabled
                    MenuItem hint = new MenuItem(menu, SWT.PUSH);
                    hint.setText("  (must be consecutive, non-merge commits)");
                    hint.setEnabled(false);
                }
                return;
            }

            // ── Single-select menu ──

            MenuItem newBranch = new MenuItem(menu, SWT.PUSH);
            newBranch.setText("Create Branch here...\t\u2318B");
            newBranch.addListener(SWT.Selection, ev -> showNewBranchDialog(ci.fullHash));

            MenuItem newTag = new MenuItem(menu, SWT.PUSH);
            newTag.setText("Create Tag here...");
            newTag.addListener(SWT.Selection, ev -> showCreateTagDialog(ci.fullHash));

            MenuItem checkoutCommit = new MenuItem(menu, SWT.PUSH);
            checkoutCommit.setText("Checkout this Commit");
            checkoutCommit.addListener(SWT.Selection, ev -> {
                String result = runGitWithExitCode("checkout", ci.fullHash);
                if (result != null) {
                    currentBranch = "(detached)";
                    statusBarLeft.setText("Checked out: " + ci.shortHash + " (detached HEAD)");
                    loadBranches();
                    loadCommits();
                } else {
                    statusBarLeft.setText("Checkout failed");
                }
            });

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem cherryPick = new MenuItem(menu, SWT.PUSH);
            cherryPick.setText("Cherry-pick to " + currentBranch);
            cherryPick.addListener(SWT.Selection, ev -> {
                String result = runGitWithExitCode("cherry-pick", ci.fullHash);
                if (result != null) {
                    statusBarLeft.setText("Cherry-picked: " + ci.shortHash);
                    loadBranches();
                    loadCommits();
                } else {
                    handleConflictAfterOp("Cherry-pick");
                }
            });

            MenuItem revert = new MenuItem(menu, SWT.PUSH);
            revert.setText("Revert Commit");
            revert.addListener(SWT.Selection, ev -> {
                String result = runGitWithExitCode("revert", "--no-edit", ci.fullHash);
                if (result != null) {
                    statusBarLeft.setText("Reverted: " + ci.shortHash);
                    loadBranches();
                    loadCommits();
                } else {
                    handleConflictAfterOp("Revert");
                }
            });

            MenuItem interactiveRebase = new MenuItem(menu, SWT.PUSH);
            interactiveRebase.setText("Interactive Rebase from here...");
            interactiveRebase.addListener(SWT.Selection, ev -> showInteractiveRebaseDialog(ci));

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem resetSoft = new MenuItem(menu, SWT.PUSH);
            resetSoft.setText("Reset " + currentBranch + " to here (soft)...");
            resetSoft.addListener(SWT.Selection, ev -> {
                MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
                confirm.setText("Reset");
                confirm.setMessage("Soft reset " + currentBranch + " to " + ci.shortHash + "?\nChanges will be kept as staged.");
                if (confirm.open() != SWT.YES) return;
                String result = runGitWithExitCode("reset", "--soft", ci.fullHash);
                statusBarLeft.setText(result != null ? "Reset to " + ci.shortHash : "Reset failed");
                loadBranches();
                loadCommits();
            });

            MenuItem resetMixed = new MenuItem(menu, SWT.PUSH);
            resetMixed.setText("Reset " + currentBranch + " to here (mixed)...");
            resetMixed.addListener(SWT.Selection, ev -> {
                MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
                confirm.setText("Reset");
                confirm.setMessage("Mixed reset " + currentBranch + " to " + ci.shortHash + "?\nChanges will be kept as unstaged.");
                if (confirm.open() != SWT.YES) return;
                String result = runGitWithExitCode("reset", "--mixed", ci.fullHash);
                statusBarLeft.setText(result != null ? "Reset (mixed) to " + ci.shortHash : "Reset failed");
                loadBranches();
                loadCommits();
            });

            MenuItem resetHard = new MenuItem(menu, SWT.PUSH);
            resetHard.setText("Reset " + currentBranch + " to here (hard)...");
            resetHard.addListener(SWT.Selection, ev -> {
                MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
                confirm.setText("Reset (Hard)");
                confirm.setMessage("HARD reset " + currentBranch + " to " + ci.shortHash + "?\n\nWARNING: All uncommitted changes will be LOST!");
                if (confirm.open() != SWT.YES) return;
                String result = runGitWithExitCode("reset", "--hard", ci.fullHash);
                statusBarLeft.setText(result != null ? "Reset (hard) to " + ci.shortHash : "Reset failed");
                loadBranches();
                loadCommits();
            });

            new MenuItem(menu, SWT.SEPARATOR);

            MenuItem copyHash = new MenuItem(menu, SWT.PUSH);
            copyHash.setText("Copy Hash\t\u2318C");
            copyHash.addListener(SWT.Selection, ev -> {
                Clipboard cb = new Clipboard(display);
                cb.setContents(new Object[]{ci.fullHash}, new Transfer[]{TextTransfer.getInstance()});
                cb.dispose();
                statusBarLeft.setText("Copied: " + ci.fullHash);
            });

            MenuItem copyMsg = new MenuItem(menu, SWT.PUSH);
            copyMsg.setText("Copy Message");
            copyMsg.addListener(SWT.Selection, ev -> {
                Clipboard cb = new Clipboard(display);
                cb.setContents(new Object[]{ci.message}, new Transfer[]{TextTransfer.getInstance()});
                cb.dispose();
                statusBarLeft.setText("Copied message");
            });

            MenuItem editMsg = new MenuItem(menu, SWT.PUSH);
            editMsg.setText("Edit Commit Message...");
            editMsg.addListener(SWT.Selection, ev -> doEditCommitMessage(ci));
        });
    }

    // ── Right: Files + Detail ────────────────────────────────────────

    private void createRightPanel(Composite parent) {
        Composite panel = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 0;
        gl.marginWidth = 0;
        gl.verticalSpacing = 0;
        panel.setLayout(gl);

        // Files header — 7 columns: [title] [nM] [nA] [nD] [spacer] [stageAll] [unstageAll]
        Composite header = new Composite(panel, SWT.NONE);
        header.setBackground(toolbarBg);
        GridData filesHeaderGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        filesHeaderGd.heightHint = headerHeight;
        header.setLayoutData(filesHeaderGd);
        GridLayout hgl = new GridLayout(7, false);
        hgl.marginHeight = headerMarginH;
        hgl.marginWidth = HEADER_MARGIN_W;
        hgl.horizontalSpacing = TOOLBAR_SPACING;
        header.setLayout(hgl);

        filesHeaderTitle = new Label(header, SWT.NONE);
        filesHeaderTitle.setText("CHANGED FILES");
        filesHeaderTitle.setFont(appFont);
        filesHeaderTitle.setBackground(toolbarBg);

        // Per-status counts, e.g. "● 5 modified  ● 2 added  ● 1 deleted"
        summaryModified = new Label(header, SWT.NONE);
        summaryModified.setFont(appFont);
        summaryModified.setBackground(toolbarBg);
        summaryModified.setForeground(modifiedFg);

        summaryAdded = new Label(header, SWT.NONE);
        summaryAdded.setFont(appFont);
        summaryAdded.setBackground(toolbarBg);
        summaryAdded.setForeground(additionsFg);

        summaryDeleted = new Label(header, SWT.NONE);
        summaryDeleted.setFont(appFont);
        summaryDeleted.setBackground(toolbarBg);
        summaryDeleted.setForeground(deletionsFg);

        Label spacer = new Label(header, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        spacer.setBackground(toolbarBg);

        stageAllBtn = new Button(header, SWT.PUSH);
        stageAllBtn.setText("Stage All");
        stageAllBtn.setFont(appFont);
        stageAllBtn.setVisible(false);
        GridData saGd = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        saGd.exclude = true;
        stageAllBtn.setLayoutData(saGd);
        stageAllBtn.addListener(SWT.Selection, e -> stageAll());

        unstageAllBtn = new Button(header, SWT.PUSH);
        unstageAllBtn.setText("Unstage All");
        unstageAllBtn.setFont(appFont);
        unstageAllBtn.setVisible(false);
        GridData uaGd = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        uaGd.exclude = true;
        unstageAllBtn.setLayoutData(uaGd);
        unstageAllBtn.addListener(SWT.Selection, e -> unstageAll());

        // Separator
        Label sep = new Label(panel, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Files table (SWT.CHECK adds checkbox column)
        filesTable = new Table(panel, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.CHECK);
        filesTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        filesTable.setHeaderVisible(true);
        filesTable.setLinesVisible(true);
        filesTable.setFont(appFont);

        TableColumn colStatus = new TableColumn(filesTable, SWT.CENTER);
        colStatus.setText("");
        colStatus.setWidth(COL_STATUS_WIDTH);

        TableColumn colName = new TableColumn(filesTable, SWT.LEFT);
        colName.setText("Name");
        colName.setWidth(180);

        TableColumn colDir = new TableColumn(filesTable, SWT.LEFT);
        colDir.setText("Directory");
        colDir.setWidth(200);

        TableColumn colModified = new TableColumn(filesTable, SWT.LEFT);
        colModified.setText("Modified");
        colModified.setWidth(COL_MODIFIED_WIDTH);

        TableColumn colAdd = new TableColumn(filesTable, SWT.RIGHT);
        colAdd.setText("+");
        colAdd.setWidth(COL_ADD_WIDTH);

        TableColumn colDel = new TableColumn(filesTable, SWT.RIGHT);
        colDel.setText("-");
        colDel.setWidth(COL_DEL_WIDTH);

        // Click column header to sort (persisted)
        filesColStatus = colStatus;
        filesColName = colName;
        filesColModified = colModified;
        colStatus.addListener(SWT.Selection, e -> onFileSortColumn("status"));
        colName.addListener(SWT.Selection, e -> onFileSortColumn("name"));
        colModified.addListener(SWT.Selection, e -> onFileSortColumn("modified"));
        updateFileSortIndicator();

        // Hover tooltip: full status word + path (the M/A/D letters are terse)
        filesTable.addListener(SWT.MouseMove, e -> filesTable.setToolTipText(null));
        filesTable.addListener(SWT.MouseHover, e -> {
            TableItem it = filesTable.getItem(new Point(e.x, e.y));
            String tip = null;
            if (it != null && it.getData() instanceof FileChange) {
                FileChange fc = (FileChange) it.getData();
                tip = statusTooltip(fc) + "  —  " + fc.path;
            }
            filesTable.setToolTipText(tip);
        });

        // Auto-resize columns
        filesTable.addListener(SWT.Resize, e -> {
            int total = filesTable.getClientArea().width;
            int used = colStatus.getWidth() + colModified.getWidth() + colAdd.getWidth() + colDel.getWidth();
            int remaining = total - used;
            int nameW = remaining * COL_NAME_PCT / 100;
            int dirW = remaining - nameW;
            if (nameW < MIN_NAME_COL_WIDTH) nameW = MIN_NAME_COL_WIDTH;
            if (dirW < MIN_DIR_COL_WIDTH) dirW = MIN_DIR_COL_WIDTH;
            colName.setWidth(nameW);
            colDir.setWidth(dirW);
        });

        // Single-click to preview in embedded diff panel; checkbox click to stage/unstage
        filesTable.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.CHECK) {
                // Checkbox toggled
                TableItem item = (TableItem) e.item;
                Object data = item.getData();
                if (data instanceof FileChange) {
                    FileChange fc = (FileChange) data;
                    if (isWorkingTreeSelected) {
                        toggleStageFile(fc);
                    } else {
                        // Not working tree — revert checkbox (no staging)
                        item.setChecked(!item.getChecked());
                    }
                } else {
                    // Section header — revert checkbox
                    item.setChecked(false);
                }
                return;
            }
            previewSelectedFile();
        });

        // Double-click: open file (section headers are handled on single click)
        filesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDoubleClick(MouseEvent e) {
                TableItem hit = filesTable.getItem(new Point(e.x, e.y));
                if (hit != null && hit.getData() instanceof String) return; // section header
                openSelectedFile();
            }
        });

        // Single-click on section header → toggle collapse.
        // Hit-test the actual click position — at MouseDown time getSelectionIndex()
        // still returns the previous selection, which made rows shift unexpectedly.
        filesTable.addListener(SWT.MouseDown, e -> {
            if (e.count != 1) return;
            TableItem hit = filesTable.getItem(new Point(e.x, e.y));
            if (hit == null) return;
            Object data = hit.getData();
            boolean toggled = false;
            if ("section-conflicted".equals(data)) {
                conflictedCollapsed = !conflictedCollapsed;
                toggled = true;
            } else if ("section-staged".equals(data)) {
                stagedCollapsed = !stagedCollapsed;
                toggled = true;
            } else if ("section-unstaged".equals(data)) {
                unstagedCollapsed = !unstagedCollapsed;
                toggled = true;
            }
            if (toggled) {
                // Rebuild after the native click finishes processing
                display.asyncExec(() -> {
                    if (!filesTable.isDisposed()) populateFilesTable(false);
                });
            }
        });

        // Context menu for files table
        createFilesContextMenu();

        // Separator before detail/commit area
        Label sep2 = new Label(panel, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Detail area (read-only, for normal commits)
        createDetailArea(panel);

        // Commit area (for Working Tree mode)
        createCommitArea(panel);

        // Conflict area (for merge/cherry-pick/revert conflicts)
        createConflictArea(panel);
    }

    private void createDetailArea(Composite parent) {
        detailComposite = new Composite(parent, SWT.NONE);
        detailComposite.setBackground(detailBg);
        GridData dgd = new GridData(SWT.FILL, SWT.END, true, false);
        dgd.heightHint = DETAIL_HEIGHT;
        detailComposite.setLayoutData(dgd);

        GridLayout dgl = new GridLayout(1, false);
        dgl.marginHeight = DETAIL_MARGIN_H;
        dgl.marginWidth = DETAIL_MARGIN_W;
        dgl.verticalSpacing = DETAIL_SPACING;
        detailComposite.setLayout(dgl);

        // Message (scrollable text box)
        detailMsg = new Text(detailComposite, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        detailMsg.setBackground(detailBg);
        detailMsg.setFont(appFont);
        detailMsg.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        detailMsg.setText("");

        // Meta line
        detailMeta = new Label(detailComposite, SWT.NONE);
        detailMeta.setBackground(detailBg);
        detailMeta.setFont(appFont);
        detailMeta.setForeground(metaFg);
        detailMeta.setLayoutData(new GridData(SWT.FILL, SWT.END, true, false));
        detailMeta.setText("");
    }

    private void createCommitArea(Composite parent) {
        commitArea = new Composite(parent, SWT.NONE);
        commitArea.setBackground(detailBg);
        GridData cgd = new GridData(SWT.FILL, SWT.END, true, false);
        cgd.heightHint = DETAIL_HEIGHT;
        cgd.exclude = true;  // hidden by default
        commitArea.setLayoutData(cgd);
        commitArea.setVisible(false);

        GridLayout cgl = new GridLayout(1, false);
        cgl.marginHeight = DETAIL_MARGIN_H;
        cgl.marginWidth = DETAIL_MARGIN_W;
        cgl.verticalSpacing = DETAIL_SPACING;
        commitArea.setLayout(cgl);

        // "Commit Message" label
        Label msgLabel = new Label(commitArea, SWT.NONE);
        msgLabel.setText("Commit Message");
        msgLabel.setFont(appFont);
        msgLabel.setBackground(detailBg);
        msgLabel.setForeground(metaFg);

        // Editable commit message text
        commitMsgText = new Text(commitArea, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        commitMsgText.setFont(appFont);
        commitMsgText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        commitMsgText.setMessage("Summary (required)");
        // Update commit button enabled state as user types
        commitMsgText.addModifyListener(e -> updateCommitBtnState());

        // Bottom row: [Amend] [meta spacer] [Commit]
        Composite bottomRow = new Composite(commitArea, SWT.NONE);
        bottomRow.setBackground(detailBg);
        bottomRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout brgl = new GridLayout(3, false);
        brgl.marginHeight = 0;
        brgl.marginWidth = 0;
        brgl.horizontalSpacing = TOOLBAR_SPACING;
        bottomRow.setLayout(brgl);

        amendCheck = new Button(bottomRow, SWT.CHECK);
        amendCheck.setText("Amend");
        amendCheck.setFont(appFont);
        amendCheck.setBackground(detailBg);
        amendCheck.addListener(SWT.Selection, e -> onAmendToggled());

        commitMeta = new Label(bottomRow, SWT.NONE);
        commitMeta.setFont(appFont);
        commitMeta.setBackground(detailBg);
        commitMeta.setForeground(metaFg);
        commitMeta.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        commitBtn = new Button(bottomRow, SWT.PUSH);
        commitBtn.setText("  Commit  ");
        commitBtn.setFont(appFont);
        commitBtn.addListener(SWT.Selection, e -> doCommit());
    }

    private void createConflictArea(Composite parent) {
        conflictArea = new Composite(parent, SWT.NONE);
        conflictArea.setBackground(conflictBg);
        GridData cagd = new GridData(SWT.FILL, SWT.END, true, false);
        cagd.heightHint = DETAIL_HEIGHT;
        cagd.exclude = true;
        conflictArea.setLayoutData(cagd);
        conflictArea.setVisible(false);

        GridLayout cagl = new GridLayout(1, false);
        cagl.marginHeight = DETAIL_MARGIN_H;
        cagl.marginWidth = DETAIL_MARGIN_W;
        cagl.verticalSpacing = DETAIL_SPACING;
        conflictArea.setLayout(cagl);

        // Conflict description label
        conflictLabel = new Label(conflictArea, SWT.WRAP);
        conflictLabel.setFont(appFont);
        conflictLabel.setBackground(conflictBg);
        conflictLabel.setForeground(conflictFg);
        conflictLabel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        conflictLabel.setText("");

        // Bottom row: [Abort] ... spacer ... [Continue]
        Composite bottomRow = new Composite(conflictArea, SWT.NONE);
        bottomRow.setBackground(conflictBg);
        bottomRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout brgl = new GridLayout(3, false);
        brgl.marginHeight = 0;
        brgl.marginWidth = 0;
        brgl.horizontalSpacing = TOOLBAR_SPACING;
        bottomRow.setLayout(brgl);

        abortBtn = new Button(bottomRow, SWT.PUSH);
        abortBtn.setText("Abort");
        abortBtn.setFont(appFont);
        abortBtn.addListener(SWT.Selection, e -> doAbortConflict());

        Label spacer = new Label(bottomRow, SWT.NONE);
        spacer.setBackground(conflictBg);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        continueBtn = new Button(bottomRow, SWT.PUSH);
        continueBtn.setText("Continue");
        continueBtn.setFont(appFont);
        continueBtn.setEnabled(false);
        continueBtn.addListener(SWT.Selection, e -> doContinueConflict());
    }

    private void showDetailArea(String mode) {
        GridData detailGd = (GridData) detailComposite.getLayoutData();
        GridData commitGd = (GridData) commitArea.getLayoutData();
        GridData conflictGd = (GridData) conflictArea.getLayoutData();

        boolean isDetail = "detail".equals(mode);
        boolean isCommit = "commit".equals(mode);
        boolean isConflict = "conflict".equals(mode);

        detailComposite.setVisible(isDetail);
        detailGd.exclude = !isDetail;
        commitArea.setVisible(isCommit);
        commitGd.exclude = !isCommit;
        conflictArea.setVisible(isConflict);
        conflictGd.exclude = !isConflict;

        // Show/hide stage buttons for commit and conflict modes
        boolean showStageButtons = isCommit || isConflict;
        GridData saGd = (GridData) stageAllBtn.getLayoutData();
        GridData uaGd = (GridData) unstageAllBtn.getLayoutData();
        stageAllBtn.setVisible(showStageButtons);
        saGd.exclude = !showStageButtons;
        unstageAllBtn.setVisible(showStageButtons);
        uaGd.exclude = !showStageButtons;
        stageAllBtn.getParent().layout(true, true);

        detailComposite.getParent().layout(true, true);
    }

    // ── Status bar ───────────────────────────────────────────────────

    private void createStatusBar(Shell shell) {
        Label sep = new Label(shell, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite bar = new Composite(shell, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout bgl = new GridLayout(2, false);
        bgl.marginHeight = STATUSBAR_MARGIN_H;
        bgl.marginWidth = STATUSBAR_MARGIN_W;
        bar.setLayout(bgl);

        statusBarLeft = new Label(bar, SWT.NONE);
        statusBarLeft.setText("Double-click branch to checkout  \u00B7  Right-click for operations  \u00B7  Space to toggle stage");
        statusBarLeft.setFont(appFont);
        statusBarLeft.setForeground(metaFg);
        statusBarLeft.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        statusBarRight = new Label(bar, SWT.RIGHT);
        statusBarRight.setText("");
        statusBarRight.setFont(appFont);
        statusBarRight.setForeground(metaFg);
        statusBarRight.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    }

    // ── Keyboard shortcuts ───────────────────────────────────────────

    private void createKeyboardShortcuts() {
        display.addFilter(SWT.KeyDown, event -> {
            if (shell.isDisposed()) return;

            // Cmd+Enter in commitMsgText → commit
            if (commitMsgText != null && commitMsgText.isFocusControl()
                    && (event.stateMask & SWT.MOD1) != 0
                    && (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)) {
                doCommit();
                event.doit = false;
                return;
            }

            // Space on filesTable in Working Tree mode → toggle stage (multi-select)
            if (filesTable.isFocusControl() && isWorkingTreeSelected
                    && event.keyCode == ' ') {
                TableItem[] sel = filesTable.getSelection();
                if (sel.length > 0) {
                    List<FileChange> files = new ArrayList<>();
                    for (TableItem ti : sel) {
                        Object d = ti.getData();
                        if (d instanceof FileChange) files.add((FileChange) d);
                    }
                    if (!files.isEmpty()) {
                        toggleStageFiles(files);
                        event.doit = false;
                        return;
                    }
                }
            }

            // Delete/Backspace on filesTable in Working Tree mode → discard (multi-select)
            if (filesTable.isFocusControl() && isWorkingTreeSelected
                    && (event.keyCode == SWT.DEL || event.keyCode == SWT.BS)) {
                TableItem[] sel = filesTable.getSelection();
                if (sel.length > 0) {
                    List<FileChange> files = new ArrayList<>();
                    for (TableItem ti : sel) {
                        Object d = ti.getData();
                        if (d instanceof FileChange) files.add((FileChange) d);
                    }
                    if (!files.isEmpty()) {
                        discardFiles(files);
                        event.doit = false;
                        return;
                    }
                }
            }

            // Enter on filesTable → open standalone FileCompare
            if (filesTable.isFocusControl() &&
                    (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)) {
                openSelectedFile();
                event.doit = false;
                return;
            }

            // Cmd+B → new branch dialog
            if ((event.stateMask & SWT.MOD1) != 0 && event.keyCode == 'b') {
                int cIdx = commitTable.getSelectionIndex();
                String startPoint = null;
                if (cIdx >= 0 && cIdx < filteredCommits.size()) {
                    CommitInfo ci = filteredCommits.get(cIdx);
                    if (!ci.isWorkingTree) startPoint = ci.fullHash;
                }
                showNewBranchDialog(startPoint);
                event.doit = false;
                return;
            }

            // Cmd+C on commitTable → copy hash
            if (commitTable.isFocusControl() && (event.stateMask & SWT.MOD1) != 0
                    && event.keyCode == 'c') {
                int cIdx = commitTable.getSelectionIndex();
                if (cIdx >= 0 && cIdx < filteredCommits.size()) {
                    CommitInfo ci = filteredCommits.get(cIdx);
                    if (!ci.isWorkingTree) {
                        Clipboard cb = new Clipboard(display);
                        cb.setContents(new Object[]{ci.fullHash}, new Transfer[]{TextTransfer.getInstance()});
                        cb.dispose();
                        statusBarLeft.setText("Copied: " + ci.fullHash);
                        event.doit = false;
                        return;
                    }
                }
            }

            // Cmd+Up / Cmd+Down → navigate commits
            if ((event.stateMask & SWT.MOD1) != 0) {
                if (event.keyCode == SWT.ARROW_UP) {
                    navigateCommit(-1);
                    event.doit = false;
                } else if (event.keyCode == SWT.ARROW_DOWN) {
                    navigateCommit(1);
                    event.doit = false;
                }
            }

            // Enter on commitTable → focus files table
            if (commitTable.isFocusControl() &&
                    (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)) {
                filesTable.setFocus();
                if (filesTable.getItemCount() > 0) {
                    filesTable.setSelection(0);
                }
                event.doit = false;
            }

            // F2 on sidebar → rename branch
            if (branchTree.isFocusControl() && event.keyCode == SWT.F2) {
                TreeItem[] sel = branchTree.getSelection();
                if (sel.length > 0 && "local".equals(sel[0].getData("type"))) {
                    showRenameBranchDialog((String) sel[0].getData("branch"));
                    event.doit = false;
                }
            }

            // Delete on sidebar → delete branch
            if (branchTree.isFocusControl()
                    && (event.keyCode == SWT.DEL || event.keyCode == SWT.BS)) {
                TreeItem[] sel = branchTree.getSelection();
                if (sel.length > 0 && "local".equals(sel[0].getData("type"))) {
                    showDeleteBranchDialog((String) sel[0].getData("branch"));
                    event.doit = false;
                }
            }

            // Enter on sidebar → checkout branch
            if (branchTree.isFocusControl()
                    && (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)) {
                TreeItem[] sel = branchTree.getSelection();
                if (sel.length > 0 && "local".equals(sel[0].getData("type"))) {
                    String branch = (String) sel[0].getData("branch");
                    if (!branch.equals(currentBranch)) doCheckout(branch);
                    event.doit = false;
                }
            }
        });
    }

    private void navigateCommit(int delta) {
        int idx = commitTable.getSelectionIndex();
        int newIdx = idx + delta;
        if (newIdx >= 0 && newIdx < commitTable.getItemCount()) {
            commitTable.setSelection(newIdx);
            commitTable.showSelection();
            onCommitSelected();
        }
    }

    // ── Git operations ───────────────────────────────────────────────

    private String runGit(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            Collections.addAll(cmd, args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(false);
            Process p = pb.start();
            // Drain stderr on its own thread so git warnings (e.g. CRLF
            // notices) never get interleaved into the stdout we parse.
            Thread stderrDrain = new Thread(() -> {
                try { p.getErrorStream().readAllBytes(); } catch (Exception ignored) {}
            });
            stderrDrain.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            stderrDrain.join();
            return output;
        } catch (Exception e) {
            return "";
        }
    }

    private void loadBranches() {
        shell.setText("GitLog \u2014 " + repoPath);
        // Get current branch
        String head = runGit("rev-parse", "--abbrev-ref", "HEAD").trim();
        currentBranch = head;
        logBranch = head;

        // Clear sidebar tree
        branchTree.removeAll();
        branchToCommit.clear();
        commitToRefs.clear();

        // ── Local branches ──
        TreeItem localSection = new TreeItem(branchTree, SWT.NONE);
        localSection.setText("Local");
        localSection.setData("type", "section");

        String localOutput = runGit("branch", "--format=%(refname:short)\t%(objectname:short)\t%(upstream:trackshort)");
        for (String line : localOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", 3);
            String name = parts[0].trim();
            String hash = parts.length > 1 ? parts[1].trim() : "";
            String tracking = parts.length > 2 ? parts[2].trim() : "";

            TreeItem item = new TreeItem(localSection, SWT.NONE);
            String display = name;
            if (!tracking.isEmpty()) display += "  " + tracking;
            item.setText(display);
            item.setData("type", "local");
            item.setData("branch", name);
            item.setData("hash", hash);

            if (name.equals(currentBranch)) {
                item.setFont(boldFont);
            }

            if (!hash.isEmpty()) {
                branchToCommit.put(name, hash);
                commitToRefs.computeIfAbsent(hash, k -> new ArrayList<>()).add(name);
            }
        }
        localSection.setExpanded(true);

        // ── Remote branches ──
        // Group by remote name
        String remoteOutput = runGit("branch", "-r", "--format=%(refname:short)\t%(objectname:short)");
        Map<String, List<String[]>> remoteGroups = new LinkedHashMap<>();
        for (String line : remoteOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            if (line.contains("->")) continue; // skip HEAD pointers
            String[] parts = line.split("\t", 2);
            String name = parts[0].trim();
            String hash = parts.length > 1 ? parts[1].trim() : "";
            String remote = name.contains("/") ? name.substring(0, name.indexOf('/')) : "origin";
            remoteGroups.computeIfAbsent(remote, k -> new ArrayList<>()).add(new String[]{name, hash});
        }

        for (Map.Entry<String, List<String[]>> entry : remoteGroups.entrySet()) {
            TreeItem remoteSection = new TreeItem(branchTree, SWT.NONE);
            remoteSection.setText(entry.getKey());
            remoteSection.setData("type", "section");

            for (String[] ref : entry.getValue()) {
                TreeItem item = new TreeItem(remoteSection, SWT.NONE);
                item.setText(ref[0]);
                item.setData("type", "remote");
                item.setData("branch", ref[0]);
                item.setData("hash", ref[1]);

                if (!ref[1].isEmpty()) {
                    branchToCommit.put(ref[0], ref[1]);
                    commitToRefs.computeIfAbsent(ref[1], k -> new ArrayList<>()).add(ref[0]);
                }
            }
            remoteSection.setExpanded(true);
        }

        // ── Tags ──
        TreeItem tagsSection = new TreeItem(branchTree, SWT.NONE);
        tagsSection.setData("type", "section");

        String tagsOutput = runGit("tag", "--sort=-creatordate", "--format=%(refname:short)\t%(objectname:short)");
        int tagCount = 0;
        for (String line : tagsOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", 2);
            String name = parts[0].trim();
            String hash = parts.length > 1 ? parts[1].trim() : "";
            tagCount++;

            TreeItem item = new TreeItem(tagsSection, SWT.NONE);
            item.setText(name);
            item.setData("type", "tag");
            item.setData("tag", name);
            item.setData("hash", hash);

            if (!hash.isEmpty()) {
                branchToCommit.put("tag:" + name, hash);
                commitToRefs.computeIfAbsent(hash, k -> new ArrayList<>()).add("tag:" + name);
            }
        }
        tagsSection.setText("Tags" + (tagCount > 0 ? " (" + tagCount + ")" : ""));
        // Tags collapsed by default
        tagsSection.setExpanded(false);

        // ── Stashes ──
        TreeItem stashSection = new TreeItem(branchTree, SWT.NONE);
        stashSection.setData("type", "section");

        String stashOutput = runGit("stash", "list", "--format=%gd\t%gs");
        int stashCount = 0;
        for (String line : stashOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", 2);
            String ref = parts[0].trim();
            String msg = parts.length > 1 ? parts[1].trim() : ref;
            stashCount++;

            TreeItem item = new TreeItem(stashSection, SWT.NONE);
            item.setText(ref + ": " + msg);
            item.setData("type", "stash");
            item.setData("ref", ref);
        }
        stashSection.setText("Stashes" + (stashCount > 0 ? " (" + stashCount + ")" : ""));
        stashSection.setExpanded(stashCount > 0);

        // Also add HEAD to commitToRefs
        String headHash = runGit("rev-parse", "--short", "HEAD").trim();
        if (!headHash.isEmpty()) {
            commitToRefs.computeIfAbsent(headHash, k -> new ArrayList<>()).add(0, "HEAD");
        }

        // Sync auto-refresh baseline so the next poll doesn't see a phantom change
        lastRefsFingerprint = computeRefsFingerprint();
    }

    private String computeRefsFingerprint() {
        return runGit("for-each-ref", "--format=%(refname)%00%(objectname)")
                + "\u0001" + runGit("rev-parse", "HEAD")
                + "\u0001" + runGit("stash", "list", "--format=%gd%x00%gs");
    }

    private void loadCommits() {
        allCommits.clear();
        activeSearchQuery = "";

        // Insert "Working Tree" virtual entry at the top
        CommitInfo wt = new CommitInfo();
        wt.isWorkingTree = true;
        wt.fullHash = "";
        wt.shortHash = "\u2022\u2022\u2022";
        wt.author = "";
        wt.date = "";
        wt.message = "Working Tree";
        wt.parentHashes = new ArrayList<>();
        allCommits.add(wt);

        String branch = logBranch.isEmpty() ? (currentBranch.isEmpty() ? "HEAD" : currentBranch) : logBranch;
        String output = runGit("log", branch, "--format=%H%x00%h%x00%an%x00%ai%x00%s%x00%P");
        parseCommitOutput(output);
        applyFilter();
    }

    private void parseCommitOutput(String output) {
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\0", 6);
            if (parts.length < 5) continue;
            CommitInfo ci = new CommitInfo();
            ci.fullHash = parts[0].trim();
            ci.shortHash = parts[1].trim();
            ci.author = parts[2].trim();
            ci.date = formatDate(parts[3].trim());
            ci.message = parts[4].trim();
            String parentsStr = parts.length > 5 ? parts[5].trim() : "";
            ci.parentHashes = parentsStr.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(parentsStr.split("\\s+")));
            allCommits.add(ci);
        }
    }

    private String formatDate(String isoDate) {
        // Input: 2026-02-08 15:32:10 +0700 → Output: 2026-02-08 15:32
        if (isoDate.length() >= 16) {
            return isoDate.substring(0, 16);
        }
        return isoDate;
    }

    private void applyFilter() {
        String query = filterText != null ? filterText.getText().trim().toLowerCase() : "";
        filteredCommits.clear();
        for (CommitInfo ci : allCommits) {
            if (query.isEmpty() ||
                    ci.message.toLowerCase().contains(query) ||
                    ci.author.toLowerCase().contains(query) ||
                    ci.shortHash.toLowerCase().contains(query)) {
                filteredCommits.add(ci);
            }
        }
        populateCommitTable();
    }

    private void executeSearch() {
        int mode = searchModeCombo.getSelectionIndex();
        if (mode == SEARCH_ALL) {
            applyFilter();
            return;
        }

        String query = filterText.getText().trim();
        if (query.isEmpty()) {
            activeSearchQuery = "";
            loadCommits();
            return;
        }

        String branch = logBranch.isEmpty() ? (currentBranch.isEmpty() ? "HEAD" : currentBranch) : logBranch;
        String output;

        switch (mode) {
            case SEARCH_MESSAGE:
                output = runGit("log", branch, "--grep=" + query, "-i", "--format=%H%x00%h%x00%an%x00%ai%x00%s%x00%P");
                break;
            case SEARCH_AUTHOR:
                output = runGit("log", branch, "--author=" + query, "-i", "--format=%H%x00%h%x00%an%x00%ai%x00%s%x00%P");
                break;
            case SEARCH_FILE:
                output = runGit("log", branch, "--format=%H%x00%h%x00%an%x00%ai%x00%s%x00%P", "--", query);
                break;
            default:
                return;
        }

        activeSearchQuery = query;
        allCommits.clear();
        parseCommitOutput(output);
        filteredCommits.clear();
        filteredCommits.addAll(allCommits);
        populateCommitTable();
    }

    private void onSearchModeChanged() {
        int mode = searchModeCombo.getSelectionIndex();
        switch (mode) {
            case SEARCH_ALL:
                filterText.setMessage("Search commits...");
                break;
            case SEARCH_MESSAGE:
                filterText.setMessage("Search message... (Enter)");
                break;
            case SEARCH_AUTHOR:
                filterText.setMessage("Search author... (Enter)");
                break;
            case SEARCH_FILE:
                filterText.setMessage("Search file... (Enter)");
                break;
        }

        if (mode == SEARCH_ALL) {
            activeSearchQuery = "";
            loadCommits();
        }
    }

    private void computeGraphLanes() {
        // Build hash → row index map for quick lookup
        Map<String, Integer> hashToRow = new HashMap<>();
        for (int i = 0; i < filteredCommits.size(); i++) {
            CommitInfo ci = filteredCommits.get(i);
            if (!ci.fullHash.isEmpty()) {
                hashToRow.put(ci.fullHash, i);
            }
        }

        // Connect Working Tree to HEAD (first real commit)
        if (!filteredCommits.isEmpty() && filteredCommits.get(0).isWorkingTree
                && filteredCommits.size() > 1) {
            CommitInfo wt = filteredCommits.get(0);
            wt.parentHashes = new ArrayList<>();
            wt.parentHashes.add(filteredCommits.get(1).fullHash);
        }

        List<String> activeLanes = new ArrayList<>();      // lane → target hash
        List<Integer> activeLaneColors = new ArrayList<>(); // lane → color palette index
        int nextColor = 0;

        for (int row = 0; row < filteredCommits.size(); row++) {
            CommitInfo ci = filteredCommits.get(row);
            ci.forkLanes = new ArrayList<>();
            ci.mergeLanes = new ArrayList<>();

            // 1. Snapshot active lanes BEFORE this row (for through-lines from above)
            ci.activeLaneIndices = new HashSet<>();
            for (int j = 0; j < activeLanes.size(); j++) {
                if (activeLanes.get(j) != null) {
                    ci.activeLaneIndices.add(j);
                }
            }

            // 2. Find which lane this commit is in
            int myLane = -1;
            if (!ci.fullHash.isEmpty()) {
                for (int j = 0; j < activeLanes.size(); j++) {
                    if (ci.fullHash.equals(activeLanes.get(j))) {
                        myLane = j;
                        break;
                    }
                }
            }

            if (myLane == -1) {
                // New lane needed — find first empty slot or append
                for (int j = 0; j < activeLanes.size(); j++) {
                    if (activeLanes.get(j) == null) {
                        myLane = j;
                        break;
                    }
                }
                if (myLane == -1) {
                    myLane = activeLanes.size();
                    activeLanes.add(null);
                    activeLaneColors.add(0);
                }
                activeLanes.set(myLane, ci.fullHash.isEmpty() ? "__wt__" : ci.fullHash);
                activeLaneColors.set(myLane, nextColor % laneColors.length);
                nextColor++;
                ci.activeLaneIndices.add(myLane);
            }
            ci.lane = myLane;

            // 3. Handle parents
            if (!ci.parentHashes.isEmpty()) {
                // First parent continues in same lane
                activeLanes.set(myLane, ci.parentHashes.get(0));

                // Additional parents: find or create lanes
                for (int p = 1; p < ci.parentHashes.size(); p++) {
                    String parentHash = ci.parentHashes.get(p);

                    // Check if already being tracked
                    boolean found = false;
                    for (int j = 0; j < activeLanes.size(); j++) {
                        if (parentHash.equals(activeLanes.get(j))) {
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        // Open new lane for this parent
                        int newLane = -1;
                        for (int j = 0; j < activeLanes.size(); j++) {
                            if (activeLanes.get(j) == null) {
                                newLane = j;
                                break;
                            }
                        }
                        if (newLane == -1) {
                            newLane = activeLanes.size();
                            activeLanes.add(null);
                            activeLaneColors.add(0);
                        }
                        activeLanes.set(newLane, parentHash);
                        activeLaneColors.set(newLane, nextColor % laneColors.length);
                        nextColor++;
                        ci.forkLanes.add(newLane);
                    }
                }
            } else {
                // Root commit: close this lane
                activeLanes.set(myLane, null);
            }

            // 4. Close converging lanes (other lanes targeting this commit)
            if (!ci.fullHash.isEmpty()) {
                for (int j = 0; j < activeLanes.size(); j++) {
                    if (j != myLane && ci.fullHash.equals(activeLanes.get(j))) {
                        ci.mergeLanes.add(j);
                        activeLanes.set(j, null);
                    }
                }
            }

            // 5. Color snapshot for this row
            ci.laneColorMap = new HashMap<>();
            Set<Integer> allLanes = new HashSet<>(ci.activeLaneIndices);
            allLanes.add(ci.lane);
            allLanes.addAll(ci.forkLanes);
            allLanes.addAll(ci.mergeLanes);
            for (int j : allLanes) {
                if (j >= 0 && j < activeLaneColors.size()) {
                    ci.laneColorMap.put(j, activeLaneColors.get(j));
                }
            }
        }
    }

    private void populateCommitTable() {
        computeGraphLanes();

        // Size graph column based on max lanes
        int maxLane = 0;
        for (CommitInfo ci : filteredCommits) {
            maxLane = Math.max(maxLane, ci.lane);
            for (int l : ci.activeLaneIndices) maxLane = Math.max(maxLane, l);
            for (int l : ci.forkLanes) maxLane = Math.max(maxLane, l);
            for (int l : ci.mergeLanes) maxLane = Math.max(maxLane, l);
        }
        int graphWidth = (maxLane + 1) * LANE_WIDTH + GRAPH_PADDING * 2;
        graphWidth = Math.max(graphWidth, LANE_WIDTH + GRAPH_PADDING * 2);
        commitTable.getColumn(0).setWidth(graphWidth);

        // Remember selected commit (by hash) so a rebuild doesn't jump to the top.
        // Read it from the old table items — filteredCommits was already rebuilt.
        String prevHash = null;
        int prevSel = commitTable.getSelectionIndex();
        if (prevSel >= 0 && prevSel < commitTable.getItemCount()) {
            Object d = commitTable.getItem(prevSel).getData();
            if (d instanceof CommitInfo) {
                CommitInfo p = (CommitInfo) d;
                prevHash = p.isWorkingTree ? "" : p.fullHash;
            }
        }

        commitTable.removeAll();
        for (CommitInfo ci : filteredCommits) {
            TableItem item = new TableItem(commitTable, SWT.NONE);
            item.setText(1, ci.shortHash);
            item.setText(2, ci.message);
            item.setText(3, ci.author);
            item.setText(4, ci.date);
            item.setForeground(1, hashFg);
            if (ci.isWorkingTree) {
                item.setBackground(workingTreeBg);
            }
            item.setData(ci);
        }
        updateStatusBar();

        // Restore previous selection by hash; fall back to first commit
        if (commitTable.getItemCount() > 0) {
            int restoreIdx = 0;
            boolean found = false;
            if (prevHash != null) {
                for (int i = 0; i < filteredCommits.size(); i++) {
                    CommitInfo ci = filteredCommits.get(i);
                    String h = ci.isWorkingTree ? "" : ci.fullHash;
                    if (h.equals(prevHash)) {
                        restoreIdx = i;
                        found = true;
                        break;
                    }
                }
            }
            commitTable.setSelection(restoreIdx);
            // Skip reloading files when the same immutable commit stays selected;
            // the Working Tree entry is mutable, so always reload it
            if (!found || filteredCommits.get(restoreIdx).isWorkingTree) {
                onCommitSelected();
            } else {
                updateStatusBar();
            }
        } else {
            filesTable.removeAll();
            if (detailMsg != null) detailMsg.setText("");
            if (detailMeta != null) detailMeta.setText("");
        }
    }

    private void onCommitSelected() {
        int[] indices = commitTable.getSelectionIndices();
        if (indices.length == 0) return;
        int idx = indices[0]; // show detail for first selected
        if (idx < 0 || idx >= filteredCommits.size()) return;
        CommitInfo ci = filteredCommits.get(idx);

        isWorkingTreeSelected = ci.isWorkingTree;

        if (ci.isWorkingTree) {
            loadWorkingTreeFiles();
            conflictOperation = detectConflictState();
            if (conflictOperation != null) {
                showDetailArea("conflict");
                updateConflictBanner();
            } else {
                showDetailArea("commit");
            }
            updateCommitMeta();
        } else {
            loadFilesForCommit(ci);
            updateDetail(ci);
            showDetailArea("detail");
        }
        updateStatusBar();
    }

    private void loadFilesForCommit(CommitInfo ci) {
        currentFiles.clear();

        // Get file status (M/A/D/R)  — --root needed for initial commit (no parent)
        String statusOutput = runGit("diff-tree", "-r", "--root", "--no-commit-id", "--name-status", ci.fullHash);
        // Get line counts
        String numstatOutput = runGit("diff-tree", "-r", "--root", "--no-commit-id", "--numstat", ci.fullHash);

        // Parse numstat into a map: path → [added, deleted]
        Map<String, int[]> numstatMap = new HashMap<>();
        for (String line : numstatOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            int added = parseIntSafe(parts[0]);
            int deleted = parseIntSafe(parts[1]);
            String path = parts[2].trim();
            // Handle renames: old -> new
            if (path.contains("\t")) {
                String[] renameParts = path.split("\t");
                path = renameParts[renameParts.length - 1];
            }
            numstatMap.put(path, new int[]{added, deleted});
        }

        // Parse name-status
        for (String line : statusOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) continue;
            FileChange fc = new FileChange();
            fc.status = parts[0].trim().charAt(0);
            if (fc.status == 'R' && parts.length >= 3) {
                fc.oldPath = parts[1].trim();
                fc.path = parts[2].trim();
            } else {
                fc.path = parts[1].trim();
                fc.oldPath = fc.path;
            }

            // Extract name and directory
            int lastSlash = fc.path.lastIndexOf('/');
            if (lastSlash >= 0) {
                fc.name = fc.path.substring(lastSlash + 1);
                fc.directory = fc.path.substring(0, lastSlash);
            } else {
                fc.name = fc.path;
                fc.directory = ".";
            }

            // Line counts
            int[] counts = numstatMap.get(fc.path);
            if (counts != null) {
                fc.additions = counts[0];
                fc.deletions = counts[1];
            }

            fc.lastModified = fileMTime(fc.path);
            currentFiles.add(fc);
        }

        populateFilesTable();
    }

    private void loadWorkingTreeFiles() {
        currentFiles.clear();

        // ── Staged files (index vs HEAD) ──
        String stagedStatus = runGit("diff", "--cached", "--name-status");
        String stagedNumstat = runGit("diff", "--cached", "--numstat");
        Map<String, int[]> stagedNums = parseNumstat(stagedNumstat);
        List<FileChange> stagedFiles = parseFileChanges(stagedStatus, stagedNums, true);

        // ── Unstaged files (working tree vs index) ──
        String unstagedStatus = runGit("diff", "--name-status");
        String unstagedNumstat = runGit("diff", "--numstat");
        Map<String, int[]> unstagedNums = parseNumstat(unstagedNumstat);
        List<FileChange> unstagedFiles = parseFileChanges(unstagedStatus, unstagedNums, false);

        // ── Untracked files ──
        String untrackedOutput = runGit("ls-files", "--others", "--exclude-standard");
        for (String line : untrackedOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String path = line.trim();
            FileChange fc = new FileChange();
            fc.status = '?';
            fc.path = path;
            fc.oldPath = path;
            fc.staged = false;
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0) {
                fc.name = path.substring(lastSlash + 1);
                fc.directory = path.substring(0, lastSlash);
            } else {
                fc.name = path;
                fc.directory = ".";
            }
            fc.lastModified = fileMTime(path);
            unstagedFiles.add(fc);
        }

        // ── Conflicted (unmerged) files ──
        String conflictedOutput = runGit("diff", "--name-only", "--diff-filter=U");
        List<FileChange> conflictedFiles = new ArrayList<>();
        Set<String> conflictedPaths = new HashSet<>();
        for (String line : conflictedOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String path = line.trim();
            conflictedPaths.add(path);
            FileChange cfc = new FileChange();
            cfc.status = 'U';
            cfc.path = path;
            cfc.oldPath = path;
            cfc.staged = false;
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0) {
                cfc.name = path.substring(lastSlash + 1);
                cfc.directory = path.substring(0, lastSlash);
            } else {
                cfc.name = path;
                cfc.directory = ".";
            }
            cfc.lastModified = fileMTime(path);
            conflictedFiles.add(cfc);
        }
        // Remove conflicted paths from staged/unstaged to avoid duplicates
        if (!conflictedPaths.isEmpty()) {
            stagedFiles.removeIf(f -> conflictedPaths.contains(f.path));
            unstagedFiles.removeIf(f -> conflictedPaths.contains(f.path));
        }

        // Conflicted first, then staged, then unstaged
        currentFiles.addAll(conflictedFiles);
        currentFiles.addAll(stagedFiles);
        currentFiles.addAll(unstagedFiles);

        // Sync auto-refresh baseline from the outputs we just loaded (no extra git calls)
        lastWtFingerprint = buildWtFingerprint(stagedStatus, stagedNumstat,
                unstagedStatus, unstagedNumstat, untrackedOutput, conflictedOutput);

        populateFilesTable();
    }

    // Fingerprint of the working tree state; mtimes are included so content
    // edits that keep line counts identical still register as a change
    private String buildWtFingerprint(String stagedStatus, String stagedNumstat,
                                      String unstagedStatus, String unstagedNumstat,
                                      String untracked, String conflicted) {
        StringBuilder sb = new StringBuilder();
        sb.append(stagedStatus).append('\u0001').append(stagedNumstat).append('\u0001')
          .append(unstagedStatus).append('\u0001').append(unstagedNumstat).append('\u0001')
          .append(untracked).append('\u0001').append(conflicted).append('\u0001');
        for (String line : (unstagedStatus + "\n" + untracked).split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t");
            String path = parts[parts.length - 1].trim();
            sb.append(fileMTime(path)).append(',');
        }
        return sb.toString();
    }

    private String computeWtFingerprint() {
        return buildWtFingerprint(
                runGit("diff", "--cached", "--name-status"),
                runGit("diff", "--cached", "--numstat"),
                runGit("diff", "--name-status"),
                runGit("diff", "--numstat"),
                runGit("ls-files", "--others", "--exclude-standard"),
                runGit("diff", "--name-only", "--diff-filter=U"));
    }

    private long fileMTime(String relPath) {
        return new File(repoPath, relPath).lastModified(); // 0 if the file is gone
    }

    private String formatMTime(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(millis));
    }

    private Map<String, int[]> parseNumstat(String numstatOutput) {
        Map<String, int[]> map = new HashMap<>();
        for (String line : numstatOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            int added = parseIntSafe(parts[0]);
            int deleted = parseIntSafe(parts[1]);
            String path = parts[2].trim();
            if (path.contains("\t")) {
                String[] renameParts = path.split("\t");
                path = renameParts[renameParts.length - 1];
            }
            map.put(path, new int[]{added, deleted});
        }
        return map;
    }

    private List<FileChange> parseFileChanges(String statusOutput, Map<String, int[]> numstatMap, boolean staged) {
        List<FileChange> files = new ArrayList<>();
        for (String line : statusOutput.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) continue;
            FileChange fc = new FileChange();
            fc.status = parts[0].trim().charAt(0);
            fc.staged = staged;
            if (fc.status == 'R' && parts.length >= 3) {
                fc.oldPath = parts[1].trim();
                fc.path = parts[2].trim();
            } else {
                fc.path = parts[1].trim();
                fc.oldPath = fc.path;
            }
            int lastSlash = fc.path.lastIndexOf('/');
            if (lastSlash >= 0) {
                fc.name = fc.path.substring(lastSlash + 1);
                fc.directory = fc.path.substring(0, lastSlash);
            } else {
                fc.name = fc.path;
                fc.directory = ".";
            }
            int[] counts = numstatMap.get(fc.path);
            if (counts != null) {
                fc.additions = counts[0];
                fc.deletions = counts[1];
            }
            fc.lastModified = fileMTime(fc.path);
            files.add(fc);
        }
        return files;
    }

    private void updateCommitMeta() {
        int conflictedCount = 0, stagedCount = 0, unstagedCount = 0;
        for (FileChange fc : currentFiles) {
            if (fc.status == 'U') conflictedCount++;
            else if (fc.staged) stagedCount++;
            else unstagedCount++;
        }
        String author = runGit("config", "user.name").trim();
        String email = runGit("config", "user.email").trim();
        commitMeta.setText(author + " <" + email + "> \u00B7 " + currentBranch);
        commitMeta.getParent().layout();

        // Update files header title
        StringBuilder header = new StringBuilder("CHANGED FILES  (");
        if (conflictedCount > 0) header.append(conflictedCount).append(" conflicted, ");
        header.append(stagedCount).append(" staged, ").append(unstagedCount).append(" unstaged)");
        filesHeaderTitle.setText(header.toString());
        filesHeaderTitle.getParent().layout();

        updateCommitBtnState();
    }

    private void updateCommitBtnState() {
        if (commitBtn == null || commitBtn.isDisposed()) return;
        boolean hasMessage = !commitMsgText.getText().trim().isEmpty();
        boolean hasStaged = false;
        for (FileChange fc : currentFiles) {
            if (fc.staged) { hasStaged = true; break; }
        }
        commitBtn.setEnabled(hasMessage && (hasStaged || amendCheck.getSelection()));
    }

    // ── Stage / Unstage / Discard ────────────────────────────────────

    private void stageFile(FileChange fc) {
        runGit("add", fc.path);
        reloadWorkingTree();
    }

    private void unstageFile(FileChange fc) {
        runGit("restore", "--staged", fc.path);
        reloadWorkingTree();
    }

    private void toggleStageFile(FileChange fc) {
        if (fc.staged) {
            unstageFile(fc);
        } else {
            stageFile(fc);
        }
    }

    private void stageAll() {
        runGit("add", "-A");
        reloadWorkingTree();
    }

    private void unstageAll() {
        runGit("reset", "HEAD");
        reloadWorkingTree();
    }

    private void stageFiles(List<FileChange> files) {
        List<String> args = new ArrayList<>();
        args.add("add");
        for (FileChange f : files) args.add(f.path);
        runGit(args.toArray(new String[0]));
        reloadWorkingTree();
    }

    private void unstageFiles(List<FileChange> files) {
        List<String> args = new ArrayList<>();
        args.add("restore");
        args.add("--staged");
        for (FileChange f : files) args.add(f.path);
        runGit(args.toArray(new String[0]));
        reloadWorkingTree();
    }

    private void toggleStageFiles(List<FileChange> files) {
        List<FileChange> toStage = new ArrayList<>();
        List<FileChange> toUnstage = new ArrayList<>();
        for (FileChange f : files) {
            if (f.staged) toUnstage.add(f);
            else toStage.add(f);
        }
        if (!toStage.isEmpty()) {
            List<String> args = new ArrayList<>();
            args.add("add");
            for (FileChange f : toStage) args.add(f.path);
            runGit(args.toArray(new String[0]));
        }
        if (!toUnstage.isEmpty()) {
            List<String> args = new ArrayList<>();
            args.add("restore");
            args.add("--staged");
            for (FileChange f : toUnstage) args.add(f.path);
            runGit(args.toArray(new String[0]));
        }
        reloadWorkingTree();
    }

    private void discardFiles(List<FileChange> files) {
        if (files.size() == 1) {
            discardFile(files.get(0));
            return;
        }
        MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
        confirm.setText("Discard Changes");
        confirm.setMessage("Discard changes to " + files.size() + " files?\nThis cannot be undone.");
        if (confirm.open() != SWT.YES) return;

        for (FileChange fc : files) {
            if (fc.status == '?' || (fc.status == 'A' && !fc.staged)) {
                try {
                    Files.deleteIfExists(Paths.get(repoPath, fc.path));
                } catch (IOException ex) {
                    statusBarLeft.setText("Error deleting: " + ex.getMessage());
                }
            } else if (fc.staged) {
                runGit("restore", "--staged", fc.path);
                runGit("restore", fc.path);
            } else {
                runGit("restore", fc.path);
            }
        }
        reloadWorkingTree();
    }

    private void deleteFiles(List<FileChange> files) {
        MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
        confirm.setText("Delete File" + (files.size() > 1 ? "s" : ""));
        confirm.setMessage("Delete " + (files.size() == 1 ? files.get(0).path : files.size() + " files")
                + "?\nThis cannot be undone.");
        if (confirm.open() != SWT.YES) return;
        for (FileChange fc : files) {
            try {
                Files.deleteIfExists(Paths.get(repoPath, fc.path));
            } catch (IOException ex) {
                statusBarLeft.setText("Error deleting: " + ex.getMessage());
            }
        }
        statusBarLeft.setText("Deleted " + files.size() + " file" + (files.size() > 1 ? "s" : ""));
        reloadWorkingTree();
    }

    private void discardFile(FileChange fc) {
        MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
        confirm.setText("Discard Changes");
        confirm.setMessage("Discard changes to " + fc.path + "?\nThis cannot be undone.");
        if (confirm.open() != SWT.YES) return;

        if (fc.status == '?' || (fc.status == 'A' && !fc.staged)) {
            // Untracked file — delete from disk
            try {
                Files.deleteIfExists(Paths.get(repoPath, fc.path));
            } catch (IOException e) {
                statusBarLeft.setText("Error deleting: " + e.getMessage());
            }
        } else if (fc.staged) {
            // Staged file — unstage first, then restore
            runGit("restore", "--staged", fc.path);
            runGit("restore", fc.path);
        } else {
            // Tracked modified file — restore
            runGit("restore", fc.path);
        }
        reloadWorkingTree();
    }

    private void reloadWorkingTree() {
        loadWorkingTreeFiles();
        conflictOperation = detectConflictState();
        if (conflictOperation != null) {
            showDetailArea("conflict");
            updateConflictBanner();
        } else {
            showDetailArea("commit");
        }
        updateCommitMeta();
        updateStatusBar();
    }

    // ── Conflict detection ─────────────────────────────────────────

    private String detectConflictState() {
        Path gitDir = Paths.get(repoPath, ".git");
        if (Files.exists(gitDir.resolve("MERGE_HEAD"))) return "merge";
        if (Files.exists(gitDir.resolve("CHERRY_PICK_HEAD"))) return "cherry-pick";
        if (Files.exists(gitDir.resolve("REVERT_HEAD"))) return "revert";
        if (Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply"))) return "rebase";
        return null;
    }

    private void updateConflictBanner() {
        if (conflictLabel == null || conflictLabel.isDisposed()) return;
        int conflictCount = 0;
        for (FileChange fc : currentFiles) {
            if (fc.status == 'U') conflictCount++;
        }
        String op = conflictOperation != null ? conflictOperation : "Operation";
        String capOp = op.substring(0, 1).toUpperCase() + op.substring(1);
        if (conflictCount > 0) {
            conflictLabel.setText(capOp + " in progress \u2014 " + conflictCount + " conflict(s) remaining. Resolve all conflicts and stage files to continue.");
        } else {
            conflictLabel.setText(capOp + " in progress \u2014 all conflicts resolved. Click Continue to finish.");
        }
        continueBtn.setEnabled(conflictCount == 0);
        abortBtn.setText("Abort " + capOp);
        continueBtn.setText("Continue " + capOp);
        conflictArea.layout(true, true);
    }

    private void doAbortConflict() {
        if (conflictOperation == null) return;
        String capOp = conflictOperation.substring(0, 1).toUpperCase() + conflictOperation.substring(1);
        MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
        confirm.setText("Abort " + capOp);
        confirm.setMessage("Abort the current " + conflictOperation + " and restore the previous state?");
        if (confirm.open() != SWT.YES) return;

        String result;
        switch (conflictOperation) {
            case "merge":       result = runGitWithExitCode("merge", "--abort"); break;
            case "cherry-pick": result = runGitWithExitCode("cherry-pick", "--abort"); break;
            case "revert":      result = runGitWithExitCode("revert", "--abort"); break;
            case "rebase":      result = runGitWithExitCode("rebase", "--abort"); break;
            default:            result = null;
        }
        if (result != null) {
            statusBarLeft.setText(capOp + " aborted");
            conflictOperation = null;
        } else {
            statusBarLeft.setText("Abort failed");
        }
        loadBranches();
        loadCommits();
    }

    private void doContinueConflict() {
        if (conflictOperation == null) return;
        // Guard: refuse if U files remain
        for (FileChange fc : currentFiles) {
            if (fc.status == 'U') {
                MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
                warn.setText("Conflicts Remaining");
                warn.setMessage("There are still unresolved conflicts.\nResolve and stage all conflicted files before continuing.");
                warn.open();
                return;
            }
        }
        String capOp = conflictOperation.substring(0, 1).toUpperCase() + conflictOperation.substring(1);
        String result;
        switch (conflictOperation) {
            case "merge":
                result = runGitWithExitCode("commit", "--no-edit");
                break;
            case "cherry-pick":
                result = runGitWithEnv(Map.of("GIT_EDITOR", "true"), "cherry-pick", "--continue");
                break;
            case "revert":
                result = runGitWithEnv(Map.of("GIT_EDITOR", "true"), "revert", "--continue");
                break;
            case "rebase":
                result = runGitWithEnv(Map.of("GIT_EDITOR", "true"), "rebase", "--continue");
                break;
            default:
                result = null;
        }
        if (result != null) {
            statusBarLeft.setText(capOp + " completed");
            conflictOperation = null;
        } else {
            statusBarLeft.setText(capOp + " continue failed \u2014 check status");
        }
        loadBranches();
        loadCommits();
    }

    /**
     * Whether a failed merge/rebase/pull actually left conflicts behind, as
     * opposed to failing for some unrelated reason (auth, dirty worktree,
     * divergent branches with no reconcile strategy, no upstream, ...).
     */
    private boolean looksLikeConflict(GitResult r) {
        String out = r.combined();
        if (out.contains("CONFLICT")
                || out.contains("Automatic merge failed")
                || out.contains("could not apply")
                || out.contains("Resolve all conflicts manually")) {
            return true;
        }
        // Fall back on the index: unmerged paths are the ground truth.
        return !runGit("diff", "--name-only", "--diff-filter=U").trim().isEmpty();
    }

    private void handleConflictAfterOp(String opName) {
        statusBarLeft.setText(opName + " resulted in conflicts \u2014 resolve in Working Tree");
        loadBranches();
        loadCommits();
        // Auto-select Working Tree row
        if (commitTable.getItemCount() > 0) {
            commitTable.setSelection(0);
            onCommitSelected();
        }
    }

    // ── Commit ───────────────────────────────────────────────────────

    private void doCommit() {
        String msg = commitMsgText.getText().trim();
        if (msg.isEmpty()) {
            MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            warn.setText("Empty Message");
            warn.setMessage("Please enter a commit message.");
            warn.open();
            return;
        }

        // Check that there are staged files
        boolean hasStaged = false;
        for (FileChange fc : currentFiles) {
            if (fc.staged) { hasStaged = true; break; }
        }
        if (!hasStaged && !amendCheck.getSelection()) {
            MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            warn.setText("Nothing Staged");
            warn.setMessage("No files are staged for commit.\nUse Stage All or stage individual files first.");
            warn.open();
            return;
        }

        String result;
        if (amendCheck.getSelection()) {
            result = runGitWithInput(msg, "commit", "--amend", "-F", "-");
        } else {
            result = runGitWithInput(msg, "commit", "-F", "-");
        }

        if (result != null) {
            // Success
            commitMsgText.setText("");
            savedCommitMsg = "";
            amendCheck.setSelection(false);
            statusBarLeft.setText("Committed: " + msg.split("\n")[0]);
            loadCommits();  // reload commit log + working tree
        } else {
            statusBarLeft.setText("Commit failed — " + lastGitErrorLine());
        }
    }

    /** Like runGit but returns null on non-zero exit. */
    /**
     * The repository's remotes, empty if it has none. Callers must guard with
     * {@link #requireRemote()} — this used to invent an "origin" that did not
     * exist, so pushing in a remote-less repo failed with a confusing error
     * about origin not being a repository.
     */
    private List<String> getRemotes() {
        String output = runGit("remote").trim();
        List<String> remotes = new ArrayList<>();
        for (String line : output.split("\n")) {
            String r = line.trim();
            if (!r.isEmpty()) remotes.add(r);
        }
        return remotes;
    }

    // ── Remote operations: process plumbing ──────────────────────────

    /**
     * Full outcome of a git invocation. Remote operations need more than
     * "worked / didn't": the user has to see git's own words to tell a
     * rejected push from an expired token from a dead network.
     */
    static final class GitResult {
        final int exit;
        final String stdout;
        final String stderr;
        final boolean timedOut;

        GitResult(int exit, String stdout, String stderr, boolean timedOut) {
            this.exit = exit;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.timedOut = timedOut;
        }

        boolean ok() { return exit == 0 && !timedOut; }

        /** Everything git said, stderr last — that is where the errors are. */
        String combined() {
            String s = (stdout.trim() + "\n" + stderr.trim()).trim();
            return s.replace("\r\n", "\n");
        }

        /**
         * The single most informative line, for the status bar. Prefers the
         * line that actually says what went wrong: git leads a failed push
         * with "To &lt;url&gt;", which tells the user nothing.
         */
        String firstLine() {
            if (timedOut) return "timed out";
            String[] lines = combined().split("\n");
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("! ") || t.startsWith("fatal:")
                        || t.startsWith("error:") || t.startsWith("remote:")) {
                    return t;
                }
            }
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty()) return t;
            }
            return "exit code " + exit;
        }
    }

    /** Remote git commands are killed after this long rather than hanging forever. */
    private static final int REMOTE_TIMEOUT_SEC = 120;

    /**
     * Run a git command that talks to a remote.
     *
     * <p>Differs from {@link #runGitWithEnv} in three ways that matter once a
     * network and a credential helper are involved:
     * <ul>
     *   <li>stdin is closed immediately, so git or ssh can never block forever
     *       waiting for a password on a pipe nobody will ever write to;</li>
     *   <li>{@code GIT_TERMINAL_PROMPT=0} and ssh {@code BatchMode} turn a
     *       would-be prompt into a fast, readable auth error (a GUI credential
     *       helper such as GCM still pops up normally);</li>
     *   <li>the process is force-killed after {@link #REMOTE_TIMEOUT_SEC}.</li>
     * </ul>
     *
     * <p>stdout and stderr are kept apart and drained on their own threads, so
     * neither pipe can fill up and deadlock the command.
     *
     * <p>Safe to call off the UI thread — and must be, since it blocks.
     */
    private GitResult runGitRemote(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(repoPath));
            Map<String, String> env = pb.environment();
            env.put("GIT_TERMINAL_PROMPT", "0");
            // Don't clobber a GIT_SSH_COMMAND the user configured themselves.
            env.putIfAbsent("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
            env.put("LC_ALL", "C");   // stable English messages to classify later

            p = pb.start();
            p.getOutputStream().close();

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread tOut = drainStream(p.getInputStream(), out);
            Thread tErr = drainStream(p.getErrorStream(), err);

            if (!p.waitFor(REMOTE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                tOut.join(1000);
                tErr.join(1000);
                return new GitResult(-1, readSafely(out), readSafely(err), true);
            }
            tOut.join(5000);
            tErr.join(5000);
            return new GitResult(p.exitValue(), readSafely(out), readSafely(err), false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return new GitResult(-1, "", "Interrupted", false);
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return new GitResult(-1, "", String.valueOf(e.getMessage()), false);
        }
    }

    /** Consume a stream on a daemon thread so a full pipe can't deadlock git. */
    private static Thread drainStream(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            // Decode as chars, not bytes: chunking raw bytes would split
            // multi-byte UTF-8 sequences in branch names across reads.
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    synchronized (sink) { sink.append(buf, 0, n); }
                }
            } catch (IOException ignored) {
                // stream closed by destroyForcibly — whatever we got is enough
            }
        }, "gitlog-stream-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String readSafely(StringBuilder sb) {
        synchronized (sb) { return sb.toString(); }
    }

    // ── Remote operations: UI orchestration ──────────────────────────

    /** Guards against a second remote op being launched while one is in flight. */
    private volatile boolean remoteOpRunning = false;

    /**
     * Run {@code op} on a worker thread, then hand the result to {@code onDone}
     * back on the UI thread.
     *
     * <p>Note this is deliberately not {@code Display.asyncExec} around the git
     * call: asyncExec runs its runnable <em>on</em> the UI thread, so a slow
     * push would freeze the whole window. The git call belongs on a real
     * thread; only the result handling comes back.
     */
    private void runRemoteOp(String busyText, Supplier<GitResult> op, Consumer<GitResult> onDone) {
        if (remoteOpRunning) return;
        remoteOpRunning = true;
        setRemoteButtonsEnabled(false);
        statusBarLeft.setText(busyText);

        final Display disp = shell.getDisplay();
        Thread worker = new Thread(() -> {
            GitResult r;
            try {
                r = op.get();
            } catch (Exception ex) {
                r = new GitResult(-1, "", String.valueOf(ex.getMessage()), false);
            }
            final GitResult result = r;
            if (disp.isDisposed()) { remoteOpRunning = false; return; }
            disp.asyncExec(() -> {
                remoteOpRunning = false;
                if (shell.isDisposed()) return;
                setRemoteButtonsEnabled(true);
                onDone.accept(result);
            });
        }, "gitlog-remote");
        worker.setDaemon(true);
        worker.start();
    }

    private void setRemoteButtonsEnabled(boolean enabled) {
        if (fetchBtn != null && !fetchBtn.isDisposed()) fetchBtn.setEnabled(enabled);
        if (pullBtn != null && !pullBtn.isDisposed()) pullBtn.setEnabled(enabled);
        if (pushBtn != null && !pushBtn.isDisposed()) pushBtn.setEnabled(enabled);
    }

    /** A recovery button offered alongside a remote error. */
    private static final class ErrorAction {
        final String label;
        final boolean primary;
        final Runnable run;
        ErrorAction(String label, boolean primary, Runnable run) {
            this.label = label;
            this.primary = primary;
            this.run = run;
        }
    }

    /**
     * Report a failed remote operation: a one-line summary in the status bar
     * plus a dialog carrying git's raw output verbatim. Guessing at what went
     * wrong and hiding the evidence is what made these failures unreadable.
     */
    private void showGitError(String title, String summary, GitResult r) {
        showGitError(title, summary, null, r, Collections.emptyList());
    }

    /**
     * As {@link #showGitError(String, String, GitResult)}, plus an optional
     * explanatory {@code hint} and buttons that actually fix the problem.
     */
    private void showGitError(String title, String summary, String hint,
                              GitResult r, List<ErrorAction> actions) {
        String terse = summary.endsWith(".") ? summary.substring(0, summary.length() - 1) : summary;
        statusBarLeft.setText(terse + " — " + r.firstLine());

        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dlg.setText(title);
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label msg = new Label(dlg, SWT.WRAP);
        msg.setText(r.timedOut
                ? summary + "\n\nGit did not finish within " + REMOTE_TIMEOUT_SEC
                  + " seconds and was stopped. The remote may be unreachable, or a"
                  + " credential helper may be waiting for input it will never get."
                : summary);
        msg.setFont(appFont);
        GridData msgGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        msgGd.widthHint = 560;
        msg.setLayoutData(msgGd);

        if (hint != null && !hint.isEmpty()) {
            Label hintLbl = new Label(dlg, SWT.WRAP);
            hintLbl.setText(hint);
            hintLbl.setFont(appFont);
            hintLbl.setForeground(AppTheme.lineNumFg);
            GridData hintGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            hintGd.widthHint = 560;
            hintLbl.setLayoutData(hintGd);
        }

        new Label(dlg, SWT.SEPARATOR | SWT.HORIZONTAL)
                .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label outLbl = new Label(dlg, SWT.NONE);
        outLbl.setText("Git output:");
        outLbl.setFont(appFont);

        Text out = new Text(dlg, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        String raw = r.combined();
        out.setText(raw.isEmpty() ? "(git exited with code " + r.exit + " and said nothing)" : raw);
        Font monoFont = new Font(dlg.getDisplay(), AppTheme.MONO_FONT, 9, SWT.NORMAL);
        out.setFont(monoFont);
        GridData outGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        outGd.heightHint = 190;
        outGd.widthHint = 560;
        out.setLayoutData(outGd);
        dlg.addDisposeListener(e -> monoFont.dispose());

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        buttons.setLayout(new GridLayout(2 + actions.size(), false));

        Button copyBtn = new Button(buttons, SWT.PUSH);
        copyBtn.setText("Copy");
        copyBtn.setFont(appFont);
        copyBtn.addListener(SWT.Selection, e -> {
            Clipboard cb = new Clipboard(dlg.getDisplay());
            try {
                cb.setContents(new Object[]{out.getText()}, new Transfer[]{TextTransfer.getInstance()});
                copyBtn.setText("Copied");
            } finally {
                cb.dispose();
            }
        });

        Button closeBtn = new Button(buttons, SWT.PUSH);
        closeBtn.setText("Close");
        closeBtn.setFont(appFont);
        closeBtn.addListener(SWT.Selection, e -> dlg.close());

        Button defaultBtn = closeBtn;
        for (ErrorAction a : actions) {
            Button b = new Button(buttons, SWT.PUSH);
            b.setText(a.label);
            b.setFont(appFont);
            b.addListener(SWT.Selection, e -> {
                // Close before acting: the action usually starts another remote
                // operation that will raise its own dialog.
                dlg.close();
                a.run.run();
            });
            if (a.primary) defaultBtn = b;
        }

        dlg.setDefaultButton(defaultBtn);
        dlg.setSize(620, actions.isEmpty() ? 400 : 440);
        dlg.open();
    }

    // ── Remote operations: error classification (item 4) ─────────────

    private enum RemoteErrorKind {
        NON_FAST_FORWARD, STALE_INFO, PROTECTED_BRANCH, AUTH_FAILED, NETWORK,
        NO_SUCH_REMOTE, NO_UPSTREAM, DIVERGENT, LOCAL_CHANGES, CONFLICT,
        FILE_TOO_LARGE, TIMED_OUT, UNKNOWN
    }

    /**
     * Work out what git actually objected to. Matching is done against
     * git's English messages, which is why {@link #runGitRemote} pins
     * {@code LC_ALL=C}. Order matters: the specific rejection reasons have
     * to be tested before the generic ones.
     */
    private static RemoteErrorKind classifyRemoteError(GitResult r) {
        if (r.timedOut) return RemoteErrorKind.TIMED_OUT;
        String raw = r.combined();
        String o = raw.toLowerCase(Locale.ROOT);

        if (o.contains("stale info")) return RemoteErrorKind.STALE_INFO;
        if (o.contains("pre-receive hook declined") || o.contains("protected branch")
                || o.contains("push declined") || raw.contains("GH006")) {
            return RemoteErrorKind.PROTECTED_BRANCH;
        }
        if (raw.contains("GH001") || o.contains("file size limit")
                || o.contains("exceeds") && o.contains("file size")) {
            return RemoteErrorKind.FILE_TOO_LARGE;
        }
        if (o.contains("[rejected]")
                && (o.contains("non-fast-forward") || o.contains("fetch first"))) {
            return RemoteErrorKind.NON_FAST_FORWARD;
        }
        if (o.contains("authentication failed") || o.contains("could not read username")
                || o.contains("could not read password") || o.contains("terminal prompts disabled")
                || o.contains("permission denied (publickey)") || o.contains("invalid username or password")
                || o.contains("403 forbidden") || o.contains("access denied")) {
            return RemoteErrorKind.AUTH_FAILED;
        }
        if (o.contains("could not resolve host") || o.contains("connection timed out")
                || o.contains("connection refused") || o.contains("network is unreachable")
                || o.contains("failed to connect") || o.contains("operation timed out")) {
            return RemoteErrorKind.NETWORK;
        }
        if (o.contains("does not appear to be a git repository")
                || o.contains("repository not found")) {
            return RemoteErrorKind.NO_SUCH_REMOTE;
        }
        if (o.contains("no upstream branch") || o.contains("no tracking information")) {
            return RemoteErrorKind.NO_UPSTREAM;
        }
        if (o.contains("divergent branches")) return RemoteErrorKind.DIVERGENT;
        if (o.contains("would be overwritten by")) return RemoteErrorKind.LOCAL_CHANGES;
        if (raw.contains("CONFLICT") || o.contains("automatic merge failed")) {
            return RemoteErrorKind.CONFLICT;
        }
        return RemoteErrorKind.UNKNOWN;
    }

    // ── Remote operations: push targeting (item 6) ───────────────────

    /** Where the current branch is actually going to land on the remote. */
    private static final class PushTarget {
        final String remote;
        final String remoteBranch;  // short name on the remote side, e.g. "main"
        final boolean isNew;        // no upstream configured for this remote yet
        PushTarget(String remote, String remoteBranch, boolean isNew) {
            this.remote = remote;
            this.remoteBranch = remoteBranch;
            this.isNew = isNew;
        }
        String destRef() { return "refs/heads/" + remoteBranch; }
        String trackingRef() { return remote + "/" + remoteBranch; }
    }

    /**
     * Resolve where {@code branch} should be pushed on {@code remote}.
     *
     * <p>Reads the branch's configured upstream rather than assuming the
     * remote branch shares the local name. Pushing local {@code feature} to
     * {@code origin/feature} when it actually tracks {@code origin/feat-x}
     * silently creates a second remote branch and, with {@code -u}, rewrites
     * the tracking config to point at it.
     */
    private PushTarget resolvePushTarget(String remote, String branch) {
        String upRemote = runGit("config", "--get", "branch." + branch + ".remote").trim();
        String upMerge = runGit("config", "--get", "branch." + branch + ".merge").trim();
        if (!upMerge.isEmpty() && upRemote.equals(remote)) {
            String shortName = upMerge.startsWith("refs/heads/")
                    ? upMerge.substring("refs/heads/".length()) : upMerge;
            return new PushTarget(remote, shortName, false);
        }
        // No upstream, or the user picked a different remote than the tracked one.
        return new PushTarget(remote, branch, true);
    }

    /** Explicit refspec: never let git guess the destination from the branch name. */
    private String[] buildPushArgs(PushTarget t, boolean setUpstream) {
        List<String> a = new ArrayList<>();
        a.add("push");
        if (setUpstream) a.add("-u");
        a.add(t.remote);
        a.add("HEAD:" + t.destRef());
        return a.toArray(new String[0]);
    }

    /** True when HEAD is not on a branch, so there is nothing to push or pull. */
    private boolean requireBranch(String verb) {
        if (currentBranch == null || currentBranch.isEmpty() || "HEAD".equals(currentBranch)) {
            MessageBox mb = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            mb.setText("Detached HEAD");
            mb.setMessage("You are not on a branch, so there is nothing to " + verb + ".\n\n"
                    + "Check out a branch first, or create one from here with:\n"
                    + "    git switch -c <name>");
            mb.open();
            return false;
        }
        return true;
    }

    /** True when the repository actually has a remote configured. */
    private boolean requireRemote() {
        if (getRemotes().isEmpty()) {
            MessageBox mb = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
            mb.setText("No Remote");
            mb.setMessage("This repository has no remote configured.\n\n"
                    + "Add one with:\n    git remote add origin <url>");
            mb.open();
            return false;
        }
        return true;
    }

    // ── Remote operations: push + recovery (items 4 and 5) ───────────

    /** Push the current branch, setting upstream only if it has none yet. */
    private void startPush(String remote, String branch, boolean pushTags) {
        startPush(remote, branch, pushTags, resolvePushTarget(remote, branch).isNew);
    }

    /** Push the current branch, then route any failure to a recovery path. */
    private void startPush(String remote, String branch, boolean pushTags, boolean setUpstream) {
        PushTarget t = resolvePushTarget(remote, branch);
        String[] cmd = buildPushArgs(t, setUpstream);

        runRemoteOp("Pushing " + branch + " to " + t.trackingRef() + "...",
                () -> {
                    GitResult r = runGitRemote(cmd);
                    if (r.ok() && pushTags) {
                        GitResult tags = runGitRemote("push", remote, "--tags");
                        if (!tags.ok()) return tags;
                    }
                    return r;
                },
                r -> {
                    if (r.ok()) {
                        statusBarLeft.setText(pushTags ? "Push complete (with tags)" : "Push complete");
                        loadBranches();
                        loadCommits();
                    } else {
                        handlePushFailure(remote, branch, r);
                    }
                });
    }

    /** Turn a push failure into an explanation plus, where possible, a way out. */
    private void handlePushFailure(String remote, String branch, GitResult r) {
        PushTarget t = resolvePushTarget(remote, branch);
        RemoteErrorKind kind = classifyRemoteError(r);
        String where = branch + " → " + t.trackingRef();

        switch (kind) {
            case NON_FAST_FORWARD: {
                List<ErrorAction> actions = new ArrayList<>();
                actions.add(new ErrorAction("Pull (rebase), then Push", true,
                        () -> pullRebaseThenPush(remote, branch)));
                actions.add(new ErrorAction("Force push…", false,
                        () -> confirmForcePush(remote, branch)));
                showGitError("Push Rejected",
                        t.trackingRef() + " has commits that you do not have locally, "
                        + "so pushing " + branch + " would throw them away.",
                        "Pull (rebase) replays your commits on top of theirs and is almost always "
                        + "what you want. Force push discards theirs — only correct when the "
                        + "remote history is yours to rewrite, e.g. after amending your own commit.",
                        r, actions);
                break;
            }
            case STALE_INFO: {
                List<ErrorAction> actions = new ArrayList<>();
                actions.add(new ErrorAction("Fetch, then try again", true,
                        () -> runFetch(remote)));
                showGitError("Push Rejected (stale info)",
                        "Your copy of " + t.trackingRef() + " is out of date, so the safety "
                        + "check on the force push could not be trusted.",
                        "Fetch first, look at what actually changed on the remote, then decide "
                        + "whether the force push is still the right move.",
                        r, actions);
                break;
            }
            case PROTECTED_BRANCH:
                // Deliberately no force button: the server said no, and forcing
                // would only produce the same rejection.
                showGitError("Push Rejected by Server",
                        "The server refused the push to " + t.trackingRef() + ".",
                        "This is a branch protection rule or a pre-receive hook, not a problem with "
                        + "your local repository. Open a pull request, or ask whoever administers "
                        + "the repository. Forcing will not help.",
                        r, Collections.emptyList());
                break;
            case AUTH_FAILED:
                showGitError("Authentication Failed",
                        "Git could not authenticate to " + remote + ".",
                        "Your token or SSH key is missing, expired, or lacks write access. "
                        + "For HTTPS, refresh the credential with:\n"
                        + "    git credential-manager github login\n"
                        + "For SSH, check that your key is loaded:  ssh-add -l",
                        r, Collections.emptyList());
                break;
            case NETWORK: {
                List<ErrorAction> actions = new ArrayList<>();
                actions.add(new ErrorAction("Retry", true,
                        () -> startPush(remote, branch, false)));
                showGitError("Network Error",
                        "Could not reach " + remote + ".",
                        "Check your connection, VPN, or proxy and try again.",
                        r, actions);
                break;
            }
            case TIMED_OUT: {
                List<ErrorAction> actions = new ArrayList<>();
                actions.add(new ErrorAction("Retry", true,
                        () -> startPush(remote, branch, false)));
                showGitError("Push Timed Out", "Pushing " + where + " did not finish in time.",
                        null, r, actions);
                break;
            }
            case FILE_TOO_LARGE:
                showGitError("Push Rejected (file too large)",
                        "The server refused the push because a file in your history is too big.",
                        "The file has to come out of the commits themselves — pushing again "
                        + "will not help. Use git-lfs for large assets, or rewrite the offending "
                        + "commit to drop the file.",
                        r, Collections.emptyList());
                break;
            case NO_SUCH_REMOTE:
                showGitError("Remote Not Found",
                        "'" + remote + "' does not point at a repository git can reach.",
                        "Check the URL with:  git remote -v",
                        r, Collections.emptyList());
                break;
            default:
                showGitError("Push Failed", "Could not push " + where + ".", null,
                        r, Collections.emptyList());
                break;
        }
    }

    /** Explain a failed pull that did not leave conflicts behind. */
    private void handlePullFailure(String remote, GitResult r) {
        switch (classifyRemoteError(r)) {
            case DIVERGENT:
                showGitError("Pull Rejected",
                        "Your branch and " + remote + " have both moved on, and git does not "
                        + "know how you want them reconciled.",
                        "Tick Rebase in the Pull dialog to replay your commits on top, or set a "
                        + "default once with:\n    git config pull.rebase true",
                        r, Collections.emptyList());
                break;
            case LOCAL_CHANGES:
                showGitError("Pull Blocked",
                        "Pulling would overwrite changes in your working tree.",
                        "Commit or stash your changes first — the Stash button on the toolbar "
                        + "does this in one step.",
                        r, Collections.emptyList());
                break;
            case AUTH_FAILED:
                showGitError("Authentication Failed",
                        "Git could not authenticate to " + remote + ".",
                        "Your token or SSH key is missing, expired, or lacks access.",
                        r, Collections.emptyList());
                break;
            case NETWORK:
            case TIMED_OUT:
                showGitError("Network Error", "Could not reach " + remote + ".",
                        "Check your connection, VPN, or proxy and try again.",
                        r, Collections.emptyList());
                break;
            case NO_UPSTREAM:
                showGitError("No Upstream",
                        "This branch does not track a branch on " + remote + ".",
                        "Push it once with Set upstream ticked, and pull will work from then on.",
                        r, Collections.emptyList());
                break;
            default:
                showGitError("Pull Failed", "Could not pull from " + remote + ".", r);
                break;
        }
    }

    /** Recovery path for a non-fast-forward: rebase onto the remote, then retry. */
    private void pullRebaseThenPush(String remote, String branch) {
        runRemoteOp("Pulling (rebase) from " + remote + "...",
                () -> runGitRemote("pull", "--rebase", remote, branch),
                r -> {
                    loadBranches();
                    loadCommits();
                    if (r.ok()) {
                        startPush(remote, branch, false);
                    } else if (looksLikeConflict(r)) {
                        handleConflictAfterOp("Pull (rebase)");
                    } else {
                        showGitError("Pull Failed",
                                "Rebasing onto " + remote + " failed, so nothing was pushed.", r);
                    }
                });
    }

    // ── Force push (item 5) ──────────────────────────────────────────

    private Boolean forceIfIncludesSupported;

    /** {@code --force-if-includes} needs git 2.30. */
    private boolean supportsForceIfIncludes() {
        if (forceIfIncludesSupported == null) {
            forceIfIncludesSupported = gitVersionAtLeast(runGit("--version"), 2, 30);
        }
        return forceIfIncludesSupported;
    }

    static boolean gitVersionAtLeast(String versionLine, int major, int minor) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(versionLine);
        if (!m.find()) return false;
        try {
            int maj = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            return maj > major || (maj == major && min >= minor);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Branches where a force push is presumed to be a mistake unless confirmed hard. */
    private static boolean isProtectedName(String branch) {
        return "main".equals(branch) || "master".equals(branch)
                || "develop".equals(branch) || "release".equals(branch);
    }

    /**
     * Confirm, then force push with a lease.
     *
     * <p>Never offers a bare {@code --force}. The lease is pinned to the exact
     * SHA we currently believe the remote is at, so if anyone pushed in the
     * meantime the push is refused rather than silently overwriting them.
     */
    private void confirmForcePush(String remote, String branch) {
        PushTarget t = resolvePushTarget(remote, branch);
        String trackingRef = t.trackingRef();

        String remoteSha = runGit("rev-parse", "--verify", "--quiet", trackingRef).trim();
        if (remoteSha.isEmpty()) {
            // Without a remote-tracking ref there is nothing to take a lease on,
            // and a force would be blind. Make the user fetch first.
            MessageBox mb = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            mb.setText("Fetch First");
            mb.setMessage("There is no local copy of " + trackingRef + " to check against.\n\n"
                    + "Fetch first so the force push can verify what it is about to overwrite.");
            mb.open();
            return;
        }

        // Commits the remote has that we do not — exactly what would be destroyed.
        String countOut = runGit("rev-list", "--count", "HEAD.." + trackingRef).trim();
        int discarded = 0;
        try { discarded = Integer.parseInt(countOut); } catch (NumberFormatException ignored) {}
        String doomed = runGit("log", "--format=%h  %an  %s", "-10", "HEAD.." + trackingRef).trim();

        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dlg.setText("Force Push");
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label headline = new Label(dlg, SWT.WRAP);
        headline.setFont(boldFont);
        headline.setText(discarded > 0
                ? "This will permanently delete " + discarded + " commit"
                  + (discarded == 1 ? "" : "s") + " from " + trackingRef
                : "Force push " + branch + " to " + trackingRef);
        GridData hlGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hlGd.widthHint = 560;
        headline.setLayoutData(hlGd);

        Label detail = new Label(dlg, SWT.WRAP);
        detail.setFont(appFont);
        detail.setText(discarded > 0
                ? "These commits exist only on the remote. Anyone who has pulled them will "
                  + "have to reset. If you are not sure they are yours to delete, cancel and "
                  + "pull instead."
                : "The remote branch will be reset to your local history. Nothing on the remote "
                  + "is ahead of you, so this is the normal case after amending or rebasing "
                  + "your own commits.");
        GridData dGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dGd.widthHint = 560;
        detail.setLayoutData(dGd);

        if (!doomed.isEmpty()) {
            Text list = new Text(dlg, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL);
            list.setText(doomed);
            Font mono = new Font(dlg.getDisplay(), AppTheme.MONO_FONT, 9, SWT.NORMAL);
            list.setFont(mono);
            dlg.addDisposeListener(e -> mono.dispose());
            GridData lGd = new GridData(SWT.FILL, SWT.FILL, true, true);
            lGd.heightHint = 110;
            lGd.widthHint = 560;
            list.setLayoutData(lGd);
        }

        Label safety = new Label(dlg, SWT.WRAP);
        safety.setFont(appFont);
        safety.setForeground(AppTheme.lineNumFg);
        safety.setText("Uses --force-with-lease"
                + (supportsForceIfIncludes() ? " --force-if-includes" : "")
                + ", pinned to " + remoteSha.substring(0, Math.min(8, remoteSha.length()))
                + ". If anyone pushes before this runs, it will be refused rather than "
                + "overwrite them.");
        GridData sGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sGd.widthHint = 560;
        safety.setLayoutData(sGd);

        // Extra friction on branches people share.
        final Text confirmText;
        if (isProtectedName(t.remoteBranch)) {
            Label ask = new Label(dlg, SWT.NONE);
            ask.setFont(appFont);
            ask.setText("Type \"" + t.remoteBranch + "\" to confirm:");
            confirmText = new Text(dlg, SWT.BORDER | SWT.SINGLE);
            confirmText.setFont(appFont);
            confirmText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        } else {
            confirmText = null;
        }

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        buttons.setLayout(new GridLayout(2, false));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button goBtn = new Button(buttons, SWT.PUSH);
        goBtn.setText("Force push");
        goBtn.setFont(appFont);
        goBtn.addListener(SWT.Selection, e -> {
            if (confirmText != null && !confirmText.getText().trim().equals(t.remoteBranch)) {
                MessageBox mb = new MessageBox(dlg, SWT.ICON_WARNING | SWT.OK);
                mb.setText("Not Confirmed");
                mb.setMessage("Type the branch name exactly to confirm.");
                mb.open();
                return;
            }
            dlg.close();
            runForcePush(t, branch, remoteSha);
        });

        dlg.setDefaultButton(cancelBtn);
        dlg.setSize(620, doomed.isEmpty() ? 340 : 430);
        dlg.open();
    }

    private void runForcePush(PushTarget t, String branch, String expectedSha) {
        List<String> a = new ArrayList<>();
        a.add("push");
        a.add("--force-with-lease=" + t.destRef() + ":" + expectedSha);
        if (supportsForceIfIncludes()) a.add("--force-if-includes");
        a.add(t.remote);
        a.add("HEAD:" + t.destRef());
        String[] cmd = a.toArray(new String[0]);

        runRemoteOp("Force pushing " + branch + " to " + t.trackingRef() + "...",
                () -> runGitRemote(cmd),
                r -> {
                    if (r.ok()) {
                        statusBarLeft.setText("Force push complete — " + t.trackingRef()
                                + " now matches " + branch);
                        loadBranches();
                        loadCommits();
                    } else {
                        handlePushFailure(t.remote, branch, r);
                    }
                });
    }

    private String runGitWithExitCode(String... args) {
        return runGitWithEnv(null, args);
    }

    /** Like runGitWithExitCode but accepts environment variables. */
    private String runGitWithEnv(Map<String, String> env, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            Collections.addAll(cmd, args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);
            if (env != null) pb.environment().putAll(env);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit != 0) {
                lastGitError = output.trim();
                return null;
            }
            return output;
        } catch (Exception e) {
            lastGitError = String.valueOf(e.getMessage());
            return null;
        }
    }

    /**
     * Like runGitWithExitCode but feeds {@code input} to git's stdin.
     * Free-form user text (commit/tag messages) MUST go through stdin ("-F", "-")
     * instead of a "-m" argument: on Windows, ProcessBuilder does not escape
     * embedded double quotes, so a message containing '"' breaks the command
     * line apart and git sees the fragments as pathspecs.
     */
    private String runGitWithInput(String input, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            Collections.addAll(cmd, args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit != 0) {
                lastGitError = output.trim();
                return null;
            }
            return output;
        } catch (Exception e) {
            lastGitError = String.valueOf(e.getMessage());
            return null;
        }
    }

    /** First line of the last failed git command's output, for status messages. */
    private String lastGitErrorLine() {
        if (lastGitError == null || lastGitError.isEmpty()) return "";
        return lastGitError.split("\n")[0];
    }

    private void onAmendToggled() {
        if (amendCheck.getSelection()) {
            // Save current message, load previous commit message
            savedCommitMsg = commitMsgText.getText();
            String prevMsg = runGit("log", "-1", "--format=%B").trim();
            commitMsgText.setText(prevMsg);
        } else {
            // Restore previously typed message
            commitMsgText.setText(savedCommitMsg);
        }
    }

    // ── Branch operations ──────────────────────────────────────────

    private void doCheckout(String branch) {
        // Check for dirty working tree
        String status = runGit("status", "--porcelain");
        if (!status.trim().isEmpty()) {
            // Dirty tree — show dialog
            showCheckoutDirtyDialog(branch);
            return;
        }
        String result = runGitWithExitCode("checkout", branch);
        if (result != null) {
            currentBranch = branch;
            statusBarLeft.setText("Switched to branch: " + branch);
            loadBranches();
            loadCommits();
        } else {
            statusBarLeft.setText("Checkout failed — check status");
        }
    }

    private void showCheckoutDirtyDialog(String branch) {
        MessageBox dlg = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO | SWT.CANCEL);
        dlg.setText("Uncommitted Changes");
        dlg.setMessage("You have uncommitted changes.\n\n"
                + "YES = Stash changes, switch to " + branch + ", restore when switching back\n"
                + "NO = Try switching anyway (may fail if conflicts)\n"
                + "CANCEL = Stay on " + currentBranch);
        int choice = dlg.open();
        if (choice == SWT.YES) {
            // Stash & switch
            runGit("stash", "push", "-m", "Auto-stash before checkout " + branch);
            String result = runGitWithExitCode("checkout", branch);
            if (result != null) {
                currentBranch = branch;
                statusBarLeft.setText("Switched to " + branch + " (changes stashed)");
                loadBranches();
                loadCommits();
            } else {
                // Restore stash since checkout failed
                runGit("stash", "pop");
                statusBarLeft.setText("Checkout failed — changes restored");
            }
        } else if (choice == SWT.NO) {
            String result = runGitWithExitCode("checkout", branch);
            if (result != null) {
                currentBranch = branch;
                statusBarLeft.setText("Switched to branch: " + branch);
                loadBranches();
                loadCommits();
            } else {
                statusBarLeft.setText("Checkout failed — conflicts with local changes");
            }
        }
    }

    private void showCheckoutRemoteDialog(String remoteBranch, String localName) {
        // Simple input dialog for local branch name
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Checkout Remote Branch");
        dlg.setLayout(new GridLayout(2, false));

        new Label(dlg, SWT.NONE).setText("Remote:");
        Label remoteLabel = new Label(dlg, SWT.NONE);
        remoteLabel.setText(remoteBranch);
        remoteLabel.setFont(appFont);

        new Label(dlg, SWT.NONE).setText("Local name:");
        Text nameText = new Text(dlg, SWT.BORDER | SWT.SINGLE);
        nameText.setText(localName);
        nameText.setFont(appFont);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button okBtn = new Button(buttons, SWT.PUSH);
        okBtn.setText("Checkout");
        okBtn.setFont(appFont);
        okBtn.addListener(SWT.Selection, e -> {
            String name = nameText.getText().trim();
            if (!name.isEmpty()) {
                String result = runGitWithExitCode("checkout", "-b", name, remoteBranch);
                if (result != null) {
                    currentBranch = name;
                    statusBarLeft.setText("Created and switched to: " + name);
                    loadBranches();
                    loadCommits();
                } else {
                    statusBarLeft.setText("Checkout failed — branch may already exist");
                }
            }
            dlg.close();
        });

        dlg.setSize(400, 160);
        dlg.open();
    }

    private void showNewBranchDialog(String startPoint) {
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("New Branch");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label nameLabel = new Label(dlg, SWT.NONE);
        nameLabel.setText("Name:");
        nameLabel.setFont(appFont);
        Text nameText = new Text(dlg, SWT.BORDER | SWT.SINGLE);
        nameText.setFont(appFont);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        nameText.setMessage("feature/my-branch");

        Label startLabel = new Label(dlg, SWT.NONE);
        startLabel.setText("Start from:");
        startLabel.setFont(appFont);
        Combo startCombo = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        startCombo.setFont(appFont);
        startCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        startCombo.add("HEAD (" + currentBranch + ")");
        for (Map.Entry<String, String> entry : branchToCommit.entrySet()) {
            if (!entry.getKey().startsWith("tag:")) {
                startCombo.add(entry.getKey());
            }
        }
        startCombo.select(0);
        if (startPoint != null) {
            int idx = startCombo.indexOf(startPoint);
            if (idx >= 0) startCombo.select(idx);
        }

        Button checkoutBox = new Button(dlg, SWT.CHECK);
        checkoutBox.setText("Checkout after creating");
        checkoutBox.setSelection(true);
        checkoutBox.setFont(appFont);
        checkoutBox.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

        // Buttons
        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button createBtn = new Button(buttons, SWT.PUSH);
        createBtn.setText("Create");
        createBtn.setFont(appFont);
        createBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createBtn.addListener(SWT.Selection, e -> {
            String name = nameText.getText().trim();
            if (name.isEmpty()) return;
            String from = startCombo.getSelectionIndex() == 0 ? "HEAD" : startCombo.getText();
            boolean doCheckoutAfter = checkoutBox.getSelection();

            String result;
            if (doCheckoutAfter) {
                result = runGitWithExitCode("checkout", "-b", name, from);
            } else {
                result = runGitWithExitCode("branch", name, from);
            }

            if (result != null) {
                if (doCheckoutAfter) currentBranch = name;
                statusBarLeft.setText("Created branch: " + name);
                loadBranches();
                loadCommits();
            } else {
                statusBarLeft.setText("Failed to create branch: " + name);
            }
            dlg.close();
        });

        dlg.setSize(420, 200);
        dlg.open();
    }

    private void showCreateTagDialog(String target) {
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Create Tag");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label nameLabel = new Label(dlg, SWT.NONE);
        nameLabel.setText("Tag name:");
        nameLabel.setFont(appFont);
        Text nameText = new Text(dlg, SWT.BORDER | SWT.SINGLE);
        nameText.setFont(appFont);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        nameText.setMessage("v1.0.0");

        Label targetLabel = new Label(dlg, SWT.NONE);
        targetLabel.setText("Target:");
        targetLabel.setFont(appFont);
        Label targetValue = new Label(dlg, SWT.NONE);
        targetValue.setText(target);
        targetValue.setFont(appFont);
        targetValue.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label msgLabel = new Label(dlg, SWT.NONE);
        msgLabel.setText("Message:");
        msgLabel.setFont(appFont);
        msgLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        Text msgText = new Text(dlg, SWT.BORDER | SWT.MULTI | SWT.WRAP);
        msgText.setFont(appFont);
        GridData msgGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        msgGd.heightHint = 60;
        msgText.setLayoutData(msgGd);
        msgText.setMessage("Optional — leave empty for lightweight tag");

        // Buttons
        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button createBtn = new Button(buttons, SWT.PUSH);
        createBtn.setText("Create");
        createBtn.setFont(appFont);
        createBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createBtn.addListener(SWT.Selection, e -> {
            String name = nameText.getText().trim();
            if (name.isEmpty()) return;
            String message = msgText.getText().trim();

            String result;
            if (message.isEmpty()) {
                result = runGitWithExitCode("tag", name, target);
            } else {
                result = runGitWithInput(message, "tag", "-a", name, target, "-F", "-");
            }

            if (result != null) {
                statusBarLeft.setText("Created tag: " + name);
                loadBranches();
                loadCommits();
            } else {
                statusBarLeft.setText("Failed to create tag: " + name);
            }
            dlg.close();
        });

        dlg.setSize(420, 260);
        dlg.open();
    }

    private void showDeleteBranchDialog(String branch) {
        boolean isCurrent = branch.equals(currentBranch);
        if (isCurrent) {
            MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            warn.setText("Cannot Delete");
            warn.setMessage("Cannot delete the current branch.\nSwitch to another branch first.");
            warn.open();
            return;
        }

        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Delete Branch");
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label msg = new Label(dlg, SWT.WRAP);
        msg.setText("Delete branch: " + branch + "?");
        msg.setFont(appFont);
        msg.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button forceCheck = new Button(dlg, SWT.CHECK);
        forceCheck.setText("Force delete (-D)");
        forceCheck.setFont(appFont);

        // Check if branch has unmerged commits
        String mergeBase = runGit("merge-base", currentBranch, branch).trim();
        String branchTip = runGit("rev-parse", branch).trim();
        boolean hasUnmerged = !mergeBase.equals(branchTip);
        if (hasUnmerged) {
            forceCheck.setSelection(true);
            Label warn = new Label(dlg, SWT.WRAP);
            warn.setText("This branch has unmerged commits.");
            warn.setFont(appFont);
            warn.setForeground(deletionsFg);
            warn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        }

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button deleteBtn = new Button(buttons, SWT.PUSH);
        deleteBtn.setText("Delete");
        deleteBtn.setFont(appFont);
        deleteBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        deleteBtn.addListener(SWT.Selection, e -> {
            String flag = forceCheck.getSelection() ? "-D" : "-d";
            // Try delete; if worktree blocks it, remove worktree and retry
            String errorOut = runGit("branch", flag, branch);
            boolean ok = !errorOut.startsWith("error:");
            if (!ok && errorOut.contains("used by worktree")) {
                // Auto-remove the worktree and retry
                String wtList = runGit("worktree", "list", "--porcelain");
                String wtPath = null;
                for (String line : wtList.split("\n")) {
                    if (line.startsWith("worktree ")) wtPath = line.substring(9);
                    if (line.startsWith("branch refs/heads/" + branch) && wtPath != null) break;
                }
                if (wtPath != null) {
                    runGit("worktree", "remove", wtPath);
                    errorOut = runGit("branch", flag, branch);
                    ok = !errorOut.startsWith("error:");
                }
            }
            if (ok) {
                statusBarLeft.setText("Deleted branch: " + branch);
                dlg.close();
                loadBranches();
                loadCommits();
                return;
            }
            // -d failed (unmerged commits) — ask to force delete
            if (!forceCheck.getSelection()) {
                MessageBox confirm = new MessageBox(dlg, SWT.ICON_WARNING | SWT.YES | SWT.NO);
                confirm.setText("Force Delete?");
                confirm.setMessage("Branch " + branch + " has unmerged commits.\n\nForce delete anyway?");
                if (confirm.open() == SWT.YES) {
                    String r2 = runGit("branch", "-D", branch);
                    if (!r2.startsWith("error:")) {
                        statusBarLeft.setText("Deleted branch: " + branch);
                        dlg.close();
                        loadBranches();
                        loadCommits();
                        return;
                    }
                }
            }
            statusBarLeft.setText("Delete failed: " + errorOut.replace("error: ", "").trim());
        });

        dlg.setSize(400, 180);
        dlg.open();
    }

    private void showRenameBranchDialog(String branch) {
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Rename Branch");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        new Label(dlg, SWT.NONE).setText("Current:");
        Label curLabel = new Label(dlg, SWT.NONE);
        curLabel.setText(branch);
        curLabel.setFont(appFont);

        new Label(dlg, SWT.NONE).setText("New name:");
        Text nameText = new Text(dlg, SWT.BORDER | SWT.SINGLE);
        nameText.setText(branch);
        nameText.setFont(appFont);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button renameBtn = new Button(buttons, SWT.PUSH);
        renameBtn.setText("Rename");
        renameBtn.setFont(appFont);
        renameBtn.addListener(SWT.Selection, e -> {
            String newName = nameText.getText().trim();
            if (!newName.isEmpty() && !newName.equals(branch)) {
                String result = runGitWithExitCode("branch", "-m", branch, newName);
                if (result != null) {
                    if (branch.equals(currentBranch)) currentBranch = newName;
                    statusBarLeft.setText("Renamed: " + branch + " → " + newName);
                    loadBranches();
                    loadCommits();
                } else {
                    statusBarLeft.setText("Rename failed");
                }
            }
            dlg.close();
        });

        dlg.setSize(400, 150);
        dlg.open();
    }

    private void showMergeDialog(String fromBranch) {
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Merge Branch");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label intoLabel = new Label(dlg, SWT.NONE);
        intoLabel.setText("Merge into: " + currentBranch + " (current)");
        intoLabel.setFont(appFont);
        intoLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(dlg, SWT.NONE).setText("From:");
        Combo fromCombo = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        fromCombo.setFont(appFont);
        fromCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Add all branches except current
        for (String branch : branchToCommit.keySet()) {
            if (!branch.equals(currentBranch) && !branch.startsWith("tag:")) {
                fromCombo.add(branch);
            }
        }
        if (fromBranch != null) {
            int idx = fromCombo.indexOf(fromBranch);
            if (idx >= 0) fromCombo.select(idx);
        }
        if (fromCombo.getSelectionIndex() < 0 && fromCombo.getItemCount() > 0) {
            fromCombo.select(0);
        }

        // Strategy radio buttons
        new Label(dlg, SWT.NONE).setText("Strategy:");
        Composite stratGroup = new Composite(dlg, SWT.NONE);
        stratGroup.setLayout(new GridLayout(3, false));
        Button defaultRadio = new Button(stratGroup, SWT.RADIO);
        defaultRadio.setText("Default");
        defaultRadio.setSelection(true);
        defaultRadio.setFont(appFont);
        Button noFfRadio = new Button(stratGroup, SWT.RADIO);
        noFfRadio.setText("--no-ff");
        noFfRadio.setFont(appFont);
        Button squashRadio = new Button(stratGroup, SWT.RADIO);
        squashRadio.setText("--squash");
        squashRadio.setFont(appFont);

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button mergeBtn = new Button(buttons, SWT.PUSH);
        mergeBtn.setText("Merge");
        mergeBtn.setFont(appFont);
        mergeBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        mergeBtn.addListener(SWT.Selection, e -> {
            String from = fromCombo.getText();
            if (from.isEmpty()) return;
            List<String> args = new ArrayList<>();
            args.add("merge");
            if (noFfRadio.getSelection()) args.add("--no-ff");
            if (squashRadio.getSelection()) args.add("--squash");
            args.add(from);
            String result = runGitWithExitCode(args.toArray(new String[0]));
            dlg.close();
            if (result != null) {
                statusBarLeft.setText("Merged " + from + " into " + currentBranch);
                loadBranches();
                loadCommits();
            } else {
                handleConflictAfterOp("Merge");
            }
        });

        dlg.setSize(420, 210);
        dlg.open();
    }

    private void showStashDialog() {
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Stash Changes");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        new Label(dlg, SWT.NONE).setText("Message:");
        Text msgText = new Text(dlg, SWT.BORDER | SWT.SINGLE);
        msgText.setFont(appFont);
        msgText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        msgText.setMessage("(optional)");

        Button untrackedCheck = new Button(dlg, SWT.CHECK);
        untrackedCheck.setText("Include untracked files");
        untrackedCheck.setFont(appFont);
        untrackedCheck.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button stashBtn = new Button(buttons, SWT.PUSH);
        stashBtn.setText("Stash");
        stashBtn.setFont(appFont);
        stashBtn.addListener(SWT.Selection, e -> {
            List<String> args = new ArrayList<>();
            args.add("stash");
            args.add("push");
            if (untrackedCheck.getSelection()) args.add("-u");
            String msg = msgText.getText().trim();
            if (!msg.isEmpty()) { args.add("-m"); args.add(msg); }
            String result = runGitWithExitCode(args.toArray(new String[0]));
            if (result != null) {
                statusBarLeft.setText("Changes stashed");
                loadBranches();
                loadCommits();
            } else {
                statusBarLeft.setText("Stash failed");
            }
            dlg.close();
        });

        dlg.setSize(400, 160);
        dlg.open();
    }

    private void doFetch() {
        if (!requireRemote()) return;
        List<String> remotes = getRemotes();
        if (remotes.size() <= 1) {
            // Single remote — fetch directly without dialog
            runFetch(null);
            return;
        }

        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Fetch");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        new Label(dlg, SWT.NONE).setText("Remote:");
        Combo remoteCombo = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        remoteCombo.setFont(appFont);
        remoteCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        remoteCombo.add("All remotes");
        for (String r : remotes) remoteCombo.add(r);
        remoteCombo.select(0);

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button fetchBtn2 = new Button(buttons, SWT.PUSH);
        fetchBtn2.setText("Fetch");
        fetchBtn2.setFont(appFont);
        fetchBtn2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        fetchBtn2.addListener(SWT.Selection, e -> {
            int selIdx = remoteCombo.getSelectionIndex();
            String remote = selIdx == 0 ? null : remoteCombo.getText();
            dlg.close();
            runFetch(remote);
        });

        dlg.setSize(380, 130);
        dlg.open();
    }

    /** Fetch from {@code remote}, or from every remote when it is null. */
    private void runFetch(String remote) {
        String what = remote != null ? remote : "all remotes";
        runRemoteOp("Fetching " + what + "...",
                () -> remote == null
                        ? runGitRemote("fetch", "--all")
                        : runGitRemote("fetch", remote),
                r -> {
                    if (r.ok()) {
                        statusBarLeft.setText("Fetch complete");
                        loadBranches();
                        loadCommits();
                    } else {
                        showGitError("Fetch Failed",
                                "Could not fetch from " + what + ".", r);
                    }
                });
    }

    private void doPull() {
        if (!requireRemote() || !requireBranch("pull")) return;
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Pull");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        new Label(dlg, SWT.NONE).setText("Remote:");
        Combo remoteCombo = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        remoteCombo.setFont(appFont);
        remoteCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        List<String> remotes = getRemotes();
        for (String r : remotes) remoteCombo.add(r);
        // Try to detect tracking remote for current branch
        String trackingRemote = runGit("config", "--get", "branch." + currentBranch + ".remote").trim();
        int defaultIdx = !trackingRemote.isEmpty() ? remotes.indexOf(trackingRemote) : remotes.indexOf("origin");
        remoteCombo.select(defaultIdx >= 0 ? defaultIdx : 0);

        new Label(dlg, SWT.NONE); // spacer
        Button rebaseCheck = new Button(dlg, SWT.CHECK);
        rebaseCheck.setText("Rebase");
        rebaseCheck.setFont(appFont);

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(2, true));

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button pullBtn2 = new Button(buttons, SWT.PUSH);
        pullBtn2.setText("Pull");
        pullBtn2.setFont(appFont);
        pullBtn2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        pullBtn2.addListener(SWT.Selection, e -> {
            String remote = remoteCombo.getText();
            boolean rebase = rebaseCheck.getSelection();
            dlg.close();
            String branch = currentBranch;

            List<String> args = new ArrayList<>();
            args.add("pull");
            if (rebase) args.add("--rebase");
            PushTarget t = resolvePushTarget(remote, branch);
            if (t.isNew) {
                // No upstream on this remote — name the ref explicitly.
                args.add(remote);
                args.add(branch);
            } else {
                // Upstream is configured: let git use it. Naming the local branch
                // here would pull the wrong ref whenever the names differ.
                args.add(remote);
                args.add(t.remoteBranch);
            }
            String[] cmd = args.toArray(new String[0]);

            runRemoteOp("Pulling from " + remote + (rebase ? " (rebase)..." : "..."),
                    () -> runGitRemote(cmd),
                    r -> {
                        loadBranches();
                        loadCommits();
                        if (r.ok()) {
                            statusBarLeft.setText("Pull complete");
                        } else if (looksLikeConflict(r)) {
                            // A non-zero pull is only sometimes a conflict. Reporting
                            // every failure as one used to send people to the Working
                            // Tree to resolve conflicts that were not there.
                            handleConflictAfterOp("Pull");
                        } else {
                            handlePullFailure(remote, r);
                        }
                    });
        });

        dlg.setSize(380, 160);
        dlg.open();
    }

    private void doPush() {
        if (!requireRemote() || !requireBranch("push")) return;
        final String branch = currentBranch;

        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dlg.setText("Push");
        GridLayout gl = new GridLayout(2, false);
        gl.marginHeight = 12;
        gl.marginWidth = 16;
        gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        new Label(dlg, SWT.NONE).setText("Remote:");
        Combo remoteCombo = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        remoteCombo.setFont(appFont);
        remoteCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        List<String> remotes = getRemotes();
        for (String r : remotes) remoteCombo.add(r);
        String trackedRemote = runGit("config", "--get", "branch." + branch + ".remote").trim();
        int defaultIdx = !trackedRemote.isEmpty() ? remotes.indexOf(trackedRemote) : remotes.indexOf("origin");
        remoteCombo.select(defaultIdx >= 0 ? defaultIdx : 0);

        new Label(dlg, SWT.NONE).setText("Branch:");
        Label branchLabel = new Label(dlg, SWT.NONE);
        branchLabel.setFont(appFont);
        branchLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Spell out where this is actually going — the destination is not always
        // a branch of the same name.
        new Label(dlg, SWT.NONE).setText("Target:");
        Label targetLabel = new Label(dlg, SWT.NONE);
        targetLabel.setFont(appFont);
        targetLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(dlg, SWT.NONE); // spacer
        Button upstreamCheck = new Button(dlg, SWT.CHECK);
        upstreamCheck.setText("Set as upstream (-u)");
        upstreamCheck.setFont(appFont);

        new Label(dlg, SWT.NONE); // spacer
        Button pushTagsCheck = new Button(dlg, SWT.CHECK);
        pushTagsCheck.setText("Push tags");
        pushTagsCheck.setFont(appFont);

        Runnable refreshTarget = () -> {
            String remote = remoteCombo.getText();
            PushTarget t = resolvePushTarget(remote, branch);
            branchLabel.setText(branch);
            targetLabel.setText(t.trackingRef() + (t.isNew ? "   (new branch)" : ""));
            // -u only makes sense when there is no upstream yet; ticking it
            // otherwise silently rewrites the branch's tracking config.
            upstreamCheck.setSelection(t.isNew);
            dlg.layout(true, true);
        };
        refreshTarget.run();
        remoteCombo.addListener(SWT.Selection, e -> refreshTarget.run());

        Composite buttons = new Composite(dlg, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        buttons.setLayout(new GridLayout(3, false));

        Button forceBtn = new Button(buttons, SWT.PUSH);
        forceBtn.setText("Force push…");
        forceBtn.setFont(appFont);
        forceBtn.addListener(SWT.Selection, e -> {
            String remote = remoteCombo.getText();
            dlg.close();
            confirmForcePush(remote, branch);
        });

        Button cancelBtn = new Button(buttons, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        Button pushBtn2 = new Button(buttons, SWT.PUSH);
        pushBtn2.setText("Push");
        pushBtn2.setFont(appFont);
        pushBtn2.addListener(SWT.Selection, e -> {
            String remote = remoteCombo.getText();
            boolean pushTags = pushTagsCheck.getSelection();
            boolean setUpstream = upstreamCheck.getSelection();
            dlg.close();
            startPush(remote, branch, pushTags, setUpstream);
        });

        dlg.setDefaultButton(pushBtn2);
        dlg.setSize(440, 250);
        dlg.open();
    }

    /** Show remote-selection dialog for push, or push directly if only one remote. */
    private void pushToRemote(String title, java.util.function.BiConsumer<String, String> pushAction, String pushRef) {
        if (!requireRemote()) return;
        List<String> remotes = getRemotes();
        if (remotes.size() == 1) {
            pushAction.accept(remotes.get(0), pushRef);
            return;
        }
        Shell rdlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        rdlg.setText(title);
        GridLayout rgl = new GridLayout(2, false);
        rgl.marginHeight = 12;
        rgl.marginWidth = 16;
        rgl.verticalSpacing = 8;
        rdlg.setLayout(rgl);

        new Label(rdlg, SWT.NONE).setText("Remote:");
        Combo rCombo = new Combo(rdlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        rCombo.setFont(appFont);
        rCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        for (String r : remotes) rCombo.add(r);
        int oi = remotes.indexOf("origin");
        rCombo.select(oi >= 0 ? oi : 0);

        Composite rBtns = new Composite(rdlg, SWT.NONE);
        rBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
        rBtns.setLayout(new GridLayout(2, true));

        Button rCancel = new Button(rBtns, SWT.PUSH);
        rCancel.setText("Cancel");
        rCancel.setFont(appFont);
        rCancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        rCancel.addListener(SWT.Selection, e -> rdlg.close());

        Button rPush = new Button(rBtns, SWT.PUSH);
        rPush.setText("Push");
        rPush.setFont(appFont);
        rPush.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        rPush.addListener(SWT.Selection, e -> {
            String remote = rCombo.getText();
            rdlg.close();
            pushAction.accept(remote, pushRef);
        });

        rdlg.setSize(350, 120);
        rdlg.open();
    }

    // ── Sidebar context menu ────────────────────────────────────────

    private void createSidebarContextMenu() {
        Menu menu = new Menu(branchTree);
        branchTree.setMenu(menu);

        // Force-select item under cursor on right-click (macOS doesn't always do this)
        branchTree.addListener(SWT.MenuDetect, e -> {
            Point pt = branchTree.toControl(e.x, e.y);
            TreeItem item = branchTree.getItem(pt);
            if (item != null) {
                branchTree.setSelection(item);
            }
        });

        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();

            TreeItem[] sel = branchTree.getSelection();
            if (sel.length == 0) return;
            TreeItem item = sel[0];
            String type = (String) item.getData("type");
            if (type == null) return;

            if ("section".equals(type)) {
                if (item.getText().startsWith("Tags")) {
                    MenuItem createTag = new MenuItem(menu, SWT.PUSH);
                    createTag.setText("Create Tag...");
                    createTag.addListener(SWT.Selection, ev -> showCreateTagDialog("HEAD"));
                }
                return;
            }

            String branch = (String) item.getData("branch");
            String ref = (String) item.getData("ref");

            if ("local".equals(type)) {
                boolean isCurrent = branch != null && branch.equals(currentBranch);

                if (!isCurrent) {
                    MenuItem checkout = new MenuItem(menu, SWT.PUSH);
                    checkout.setText("Checkout");
                    checkout.addListener(SWT.Selection, ev -> doCheckout(branch));
                    new MenuItem(menu, SWT.SEPARATOR);
                }

                MenuItem merge = new MenuItem(menu, SWT.PUSH);
                merge.setText("Merge into " + currentBranch + "...");
                merge.addListener(SWT.Selection, ev -> showMergeDialog(branch));

                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem rename = new MenuItem(menu, SWT.PUSH);
                rename.setText("Rename Branch...");
                rename.addListener(SWT.Selection, ev -> showRenameBranchDialog(branch));

                if (!isCurrent) {
                    MenuItem delete = new MenuItem(menu, SWT.PUSH);
                    delete.setText("Delete Branch...");
                    delete.addListener(SWT.Selection, ev -> showDeleteBranchDialog(branch));
                }

                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem pushItem = new MenuItem(menu, SWT.PUSH);
                pushItem.setText("Push Branch...");
                pushItem.addListener(SWT.Selection, ev -> {
                    pushToRemote("Push Branch: " + branch, (remote, branchRef) ->
                        runRemoteOp("Pushing " + branchRef + " to " + remote + "...",
                                () -> runGitRemote("push", "-u", remote, branchRef),
                                r -> {
                                    if (r.ok()) {
                                        statusBarLeft.setText("Pushed " + branchRef + " to " + remote);
                                        loadBranches();
                                    } else {
                                        showGitError("Push Failed",
                                                "Could not push " + branchRef + " to " + remote + ".", r);
                                    }
                                }), branch);
                });

            } else if ("remote".equals(type)) {
                MenuItem checkout = new MenuItem(menu, SWT.PUSH);
                checkout.setText("Checkout as Local Branch...");
                String localName = branch != null && branch.contains("/")
                        ? branch.substring(branch.indexOf('/') + 1)
                        : branch;
                checkout.addListener(SWT.Selection, ev -> showCheckoutRemoteDialog(branch, localName));

                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem merge = new MenuItem(menu, SWT.PUSH);
                merge.setText("Merge into " + currentBranch + "...");
                merge.addListener(SWT.Selection, ev -> showMergeDialog(branch));

                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem deleteRemote = new MenuItem(menu, SWT.PUSH);
                deleteRemote.setText("Delete Remote Branch...");
                deleteRemote.addListener(SWT.Selection, ev -> {
                    MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
                    confirm.setText("Delete Remote Branch");
                    confirm.setMessage("Delete " + branch + " from remote?\nThis cannot be undone.");
                    if (confirm.open() != SWT.YES) return;
                    String remoteName = branch.contains("/") ? branch.substring(branch.indexOf('/') + 1) : branch;
                    String remote = branch.contains("/") ? branch.substring(0, branch.indexOf('/')) : "origin";
                    runRemoteOp("Deleting " + remoteName + " on " + remote + "...",
                            () -> runGitRemote("push", remote, "--delete", remoteName),
                            r -> {
                                if (r.ok()) {
                                    statusBarLeft.setText("Deleted remote branch: " + branch);
                                    loadBranches();
                                } else {
                                    showGitError("Delete Failed",
                                            "Could not delete " + remoteName + " on " + remote + ".", r);
                                }
                            });
                });

            } else if ("stash".equals(type) && ref != null) {
                MenuItem apply = new MenuItem(menu, SWT.PUSH);
                apply.setText("Apply Stash");
                apply.addListener(SWT.Selection, ev -> {
                    String result = runGitWithExitCode("stash", "apply", ref);
                    if (result != null) {
                        statusBarLeft.setText("Applied " + ref);
                        loadBranches();
                        loadCommits();
                    } else {
                        handleConflictAfterOp("Stash apply");
                    }
                });

                MenuItem pop = new MenuItem(menu, SWT.PUSH);
                pop.setText("Pop Stash");
                pop.addListener(SWT.Selection, ev -> {
                    String result = runGitWithExitCode("stash", "pop", ref);
                    if (result != null) {
                        statusBarLeft.setText("Popped " + ref);
                        loadBranches();
                        loadCommits();
                    } else {
                        handleConflictAfterOp("Stash pop");
                    }
                });

                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem drop = new MenuItem(menu, SWT.PUSH);
                drop.setText("Drop Stash");
                drop.addListener(SWT.Selection, ev -> {
                    MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
                    confirm.setText("Drop Stash");
                    confirm.setMessage("Drop " + ref + "?\nThis cannot be undone.");
                    if (confirm.open() != SWT.YES) return;
                    String result = runGitWithExitCode("stash", "drop", ref);
                    statusBarLeft.setText(result != null ? "Dropped " + ref : "Drop failed");
                    loadBranches();
                });

            } else if ("tag".equals(type)) {
                String tagName = (String) item.getData("tag");

                MenuItem createTag = new MenuItem(menu, SWT.PUSH);
                createTag.setText("Create Tag...");
                createTag.addListener(SWT.Selection, ev -> showCreateTagDialog("HEAD"));

                MenuItem pushTag = new MenuItem(menu, SWT.PUSH);
                pushTag.setText("Push Tag to Remote...");
                pushTag.addListener(SWT.Selection, ev -> {
                    pushToRemote("Push Tag: " + tagName, (remote, tag) ->
                        runRemoteOp("Pushing tag " + tag + " to " + remote + "...",
                                () -> runGitRemote("push", remote, tag),
                                r -> {
                                    if (r.ok()) {
                                        statusBarLeft.setText("Pushed tag " + tag + " to " + remote);
                                    } else {
                                        showGitError("Push Tag Failed",
                                                "Could not push tag " + tag + " to " + remote + ".", r);
                                    }
                                }), tagName);
                });

                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem deleteTag = new MenuItem(menu, SWT.PUSH);
                deleteTag.setText("Delete Tag...");
                deleteTag.addListener(SWT.Selection, ev -> {
                    MessageBox confirm = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
                    confirm.setText("Delete Tag");
                    confirm.setMessage("Delete tag: " + tagName + "?");
                    if (confirm.open() != SWT.YES) return;
                    String result = runGitWithExitCode("tag", "-d", tagName);
                    statusBarLeft.setText(result != null ? "Deleted tag: " + tagName : "Delete failed");
                    loadBranches();
                    loadCommits();
                });
            }
        });
    }

    // ── Context menu for files table ─────────────────────────────────

    private void createFilesContextMenu() {
        Menu menu = new Menu(filesTable);
        filesTable.setMenu(menu);

        menu.addListener(SWT.Show, e -> {
            // Clear existing items
            for (MenuItem mi : menu.getItems()) mi.dispose();

            // Collect selected FileChange items
            TableItem[] sel = filesTable.getSelection();
            List<FileChange> selectedFiles = new ArrayList<>();
            for (TableItem ti : sel) {
                Object d = ti.getData();
                if (d instanceof FileChange) selectedFiles.add((FileChange) d);
            }
            if (selectedFiles.isEmpty()) return;

            boolean multiSelect = selectedFiles.size() > 1;
            FileChange fc = selectedFiles.get(0); // primary selection for single-file ops

            if (isWorkingTreeSelected) {
                if (!multiSelect && fc.status == 'U') {
                    // Conflicted file: special menu (single only)
                    MenuItem resolveItem = new MenuItem(menu, SWT.PUSH);
                    resolveItem.setText("Mark as Resolved (Stage)");
                    resolveItem.addListener(SWT.Selection, ev -> {
                        runGit("add", fc.path);
                        reloadWorkingTree();
                    });

                    new MenuItem(menu, SWT.SEPARATOR);

                    MenuItem openCompare = new MenuItem(menu, SWT.PUSH);
                    openCompare.setText("Open in FileCompare");
                    openCompare.addListener(SWT.Selection, ev -> openSelectedFile());

                    new MenuItem(menu, SWT.SEPARATOR);

                    MenuItem checkoutOurs = new MenuItem(menu, SWT.PUSH);
                    checkoutOurs.setText("Resolve using Ours");
                    checkoutOurs.addListener(SWT.Selection, ev -> {
                        runGitWithExitCode("checkout", "--ours", fc.path);
                        runGit("add", fc.path);
                        reloadWorkingTree();
                    });

                    MenuItem checkoutTheirs = new MenuItem(menu, SWT.PUSH);
                    checkoutTheirs.setText("Resolve using Theirs");
                    checkoutTheirs.addListener(SWT.Selection, ev -> {
                        runGitWithExitCode("checkout", "--theirs", fc.path);
                        runGit("add", fc.path);
                        reloadWorkingTree();
                    });
                } else {
                    String suffix = multiSelect ? " (" + selectedFiles.size() + " files)" : "";

                    // Stage/Unstage
                    boolean anyUnstaged = selectedFiles.stream().anyMatch(f -> !f.staged && f.status != 'U');
                    boolean anyStaged = selectedFiles.stream().anyMatch(f -> f.staged);
                    if (anyUnstaged) {
                        MenuItem stageItem = new MenuItem(menu, SWT.PUSH);
                        stageItem.setText("Stage" + suffix);
                        stageItem.addListener(SWT.Selection, ev -> {
                            stageFiles(selectedFiles.stream().filter(f -> !f.staged && f.status != 'U')
                                    .collect(Collectors.toList()));
                        });
                    }
                    if (anyStaged) {
                        MenuItem unstageItem = new MenuItem(menu, SWT.PUSH);
                        unstageItem.setText("Unstage" + suffix);
                        unstageItem.addListener(SWT.Selection, ev -> {
                            unstageFiles(selectedFiles.stream().filter(f -> f.staged)
                                    .collect(Collectors.toList()));
                        });
                    }

                    new MenuItem(menu, SWT.SEPARATOR);

                    // Discard
                    MenuItem discardItem = new MenuItem(menu, SWT.PUSH);
                    discardItem.setText("Discard Changes" + suffix);
                    discardItem.addListener(SWT.Selection, ev -> discardFiles(selectedFiles));

                    // Delete file (unstaged only, file must exist on disk)
                    List<FileChange> deletable = selectedFiles.stream()
                            .filter(f -> !f.staged && f.status != 'D')
                            .collect(Collectors.toList());
                    if (!deletable.isEmpty()) {
                        MenuItem deleteItem = new MenuItem(menu, SWT.PUSH);
                        deleteItem.setText("Delete File" + (deletable.size() > 1 ? "s (" + deletable.size() + ")" : ""));
                        deleteItem.addListener(SWT.Selection, ev -> deleteFiles(deletable));
                    }
                }
            }

            // Always available: Open in FileCompare
            new MenuItem(menu, SWT.SEPARATOR);
            MenuItem openItem = new MenuItem(menu, SWT.PUSH);
            openItem.setText("Open in FileCompare");
            openItem.addListener(SWT.Selection, ev -> openSelectedFile());

            new MenuItem(menu, SWT.SEPARATOR);
            if (!multiSelect) {
                MenuItem fileHistory = new MenuItem(menu, SWT.PUSH);
                fileHistory.setText("Show File History");
                fileHistory.addListener(SWT.Selection, ev -> {
                    showFileHistoryDialog(fc.path);
                });
            }

            // Add to .gitignore (only for working tree, single file)
            if (isWorkingTreeSelected && !multiSelect) {
                new MenuItem(menu, SWT.SEPARATOR);
                MenuItem gitignoreItem = new MenuItem(menu, SWT.CASCADE);
                gitignoreItem.setText("Add to .gitignore");
                Menu gitignoreSub = new Menu(menu);
                gitignoreItem.setMenu(gitignoreSub);

                // Option 1: exact file path
                MenuItem exactItem = new MenuItem(gitignoreSub, SWT.PUSH);
                exactItem.setText(fc.path);
                exactItem.addListener(SWT.Selection, ev -> addToGitignore(fc.path));

                // Option 2: *.ext (only if file has an extension)
                String fileName = fc.name;
                int dotIdx = fileName.lastIndexOf('.');
                if (dotIdx > 0) {
                    String ext = fileName.substring(dotIdx);
                    MenuItem extItem = new MenuItem(gitignoreSub, SWT.PUSH);
                    extItem.setText("*" + ext);
                    extItem.addListener(SWT.Selection, ev -> addToGitignore("*" + ext));
                }

                // Option 3: directory/ (only if file is in a subdirectory)
                if (!fc.directory.equals(".")) {
                    String topDir = fc.directory.contains("/")
                            ? fc.directory.substring(0, fc.directory.indexOf('/'))
                            : fc.directory;
                    MenuItem dirItem = new MenuItem(gitignoreSub, SWT.PUSH);
                    dirItem.setText(topDir + "/");
                    dirItem.addListener(SWT.Selection, ev -> addToGitignore(topDir + "/"));
                }
            }
        });
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0; // binary files show "-"
        }
    }

    private void populateFilesTable() {
        populateFilesTable(true);
    }

    private void populateFilesTable(boolean refreshPreview) {
        // Remember selection + scroll position so a rebuild doesn't jump around
        Set<Object> selectedKeys = new HashSet<>();
        for (TableItem it : filesTable.getSelection()) {
            Object d = it.getData();
            if (d instanceof FileChange) selectedKeys.add("file:" + ((FileChange) d).path);
            else if (d instanceof String) selectedKeys.add(d);
        }
        int topIndex = filesTable.getTopIndex();

        filesTable.setRedraw(false);
        filesTable.removeAll();
        sortCurrentFiles();

        if (isWorkingTreeSelected) {
            // Count conflicted, staged, and unstaged
            int conflictedCount = 0, stagedCount = 0, unstagedCount = 0;
            for (FileChange fc : currentFiles) {
                if (fc.status == 'U') conflictedCount++;
                else if (fc.staged) stagedCount++;
                else unstagedCount++;
            }

            // Section: Conflicted (only if there are conflicts)
            if (conflictedCount > 0) {
                String conflictArrow = conflictedCollapsed ? "\u25B8" : "\u25BE";
                TableItem conflictHeader = new TableItem(filesTable, SWT.NONE);
                conflictHeader.setText(0, conflictArrow);
                conflictHeader.setText(1, "Conflicted (" + conflictedCount + " files)");
                conflictHeader.setBackground(conflictBg);
                conflictHeader.setForeground(conflictFg);
                conflictHeader.setData("section-conflicted");

                if (!conflictedCollapsed) {
                    for (FileChange fc : currentFiles) {
                        if (fc.status != 'U') continue;
                        addFileTableItem(fc);
                    }
                }
            }

            // Section: Staged
            String stagedArrow = stagedCollapsed ? "\u25B8" : "\u25BE";
            TableItem stagedHeader = new TableItem(filesTable, SWT.NONE);
            stagedHeader.setText(0, stagedArrow);
            stagedHeader.setText(1, "Staged (" + stagedCount + " files)");
            stagedHeader.setBackground(sectionBg);
            stagedHeader.setForeground(sectionFg);
            stagedHeader.setData("section-staged");

            if (!stagedCollapsed) {
                for (FileChange fc : currentFiles) {
                    if (!fc.staged || fc.status == 'U') continue;
                    addFileTableItem(fc);
                }
            }

            // Section: Unstaged
            String unstagedArrow = unstagedCollapsed ? "\u25B8" : "\u25BE";
            TableItem unstagedHeader = new TableItem(filesTable, SWT.NONE);
            unstagedHeader.setText(0, unstagedArrow);
            unstagedHeader.setText(1, "Unstaged (" + unstagedCount + " files)");
            unstagedHeader.setBackground(sectionBg);
            unstagedHeader.setForeground(sectionFg);
            unstagedHeader.setData("section-unstaged");

            if (!unstagedCollapsed) {
                for (FileChange fc : currentFiles) {
                    if (fc.staged || fc.status == 'U') continue;
                    addFileTableItem(fc);
                }
            }
        } else {
            for (FileChange fc : currentFiles) {
                addFileTableItem(fc);
            }
            // Header for a commit is not updated by updateCommitMeta — set it here
            filesHeaderTitle.setText("CHANGED FILES  (" + currentFiles.size() + " files)");
        }

        updateFilesSummary();

        // Restore previous selection (by path / section); fall back to first file
        List<Integer> restoreIdx = new ArrayList<>();
        if (!selectedKeys.isEmpty()) {
            for (int i = 0; i < filesTable.getItemCount(); i++) {
                Object d = filesTable.getItem(i).getData();
                Object key = d instanceof FileChange ? "file:" + ((FileChange) d).path : d;
                if (selectedKeys.contains(key)) restoreIdx.add(i);
            }
        }
        if (!restoreIdx.isEmpty()) {
            if (filesTable.getItemCount() > 0) {
                filesTable.setTopIndex(Math.min(topIndex, filesTable.getItemCount() - 1));
            }
            int[] idx = new int[restoreIdx.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = restoreIdx.get(i);
            filesTable.setSelection(idx);
            filesTable.setRedraw(true);
            if (refreshPreview) previewSelectedFile();
        } else {
            filesTable.setRedraw(true);
            // Select first actual file and preview it
            selectFirstFile();
        }
    }

    // Colored per-status counts in the files header: ● 5 modified  ● 2 added  ● 1 deleted
    private void updateFilesSummary() {
        int m = 0, a = 0, d = 0;
        for (FileChange fc : currentFiles) {
            switch (fc.status) {
                case 'M': case 'R': case 'C': m++; break;
                case 'A': case '?': a++; break;
                case 'D': d++; break;
                default: break; // conflicts are announced by their own section/banner
            }
        }
        summaryModified.setText(m > 0 ? "● " + m + " modified" : "");
        summaryAdded.setText(a > 0 ? "● " + a + " added" : "");
        summaryDeleted.setText(d > 0 ? "● " + d + " deleted" : "");
        filesHeaderTitle.getParent().layout();
    }

    private void sortCurrentFiles() {
        Comparator<FileChange> cmp;
        if ("modified".equals(fileSortMode)) {
            cmp = Comparator.comparingLong((FileChange fc) -> fc.lastModified)
                    .thenComparing(fc -> fc.name, String.CASE_INSENSITIVE_ORDER);
        } else if ("status".equals(fileSortMode)) {
            cmp = Comparator.comparingInt((FileChange fc) -> statusSortRank(fc.status))
                    .thenComparing(fc -> fc.name, String.CASE_INSENSITIVE_ORDER);
        } else {
            cmp = Comparator.comparing((FileChange fc) -> fc.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(fc -> fc.directory, String.CASE_INSENSITIVE_ORDER);
        }
        if (!fileSortAscending) cmp = cmp.reversed();
        currentFiles.sort(cmp);
    }

    // A → M → D → R → untracked, so the header letters group visually
    private int statusSortRank(char status) {
        switch (status) {
            case 'A': return 0;
            case 'M': return 1;
            case 'D': return 2;
            case 'R': return 3;
            case 'C': return 4;
            case '?': return 5;
            default:  return 6;
        }
    }

    private void onFileSortColumn(String mode) {
        if (mode.equals(fileSortMode)) {
            fileSortAscending = !fileSortAscending;
        } else {
            fileSortMode = mode;
            // Name: A→Z first; Status: A/M/D order; Modified: newest first
            fileSortAscending = !"modified".equals(mode);
        }
        PREFS.put("fileSortMode", fileSortMode);
        PREFS.putBoolean("fileSortAsc", fileSortAscending);
        updateFileSortIndicator();
        populateFilesTable(false);
    }

    private void updateFileSortIndicator() {
        TableColumn col = "modified".equals(fileSortMode) ? filesColModified
                : "status".equals(fileSortMode) ? filesColStatus : filesColName;
        filesTable.setSortColumn(col);
        filesTable.setSortDirection(fileSortAscending ? SWT.UP : SWT.DOWN);
    }

    private void addFileTableItem(FileChange fc) {
        TableItem item = new TableItem(filesTable, SWT.NONE);
        item.setChecked(fc.staged);
        Image chip = statusChips.get(fc.status);
        if (chip != null) item.setImage(0, chip);
        else item.setText(0, String.valueOf(fc.status));
        item.setText(1, fc.name);
        item.setText(2, fc.directory);
        item.setText(3, formatMTime(fc.lastModified));
        item.setText(4, fc.additions > 0 ? "+" + fc.additions : "");
        item.setText(5, fc.deletions > 0 ? "-" + fc.deletions : "");

        // Status is also encoded on the file name (the eye's first target);
        // deleted files fade to gray, conflicts keep the loud row background
        switch (fc.status) {
            case 'M':
                item.setForeground(1, modifiedFg);
                break;
            case 'A':
                item.setForeground(1, addedFg);
                break;
            case 'D':
                item.setForeground(1, metaFg);
                break;
            case 'R':
            case 'C':
                item.setForeground(1, renamedFg);
                break;
            case 'U':
                item.setForeground(1, conflictFg);
                item.setBackground(conflictBg);
                break;
            default:
                break; // '?' untracked keeps the default color
        }

        // Additions/deletions coloring
        item.setForeground(3, metaFg);
        item.setForeground(4, additionsFg);
        item.setForeground(5, deletionsFg);

        item.setData(fc);
    }

    private String statusTooltip(FileChange fc) {
        String word;
        switch (fc.status) {
            case 'M': word = "Modified"; break;
            case 'A': word = "Added"; break;
            case 'D': word = "Deleted"; break;
            case 'R': word = "Renamed from " + fc.oldPath; break;
            case 'C': word = "Copied from " + fc.oldPath; break;
            case '?': word = "Untracked"; break;
            case 'U': word = "Conflicted"; break;
            default:  word = String.valueOf(fc.status); break;
        }
        if (isWorkingTreeSelected && fc.status != 'U' && fc.status != '?') {
            word += fc.staged ? " (staged)" : " (unstaged)";
        }
        return word;
    }

    private void selectFirstFile() {
        // Find first non-section item
        for (int i = 0; i < filesTable.getItemCount(); i++) {
            Object data = filesTable.getItem(i).getData();
            if (data instanceof FileChange) {
                filesTable.setSelection(i);
                previewSelectedFile();
                return;
            }
        }
        // No files — clear diff panel
        if (embeddedCompare != null) {
            embeddedCompare.setContent("", "", "Left file", "Right file");
        }
    }

    private void updateDetail(CommitInfo ci) {
        // Full commit message
        String fullMsg = runGit("log", "-1", "--format=%B", ci.fullHash).trim();
        detailMsg.setText(fullMsg);

        // Total additions/deletions
        int totalAdd = 0, totalDel = 0;
        for (FileChange fc : currentFiles) {
            totalAdd += fc.additions;
            totalDel += fc.deletions;
        }

        String parent = ci.parentHashes.isEmpty() ? "(root)" : ci.parentHashes.get(0).substring(0, Math.min(7, ci.parentHashes.get(0).length()));
        detailMeta.setText(ci.shortHash + "  " + ci.author + "  " + ci.date
                + "  parent: " + parent
                + "  +" + totalAdd + "  -" + totalDel);
    }

    private void updateStatusBar() {
        int[] selIndices = commitTable.getSelectionIndices();
        int commitIdx = selIndices.length > 0 ? selIndices[0] : -1;
        int total = filteredCommits.size();

        String searchInfo;
        if (!activeSearchQuery.isEmpty()) {
            int mode = searchModeCombo != null ? searchModeCombo.getSelectionIndex() : SEARCH_ALL;
            String modeLabel = mode == SEARCH_MESSAGE ? "message" : mode == SEARCH_AUTHOR ? "author" : "file";
            searchInfo = total + " commits matching " + modeLabel + ": \"" + activeSearchQuery + "\"";
        } else {
            searchInfo = total + " commits";
        }

        String selInfo;
        if (selIndices.length > 1) {
            selInfo = "  |  " + selIndices.length + " commits selected";
        } else {
            selInfo = commitIdx >= 0 ? "  |  Commit " + (commitIdx + 1) + " / " + total : "";
        }

        String rightText = "Branch: " + currentBranch
                + "  |  " + searchInfo
                + selInfo;

        if (isWorkingTreeSelected) {
            int conflictedCount = 0, stagedCount = 0, unstagedCount = 0;
            for (FileChange fc : currentFiles) {
                if (fc.status == 'U') conflictedCount++;
                else if (fc.staged) stagedCount++;
                else unstagedCount++;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Branch: ").append(currentBranch).append("  |  ");
            if (conflictedCount > 0) sb.append(conflictedCount).append(" conflicted, ");
            sb.append(stagedCount).append(" staged, ").append(unstagedCount).append(" unstaged");
            sb.append("  |  Space: stage/unstage  |  Del: discard");
            rightText = sb.toString();
        }

        statusBarRight.setText(rightText);
        statusBarRight.getParent().layout();
    }

    // ── FileCompare integration ──────────────────────────────────────

    private void previewSelectedFile() {
        if (embeddedCompare == null) return;
        int fileIdx = filesTable.getSelectionIndex();
        if (fileIdx < 0 || fileIdx >= filesTable.getItemCount()) return;
        int[] commitIndices = commitTable.getSelectionIndices();
        if (commitIndices.length == 0) return;
        int commitIdx = commitIndices[0];
        if (commitIdx < 0 || commitIdx >= filteredCommits.size()) return;

        // Skip section divider rows
        Object data = filesTable.getItem(fileIdx).getData();
        if (!(data instanceof FileChange)) return;

        FileChange fc = (FileChange) data;
        CommitInfo ci = filteredCommits.get(commitIdx);

        String leftContent = "";
        String rightContent = "";
        String leftLbl = "Left file";
        String rightLbl = "Right file";

        if (ci.isWorkingTree) {
            // Conflicted file: show ours (stage 2) vs theirs (stage 3)
            if (fc.status == 'U') {
                leftContent = runGit("show", ":2:" + fc.path);
                rightContent = runGit("show", ":3:" + fc.path);
                leftLbl = fc.path + " (ours)";
                rightLbl = fc.path + " (theirs)";
                embeddedCompare.setContent(leftContent, rightContent, leftLbl, rightLbl);
                return;
            }
            // Working tree: show staged diff (HEAD vs index) or unstaged diff (index vs working tree)
            if (fc.staged) {
                // Staged: HEAD vs index
                switch (fc.status) {
                    case 'M':
                        leftContent = runGit("show", "HEAD:" + fc.path);
                        rightContent = runGit("show", ":" + fc.path);  // index version
                        leftLbl = "HEAD:" + fc.path;
                        rightLbl = fc.path + " (staged)";
                        break;
                    case 'A':
                        rightContent = runGit("show", ":" + fc.path);
                        leftLbl = "(empty)";
                        rightLbl = fc.path + " (staged)";
                        break;
                    case 'D':
                        leftContent = runGit("show", "HEAD:" + fc.path);
                        leftLbl = "HEAD:" + fc.path;
                        rightLbl = "(deleted)";
                        break;
                    case 'R':
                        leftContent = runGit("show", "HEAD:" + fc.oldPath);
                        rightContent = runGit("show", ":" + fc.path);
                        leftLbl = "HEAD:" + fc.oldPath;
                        rightLbl = fc.path + " (staged)";
                        break;
                    default:
                        return;
                }
            } else {
                // Unstaged: index vs working tree
                switch (fc.status) {
                    case 'M':
                        leftContent = runGit("show", ":" + fc.path);
                        rightContent = readDiskFile(fc.path);
                        leftLbl = fc.path + " (index)";
                        rightLbl = fc.path + " (working tree)";
                        break;
                    case 'A':
                    case '?':
                        rightContent = readDiskFile(fc.path);
                        leftLbl = "(empty)";
                        rightLbl = fc.path + " (working tree)";
                        break;
                    case 'D':
                        leftContent = runGit("show", ":" + fc.path);
                        leftLbl = fc.path + " (index)";
                        rightLbl = "(deleted)";
                        break;
                    case 'R':
                        leftContent = runGit("show", ":" + fc.oldPath);
                        rightContent = readDiskFile(fc.path);
                        leftLbl = fc.oldPath + " (index)";
                        rightLbl = fc.path + " (working tree)";
                        break;
                    default:
                        return;
                }
            }
        } else {
            boolean hasParent = !ci.parentHashes.isEmpty();
            String parentRef = hasParent ? ci.parentHashes.get(0) : null;
            switch (fc.status) {
                case 'M':
                    if (hasParent) {
                        leftContent = runGit("show", parentRef + ":" + fc.oldPath);
                        leftLbl = ci.shortHash + "~1:" + fc.path;
                    } else {
                        leftLbl = "(empty)";
                    }
                    rightContent = runGit("show", ci.fullHash + ":" + fc.path);
                    rightLbl = ci.shortHash + ":" + fc.path;
                    break;
                case 'A':
                    rightContent = runGit("show", ci.fullHash + ":" + fc.path);
                    leftLbl = "(empty)";
                    rightLbl = ci.shortHash + ":" + fc.path;
                    break;
                case 'D':
                    if (hasParent) {
                        leftContent = runGit("show", parentRef + ":" + fc.oldPath);
                        leftLbl = ci.shortHash + "~1:" + fc.path;
                    } else {
                        leftLbl = "(empty)";
                    }
                    rightLbl = "(deleted)";
                    break;
                case 'R':
                    if (hasParent) {
                        leftContent = runGit("show", parentRef + ":" + fc.oldPath);
                        leftLbl = ci.shortHash + "~1:" + fc.oldPath;
                    } else {
                        leftLbl = "(empty)";
                    }
                    rightContent = runGit("show", ci.fullHash + ":" + fc.path);
                    rightLbl = ci.shortHash + ":" + fc.path;
                    break;
                default:
                    return;
            }
        }

        embeddedCompare.setContent(leftContent, rightContent, leftLbl, rightLbl);
    }

    private void openSelectedFile() {
        int fileIdx = filesTable.getSelectionIndex();
        if (fileIdx < 0 || fileIdx >= filesTable.getItemCount()) return;

        // Skip section dividers
        Object data = filesTable.getItem(fileIdx).getData();
        if (!(data instanceof FileChange)) return;

        int commitIdx = commitTable.getSelectionIndex();
        if (commitIdx < 0 || commitIdx >= filteredCommits.size()) return;

        FileChange fc = (FileChange) data;
        CommitInfo ci = filteredCommits.get(commitIdx);

        try {
            String leftPath = null;
            String rightPath = null;

            if (ci.isWorkingTree) {
                // Conflicted file: ours (stage 2) as temp file, disk file (with markers) as right
                if (fc.status == 'U') {
                    String oursContent = runGit("show", ":2:" + fc.path);
                    leftPath = writeTempFile("ours_" + fc.name, oursContent);
                    rightPath = Paths.get(repoPath, fc.path).toString();
                    FileCompare compare = new FileCompare(leftPath, rightPath, display, shell);
                    compare.openWindow();
                    return;
                }
                // Working tree: show appropriate diff based on staged status
                switch (fc.status) {
                    case 'M': {
                        String leftContent = fc.staged ? runGit("show", "HEAD:" + fc.path)
                                                       : runGit("show", ":" + fc.path);
                        leftPath = writeTempFile("base_" + fc.name, leftContent);
                        rightPath = fc.staged ? writeTempFile("staged_" + fc.name, runGit("show", ":" + fc.path))
                                              : Paths.get(repoPath, fc.path).toString();
                        break;
                    }
                    case 'A':
                    case '?': {
                        leftPath = writeTempFile("empty_" + fc.name, "");
                        rightPath = Paths.get(repoPath, fc.path).toString();
                        break;
                    }
                    case 'D': {
                        String leftContent = fc.staged ? runGit("show", "HEAD:" + fc.path)
                                                       : runGit("show", ":" + fc.path);
                        leftPath = writeTempFile("base_" + fc.name, leftContent);
                        rightPath = writeTempFile("deleted_" + fc.name, "");
                        break;
                    }
                    case 'R': {
                        String leftContent = fc.staged ? runGit("show", "HEAD:" + fc.oldPath)
                                                       : runGit("show", ":" + fc.oldPath);
                        leftPath = writeTempFile("base_" + Paths.get(fc.oldPath).getFileName().toString(), leftContent);
                        rightPath = fc.staged ? writeTempFile("staged_" + fc.name, runGit("show", ":" + fc.path))
                                              : Paths.get(repoPath, fc.path).toString();
                        break;
                    }
                    default:
                        return;
                }
            } else {
                boolean hasParent = !ci.parentHashes.isEmpty();
                String parentRef = hasParent ? ci.parentHashes.get(0) : null;
                switch (fc.status) {
                    case 'M': {
                        String leftContent = hasParent ? runGit("show", parentRef + ":" + fc.oldPath) : "";
                        String rightContent = runGit("show", ci.fullHash + ":" + fc.path);
                        leftPath = writeTempFile(ci.shortHash + "~1_" + fc.name, leftContent);
                        rightPath = writeTempFile(ci.shortHash + "_" + fc.name, rightContent);
                        break;
                    }
                    case 'A': {
                        String rightContent = runGit("show", ci.fullHash + ":" + fc.path);
                        leftPath = writeTempFile(ci.shortHash + "~1_" + fc.name, "");
                        rightPath = writeTempFile(ci.shortHash + "_" + fc.name, rightContent);
                        break;
                    }
                    case 'D': {
                        String leftContent = hasParent ? runGit("show", parentRef + ":" + fc.oldPath) : "";
                        leftPath = writeTempFile(ci.shortHash + "~1_" + fc.name, leftContent);
                        rightPath = writeTempFile(ci.shortHash + "_" + fc.name, "");
                        break;
                    }
                    case 'R': {
                        String leftContent = hasParent ? runGit("show", parentRef + ":" + fc.oldPath) : "";
                        String rightContent = runGit("show", ci.fullHash + ":" + fc.path);
                        leftPath = writeTempFile(ci.shortHash + "~1_" + Paths.get(fc.oldPath).getFileName().toString(), leftContent);
                        rightPath = writeTempFile(ci.shortHash + "_" + fc.name, rightContent);
                        break;
                    }
                    default:
                        return;
                }
            }

            FileCompare compare = new FileCompare(leftPath, rightPath, display, shell);
            compare.openWindow();

        } catch (Exception e) {
            statusBarLeft.setText("Error opening file: " + e.getMessage());
        }
    }

    private String readDiskFile(String relativePath) {
        try {
            return Files.readString(Paths.get(repoPath, relativePath));
        } catch (IOException e) {
            return "";
        }
    }

    private String writeTempFile(String name, String content) throws IOException {
        Path tmpDir = Files.createTempDirectory("visualgit");
        Path tmpFile = tmpDir.resolve(name);
        Files.writeString(tmpFile, content != null ? content : "");
        tmpFile.toFile().deleteOnExit();
        tmpDir.toFile().deleteOnExit();
        return tmpFile.toString();
    }

    // ── Edit Commit Message / Squash ────────────────────────────────

    private boolean isWorkingTreeDirty() {
        String status = runGit("status", "--porcelain");
        return !status.trim().isEmpty();
    }

    private void doEditCommitMessage(CommitInfo ci) {
        if (ci.isWorkingTree) return;
        if (isWorkingTreeDirty()) {
            MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            warn.setText("Dirty Working Tree");
            warn.setMessage("Please commit or stash your changes before editing a commit message.");
            warn.open();
            return;
        }

        // Get full message
        String fullMsg = runGit("log", "-1", "--format=%B", ci.fullHash).trim();
        String newMsg = showEditMessageDialog(fullMsg);
        if (newMsg == null) return; // cancelled

        // Check if HEAD commit
        String headHash = runGit("rev-parse", "HEAD").trim();
        boolean isHead = ci.fullHash.equals(headHash);

        if (isHead) {
            String result = runGitWithInput(newMsg, "commit", "--amend", "-F", "-");
            if (result != null) {
                statusBarLeft.setText("Amended commit message");
                loadBranches();
                loadCommits();
            } else {
                statusBarLeft.setText("Amend failed");
            }
        } else {
            // Non-interactive rebase for older commit
            boolean ok = rebaseReword(ci, newMsg);
            if (ok) {
                statusBarLeft.setText("Reworded commit " + ci.shortHash);
                loadBranches();
                loadCommits();
            } else {
                // Abort any in-progress rebase
                runGitWithExitCode("rebase", "--abort");
                statusBarLeft.setText("Reword failed — rebase aborted");
            }
        }
    }

    private boolean rebaseReword(CommitInfo ci, String newMsg) {
        try {
            // Write the new message to a temp file
            Path msgFile = Files.createTempFile("visualgit-msg-", ".txt");
            Files.writeString(msgFile, newMsg);
            msgFile.toFile().deleteOnExit();

            // GIT_SEQUENCE_EDITOR: replace "pick <hash>" with "reword <hash>"
            String seqEditor = "sed -i.bak 's/^pick " + ci.shortHash + "/reword " + ci.shortHash + "/'";
            // GIT_EDITOR: replace editor content with our message file
            String editor = "cp " + msgFile.toAbsolutePath() + " \"$1\"";

            // Write editor script
            Path editorScript = Files.createTempFile("visualgit-editor-", ".sh");
            Files.writeString(editorScript, "#!/bin/sh\n" + editor + "\n");
            editorScript.toFile().setExecutable(true);
            editorScript.toFile().deleteOnExit();

            Map<String, String> env = new HashMap<>();
            env.put("GIT_SEQUENCE_EDITOR", seqEditor);
            env.put("GIT_EDITOR", editorScript.toAbsolutePath().toString());

            // Parent of the commit to reword
            String parent = ci.parentHashes.isEmpty() ? "--root" : ci.parentHashes.get(0);
            String result;
            if (ci.parentHashes.isEmpty()) {
                result = runGitWithEnv(env, "rebase", "-i", "--root");
            } else {
                result = runGitWithEnv(env, "rebase", "-i", parent);
            }
            return result != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Shows a dialog to edit a commit message. Returns null if cancelled. */
    private String showEditMessageDialog(String currentMsg) {
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dlg.setText("Edit Commit Message");
        dlg.setLayout(new GridLayout(1, false));
        dlg.setSize(500, 300);

        Text msgText = new Text(dlg, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        msgText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        msgText.setFont(appFont);
        msgText.setText(currentMsg);

        Composite btnBar = new Composite(dlg, SWT.NONE);
        btnBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        btnBar.setLayout(new RowLayout(SWT.HORIZONTAL));

        String[] result = {null};

        Button okBtn = new Button(btnBar, SWT.PUSH);
        okBtn.setText("OK");
        okBtn.addListener(SWT.Selection, e -> {
            String txt = msgText.getText().trim();
            if (!txt.isEmpty()) {
                result[0] = txt;
                dlg.close();
            }
        });

        Button cancelBtn = new Button(btnBar, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        dlg.setDefaultButton(okBtn);

        // Center on parent
        Rectangle parentBounds = shell.getBounds();
        Point size = dlg.getSize();
        dlg.setLocation(
                parentBounds.x + (parentBounds.width - size.x) / 2,
                parentBounds.y + (parentBounds.height - size.y) / 2);

        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return result[0];
    }

    /** Validates multi-selection for squash. Returns ordered list (oldest first) or null. */
    private List<CommitInfo> getConsecutiveSelectedCommits() {
        int[] indices = commitTable.getSelectionIndices();
        if (indices.length < 2) return null;

        Arrays.sort(indices);

        // Check consecutive
        for (int i = 1; i < indices.length; i++) {
            if (indices[i] != indices[i - 1] + 1) return null;
        }

        List<CommitInfo> commits = new ArrayList<>();
        for (int idx : indices) {
            if (idx < 0 || idx >= filteredCommits.size()) return null;
            CommitInfo ci = filteredCommits.get(idx);
            if (ci.isWorkingTree) return null;
            // Merge commits (multiple parents) can't be squashed
            if (ci.parentHashes.size() > 1) return null;
            commits.add(ci);
        }
        return commits; // ordered top-to-bottom (newest first in log)
    }

    // ── Interactive Rebase ─────────────────────────────────────────

    private void showInteractiveRebaseDialog(CommitInfo base) {
        if (base.isWorkingTree) return;

        if (isWorkingTreeDirty()) {
            MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            warn.setText("Dirty Working Tree");
            warn.setMessage("Please commit or stash your changes before interactive rebase.");
            warn.open();
            return;
        }

        // Collect commits from HEAD down to base (inclusive), oldest-first
        String headHash = runGit("rev-parse", "HEAD").trim();
        String logOutput = runGit("log", "--format=%H%x00%h%x00%s", "--ancestry-path", base.fullHash + "^.." + headHash);
        if (logOutput == null || logOutput.trim().isEmpty()) {
            // Try without --ancestry-path for simpler histories
            logOutput = runGit("log", "--format=%H%x00%h%x00%s", base.fullHash + "^.." + headHash);
        }
        if (logOutput == null || logOutput.trim().isEmpty()) {
            // base might be root commit — try --root range
            logOutput = runGit("log", "--format=%H%x00%h%x00%s", headHash);
            if (logOutput == null || logOutput.trim().isEmpty()) {
                MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
                warn.setText("Cannot Rebase");
                warn.setMessage("Could not determine commits to rebase.");
                warn.open();
                return;
            }
            // Filter to only include commits from HEAD to base
            String[] allLines = logOutput.trim().split("\n");
            StringBuilder filtered = new StringBuilder();
            boolean found = false;
            for (String line : allLines) {
                String[] parts = line.split("\0", 3);
                if (parts.length >= 1) {
                    filtered.append(line).append("\n");
                    if (parts[0].equals(base.fullHash)) { found = true; break; }
                }
            }
            if (!found) {
                MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
                warn.setText("Cannot Rebase");
                warn.setMessage("Selected commit is not an ancestor of HEAD.");
                warn.open();
                return;
            }
            logOutput = filtered.toString();
        }

        String[] lines = logOutput.trim().split("\n");
        List<RebaseEntry> entries = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\0", 3);
            if (parts.length < 3) continue;
            RebaseEntry e = new RebaseEntry();
            e.fullHash = parts[0];
            e.hash = parts[1];
            e.message = parts[2];
            e.action = "pick";
            entries.add(e);
        }
        // Reverse to oldest-first (git log outputs newest-first)
        Collections.reverse(entries);

        if (entries.isEmpty()) {
            MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            warn.setText("Cannot Rebase");
            warn.setMessage("No commits found to rebase.");
            warn.open();
            return;
        }

        // Determine base parent
        String baseParent;
        if (base.parentHashes != null && !base.parentHashes.isEmpty()) {
            baseParent = base.parentHashes.get(0);
        } else {
            baseParent = null; // root commit → use --root
        }

        // ── Dialog ──
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dlg.setText("Interactive Rebase");
        dlg.setLayout(new GridLayout(1, false));
        dlg.setSize(650, 450);

        Label info = new Label(dlg, SWT.WRAP);
        info.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        String parentLabel = baseParent != null ? baseParent.substring(0, Math.min(7, baseParent.length())) : "--root";
        info.setText("Rebase " + entries.size() + " commit(s) onto " + parentLabel);
        info.setFont(appFont);

        // Table
        Table table = new Table(dlg, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.setHeaderVisible(true);
        table.setFont(appFont);

        TableColumn colHandle = new TableColumn(table, SWT.CENTER);
        colHandle.setText("");
        colHandle.setWidth(30);
        TableColumn colAction = new TableColumn(table, SWT.LEFT);
        colAction.setText("Action");
        colAction.setWidth(80);
        TableColumn colHash = new TableColumn(table, SWT.LEFT);
        colHash.setText("Hash");
        colHash.setWidth(80);
        TableColumn colMsg = new TableColumn(table, SWT.LEFT);
        colMsg.setText("Message");
        colMsg.setWidth(420);

        // Populate
        Runnable refreshTable = () -> {
            table.removeAll();
            for (RebaseEntry e : entries) {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(0, "\u2261"); // ≡ drag handle
                item.setText(1, e.action);
                item.setText(2, e.hash);
                item.setText(3, e.message);
                // Color-code action
                if ("drop".equals(e.action)) {
                    item.setForeground(display.getSystemColor(SWT.COLOR_GRAY));
                } else if ("reword".equals(e.action)) {
                    item.setForeground(display.getSystemColor(SWT.COLOR_DARK_BLUE));
                } else if ("edit".equals(e.action)) {
                    item.setForeground(display.getSystemColor(SWT.COLOR_DARK_MAGENTA));
                } else {
                    item.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
                }
            }
        };
        refreshTable.run();

        // Double-click: cycle action or edit message
        table.addListener(SWT.MouseDoubleClick, ev -> {
            Point pt = new Point(ev.x, ev.y);
            TableItem item = table.getItem(pt);
            if (item == null) return;
            int row = table.indexOf(item);
            if (row < 0 || row >= entries.size()) return;
            RebaseEntry e = entries.get(row);

            // Determine column
            int colX = 0;
            int clickedCol = -1;
            for (int c = 0; c < table.getColumnCount(); c++) {
                int w = table.getColumn(c).getWidth();
                if (ev.x >= colX && ev.x < colX + w) { clickedCol = c; break; }
                colX += w;
            }

            if (clickedCol == 1) {
                // Cycle action: pick -> reword -> edit -> drop -> pick
                switch (e.action) {
                    case "pick":   e.action = "reword"; break;
                    case "reword": e.action = "edit"; break;
                    case "edit":   e.action = "drop"; break;
                    case "drop":   e.action = "pick"; break;
                }
                refreshTable.run();
                table.setSelection(row);
            } else if (clickedCol == 3 && "reword".equals(e.action)) {
                String newMsg = showRewordDialog(e.message);
                if (newMsg != null) {
                    e.message = newMsg;
                    refreshTable.run();
                    table.setSelection(row);
                }
            }
        });

        // Drag-and-drop for row reordering
        final int[] dragIndex = {-1};

        DragSource dragSource = new DragSource(table, DND.DROP_MOVE);
        dragSource.setTransfer(TextTransfer.getInstance());
        dragSource.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragStart(DragSourceEvent event) {
                int sel = table.getSelectionIndex();
                if (sel < 0) { event.doit = false; return; }
                dragIndex[0] = sel;
            }
            @Override
            public void dragSetData(DragSourceEvent event) {
                event.data = String.valueOf(dragIndex[0]);
            }
        });

        DropTarget dropTarget = new DropTarget(table, DND.DROP_MOVE);
        dropTarget.setTransfer(TextTransfer.getInstance());
        dropTarget.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetEvent event) {
                event.feedback = DND.FEEDBACK_SELECT | DND.FEEDBACK_SCROLL;
            }
            @Override
            public void drop(DropTargetEvent event) {
                if (dragIndex[0] < 0) return;
                // Compute target index
                Point p = table.toControl(event.x, event.y);
                TableItem targetItem = table.getItem(p);
                int targetIdx;
                if (targetItem != null) {
                    targetIdx = table.indexOf(targetItem);
                } else {
                    targetIdx = entries.size() - 1;
                }
                if (targetIdx == dragIndex[0]) return;

                RebaseEntry moved = entries.remove(dragIndex[0]);
                entries.add(targetIdx, moved);
                refreshTable.run();
                table.setSelection(targetIdx);
                dragIndex[0] = -1;
            }
        });

        // Buttons bar
        Composite btnBar = new Composite(dlg, SWT.NONE);
        btnBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout btnGl = new GridLayout(5, false);
        btnGl.marginHeight = 0;
        btnBar.setLayout(btnGl);

        Button moveUpBtn = new Button(btnBar, SWT.PUSH);
        moveUpBtn.setText("Move Up");
        moveUpBtn.setFont(appFont);
        moveUpBtn.addListener(SWT.Selection, ev -> {
            int sel = table.getSelectionIndex();
            if (sel <= 0) return;
            RebaseEntry moved = entries.remove(sel);
            entries.add(sel - 1, moved);
            refreshTable.run();
            table.setSelection(sel - 1);
        });

        Button moveDownBtn = new Button(btnBar, SWT.PUSH);
        moveDownBtn.setText("Move Down");
        moveDownBtn.setFont(appFont);
        moveDownBtn.addListener(SWT.Selection, ev -> {
            int sel = table.getSelectionIndex();
            if (sel < 0 || sel >= entries.size() - 1) return;
            RebaseEntry moved = entries.remove(sel);
            entries.add(sel + 1, moved);
            refreshTable.run();
            table.setSelection(sel + 1);
        });

        Label spacer = new Label(btnBar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        boolean[] applied = {false};

        Button cancelBtn = new Button(btnBar, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setFont(appFont);
        cancelBtn.addListener(SWT.Selection, ev -> dlg.close());

        Button applyBtn = new Button(btnBar, SWT.PUSH);
        applyBtn.setText("Apply");
        applyBtn.setFont(appFont);
        applyBtn.addListener(SWT.Selection, ev -> {
            // Check if all actions are "drop"
            boolean allDrop = true;
            for (RebaseEntry e : entries) {
                if (!"drop".equals(e.action)) { allDrop = false; break; }
            }
            if (allDrop) {
                MessageBox warn = new MessageBox(dlg, SWT.ICON_WARNING | SWT.OK);
                warn.setText("Nothing to do");
                warn.setMessage("Cannot drop all commits.");
                warn.open();
                return;
            }
            applied[0] = true;
            dlg.close();
        });

        dlg.setDefaultButton(applyBtn);

        // Center on parent
        Rectangle parentBounds = shell.getBounds();
        Point size = dlg.getSize();
        dlg.setLocation(
                parentBounds.x + (parentBounds.width - size.x) / 2,
                parentBounds.y + (parentBounds.height - size.y) / 2);

        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        dragSource.dispose();
        dropTarget.dispose();

        if (!applied[0]) return;

        doInteractiveRebase(entries, baseParent);
    }

    private void doInteractiveRebase(List<RebaseEntry> entries, String baseParent) {
        try {
            // Build todo content (oldest-first, as entries are already in that order)
            StringBuilder todo = new StringBuilder();
            for (RebaseEntry e : entries) {
                todo.append(e.action).append(" ").append(e.hash).append(" ").append(e.message).append("\n");
            }

            // Write GIT_SEQUENCE_EDITOR script: replace todo file with our content
            Path todoFile = Files.createTempFile("visualgit-rebase-todo-", ".txt");
            Files.writeString(todoFile, todo.toString());
            todoFile.toFile().deleteOnExit();

            Path seqScript = Files.createTempFile("visualgit-seqed-", ".sh");
            Files.writeString(seqScript, "#!/bin/sh\ncp " + todoFile.toAbsolutePath() + " \"$1\"\n");
            seqScript.toFile().setExecutable(true);
            seqScript.toFile().deleteOnExit();

            // Handle reword messages: build a GIT_EDITOR script
            // Collect reword entries with their messages
            List<RebaseEntry> rewordEntries = new ArrayList<>();
            for (RebaseEntry e : entries) {
                if ("reword".equals(e.action)) rewordEntries.add(e);
            }

            Map<String, String> env = new HashMap<>();
            env.put("GIT_SEQUENCE_EDITOR", seqScript.toAbsolutePath().toString());

            if (!rewordEntries.isEmpty()) {
                // Write each reword message to a separate temp file
                // Use a counter-based editor script: each invocation reads the next message
                Path counterFile = Files.createTempFile("visualgit-reword-counter-", ".txt");
                Files.writeString(counterFile, "0");
                counterFile.toFile().deleteOnExit();

                StringBuilder editorScript = new StringBuilder("#!/bin/sh\n");
                editorScript.append("COUNTER=$(cat ").append(counterFile.toAbsolutePath()).append(")\n");

                for (int i = 0; i < rewordEntries.size(); i++) {
                    Path msgFile = Files.createTempFile("visualgit-reword-msg-" + i + "-", ".txt");
                    Files.writeString(msgFile, rewordEntries.get(i).message);
                    msgFile.toFile().deleteOnExit();

                    if (i == 0) {
                        editorScript.append("if [ \"$COUNTER\" = \"").append(i).append("\" ]; then\n");
                    } else {
                        editorScript.append("elif [ \"$COUNTER\" = \"").append(i).append("\" ]; then\n");
                    }
                    editorScript.append("  cp ").append(msgFile.toAbsolutePath()).append(" \"$1\"\n");
                }
                editorScript.append("fi\n");
                editorScript.append("COUNTER=$((COUNTER + 1))\n");
                editorScript.append("echo $COUNTER > ").append(counterFile.toAbsolutePath()).append("\n");

                Path edScript = Files.createTempFile("visualgit-editor-", ".sh");
                Files.writeString(edScript, editorScript.toString());
                edScript.toFile().setExecutable(true);
                edScript.toFile().deleteOnExit();

                env.put("GIT_EDITOR", edScript.toAbsolutePath().toString());
            } else {
                env.put("GIT_EDITOR", "true");
            }

            String result;
            if (baseParent == null) {
                result = runGitWithEnv(env, "rebase", "-i", "--root");
            } else {
                result = runGitWithEnv(env, "rebase", "-i", baseParent);
            }

            if (result != null) {
                statusBarLeft.setText("Interactive rebase completed");
                loadBranches();
                loadCommits();
            } else {
                // Check if rebase is still in progress (edit stop or conflict)
                String state = detectConflictState();
                if ("rebase".equals(state)) {
                    handleConflictAfterOp("Rebase");
                } else {
                    runGitWithExitCode("rebase", "--abort");
                    statusBarLeft.setText("Interactive rebase failed — aborted");
                    loadBranches();
                    loadCommits();
                }
            }
        } catch (IOException e) {
            statusBarLeft.setText("Interactive rebase failed: " + e.getMessage());
        }
    }

    private String showRewordDialog(String currentMessage) {
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dlg.setText("Edit Commit Message");
        dlg.setLayout(new GridLayout(1, false));
        dlg.setSize(450, 250);

        Label info = new Label(dlg, SWT.NONE);
        info.setText("Edit the commit message:");
        info.setFont(appFont);

        Text msgText = new Text(dlg, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        msgText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        msgText.setFont(appFont);
        msgText.setText(currentMessage);

        Composite btnBar = new Composite(dlg, SWT.NONE);
        btnBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        btnBar.setLayout(new RowLayout(SWT.HORIZONTAL));

        String[] result = {null};

        Button okBtn = new Button(btnBar, SWT.PUSH);
        okBtn.setText("OK");
        okBtn.addListener(SWT.Selection, e -> {
            String txt = msgText.getText().trim();
            if (!txt.isEmpty()) {
                result[0] = txt;
                dlg.close();
            }
        });

        Button cancelBtn = new Button(btnBar, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        dlg.setDefaultButton(okBtn);

        Rectangle parentBounds = shell.getBounds();
        Point size = dlg.getSize();
        dlg.setLocation(
                parentBounds.x + (parentBounds.width - size.x) / 2,
                parentBounds.y + (parentBounds.height - size.y) / 2);

        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return result[0];
    }

    private void addToGitignore(String pattern) {
        Path gitignore = Paths.get(repoPath, ".gitignore");
        try {
            // Check for duplicate
            if (Files.exists(gitignore)) {
                List<String> lines = Files.readAllLines(gitignore);
                for (String line : lines) {
                    if (line.trim().equals(pattern)) {
                        statusBarLeft.setText(".gitignore already contains: " + pattern);
                        return;
                    }
                }
            }
            // Ensure file ends with newline before appending
            if (Files.exists(gitignore)) {
                byte[] bytes = Files.readAllBytes(gitignore);
                if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                    Files.write(gitignore, "\n".getBytes(), StandardOpenOption.APPEND);
                }
            }
            Files.write(gitignore, (pattern + "\n").getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            statusBarLeft.setText("Added to .gitignore: " + pattern);
            reloadWorkingTree();
        } catch (IOException ex) {
            statusBarLeft.setText("Error updating .gitignore: " + ex.getMessage());
        }
    }

    private void showFileHistoryDialog(String filePath) {
        // Load commits that touched this file (with rename tracking)
        String logOutput = runGit("log", "--follow",
                "--format=%H%x00%h%x00%an%x00%ai%x00%s%x00%P",
                "--name-status", "--", filePath);
        if (logOutput == null || logOutput.trim().isEmpty()) {
            statusBarLeft.setText("No history found for: " + filePath);
            return;
        }

        // Parse: each commit is a format line followed by an empty line + name-status line(s)
        // Format line: fullHash\0shortHash\0author\0date\0subject\0parentHashes
        // Name-status: M\tpath  or  R100\toldPath\tnewPath  etc.
        List<String[]> commits = new ArrayList<>(); // [fullHash, shortHash, author, date, message, parentFullHash, filePathAtCommit, status]
        String[] lines = logOutput.split("\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.isEmpty()) { i++; continue; }
            String[] parts = line.split("\0", -1);
            if (parts.length < 5) { i++; continue; }
            String fullHash = parts[0];
            String shortHash = parts[1];
            String author = parts[2];
            String date = parts[3];
            String msg = parts[4];
            String parentStr = parts.length > 5 ? parts[5] : "";
            String parentHash = "";
            if (!parentStr.isEmpty()) {
                parentHash = parentStr.split(" ")[0]; // first parent
            }
            i++;
            // Collect name-status lines for this commit
            String pathAtCommit = filePath;
            String statusChar = "M";
            while (i < lines.length) {
                String ns = lines[i].trim();
                if (ns.isEmpty()) { i++; continue; }
                // name-status lines start with A/M/D/R/C + tab
                if (ns.length() >= 2 && (ns.charAt(0) == 'M' || ns.charAt(0) == 'A' ||
                        ns.charAt(0) == 'D' || ns.startsWith("R") || ns.startsWith("C"))) {
                    String[] nsParts = ns.split("\t");
                    statusChar = nsParts[0].substring(0, 1);
                    if (statusChar.equals("R") && nsParts.length >= 3) {
                        pathAtCommit = nsParts[2]; // new path after rename
                    } else if (nsParts.length >= 2) {
                        pathAtCommit = nsParts[nsParts.length - 1];
                    }
                    i++;
                } else {
                    break; // next commit's format line
                }
            }
            commits.add(new String[]{fullHash, shortHash, author, date, msg, parentHash, pathAtCommit, statusChar});
        }

        if (commits.isEmpty()) {
            statusBarLeft.setText("No history found for: " + filePath);
            return;
        }

        // Create non-modal dialog
        Shell dlg = new Shell(display, SWT.SHELL_TRIM | SWT.RESIZE);
        dlg.setText("File History: " + filePath);
        dlg.setSize(900, 600);
        // Center on parent
        Rectangle parentBounds = shell.getBounds();
        Point size = dlg.getSize();
        dlg.setLocation(
                parentBounds.x + (parentBounds.width - size.x) / 2,
                parentBounds.y + (parentBounds.height - size.y) / 2);

        GridLayout dlgLayout = new GridLayout(1, false);
        dlgLayout.marginHeight = 5;
        dlgLayout.marginWidth = 5;
        dlg.setLayout(dlgLayout);

        // SashForm: top = commit table, bottom = diff
        SashForm sash = new SashForm(dlg, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Commit table
        Table histTable = new Table(sash, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        histTable.setHeaderVisible(true);
        histTable.setLinesVisible(true);
        histTable.setFont(appFont);

        TableColumn hColHash = new TableColumn(histTable, SWT.LEFT);
        hColHash.setText("Hash");
        hColHash.setWidth(80);
        TableColumn hColMsg = new TableColumn(histTable, SWT.LEFT);
        hColMsg.setText("Message");
        hColMsg.setWidth(350);
        TableColumn hColAuthor = new TableColumn(histTable, SWT.LEFT);
        hColAuthor.setText("Author");
        hColAuthor.setWidth(120);
        TableColumn hColDate = new TableColumn(histTable, SWT.LEFT);
        hColDate.setText("Date");
        hColDate.setWidth(150);

        // Percentage-based column resize
        histTable.addListener(SWT.Resize, e -> {
            int total = histTable.getClientArea().width;
            if (total <= 0) return;
            hColHash.setWidth(total * 10 / 100);
            hColAuthor.setWidth(total * 15 / 100);
            hColDate.setWidth(total * 20 / 100);
            int msgW = total - hColHash.getWidth() - hColAuthor.getWidth() - hColDate.getWidth();
            if (msgW < 100) msgW = 100;
            hColMsg.setWidth(msgW);
        });

        // Populate table
        for (String[] c : commits) {
            TableItem ti = new TableItem(histTable, SWT.NONE);
            ti.setText(new String[]{c[1], c[4], c[2], c[3]});
            ti.setData(c);
        }

        // Embedded diff panel
        Composite diffPanel = new Composite(sash, SWT.NONE);
        GridLayout diffLayout = new GridLayout(1, false);
        diffLayout.marginHeight = 0;
        diffLayout.marginWidth = 0;
        diffLayout.verticalSpacing = 0;
        diffPanel.setLayout(diffLayout);

        FileCompare histCompare = new FileCompare(null, null, display);
        histCompare.createPanel(diffPanel);

        sash.setWeights(new int[]{60, 40});

        // Selection handler
        histTable.addListener(SWT.Selection, e -> {
            int idx = histTable.getSelectionIndex();
            if (idx < 0) return;
            String[] c = (String[]) histTable.getItem(idx).getData();
            String commitHash = c[0];
            String parentHash = c[5];
            String pathAt = c[6];
            String status = c[7];

            String leftContent = "";
            String rightContent = "";
            String leftLbl = "";
            String rightLbl = "";

            if (status.equals("A") || parentHash.isEmpty()) {
                // Added file — left empty
                rightContent = runGit("show", commitHash + ":" + pathAt);
                leftLbl = "(not yet created)";
                rightLbl = pathAt + " @ " + c[1];
            } else if (status.equals("D")) {
                // Deleted file — right empty
                leftContent = runGit("show", parentHash + ":" + pathAt);
                leftLbl = pathAt + " @ " + parentHash.substring(0, Math.min(7, parentHash.length()));
                rightLbl = "(deleted)";
            } else {
                // Modified or renamed
                String parentPath = pathAt;
                // For renames, we need the old path — check the previous commit entry
                if (status.equals("R")) {
                    // Re-parse name-status for this specific commit to get oldPath
                    String nsOutput = runGit("diff-tree", "--no-commit-id", "-r", "--name-status", commitHash);
                    for (String nsLine : nsOutput.split("\n")) {
                        nsLine = nsLine.trim();
                        if (nsLine.startsWith("R")) {
                            String[] nsp = nsLine.split("\t");
                            if (nsp.length >= 3 && nsp[2].equals(pathAt)) {
                                parentPath = nsp[1];
                                break;
                            }
                        }
                    }
                }
                leftContent = runGit("show", parentHash + ":" + parentPath);
                rightContent = runGit("show", commitHash + ":" + pathAt);
                leftLbl = parentPath + " @ " + parentHash.substring(0, Math.min(7, parentHash.length()));
                rightLbl = pathAt + " @ " + c[1];
            }
            histCompare.setContent(leftContent, rightContent, leftLbl, rightLbl);
        });

        // Close button
        Composite btnBar = new Composite(dlg, SWT.NONE);
        btnBar.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        btnBar.setLayout(new GridLayout(1, false));
        Button closeBtn = new Button(btnBar, SWT.PUSH);
        closeBtn.setText("Close");
        closeBtn.addListener(SWT.Selection, e -> dlg.dispose());

        // Cleanup
        dlg.addDisposeListener(e -> histCompare.disposeResources());

        dlg.open();
    }

    private void doSquashCommits() {
        List<CommitInfo> commits = getConsecutiveSelectedCommits();
        if (commits == null) {
            MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            warn.setText("Cannot Squash");
            warn.setMessage("Select 2 or more consecutive, non-merge commits to squash.");
            warn.open();
            return;
        }

        if (isWorkingTreeDirty()) {
            MessageBox warn = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            warn.setText("Dirty Working Tree");
            warn.setMessage("Please commit or stash your changes before squashing commits.");
            warn.open();
            return;
        }

        // Concatenate messages (newest first → reverse to oldest first for display)
        StringBuilder sb = new StringBuilder();
        for (int i = commits.size() - 1; i >= 0; i--) {
            CommitInfo ci = commits.get(i);
            String fullMsg = runGit("log", "-1", "--format=%B", ci.fullHash).trim();
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(fullMsg);
        }

        String newMsg = showSquashDialog(commits, sb.toString());
        if (newMsg == null) return; // cancelled

        // Check if the selection includes HEAD
        String headHash = runGit("rev-parse", "HEAD").trim();
        boolean includesHead = false;
        for (CommitInfo ci : commits) {
            if (ci.fullHash.equals(headHash)) { includesHead = true; break; }
        }

        // Oldest commit is last in the list (commits is newest-first from table order)
        CommitInfo oldest = commits.get(commits.size() - 1);

        if (includesHead) {
            // Simple: reset --soft to parent of oldest, then commit
            String parent = oldest.parentHashes.isEmpty() ? null : oldest.parentHashes.get(0);
            String result;
            if (parent != null) {
                result = runGitWithExitCode("reset", "--soft", parent);
            } else {
                // Squashing all commits from root — update-ref to delete HEAD, re-add index
                result = runGitWithExitCode("reset", "--soft", oldest.fullHash);
                // For root commits, we need a different approach
                // Reset soft to root, amend it
                if (result != null) {
                    result = runGitWithInput(newMsg, "commit", "--amend", "-F", "-");
                    if (result != null) {
                        statusBarLeft.setText("Squashed " + commits.size() + " commits");
                        loadBranches();
                        loadCommits();
                    } else {
                        statusBarLeft.setText("Squash failed");
                    }
                    return;
                }
            }
            if (result != null) {
                result = runGitWithInput(newMsg, "commit", "-F", "-");
                if (result != null) {
                    statusBarLeft.setText("Squashed " + commits.size() + " commits");
                    loadBranches();
                    loadCommits();
                } else {
                    statusBarLeft.setText("Squash commit failed");
                }
            } else {
                statusBarLeft.setText("Squash reset failed");
            }
        } else {
            // Non-interactive rebase for commits not at HEAD
            boolean ok = rebaseSquash(commits, newMsg);
            if (ok) {
                statusBarLeft.setText("Squashed " + commits.size() + " commits");
                loadBranches();
                loadCommits();
            } else {
                runGitWithExitCode("rebase", "--abort");
                statusBarLeft.setText("Squash failed — rebase aborted");
            }
        }
    }

    private boolean rebaseSquash(List<CommitInfo> commits, String newMsg) {
        try {
            // Write the new message to a temp file
            Path msgFile = Files.createTempFile("visualgit-sqmsg-", ".txt");
            Files.writeString(msgFile, newMsg);
            msgFile.toFile().deleteOnExit();

            // Build sed command to change "pick" to "squash" for all but the oldest commit
            // commits list is newest-first; oldest is last — keep oldest as "pick", squash the rest
            CommitInfo oldest = commits.get(commits.size() - 1);
            StringBuilder sedCmd = new StringBuilder("sed -i.bak ");
            for (CommitInfo ci : commits) {
                if (ci == oldest) continue; // keep as pick
                sedCmd.append("-e 's/^pick ").append(ci.shortHash).append("/squash ").append(ci.shortHash).append("/' ");
            }
            sedCmd.append("\"$1\"");

            // Write sequence editor script
            Path seqScript = Files.createTempFile("visualgit-seqed-", ".sh");
            Files.writeString(seqScript, "#!/bin/sh\n" + sedCmd + "\n");
            seqScript.toFile().setExecutable(true);
            seqScript.toFile().deleteOnExit();

            // GIT_EDITOR: replace editor content with our message
            Path editorScript = Files.createTempFile("visualgit-editor-", ".sh");
            Files.writeString(editorScript, "#!/bin/sh\ncp " + msgFile.toAbsolutePath() + " \"$1\"\n");
            editorScript.toFile().setExecutable(true);
            editorScript.toFile().deleteOnExit();

            Map<String, String> env = new HashMap<>();
            env.put("GIT_SEQUENCE_EDITOR", seqScript.toAbsolutePath().toString());
            env.put("GIT_EDITOR", editorScript.toAbsolutePath().toString());

            // Parent of the oldest commit
            String parent = oldest.parentHashes.isEmpty() ? "--root" : oldest.parentHashes.get(0);
            String result;
            if (oldest.parentHashes.isEmpty()) {
                result = runGitWithEnv(env, "rebase", "-i", "--root");
            } else {
                result = runGitWithEnv(env, "rebase", "-i", parent);
            }
            return result != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Shows a dialog for squash commit message. Returns null if cancelled. */
    private String showSquashDialog(List<CommitInfo> commits, String defaultMsg) {
        Shell dlg = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dlg.setText("Squash " + commits.size() + " Commits");
        dlg.setLayout(new GridLayout(1, false));
        dlg.setSize(500, 350);

        Label info = new Label(dlg, SWT.WRAP);
        info.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        info.setText("Squashing " + commits.size() + " commits into one. Edit the combined message:");

        Text msgText = new Text(dlg, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        msgText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        msgText.setFont(appFont);
        msgText.setText(defaultMsg);

        Composite btnBar = new Composite(dlg, SWT.NONE);
        btnBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        btnBar.setLayout(new RowLayout(SWT.HORIZONTAL));

        String[] result = {null};

        Button okBtn = new Button(btnBar, SWT.PUSH);
        okBtn.setText("Squash");
        okBtn.addListener(SWT.Selection, e -> {
            String txt = msgText.getText().trim();
            if (!txt.isEmpty()) {
                result[0] = txt;
                dlg.close();
            }
        });

        Button cancelBtn = new Button(btnBar, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.addListener(SWT.Selection, e -> dlg.close());

        dlg.setDefaultButton(okBtn);

        // Center on parent
        Rectangle parentBounds = shell.getBounds();
        Point size = dlg.getSize();
        dlg.setLocation(
                parentBounds.x + (parentBounds.width - size.x) / 2,
                parentBounds.y + (parentBounds.height - size.y) / 2);

        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return result[0];
    }

    // ── Data classes ─────────────────────────────────────────────────

    private static class CommitInfo {
        String fullHash;
        String shortHash;
        String author;
        String date;
        String message;
        List<String> parentHashes = new ArrayList<>();
        boolean isWorkingTree;

        // Graph data (computed by computeGraphLanes)
        int lane = -1;
        List<Integer> forkLanes = new ArrayList<>();
        List<Integer> mergeLanes = new ArrayList<>();
        Set<Integer> activeLaneIndices = new HashSet<>();
        Map<Integer, Integer> laneColorMap = new HashMap<>();
    }

    private static class RebaseEntry {
        String action;    // "pick", "reword", "edit", "drop"
        String hash;      // short hash
        String fullHash;
        String message;   // editable for reword
    }

    private void loadRepoHistory() {
        if (!Files.exists(REPO_HISTORY_FILE)) return;
        try {
            List<String> lines = Files.readAllLines(REPO_HISTORY_FILE, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) repoHistory.add(trimmed);
            }
        } catch (IOException ex) { /* ignore */ }
    }

    private void saveRepoHistory() {
        try {
            Files.createDirectories(CONFIG_DIR);
            if (repoHistory.size() > MAX_REPO_HISTORY) repoHistory.subList(MAX_REPO_HISTORY, repoHistory.size()).clear();
            Files.write(REPO_HISTORY_FILE, repoHistory, StandardCharsets.UTF_8);
        } catch (IOException ex) { /* ignore */ }
    }

    private void addRepoHistory(String path) {
        if (path == null || path.isEmpty()) return;
        repoHistory.remove(path);
        repoHistory.add(0, path);
        if (repoHistory.size() > MAX_REPO_HISTORY) repoHistory.subList(MAX_REPO_HISTORY, repoHistory.size()).clear();
        saveRepoHistory();
        // Refresh combo items
        repoPathCombo.removeAll();
        for (String h : repoHistory) repoPathCombo.add(h);
        repoPathCombo.setText(path);
    }

    private static class FileChange {
        char status;       // M, A, D, R, U (unmerged/conflict)
        String path;       // file path (new path for renames)
        String oldPath;    // original path (for renames)
        String name;       // file name only
        String directory;  // directory part
        int additions;
        int deletions;
        boolean staged;    // true if in staging area (index)
        long lastModified; // filesystem mtime in epoch millis, 0 if unknown
    }
}
