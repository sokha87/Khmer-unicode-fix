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

import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;
import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    /** Whether the Khmer typeface reached the key labels; see {@link #keyFont}. */
    static final String PREF_FONT_STATUS = "font_status";

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

    /** What follows what in English at large, until the learned counts take over. */
    private Bigrams latinPairs;

    /**
     * Battambang, shipped so the keys and the suggestions read the same on
     * every phone rather than in whatever Khmer face the vendor chose.
     */
    private Typeface keyFont;

    /** What has followed what in this phone's own typing; see {@link NextWords}. */
    private final NextWords nextWords = new NextWords();

    /** The finished word before the one being typed, or null if there is none. */
    private String contextWord;

    /** The pair last learned, so a cursor move does not count it twice. */
    private String lastLearned;

    /** The context under which a word is counted regardless of what preceded it. */
    private static final String EVERYWHERE = "\u0000";

    /**
     * What this keyboard has typed into the field, most recent last.
     *
     * <p>Suggestions are normally worked out from the text read back from the
     * editor, which is the truth and includes whatever was already there. Some
     * editors return nothing at all - a browser's search box among them - and
     * in those the strip had nothing to go on and fell back on the words
     * written most often, which is why it kept offering English under Khmer
     * typing. This is the fallback: less than the truth, since it only knows
     * what was typed here, but enough to complete the word in hand.
     */
    private final StringBuilder typed = new StringBuilder();

    private LinearLayout candidateStrip;
    private LinearLayout suggestionStrip;
    private HorizontalScrollView suggestionScroller;

    /** How much of the text before the cursor the current suggestion replaces. */
    private int replacing;
    private boolean replacingCapital;

    /**
     * What to put after a picked suggestion so the next word can be typed
     * straight away. Latin takes a space. Khmer is written without spaces
     * between words, so a space there would break the text; it takes a zero
     * width space instead, which is the Khmer word boundary - invisible, it
     * lets a line break fall in the right place, and it shows the next lookup
     * where the new word starts.
     */
    private String separator = "";

    private static final String KHMER_WORD_BREAK = "\u200b";

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
        khmerSymbols = new Keyboard(this, R.xml.soft_khmer_alt);
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
        loadKeyFont();
        nextWords.load(learnedWordsFile());
    }

    /** The learned word pairs live in the app's own storage, and go nowhere else. */
    private File learnedWordsFile() {
        return new File(getFilesDir(), "next_words.txt");
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
                Bigrams pairs = null;
                String status;
                try {
                    khmer = Dictionary.load(getAssets(), "dict_km.txt");
                    latin = Dictionary.load(getAssets(), "dict_en.txt");
                    pairs = Bigrams.load(getAssets(), "bigrams_en.txt");
                    status = "loaded (" + khmer.size() + " Khmer, "
                            + latin.size() + " English, "
                            + pairs.size() + " English pairs)";
                } catch (Throwable t) {
                    // Suggestions are optional; typing must work regardless.
                    // But record why, or a silent failure is undiagnosable.
                    status = "FAILED: " + t;
                }
                note(PREF_DICT_STATUS, status);
                final Dictionary loadedKhmer = khmer;
                final Dictionary loadedLatin = latin;
                final Bigrams loadedPairs = pairs;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        khmerWords = loadedKhmer;
                        latinWords = loadedLatin;
                        latinPairs = loadedPairs;
                    }
                });
            }
        }, "dictionary-load").start();
    }

    private void loadKeyFont() {
        try {
            keyFont = Typeface.createFromAsset(getAssets(), "battambang.ttf");
        } catch (Throwable t) {
            // The system Khmer face will do; this must not stop the keyboard.
            keyFont = null;
            note(PREF_FONT_STATUS, "not loaded: " + t);
        }
    }

    /**
     * Puts the shipped typeface on the key labels.
     *
     * <p>{@link KeyboardView} paints every label with one {@link Paint} of its
     * own and offers no way to reach it: there is no typeface attribute in its
     * XML and no setter on the class. Reaching in for the field is the only
     * way short of drawing every key by hand, and a platform that refuses the
     * reach simply leaves the labels in the system face - which is correct
     * Khmer, just not this one. The outcome is recorded either way, because a
     * font that silently did not apply is indistinguishable from one that did
     * not load.
     */
    private void applyKeyFont(KeyboardView view) {
        if (keyFont == null || view == null) {
            return;
        }
        try {
            Field field = KeyboardView.class.getDeclaredField("mPaint");
            field.setAccessible(true);
            Object paint = field.get(view);
            if (paint instanceof Paint) {
                ((Paint) paint).setTypeface(keyFont);
                view.invalidateAllKeys();
                note(PREF_FONT_STATUS, "Battambang on the key labels");
            } else {
                note(PREF_FONT_STATUS, "key labels unchanged: no paint to set");
            }
        } catch (Throwable t) {
            note(PREF_FONT_STATUS, "key labels in the system font ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public void onDestroy() {
        if (inputManager != null && deviceListener != null) {
            inputManager.unregisterInputDeviceListener(deviceListener);
        }
        saveLearnedWords();
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
        applyKeyFont(keyboardView);

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
    public void onFinishInput() {
        super.onFinishInput();
        // The natural moment to write out what was learned while typing.
        saveLearnedWords();
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
        // A different field, so nothing typed into the last one applies.
        typed.setLength(0);
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
     * Turns shift on where a capital belongs - the start of the text and after
     * a full stop. Khmer has no case, so this only applies to Latin.
     */
    private void updateShiftFromCursor() {
        if (numericField || !latinLayer || editor == null) {
            return;
        }
        int modes = capsModes(editor);
        boolean wanted = false;
        if (modes != 0) {
            InputConnection connection = getCurrentInputConnection();
            if (connection == null) {
                return;
            }
            CharSequence before = connection.getTextBeforeCursor(LOOKBEHIND, 0);
            if (before == null) {
                before = "";
            }
            wanted = wantsCapital(before, before.length() < LOOKBEHIND, modes);
        }
        if (wanted != shifted) {
            shifted = wanted;
            applyKeyboard();
        }
    }

    /**
     * Which capitalisation rules apply in this field, or 0 for none.
     *
     * <p>Most fields request nothing at all, so a field that asks for nothing
     * gets sentence capitalisation anyway: a sentence starts with a capital
     * whether or not the app remembered to say so. Only the fields where a
     * capital would be actively wrong are left alone, and that is a shorter
     * list than it looks - a browser's address bar doubles as its search box,
     * and domain names are case-insensitive, so capitalising there costs
     * nothing and not capitalising costs every sentence typed into it.
     */
    private static int capsModes(EditorInfo info) {
        if ((info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return 0;
        }
        switch (info.inputType & InputType.TYPE_MASK_VARIATION) {
            case InputType.TYPE_TEXT_VARIATION_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                return 0;
            default:
                break;
        }
        int asked = info.inputType
                & (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return asked != 0 ? asked : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    }

    /**
     * Whether the next letter typed at the end of {@code before} is a capital.
     *
     * <p>Worked out here rather than asked of the editor. {@code
     * getCursorCapsMode} is the editor's own answer, and an editor that has not
     * implemented it answers "no capital" everywhere, which is why the first
     * letter stayed lower case in some apps - a browser's search box among
     * them. The rules are short enough to apply to text read back directly.
     *
     * <p>{@code wholeText} says whether {@code before} is all the text there
     * is. Only so much is read back, and running out of buffer looks exactly
     * like the start of the field; without this, every sentence long enough to
     * fill the buffer would capitalise in the middle.
     */
    private static boolean wantsCapital(CharSequence before, boolean wholeText,
            int modes) {
        if ((modes & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) {
            return true;
        }
        int at = before.length();
        while (at > 0 && isWordBreak(before.charAt(at - 1))) {
            at--;
        }
        if (at == 0) {
            return wholeText;
        }
        if ((modes & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0) {
            return at < before.length();
        }
        char ends = before.charAt(at - 1);
        return ends == '.' || ends == '!' || ends == '?' || ends == '\n';
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
                forget(1);
                updateCandidates();
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
                updateCandidates();
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
        updateCandidates();
    }

    private void commit(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.commitText(text, 1);
        }
        track(text);
    }

    /** Notes text added to the field, keeping {@link #typed} to a useful tail. */
    private void track(String text) {
        typed.append(text);
        if (typed.length() > LOOKBEHIND) {
            typed.delete(0, typed.length() - LOOKBEHIND);
        }
    }

    /** Keeps {@link #typed} in step with text removed from the field. */
    private void forget(int characters) {
        typed.delete(Math.max(0, typed.length() - characters), typed.length());
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
    private static final int LOOKBEHIND = 48;

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
        separator = "";
        contextWord = null;

        InputConnection connection = getCurrentInputConnection();
        if (connection != null && suggestionsEnabled()) {
            CharSequence before = connection.getTextBeforeCursor(LOOKBEHIND, 0);
            if (before == null || before.length() == 0) {
                // Editors that will not say; see #typed. Taken as a snapshot,
                // because correcting a pronoun below edits the record itself.
                before = typed.toString();
            }
            if (before.length() > 0) {
                char last = before.charAt(before.length() - 1);
                if (isLatinLetter(last) && latinWords != null) {
                    int start = before.length();
                    while (start > 0 && isLatinLetter(before.charAt(start - 1))) {
                        start--;
                    }
                    String word = before.subSequence(start, before.length()).toString();
                    contextWord = wordEndingAt(before, start);
                    suggestions = preferWhatFollows(
                            latinWords.completions(word.toLowerCase(), MAX_CANDIDATES));
                    replacing = word.length();
                    // The word list is lower case; a word typed with a capital
                    // should not lose it by being picked from the strip.
                    replacingCapital = Character.isUpperCase(word.charAt(0));
                    separator = " ";
                } else if (isKhmer(last) && khmerWords != null) {
                    int start = before.length();
                    while (start > 0 && isKhmer(before.charAt(start - 1))) {
                        start--;
                    }
                    contextWord = wordEndingAt(before, start);
                    for (int from = start; from < before.length(); from++) {
                        String tail = before.subSequence(from, before.length()).toString();
                        List<String> found = khmerWords.completions(tail, MAX_CANDIDATES);
                        if (!found.isEmpty()) {
                            suggestions = preferWhatFollows(found);
                            replacing = tail.length();
                            separator = KHMER_WORD_BREAK;
                            break;
                        }
                    }
                } else if (!isWordChar(last)) {
                    // A word has just been closed off. Remember what it
                    // followed, and - once there is a space to put one in -
                    // offer what usually comes after it.
                    List<String> tail = wordsBefore(before, 2);
                    if (!tail.isEmpty()) {
                        String finished =
                                capitalisePronoun(connection, before, tail.get(0));
                        remember(tail.size() > 1 ? tail.get(1) : null, finished, before);
                        if (isWordBreak(last)) {
                            contextWord = finished;
                            suggestions = whatComesAfter(finished);
                            separator = isKhmer(finished.charAt(finished.length() - 1))
                                    ? KHMER_WORD_BREAK : " ";
                        }
                    }
                }
            } else {
                // An empty field: nothing to complete and nothing to follow,
                // but the words written most often are still a fair opening -
                // the ones in the script being written, at least.
                List<String> opening = new ArrayList<>();
                addUnseen(opening, nextWords.after(EVERYWHERE, MAX_CANDIDATES),
                        null, !latinLayer);
                suggestions = opening;
                separator = latinLayer ? " " : KHMER_WORD_BREAK;
            }
        }
        note(PREF_LAST_LOOKUP, "strip=" + (candidateStrip == null ? "not created" : "ready")
                + ", dictionaries=" + (latinWords == null ? "null" : "ready")
                + ", learned=" + nextWords.size()
                + ", after=" + (contextWord == null ? "-" : contextWord)
                + ", matches=" + suggestions.size()
                + (suggestions.isEmpty() ? "" : " (" + suggestions.get(0) + ")"));
        showCandidates(suggestions);
    }

    /** A space, or the zero width space that separates Khmer words. */
    private static boolean isWordBreak(char c) {
        return c == ' ' || c == '\u200b' || c == '\t';
    }

    private static boolean isWordChar(char c) {
        return isLatinLetter(c) || isKhmer(c) || c == '\'';
    }

    /**
     * The word that ends at {@code end}, skipping any word breaks just before
     * it, or null if the text there is punctuation or the start of the field.
     *
     * <p>Stopping at punctuation is the point: "hello. world" should not teach
     * the keyboard that "world" follows "hello", because it does not - a new
     * sentence started.
     */
    private static String wordEndingAt(CharSequence text, int end) {
        int i = end;
        while (i > 0 && isWordBreak(text.charAt(i - 1))) {
            i--;
        }
        int stop = i;
        while (i > 0 && isWordChar(text.charAt(i - 1))) {
            i--;
        }
        return i == stop ? null : text.subSequence(i, stop).toString();
    }

    /**
     * Up to {@code limit} words at the end of {@code text}, most recent first.
     *
     * <p>Whatever closed off the last word is stepped over, punctuation
     * included - "hello world." still ends in the word "world". Between words,
     * only spaces are stepped over, so "hello. world" yields "world" alone: a
     * full stop between two words means the second does not follow the first,
     * and teaching the keyboard otherwise would be teaching it nonsense.
     */
    private static List<String> wordsBefore(CharSequence text, int limit) {
        List<String> found = new ArrayList<>();
        int i = text.length();
        boolean last = true;
        while (found.size() < limit) {
            while (i > 0 && (last ? !isWordChar(text.charAt(i - 1))
                                  : isWordBreak(text.charAt(i - 1)))) {
                i--;
            }
            int stop = i;
            while (i > 0 && isWordChar(text.charAt(i - 1))) {
                i--;
            }
            if (i == stop) {
                break;          // a sentence boundary, or the start of the field
            }
            found.add(text.subSequence(i, stop).toString());
            last = false;
        }
        return found;
    }

    /**
     * Moves the completions that have followed {@link #contextWord} before to
     * the front, commonest first, leaving the rest in the order the word list
     * gave them.
     *
     * <p>This is what makes the strip read the sentence rather than the word:
     * with "my l" typed, "love" beats "long" if "my love" has been written
     * before, however the two rank in the language at large.
     */
    private List<String> preferWhatFollows(List<String> completions) {
        if (contextWord == null || completions.size() < 2) {
            return completions;
        }
        List<String> usual =
                latinPairs == null ? Collections.<String>emptyList()
                                   : latinPairs.after(contextWord);
        List<String> seen = new ArrayList<>();
        List<String> known = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String word : completions) {
            if (nextWords.timesAfter(contextWord, word) > 0) {
                seen.add(word);
            } else if (usual.contains(word)) {
                known.add(word);
            } else {
                rest.add(word);
            }
        }
        if (seen.isEmpty() && known.isEmpty()) {
            return completions;
        }
        final String context = contextWord;
        Collections.sort(seen, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return nextWords.timesAfter(context, b) - nextWords.timesAfter(context, a);
            }
        });
        seen.addAll(known);
        seen.addAll(rest);
        return seen;
    }

    /**
     * English writes the pronoun with a capital wherever it falls: "i" is
     * always "I", and so are "i'm", "i'll" and "i've". Nothing else is
     * corrected - a keyboard that rewrites what was typed is worse than one
     * that leaves it alone - but this one is unambiguous, and doing it by hand
     * means reaching for shift in the middle of every other sentence.
     */
    private static String englishCase(String word) {
        return word.equals("i") || word.startsWith("i'")
                ? "I" + word.substring(1) : word;
    }

    /**
     * Puts the capital on a pronoun that has just been finished.
     *
     * @return the word as it now stands in the field
     */
    private String capitalisePronoun(InputConnection connection, CharSequence before,
            String word) {
        if (!latinLayer || editor == null || capsModes(editor) == 0) {
            return word;
        }
        String fixed = englishCase(word);
        if (fixed.equals(word)) {
            return word;
        }
        // Whatever closed the word off - a space, a full stop - is put back.
        int trailing = 0;
        while (trailing < before.length()
                && !isWordChar(before.charAt(before.length() - 1 - trailing))) {
            trailing++;
        }
        String closing =
                before.subSequence(before.length() - trailing, before.length()).toString();
        connection.beginBatchEdit();
        connection.deleteSurroundingText(word.length() + trailing, 0);
        connection.commitText(fixed + closing, 1);
        connection.endBatchEdit();
        forget(word.length() + trailing);
        track(fixed + closing);
        return fixed;
    }

    /**
     * Counts a word once, however often the cursor passes back over it.
     *
     * <p>The text leading up to the word says where it was written, so the same
     * pair written twice in a sentence counts twice, while the cursor wandering
     * back over one counts once. The cursor position would have done as well,
     * but it is only known when the editor reports it, and the strip no longer
     * waits for that.
     */
    private void remember(String previous, String word, CharSequence where) {
        String key = where.toString();
        if (key.equals(lastLearned)) {
            return;
        }
        lastLearned = key;
        if (previous != null) {
            nextWords.learn(previous, word);
        }
        // Also counted without any context, which is what the strip falls back
        // on before it has learned enough to say what follows what.
        nextWords.learn(EVERYWHERE, word);
    }

    /**
     * What to offer once a word is finished: the words that have followed it,
     * topped up with the words written most often anywhere.
     *
     * <p>The top-up is what makes this worth anything on the first day. A
     * count of what follows what is only as good as what it has seen, and it
     * has seen nothing until the same pair has been written twice, so on its
     * own the strip would sit empty for a long while. The words someone writes
     * most often are a fair guess in the meantime, and picking one teaches the
     * pair, so the strip sharpens as it is used.
     */
    private List<String> whatComesAfter(String word) {
        boolean khmer = isKhmer(word.charAt(word.length() - 1));
        List<String> found = new ArrayList<>();
        addUnseen(found, nextWords.after(word, MAX_CANDIDATES), word, khmer);
        if (!khmer && latinPairs != null) {
            addUnseen(found, latinPairs.after(word), word, khmer);
        }
        addUnseen(found, nextWords.after(EVERYWHERE, MAX_CANDIDATES), word, khmer);
        return found;
    }

    /**
     * Adds what is not already there, skipping anything in the wrong script.
     *
     * <p>The learned counts are one table across both languages, because a
     * Khmer sentence can quote an English word and the pair is worth knowing
     * either way. What is worth nothing is offering English words under Khmer
     * typing, which is what the strip did whenever it had nothing else.
     */
    private static void addUnseen(List<String> found, List<String> extra,
            String word, boolean khmer) {
        for (String candidate : extra) {
            if (found.size() >= MAX_CANDIDATES) {
                return;
            }
            if (candidate.length() == 0 || isKhmer(candidate.charAt(0)) != khmer) {
                continue;
            }
            if (!found.contains(candidate) && !candidate.equals(word)) {
                found.add(candidate);
            }
        }
    }

    private void saveLearnedWords() {
        if (nextWords.isDirty()) {
            nextWords.save(learnedWordsFile());
        }
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
            if (keyFont != null) {
                view.setTypeface(keyFont);
            }
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

    /**
     * Swaps the partial word for the chosen one, and closes it off so typing
     * can carry straight on into the next word - see {@link #separator}.
     */
    private void pick(String suggestion) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        if (latinLayer && editor != null && capsModes(editor) != 0) {
            suggestion = englishCase(suggestion);
        }
        if (replacingCapital && suggestion.length() > 0) {
            suggestion = Character.toUpperCase(suggestion.charAt(0)) + suggestion.substring(1);
        }
        connection.beginBatchEdit();
        if (replacing > 0) {
            connection.deleteSurroundingText(replacing, 0);
            forget(replacing);
        }
        connection.commitText(suggestion + separator, 1);
        connection.endBatchEdit();
        track(suggestion + separator);
        replacing = 0;
        replacingCapital = false;
        separator = "";
        // Work the strip out again from the text as it now stands, rather than
        // clearing it and waiting for onUpdateSelection. Not every editor
        // reports a selection change promptly, and one that does not used to
        // leave the strip blank from the first pick onwards - which is exactly
        // the moment the next word should be offered.
        updateCandidates();
        // A word just ended, so the next one may want a capital.
        updateShiftFromCursor();
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
