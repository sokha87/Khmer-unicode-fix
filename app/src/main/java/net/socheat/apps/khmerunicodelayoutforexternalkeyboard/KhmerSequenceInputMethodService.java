package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Types the five NiDA keys that a key character map physically cannot express.
 *
 * <p>An Android .kcm behavior is a single UTF-16 code unit: AOSP's
 * {@code KeyCharacterMap.cpp} parses one character between the quotes and rejects
 * a second literal with "Cannot combine multiple character literals". Five NiDA
 * positions are two-code-point vowel sequences, and Unicode has no precomposed
 * form for any of them, so the layout file carries only their first code point.
 * This service commits them in full, matching the desktop NiDA keyboard.
 *
 * <p>Every other key is left alone and falls through to the layout, which stays
 * the single source of truth for the rest of the keyboard.
 *
 * <p><b>Why each rule checks the character first.</b> One of the five is the
 * <em>unshifted</em> comma. Blindly rewriting that key would turn an ordinary
 * comma into a Khmer vowel whenever the user switched their physical keyboard to
 * a Latin layout. So a rule fires only when the key currently produces the
 * sequence's leading code point — which is true exactly when the Khmer layout is
 * the active one. Under any other layout these keys behave normally.
 */
public class KhmerSequenceInputMethodService extends InputMethodService {

    private static final char NIKAHIT = 'ំ';   // ំ  completes -AM / -OM
    private static final char REAHMUK = 'ះ';   // ះ  completes -AH / -OH

    /** keyCode, shift required, the code point the layout gives, the full sequence. */
    private static final Object[][] SEQUENCES = {
            // NiDA: SHIFT+A  -> SARA AA + NIKAHIT
            {KeyEvent.KEYCODE_A, Boolean.TRUE, 'ា', "ា" + NIKAHIT},
            // NiDA: SHIFT+V  -> SARA E + REAHMUK
            {KeyEvent.KEYCODE_V, Boolean.TRUE, 'េ', "េ" + REAHMUK},
            // NiDA: SHIFT+;  -> SARA OO + REAHMUK
            {KeyEvent.KEYCODE_SEMICOLON, Boolean.TRUE, 'ោ', "ោ" + REAHMUK},
            // NiDA: ,        -> SARA U + NIKAHIT
            {KeyEvent.KEYCODE_COMMA, Boolean.FALSE, 'ុ', "ុ" + NIKAHIT},
            // NiDA: SHIFT+,  -> SARA U + REAHMUK
            {KeyEvent.KEYCODE_COMMA, Boolean.TRUE, 'ុ', "ុ" + REAHMUK},
    };

    /** Modifiers that must not be held, or we would steal a real shortcut. */
    private static final int DISQUALIFYING_META =
            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON;

    /**
     * A way out when the physical keyboard is gone.
     *
     * <p>This keyboard has no soft layout of its own, so returning {@code null}
     * here used to strand anyone who undocked their tablet while it was
     * selected: no keys, and no on-screen way to reach another keyboard.
     *
     * <p>Visibility is left to the inherited
     * {@link #onEvaluateInputViewShown()}, which shows the input view only when
     * {@code Configuration.keyboard == KEYBOARD_NOKEYS}, when the hard keyboard
     * is hidden, or when the user has asked to see a soft keyboard alongside a
     * physical one. So with the keyboard attached this stays hidden and nothing
     * covers the screen.
     */
    @Override
    public View onCreateInputView() {
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);

        TextView message = new TextView(this);
        message.setText(R.string.ime_no_hardware_keyboard);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setPadding(pad, pad, pad, pad / 2);

        Button picker = new Button(this);
        picker.setText(R.string.ime_choose_keyboard);
        picker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showInputMethodPicker();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = pad;
        lp.rightMargin = pad;
        lp.bottomMargin = pad;
        picker.setLayoutParams(lp);

        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.addView(message);
        view.addView(picker);
        return view;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        String sequence = sequenceFor(keyCode, event);
        if (sequence != null) {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null && connection.commitText(sequence, 1)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Swallow the matching up event so the key is not handled twice.
        if (sequenceFor(keyCode, event) != null) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * @return the sequence this key should type, or {@code null} to let the layout
     *         handle the key normally.
     */
    private String sequenceFor(int keyCode, KeyEvent event) {
        if (event == null || (event.getMetaState() & DISQUALIFYING_META) != 0) {
            return null;
        }
        boolean shift = event.isShiftPressed();
        for (Object[] rule : SEQUENCES) {
            if (((Integer) rule[0]).intValue() != keyCode) {
                continue;
            }
            if (((Boolean) rule[1]).booleanValue() != shift) {
                continue;
            }
            // Only act when the active layout really is the Khmer one.
            if (event.getUnicodeChar() != ((Character) rule[2]).charValue()) {
                continue;
            }
            return (String) rule[3];
        }
        return null;
    }
}
