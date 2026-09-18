package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;

/**
 * Types the two NiDA keys that the .kcm layout physically cannot express.
 *
 * <p>A key character map behavior is a single UTF-16 code unit: AOSP's
 * {@code KeyCharacterMap.cpp} parses exactly one character between the quotes and
 * rejects a second literal with "Cannot combine multiple character literals".
 * Two NiDA keys are two-code-point sequences, so they have no valid .kcm form:
 *
 * <ul>
 *   <li>Shift+A     &rarr; U+17B6 U+17C6 (SARA AA + NIKAHIT, "SARA AM")</li>
 *   <li>Shift+COMMA &rarr; U+17BB U+17C6 (SARA U  + NIKAHIT, "SARA OM")</li>
 * </ul>
 *
 * <p>This service commits them in full. Every other key is left alone and falls
 * through to the normal layout, so the .kcm stays the single source of truth for
 * the rest of the keyboard.
 *
 * <p>It only has an effect while it is the selected input method. It draws no
 * on-screen keyboard, so it is meant for a device that has a physical keyboard
 * attached; see README.md.
 */
public class KhmerSequenceInputMethodService extends InputMethodService {

    /** NiDA Shift+A: KHMER VOWEL SIGN AA + KHMER SIGN NIKAHIT. */
    private static final String SARA_AM = "ាំ";

    /** NiDA Shift+COMMA: KHMER VOWEL SIGN U + KHMER SIGN NIKAHIT. */
    private static final String SARA_OM = "ុំ";

    /** Modifiers that must NOT be held, or we would steal a real shortcut. */
    private static final int DISQUALIFYING_META =
            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON;

    @Override
    public View onCreateInputView() {
        // Hardware-keyboard companion: there is deliberately no soft keyboard.
        return null;
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
        // Swallow the matching up event so the key is not also handled downstream.
        if (sequenceFor(keyCode, event) != null) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * @return the sequence this key should type, or {@code null} to let the
     *         normal .kcm layout handle the key.
     */
    private String sequenceFor(int keyCode, KeyEvent event) {
        if (event == null || !event.isShiftPressed()) {
            return null;
        }
        if ((event.getMetaState() & DISQUALIFYING_META) != 0) {
            return null;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                return SARA_AM;
            case KeyEvent.KEYCODE_COMMA:
                return SARA_OM;
            default:
                return null;
        }
    }
}
