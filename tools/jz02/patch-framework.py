#!/usr/bin/env python3
"""Rebuild the JZ02 framework patches from the ORIGINAL jars.

Usage:
    python3 patch-framework.py <dir-with-original-jars> <output-dir>

Inputs  (original jars from devcmp-rollback.tar.gz, see README v12/v13):
    services.jar, telephony-common.jar     (the vendor/original copies)
Outputs:
    patched_services.jar, patched_telephony-common.jar

Two structure-preserving, byte-minimal patches (see README v12):

A) telephony-common.jar / PhoneFactory.makeDefaultPhone():
   the vendor replaced AOSP's networkMode value with 11 (NT_MODE_LTE_ONLY)
   on BOTH branches. Change both `const/16 11` immediates to 9
   (NT_MODE_LTE_GSM_WCDMA), matching ro.telephony.default_network=9.
   Vendor logging and branching are kept intact.

B) services.jar: equal-length rename of the 7 vendor-private broadcast
   action strings registered by LightsService, so externally sent actions
   no longer match. dex string_ids must stay sorted -> only the last
   character is changed to 'z' (verified to keep order).

Only bytes inside classes.dex change; the dex header checksum (adler32 over
[12:]) and SHA-1 signature (over [32:]) are recomputed. Jars are rebuilt
keeping all original zip entries/metadata.

Deploy: push patched jars to /system/framework/, remove the matching .odex
files and /data/dalvik-cache entries, then reboot. Keep the originals for
rollback.
"""
import hashlib
import os
import struct
import sys
import zipfile
import zlib

VENDOR_ACTIONS = [
    'android.intent.action.apnrestore',
    'android.intent.action.usbshare',
    'android.led.power.off',
    'android.led.sim.switch',
    'android.led.sw.reset',
    'android.lte.get.cellinfo',
    'com.youdo.restore',
]

W1 = set([0x00, 0x01, 0x04, 0x07, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
          0x1d, 0x1e, 0x21, 0x27, 0x28] + list(range(0x3e, 0x44)) + list(range(0x7b, 0x90))
         + list(range(0xb0, 0xd0)))
W2 = set([0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23,
          0x29] + list(range(0x2d, 0x3e)) + list(range(0x44, 0x60)) + list(range(0x60, 0x6e))
         + list(range(0x90, 0xb0)) + list(range(0xd0, 0xe3)))
W3 = set([0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c]
         + list(range(0x6e, 0x73)) + list(range(0x74, 0x79)))
W5 = {0x18}


def width(op):
    return 5 if op in W5 else 3 if op in W3 else 2 if op in W2 else 1


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7f) << s
        if not x & 0x80:
            return r, o
        s += 7


class Dex:
    def __init__(self, data):
        self.b = bytearray(data)
        b = self.b
        self.u32 = lambda o: struct.unpack_from('<I', b, o)[0]
        self.u16 = lambda o: struct.unpack_from('<H', b, o)[0]
        self.sn, self.so = self.u32(56), self.u32(60)
        self.tn, self.to = self.u32(64), self.u32(68)
        self.mn, self.mo = self.u32(88), self.u32(92)
        self.cn, self.co = self.u32(96), self.u32(100)
        self._strings()

    def _string_at(self, off):
        b = self.b
        ln, o = uleb(b, off)
        e = b.find(b'\0', o)
        return bytes(b[o:e]), o, ln

    def _strings(self):
        self.stroff = [self.u32(self.so + 4 * i) for i in range(self.sn)]
        self.strings = [self._string_at(o)[0] for o in self.stroff]

    def methods_of(self, class_desc, method_name):
        b, u32 = self.b, self.u32
        out = []
        for ci in range(self.cn):
            o = self.co + 32 * ci
            if self.strings[u32(self.to + 4 * u32(o))] != class_desc:
                continue
            cd = u32(o + 24)
            if not cd:
                continue
            q = cd
            sf, q = uleb(b, q)
            inf, q = uleb(b, q)
            dm, q = uleb(b, q)
            vm, q = uleb(b, q)
            for _ in range(sf + inf):
                _, q = uleb(b, q)
                _, q = uleb(b, q)
            for n in (dm, vm):
                idx = 0
                for _ in range(n):
                    d, q = uleb(b, q)
                    _, q = uleb(b, q)
                    coff, q = uleb(b, q)
                    idx += d
                    if self.strings[u32(self.mo + 8 * idx + 4)] == method_name:
                        out.append((idx, coff))
        return out

    def const16_in(self, code_off, value):
        b = self.b
        sz = struct.unpack_from('<I', b, code_off + 12)[0]
        base, end = code_off + 16, min(code_off + 16 + sz * 2, len(b))
        hits, off = [], 0
        while base + off * 2 + 2 <= end:
            p = base + off * 2
            op = b[p] & 0xff
            if op == 0x13 and struct.unpack_from('<h', b, p + 2)[0] == value:
                hits.append((off, p + 2))
            off += width(op)
        return hits

    def fix_header(self):
        b = self.b
        b[12:32] = hashlib.sha1(bytes(b[32:])).digest()
        b[8:12] = struct.pack('<I', zlib.adler32(bytes(b[12:])) & 0xffffffff)

    def verify_header(self):
        b = self.b
        return (struct.unpack_from('<I', b, 8)[0] == (zlib.adler32(bytes(b[12:])) & 0xffffffff)
                and bytes(b[12:32]) == hashlib.sha1(bytes(b[32:])).digest())


def read_dex(path):
    with zipfile.ZipFile(path) as z:
        return z.read('classes.dex')


def write_jar(orig, out, new_dex):
    with zipfile.ZipFile(orig) as zin, zipfile.ZipFile(out, 'w') as zout:
        for info in zin.infolist():
            data = new_dex if info.filename == 'classes.dex' else zin.read(info.filename)
            ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            ni.compress_type = info.compress_type
            ni.external_attr = info.external_attr
            ni.internal_attr = info.internal_attr
            ni.create_system = info.create_system
            zout.writestr(ni, data)
    with zipfile.ZipFile(out) as z:
        assert z.testzip() is None
        assert z.read('classes.dex') == new_dex


def patch_telephony_common(src, dst, orig_dex):
    d = Dex(orig_dex)
    ms = d.methods_of(b'Lcom/android/internal/telephony/PhoneFactory;', b'makeDefaultPhone')
    assert len(ms) == 1, 'expected one makeDefaultPhone'
    hits = d.const16_in(ms[0][1], 11)
    assert len(hits) == 2, 'expected 2 LTE_ONLY constants, got %d (ROM changed?)' % len(hits)
    for _, p in hits:
        struct.pack_into('<h', d.b, p, 9)
    d.fix_header()
    assert d.verify_header()
    dex2 = bytes(d.b)
    assert len(Dex(dex2).const16_in(ms[0][1], 9)) == 2
    write_jar(src, dst, dex2)
    print('  telephony-common.jar: 2x const/16 11 -> 9 at byte offsets %s' % [p for _, p in hits])
    return dex2


def patch_services(src, dst, orig_dex):
    d = Dex(orig_dex)
    for target in VENDOR_ACTIONS:
        tb = target.encode()
        idxs = [i for i, s in enumerate(d.strings) if s == tb]
        assert len(idxs) == 1, '%r found %d times' % (target, len(idxs))
        i = idxs[0]
        _, data_off, ln = d._string_at(d.stroff[i])
        prev = d.strings[i - 1] if i else b''
        nxt = d.strings[i + 1] if i + 1 < len(d.strings) else b'\xff' * 200
        chosen = None
        for c in b'z~9876543210_':
            cand = tb[:-1] + bytes([c])
            if cand != tb and prev < cand < nxt:
                chosen = cand
                break
        assert chosen, 'no order-preserving replacement for %r' % target
        d.b[data_off:data_off + ln] = chosen
        d.strings[i] = chosen
        print('  %-36s -> %s' % (target, chosen.decode()))
    assert all(d.strings[i] < d.strings[i + 1] for i in range(len(d.strings) - 1)), \
        'dex string_ids order broken'
    d.fix_header()
    assert d.verify_header()
    dex2 = bytes(d.b)
    write_jar(src, dst, dex2)
    return dex2


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    indir, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)
    jobs = [
        ('telephony-common.jar', 'patched_telephony-common.jar', patch_telephony_common),
        ('services.jar', 'patched_services.jar', patch_services),
    ]
    for name, outname, fn in jobs:
        src = os.path.join(indir, name)
        dst = os.path.join(outdir, outname)
        assert os.path.isfile(src), 'missing original: ' + src
        orig = read_dex(src)
        print('== %s (classes.dex %d bytes)' % (name, len(orig)))
        new = fn(src, dst, orig)
        diff = sum(1 for a, b in zip(orig, new) if a != b)
        print('   %s: %d bytes changed, SHA1=%s' %
              (outname, diff, hashlib.sha1(new).hexdigest()))
    print('\nDeploy: copy patched jars over /system/framework/*.jar, remove the two .odex')
    print('files and matching /data/dalvik-cache entries, then reboot. Keep originals!')
    return 0


if __name__ == '__main__':
    sys.exit(main())
