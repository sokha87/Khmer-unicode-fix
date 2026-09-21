package net.socheat.apps.khmerunicodelayoutforexternalkeyboard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What word tends to follow what, learned from this phone's own typing.
 *
 * <p>The shipped word lists can only complete the word being typed; they say
 * nothing about what comes after it. This fills that in from the one corpus
 * that actually matches the person writing: their own messages. Nothing is
 * sent anywhere - the counts live in the app's private storage and are read
 * back on the next keystroke.
 *
 * <p>It is deliberately a plain bigram count rather than anything cleverer. A
 * keyboard has to answer between one keystroke and the next, and the previous
 * word carries most of what a table this size can usefully know.
 *
 * <p>Learning from the person rather than from a corpus is what makes this
 * work for Khmer at all. Khmer is written without spaces, so a general corpus
 * cannot be split into words without a segmenter; here the word boundaries are
 * known exactly, because they are where the suggestions were picked.
 */
final class NextWords {

    /** Distinct preceding words remembered; the least recently used goes first. */
    private static final int MAX_CONTEXTS = 4000;

    /** Followers kept per preceding word; the rarest goes first. */
    private static final int MAX_FOLLOWERS = 12;

    /** Counts are halved when one gets this high, so old habits can be lost. */
    private static final int DECAY_AT = 250;

    private final LinkedHashMap<String, HashMap<String, Integer>> byContext =
            new LinkedHashMap<String, HashMap<String, Integer>>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, HashMap<String, Integer>> eldest) {
                    return size() > MAX_CONTEXTS;
                }
            };

    private boolean dirty;

    /** Records that {@code word} was written straight after {@code previous}. */
    void learn(String previous, String word) {
        if (previous == null || word == null
                || previous.length() == 0 || word.length() == 0) {
            return;
        }
        HashMap<String, Integer> followers = byContext.get(previous);
        if (followers == null) {
            followers = new HashMap<>();
            byContext.put(previous, followers);
        }
        Integer seen = followers.get(word);
        int count = (seen == null ? 0 : seen) + 1;
        followers.put(word, count);
        if (count >= DECAY_AT) {
            halve(followers);
        }
        if (followers.size() > MAX_FOLLOWERS) {
            dropRarest(followers, word);
        }
        dirty = true;
    }

    /** @return the words most often written after {@code previous}, commonest first. */
    List<String> after(String previous, int limit) {
        List<String> found = new ArrayList<>();
        if (previous == null) {
            return found;
        }
        HashMap<String, Integer> followers = byContext.get(previous);
        if (followers == null) {
            return found;
        }
        found.addAll(followers.keySet());
        final HashMap<String, Integer> counts = followers;
        Collections.sort(found, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int byCount = counts.get(b) - counts.get(a);
                return byCount != 0 ? byCount : a.compareTo(b);
            }
        });
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    /** How often {@code word} has followed {@code previous}; 0 if never. */
    int timesAfter(String previous, String word) {
        if (previous == null || word == null) {
            return 0;
        }
        HashMap<String, Integer> followers = byContext.get(previous);
        if (followers == null) {
            return 0;
        }
        Integer seen = followers.get(word);
        return seen == null ? 0 : seen;
    }

    int size() {
        return byContext.size();
    }

    boolean isDirty() {
        return dirty;
    }

    /** One "previous<TAB>word<TAB>count" per line; a missing file is not an error. */
    void load(File file) {
        if (!file.exists()) {
            return;
        }
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"), 16384);
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t");
                    if (parts.length != 3) {
                        continue;
                    }
                    HashMap<String, Integer> followers = byContext.get(parts[0]);
                    if (followers == null) {
                        followers = new HashMap<>();
                        byContext.put(parts[0], followers);
                    }
                    followers.put(parts[1], Integer.parseInt(parts[2]));
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            // A corrupt or half-written file costs the learned words, not typing.
            byContext.clear();
        }
        dirty = false;
    }

    void save(File file) {
        try {
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), "UTF-8"), 16384);
            try {
                for (Map.Entry<String, HashMap<String, Integer>> context
                        : byContext.entrySet()) {
                    for (Map.Entry<String, Integer> follower
                            : context.getValue().entrySet()) {
                        writer.write(context.getKey());
                        writer.write('\t');
                        writer.write(follower.getKey());
                        writer.write('\t');
                        writer.write(Integer.toString(follower.getValue()));
                        writer.write('\n');
                    }
                }
            } finally {
                writer.close();
            }
            dirty = false;
        } catch (IOException ignored) {
            // Nothing to do about it, and it must not interrupt typing.
        }
    }

    /** Forgets everything, for the "clear learned words" button. */
    void clear() {
        byContext.clear();
        dirty = true;
    }

    private static void halve(HashMap<String, Integer> followers) {
        for (Map.Entry<String, Integer> entry : followers.entrySet()) {
            entry.setValue(entry.getValue() / 2);
        }
    }

    /**
     * Makes room by forgetting the least-written follower.
     *
     * <p>{@code keep} is the word just learned, and it is spared. It is on its
     * first sighting, so it is always the rarest, and dropping it would mean a
     * word that has filled up could never take on anything new again. Sparing
     * it costs the next-rarest instead, so a newcomer gets one chance to be
     * written a second time and earn its place.
     */
    private static void dropRarest(HashMap<String, Integer> followers, String keep) {
        String rarest = null;
        int lowest = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : followers.entrySet()) {
            if (!entry.getKey().equals(keep) && entry.getValue() < lowest) {
                lowest = entry.getValue();
                rarest = entry.getKey();
            }
        }
        if (rarest != null) {
            followers.remove(rarest);
        }
    }
}
