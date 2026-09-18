import re, json, unicodedata

text = open('angkor.txt', encoding='utf-8').read()

# Join backslash continuations, then pull out store(...) definitions.
text = re.sub(r'\\\s*\r?\n', ' ', text)

def strip_comment(line):
    # 'c' as a standalone token starts a comment; quoted text is safe.
    out, i, n = [], 0, len(line)
    while i < n:
        ch = line[i]
        if ch in "'\"":
            q = ch; out.append(ch); i += 1
            while i < n and line[i] != q:
                out.append(line[i]); i += 1
            if i < n: out.append(line[i]); i += 1
            continue
        if ch == 'c' and (not out or out[-1].isspace()) and i + 1 < n and line[i+1].isspace():
            break
        out.append(ch); i += 1
    return ''.join(out)

stores = {}
for raw in text.splitlines():
    m = re.match(r'\s*store\(([&\w]+)\)\s*(.*)$', raw)
    if not m:
        continue
    name, body = m.group(1), strip_comment(m.group(2))
    stores[name] = body

def tokens_of(body, seen=()):
    """Return list of tokens: ('key', mods, code) | ('chr', text)."""
    result = []
    for tok in re.finditer(r"outs\((\w+)\)|\[([^\]]+)\]|U\+([0-9A-Fa-f]{4,6})|'([^']*)'|\"([^\"]*)\"", body):
        ref, key, cp, s1, s2 = tok.groups()
        if ref:
            if ref in seen or ref not in stores:
                continue
            result.extend(tokens_of(stores[ref], seen + (ref,)))
        elif key:
            parts = key.split()
            code = parts[-1]
            mods = frozenset(p.upper() for p in parts[:-1])
            result.append(('key', mods, code))
        elif cp:
            result.append(('chr', chr(int(cp, 16))))
        else:
            s = s1 if s1 is not None else s2
            for chpart in s:
                result.append(('chr', chpart))
    return result

KEYMAP = {
    'K_HYPHEN': 'MINUS', 'K_EQUAL': 'EQUALS', 'K_LBRKT': 'LEFT_BRACKET',
    'K_RBRKT': 'RIGHT_BRACKET', 'K_BKSLASH': 'BACKSLASH', 'K_COLON': 'SEMICOLON',
    'K_QUOTE': 'APOSTROPHE', 'K_COMMA': 'COMMA', 'K_PERIOD': 'PERIOD',
    'K_SLASH': 'SLASH', 'K_BKQUOTE': 'GRAVE', 'K_SPACE': 'SPACE',
}
def android_key(code):
    if code in KEYMAP:
        return KEYMAP[code]
    m = re.fullmatch(r'K_([A-Z0-9])', code)
    return m.group(1) if m else None

def modname(mods):
    mods = set(mods) - {'NCAPS'}
    if not mods: return 'base'
    if mods == {'SHIFT'}: return 'shift'
    if mods == {'RALT'}: return 'ralt'
    if mods == {'SHIFT', 'RALT'}: return 'shift+ralt'
    return None

PAIRS = [
    ('c_key', 'c_out'), ('v_gen_key', 'v_gen'), ('v_pseudo_key', 'v_pseudo'),
    ('ind_v_key', 'ind_v_out'), ('diacritic_key', 'diacritic_out'),
    ('c_shifter_key', 'c_shifter'), ('punct_key', 'punct_out'),
    ('latin_punct_key', 'latin_punct_out'), ('currency_key', 'currency_out'),
    ('digit_key', 'digit_out'), ('lek_attak_key', 'lek_attak_out'),
    ('lunar_date_key', 'lunar_date_out'), ('spaces_key', 'spaces_out'),
]

layout = {}
problems = []
for kname, oname in PAIRS:
    if kname not in stores or oname not in stores:
        problems.append('missing store pair %s/%s' % (kname, oname)); continue
    keys = [t for t in tokens_of(stores[kname]) if t[0] == 'key']
    outs = [t for t in tokens_of(stores[oname]) if t[0] == 'chr']
    if len(keys) != len(outs):
        problems.append('%s/%s length mismatch: %d keys vs %d outputs'
                        % (kname, oname, len(keys), len(outs)))
    for (_, mods, code), (_, ch) in zip(keys, outs):
        ak, mod = android_key(code), modname(mods)
        if ak and mod:
            layout.setdefault(ak, {})[mod] = ch

# The explicit two-code-point rules.
for m in re.finditer(r'^\s*\+\s*\[([^\]]+)\]\s*>\s*((?:U\+[0-9A-Fa-f]{4,6}\s*)+)$',
                     text, re.M):
    parts = m.group(1).split()
    ak, mod = android_key(parts[-1]), modname(frozenset(p.upper() for p in parts[:-1]))
    seq = ''.join(chr(int(c, 16)) for c in re.findall(r'U\+([0-9A-Fa-f]{4,6})', m.group(2)))
    if ak and mod:
        layout.setdefault(ak, {})[mod] = seq

json.dump(layout, open('nida_layout.json', 'w'), ensure_ascii=False, indent=1)
print('parsed keys: %d' % len(layout))
for p in problems: print('NOTE:', p)
multi = {(k, m): v for k, d in layout.items() for m, v in d.items() if len(v) > 1}
print('\nmulti-code-point keys (%d):' % len(multi))
for (k, m), v in sorted(multi.items()):
    print('  %-12s %-6s %s   %s' % (k, m, v, ' '.join('U+%04X' % ord(c) for c in v)))
