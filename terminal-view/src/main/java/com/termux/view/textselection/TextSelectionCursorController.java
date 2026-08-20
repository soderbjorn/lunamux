package com.termux.view.textselection;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.WcWidth;
import com.termux.view.R;
import com.termux.view.TerminalView;

public class TextSelectionCursorController implements CursorController {

    private final TerminalView terminalView;
    private final TextSelectionHandleView mStartHandle, mEndHandle;
    private boolean mIsSelectingText = false;
    private long mShowStartTime = System.currentTimeMillis();

    private final int mHandleHeight;
    private int mSelX1 = -1, mSelX2 = -1, mSelY1 = -1, mSelY2 = -1;

    private ActionMode mActionMode;
    public final int ACTION_COPY = 1;
    public final int ACTION_PASTE = 2;

    /** Whether "Paste" was last built enabled, so a refresh can skip a no-op menu rebuild. */
    private boolean mPasteEnabled;

    /**
     * Set when the system clipboard has changed under a live selection bar, so the next
     * {@code onPrepareActionMode} re-reads it. Event-driven rather than polled: {@link #render()}
     * invalidates the action mode on every frame, and {@code hasPrimaryClip()} is a binder call
     * into the clipboard service -- asking it per frame would put a synchronous IPC in the draw
     * path. It is also the only accurate way to do it, since from API 29 the clipboard reads as
     * empty whenever the app is unfocused.
     */
    private boolean mClipboardDirty;

    /** Registered for the lifetime of the action mode; null when nothing is listening. */
    @Nullable
    private ClipboardManager.OnPrimaryClipChangedListener mClipChangedListener;

    public TextSelectionCursorController(TerminalView terminalView) {
        this.terminalView = terminalView;
        mStartHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT);
        mEndHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT);

        mHandleHeight = Math.max(mStartHandle.getHandleHeight(), mEndHandle.getHandleHeight());
    }

    @Override
    public void show(MotionEvent event) {
        setInitialTextSelectionPosition(event);
        mStartHandle.positionAtCursor(mSelX1, mSelY1, true);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, true);

        setActionModeCallBacks();
        mShowStartTime = System.currentTimeMillis();
        mIsSelectingText = true;
    }

    @Override
    public boolean hide() {
        if (!isActive()) return false;

        // prevent hide calls right after a show call, like long pressing the down key
        // 300ms seems long enough that it wouldn't cause hide problems if action button
        // is quickly clicked after the show, otherwise decrease it
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false;
        }

        mStartHandle.hide();
        mEndHandle.hide();

        if (mActionMode != null) {
            // This will hide the TextSelectionCursorController
            mActionMode.finish();
        }

        mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
        mIsSelectingText = false;

        return true;
    }

    @Override
    public void render() {
        if (!isActive()) return;

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false);

        if (mActionMode != null) {
            mActionMode.invalidate();
        }
    }

    public void setInitialTextSelectionPosition(MotionEvent event) {
        int[] columnAndRow = terminalView.getColumnAndRow(event, true);
        mSelX1 = mSelX2 = columnAndRow[0];
        mSelY1 = mSelY2 = columnAndRow[1];

        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        if (!" ".equals(screen.getSelectedText(mSelX1, mSelY1, mSelX1, mSelY1))) {
            // Selecting something other than whitespace. Expand to word.
            while (mSelX1 > 0 && !"".equals(screen.getSelectedText(mSelX1 - 1, mSelY1, mSelX1 - 1, mSelY1))) {
                mSelX1--;
            }
            while (mSelX2 < terminalView.mEmulator.mColumns - 1 && !"".equals(screen.getSelectedText(mSelX2 + 1, mSelY1, mSelX2 + 1, mSelY1))) {
                mSelX2++;
            }
        }
    }
    
    public void setActionModeCallBacks() {
        final ActionMode.Callback callback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                int show = MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT;

                mPasteEnabled = clipboardHasText();
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.copy_text).setShowAsAction(show);
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, R.string.paste_text).setEnabled(mPasteEnabled).setShowAsAction(show);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                // LUNAMUX: keep "Paste" in step with the clipboard for the life of the
                // selection. Upstream built the item once and returned false forever, so a
                // selection begun with an empty clipboard kept a greyed-out Paste even after
                // the user had copied something -- a disabled button being indistinguishable
                // from a dead one (LMX-120).
                //
                // render() invalidates the mode on every frame, so the common path here must
                // cost nothing and must claim a change only when there is one: returning true
                // unconditionally rebuilds and re-animates the floating bar every frame. The
                // clipboard is therefore only re-read when its change listener has said so.
                if (!mClipboardDirty) return false;
                mClipboardDirty = false;
                boolean pasteEnabled = clipboardHasText();
                if (pasteEnabled == mPasteEnabled) return false;
                mPasteEnabled = pasteEnabled;
                MenuItem paste = menu.findItem(ACTION_PASTE);
                if (paste != null) paste.setEnabled(pasteEnabled);
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (!isActive()) {
                    // Fix issue where the dialog is pressed while being dismissed.
                    return true;
                }

                switch (item.getItemId()) {
                    case ACTION_COPY:
                        String selectedText = getSelectedText();
                        terminalView.mTermSession.onCopyTextToClipboard(selectedText);
                        terminalView.stopTextSelectionMode();
                        break;
                    case ACTION_PASTE:
                        terminalView.stopTextSelectionMode();
                        terminalView.mTermSession.onPasteTextFromClipboard();
                        break;
                }

                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }

        };

        mActionMode = terminalView.startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return callback.onCreateActionMode(mode, menu);
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return callback.onActionItemClicked(mode, item);
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                // LUNAMUX: the action mode's own teardown is the reliable place to drop the
                // clipboard listener -- hide() can bail out early (its 300ms guard), whereas
                // this fires however the mode ends, including when the system replaces it.
                unregisterClipChangedListener();
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                // LUNAMUX: go through the view's own cell -> pixel helpers instead of
                // multiplying out the cell metrics here. onDraw translates the canvas by
                // (-panX, contentOffsetY) to pan a grid wider than the view and to centre one
                // that does not fill its height; getPointX/getPointY undo exactly those two
                // offsets, and this rect -- which is what the floating bar anchors to -- did
                // not, so the bar drifted away from the selection it belongs to as soon as the
                // pane was panned or vertically centred.
                int x1 = terminalView.getPointX(mSelX1);
                int x2 = terminalView.getPointX(mSelX2);
                int y1 = terminalView.getPointY(mSelY1 - 1);
                int y2 = terminalView.getPointY(mSelY2 + 1);

                if (x1 > x2) {
                    int tmp = x1;
                    x1 = x2;
                    x2 = tmp;
                }

                int terminalBottom = terminalView.getBottom();
                int top = y1 + mHandleHeight;
                int bottom = y2 + mHandleHeight;
                if (top > terminalBottom) top = terminalBottom;
                if (bottom > terminalBottom) bottom = terminalBottom;

                outRect.set(x1, top, x2, bottom);
            }
        }, ActionMode.TYPE_FLOATING);

        registerClipChangedListener();
    }

    /**
     * LUNAMUX: start watching the system clipboard so the bar's "Paste" item can follow it.
     *
     * Called when the action mode is created; torn down again by
     * {@link #unregisterClipChangedListener()} from {@code onDestroyActionMode}. The listener
     * only marks state dirty and asks the mode to refresh -- reading the clipboard is left to
     * {@code onPrepareActionMode}, which runs on the UI thread.
     *
     * @see #mClipboardDirty
     */
    private void registerClipChangedListener() {
        if (mClipChangedListener != null) return;
        ClipboardManager clipboard = clipboardManager();
        if (clipboard == null) return;
        mClipChangedListener = () -> {
            mClipboardDirty = true;
            if (mActionMode != null) mActionMode.invalidate();
        };
        try {
            clipboard.addPrimaryClipChangedListener(mClipChangedListener);
        } catch (Exception e) {
            // Nothing to watch with; "Paste" simply keeps the state it was built with.
            mClipChangedListener = null;
        }
    }

    /**
     * LUNAMUX: stop watching the system clipboard.
     *
     * Called from {@code onDestroyActionMode} and {@link #onDetached()}. Idempotent, because
     * both can run for the same selection.
     *
     * @see #registerClipChangedListener()
     */
    private void unregisterClipChangedListener() {
        if (mClipChangedListener == null) return;
        ClipboardManager clipboard = clipboardManager();
        if (clipboard != null) {
            try {
                clipboard.removePrimaryClipChangedListener(mClipChangedListener);
            } catch (Exception e) {
                // Already gone as far as the service is concerned; nothing to undo.
            }
        }
        mClipChangedListener = null;
        mClipboardDirty = false;
    }

    /**
     * LUNAMUX: the system clipboard service, or null if this device does not expose one.
     *
     * @return the {@link ClipboardManager}, or null.
     */
    @Nullable
    private ClipboardManager clipboardManager() {
        return (ClipboardManager) terminalView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /**
     * LUNAMUX: whether a paste would produce anything, used to enable the bar's "Paste" item.
     *
     * @return true if the system clipboard holds a primary clip. Note that from API 29 the
     * platform answers false whenever this app is unfocused -- which is precisely when no
     * selection bar is up, so it costs nothing here.
     */
    private boolean clipboardHasText() {
        ClipboardManager clipboard = clipboardManager();
        if (clipboard == null) return false;
        try {
            return clipboard.hasPrimaryClip();
        } catch (Exception e) {
            // Reading the clipboard is an IPC into the system service; a dead source app can
            // surface here, and a greyed-out Paste is a better outcome than a crash.
            return false;
        }
    }

    @Override
    public void updatePosition(TextSelectionHandleView handle, int x, int y) {
        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        final int scrollRows = screen.getActiveRows() - terminalView.mEmulator.mRows;
        if (handle == mStartHandle) {
            mSelX1 = terminalView.getCursorX(x);
            mSelY1 = terminalView.getCursorY(y);
            if (mSelX1 < 0) {
                mSelX1 = 0;
            }

            if (mSelY1 < -scrollRows) {
                mSelY1 = -scrollRows;

            } else if (mSelY1 > terminalView.mEmulator.mRows - 1) {
                mSelY1 = terminalView.mEmulator.mRows - 1;

            }

            if (mSelY1 > mSelY2) {
                mSelY1 = mSelY2;
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX1 = mSelX2;
            }

            if (!terminalView.mEmulator.isAlternateBufferActive()) {
                int topRow = terminalView.getTopRow();

                if (mSelY1 <= topRow) {
                    topRow--;
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows;
                    }
                } else if (mSelY1 >= topRow + terminalView.mEmulator.mRows) {
                    topRow++;
                    if (topRow > 0) {
                        topRow = 0;
                    }
                }

                terminalView.setTopRow(topRow);
            }

            mSelX1 = getValidCurX(screen, mSelY1, mSelX1);

        } else {
            mSelX2 = terminalView.getCursorX(x);
            mSelY2 = terminalView.getCursorY(y);
            if (mSelX2 < 0) {
                mSelX2 = 0;
            }

            if (mSelY2 < -scrollRows) {
                mSelY2 = -scrollRows;
            } else if (mSelY2 > terminalView.mEmulator.mRows - 1) {
                mSelY2 = terminalView.mEmulator.mRows - 1;
            }

            if (mSelY1 > mSelY2) {
                mSelY2 = mSelY1;
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX2 = mSelX1;
            }

            if (!terminalView.mEmulator.isAlternateBufferActive()) {
                int topRow = terminalView.getTopRow();

                if (mSelY2 <= topRow) {
                    topRow--;
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows;
                    }
                } else if (mSelY2 >= topRow + terminalView.mEmulator.mRows) {
                    topRow++;
                    if (topRow > 0) {
                        topRow = 0;
                    }
                }

                terminalView.setTopRow(topRow);
            }

            mSelX2 = getValidCurX(screen, mSelY2, mSelX2);
        }

        terminalView.invalidate();
    }

    private int getValidCurX(TerminalBuffer screen, int cy, int cx) {
        String line = screen.getSelectedText(0, cy, cx, cy);
        if (!TextUtils.isEmpty(line)) {
            int col = 0;
            for (int i = 0, len = line.length(); i < len; i++) {
                char ch1 = line.charAt(i);
                if (ch1 == 0) {
                    break;
                }

                int wc;
                if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                    char ch2 = line.charAt(++i);
                    wc = WcWidth.width(Character.toCodePoint(ch1, ch2));
                } else {
                    wc = WcWidth.width(ch1);
                }

                final int cend = col + wc;
                if (cx > col && cx < cend) {
                    return cend;
                }
                if (cend == col) {
                    return col;
                }
                col = cend;
            }
        }
        return cx;
    }

    public void decrementYTextSelectionCursors(int decrement) {
        mSelY1 -= decrement;
        mSelY2 -= decrement;
    }

    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    public void onTouchModeChanged(boolean isInTouchMode) {
        if (!isInTouchMode) {
            terminalView.stopTextSelectionMode();
        }
    }

    @Override
    public void onDetached() {
        // LUNAMUX: the view is going away; make sure the clipboard service is not left holding
        // a listener that closes over it.
        unregisterClipChangedListener();
    }

    @Override
    public boolean isActive() {
        return mIsSelectingText;
    }

    public void getSelectors(int[] sel) {
        if (sel == null || sel.length != 4) {
            return;
        }

        sel[0] = mSelY1;
        sel[1] = mSelY2;
        sel[2] = mSelX1;
        sel[3] = mSelX2;
    }

    /** Get the currently selected text. */
    public String getSelectedText() {
        return terminalView.mEmulator.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2);
    }

    public ActionMode getActionMode() {
        return mActionMode;
    }

    /**
     * @return true if this controller is currently used to move the start selection.
     */
    public boolean isSelectionStartDragged() {
        return mStartHandle.isDragging();
    }

    /**
     * @return true if this controller is currently used to move the end selection.
     */
    public boolean isSelectionEndDragged() {
        return mEndHandle.isDragging();
    }

}
