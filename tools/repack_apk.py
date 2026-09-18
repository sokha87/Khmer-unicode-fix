"""Swap the Khmer layout into an existing APK, unsigned.

The .kcm is a plain resource stored verbatim in the APK's ZIP, so the layout
can be replaced without the Android SDK: no aapt2, no resource table rebuild.
Drops any existing signature (it no longer matches) and 4-byte aligns stored
entries so the platform can still mmap resources.arsc.

Usage:  python3 tools/repack_apk.py <input.apk> <output.apk> [layout.kcm]

The output is UNSIGNED. Sign it with tools/Sign.java before installing.
"""

import zipfile, sys, os

SRC = sys.argv[1]
OUT = sys.argv[2]
KCM = sys.argv[3] if len(sys.argv) > 3 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/res/raw/keyboard_layout_khmer.kcm")
TARGET = "res/raw/keyboard_layout_khmer.kcm"
SIG = ("META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA")

new_kcm = open(KCM, "rb").read()

zin = zipfile.ZipFile(SRC, "r")
replaced = 0
with zipfile.ZipFile(OUT, "w") as zout:
    for item in zin.infolist():
        if item.filename in SIG:
            continue
        data = new_kcm if item.filename == TARGET else zin.read(item.filename)
        if item.filename == TARGET:
            replaced += 1

        zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        zi.compress_type = item.compress_type
        zi.external_attr = item.external_attr
        zi.internal_attr = item.internal_attr
        zi.create_system = item.create_system

        # zipalign: stored entries must start on a 4-byte boundary so the
        # platform can mmap them (resources.arsc in particular).
        if item.compress_type == zipfile.ZIP_STORED:
            offset = zout.fp.tell()
            header = 30 + len(zi.filename.encode("utf-8"))
            pad = -(offset + header) % 4
            if pad:
                # Android alignment extra field: id 0xd935, then padding.
                if pad < 4:
                    pad += 4
                zi.extra = (0xD935).to_bytes(2, "little") + (pad - 4).to_bytes(2, "little") + b"\0" * (pad - 4)
        zout.writestr(zi, data)
zin.close()

assert replaced == 1, "expected to replace exactly one .kcm, replaced %d" % replaced
print("repacked -> %s (%d bytes), replaced %d entry" % (OUT, os.path.getsize(OUT), replaced))
