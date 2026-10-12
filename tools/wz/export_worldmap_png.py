#!/usr/bin/env python3
"""Export WorldMap BaseImg PNGs from a binary client Map.wz into wz-WorldMap/.

The server reads XML WZ (wz/Map.wz/WorldMap/*.img.xml), which carries no pixels, so the
dashboard's region images come from a real client file. Usage:

    python3 tools/wz/export_worldmap_png.py <client Map.wz> <out dir> [id ...]

With no ids it exports every WorldMap<id>.img. Strings are decrypted with the GMS key
when the file uses it (keystream from openssl AES-256-OFB), else with the zero key.
Only the BGRA4444 / BGRA8888 / RGB565 canvas formats are handled; that covers WorldMap.
"""
import mmap, os, struct, subprocess, sys, zlib

USER_KEY = bytes([0x13, 0, 0, 0, 0x08, 0, 0, 0, 0x06, 0, 0, 0, 0xB4, 0, 0, 0,
                  0x1B, 0, 0, 0, 0x0F, 0, 0, 0, 0x33, 0, 0, 0, 0x52, 0, 0, 0])
GMS_IV = bytes([0x4D, 0x23, 0xC7, 0x2B])
OFFSET_KEY = 0x581C3F6D


def keystream(iv, n):
    if iv == bytes(4):
        return bytes(n)
    out = subprocess.run(['openssl', 'enc', '-aes-256-ofb', '-nopad', '-K', USER_KEY.hex(),
                          '-iv', (iv * 4).hex()], input=bytes(n), capture_output=True, check=True)
    return out.stdout


class Wz:
    def __init__(self, path):
        self.f = open(path, 'rb')
        self.d = mmap.mmap(self.f.fileno(), 0, access=mmap.ACCESS_READ)
        assert self.d[:4] == b'PKG1', 'not a WZ file'
        self.start = struct.unpack_from('<I', self.d, 12)[0]
        enc_ver = struct.unpack_from('<H', self.d, self.start)[0]
        self.hash = None
        for v in range(1, 400):
            h = 0
            for c in str(v):
                h = (32 * h + ord(c) + 1) & 0xFFFFFFFF
            if ((h >> 24) & 0xFF) ^ ((h >> 16) & 0xFF) ^ ((h >> 8) & 0xFF) ^ (h & 0xFF) ^ 0xFF == enc_ver:
                self.hash, self.version = h, v
                break
        assert self.hash is not None, 'version not found'
        self.key = keystream(GMS_IV, 0x10000)
        if not self._names_look_ascii():
            self.key = bytes(0x10000)
        self.pos = 0

    # --- primitives ---
    def u8(self):
        v = self.d[self.pos]; self.pos += 1; return v

    def s8(self):
        v = struct.unpack_from('<b', self.d, self.pos)[0]; self.pos += 1; return v

    def fmt(self, f):
        v = struct.unpack_from(f, self.d, self.pos)[0]; self.pos += struct.calcsize(f); return v

    def cint(self):
        b = self.s8()
        return self.fmt('<i') if b == -128 else b

    def clong(self):
        b = self.s8()
        return self.fmt('<q') if b == -128 else b

    def string(self):
        n = self.s8()
        if n == 0:
            return ''
        if n > 0:
            if n == 127:
                n = self.fmt('<i')
            mask, out = 0xAAAA, []
            for i in range(n):
                c = struct.unpack_from('<H', self.d, self.pos + 2 * i)[0]
                k = self.key[2 * i] | (self.key[2 * i + 1] << 8)
                out.append(chr(c ^ mask ^ k)); mask = (mask + 1) & 0xFFFF
            self.pos += 2 * n
            return ''.join(out)
        n = self.fmt('<i') if n == -128 else -n
        mask, out = 0xAA, []
        for i in range(n):
            out.append(chr(self.d[self.pos + i] ^ mask ^ self.key[i])); mask = (mask + 1) & 0xFF
        self.pos += n
        return ''.join(out)

    def offset(self):
        off = ((self.pos - self.start) ^ 0xFFFFFFFF) & 0xFFFFFFFF
        off = (off * self.hash) & 0xFFFFFFFF
        off = (off - OFFSET_KEY) & 0xFFFFFFFF
        r = off & 0x1F
        off = ((off << r) | (off >> (32 - r))) & 0xFFFFFFFF if r else off
        off ^= self.fmt('<I')
        return (off + self.start * 2) & 0xFFFFFFFF

    # --- directory tree ---
    def directory(self, at):
        self.pos = at
        entries = []
        for _ in range(self.cint()):
            t = self.u8()
            if t == 1:
                self.pos += 10; continue
            if t == 2:
                ref = self.fmt('<i'); back = self.pos
                self.pos = self.start + ref; t = self.u8(); name = self.string(); self.pos = back
            else:
                name = self.string()
            self.cint(); self.cint()  # size, checksum
            entries.append((t, name, self.offset()))
        return entries

    def _names_look_ascii(self):
        try:
            names = [n for _, n, _ in self.directory(self.start + 2)]
        except Exception:
            return False
        return bool(names) and all(n.isascii() and n.isprintable() for n in names)

    def find(self, *path):
        entries = self.directory(self.start + 2)
        for i, part in enumerate(path):
            hit = next((e for e in entries if e[1] == part), None)
            if hit is None:
                return None
            if i == len(path) - 1:
                return hit
            entries = self.directory(hit[2])

    # --- image properties ---
    def str_block(self, base):
        t = self.u8()
        if t in (0x00, 0x73):
            return self.string()
        ref = self.fmt('<i'); back = self.pos
        self.pos = base + ref; s = self.string(); self.pos = back
        return s

    def props(self, base):
        out = {}
        for _ in range(self.cint()):
            name = self.str_block(base)
            t = self.u8()
            if t == 0:
                out[name] = None
            elif t in (2, 11):
                out[name] = self.fmt('<h')
            elif t in (3, 19):
                out[name] = self.cint()
            elif t == 20:
                out[name] = self.clong()
            elif t == 4:
                out[name] = self.fmt('<f') if self.s8() == -128 else 0.0
            elif t == 5:
                out[name] = self.fmt('<d')
            elif t == 8:
                out[name] = self.str_block(base)
            elif t == 9:
                size = self.fmt('<I'); end = self.pos + size
                out[name] = self.extended(base)
                self.pos = end
            else:
                raise ValueError('property type %d' % t)
        return out

    def extended(self, base):
        t = self.u8()
        if t in (0x01, 0x1B):
            ref = self.fmt('<i'); back = self.pos
            self.pos = base + ref; kind = self.string(); self.pos = back
        else:
            kind = self.string()
        if kind == 'Property':
            self.pos += 2
            return self.props(base)
        if kind == 'Canvas':
            self.pos += 1
            children = {}
            if self.u8() == 1:
                self.pos += 2
                children = self.props(base)
            w, h = self.cint(), self.cint()
            fmt = self.cint() + self.u8()
            self.pos += 4
            n = self.fmt('<i')
            return {'_canvas': (w, h, fmt, self.pos + 1, n - 1), **children}
        if kind == 'Shape2D#Vector2D':
            return (self.cint(), self.cint())
        return {'_kind': kind}

    def image(self, at):
        self.pos = at
        assert self.u8() == 0x73 and self.string() == 'Property'
        self.pos += 2
        return self.props(at)

    def pixels(self, canvas):
        w, h, fmt, at, n = canvas
        raw = self.d[at:at + n]
        if raw[:2] in (b'\x78\x9c', b'\x78\x01', b'\x78\xda', b'\x78\x5e'):
            data = zlib.decompressobj().decompress(raw)  # some clients omit the zlib trailer
        else:  # encrypted blocks: int32 length + xor keystream
            p, buf = 0, bytearray()
            while p < len(raw):
                ln = struct.unpack_from('<i', raw, p)[0]; p += 4
                buf += bytes(b ^ self.key[i] for i, b in enumerate(raw[p:p + ln])); p += ln
            data = zlib.decompressobj().decompress(bytes(buf))
        need = w * h * (4 if fmt == 2 else 2)
        if len(data) < need:
            raise ValueError('pixel data short: %d of %d bytes' % (len(data), need))
        rgba = bytearray(w * h * 4)
        if fmt == 1:  # BGRA4444
            for i in range(w * h):
                lo, hi = data[2 * i], data[2 * i + 1]
                b, g, r, a = lo & 0xF, lo >> 4, hi & 0xF, hi >> 4
                rgba[4 * i:4 * i + 4] = bytes((r * 17, g * 17, b * 17, a * 17))
        elif fmt == 2:  # BGRA8888
            for i in range(w * h):
                b, g, r, a = data[4 * i:4 * i + 4]
                rgba[4 * i:4 * i + 4] = bytes((r, g, b, a))
        elif fmt == 513:  # RGB565
            for i in range(w * h):
                v = data[2 * i] | (data[2 * i + 1] << 8)
                r, g, b = (v >> 11) & 0x1F, (v >> 5) & 0x3F, v & 0x1F
                rgba[4 * i:4 * i + 4] = bytes((r * 255 // 31, g * 255 // 63, b * 255 // 31, 255))
        else:
            raise ValueError('canvas format %d not handled' % fmt)
        return w, h, bytes(rgba)


def write_png(path, w, h, rgba):
    def chunk(tag, body):
        return struct.pack('>I', len(body)) + tag + body + struct.pack('>I', zlib.crc32(tag + body) & 0xFFFFFFFF)
    rows = b''.join(b'\x00' + rgba[y * w * 4:(y + 1) * w * 4] for y in range(h))
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b''))


def main():
    src, out, ids = sys.argv[1], sys.argv[2], sys.argv[3:]
    wz = Wz(src)
    print('WZ version %d, %s key' % (wz.version, 'zero' if wz.key[0] == 0 and wz.key[1] == 0 else 'GMS'))
    wm_dir = wz.find('WorldMap')
    assert wm_dir, 'no WorldMap directory'
    for t, name, at in wz.directory(wm_dir[2]):
        wid = name[len('WorldMap'):-len('.img')]
        if t != 4 or not name.startswith('WorldMap') or (ids and wid not in ids):
            continue
        canvas = wz.image(at).get('BaseImg', {}).get('0', {}).get('_canvas')
        if not canvas:
            print(name, 'has no BaseImg/0'); continue
        w, h, rgba = wz.pixels(canvas)
        write_png(os.path.join(out, wid + '.png'), w, h, rgba)
        print(name, '->', wid + '.png', w, 'x', h, 'format', canvas[2])


if __name__ == '__main__':
    main()
