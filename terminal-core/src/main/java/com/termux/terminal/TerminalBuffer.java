package com.termux.terminal;

import java.util.Arrays;

/**
 * A circular buffer of {@link TerminalRow}:s which keeps notes about what is visible on a logical screen and the scroll
 * history.
 * <p>
 * See {@link #externalToInternalRow(int)} for how to map from logical screen rows to array indices.
 */
public final class TerminalBuffer {

    TerminalRow[] mLines;
    /** The length of {@link #mLines}. */
    int mTotalRows;
    /** The number of rows and columns visible on the screen. */
    int mScreenRows, mColumns;
    /** The number of rows kept in history. */
    private int mActiveTranscriptRows = 0;
    /** The index in the circular buffer where the visible screen starts. */
    private int mScreenFirstRow = 0;

    /**
     * Create a transcript screen.
     *
     * @param columns    the width of the screen in characters.
     * @param totalRows  the height of the entire text area, in rows of text.
     * @param screenRows the height of just the screen, not including the transcript that holds lines that have scrolled off
     *                   the top of the screen.
     */
    public TerminalBuffer(int columns, int totalRows, int screenRows) {
        mColumns = columns;
        mTotalRows = totalRows;
        mScreenRows = screenRows;
        mLines = new TerminalRow[totalRows];

        blockSet(0, 0, columns, screenRows, ' ', TextStyle.NORMAL);
    }

    public String getTranscriptText() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows).trim();
    }

    public String getTranscriptTextWithoutJoinedLines() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, false).trim();
    }

    public String getTranscriptTextWithFullLinesJoined() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, true, true).trim();
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2) {
        return getSelectedText(selX1, selY1, selX2, selY2, true);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines) {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines, boolean joinFullLines) {
        final StringBuilder builder = new StringBuilder();
        final int columns = mColumns;

        if (selY1 < -getActiveTranscriptRows()) selY1 = -getActiveTranscriptRows();
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1;

        for (int row = selY1; row <= selY2; row++) {
            int x1 = (row == selY1) ? selX1 : 0;
            int x2;
            if (row == selY2) {
                x2 = selX2 + 1;
                if (x2 > columns) x2 = columns;
            } else {
                x2 = columns;
            }
            TerminalRow lineObject = mLines[externalToInternalRow(row)];
            if (lineObject == null) {
                // Rows are allocated lazily (see allocateFullLineIfNecessary) and are
                // nulled out again by clearTranscript(), so a row inside the requested
                // range can legitimately be absent — e.g. in the window between a
                // resize that grew the screen and the repaint that fills it. Reading it
                // must treat that as a blank line instead of dereferencing null, which
                // crashed the Android view's onScreenUpdated() -> getText() path.
                if (row < selY2 && row < mScreenRows - 1) builder.append('\n');
                continue;
            }
            int x1Index = lineObject.findStartOfColumn(x1);
            int x2Index = (x2 < mColumns) ? lineObject.findStartOfColumn(x2) : lineObject.getSpaceUsed();
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = lineObject.findStartOfColumn(x2 + 1);
            }
            char[] line = lineObject.mText;
            int lastPrintingCharIndex = -1;
            int i;
            boolean rowLineWrap = getLineWrap(row);
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space:
                lastPrintingCharIndex = x2Index - 1;
            } else {
                for (i = x1Index; i < x2Index; ++i) {
                    char c = line[i];
                    if (c != ' ') lastPrintingCharIndex = i;
                }
            }

            int len = lastPrintingCharIndex - x1Index + 1;
            if (lastPrintingCharIndex != -1 && len > 0)
                builder.append(line, x1Index, len);

            boolean lineFillsWidth = lastPrintingCharIndex == x2Index - 1;
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < selY2 && row < mScreenRows - 1) builder.append('\n');
        }
        return builder.toString();
    }

    public String getWordAtLocation(int x, int y) {
        // Set y1 and y2 to the lines where the wrapped line starts and ends.
        // I.e. if a line that is wrapped to 3 lines starts at line 4, and this
        // is called with y=5, then y1 would be set to 4 and y2 would be set to 6.
        int y1 = y;
        int y2 = y;
        while (y1 > 0 && !getSelectedText(0, y1 - 1, mColumns, y, true, true).contains("\n")) {
            y1--;
        }
        while (y2 < mScreenRows && !getSelectedText(0, y, mColumns, y2 + 1, true, true).contains("\n")) {
            y2++;
        }

        // Get the text for the whole wrapped line
        String text = getSelectedText(0, y1, mColumns, y2, true, true);
        // The index of x in text
        int textOffset = (y - y1) * mColumns + x;

        if (textOffset >= text.length()) {
          // The click was to the right of the last word on the line, so
          // there's no word to return
          return "";
        }

        // Set x1 and x2 to the indices of the last space before x and the
        // first space after x in text respectively
        int x1 = text.lastIndexOf(' ', textOffset);
        int x2 = text.indexOf(' ', textOffset);
        if (x2 == -1) {
            x2 = text.length();
        }

        if (x1 == x2) {
          // The click was on a space, so there's no word to return
          return "";
        }
        return text.substring(x1 + 1, x2);
    }

    /**
     * LUNAMUX ADDITION. Notified as a row leaves the visible screen.
     * <p>
     * A terminal normally answers "where does a row go when it scrolls off?" with
     * "into the transcript", which ties history to the width it was written at and
     * makes every later reflow rewrite it. The Lunamux server instead keeps history
     * outside the emulator, as logical lines that are never reflowed, and needs this
     * hook to see each row at the one instant it is still intact — the buffer recycles
     * the row immediately afterwards.
     *
     * @see #setRowEvictionListener(RowEvictionListener)
     */
    public interface RowEvictionListener {
        /**
         * @param row     the row about to leave the screen; valid only for the duration
         *                of this call, as the buffer reuses it on return.
         * @param wrapped whether the row soft-wraps into the one below, i.e. the logical
         *                line it belongs to continues rather than ending here.
         */
        void onRowLeavingScreen(TerminalRow row, boolean wrapped);
    }

    /** LUNAMUX ADDITION. Null (the default) means nothing observes eviction. */
    private RowEvictionListener mRowEvictionListener;

    /**
     * LUNAMUX ADDITION. Observe rows leaving the screen.
     *
     * @param listener the observer, or null to stop observing.
     */
    public void setRowEvictionListener(RowEvictionListener listener) {
        mRowEvictionListener = listener;
    }

    /**
     * LUNAMUX ADDITION. Notified when the scrollback is explicitly cleared (ED3, i.e.
     * {@code ESC[3J} — what the {@code clear} command emits to wipe history).
     * <p>
     * A terminal answers "erase the scrollback" by dropping its transcript. When history is
     * kept outside the emulator (the Lunamux server), the emulator's own transcript is empty
     * and clearing it does nothing, so the external history would survive a {@code clear} and
     * be re-emitted on the next redraw — the pre-split build did not have this problem because
     * the transcript WAS the history. This hook lets the external log be cleared in step.
     */
    public interface TranscriptClearedListener {
        /** The scrollback has been erased; drop any external history mirror. */
        void onTranscriptCleared();
    }

    /** LUNAMUX ADDITION. Null (the default) means nothing observes scrollback clears. */
    private TranscriptClearedListener mTranscriptClearedListener;

    /**
     * LUNAMUX ADDITION. Observe explicit scrollback clears (ED3).
     *
     * @param listener the observer, or null to stop observing.
     */
    public void setTranscriptClearedListener(TranscriptClearedListener listener) {
        mTranscriptClearedListener = listener;
    }

    /**
     * LUNAMUX ADDITION. The row at an external row index, as the row object.
     * <p>
     * Everything else reads this buffer as text; the one caller that needs the rows
     * themselves is {@link #backfillAboveScreen}, lifting freshly laid-out rows out of a
     * scratch buffer to place them in another. The row is the buffer's own — a caller that
     * keeps it must be finished with the buffer.
     *
     * @param externalRow row index in the external coordinate system (see
     *                    {@link #externalToInternalRow}).
     * @return the row, allocated if it was not yet.
     */
    public TerminalRow getRow(int externalRow) {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow));
    }

    /**
     * LUNAMUX ADDITION. How many rows {@link #backfillAboveScreen} could accept right now.
     * <p>
     * Backfilling overwrites the blank rows at the bottom of the screen (see there), so the
     * capacity is however many trailing rows are blank — capped by what the caller asks for.
     * Callers ask first because they must not commit to un-scrolling more history than can
     * be placed: the history it comes from gives lines up whole, so a partial placement
     * would drop the head of a line.
     *
     * @param maxRows the most the caller wants; a budget, not a demand.
     * @return rows that can be placed, 0 when none can.
     */
    public int backfillCapacity(int maxRows) {
        if (maxRows <= 0 || mTotalRows != mScreenRows) return 0;
        int blankTail = 0;
        while (blankTail < mScreenRows && blankTail < maxRows) {
            TerminalRow row = mLines[externalToInternalRow(mScreenRows - 1 - blankTail)];
            if (row != null && !row.isBlank()) break;
            blankTail++;
        }
        return blankTail;
    }

    /**
     * LUNAMUX ADDITION. Place rows back above the screen, shifting the screen down — the
     * exact inverse of the eviction {@link RowEvictionListener} reports.
     * <p>
     * Growing the screen has to find rows from somewhere. A terminal that keeps its history
     * in the transcript takes them from there, which is why a real terminal keeps the prompt
     * at the bottom of the window as you drag it taller. The Lunamux server's canonical grid
     * has no transcript at all — its history lives outside the emulator as logical lines — so
     * the vendored grow path had nothing to reveal and appended blank rows below instead,
     * stranding the prompt mid-screen with an empty band under it. This is how the owner of
     * that external history hands the rows back.
     * <p>
     * Only for screen-only rings ({@code mTotalRows == mScreenRows}, i.e. the server grid and
     * the alternate buffer). A ring with a transcript already has rows above the screen to
     * reveal and the vendored path handles it; rotating one backwards here would consume them.
     * <p>
     * The rows are placed by rotating the ring, so the blank rows at the bottom become the
     * top ones and every other row shifts down by the same amount. Nothing but those blanks
     * is discarded. The caller must have sized {@code rows} to {@link #backfillCapacity};
     * anything larger is refused outright rather than truncated, because truncating would
     * silently swallow the oldest supplied row.
     *
     * {@code replacingTopRows} exists because the screen's first row is often the tail of a
     * line whose head is in the external history, and a *widening* can leave the two fitting
     * in fewer rows than the tail alone occupies now — the head does not go above that row, it
     * rejoins it. So the caller may re-lay-out the top rows of the screen and hand back the
     * whole line: the first {@code replacingTopRows} screen rows are overwritten, and only the
     * excess shifts the screen down.
     *
     * @param rows              the rows to place, top first, each already laid out at this
     *                          buffer's width. The last one ends up at screen row
     *                          {@code rows.length - replacingTopRows - 1}, so its
     *                          {@link TerminalRow#mLineWrap} must be false unless the row below
     *                          it really is its continuation.
     * @param replacingTopRows  how many of the screen's current top rows {@code rows} restates
     *                          rather than sits above; 0 for a pure prepend.
     * @return whether the rows were placed.
     */
    public boolean backfillAboveScreen(TerminalRow[] rows, int replacingTopRows) {
        final int shift = rows.length - replacingTopRows;
        if (rows.length == 0 || replacingTopRows < 0 || shift < 0) return false;
        if (mTotalRows != mScreenRows || rows.length > mScreenRows) return false;
        if (shift > backfillCapacity(shift)) return false;
        if (shift > 0)
            mScreenFirstRow = ((mScreenFirstRow - shift) % mTotalRows + mTotalRows) % mTotalRows;
        for (int i = 0; i < rows.length; i++) mLines[externalToInternalRow(i)] = rows[i];
        return true;
    }

    public int getActiveTranscriptRows() {
        return mActiveTranscriptRows;
    }

    public int getActiveRows() {
        return mActiveTranscriptRows + mScreenRows;
    }

    /**
     * Convert a row value from the public external coordinate system to our internal private coordinate system.
     *
     * <pre>
     * - External coordinate system: -mActiveTranscriptRows to mScreenRows-1, with the screen being 0..mScreenRows-1.
     * - Internal coordinate system: the mScreenRows lines starting at mScreenFirstRow comprise the screen, while the
     *   mActiveTranscriptRows lines ending at mScreenFirstRow-1 form the transcript (as a circular buffer).
     *
     * External ↔ Internal:
     *
     * [ ...                            ]     [ ...                                     ]
     * [ -mActiveTranscriptRows         ]     [ mScreenFirstRow - mActiveTranscriptRows ]
     * [ ...                            ]     [ ...                                     ]
     * [ 0 (visible screen starts here) ]  ↔  [ mScreenFirstRow                         ]
     * [ ...                            ]     [ ...                                     ]
     * [ mScreenRows-1                  ]     [ mScreenFirstRow + mScreenRows-1         ]
     * </pre>
     *
     * @param externalRow a row in the external coordinate system.
     * @return The row corresponding to the input argument in the private coordinate system.
     */
    public int externalToInternalRow(int externalRow) {
        if (externalRow < -mActiveTranscriptRows || externalRow > mScreenRows)
            throw new IllegalArgumentException("extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + mActiveTranscriptRows);
        final int internalRow = mScreenFirstRow + externalRow;
        return (internalRow < 0) ? (mTotalRows + internalRow) : (internalRow % mTotalRows);
    }

    public void setLineWrap(int row) {
        mLines[externalToInternalRow(row)].mLineWrap = true;
    }

    public boolean getLineWrap(int row) {
        // Null-safe for the same reason as getSelectedText: rows are lazily
        // allocated, and an unallocated row is by definition not wrapped.
        TerminalRow line = mLines[externalToInternalRow(row)];
        return line != null && line.mLineWrap;
    }

    public void clearLineWrap(int row) {
        mLines[externalToInternalRow(row)].mLineWrap = false;
    }

    /**
     * Resize the screen which this transcript backs. Currently, this only works if the number of columns does not
     * change or the rows expand (that is, it only works when shrinking the number of rows).
     *
     * @param newColumns The number of columns the screen should have.
     * @param newRows    The number of rows the screen should have.
     * @param cursor     An int[2] containing the (column, row) cursor location.
     */
    public void resize(int newColumns, int newRows, int newTotalRows, int[] cursor, long currentStyle, boolean altScreen) {
        // newRows > mTotalRows should not normally happen since mTotalRows is TRANSCRIPT_ROWS (10000):
        if (newColumns == mColumns && newRows <= mTotalRows) {
            // Fast resize where just the rows changed.
            int shiftDownOfTopRow = mScreenRows - newRows;
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < mScreenRows) {
                // Shrinking. Check if we can skip blank rows at bottom below cursor.
                for (int i = mScreenRows - 1; i > 0; i--) {
                    if (cursor[1] >= i) break;
                    int r = externalToInternalRow(i);
                    if (mLines[r] == null || mLines[r].isBlank()) {
                        if (--shiftDownOfTopRow == 0) break;
                    }
                }
            } else if (shiftDownOfTopRow < 0) {
                // Negative shift down = expanding. Only move screen up if there is transcript to show:
                int actualShift = Math.max(shiftDownOfTopRow, -mActiveTranscriptRows);
                if (shiftDownOfTopRow != actualShift) {
                    // The new lines revealed by the resizing are not all from the transcript. Blank the below ones.
                    for (int i = 0; i < actualShift - shiftDownOfTopRow; i++)
                        allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows).clear(currentStyle);
                    shiftDownOfTopRow = actualShift;
                }
            }
            if (newTotalRows != mTotalRows) {
                // LUNAMUX ADDITION. The ring itself changes size, so the screen cannot simply
                // be rotated inside it — see resizeRowsOnlyReallocating.
                resizeRowsOnlyReallocating(newRows, newTotalRows, shiftDownOfTopRow, cursor, altScreen);
            } else {
                mScreenFirstRow += shiftDownOfTopRow;
                mScreenFirstRow = (mScreenFirstRow < 0) ? (mScreenFirstRow + mTotalRows) : (mScreenFirstRow % mTotalRows);
                // LUNAMUX CHANGE. Derived from capacity rather than taken from altScreen: a
                // buffer with no room beyond the screen (the alternate buffer, and the
                // server's canonical screen, whose history lives outside the emulator) can
                // never hold transcript rows, and letting a rows-shrink create them here
                // would address rows past the end of the ring. For the alternate buffer this
                // is the same answer altScreen gave, since it is allocated with
                // totalRows == screenRows.
                boolean keepsTranscript = newTotalRows > newRows;
                mActiveTranscriptRows = (altScreen || !keepsTranscript)
                    ? 0
                    : Math.max(0, mActiveTranscriptRows + shiftDownOfTopRow);
                cursor[1] -= shiftDownOfTopRow;
                mScreenRows = newRows;
            }
        } else {
            // Copy away old state and update new:
            TerminalRow[] oldLines = mLines;
            mLines = new TerminalRow[newTotalRows];
            for (int i = 0; i < newTotalRows; i++)
                mLines[i] = new TerminalRow(newColumns, currentStyle);

            final int oldActiveTranscriptRows = mActiveTranscriptRows;
            final int oldScreenFirstRow = mScreenFirstRow;
            final int oldScreenRows = mScreenRows;
            final int oldTotalRows = mTotalRows;
            mTotalRows = newTotalRows;
            mScreenRows = newRows;
            mActiveTranscriptRows = mScreenFirstRow = 0;
            mColumns = newColumns;

            int newCursorRow = -1;
            int newCursorColumn = -1;
            int oldCursorRow = cursor[1];
            int oldCursorColumn = cursor[0];
            boolean newCursorPlaced = false;

            int currentOutputExternalRow = 0;
            int currentOutputExternalColumn = 0;

            // Loop over every character in the initial state.
            // Blank lines should be skipped only if at end of transcript (just as is done in the "fast" resize), so we
            // keep track how many blank lines we have skipped if we later on find a non-blank line.
            int skippedBlankLines = 0;
            for (int externalOldRow = -oldActiveTranscriptRows; externalOldRow < oldScreenRows; externalOldRow++) {
                // Do what externalToInternalRow() does but for the old state:
                int internalOldRow = oldScreenFirstRow + externalOldRow;
                internalOldRow = (internalOldRow < 0) ? (oldTotalRows + internalOldRow) : (internalOldRow % oldTotalRows);

                TerminalRow oldLine = oldLines[internalOldRow];
                boolean cursorAtThisRow = externalOldRow == oldCursorRow;
                // The cursor may only be on a non-null line, which we should not skip:
                if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank()) {
                    skippedBlankLines++;
                    continue;
                } else if (skippedBlankLines > 0) {
                    // After skipping some blank lines we encounter a non-blank line. Insert the skipped blank lines.
                    for (int i = 0; i < skippedBlankLines; i++) {
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            // LUNAMUX FIX. Scrolling moves an already-placed cursor up with the
                            // content, exactly as the two sibling scroll sites below do; without
                            // this the cursor was left pointing a row too low whenever a blank
                            // run flushed after the cursor had been placed.
                            if (newCursorPlaced) newCursorRow--;
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }
                    skippedBlankLines = 0;
                }

                int lastNonSpaceIndex = 0;
                boolean justToCursor = false;
                if (cursorAtThisRow || oldLine.mLineWrap) {
                    // Take the whole line, either because of cursor on it, or if line wrapping.
                    lastNonSpaceIndex = oldLine.getSpaceUsed();
                    if (cursorAtThisRow) justToCursor = true;
                } else {
                    // Find the last cell that carries content. A trailing space
                    // is only truly blank when its cell also has the default
                    // style; a differently-styled space (a coloured status bar
                    // or box row that is all spaces) is real content and must
                    // survive the rewrap. mStyle is indexed by *column*, not by
                    // char index, so walk the column counter alongside i and
                    // read the style via getStyle(col) — indexing mStyle[i] was
                    // the earlier bug that dropped styled trailing spaces.
                    int col = 0;
                    for (int i = 0; i < oldLine.getSpaceUsed(); i++) {
                        char c = oldLine.mText[i];
                        int codePoint = (Character.isHighSurrogate(c)) ? Character.toCodePoint(c, oldLine.mText[++i]) : c;
                        int displayWidth = WcWidth.width(codePoint);
                        if (c != ' ' || (displayWidth > 0 && oldLine.getStyle(col) != currentStyle))
                            lastNonSpaceIndex = i + 1;
                        if (displayWidth > 0) col += displayWidth;
                    }
                }

                int currentOldCol = 0;
                long styleAtCol = 0;
                for (int i = 0; i < lastNonSpaceIndex; i++) {
                    // Note that looping over java character, not cells.
                    char c = oldLine.mText[i];
                    int codePoint = (Character.isHighSurrogate(c)) ? Character.toCodePoint(c, oldLine.mText[++i]) : c;
                    int displayWidth = WcWidth.width(codePoint);
                    // Use the last style if this is a zero-width character:
                    if (displayWidth > 0) styleAtCol = oldLine.getStyle(currentOldCol);

                    // Line wrap as necessary:
                    if (currentOutputExternalColumn + displayWidth > mColumns) {
                        setLineWrap(currentOutputExternalRow);
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            if (newCursorPlaced) newCursorRow--;
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }

                    int offsetDueToCombiningChar = ((displayWidth <= 0 && currentOutputExternalColumn > 0) ? 1 : 0);
                    int outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar;
                    setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol);

                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn;
                            newCursorRow = currentOutputExternalRow;
                            newCursorPlaced = true;
                        }
                        currentOldCol += displayWidth;
                        currentOutputExternalColumn += displayWidth;
                        if (justToCursor && newCursorPlaced) break;
                    }
                }
                // Old row has been copied. Check if we need to insert newline if old line was not wrapping:
                if (externalOldRow != (oldScreenRows - 1) && !oldLine.mLineWrap) {
                    if (currentOutputExternalRow == mScreenRows - 1) {
                        if (newCursorPlaced) newCursorRow--;
                        scrollDownOneLine(0, mScreenRows, currentStyle);
                    } else {
                        currentOutputExternalRow++;
                    }
                    currentOutputExternalColumn = 0;
                }
            }

            cursor[0] = newCursorColumn;
            cursor[1] = newCursorRow;
        }

        // Handle cursor scrolling off screen:
        if (cursor[0] < 0 || cursor[1] < 0) cursor[0] = cursor[1] = 0;
    }

    /**
     * LUNAMUX ADDITION. The rows-only branch of {@link #resize} for the case where the ring
     * itself has to change size ({@code newTotalRows != mTotalRows}) — a buffer with no room
     * beyond the screen, i.e. the alternate buffer and the server's canonical screen-only
     * main buffer, both of which are allocated with {@code mTotalRows == mScreenRows}.
     * <p>
     * The rotate-in-place code this replaces for that case was silently destructive: it
     * advanced {@link #mScreenFirstRow} modulo the OLD ring size, then shrank
     * {@code mTotalRows} without reallocating {@link #mLines}, so every later
     * {@link #externalToInternalRow(int)} reduced modulo the NEW size and addressed a
     * rotated, aliased set of rows — a screen that was neither the old one nor a coherent
     * new one. It also dropped the rows shifted off the top on the floor: for a screen-only
     * buffer those rows are the only copy, since its history lives outside the emulator and
     * is fed by {@link RowEvictionListener}. Because a rows-only change fires no client
     * resync, the damage stayed invisible until the next columns change baked it into
     * history and repainted every attached client from it.
     * <p>
     * <b>The surviving screen here is deliberately identical to what the same-size ring path
     * produces</b> (the same {@link TerminalRow} objects, in the same order, chosen by the
     * same blank-trim rule): clients run transcript-ful buffers and take that path, and they
     * receive no resync on a rows-only change, so any divergence would be a permanent split
     * between what the server believes it shows and what a client actually shows. That is
     * also why this does not simply defer to the reflow branch, which normalizes content
     * (drops trailing blank rows, truncates the cursor row at the cursor).
     *
     * @param newRows           the new screen height, already blank-trimmed by the caller.
     * @param newTotalRows      the new ring size; {@link #mLines} is reallocated to it.
     * @param shiftDownOfTopRow how far the screen's top edge moves down, as computed by
     *                          {@link #resize} (negative when growing).
     * @param cursor            an int[2] of (column, row); the row is adjusted by the shift
     *                          and clamped into the new screen.
     * @param altScreen         whether this is the alternate buffer, which never has history.
     * @see #resize(int, int, int, int[], long, boolean)
     */
    private void resizeRowsOnlyReallocating(int newRows, int newTotalRows, int shiftDownOfTopRow, int[] cursor, boolean altScreen) {
        // Report the rows leaving the screen while they are still intact and still
        // addressable through the old geometry, oldest first — the same order and the same
        // one-call-per-row contract scrollDownOneLine honours, so a listener cannot tell a
        // shrink from that many scrolls. Null-safe for buffers with no listener (the
        // alternate buffer never has one).
        if (mRowEvictionListener != null) {
            for (int i = 0; i < shiftDownOfTopRow && i < mScreenRows; i++) {
                TerminalRow leaving = mLines[externalToInternalRow(i)];
                if (leaving != null) mRowEvictionListener.onRowLeavingScreen(leaving, leaving.mLineWrap);
            }
        }

        // How much transcript the new ring can and does hold. Zero for every real caller of
        // this path (a screen-only ring has no room beyond the screen), but kept general so
        // the branch is keyed purely on the ring changing size.
        boolean keepsTranscript = !altScreen && newTotalRows > newRows;
        int newTranscriptRows = keepsTranscript
            ? Math.min(newTotalRows - newRows, Math.max(0, mActiveTranscriptRows + shiftDownOfTopRow))
            : 0;

        // The surviving window in OLD external coordinates: the new screen is old rows
        // [shiftDownOfTopRow, shiftDownOfTopRow + newRows), preceded by whatever transcript
        // the new ring still has room for.
        final int firstSurvivingExternalRow = shiftDownOfTopRow - newTranscriptRows;
        TerminalRow[] newLines = new TerminalRow[newTotalRows];
        for (int i = 0; i < newTranscriptRows + newRows; i++)
            newLines[i] = mLines[externalToInternalRow(firstSurvivingExternalRow + i)];

        mLines = newLines;
        mTotalRows = newTotalRows;
        mScreenRows = newRows;
        mActiveTranscriptRows = newTranscriptRows;
        // Unrotated by construction: the copy above already put the window in order.
        mScreenFirstRow = newTranscriptRows;

        cursor[1] -= shiftDownOfTopRow;
        // The blank-trim rule bounds the shift so the cursor stays on screen; clamp anyway
        // rather than let an out-of-range row escape into setChar(). A negative row is left
        // to resize()'s shared trailing clamp, which zeroes both coordinates together.
        if (cursor[1] >= mScreenRows) cursor[1] = mScreenRows - 1;
    }

    /**
     * Block copy lines and associated metadata from one location to another in the circular buffer, taking wraparound
     * into account.
     *
     * @param srcInternal The first line to be copied.
     * @param len         The number of lines to be copied.
     */
    private void blockCopyLinesDown(int srcInternal, int len) {
        if (len == 0) return;
        int totalRows = mTotalRows;

        int start = len - 1;
        // Save away line to be overwritten:
        TerminalRow lineToBeOverWritten = mLines[(srcInternal + start + 1) % totalRows];
        // Do the copy from bottom to top.
        for (int i = start; i >= 0; --i)
            mLines[(srcInternal + i + 1) % totalRows] = mLines[(srcInternal + i) % totalRows];
        // Put back overwritten line, now above the block:
        mLines[(srcInternal) % totalRows] = lineToBeOverWritten;
    }

    /**
     * Scroll the screen down one line. To scroll the whole screen of a 24 line screen, the arguments would be (0, 24).
     *
     * @param topMargin    First line that is scrolled.
     * @param bottomMargin One line after the last line that is scrolled.
     * @param style        the style for the newly exposed line.
     */
    public void scrollDownOneLine(int topMargin, int bottomMargin, long style) {
        if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)
            throw new IllegalArgumentException("topMargin=" + topMargin + ", bottomMargin=" + bottomMargin + ", mScreenRows=" + mScreenRows);

        // LUNAMUX ADDITION. Report the row about to leave the screen while it is still
        // intact. Only for topMargin == 0: with a scroll region set, the row displaced
        // out of the region is overwritten in place and never becomes history — in a real
        // terminal it does not reach scrollback either.
        if (mRowEvictionListener != null && topMargin == 0) {
            TerminalRow leaving = mLines[mScreenFirstRow];
            if (leaving != null) mRowEvictionListener.onRowLeavingScreen(leaving, leaving.mLineWrap);
        }

        // Copy the fixed topMargin lines one line down so that they remain on screen in same position:
        blockCopyLinesDown(mScreenFirstRow, topMargin);
        // Copy the fixed mScreenRows-bottomMargin lines one line down so that they remain on screen in same
        // position:
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin);

        // Update the screen location in the ring buffer:
        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows;
        // Note that the history has grown if not already full:
        if (mActiveTranscriptRows < mTotalRows - mScreenRows) mActiveTranscriptRows++;

        // Blank the newly revealed line above the bottom margin:
        int blankRow = externalToInternalRow(bottomMargin - 1);
        if (mLines[blankRow] == null) {
            mLines[blankRow] = new TerminalRow(mColumns, style);
        } else {
            mLines[blankRow].clear(style);
        }
    }

    /**
     * Block copy characters from one position in the screen to another. The two positions can overlap. All characters
     * of the source and destination must be within the bounds of the screen, or else an InvalidParameterException will
     * be thrown.
     *
     * @param sx source X coordinate
     * @param sy source Y coordinate
     * @param w  width
     * @param h  height
     * @param dx destination X coordinate
     * @param dy destination Y coordinate
     */
    public void blockCopy(int sx, int sy, int w, int h, int dx, int dy) {
        if (w == 0) return;
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows || dx < 0 || dx + w > mColumns || dy < 0 || dy + h > mScreenRows)
            throw new IllegalArgumentException();
        boolean copyingUp = sy > dy;
        for (int y = 0; y < h; y++) {
            int y2 = copyingUp ? y : (h - (y + 1));
            TerminalRow sourceRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2));
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2)).copyInterval(sourceRow, sx, sx + w, dx);
        }
    }

    /**
     * Block set characters. All characters must be within the bounds of the screen, or else and
     * InvalidParemeterException will be thrown. Typically this is called with a "val" argument of 32 to clear a block
     * of characters.
     */
    public void blockSet(int sx, int sy, int w, int h, int val, long style) {
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows) {
            throw new IllegalArgumentException(
                "Illegal arguments! blockSet(" + sx + ", " + sy + ", " + w + ", " + h + ", " + val + ", " + mColumns + ", " + mScreenRows + ")");
        }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                setChar(sx + x, sy + y, val, style);
        // LUNAMUX FIX. A row's wrap flag is a claim about its LAST cell: "the text ran off
        // this row's right edge and continues on the row below". Overwriting that cell — every
        // erase lands here, EL/ED/ECH/IL/DL/DECSEL and the column insert/delete fills — makes
        // the claim false, so the flag has to go with it. Real terminals do this (xterm clears
        // LINEWRAPPED when an erase reaches the end of a line); this emulator never did, and
        // nothing else clears the flag either, so it survived until the row object was
        // recycled.
        //
        // Harmless while a wrap flag only guides reflow and selection — which is all it did
        // upstream. It is load-bearing here: [TerminalBuffer.getLineWrap] decides whether the
        // serializer emits a row as `all cols, no CRLF` (a soft wrap the receiver reconstructs)
        // and whether the external history fuses it with the next row into one logical line. A
        // stale flag therefore had a *program's own repaint* pad rows out to full width and
        // splice them together permanently: any full-screen app that erases its rows and
        // repaints something shorter (Claude Code answering a SIGWINCH) left every shortened
        // row claiming a continuation it no longer had.
        if (sx + w >= mColumns) {
            for (int y = 0; y < h; y++) {
                TerminalRow row = mLines[externalToInternalRow(sy + y)];
                if (row != null) row.mLineWrap = false;
            }
        }
    }

    public TerminalRow allocateFullLineIfNecessary(int row) {
        return (mLines[row] == null) ? (mLines[row] = new TerminalRow(mColumns, 0)) : mLines[row];
    }

    public void setChar(int column, int row, int codePoint, long style) {
        if (row  < 0 || row >= mScreenRows || column < 0 || column >= mColumns)
            throw new IllegalArgumentException("TerminalBuffer.setChar(): row=" + row + ", column=" + column + ", mScreenRows=" + mScreenRows + ", mColumns=" + mColumns);
        row = externalToInternalRow(row);
        allocateFullLineIfNecessary(row).setChar(column, codePoint, style);
    }

    public long getStyleAt(int externalRow, int column) {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column);
    }

    /**
     * The row at [externalRow], or null when that row has never been written (a
     * blank, default-styled line). Non-allocating — unlike {@link #getStyleAt}
     * it does not materialize the row, so it is safe to walk the whole buffer
     * while serializing without mutating it. Added for the server-side
     * GridSerializer, which reads cells (chars, styles, wrap flag) directly.
     *
     * @param externalRow a row in the external coordinate system
     *   (-{@link #getActiveTranscriptRows()} .. {@link #mScreenRows}-1).
     * @return the backing {@link TerminalRow}, or null if unallocated (blank).
     */
    public TerminalRow getLineOrNull(int externalRow) {
        return mLines[externalToInternalRow(externalRow)];
    }

    /** Support for http://vt100.net/docs/vt510-rm/DECCARA and http://vt100.net/docs/vt510-rm/DECCARA */
    public void setOrClearEffect(int bits, boolean setOrClear, boolean reverse, boolean rectangular, int leftMargin, int rightMargin, int top, int left,
                                 int bottom, int right) {
        for (int y = top; y < bottom; y++) {
            TerminalRow line = mLines[externalToInternalRow(y)];
            int startOfLine = (rectangular || y == top) ? left : leftMargin;
            int endOfLine = (rectangular || y + 1 == bottom) ? right : rightMargin;
            for (int x = startOfLine; x < endOfLine; x++) {
                long currentStyle = line.getStyle(x);
                int foreColor = TextStyle.decodeForeColor(currentStyle);
                int backColor = TextStyle.decodeBackColor(currentStyle);
                int effect = TextStyle.decodeEffect(currentStyle);
                if (reverse) {
                    // Clear out the bits to reverse and add them back in reversed:
                    effect = (effect & ~bits) | (bits & ~effect);
                } else if (setOrClear) {
                    effect |= bits;
                } else {
                    effect &= ~bits;
                }
                line.mStyle[x] = TextStyle.encode(foreColor, backColor, effect);
            }
        }
    }

    public void clearTranscript() {
        if (mScreenFirstRow < mActiveTranscriptRows) {
            Arrays.fill(mLines, mTotalRows + mScreenFirstRow - mActiveTranscriptRows, mTotalRows, null);
            Arrays.fill(mLines, 0, mScreenFirstRow, null);
        } else {
            Arrays.fill(mLines, mScreenFirstRow - mActiveTranscriptRows, mScreenFirstRow, null);
        }
        mActiveTranscriptRows = 0;
        // LUNAMUX ADDITION. Tell any external history mirror to drop itself too — otherwise a
        // `clear` blanks the emulator's (here empty) transcript while the real history lives on.
        if (mTranscriptClearedListener != null) mTranscriptClearedListener.onTranscriptCleared();
    }

}
