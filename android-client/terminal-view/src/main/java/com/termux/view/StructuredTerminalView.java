package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Scroller;

import com.termux.terminal.KeyHandler;

/**
 * A terminal View that consumes structured Cell data (Snapshot/Patch) from
 * a Go backend, instead of parsing ANSI escape sequences locally.
 *
 * Public API is compatible with {@link TerminalView} so existing integration
 * code (TerminalLifecycleController, TerminalScreenBuilder) needs minimal changes.
 */
public class StructuredTerminalView extends View {

    // ================================================================
    // Data
    // ================================================================

    private CellBuffer cellBuffer;
    private CellRenderer renderer;

    // ================================================================
    // Appearance
    // ================================================================

    private int textSize = 14;
    private Typeface typeface = Typeface.MONOSPACE;
    private TerminalViewClient client;

    // ================================================================
    // Cursor blink
    // ================================================================

    private boolean cursorBlinkState = true;
    private int cursorBlinkerRate = 500;
    private Handler cursorBlinkerHandler;
    private CursorBlinkerRunnable cursorBlinkerRunnable;
    private boolean cursorInvisibleIgnoreOnce;

    // ================================================================
    // Scrolling
    // ================================================================

    private int topRow = 0;
    private float scrollRemainder;
    private final Scroller scroller;
    private final GestureDetector gestureDetector;

    // ================================================================
    // Text selection
    // ================================================================

    private int selX1 = -1, selY1 = -1, selX2 = -1, selY2 = -1;
    private boolean selectingText;

    // ================================================================
    // Input
    // ================================================================

    private int combiningAccent;

    public StructuredTerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scroller = new Scroller(context);
        cellBuffer = new CellBuffer();
        renderer = new CellRenderer(textSize, typeface);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true; // must return true for onScroll/onFling to fire
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (renderer == null || cellBuffer == null) return false;
                scrollRemainder += distanceY;
                int deltaRows = (int) (scrollRemainder / renderer.getFontLineSpacing());
                scrollRemainder -= deltaRows * renderer.getFontLineSpacing();

                int scrollbackSize = cellBuffer.getScrollbackSize();
                int newTopRow = Math.max(-scrollbackSize, Math.min(0, topRow + deltaRows));
                if (newTopRow != topRow) {
                    topRow = newTopRow;
                    invalidate();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (renderer == null || cellBuffer == null) return false;
                if (!scroller.isFinished()) scroller.abortAnimation();

                int scrollbackSize = cellBuffer.getScrollbackSize();
                // velocityY is pixels/sec; convert to rows/sec then scale down for inertia feel
                int velocityRows = (int) (velocityY / renderer.getFontLineSpacing());
                scroller.fling(0, topRow, 0, velocityRows,
                        0, 0, -scrollbackSize, 0);

                post(new Runnable() {
                    @Override
                    public void run() {
                        if (scroller.isFinished()) return;
                        if (scroller.computeScrollOffset()) {
                            int newTopRow = scroller.getCurrY();
                            if (newTopRow != topRow) {
                                topRow = newTopRow;
                                invalidate();
                            }
                            post(this);
                        }
                    }
                });
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (client != null) {
                    client.onSingleTapUp(e);
                }
                return true;
            }
        });

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // ================================================================
    // Appearance API (compatible with TerminalView)
    // ================================================================

    public void setTextSize(int size) {
        if (size <= 0) return;
        this.textSize = size;
        this.renderer = new CellRenderer(size, typeface);
        updateSize();
    }

    public void setTypeface(Typeface tf) {
        if (tf == null) return;
        this.typeface = tf;
        this.renderer = new CellRenderer(textSize, tf);
        updateSize();
    }

    public void setTerminalViewClient(TerminalViewClient c) {
        this.client = c;
    }

    public TerminalViewClient getTerminalViewClient() {
        return client;
    }

    // ================================================================
    // Structured data input API
    // ================================================================

    /** Apply a full terminal snapshot (initial connect / terminal switch). */
    public void applySnapshot(byte[] jsonBytes) {
        if (cellBuffer == null) return;
        cellBuffer.applySnapshot(jsonBytes);
        onScreenUpdated();
    }

    /** Apply an incremental patch (33ms throttle cycle). */
    public void applyPatch(byte[] jsonBytes) {
        if (cellBuffer == null) return;
        cellBuffer.applyPatch(jsonBytes);
        onScreenUpdated();
    }

    /** Append scrollback history lines. */
    public void appendScrollback(byte[] jsonBytes) {
        if (cellBuffer == null) return;
        cellBuffer.appendScrollback(jsonBytes);
    }

    public CellBuffer getCellBuffer() {
        return cellBuffer;
    }

    // ================================================================
    // Input API (compatible with TerminalView)
    // ================================================================

    /** Callback for sending input bytes to the remote terminal. */
    public interface OnInputListener {
        void onInput(byte[] data);
    }

    private OnInputListener inputListener;

    /** Set the listener that receives input bytes for sending to remote. */
    public void setOnInputListener(OnInputListener listener) {
        this.inputListener = listener;
    }

    /** Write text to the remote terminal via the input listener. */
    public void write(String text) {
        if (text != null && text.length() > 0) {
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (inputListener != null) {
                inputListener.onInput(bytes);
            }
        }
    }

    /** Send raw bytes (e.g. from KeyHandler escape sequences). */
    public void sendInput(byte[] data) {
        if (inputListener != null && data != null && data.length > 0) {
            inputListener.onInput(data);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (client != null && client.onKeyDown(keyCode, event, null)) {
            return true;
        }
        // Use KeyHandler to translate key codes to escape sequences
        String code = KeyHandler.getCode(keyCode, event.getMetaState(),
            client != null && client.readControlKey(),
            client != null && client.readAltKey());
        if (code != null) {
            byte[] bytes = code.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sendInput(bytes);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void inputCodePoint(int source, int codePoint, boolean ctrlDown, boolean altDown) {
        if (client != null) {
            if (client.onCodePoint(codePoint, ctrlDown, null)) return;
        }

        if (combiningAccent != 0) {
            // Try to combine
            combiningAccent = 0;
        }

        // Encode code point to UTF-8 bytes and send
        String text = new String(Character.toChars(codePoint));
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        sendInput(bytes);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | EditorInfo.IME_FLAG_NO_FULLSCREEN;

        return new BaseInputConnection(this, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    inputCodePoint(0, c, false, false);
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength > 0) {
                    inputCodePoint(0, 0x7f, false, false); // DEL
                }
                return true;
            }
        };
    }

    // ================================================================
    // Scrolling API (compatible with TerminalView)
    // ================================================================

    public void onScreenUpdated() {
        // Stay at bottom if already there
        if (topRow == 0) {
            invalidate();
        }
    }

    public void onScreenUpdated(boolean keepScrollPosition) {
        invalidate();
    }

    public int getTopRow() { return topRow; }

    public void setTopRow(int row) {
        this.topRow = row;
        invalidate();
    }

    // ================================================================
    // Text selection API
    // ================================================================

    public boolean isSelectingText() { return selectingText; }

    public String getSelectedText() {
        if (selX1 < 0 || selY1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int row = selY1; row <= selY2; row++) {
            CellBuffer.Cell[] rowCells = cellBuffer.getRow(row);
            if (rowCells == null) continue;
            int startX = (row == selY1) ? selX1 : 0;
            int endX = (row == selY2) ? selX2 : cellBuffer.getCols() - 1;
            for (int col = startX; col <= endX && col < rowCells.length; col++) {
                CellBuffer.Cell cell = rowCells[col];
                if (cell.width > 0 && cell.text != null && !cell.text.equals(" ")) {
                    sb.append(cell.text);
                }
            }
            if (row < selY2) sb.append('\n');
        }
        return sb.toString();
    }

    public void clearSelection() {
        selX1 = selY1 = selX2 = selY2 = -1;
        selectingText = false;
        invalidate();
    }

    // ================================================================
    // Size API (compatible with TerminalView)
    // ================================================================

    public void updateSize() {
        if (renderer == null) return;
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        int cols = (int) (viewWidth / renderer.getFontWidth());
        int rows = (int) (viewHeight / renderer.getFontLineSpacing());
        if (cols < 2) cols = 2;
        if (rows < 2) rows = 2;

        // Resize is handled externally by TerminalLifecycleController → TerminalConnection
        // (which calls client.onTerminalResize(cols, rows) on the TerminalViewClient).
        // No local emulator to resize here.
    }

    /**
     * Convert pixel coordinates from a MotionEvent to column/row.
     * @param event  the touch event
     * @param scale  whether to apply scale factor
     * @param out    int[2] to receive [col, row]
     */
    public void getColumnAndRow(MotionEvent event, boolean scale, int[] out) {
        if (renderer == null) {
            out[0] = 0; out[1] = 0;
            return;
        }
        out[0] = (int) (event.getX() / renderer.getFontWidth());
        out[1] = (int) ((event.getY() - renderer.mFontLineSpacingAndAscent)
            / renderer.getFontLineSpacing()) + topRow;
        if (out[0] < 0) out[0] = 0;
        if (out[1] < topRow) out[1] = topRow;
    }

    // ================================================================
    // Cursor blink
    // ================================================================

    public void setTerminalCursorBlinkerRate(int rate) {
        this.cursorBlinkerRate = rate;
    }

    public void setTerminalCursorBlinkerState(boolean start, boolean startOnlyIfCursorEnabled) {
        if (start) {
            if (cursorBlinkerHandler == null) {
                cursorBlinkerHandler = new Handler(Looper.getMainLooper());
            }
            if (cursorBlinkerRunnable == null) {
                cursorBlinkerRunnable = new CursorBlinkerRunnable();
            }
            cursorBlinkerHandler.removeCallbacks(cursorBlinkerRunnable);
            if (cursorBlinkerRate > 0) {
                cursorBlinkerHandler.postDelayed(cursorBlinkerRunnable, cursorBlinkerRate);
            }
        } else {
            if (cursorBlinkerHandler != null && cursorBlinkerRunnable != null) {
                cursorBlinkerHandler.removeCallbacks(cursorBlinkerRunnable);
            }
            cursorBlinkState = true;
            invalidate();
        }
    }

    public void onResume() {
        setTerminalCursorBlinkerState(true, false);
    }

    public void onPause() {
        setTerminalCursorBlinkerState(false, false);
    }

    private class CursorBlinkerRunnable implements Runnable {
        @Override
        public void run() {
            if (cursorBlinkerHandler == null) return;
            cursorBlinkState = !cursorBlinkState;
            invalidate();
            cursorBlinkerHandler.postDelayed(this, cursorBlinkerRate);
        }
    }

    // ================================================================
    // Drawing
    // ================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (renderer == null || cellBuffer == null) return;

        // Override cursor visibility with blink state
        CellBuffer.CursorState cursor = cellBuffer.getCursor();
        boolean originalVisible = cursor.visible;
        if (cursorBlinkerRate > 0 && !cursorBlinkState) {
            cursor.visible = false;
        }
        if (cursorInvisibleIgnoreOnce) {
            cursor.visible = false;
            cursorInvisibleIgnoreOnce = false;
        }

        renderer.render(cellBuffer, canvas, topRow,
            selY1, selY2, selX1, selX2);

        cursor.visible = originalVisible;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            updateSize();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (!scroller.isFinished()) scroller.abortAnimation();
        }
        gestureDetector.onTouchEvent(event);
        return true;
    }

    // ================================================================
    // Backward compatibility with TerminalView API
    // ================================================================

    /**
     * No-op for backward compatibility. In cell mode, terminal emulation
     * is on the server side — no local {@code TerminalSession} needed.
     */
    public void attachSession(com.termux.terminal.TerminalSession session) {
        // In cell mode, terminal emulation is on the server side.
        // No local TerminalSession needed.
    }

    // ================================================================
    // Public getters
    // ================================================================

    public CellRenderer getRenderer() { return renderer; }
}
