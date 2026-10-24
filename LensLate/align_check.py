#!/usr/bin/env python3
"""Check 16 KB page alignment of PT_LOAD segments in ELF shared libraries.

Android 15+ (16 KB page devices) requires, for every PT_LOAD segment:
  (p_offset - p_vaddr) % 16384 == 0  and  p_align >= 16384
"""
import struct
import sys
import zipfile
import os
import tempfile

PAGE = 16384


def check_elf(path):
    """Return list of problem descriptions for an ELF file (empty = aligned)."""
    problems = []
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return ["not an ELF file"]
    ei_class = data[4]  # 1 = 32-bit, 2 = 64-bit
    ei_data = data[5]   # 1 = LE, 2 = BE
    endian = "<" if ei_data == 1 else ">"
    if ei_class == 2:
        # 64-bit
        (e_phoff,) = struct.unpack_from(endian + "Q", data, 32)
        e_phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        e_phnum = struct.unpack_from(endian + "H", data, 56)[0]
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type = struct.unpack_from(endian + "I", data, off)[0]
            if p_type != 1:  # PT_LOAD
                continue
            p_offset = struct.unpack_from(endian + "Q", data, off + 8)[0]
            p_vaddr = struct.unpack_from(endian + "Q", data, off + 16)[0]
            p_align = struct.unpack_from(endian + "Q", data, off + 48)[0]
            if (p_offset - p_vaddr) % PAGE != 0:
                problems.append(
                    f"  PT_LOAD: (p_offset-p_vaddr)={p_offset - p_vaddr} % 16384 != 0 "
                    f"(p_align={p_align})"
                )
            if p_align < PAGE:
                problems.append(f"  PT_LOAD: p_align={p_align} < 16384")
    elif ei_class == 1:
        # 32-bit
        (e_phoff,) = struct.unpack_from(endian + "I", data, 28)
        e_phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        e_phnum = struct.unpack_from(endian + "H", data, 44)[0]
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type = struct.unpack_from(endian + "I", data, off)[0]
            if p_type != 1:
                continue
            p_offset = struct.unpack_from(endian + "I", data, off + 8)[0]
            p_vaddr = struct.unpack_from(endian + "I", data, off + 12)[0]
            p_align = struct.unpack_from(endian + "I", data, off + 28)[0]
            if (p_offset - p_vaddr) % PAGE != 0:
                problems.append(
                    f"  PT_LOAD: (p_offset-p_vaddr)={p_offset - p_vaddr} % 16384 != 0 "
                    f"(p_align={p_align})"
                )
            if p_align < PAGE:
                problems.append(f"  PT_LOAD: p_align={p_align} < 16384")
    else:
        return [f"unknown ELF class {ei_class}"]
    return problems


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk"
    if not os.path.exists(apk):
        print(f"APK not found: {apk}")
        sys.exit(1)
    tmp = tempfile.mkdtemp(prefix="aligncheck_")
    total_aligned = 0
    total_unaligned = 0
    with zipfile.ZipFile(apk) as z:
        so_names = [n for n in z.namelist() if n.endswith(".so")]
        for name in sorted(so_names):
            dst = os.path.join(tmp, os.path.basename(name))
            with z.open(name) as src, open(dst, "wb") as out:
                out.write(src.read())
            probs = check_elf(dst)
            abi = name.split("/")[1] if "/" in name else "?"
            if probs:
                total_unaligned += 1
                print(f"UNALIGNED {name} ({abi}):")
                for p in probs:
                    print(p)
            else:
                total_aligned += 1
                print(f"aligned   {name} ({abi})")
    print(f"\n{total_aligned} aligned / {total_unaligned} unaligned")
    sys.exit(1 if total_unaligned else 0)


if __name__ == "__main__":
    main()
