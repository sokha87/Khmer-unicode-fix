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
the layout check. This is the build that produces the full app, the optional
input method included.

### Patching the layout into an existing APK, without the SDK

The `.kcm` is a plain resource stored verbatim in the APK's ZIP, so the layout
alone can be swapped without aapt2 or a resource-table rebuild:

```
python3 tools/repack_apk.py OldApp.apk unsigned.apk      # swap layout, zipalign
javac -cp apksig.jar -d . tools/Sign.java                # com.android.tools.build:apksig
java -cp apksig.jar:. Sign keystore.p12 PASS ALIAS unsigned.apk signed.apk 21
```

`Sign` signs with both v1 and v2 and then verifies the result. On JDK 9+,
apksig 2.3.0 needs one patch to build (`PKCS7.encodeSignedData` takes a
`DerOutputStream` now) plus
`--add-exports java.base/sun.security.{pkcs,x509,util}=ALL-UNNAMED`.

This route only changes the layout. The input method needs a real SDK build,
because adding a component means editing the binary manifest and resource
table.

**Re-signing with a different key changes the app's identity**, so Android will
refuse to install it over an existing copy — uninstall the old one first. Use
the original signing key if you have it and want in-place updates.
