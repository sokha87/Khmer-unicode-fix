package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Lets the user pick which languages this keyboard offers.
 *
 * <p>An app cannot enable or disable its own subtypes directly — the enabled set
 * lives in {@code Settings.Secure.ENABLED_INPUT_METHODS}, which needs
 * {@code WRITE_SECURE_SETTINGS}. ({@code setExplicitlyEnabledInputMethodSubtypes}
 * would allow it, but that is API 34 and this app supports API 21.) So this
 * screen hands off to the platform's own subtype enabler, which writes that
 * setting itself.
 *
 * <p>That hand-off is what makes "only one language" possible at all: subtypes
 * chosen there are stored as <em>explicitly</em> enabled, which overrides the
 * implicit, locale-dependent defaults described in {@code res/xml/method.xml}.
 */
public class LanguagesActivity extends Activity {

    private TextView diagnostics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int pad = dp(24);
        final int gap = dp(12);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.languages_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        column.addView(title);

        TextView body = new TextView(this);
        body.setText(R.string.languages_body);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setPadding(0, gap, 0, gap);
        column.addView(body);

        final SharedPreferences prefs = getSharedPreferences(
                KhmerSequenceInputMethodService.PREFS, MODE_PRIVATE);
        CheckBox showWithHardKeyboard = new CheckBox(this);
        showWithHardKeyboard.setText(R.string.languages_show_with_hard_keyboard);
        showWithHardKeyboard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        showWithHardKeyboard.setChecked(prefs.getBoolean(
                KhmerSequenceInputMethodService.PREF_SHOW_WITH_HARD_KEYBOARD, false));
        showWithHardKeyboard.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        prefs.edit().putBoolean(
                                KhmerSequenceInputMethodService.PREF_SHOW_WITH_HARD_KEYBOARD,
                                checked).apply();
                    }
                });
        showWithHardKeyboard.setPadding(0, gap, 0, gap);
        column.addView(showWithHardKeyboard);

        column.addView(button(R.string.languages_choose, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSubtypeEnabler();
            }
        }));

        column.addView(button(R.string.languages_keyboard_settings, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        }));

        diagnostics = new TextView(this);
        diagnostics.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        diagnostics.setPadding(0, gap * 2, 0, 0);
        column.addView(diagnostics);

        ScrollView root = new ScrollView(this);
        root.addView(column);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (diagnostics != null) {
            diagnostics.setText(describeState());
        }
    }

    /**
     * What the keyboard currently sees, so a report from a device does not have
     * to be guesswork. This is the same check the input method itself uses to
     * decide whether to show the on-screen keyboard.
     */
    private CharSequence describeState() {
        StringBuilder out = new StringBuilder();
        out.append("Status\n\n");

        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Cannot happen for our own package.
        }
        out.append("Version: ").append(version).append('\n');

        boolean physical = false;
        StringBuilder devices = new StringBuilder();
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null || device.isVirtual()) {
                continue;
            }
            boolean keyboardSource = (device.getSources() & InputDevice.SOURCE_KEYBOARD)
                    == InputDevice.SOURCE_KEYBOARD;
            boolean alphabetic =
                    device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
            if (keyboardSource && alphabetic) {
                physical = true;
            }
            devices.append("  \u2022 ").append(device.getName())
                   .append(keyboardSource ? " [keyboard" : " [not a keyboard")
                   .append(alphabetic ? ", can type letters]" : ", cannot type letters]")
                   .append('\n');
        }

        out.append("Physical keyboard detected: ")
           .append(physical ? "YES" : "NO").append('\n');

        SharedPreferences prefs = getSharedPreferences(
                KhmerSequenceInputMethodService.PREFS, MODE_PRIVATE);
        boolean forced = prefs.getBoolean(
                KhmerSequenceInputMethodService.PREF_SHOW_WITH_HARD_KEYBOARD, false);
        out.append("Show anyway (checkbox above): ").append(forced ? "ON" : "off").append('\n');

        out.append("On-screen keyboard will: ")
           .append(!physical || forced ? "SHOW" : "stay hidden").append("\n\n");

        out.append("Attached input devices:\n");
        out.append(devices.length() == 0 ? "  (none)\n" : devices);
        return out;
    }

    /**
     * Opens the platform screen listing this keyboard's languages with
     * checkboxes. Falls back to the general input-method settings if a device
     * does not ship that screen.
     */
    private void openSubtypeEnabler() {
        String imeId = new ComponentName(this, KhmerSequenceInputMethodService.class)
                .flattenToShortString();
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS);
        intent.putExtra(Settings.EXTRA_INPUT_METHOD_ID, imeId);
        if (!start(intent)) {
            start(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        }
    }

    /** @return true if the intent was actually started. */
    private boolean start(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.languages_unavailable, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private Button button(int textRes, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(textRes);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
