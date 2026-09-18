# Khmer Unicode Layout for External Keyboard

A physical-keyboard layout (NiDA) for Android. The system reads
`app/src/main/res/raw/keyboard_layout_khmer.kcm` and the layout appears under
**Settings → System → Languages & input → Physical keyboard**.

This repository was reconstructed from the shipped APK
(`net.socheat.apps.khmerunicodelayoutforexternalkeyboard`), which contained no
source.

## What was fixed

Two keys did not match the NiDA layout:

| Key | Was | NiDA wants |
|-----|-----|------------|
| `Shift+A` | `+` (PLUS SIGN) | `ាំ` — U+17B6 U+17C6 |
| `Shift+,` | `;` (SEMICOLON) | `ុំ` — U+17BB U+17C6 |

Both targets are **two code points**, and that is the catch described below.
The ASCII characters that occupied those slots were not dropped, just moved:

* `+` → `Shift+RightAlt+A`
* `;` → `RightAlt+,` (which previously just repeated `,`, already on the base layer)

Every other key in the layout was audited: all 49 key blocks parse, and every
code point is assigned in Unicode. Nothing else changed.

## The catch: a .kcm key cannot type two code points

An Android key character map behavior holds exactly one UTF-16 code unit. In
AOSP's `frameworks/native/libs/input/KeyCharacterMap.cpp`:

* `Parser::parseCharacterLiteral` reads **one** character (or one `\uXXXX`
  escape) and then requires the closing quote;
* `Parser::parseKeyProperty` rejects a second literal outright —
  *"Cannot combine multiple character literals"*.

The public API agrees: `KeyCharacterMap.get(int keyCode, int metaState)`
returns a single `int` code point.

So `shift: 'ាំ'` is not "unsupported but harmless" — it is a **parse
error**, and one bad key block makes Android discard the entire file and fall
back to `Generic.kcm`. The keyboard silently becomes a US Latin keyboard with
no error message anywhere. Unicode has no precomposed character for either
sequence, so there is no single code point to use instead.

### How this repo handles it

**In the layout file** (works with no extra setup): the shift layer carries the
*first* code point of each sequence, matching what NiDA starts the cluster
with. Press `Shift+M` (U+17C6 NIKAHIT) to complete it:

* `Shift+A`, `Shift+M` → `ាំ`
* `Shift+,`, `Shift+M` → `ុំ`

**Optionally, in one keystroke**: enable *Khmer Unicode sequence keys* under
**Settings → System → Languages & input → On-screen keyboard**. That service
(`KhmerSequenceInputMethodService`) intercepts only those two combinations and
commits the full sequence; every other key falls straight through to the `.kcm`
above, which stays the single source of truth for the rest of the keyboard.

It draws no on-screen keyboard, so select it only on a device that has a
physical keyboard attached — otherwise you lose the soft keyboard. If you never
enable it, the app behaves exactly as it did before, plus the layout fixes.

## Verifying the layout

`tools/validate_kcm.py` ports the platform parser's accept/reject rules, so a
layout that Android would silently discard fails the build instead:

```
python3 tools/validate_kcm.py app/src/main/res/raw/keyboard_layout_khmer.kcm
python3 tools/validate_kcm.py app/src/main/res/raw/keyboard_layout_khmer.kcm --dump
```

`--dump` prints every mapping with its code point and Unicode name, which is the
quickest way to eyeball the layout. The check also runs automatically before
every build via the `validateKcm` Gradle task.

## Building

```
./gradlew assembleDebug
```

Requires the Android SDK (compileSdk 34, minSdk 21) and `python3` on `PATH` for
the layout check.
