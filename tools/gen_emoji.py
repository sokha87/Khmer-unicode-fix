"""Build the emoji list shipped in app/src/main/assets.

    python3 tools/gen_emoji.py <emoji_ordering.json>

Source: googlefonts/emoji-metadata, emoji_15_0_ordering.json, Apache 2.0. See
NOTICE. It is the ordering Google's own keyboard uses, so the grid reads in the
order people are used to rather than in code point order.

Output is one group per pair of lines - the group name, then its emoji
separated by spaces - which is all the keyboard needs: it draws them in order
and has no use for names or shortcodes.

Only the base of each emoji is kept, not its alternates. The alternates are
mostly skin tone and gender variants, and five copies of every person in the
grid makes the grid worse, not fuller.
"""

import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), 'app/src/main/assets')


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    groups = json.load(io.open(sys.argv[1], encoding='utf-8'))
    lines = []
    total = 0
    for group in groups:
        emoji = [''.join(chr(cp) for cp in entry['base'])
                 for entry in group['emoji']]
        lines.append(group['group'])
        lines.append(' '.join(emoji))
        total += len(emoji)
        print('  %-24s %4d' % (group['group'], len(emoji)))
    path = os.path.join(ASSETS, 'emoji.txt')
    with io.open(path, 'w', encoding='utf-8') as handle:
        handle.write('\n'.join(lines) + '\n')
    print('\n  emoji.txt  %d emoji in %d groups, %.0f KB'
          % (total, len(groups), os.path.getsize(path) / 1024.0))


if __name__ == '__main__':
    main()
