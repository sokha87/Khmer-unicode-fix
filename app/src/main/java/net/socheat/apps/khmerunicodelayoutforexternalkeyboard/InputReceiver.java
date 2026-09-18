package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Advertises the Khmer keyboard layout to the system.
 *
 * <p>Android queries every receiver registered for
 * {@code android.hardware.input.action.QUERY_KEYBOARD_LAYOUTS}, reads the
 * {@code KEYBOARD_LAYOUTS} meta-data pointing at {@code res/xml/keyboard_layouts.xml},
 * and from there loads {@code res/raw/keyboard_layout_khmer.kcm}. Nothing has to
 * happen at runtime, so this receiver intentionally does no work.
 */
public class InputReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // Nothing to do: the layout is supplied entirely through meta-data.
    }
}
