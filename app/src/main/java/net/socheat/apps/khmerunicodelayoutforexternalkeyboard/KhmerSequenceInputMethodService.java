package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

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
 * <p>{@link #onEvaluateInputViewShown()} keeps it out of the way while a
 * physical keyboard is attached.
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

    /** Where {@link LanguagesActivity} stores the on-screen keyboard preference. */
    static final String PREFS = "keyboard";

    /** Show the on-screen keyboard even while a physical keyboard is attached. */
    static final String PREF_SHOW_WITH_HARD_KEYBOARD = "show_with_hard_keyboard";

    /** The service's last visibility decision, reported by {@link LanguagesActivity}. */
    static final String PREF_LAST_DECISION = "last_decision";

    /** Offer word suggestions while typing. */
    static final String PREF_SUGGESTIONS = "suggestions";

    /** Whether the word lists loaded, reported by {@link LanguagesActivity}. */
    static final String PREF_DICT_STATUS = "dict_status";

    /** The last suggestion lookup, reported by {@link LanguagesActivity}. */
    static final String PREF_LAST_LOOKUP = "last_lookup";

    /** On-screen keyboard appearance: {@link #THEME_DEVICE}, dark or light. */
    static final String PREF_THEME = "theme";

    static final String THEME_DEVICE = "device";
    static final String THEME_DARK = "dark";
    static final String THEME_LIGHT = "light";

    private InputManager inputManager;
    private InputManager.InputDeviceListener deviceListener;

    private KeyboardView keyboardView;
    private Keyboard khmer;
    private Keyboard khmerShift;
    private Keyboard latin;
    private Keyboard latinShift;
    private Keyboard number;
    private Keyboard khmerSymbols;
    private Keyboard latinSymbols;

    /** The theme the current input view was built with. */
    private boolean viewIsDark;

    /** Which script the on-screen keyboard is showing. */
    private boolean latinLayer;
    private boolean shifted;

    /** The field wants digits, so the number pad is showing. */
    private boolean numericField;

    /** The ?123 page is showing instead of the letters. */
    private boolean symbolsPage;

    /** The field being edited, kept for its input type. */
    private EditorInfo editor;

    private Dictionary khmerWords;
    private Dictionary latinWords;
    private LinearLayout candidateStrip;
    private LinearLayout suggestionStrip;
    private HorizontalScrollView suggestionScroller;

    /** How much of the text before the cursor the current suggestion replaces. */
    private int replacing;
    private boolean replacingCapital;

    // ---------------------------------------------------------------- on-screen

    @Override
    public void onInitializeInterface() {
        super.onInitializeInterface();
        // Called again after a configuration change, so key widths stay correct.
        khmer = new Keyboard(this, R.xml.soft_khmer);
        khmerShift = new Keyboard(this, R.xml.soft_khmer_shift);
        latin = new Keyboard(this, R.xml.soft_latin);
        latinShift = new Keyboard(this, R.xml.soft_latin_shift);
        number = new Keyboard(this, R.xml.soft_number);
        khmerSymbols = new Keyboard(this, R.xml.soft_khmer_sym);
        latinSymbols = new Keyboard(this, R.xml.soft_latin_sym);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Re-evaluate as soon as a keyboard is plugged in or unplugged, so the
        // on-screen keyboard appears and disappears on its own.
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            deviceListener = new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    updateInputViewShown();
                    hideIfPhysicalKeyboard();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    updateInputViewShown();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    updateInputViewShown();
                    hideIfPhysicalKeyboard();
                }
            };
            inputManager.registerInputDeviceListener(deviceListener, null);
        }
        loadDictionaries();
    }

    /**
     * Reads the word lists in the background. They are a few hundred kilobytes
     * each, and an input method must not make the first keystroke wait.
     */
    private void loadDictionaries() {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Dictionary khmer = null;
                Dictionary latin = null;
                String status;
                try {
                    khmer = Dictionary.load(getAssets(), "dict_km.txt");
                    latin = Dictionary.load(getAssets(), "dict_en.txt");
                    status = "loaded (" + khmer.size() + " Khmer, "
                            + latin.size() + " English)";
                } catch (Throwable t) {
                    // Suggestions are optional; typing must work regardless.
                    // But record why, or a silent failure is undiagnosable.
                    status = "FAILED: " + t;
                }
                note(PREF_DICT_STATUS, status);
                final Dictionary loadedKhmer = khmer;
                final Dictionary loadedLatin = latin;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        khmerWords = loadedKhmer;
                        latinWords = loadedLatin;
                    }
                });
            }
        }, "dictionary-load").start();
    }

    @Override
    public void onDestroy() {
        if (inputManager != null && deviceListener != null) {
            inputManager.unregisterInputDeviceListener(deviceListener);
        }
        super.onDestroy();
    }

    /**
     * True when a real, physical, letter-typing keyboard is attached.
     *
     * <p>Asked of the input devices rather than of {@link android.content.res.Configuration}
     * on purpose. Configuration describes the window's display, and in a desktop
     * or PC mode it reports no usable hard keyboard even while one is plugged in
     * and typing — which put the on-screen keyboard back over the screen in
     * exactly the situation this app exists to serve. The device list is the
     * same question asked directly, and it does not vary by display or mode.
     *
     * <p>{@code isVirtual()} filters out the synthetic device the platform
     * itself owns, and the alphabetic check filters out things like volume
     * rockers and game controllers, which are keyboard sources but cannot type.
     */
    private static boolean hasPhysicalKeyboard() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null || device.isVirtual()) {
                continue;
            }
            if (device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                continue;
            }
            if ((device.getSources() & InputDevice.SOURCE_KEYBOARD)
                    == InputDevice.SOURCE_KEYBOARD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps the on-screen keyboard hidden while a physical keyboard is attached.
     *
     * <p>This deliberately does not call {@code super}. The inherited version
     * also returns true whenever {@code Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD}
     * is set, and that setting is not public API, so an app can neither turn it
     * off nor offer a reliable way to. On some devices it is on by default,
     * which left a soft keyboard covering the screen for someone typing on their
     * physical keyboard.
     *
     * <p>So the decision is taken here instead, and {@link LanguagesActivity}
     * exposes it. With no physical keyboard the on-screen keyboard always shows,
     * because otherwise there would be nothing to type on.
     */
    @Override
    public boolean onEvaluateInputViewShown() {
        boolean shown = shouldShowOnScreenKeyboard();
        // Leave a trace of the decision for LanguagesActivity to report. Without
        // it there is no way to tell a service that decided "hide" and was
        // overridden from one that never ran this code at all.
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_LAST_DECISION, (shown ? "show" : "hide")
                        + " (keyboard detected: " + (hasPhysicalKeyboard() ? "yes" : "no")
                        + ", at " + android.text.format.DateFormat.format("HH:mm:ss",
                                System.currentTimeMillis()) + ")")
                .apply();
        return shown;
    }

    private boolean shouldShowOnScreenKeyboard() {
        if (!hasPhysicalKeyboard()) {
            return true;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_SHOW_WITH_HARD_KEYBOARD, false);
    }

    /**
     * True when the on-screen keyboard should be drawn dark.
     *
     * <p>Follows the device's night mode unless the user has chosen a fixed
     * theme.
     */
    private boolean wantDarkKeyboard() {
        String theme = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_THEME, THEME_DEVICE);
        if (THEME_DARK.equals(theme)) {
            return true;
        }
        if (THEME_LIGHT.equals(theme)) {
            return false;
        }
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public View onCreateInputView() {
        viewIsDark = wantDarkKeyboard();
        keyboardView = (KeyboardView) getLayoutInflater().inflate(
                viewIsDark ? R.layout.soft_keyboard_dark : R.layout.soft_keyboard_light,
                null);
        keyboardView.setOnKeyboardActionListener(this);

        // The suggestion strip sits in the input view rather than in the
        // platform's candidates view. The candidates view is only created when
        // the IME window is shown and is laid out outside the keyboard, which
        // made it unreliable here; this is simply part of the keyboard.
        suggestionStrip = new LinearLayout(this);
        suggestionStrip.setOrientation(LinearLayout.HORIZONTAL);
        suggestionScroller = new HorizontalScrollView(this);
        suggestionScroller.setHorizontalScrollBarEnabled(false);
        suggestionScroller.addView(suggestionStrip);
        suggestionScroller.setBackgroundColor(viewIsDark ? 0xFF15151A : 0xFFE6E9EF);
        suggestionScroller.setVisibility(View.GONE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(suggestionScroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(keyboardView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        applyKeyboard();
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // Rebuild if the theme changed, since the colours are baked into the
        // inflated view and cannot be swapped on the one that is up.
        if (keyboardView == null || viewIsDark != wantDarkKeyboard()) {
            setInputView(onCreateInputView());
        }
        editor = info;
        numericField = wantsDigits(info);
        latinLayer = numericField || wantsLatin(info) || !isKhmerSubtype();
        shifted = false;
        applyKeyboard();
        updateShiftFromCursor();
        updateCandidates();
        hideIfPhysicalKeyboard();
    }

    /**
     * Asks to be dismissed when a physical keyboard is doing the typing.
     *
     * <p>{@link #onEvaluateInputViewShown()} should be enough on its own, and on
     * a plain Android build it is. Some vendor shells show the input view
     * regardless, so this asks the system directly to take the keyboard away
     * rather than only answering when asked.
     */
    private void hideIfPhysicalKeyboard() {
        if (!shouldShowOnScreenKeyboard() && isInputViewShown()) {
            requestHideSelf(0);
        }
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
        Keyboard keyboard;
        if (numericField) {
            keyboard = number;
        } else if (symbolsPage) {
            keyboard = latinLayer ? latinSymbols : khmerSymbols;
        } else if (latinLayer) {
            keyboard = shifted ? latinShift : latin;
        } else {
            keyboard = shifted ? khmerShift : khmer;
        }
        keyboardView.setKeyboard(keyboard);
        keyboardView.setShifted(shifted);
    }

    /** A field that wants digits gets the number pad, whatever the language. */
    private static boolean wantsDigits(EditorInfo info) {
        if (info == null) {
            return false;
        }
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        return cls == InputType.TYPE_CLASS_NUMBER
                || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME;
    }

    /**
     * Fields that are never Khmer: an email address, a URL or a password is
     * Latin by definition, so opening on the Khmer layer would only be wrong.
     */
    private static boolean wantsLatin(EditorInfo info) {
        if (info == null
                || (info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        switch (info.inputType & InputType.TYPE_MASK_VARIATION) {
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_URI:
            case InputType.TYPE_TEXT_VARIATION_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Turns shift on where a capital belongs — the start of the text and
     * after a full stop. Khmer has no case, so this only applies to Latin.
     */
    private void updateShiftFromCursor() {
        if (numericField || !latinLayer || editor == null) {
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        boolean wanted = connection.getCursorCapsMode(capsInputType(editor)) != 0;
        if (wanted != shifted) {
            shifted = wanted;
            applyKeyboard();
        }
    }

    /**
     * The input type to ask {@link InputConnection#getCursorCapsMode} about.
     *
     * <p>That call only reports a capital where the field asked for one, and
     * most fields ask for nothing at all — which is why the first letter of a
     * message was staying lower case. A sentence begins with a capital whether
     * or not the app remembered to say so, so sentence capitalisation is
     * supplied here for ordinary prose fields. Fields that already state a
     * preference keep it, and the ones where a capital would be wrong —
     * passwords, e-mail addresses, URLs — are left alone.
     */
    private static int capsInputType(EditorInfo info) {
        int type = info.inputType;
        if ((type & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                || wantsLatin(info)) {
            return type;
        }
        int asked = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if ((type & asked) != 0) {
            return type;
        }
        return type | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
            int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        updateShiftFromCursor();
        // Fires for physical typing too, which is how that gets suggestions.
        updateCandidates();
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
                symbolsPage = false;
                applyKeyboard();
                return;
            case KEYCODE_TO_SYMBOLS:
                symbolsPage = true;
                shifted = false;
                applyKeyboard();
                return;
            case KEYCODE_TO_LETTERS:
                symbolsPage = false;
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
                // Shift is one-shot; auto-capitalisation decides the next state.
                if (shifted) {
                    shifted = false;
                    applyKeyboard();
                }
                updateShiftFromCursor();
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


    // ------------------------------------------------------------ suggestions

    private static final int KEYCODE_TO_SYMBOLS = -101;
    private static final int KEYCODE_TO_LETTERS = -102;

    private static final int MAX_CANDIDATES = 8;

    /** How far back to look for the word being typed. */
    private static final int LOOKBEHIND = 24;

    /**
     * The strip of suggestions.
     *
     * <p>Deliberately a candidates view rather than part of the keyboard: the
     * platform can show it while the input view is hidden, which is what makes
     * suggestions work for someone typing on the physical keyboard.
     */
    @Override
    public View onCreateCandidatesView() {
        candidateStrip = new LinearLayout(this);
        candidateStrip.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(candidateStrip);
        return scroller;
    }

    private static boolean isKhmer(char c) {
        return c >= '\u1780' && c <= '\u17FF';
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Offers completions for whatever is being typed before the cursor.
     *
     * <p>Nothing here alters the text. The characters are committed exactly as
     * before and simply read back, so the physical keyboard's path through the
     * key character map is untouched; picking a suggestion is what edits, by
     * deleting the partial word and committing the whole one.
     *
     * <p>Khmer writes without spaces between words, so where a word begins is
     * genuinely ambiguous. The longest recent run of Khmer is tried as a
     * starting point, then progressively shorter tails of it, and the first
     * that the dictionary recognises wins - which favours the most context that
     * still matches something real.
     */
    private void updateCandidates() {
        List<String> suggestions = Collections.emptyList();
        replacing = 0;
        replacingCapital = false;

        InputConnection connection = getCurrentInputConnection();
        if (connection != null && suggestionsEnabled()) {
            CharSequence before = connection.getTextBeforeCursor(LOOKBEHIND, 0);
            if (before != null && before.length() > 0) {
                char last = before.charAt(before.length() - 1);
                if (isLatinLetter(last) && latinWords != null) {
                    int start = before.length();
                    while (start > 0 && isLatinLetter(before.charAt(start - 1))) {
                        start--;
                    }
                    String word = before.subSequence(start, before.length()).toString();
                    suggestions = latinWords.completions(word.toLowerCase(), MAX_CANDIDATES);
                    replacing = word.length();
                    // The word list is lower case; a word typed with a capital
                    // should not lose it by being picked from the strip.
                    replacingCapital = Character.isUpperCase(word.charAt(0));
                } else if (isKhmer(last) && khmerWords != null) {
                    int start = before.length();
                    while (start > 0 && isKhmer(before.charAt(start - 1))) {
                        start--;
                    }
                    for (int from = start; from < before.length(); from++) {
                        String tail = before.subSequence(from, before.length()).toString();
                        List<String> found = khmerWords.completions(tail, MAX_CANDIDATES);
                        if (!found.isEmpty()) {
                            suggestions = found;
                            replacing = tail.length();
                            break;
                        }
                    }
                }
            }
        }
        note(PREF_LAST_LOOKUP, "strip=" + (candidateStrip == null ? "not created" : "ready")
                + ", dictionaries=" + (latinWords == null ? "null" : "ready")
                + ", matches=" + suggestions.size()
                + (suggestions.isEmpty() ? "" : " (" + suggestions.get(0) + ")"));
        showCandidates(suggestions);
    }

    private boolean suggestionsEnabled() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_SUGGESTIONS, true);
    }

    private void showCandidates(List<String> suggestions) {
        // The strip inside the keyboard is the one that shows while the
        // on-screen keyboard is up; the platform candidates view covers the
        // case where it is hidden because a physical keyboard is attached.
        fill(suggestionStrip, suggestions);
        if (suggestionScroller != null) {
            suggestionScroller.setVisibility(
                    suggestions.isEmpty() ? View.GONE : View.VISIBLE);
        }
        setCandidatesViewShown(!suggestions.isEmpty() && !isInputViewShown());
        if (candidateStrip == null) {
            return;
        }
        fill(candidateStrip, suggestions);
    }

    private void fill(LinearLayout strip, List<String> suggestions) {
        if (strip == null) {
            return;
        }
        strip.removeAllViews();
        for (final String suggestion : suggestions) {
            TextView view = new TextView(this);
            view.setText(suggestion);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            view.setTextColor(viewIsDark ? 0xFFFFFFFF : 0xFF1B1B1F);
            view.setGravity(Gravity.CENTER);
            int pad = Math.round(14 * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad / 2, pad, pad / 2);
            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick(suggestion);
                }
            });
            strip.addView(view);
        }
    }

    /** Leaves a short note for LanguagesActivity to report. */
    private void note(String key, String value) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    /** Swaps the partial word for the chosen one. */
    private void pick(String suggestion) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        if (replacingCapital && suggestion.length() > 0) {
            suggestion = Character.toUpperCase(suggestion.charAt(0)) + suggestion.substring(1);
        }
        connection.beginBatchEdit();
        if (replacing > 0) {
            connection.deleteSurroundingText(replacing, 0);
        }
        connection.commitText(suggestion, 1);
        connection.endBatchEdit();
        replacing = 0;
        replacingCapital = false;
        showCandidates(Collections.<String>emptyList());
    }

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
