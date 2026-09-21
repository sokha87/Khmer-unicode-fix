"""Build the suggestion dictionaries shipped in app/src/main/assets.

    python3 tools/gen_dictionaries.py <seafreq.txt> <en_50k.txt>

Sources, both MIT licensed (see NOTICE):

  Khmer    silnrsi/khmerlbdict, src/seafreq.txt - a frequency wordlist from
           SEALang, "word<TAB>count", most frequent first.
  English  hermitdave/FrequencyWords, content/2018/en/en_50k.txt - frequencies
           from OpenSubtitles, "word count", most frequent first.

Output is one file per language, "word<TAB>rank", sorted by word so the app can
binary-search it, with rank 0 as the most frequent word. Sorting by word and
ranking by frequency keeps both lookups cheap: find the prefix by bisection,
then order the matches by rank.
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), 'app/src/main/assets')

KHMER_LIMIT = 20000
ENGLISH_LIMIT = 20000

# The long tail of a subtitle corpus is mostly noise, and a one-letter token is
# usually the wreckage of a contraction ("s", "t", "ll") rather than a word.
MIN_LENGTH = 2

# The two that really are words. Leaving them out cost more than the noise they
# keep company with: "i" and "a" are among the commonest words in English, so
# without them the keyboard cannot complete them, cannot capitalise "I" from the
# word list, and cannot learn what follows either of them.
SHORT_WORDS = ('a', 'i')


def read_ranked(path, limit, keep):
    """Frequency-ordered file to {word: rank}, most frequent first."""
    ranked = {}
    with open(path, encoding='utf-8') as handle:
        for line in handle:
            parts = line.split()
            if not parts:
                continue
            word = parts[0].strip()
            if (len(word) < MIN_LENGTH and word not in SHORT_WORDS) or not keep(word):
                continue
            if word not in ranked:
                ranked[word] = len(ranked)
            if len(ranked) >= limit:
                break
    return ranked


def is_khmer(word):
    return all('ក' <= ch <= '៿' for ch in word)


def is_latin_letters(word):
    return all('a' <= ch <= 'z' for ch in word)


def write(name, ranked):
    path = os.path.join(ASSETS, name)
    with open(path, 'w', encoding='utf-8') as handle:
        for word in sorted(ranked):
            handle.write('%s\t%d\n' % (word, ranked[word]))
    print('  %-14s %6d words, %6.0f KB' % (name, len(ranked),
                                           os.path.getsize(path) / 1024))


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    os.makedirs(ASSETS, exist_ok=True)
    write('dict_km.txt', read_ranked(sys.argv[1], KHMER_LIMIT, is_khmer))
    write('dict_en.txt', read_ranked(sys.argv[2], ENGLISH_LIMIT,
                                     lambda w: is_latin_letters(w.lower())))
    return 0


if __name__ == '__main__':
    sys.exit(main())
