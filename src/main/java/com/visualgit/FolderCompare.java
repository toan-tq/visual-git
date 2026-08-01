package com.visualgit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FolderCompare {

    private String leftDirPath;
    private String rightDirPath;
    private Display display;
    private Shell shell;
    private Table table;
    private Label statusBar;
    private Text leftPathText;
    private Text rightPathText;
    private Button leftBrowse;
    private Canvas headerCanvas;
    private Font iconFont;
    private Font labelFont;
    private Font monoFont;
    private Color navIconColor;
    private Color lineNumFg;

    private Color hoverBg;

    // Status colors
    private Color equalBg;
    private Color changedBg;
    private Color leftOnlyBg;
    private Color rightOnlyBg;
    private Color gridColor;

    private List<FolderDiff.FileEntry> entries = new ArrayList<>();
    private List<FolderDiff.FileEntry> displayedEntries = new ArrayList<>();
    private int currentChangeIndex = -1;
    private boolean showEqual = true;
    private Label filterLabel;
    private int sortColumn = -1;
    private boolean sortAscending = true;
    private Label changeIndicator;
    private Set<String> collapsedDirs = new HashSet<>();
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public static void main(String[] args) {
        new FolderCompare(args).run();
    }

    public FolderCompare(String[] args) {
        if (args.length >= 2) {
            leftDirPath = args[0];
            rightDirPath = args[1];
        }
    }

    public void run() {
        Display.setAppName("FolderCompare");
        display = new Display();
        shell = new Shell(display);
        shell.setText("Folder Compare");
        org.eclipse.swt.graphics.Rectangle screenBounds = display.getPrimaryMonitor().getBounds();
        int w = (int)(screenBounds.width * 0.8);
        int h = (int)(screenBounds.height * 0.8);
        shell.setSize(w, h);
        shell.setLocation(screenBounds.x + (screenBounds.width - w) / 2, screenBounds.y + (screenBounds.height - h) / 2);

        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        layout.verticalSpacing = 0;
        shell.setLayout(layout);

        AppTheme.init(display);
        initColors(display);
        monoFont = new Font(display, AppTheme.MONO_FONT, 13, SWT.NORMAL);

        createUI(shell);
        refresh();

        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }

        monoFont.dispose();
        iconFont.dispose();
        labelFont.dispose();
        navIconColor.dispose();
        lineNumFg.dispose();
        disposeColors();
        AppTheme.dispose();
        display.dispose();
    }

    private void initColors(Display display) {
        equalBg    = new Color(display, 255, 255, 255);   // white
        changedBg  = new Color(display, 255, 255, 200);   // light yellow
        leftOnlyBg = new Color(display, 255, 220, 220);   // light red
        rightOnlyBg = new Color(display, 220, 255, 220);  // light green
        hoverBg     = new Color(display, 220, 230, 245);  // light blue hover
        gridColor   = new Color(display, 200, 170, 0);     // column separator (dark yellow)
    }

    private void disposeColors() {
        equalBg.dispose();
        changedBg.dispose();
        leftOnlyBg.dispose();
        rightOnlyBg.dispose();
        hoverBg.dispose();
        gridColor.dispose();
    }

    private void createUI(Shell shell) {
        // Toolbar
        Composite toolbar = new Composite(shell, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout tbLayout = new GridLayout(15, false);
        tbLayout.marginHeight = 4;
        tbLayout.marginWidth = 8;
        tbLayout.horizontalSpacing = 0;
        toolbar.setLayout(tbLayout);

        iconFont = new Font(display, AppTheme.MONO_FONT, 16, SWT.BOLD);
        labelFont = new Font(display, AppTheme.SANS_FONT, 11, SWT.NORMAL);
        navIconColor = new Color(display, 0, 0, 180);
        lineNumFg = new Color(display, 130, 130, 130);

        createToolbarButton(toolbar, "\u2191", "Prev. Change", this::goToPrevChange);
        createToolbarButton(toolbar, "\u2193", "Next Change", this::goToNextChange);

        changeIndicator = new Label(toolbar, SWT.CENTER);
        changeIndicator.setFont(labelFont);
        changeIndicator.setForeground(lineNumFg);
        changeIndicator.setText("");
        GridData ciGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        ciGd.widthHint = 90;
        changeIndicator.setLayoutData(ciGd);

        Label vsep1 = new Label(toolbar, SWT.SEPARATOR | SWT.VERTICAL);
        GridData vsepGd = new GridData(SWT.CENTER, SWT.FILL, false, true);
        vsepGd.heightHint = 40;
        vsep1.setLayoutData(vsepGd);

        createToolbarButton(toolbar, "\u00BB", "Copy \u2192", this::copyToRight);
        createToolbarButton(toolbar, "\u00AB", "\u2190 Copy", this::copyToLeft);

        Label vsep2 = new Label(toolbar, SWT.SEPARATOR | SWT.VERTICAL);
        GridData vsep2Gd = new GridData(SWT.CENTER, SWT.FILL, false, true);
        vsep2Gd.heightHint = 40;
        vsep2.setLayoutData(vsep2Gd);

        createToolbarButton(toolbar, "\u21BB", "Refresh", this::refresh);
        createToolbarButton(toolbar, "\u21C4", "Swap", this::swapFolders);

        Label vsep3 = new Label(toolbar, SWT.SEPARATOR | SWT.VERTICAL);
        GridData vsep3Gd = new GridData(SWT.CENTER, SWT.FILL, false, true);
        vsep3Gd.heightHint = 40;
        vsep3.setLayoutData(vsep3Gd);

        createToolbarButton(toolbar, "\u2716", "Delete", this::deleteSelected);
        createToolbarButton(toolbar, "\u2261", "Filter", this::toggleFilter);

        filterLabel = new Label(toolbar, SWT.CENTER);
        filterLabel.setFont(labelFont);
        filterLabel.setForeground(lineNumFg);
        filterLabel.setText("Show All");
        GridData flGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        flGd.widthHint = 80;
        filterLabel.setLayoutData(flGd);

        Label spacer = new Label(toolbar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Separator
        Label sep = new Label(shell, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Headers: path text fields + browse buttons
        Composite headers = new Composite(shell, SWT.NONE);
        headers.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout hdrLayout = new GridLayout(4, false);
        hdrLayout.marginHeight = 4;
        hdrLayout.marginWidth = 8;
        headers.setLayout(hdrLayout);

        leftPathText = new Text(headers, SWT.BORDER | SWT.SINGLE);
        leftPathText.setText(leftDirPath != null ? leftDirPath : "");
        leftPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        leftPathText.addTraverseListener(e -> {
            if (e.detail == SWT.TRAVERSE_RETURN) {
                leftDirPath = leftPathText.getText().trim();
                refresh();
            }
        });

        leftBrowse = new Button(headers, SWT.PUSH);
        leftBrowse.setText("...");
        leftBrowse.addListener(SWT.Selection, e -> browseFolder(true));

        rightPathText = new Text(headers, SWT.BORDER | SWT.SINGLE);
        rightPathText.setText(rightDirPath != null ? rightDirPath : "");
        rightPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        rightPathText.addTraverseListener(e -> {
            if (e.detail == SWT.TRAVERSE_RETURN) {
                rightDirPath = rightPathText.getText().trim();
                refresh();
            }
        });

        Button rightBrowse = new Button(headers, SWT.PUSH);
        rightBrowse.setText("...");
        rightBrowse.addListener(SWT.Selection, e -> browseFolder(false));

        // Separator
        Label sep2 = new Label(shell, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Custom header canvas
        String[] colNames = {"Left Name", "Left Size", "Left Date", "Status", "Right Date", "Right Size", "Right Name"};
        headerCanvas = new Canvas(shell, SWT.NONE);
        GridData hcGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hcGd.heightHint = 24;
        headerCanvas.setLayoutData(hcGd);
        headerCanvas.setFont(labelFont);
        headerCanvas.addPaintListener(e -> {
            GC gc = e.gc;
            int h = headerCanvas.getBounds().height;
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(0, 0, headerCanvas.getBounds().width, h);
            gc.setFont(labelFont);
            int x = 0;
            for (int i = 0; i < table.getColumnCount(); i++) {
                int w = table.getColumn(i).getWidth();
                gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
                int textX = x + 4;
                gc.drawString(colNames[i], textX, (h - gc.getFontMetrics().getHeight()) / 2, true);
                // Yellow separator line
                if (i < table.getColumnCount() - 1) {
                    gc.setForeground(gridColor);
                    gc.drawLine(x + w - 1, 0, x + w - 1, h);
                }
                x += w;
            }
            // Bottom border
            gc.setForeground(gridColor);
            gc.drawLine(0, h - 1, headerCanvas.getBounds().width, h - 1);
        });

        // Table
        table = new Table(shell, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.setHeaderVisible(false);
        table.setLinesVisible(true);
        table.setFont(monoFont);

        int[] colAligns = {SWT.LEFT, SWT.RIGHT, SWT.LEFT, SWT.CENTER, SWT.LEFT, SWT.RIGHT, SWT.LEFT};
        for (int i = 0; i < colNames.length; i++) {
            TableColumn col = new TableColumn(table, colAligns[i]);
            col.setText(colNames[i]);
            final int colIdx = i;
            col.addListener(SWT.Selection, e -> sortByColumn(colIdx));
        }

        // Auto-resize columns on table resize
        table.addListener(SWT.Resize, e -> {
            resizeColumns();
            headerCanvas.redraw();
        });

        // Draw visible column separator lines on data rows
        table.addListener(SWT.PaintItem, event -> {
            if (event.index >= table.getColumnCount() - 1) return;
            TableColumn[] cols = table.getColumns();
            int colRight = 0;
            for (int i = 0; i <= event.index; i++) {
                colRight += cols[i].getWidth();
            }
            Color old = event.gc.getForeground();
            event.gc.setForeground(gridColor);
            event.gc.drawLine(colRight - 1, event.y, colRight - 1, event.y + event.height);
            event.gc.setForeground(old);
        });

        // Double-click: toggle directory or open FileCompare
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDoubleClick(MouseEvent e) {
                int idx = table.getSelectionIndex();
                if (idx < 0 || idx >= displayedEntries.size()) return;
                FolderDiff.FileEntry entry = displayedEntries.get(idx);
                if (entry.isDirectory) {
                    toggleCollapse(entry.relativePath);
                } else {
                    openFileCompare();
                }
            }
        });

        // Keyboard shortcuts
        display.addFilter(SWT.KeyDown, event -> {
            if (table.isDisposed() || !table.isFocusControl()) return;
            if ((event.stateMask & SWT.MOD1) != 0) {
                switch (event.keyCode) {
                    case SWT.ARROW_UP:   goToPrevChange(); event.doit = false; break;
                    case SWT.ARROW_DOWN: goToNextChange(); event.doit = false; break;
                }
            }
            if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) {
                openFileCompare();
                event.doit = false;
            }
            if (event.keyCode == SWT.DEL) {
                deleteSelected();
                event.doit = false;
            }
        });

        // Status bar
        Label sep3 = new Label(shell, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep3.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        statusBar = new Label(shell, SWT.NONE);
        GridData sbGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sbGd.horizontalIndent = 8;
        statusBar.setLayoutData(sbGd);
        statusBar.setFont(labelFont);
    }

    private Composite createToolbarButton(Composite parent, String icon, String label, Runnable action) {
        Composite btn = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginHeight = 2;
        gl.marginWidth = 8;
        gl.verticalSpacing = 0;
        btn.setLayout(gl);

        Label iconLbl = new Label(btn, SWT.CENTER);
        iconLbl.setText(icon);
        iconLbl.setFont(iconFont);
        iconLbl.setForeground(navIconColor);
        iconLbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Label textLbl = new Label(btn, SWT.CENTER);
        textLbl.setText(label);
        textLbl.setFont(labelFont);
        textLbl.setForeground(lineNumFg);
        textLbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        btn.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        MouseTrackAdapter hoverListener = new MouseTrackAdapter() {
            @Override
            public void mouseEnter(MouseEvent e) {
                btn.setBackground(hoverBg);
                iconLbl.setBackground(hoverBg);
                textLbl.setBackground(hoverBg);
            }
            @Override
            public void mouseExit(MouseEvent e) {
                btn.setBackground(null);
                iconLbl.setBackground(null);
                textLbl.setBackground(null);
            }
        };
        btn.addMouseTrackListener(hoverListener);
        iconLbl.addMouseTrackListener(hoverListener);
        textLbl.addMouseTrackListener(hoverListener);

        MouseAdapter clickListener = new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) { action.run(); }
        };
        btn.addMouseListener(clickListener);
        iconLbl.addMouseListener(clickListener);
        textLbl.addMouseListener(clickListener);

        return btn;
    }

    private void browseFolder(boolean isLeft) {
        DirectoryDialog dialog = new DirectoryDialog(shell);
        dialog.setText(isLeft ? "Select Left Folder" : "Select Right Folder");
        String current = isLeft ? leftDirPath : rightDirPath;
        if (current != null) dialog.setFilterPath(current);
        String selected = dialog.open();
        if (selected != null) {
            if (isLeft) {
                leftDirPath = selected;
                leftPathText.setText(selected);
            } else {
                rightDirPath = selected;
                rightPathText.setText(selected);
            }
            refresh();
        }
    }

    private void refresh() {
        if (leftDirPath == null || leftDirPath.isEmpty()
                || rightDirPath == null || rightDirPath.isEmpty()) {
            entries = new ArrayList<>();
            if (table != null) populateTable();
            if (statusBar != null) updateStatusBar();
            return;
        }

        // Sync title
        shell.setText("Folder Compare - "
            + Paths.get(leftDirPath).getFileName() + "  vs  "
            + Paths.get(rightDirPath).getFileName());

        try {
            entries = FolderDiff.compare(Paths.get(leftDirPath), Paths.get(rightDirPath));
        } catch (IOException e) {
            entries = new ArrayList<>();
            e.printStackTrace();
        }

        currentChangeIndex = -1;
        populateTable();
        updateStatusBar();
        if (changeIndicator != null) updateChangeIndicator();
    }

    private void populateTable() {
        table.removeAll();

        // Build displayed list with filter and collapse applied
        displayedEntries = new ArrayList<>();
        for (FolderDiff.FileEntry entry : entries) {
            if (!showEqual && entry.status == FolderDiff.FileStatus.EQUAL) continue;
            // Skip children of collapsed directories
            if (isUnderCollapsedDir(entry.relativePath)) continue;
            displayedEntries.add(entry);
        }

        for (FolderDiff.FileEntry entry : displayedEntries) {
            TableItem item = new TableItem(table, SWT.NONE);

            // Indent based on path depth
            int depth = (int) entry.relativePath.chars().filter(c -> c == '/' || c == '\\').count();
            String indent = "  ".repeat(depth);
            String name = Paths.get(entry.relativePath).getFileName().toString();

            // Icon prefix for file vs directory
            String icon;
            if (entry.isDirectory) {
                icon = collapsedDirs.contains(entry.relativePath) ? "\u25B6 " : "\u25BC ";
            } else {
                icon = "  ";
            }

            String leftName = indent + icon + name;
            String rightName = indent + icon + name;
            String statusSymbol;
            Color bg;

            switch (entry.status) {
                case EQUAL:
                    statusSymbol = "=";
                    bg = equalBg;
                    break;
                case CHANGED:
                    statusSymbol = "\u2260";
                    bg = changedBg;
                    break;
                case LEFT_ONLY:
                    statusSymbol = "\u2190";
                    bg = leftOnlyBg;
                    rightName = "";
                    break;
                case RIGHT_ONLY:
                    statusSymbol = "\u2192";
                    bg = rightOnlyBg;
                    leftName = "";
                    break;
                default:
                    continue;
            }

            item.setText(new String[] {
                leftName,
                entry.status != FolderDiff.FileStatus.RIGHT_ONLY ? formatSize(entry.leftSize) : "",
                formatDate(entry.leftModified),
                statusSymbol,
                formatDate(entry.rightModified),
                entry.status != FolderDiff.FileStatus.LEFT_ONLY ? formatSize(entry.rightSize) : "",
                rightName
            });
            item.setBackground(bg);
            item.setData(entry);
        }
    }

    private boolean isUnderCollapsedDir(String relativePath) {
        for (String collapsed : collapsedDirs) {
            String prefix = collapsed + java.io.File.separator;
            if (relativePath.startsWith(prefix) && !relativePath.equals(collapsed)) {
                return true;
            }
        }
        return false;
    }

    private String formatDate(long millis) {
        if (millis < 0) return "";
        return DATE_FMT.format(new Date(millis));
    }

    private void updateStatusBar() {
        int total = entries.size();
        int equal = 0, changed = 0, leftOnly = 0, rightOnly = 0;
        for (FolderDiff.FileEntry e : entries) {
            switch (e.status) {
                case EQUAL: equal++; break;
                case CHANGED: changed++; break;
                case LEFT_ONLY: leftOnly++; break;
                case RIGHT_ONLY: rightOnly++; break;
            }
        }
        statusBar.setText(total + " files: " + equal + " equal, " + changed + " changed, "
            + leftOnly + " left only, " + rightOnly + " right only");
    }

    private String formatSize(long size) {
        if (size < 0) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024));
    }

    private void goToNextChange() {
        if (displayedEntries.isEmpty()) return;
        int start = currentChangeIndex + 1;
        for (int i = 0; i < displayedEntries.size(); i++) {
            int idx = (start + i) % displayedEntries.size();
            if (displayedEntries.get(idx).status != FolderDiff.FileStatus.EQUAL) {
                currentChangeIndex = idx;
                table.setSelection(idx);
                table.showSelection();
                updateChangeIndicator();
                return;
            }
        }
    }

    private void goToPrevChange() {
        if (displayedEntries.isEmpty()) return;
        int start = currentChangeIndex - 1;
        if (start < 0) start = displayedEntries.size() - 1;
        for (int i = 0; i < displayedEntries.size(); i++) {
            int idx = (start - i + displayedEntries.size()) % displayedEntries.size();
            if (displayedEntries.get(idx).status != FolderDiff.FileStatus.EQUAL) {
                currentChangeIndex = idx;
                table.setSelection(idx);
                table.showSelection();
                updateChangeIndicator();
                return;
            }
        }
    }

    private void openFileCompare() {
        int idx = table.getSelectionIndex();
        if (idx < 0 || idx >= displayedEntries.size()) return;

        FolderDiff.FileEntry entry = displayedEntries.get(idx);
        if (entry.isDirectory) return;

        Path leftFile = Paths.get(leftDirPath, entry.relativePath);
        Path rightFile = Paths.get(rightDirPath, entry.relativePath);

        // Only open if at least one side exists and it's a file
        String left = Files.exists(leftFile) ? leftFile.toString() : null;
        String right = Files.exists(rightFile) ? rightFile.toString() : null;
        if (left == null && right == null) return;

        // For one-sided entries, pass empty string so FileCompare shows empty pane
        if (left == null) left = "";
        if (right == null) right = "";

        FileCompare fc = new FileCompare(left, right, display, shell);
        fc.openWindow();
    }

    private void copyToRight() {
        int idx = table.getSelectionIndex();
        if (idx < 0 || idx >= displayedEntries.size()) return;
        FolderDiff.FileEntry entry = displayedEntries.get(idx);
        if (entry.isDirectory) {
            statusBar.setText("Cannot copy directories");
            return;
        }
        if (entry.status == FolderDiff.FileStatus.RIGHT_ONLY) {
            statusBar.setText("File only exists on right side");
            return;
        }

        Path src = Paths.get(leftDirPath, entry.relativePath);
        Path dst = Paths.get(rightDirPath, entry.relativePath);
        copyFile(src, dst, "left \u2192 right");
    }

    private void copyToLeft() {
        int idx = table.getSelectionIndex();
        if (idx < 0 || idx >= displayedEntries.size()) return;
        FolderDiff.FileEntry entry = displayedEntries.get(idx);
        if (entry.isDirectory) {
            statusBar.setText("Cannot copy directories");
            return;
        }
        if (entry.status == FolderDiff.FileStatus.LEFT_ONLY) {
            statusBar.setText("File only exists on left side");
            return;
        }

        Path src = Paths.get(rightDirPath, entry.relativePath);
        Path dst = Paths.get(leftDirPath, entry.relativePath);
        copyFile(src, dst, "right \u2192 left");
    }

    private void copyFile(Path src, Path dst, String direction) {
        if (!Files.exists(src)) {
            statusBar.setText("Source not found: " + src.getFileName());
            return;
        }
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            statusBar.setText("Copied " + direction + ": " + src.getFileName());
            refresh();
        } catch (IOException e) {
            MessageBox errBox = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
            errBox.setText("Copy Error");
            errBox.setMessage("Failed to copy " + src.getFileName() + ":\n" + e.getMessage());
            errBox.open();
            statusBar.setText("Copy failed: " + e.getMessage());
        }
    }

    private void toggleFilter() {
        showEqual = !showEqual;
        filterLabel.setText(showEqual ? "Show All" : "Diff Only");
        currentChangeIndex = -1;
        populateTable();
        updateStatusBar();
    }

    private void toggleCollapse(String dirPath) {
        if (collapsedDirs.contains(dirPath)) {
            collapsedDirs.remove(dirPath);
        } else {
            collapsedDirs.add(dirPath);
        }
        currentChangeIndex = -1;
        populateTable();
    }

    private void resizeColumns() {
        if (table.isDisposed() || leftBrowse == null || leftBrowse.isDisposed()) return;
        int tableWidth = table.getClientArea().width;
        int sizeW = 100, dateW = 150, statusW = 80;

        // Compute browse button center relative to the table
        int btnCenterX = leftBrowse.getBounds().x + leftBrowse.getBounds().width / 2;
        Point mapped = display.map(leftBrowse.getParent(), table, btnCenterX, 0);
        int browseCenter = mapped.x;

        // Status column center should align with browse button center
        int leftNameW = browseCenter - sizeW - dateW - statusW / 2;
        int rightNameW = tableWidth - leftNameW - sizeW - dateW - statusW - dateW - sizeW;

        leftNameW = Math.max(100, leftNameW);
        rightNameW = Math.max(100, rightNameW);

        table.getColumn(0).setWidth(leftNameW);    // Left Name
        table.getColumn(1).setWidth(sizeW);         // Left Size
        table.getColumn(2).setWidth(dateW);         // Left Date
        table.getColumn(3).setWidth(statusW);       // Status
        table.getColumn(4).setWidth(dateW);         // Right Date
        table.getColumn(5).setWidth(sizeW);         // Right Size
        table.getColumn(6).setWidth(rightNameW);    // Right Name
    }

    private void swapFolders() {
        String tmp = leftDirPath;
        leftDirPath = rightDirPath;
        rightDirPath = tmp;
        leftPathText.setText(leftDirPath != null ? leftDirPath : "");
        rightPathText.setText(rightDirPath != null ? rightDirPath : "");
        collapsedDirs.clear();
        refresh();
    }

    private void updateChangeIndicator() {
        int total = 0;
        for (FolderDiff.FileEntry e : displayedEntries) {
            if (e.status != FolderDiff.FileStatus.EQUAL) total++;
        }
        if (currentChangeIndex >= 0 && total > 0) {
            // Count which change number this is
            int changeNum = 0;
            for (int i = 0; i <= currentChangeIndex && i < displayedEntries.size(); i++) {
                if (displayedEntries.get(i).status != FolderDiff.FileStatus.EQUAL) changeNum++;
            }
            changeIndicator.setText(changeNum + " / " + total);
        } else {
            changeIndicator.setText(total > 0 ? "0 / " + total : "");
        }
    }

    private void deleteSelected() {
        int idx = table.getSelectionIndex();
        if (idx < 0 || idx >= displayedEntries.size()) return;
        FolderDiff.FileEntry entry = displayedEntries.get(idx);
        if (entry.isDirectory) return;

        // Only allow deleting one-sided files
        if (entry.status != FolderDiff.FileStatus.LEFT_ONLY
                && entry.status != FolderDiff.FileStatus.RIGHT_ONLY) {
            statusBar.setText("Can only delete files that exist on one side only");
            return;
        }

        Path file;
        if (entry.status == FolderDiff.FileStatus.LEFT_ONLY) {
            file = Paths.get(leftDirPath, entry.relativePath);
        } else {
            file = Paths.get(rightDirPath, entry.relativePath);
        }

        MessageBox confirm = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
        confirm.setText("Delete File");
        confirm.setMessage("Delete " + file + "?");
        if (confirm.open() != SWT.YES) return;

        try {
            Files.deleteIfExists(file);
            statusBar.setText("Deleted: " + file.getFileName());
            refresh();
        } catch (IOException e) {
            MessageBox errBox = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
            errBox.setText("Delete Error");
            errBox.setMessage("Failed to delete " + file.getFileName() + ":\n" + e.getMessage());
            errBox.open();
            statusBar.setText("Delete failed: " + e.getMessage());
        }
    }

    private void sortByColumn(int colIdx) {
        if (sortColumn == colIdx) {
            sortAscending = !sortAscending;
        } else {
            sortColumn = colIdx;
            sortAscending = true;
        }

        // Update sort indicator on column headers
        table.setSortColumn(table.getColumn(colIdx));
        table.setSortDirection(sortAscending ? SWT.UP : SWT.DOWN);

        Comparator<FolderDiff.FileEntry> cmp;
        switch (colIdx) {
            case 0: // Left Name
            case 6: // Right Name
                cmp = Comparator.comparing(e -> e.relativePath.toLowerCase());
                break;
            case 1: // Left Size
                cmp = Comparator.comparingLong(e -> e.leftSize);
                break;
            case 2: // Left Date
                cmp = Comparator.comparingLong(e -> e.leftModified);
                break;
            case 3: // Status
                cmp = Comparator.comparingInt(e -> e.status.ordinal());
                break;
            case 4: // Right Date
                cmp = Comparator.comparingLong(e -> e.rightModified);
                break;
            case 5: // Right Size
                cmp = Comparator.comparingLong(e -> e.rightSize);
                break;
            default:
                return;
        }
        if (!sortAscending) cmp = cmp.reversed();
        entries.sort(cmp);
        currentChangeIndex = -1;
        populateTable();
    }
}
