# Khmer Unicode Layout for External Keyboard

The Khmer **NiDA** layout for a physical keyboard on Android, matching the
desktop (Windows KBDKNI / NiDA) keyboard. Android reads
`app/src/main/res/raw/keyboard_layout_khmer.kcm`; the layout is chosen under
**Settings → System → Languages & input → Physical keyboard**.

This repository was reconstructed from the shipped APK
(`net.socheat.apps.khmerunicodelayoutforexternalkeyboard`), which had no source.

## The layout

Every base, shift, right-alt and shift+right-alt position of the desktop NiDA
keyboard is reproduced: **181 of 186 positions exactly**, and the remaining five
as far as the format allows (below).

The table is not hand-written. `tools/parse_kmn.py` reads the key/output stores
of SIL's NiDA-based *Khmer Angkor* keyboard into `tools/nida_layout.json`, and
`tools/gen_kcm.py` generates the `.kcm` from it, so the layout can be
regenerated and re-checked rather than trusted:

```
python3 tools/parse_kmn.py     # refresh tools/nida_layout.json from the source keyboard
python3 tools/gen_kcm.py       # regenerate the .kcm (deterministic)
```

## The five keys a .kcm cannot type

NiDA puts two-code-point vowel *sequences* on five positions:

| Key | Types | Code points |
|-----|-------|-------------|
| `,` | `ុំ` | U+17BB U+17C6 |
| `Shift+,` | `ុះ` | U+17BB U+17C7 |
| `Shift+A` | `ាំ` | U+17B6 U+17C6 |
| `Shift+V` | `េះ` | U+17C1 U+17C7 |
| `Shift+;` | `ោះ` | U+17C4 U+17C7 |

An Android key character map behavior holds exactly one UTF-16 code unit. In
AOSP's `frameworks/native/libs/input/KeyCharacterMap.cpp`,
`Parser::parseCharacterLiteral` reads **one** character and then requires the
closing quote, and `Parser::parseKeyProperty` rejects a second literal with
*"Cannot combine multiple character literals"*. The public API agrees:
`KeyCharacterMap.get(int, int)` returns a single `int` code point.

So `shift: 'ាំ'` is not merely unsupported — it is a **parse error**,
and one bad key block makes Android discard the whole file and fall back to
`Generic.kcm`: the keyboard silently becomes US Latin with no error anywhere.
Unicode has no precomposed character for any of the five.

**In the layout file** those five positions carry the *first* code point of the
sequence, so `Shift+M` (U+17C6 NIKAHIT) or `Shift+H` (U+17C7 REAHMUK) completes
the cluster in a second keystroke.

**For the desktop's single keystroke**, enable *Khmer Unicode sequence keys*
under **Settings → System → Languages & input → On-screen keyboard**.
`KhmerSequenceInputMethodService` intercepts exactly those five combinations and
commits the full sequence; every other key falls straight through to the `.kcm`.

Each of its rules fires only when the key currently produces the sequence's
leading code point — which is true exactly when the Khmer layout is active. That
matters because one of the five is the *unshifted* comma: without the check,
switching the physical keyboard to a Latin layout would turn every comma into a
Khmer vowel. It draws no on-screen keyboard, so select it only on a device with
a physical keyboard attached.

## Switching between Khmer and English

This input method declares **two keyboard subtypes**, Khmer (NiDA) and English
(US), so `Ctrl` + `Space` toggles between them on its own — Gboard does not have
to be installed, enabled or selected. `Ctrl` + `Shift` + `Space` goes back.
Assign a physical layout to each subtype once, under **Settings → Physical
keyboard**: *Khmer Unicode* for the Khmer subtype, *English (US)* for the
English one.

The shortcut itself is already in both layouts: this `.kcm` maps
`ctrl: fallback LANGUAGE_SWITCH` on Space and `/`, AOSP's stock `Generic.kcm`
does the same on Space, and `PhoneWindowManager` also handles plain `Ctrl+Space`
before dispatch, so no app can swallow it.

Getting the subtypes right is fiddly, and `app/src/main/res/xml/method.xml`
carries the details. Two rules drive it:

* `HardwareKeyboardShortcutController` lists one entry per enabled subtype that
  passes `isSuitableForPhysicalKeyboardLayoutMapping()` and rotates by locating
  the *current* entry in that list. A single subtype desynchronises the two
  sides — the list holds `(ime, null)` while the current position is
  `(ime, subtype)` — so the shortcut works everywhere except on this input
  method, which can then be left but never re-entered.
* `SubtypeUtils.getImplicitlyApplicableSubtypesImpl()` keeps only subtypes whose
  language matches the system locale, so on a Khmer device the English subtype
  is dropped — unless no applicable subtype is ASCII-capable, in which case it
  also adds any keyboard subtype carrying the extra value
  `EnabledWhenDefaultIsNotAsciiCapable`. Hence the Khmer subtype is marked not
  ASCII-capable and the English one carries that tag. This is the mechanism
  AOSP's LatinIME uses for its own English fallback subtype.

**Known limitation:** that second rule is locale-dependent. On a device whose
system language is English, `filterByLanguage` matches the English subtype,
an ASCII-capable subtype is therefore present, and the Khmer subtype is not
implicitly enabled. The layout still works; only the automatic subtype rotation
is affected. Keeping the system language as Khmer avoids it.

Because the sequence rules check that a key already produces the sequence's
leading code point, they are inert on the English subtype's layout — an
ordinary comma stays a comma.

## Verifying the layout

`tools/validate_kcm.py` ports the platform parser's accept/reject rules, so a
layout Android would silently discard fails the build instead:

```
python3 tools/validate_kcm.py app/src/main/res/raw/keyboard_layout_khmer.kcm
python3 tools/validate_kcm.py app/src/main/res/raw/keyboard_layout_khmer.kcm --dump
```

`--dump` prints every mapping with its code point and Unicode name. The check
also runs before every build, from both build paths below.

## Building

With Android Studio / the Google SDK:

```
./gradlew assembleDebug
```

Without it — the full app, input method included, from Ubuntu packages only:

```
sudo apt-get install aapt apksigner zipalign dalvik-exchange \
                     android-framework-res android-sdk-platform-23
tools/build_apk.sh keystore.p12 STOREPASS ALIAS KhmerUnicodeLayout.apk
```

`aapt` packages the resources and binary manifest, `dalvik-exchange` (`dx`)
dexes the classes — so javac must emit Java 8 bytecode — and `zipalign` plus
`apksigner` produce a v1+v2+v3 signed APK.

**Reuse the same keystore every time.** Android installs an update over an
existing app only when the signing certificate matches; a different key means
uninstalling first.

## Patching only the layout into an existing APK

The `.kcm` is a plain resource stored verbatim in the APK's ZIP, so the layout
alone can be swapped with no build tools at all:

```
python3 tools/repack_apk.py OldApp.apk unsigned.apk     # swap layout, zipalign
```

then sign the result (`tools/Sign.java`, or `apksigner`). This route cannot add
the input method, so the five sequence keys stay two keystrokes.
