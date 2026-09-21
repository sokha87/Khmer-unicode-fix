package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What tends to follow what in English at large, read from a shipped asset.
 *
 * <p>{@link NextWords} learns the same thing from the person typing, which is
 * far better once it has seen enough, but it has seen nothing on the day the
 * keyboard is installed. This is the standing answer underneath it: English
 * says "much" follows "so" whoever is typing, and that is worth offering while
 * the learned counts build up.
 *
 * <p>Sorted by context so a lookup is a bisection; see tools/gen_bigrams.py.
 */
final class Bigrams {

    private final String[] contexts;
    private final String[][] followers;

    private Bigrams(String[] contexts, String[][] followers) {
        this.contexts = contexts;
        this.followers = followers;
    }

    static Bigrams load(AssetManager assets, String name) throws IOException {
        return load(assets.open(name));
    }

    /** Split out from the asset path so it can be exercised off-device. */
    static Bigrams load(InputStream stream) throws IOException {
        List<String> loadedContexts = new ArrayList<>();
        List<String[]> loadedFollowers = new ArrayList<>();
        try {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream, "UTF-8"), 65536);
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                loadedContexts.add(line.substring(0, tab));
                loadedFollowers.add(line.substring(tab + 1).split(" "));
            }
        } finally {
            stream.close();
        }
        return new Bigrams(
                loadedContexts.toArray(new String[loadedContexts.size()]),
                loadedFollowers.toArray(new String[loadedFollowers.size()][]));
    }

    /**
     * @return the words that usually follow {@code word}, likeliest first, or
     *         an empty list if the corpus has nothing to say about it.
     */
    List<String> after(String word) {
        if (word == null || word.length() == 0) {
            return Collections.emptyList();
        }
        int at = Arrays.binarySearch(contexts, word.toLowerCase());
        return at < 0 ? Collections.<String>emptyList() : Arrays.asList(followers[at]);
    }

    int size() {
        return contexts.length;
    }
}
