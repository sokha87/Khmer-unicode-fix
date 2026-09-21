package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A word list that can be asked for completions of a prefix.
 *
 * <p>The asset is sorted by word, so a prefix is found by bisection, and each
 * entry carries its frequency rank, so the matches can be ordered by how common
 * they are rather than alphabetically. Sorting one way and ranking the other
 * keeps both halves of the lookup cheap.
 */
final class Dictionary {

    private final String[] words;
    private final int[] ranks;

    private Dictionary(String[] words, int[] ranks) {
        this.words = words;
        this.ranks = ranks;
    }

    /** Reads one of the generated assets; see tools/gen_dictionaries.py. */
    static Dictionary load(AssetManager assets, String name) throws IOException {
        return load(assets.open(name));
    }

    /** Split out from the asset path so it can be exercised off-device. */
    static Dictionary load(InputStream stream) throws IOException {
        List<String> loadedWords = new ArrayList<>();
        List<Integer> loadedRanks = new ArrayList<>();
        try {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream, "UTF-8"), 65536);
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                loadedWords.add(line.substring(0, tab));
                loadedRanks.add(Integer.parseInt(line.substring(tab + 1)));
            }
        } finally {
            stream.close();
        }
        String[] words = loadedWords.toArray(new String[loadedWords.size()]);
        int[] ranks = new int[loadedRanks.size()];
        for (int i = 0; i < ranks.length; i++) {
            ranks[i] = loadedRanks.get(i);
        }
        return new Dictionary(words, ranks);
    }

    int size() {
        return words.length;
    }

    /**
     * How much a completion is set back by each character it adds to what has
     * been typed, measured in places on the frequency list. Frequency alone
     * answers "what is the commonest word starting with this?", which is not
     * the question being asked: with "you" typed, "yourself" is a worse guess
     * than "your" however common it is, because it is a longer bet on what has
     * not been typed yet. A few hundred places per character is enough to keep
     * the near misses in front without letting a rare short word win.
     */
    private static final int LENGTH_PENALTY = 500;

    /**
     * @return up to {@code limit} words starting with {@code prefix}, the
     *         likeliest first. A prefix that is itself a word leads, since the
     *         word already typed is the best reading of it.
     */
    List<String> completions(String prefix, int limit) {
        List<String> found = new ArrayList<>();
        if (prefix == null || prefix.length() == 0) {
            return found;
        }
        int from = Arrays.binarySearch(words, prefix);
        if (from < 0) {
            from = -from - 1;      // the insertion point is the first match
        }
        final int typed = prefix.length();
        final List<Integer> indices = new ArrayList<>();
        for (int i = from; i < words.length && words[i].startsWith(prefix); i++) {
            indices.add(i);
        }
        Collections.sort(indices, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return score(a, typed) - score(b, typed);
            }
        });
        for (int i = 0; i < indices.size() && found.size() < limit; i++) {
            found.add(words[indices.get(i)]);
        }
        return found;
    }

    /** Lower is a better guess; see {@link #LENGTH_PENALTY}. */
    private int score(int index, int typed) {
        int extra = words[index].length() - typed;
        if (extra == 0) {
            return -1;             // what has been typed is a word in its own right
        }
        return ranks[index] + extra * LENGTH_PENALTY;
    }
}
