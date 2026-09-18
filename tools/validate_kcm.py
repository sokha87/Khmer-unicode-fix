#!/usr/bin/env python3
"""Validate an Android .kcm key character map the way the platform does.

This is a faithful port of the accept/reject rules in AOSP
frameworks/native/libs/input/KeyCharacterMap.cpp (Parser::parseType,
parseKey, parseKeyProperty, parseCharacterLiteral, parseModifier).

The point is to catch, before shipping, the failure mode that silently
breaks a layout: if ANY key block fails to parse, Android rejects the whole
file and falls back to Generic.kcm, so the user just sees a Latin keyboard
with no error anywhere.

Usage:  python3 tools/validate_kcm.py <file.kcm> [--dump]
"""

import sys
import unicodedata

MODIFIERS = {
    "shift", "lshift", "rshift", "alt", "lalt", "ralt", "ctrl", "lctrl",
    "rctrl", "meta", "lmeta", "rmeta", "sym", "fn", "capslock", "numlock",
    "scrolllock",
}

VALID_TYPES = {"NUMERIC", "PREDICTIVE", "ALPHA", "FULL", "SPECIAL_FUNCTION", "OVERLAY"}

SIMPLE_ESCAPES = {"n": "\n", "t": "\t", "\\": "\\", "'": "'", '"': '"'}


class KcmError(Exception):
    pass


class Line:
    """A single logical line, consumed left to right like AOSP's Tokenizer."""

    def __init__(self, text, lineno):
        self.text = text
        self.lineno = lineno
        self.pos = 0

    def skip_ws(self):
        while self.pos < len(self.text) and self.text[self.pos] in " \t":
            self.pos += 1

    def eol(self):
        self.skip_ws()
        return self.pos >= len(self.text)

    def peek(self):
        return self.text[self.pos] if self.pos < len(self.text) else "\0"

    def next_char(self):
        ch = self.peek()
        if self.pos < len(self.text):
            self.pos += 1
        return ch

    def next_token(self, delims=" \t"):
        self.skip_ws()
        start = self.pos
        while self.pos < len(self.text) and self.text[self.pos] not in delims:
            self.pos += 1
        return self.text[start:self.pos]

    def fail(self, msg):
        raise KcmError("line %d: %s\n    %s" % (self.lineno, msg, self.text.strip()))


def strip_comment(text):
    """Drop a trailing '#' comment, but not a '#' inside a character literal."""
    out = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "#":
            break
        out.append(ch)
        if ch == "'":
            # Copy the rest of the literal verbatim, escapes included.
            i += 1
            while i < len(text):
                out.append(text[i])
                if text[i] == "\\" and i + 1 < len(text):
                    i += 1
                    out.append(text[i])
                elif text[i] == "'":
                    break
                i += 1
        i += 1
    return "".join(out)


def parse_character_literal(line):
    """Port of Parser::parseCharacterLiteral -- exactly ONE UTF-16 code unit."""
    if line.next_char() != "'":
        line.fail("expected opening quote for character literal")
    ch = line.next_char()
    if ch == "\\":
        esc = line.next_char()
        if esc in SIMPLE_ESCAPES:
            value = SIMPLE_ESCAPES[esc]
        elif esc == "u":
            digits = "".join(line.next_char() for _ in range(4))
            if len(digits) != 4 or any(d not in "0123456789abcdefABCDEF" for d in digits):
                line.fail("\\u escape needs exactly 4 hex digits, got %r" % digits)
            value = chr(int(digits, 16))
        else:
            line.fail("unsupported escape sequence '\\%s'" % esc)
    elif 32 <= ord(ch) <= 126 and ch != "'":
        value = ch
    else:
        line.fail(
            "a literal character must be printable ASCII (32..126); use a "
            "'\\uXXXX' escape for %r" % ch
        )
    closing = line.next_char()
    if closing != "'":
        line.fail(
            "expected closing quote after ONE character, found %r -- an Android "
            "key behavior cannot hold a multi-code-point sequence" % closing
        )
    if value == "\0":
        line.fail("'\\u0000' is not a valid behavior character")
    return value


def parse_modifier(token):
    """Port of Parser::parseModifier. Returns a normalised modifier set."""
    if token == "base":
        return frozenset()
    parts = token.split("+")
    seen = set()
    for part in parts:
        if part not in MODIFIERS:
            raise KcmError("unknown modifier %r in %r" % (part, token))
        if part in seen:
            raise KcmError("duplicate modifier %r in %r" % (part, token))
        seen.add(part)
    return frozenset(seen)


def validate(path, dump=False):
    keys = {}
    ktype = None
    state = "top"
    current = None
    errors = []

    with open(path, encoding="utf-8") as fh:
        raw_lines = fh.read().splitlines()

    for lineno, raw in enumerate(raw_lines, 1):
        stripped = strip_comment(raw).strip()
        if not stripped:
            continue
        line = Line(stripped, lineno)

        try:
            if state == "top":
                token = line.next_token()
                if token == "type":
                    ktype = line.next_token()
                    if ktype not in VALID_TYPES:
                        line.fail("invalid keyboard type %r" % ktype)
                elif token == "key":
                    name = line.next_token(" \t{")
                    if not name:
                        line.fail("'key' must be followed by a key code name")
                    if name in keys:
                        line.fail("key %s declared more than once" % name)
                    line.skip_ws()
                    if line.next_char() != "{":
                        line.fail("expected '{' after key %s" % name)
                    current = keys.setdefault(name, {})
                    state = "key"
                elif token in ("map", "#"):
                    continue
                else:
                    line.fail("expected 'type' or 'key', got %r" % token)

            elif state == "key":
                token = line.next_token(" \t,:")
                if token == "}":
                    state, current = "top", None
                    continue

                props = []
                while True:
                    if token in ("label", "number"):
                        props.append(token)
                    else:
                        props.append(parse_modifier(token))
                    line.skip_ws()
                    if line.eol():
                        line.fail("expected ',' or ':' after property name")
                    ch = line.next_char()
                    if ch == ":":
                        break
                    if ch == ",":
                        token = line.next_token(" \t,:")
                        continue
                    line.fail("expected ',' or ':' after property name, got %r" % ch)

                # Behaviors (port of the do/while in parseKeyProperty).
                line.skip_ws()
                have_char = have_fallback = have_replace = False
                value = None
                while True:
                    if line.peek() == "'":
                        if have_char:
                            line.fail("cannot combine multiple character literals or 'none'")
                        if have_replace:
                            line.fail("cannot combine a character literal with 'replace'")
                        value = parse_character_literal(line)
                        have_char = True
                    else:
                        token = line.next_token()
                        if token == "none":
                            if have_char:
                                line.fail("cannot combine multiple character literals or 'none'")
                            have_char, value = True, None
                        elif token == "fallback":
                            if have_fallback or have_replace:
                                line.fail("cannot combine multiple fallback/replace key codes")
                            if not line.next_token():
                                line.fail("'fallback' needs a key code name")
                            have_fallback = True
                        elif token == "replace":
                            if have_char:
                                line.fail("cannot combine a character literal with 'replace'")
                            if have_fallback or have_replace:
                                line.fail("cannot combine multiple fallback/replace key codes")
                            if not line.next_token():
                                line.fail("'replace' needs a key code name")
                            have_replace = True
                        else:
                            line.fail("expected a character literal, 'none', 'fallback' or "
                                      "'replace', got %r" % token)
                    line.skip_ws()
                    if line.eol():
                        break

                for prop in props:
                    current[prop if isinstance(prop, str) else prop] = value

        except KcmError as exc:
            errors.append(str(exc))

    if state != "top":
        errors.append("unexpected end of file: a 'key' block was never closed with '}'")
    if ktype is None:
        errors.append("missing 'type' declaration")

    return keys, ktype, errors


def describe(ch):
    if ch is None:
        return "none"
    try:
        name = unicodedata.name(ch)
    except ValueError:
        name = "<unnamed>"
    return "U+%04X %s" % (ord(ch), name)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dump = "--dump" in sys.argv
    if len(args) != 1:
        print(__doc__)
        return 2

    path = args[0]
    keys, ktype, errors = validate(path, dump)

    if errors:
        print("FAIL  %s" % path)
        for err in errors:
            print("  * %s" % err)
        print("\n%d error(s). Android would reject this file and silently fall "
              "back to Generic.kcm." % len(errors))
        return 1

    print("OK    %s" % path)
    print("      type=%s, %d key blocks parsed" % (ktype, len(keys)))

    if dump:
        print()
        for name, props in keys.items():
            for prop, value in props.items():
                label = prop if isinstance(prop, str) else (
                    "base" if not prop else "+".join(sorted(prop)))
                print("  %-14s %-12s %s" % (name, label, describe(value)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
