package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.content.Context;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;

import java.util.List;

/**
 * A {@link KeyboardView} that fills the width it is given.
 *
 * <p>Two things leave a keyboard narrower than its window. {@code onMeasure}
 * takes the keyboard's own width where that is the smaller, so a layout that
 * works out narrower than the screen is drawn narrow and left aligned rather
 * than stretched. And {@code Keyboard.resize} only ever scales a row down, so
 * a row that comes out short - which rounding alone can manage, since every
 * key width is a percentage truncated to whole pixels - stays short.
 *
 * <p>Both are fixed here by stretching each row to the measured width, which
 * also takes up the rounding slack: the last key of each row absorbs whatever
 * is left over, so the row ends exactly where the view does.
 */
public class WideKeyboardView extends KeyboardView {

    public WideKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WideKeyboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int offered = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                && getMeasuredWidth() < offered) {
            setMeasuredDimension(offered, getMeasuredHeight());
        }
    }

    @Override
    public void onSizeChanged(int width, int height, int wasWidth, int wasHeight) {
        super.onSizeChanged(width, height, wasWidth, wasHeight);
        stretchRows(width - getPaddingLeft() - getPaddingRight());
    }

    @Override
    public void setKeyboard(Keyboard keyboard) {
        super.setKeyboard(keyboard);
        if (getWidth() > 0) {
            stretchRows(getWidth() - getPaddingLeft() - getPaddingRight());
        }
    }

    /**
     * Lays every row out across exactly {@code width}.
     *
     * <p>Rows come back from the keyboard in order, so a run of keys sharing a
     * top edge is a row. Doing nothing when a row already fits keeps this
     * idempotent, which matters because the keyboards are shared between
     * sizes and this runs again on each of them.
     */
    private void stretchRows(int width) {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null || width <= 0) {
            return;
        }
        List<Keyboard.Key> keys = keyboard.getKeys();
        int from = 0;
        while (from < keys.size()) {
            int top = keys.get(from).y;
            int to = from;
            int total = 0;
            while (to < keys.size() && keys.get(to).y == top) {
                total += keys.get(to).width + keys.get(to).gap;
                to++;
            }
            if (total > 0 && total != width) {
                float scale = (float) width / total;
                int x = 0;
                for (int at = from; at < to; at++) {
                    Keyboard.Key key = keys.get(at);
                    key.gap = Math.round(key.gap * scale);
                    key.width = Math.round(key.width * scale);
                    key.x = x + key.gap;
                    x = key.x + key.width;
                }
                // Whatever rounding left over goes on the last key, so the row
                // ends where the view does rather than a pixel or two short.
                keys.get(to - 1).width += width - x;
            }
            from = to;
        }
        invalidateAllKeys();
    }
}
