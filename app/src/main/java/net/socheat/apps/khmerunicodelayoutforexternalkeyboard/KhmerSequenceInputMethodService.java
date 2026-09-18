package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

/**
 * Khmer NiDA keyboard: the five sequence keys on a physical keyboard, and a full
 * on-screen keyboard when there is no physical keyboard attached.
 *
 * <h3>Physical keyboard</h3>
 * <p>An Android {@code .kcm} behavior is a single UTF-16 code unit: AOSP's
 * {@code KeyCharacterMap.cpp} parses one character between the quotes and rejects
 * a second literal with "Cannot combine multiple character literals". Five NiDA
 * positions are two-code-point vowel sequences with no precomposed form in
 * Unicode, so the layout file carries only their first code point and
 * {@link #onKeyDown} commits them in full. Every other key falls through to the
 * layout, which stays the single source of truth for the rest of the keyboard.
 *
 * <h3>On-screen keyboard</h3>
 * <p>Undocking used to leave nothing to type on, because this service had no
 * input view. It now shows a keyboard generated from the same NiDA table as the
 * physical layout, so the on-screen keys sit where the physical ones do. The
 * same five sequences are typed in full here through
 * {@code android:keyOutputText}, which arrives as {@link #onText}.
 *
 * <p>Whether it appears is left to the inherited
 * {@link #onEvaluateInputViewShown()}, which returns true only when there is no
 * usable hardware keyboard, so nothing is drawn while one is attached.
 */
public class KhmerSequenceInputMethodService extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

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

    private KeyboardView keyboardView;
    private Keyboard khmer;
    private Keyboard khmerShift;
    private Keyboard latin;
    private Keyboard latinShift;

    /** Which script the on-screen keyboard is showing. */
    private boolean latinLayer;
    private boolean shifted;

    // ---------------------------------------------------------------- on-screen

    @Override
    public void onInitializeInterface() {
        super.onInitializeInterface();
        // Called again after a configuration change, so key widths stay correct.
        khmer = new Keyboard(this, R.xml.soft_khmer);
        khmerShift = new Keyboard(this, R.xml.soft_khmer_shift);
        latin = new Keyboard(this, R.xml.soft_latin);
        latinShift = new Keyboard(this, R.xml.soft_latin_shift);
    }

    @Override
    public View onCreateInputView() {
        keyboardView = (KeyboardView) getLayoutInflater()
                .inflate(R.layout.soft_keyboard, null);
        keyboardView.setOnKeyboardActionListener(this);
        applyKeyboard();
        return keyboardView;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        latinLayer = !isKhmerSubtype();
        shifted = false;
        applyKeyboard();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype) {
        super.onCurrentInputMethodSubtypeChanged(subtype);
        latinLayer = subtype == null || !isKhmer(subtype);
        shifted = false;
        applyKeyboard();
    }

    private boolean isKhmerSubtype() {
        // The current subtype lives on InputMethodManager, not on the service.
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        return imm != null && isKhmer(imm.getCurrentInputMethodSubtype());
    }

    private static boolean isKhmer(InputMethodSubtype subtype) {
        return subtype != null && subtype.getLocale() != null
                && subtype.getLocale().startsWith("km");
    }

    private void applyKeyboard() {
        if (keyboardView == null) {
            return;
        }
        Keyboard keyboard = latinLayer
                ? (shifted ? latinShift : latin)
                : (shifted ? khmerShift : khmer);
        keyboardView.setKeyboard(keyboard);
        keyboardView.setShifted(shifted);
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        switch (primaryCode) {
            case Keyboard.KEYCODE_SHIFT:
                shifted = !shifted;
                applyKeyboard();
                return;
            case Keyboard.KEYCODE_MODE_CHANGE:
                latinLayer = !latinLayer;
                shifted = false;
                applyKeyboard();
                return;
            case Keyboard.KEYCODE_DELETE:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                return;
            case Keyboard.KEYCODE_DONE:
                if (!sendDefaultEditorAction(true)) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
                }
                return;
            default:
                commit(String.valueOf((char) primaryCode));
                // Shift is one-shot, like every other soft keyboard.
                if (shifted) {
                    shifted = false;
                    applyKeyboard();
                }
        }
    }

    /** Keys declaring {@code android:keyOutputText} arrive here — the sequences. */
    @Override
    public void onText(CharSequence text) {
        commit(text.toString());
        if (shifted) {
            shifted = false;
            applyKeyboard();
        }
    }

    private void commit(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.commitText(text, 1);
        }
    }

    @Override public void onPress(int primaryCode) { }
    @Override public void onRelease(int primaryCode) { }
    @Override public void swipeLeft() { }
    @Override public void swipeRight() { }
    @Override public void swipeDown() { }
    @Override public void swipeUp() { }

    // ---------------------------------------------------------------- physical

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
