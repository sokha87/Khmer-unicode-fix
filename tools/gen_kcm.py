"""Generate the Khmer NiDA .kcm from the parsed desktop layout table.

    python3 tools/gen_kcm.py

Reads tools/nida_layout.json (produced by tools/parse_kmn.py from the key
tables of SIL's NiDA-based Khmer Angkor keyboard) and writes
app/src/main/res/raw/keyboard_layout_khmer.kcm.

Five NiDA positions are two-code-point vowel sequences that a .kcm cannot
hold; they are written as their leading code point and the bundled input
method types them in full. See README.md.
"""

import json, os, unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
L = json.load(open(os.path.join(HERE, 'nida_layout.json'), encoding='utf-8'))

ORDER = [str(d) for d in range(1, 10)] + ['0'] + \
    ['Q','W','E','R','T','Y','U','I','O','P'] + \
    ['A','S','D','F','G','H','J','K','L'] + \
    ['Z','X','C','V','B','N','M'] + \
    ['GRAVE','MINUS','EQUALS','BACKSLASH','LEFT_BRACKET','RIGHT_BRACKET',
     'SEMICOLON','APOSTROPHE','COMMA','PERIOD','SLASH','SPACE']

LABEL = {
    'GRAVE': '`', 'MINUS': '-', 'EQUALS': '=', 'BACKSLASH': '\\',
    'LEFT_BRACKET': '[', 'RIGHT_BRACKET': ']', 'SEMICOLON': ';',
    'APOSTROPHE': "'", 'COMMA': ',', 'PERIOD': '.', 'SLASH': '/', 'SPACE': ' ',
}

SECTIONS = [
    ('Number row', [str(d) for d in range(1, 10)] + ['0']),
    ('QWERTY letters', ['Q','W','E','R','T','Y','U','I','O','P',
                        'A','S','D','F','G','H','J','K','L',
                        'Z','X','C','V','B','N','M']),
    ('Punctuation and other printing keys',
     ['GRAVE','MINUS','EQUALS','BACKSLASH','LEFT_BRACKET','RIGHT_BRACKET',
      'SEMICOLON','APOSTROPHE','COMMA','PERIOD','SLASH','SPACE']),
]

# Android dispatches these to the system when the app does not handle them.
EXTRA = {
    'SLASH': ['alt, meta:                          fallback SEARCH',
              'ctrl:                               fallback LANGUAGE_SWITCH'],
    'SPACE': ['alt, meta:                          fallback SEARCH',
              'ctrl:                               fallback LANGUAGE_SWITCH'],
}

def lit(ch):
    """A .kcm character literal: printable ASCII direct, everything else \\uXXXX."""
    if ch == '\\': return r"'\\'"
    if ch == "'": return r"'\''"
    if 32 <= ord(ch) <= 126: return "'%s'" % ch
    return "'\\u%04x'" % ord(ch)

def name(ch):
    try: return unicodedata.name(ch)
    except ValueError: return 'U+%04X' % ord(ch)

def label_of(k):
    return LABEL.get(k, k if len(k) == 1 else k)

out = []
out.append("""# Copyright (C) 2012 The Android Open Source Project
# Copyright (C) Socheat.net
#
#      http://app.socheat.net
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Khmer NiDA layout, matching the desktop (Windows KBDKNI / NiDA) keyboard.
#
# Generated from the key tables of SIL's NiDA-based Khmer Angkor keyboard;
# regenerate with tools/gen_kcm.py. Every base, shift, right-alt and
# shift+right-alt position of the desktop layout is reproduced here, except
# for the five keys noted below.
#
# THE FIVE TWO-CODE-POINT KEYS
#
# NiDA puts vowel *sequences* on five positions:
#
#     COMMA           ->  U+17BB U+17C6   (SARA U  + NIKAHIT)   OM
#     SHIFT+COMMA     ->  U+17BB U+17C7   (SARA U  + REAHMUK)   OH
#     SHIFT+A         ->  U+17B6 U+17C6   (SARA AA + NIKAHIT)   AM
#     SHIFT+V         ->  U+17C1 U+17C7   (SARA E  + REAHMUK)   EH
#     SHIFT+SEMICOLON ->  U+17C4 U+17C7   (SARA OO + REAHMUK)   OAH
#
# An Android key character map behavior is a single UTF-16 code unit:
# KeyCharacterMap.cpp's parseCharacterLiteral reads one character and then
# requires the closing quote, and parseKeyProperty rejects a second literal
# with "Cannot combine multiple character literals". A two-code-point literal
# is a parse ERROR, and one bad key block makes Android discard this whole
# file and silently fall back to Generic.kcm. Unicode has no precomposed form
# for any of the five.
#
# Those five positions therefore carry the FIRST code point of the sequence
# here; press SHIFT+M (U+17C6 NIKAHIT) or SHIFT+H (U+17C7 REAHMUK) to finish
# the cluster. Enable the bundled "Khmer Unicode sequence keys" input method
# to get all five as a single keystroke, exactly like the desktop layout.

type OVERLAY
""")

PARTIAL = {}
for k in ORDER:
    for mod, val in L.get(k, {}).items():
        if len(val) > 1:
            PARTIAL[(k, mod)] = val

for title, group in SECTIONS:
    out.append("#\n# %s\n#\n" % title)
    for k in group:
        d = L.get(k)
        if not d:
            continue
        lines = ["key %s {" % k]
        lines.append("    %-35s %s" % ("label:", lit(label_of(k))))
        for mod in ('base', 'shift', 'ralt', 'shift+ralt'):
            ch = d.get(mod)
            if ch is None:
                continue
            if len(ch) > 1:
                seq = ' '.join('U+%04X' % ord(c) for c in ch)
                lines.append("    # NiDA types %s (%s) here; a .kcm holds only the first"
                             % (ch, seq))
                lines.append("    # code point, so SHIFT+%s completes the cluster."
                             % ('M' if ch[-1] == 'ំ' else 'H'))
                ch = ch[0]
            lines.append("    %-35s %-12s # %s" % (mod + ':', lit(ch), name(ch)))
        for extra in EXTRA.get(k, []):
            lines.append("    " + extra)
        lines.append("}")
        out.append("\n".join(lines))
    out.append("")

out.append("""### Non-printing keys

key ESCAPE {
    base:                               fallback BACK
    alt, meta:                          fallback HOME
    ctrl:                               fallback MENU
}""")

open(os.path.join(ROOT, 'app/src/main/res/raw/keyboard_layout_khmer.kcm'), 'w',
     encoding='utf-8').write("\n".join(out) + "\n")
print("wrote layout: %d keys, %d two-code-point positions" % (len(ORDER), len(PARTIAL)))
for (k, m), v in sorted(PARTIAL.items()):
    print("   %-12s %-6s %s" % (k, m, ' '.join('U+%04X' % ord(c) for c in v)))
