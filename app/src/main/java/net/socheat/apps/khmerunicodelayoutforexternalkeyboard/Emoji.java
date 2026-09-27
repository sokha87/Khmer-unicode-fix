package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.content.res.AssetManager;
import android.graphics.Paint;
import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The emoji the keyboard offers, in groups, read from a shipped asset.
 *
 * <p>Anything the phone cannot draw is dropped as the list is read. Emoji are
 * added to Unicode faster than phones are updated, so a list current enough to
 * be worth shipping is always ahead of some of the phones it lands on, and an
 * emoji a phone has no glyph for is an empty box in the grid that types an
 * empty box into the message. Asking the font stack what it can draw costs one
 * pass at load and keeps the grid honest on every phone.
 *
 * <p>See tools/gen_emoji.py.
 */
final class Emoji {

    /** One tab of the grid. */
    static final class Group {
        final String name;
        final List<String> emoji;

        Group(String name, List<String> emoji) {
            this.name = name;
            this.emoji = emoji;
        }
    }

    private final List<Group> groups;

    private Emoji(List<Group> groups) {
        this.groups = groups;
    }

    static Emoji load(AssetManager assets, String name, Paint probe) throws IOException {
        return load(assets.open(name), probe);
    }

    /** Split out from the asset path so it can be exercised off-device. */
    static Emoji load(InputStream stream, Paint probe) throws IOException {
        List<Group> loaded = new ArrayList<>();
        try {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream, "UTF-8"), 16384);
            String name;
            while ((name = reader.readLine()) != null) {
                String line = reader.readLine();
                if (line == null) {
                    break;                    // a name with no emoji after it
                }
                List<String> kept = new ArrayList<>();
                for (String one : line.split(" ")) {
                    if (one.length() > 0 && canDraw(probe, one)) {
                        kept.add(one);
                    }
                }
                if (!kept.isEmpty()) {
                    loaded.add(new Group(name, kept));
                }
            }
        } finally {
            stream.close();
        }
        return new Emoji(Collections.unmodifiableList(loaded));
    }

    /**
     * {@code hasGlyph} arrived in API 23. Below that every emoji is offered:
     * a phone that old will draw some boxes, which is still better than an
     * emoji keyboard with nothing in it.
     */
    private static boolean canDraw(Paint probe, String emoji) {
        if (probe == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return probe.hasGlyph(emoji);
    }

    List<Group> groups() {
        return groups;
    }

    int size() {
        int total = 0;
        for (Group group : groups) {
            total += group.emoji.size();
        }
        return total;
    }
}
