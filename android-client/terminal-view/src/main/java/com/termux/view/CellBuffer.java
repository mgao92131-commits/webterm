package com.termux.view;

import com.termux.terminal.TextStyle;

/**
 * In-memory cell buffer for structured terminal rendering.
 * Stores the visible screen as a 2D Cell array plus scrollback history.
 * Independent of termux TerminalBuffer/TerminalRow.
 */
public class CellBuffer {

    private Cell[][] cells;       // [rows][cols]
    private int cols;
    private int rows;
    private CursorState cursor = new CursorState();
    private TerminalModes modes = new TerminalModes();
    private boolean altScreen;
    private int[] palette;        // 259 indexed colors
    private String title = "";

    // Scrollback history (lines above the visible screen)
    private java.util.ArrayList<Line> scrollback = new java.util.ArrayList<>();

    public CellBuffer() {
        this(80, 24);
    }

    public CellBuffer(int cols, int rows) {
        resize(cols, rows);
        initDefaultPalette();
    }

    private void resize(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        this.cells = new Cell[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cells[r][c] = new Cell();
            }
        }
    }

    // ================================================================
    // Data input
    // ================================================================

    public void applySnapshot(TerminalDataModel.Snapshot snap) {
        if (snap.cols != cols || snap.rows != rows) {
            resize(snap.cols, snap.rows);
        }
        for (int r = 0; r < rows && r < snap.lines.length; r++) {
            TerminalDataModel.Line line = snap.lines[r];
            for (int c = 0; c < cols && c < line.cells.length; c++) {
                cells[r][c] = fromModelCell(line.cells[c]);
            }
        }
        if (snap.cursor != null) {
            cursor = fromModelCursor(snap.cursor);
        }
        if (snap.modes != null) {
            modes = fromModelModes(snap.modes);
        }
        altScreen = snap.altScreen;
    }

    public void applySnapshot(byte[] jsonBytes) {
        applySnapshot(TerminalDataModel.Snapshot.fromJson(jsonBytes));
    }

    public void applyPatch(TerminalDataModel.Patch patch) {
        if (patch.rowPatches != null) {
            for (TerminalDataModel.RowPatch rp : patch.rowPatches) {
                if (rp.rowIndex >= 0 && rp.rowIndex < rows && rp.cells != null) {
                    for (int c = 0; c < cols && c < rp.cells.length; c++) {
                        cells[rp.rowIndex][c] = fromModelCell(rp.cells[c]);
                    }
                }
            }
        }
        if (patch.cursor != null) {
            cursor = fromModelCursor(patch.cursor);
        }
        if (patch.modes != null) {
            modes = fromModelModes(patch.modes);
        }
    }

    public void applyPatch(byte[] jsonBytes) {
        applyPatch(TerminalDataModel.Patch.fromJson(jsonBytes));
    }

    public void appendScrollback(TerminalDataModel.ScrollbackData data) {
        if (data.lines != null) {
            for (TerminalDataModel.Line line : data.lines) {
                scrollback.add(fromModelLine(line));
            }
        }
    }

    public void appendScrollback(byte[] jsonBytes) {
        appendScrollback(TerminalDataModel.ScrollbackData.fromJson(jsonBytes));
    }

    // ================================================================
    // Accessors
    // ================================================================

    public Cell getCell(int row, int col) {
        if (row >= 0 && row < rows && col >= 0 && col < cols) {
            return cells[row][col];
        }
        return null;
    }

    /** Get a scrollback line by negative index (-1 = most recent). */
    public Line getScrollbackLine(int index) {
        int idx = scrollback.size() + index;
        if (idx >= 0 && idx < scrollback.size()) {
            return scrollback.get(idx);
        }
        return null;
    }

    public int getScrollbackSize() {
        return scrollback.size();
    }

    public Cell[] getRow(int row) {
        if (row >= 0 && row < rows) {
            return cells[row];
        }
        // Negative row → scrollback history line (-1 = most recent).
        // Pad to current cols so the renderer loop doesn't go out of bounds
        // (scrollback lines may have a different column count from a prior resize).
        if (row < 0) {
            Line line = getScrollbackLine(row);
            if (line != null) {
                return padToCols(line.cells);
            }
        }
        return null;
    }

    /** Ensure a scrollback row has exactly {@code cols} cells. */
    private Cell[] padToCols(Cell[] source) {
        if (source == null) return null;
        if (source.length == cols) return source;
        Cell[] padded = new Cell[cols];
        int copyLen = Math.min(source.length, cols);
        System.arraycopy(source, 0, padded, 0, copyLen);
        // Fill remaining columns with empty cells
        for (int i = copyLen; i < cols; i++) {
            padded[i] = new Cell();
        }
        return padded;
    }

    public int getCols() { return cols; }
    public int getRows() { return rows; }

    public CursorState getCursor() { return cursor; }
    public void setCursor(CursorState c) { this.cursor = c; }

    public TerminalModes getModes() { return modes; }
    public boolean isAltScreen() { return altScreen; }

    public int[] getPalette() { return palette; }

    public void updatePalette(int index, int color) {
        if (index >= 0 && index < palette.length) {
            palette[index] = color;
        }
    }

    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }

    // ================================================================
    // Internal Cell class (lightweight, independent of data model)
    // ================================================================

    public static class Cell {
        public String text = " ";
        public int width = 1;       // 0=combining, 1=normal, 2=wide
        public String fg = "";      // "#rrggbb" | "ansi:N" | ""
        public String bg = "";
        public CellFlags flags = new CellFlags();
    }

    public static class CellFlags {
        public boolean bold;
        public boolean dim;
        public boolean italic;
        public boolean underline;
        public boolean blink;
        public boolean inverse;
        public boolean hidden;
        public boolean strike;
    }

    public static class CursorState {
        public int row;
        public int col;
        public boolean visible = true;
        public String style = "block";  // "block" | "underline" | "bar"
    }

    public static class TerminalModes {
        public boolean appCursorKeys;
        public boolean appKeypad;
        public boolean mouseTracking;
        public boolean mouseSgr;
        public boolean bracketedPaste;
        public boolean autoWrap = true;
        public boolean originMode;
        public boolean reverseVideo;
        public boolean cursorEnabled = true;
        public boolean insertMode;
        public boolean leftRightMargin;
    }

    public static class Line {
        public int index;
        public Cell[] cells;
        public boolean wrapped;
    }

    // ================================================================
    // Conversion helpers
    // ================================================================

    private static Cell fromModelCell(TerminalDataModel.Cell mc) {
        Cell c = new Cell();
        c.text = mc.text;
        c.width = mc.width;
        c.fg = mc.fg;
        c.bg = mc.bg;
        c.flags.bold = mc.flags.bold;
        c.flags.dim = mc.flags.dim;
        c.flags.italic = mc.flags.italic;
        c.flags.underline = mc.flags.underline;
        c.flags.blink = mc.flags.blink;
        c.flags.inverse = mc.flags.inverse;
        c.flags.hidden = mc.flags.hidden;
        c.flags.strike = mc.flags.strike;
        return c;
    }

    private static CursorState fromModelCursor(TerminalDataModel.CursorState mcs) {
        CursorState cs = new CursorState();
        cs.row = mcs.row;
        cs.col = mcs.col;
        cs.visible = mcs.visible;
        cs.style = mcs.style;
        return cs;
    }

    private static TerminalModes fromModelModes(TerminalDataModel.TerminalModes mm) {
        TerminalModes m = new TerminalModes();
        m.appCursorKeys = mm.appCursorKeys;
        m.appKeypad = mm.appKeypad;
        m.mouseTracking = mm.mouseTracking;
        m.mouseSgr = mm.mouseSgr;
        m.bracketedPaste = mm.bracketedPaste;
        m.autoWrap = mm.autoWrap;
        m.originMode = mm.originMode;
        m.reverseVideo = mm.reverseVideo;
        m.cursorEnabled = mm.cursorEnabled;
        m.insertMode = mm.insertMode;
        m.leftRightMargin = mm.leftRightMargin;
        return m;
    }

    private static Line fromModelLine(TerminalDataModel.Line ml) {
        Line l = new Line();
        l.index = ml.index;
        l.wrapped = ml.wrapped;
        if (ml.cells != null) {
            l.cells = new Cell[ml.cells.length];
            for (int i = 0; i < ml.cells.length; i++) {
                l.cells[i] = fromModelCell(ml.cells[i]);
            }
        }
        return l;
    }

    // ================================================================
    // Default xterm 256-color palette
    // ================================================================

    private void initDefaultPalette() {
        palette = new int[259];

        // Standard colors 0-7
        int[] std = {
            0xFF000000, 0xFFCC0000, 0xFF4E9A06, 0xFFC4A000,
            0xFF3465A4, 0xFF75507B, 0xFF06989A, 0xFFD3D7CF,
        };
        System.arraycopy(std, 0, palette, 0, 8);

        // Bright colors 8-15
        int[] bright = {
            0xFF555753, 0xFFEF2929, 0xFF8AE234, 0xFFFCE94F,
            0xFF729FCF, 0xFFAD7FA8, 0xFF34E2E2, 0xFFEEEEEC,
        };
        System.arraycopy(bright, 0, palette, 8, 8);

        // 6x6x6 color cube (16-231)
        int idx = 16;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    int red = r == 0 ? 0 : r * 51;
                    int green = g == 0 ? 0 : g * 51;
                    int blue = b == 0 ? 0 : b * 51;
                    palette[idx++] = 0xFF000000 | (red << 16) | (green << 8) | blue;
                }
            }
        }

        // Grayscale ramp (232-255)
        for (int i = 0; i < 24; i++) {
            int gray = 8 + i * 10;
            palette[232 + i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }

        // Default foreground (white), background (black), cursor (white)
        palette[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFD3D7CF;
        palette[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF000000;
        palette[TextStyle.COLOR_INDEX_CURSOR] = 0xFFD3D7CF;
    }
}
