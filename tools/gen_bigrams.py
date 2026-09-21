"""Build the English next-word seed shipped in app/src/main/assets.

    python3 tools/gen_bigrams.py <gutenberg-dir> [app/src/main/assets/dict_en.txt]

Source: the Project Gutenberg selections distributed with NLTK
(nltk_data/packages/corpora/gutenberg.zip), which are public domain. See NOTICE.

The keyboard learns what follows what from its own typing, but it knows nothing
on the day it is installed, and a count of pairs is worthless until the same
pair has been written twice. This seeds it with what follows what in English at
large, so the strip has something to say from the first sentence and the learned
counts can overrule it as they build up.

Output is "context<TAB>follower follower ..." per line, most likely first, sorted
by context so the app can binary-search it.

Both halves of every pair must be in the shipped word list. There is no point
suggesting a word the keyboard cannot otherwise type or complete, and it keeps
the corpus's own spelling oddities out.
"""

import collections
import glob
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, 'app/src/main/assets')

# Followers kept per context. The strip shows eight, but the learned counts and
# the most-written words fill the rest, and a long tail here is mostly noise.
MAX_FOLLOWERS = 5

# A pair seen once is a coincidence, and a context seen a handful of times
# cannot rank its followers meaningfully.
MIN_PAIR = 2
MIN_CONTEXT = 5

WORD = re.compile(r"[a-z]+'?[a-z]*")

# A full stop ends a sentence, and the word after it does not follow the word
# before it - the same rule the keyboard applies to its own learning.
SENTENCE = re.compile(r'[.!?;:\n]+')


def read_vocabulary(path):
    words = set()
    with io.open(path, encoding='utf-8') as handle:
        for line in handle:
            words.add(line.split('\t')[0])
    return words


def count_pairs(directory, vocabulary):
    counts = collections.defaultdict(collections.Counter)
    for path in sorted(glob.glob(os.path.join(directory, '*.txt'))):
        # The Gutenberg texts are Latin-1; a stray byte must not stop the build.
        text = io.open(path, encoding='latin-1').read().lower()
        for part in SENTENCE.split(text):
            words = WORD.findall(part)
            for first, second in zip(words, words[1:]):
                if first in vocabulary and second in vocabulary:
                    counts[first][second] += 1
    return counts


def write(counts, path):
    lines = []
    for context in sorted(counts):
        followers = counts[context]
        if sum(followers.values()) < MIN_CONTEXT:
            continue
        kept = [word for word, n in followers.most_common(MAX_FOLLOWERS)
                if n >= MIN_PAIR]
        if kept:
            lines.append(context + '\t' + ' '.join(kept))
    with io.open(path, 'w', encoding='utf-8') as handle:
        handle.write('\n'.join(lines) + '\n')
    return len(lines)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    vocabulary = read_vocabulary(
        sys.argv[2] if len(sys.argv) > 2 else os.path.join(ASSETS, 'dict_en.txt'))
    counts = count_pairs(sys.argv[1], vocabulary)
    path = os.path.join(ASSETS, 'bigrams_en.txt')
    kept = write(counts, path)
    print('  bigrams_en.txt  %6d contexts, %6.0f KB'
          % (kept, os.path.getsize(path) / 1024.0))


if __name__ == '__main__':
    main()
