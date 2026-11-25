"""Writes graph/words.txt from the symbol table embedded in an OpenFst file header (works for any FST type)."""
import struct, sys

def main(fst_path, out_path):
    f = open(fst_path, "rb")
    def i32(): return struct.unpack("<i", f.read(4))[0]
    def i64(): return struct.unpack("<q", f.read(8))[0]
    def s(): n = i32(); return f.read(n).decode("utf-8")
    assert i32() == 2125659606, "not an OpenFst file"
    fst_type, arc_type, version, flags = s(), s(), i32(), i32()
    f.read(8); i64(); i64(); i64()                       # properties, start, numstates, numarcs
    tables = []
    for bit in (1, 2):                                  # input symbols, output symbols
        if flags & bit:
            assert i32() == 2125658996, "bad symbol table"
            name = s(); i64(); size = i64()
            tables.append([(s(), i64()) for _ in range(size)])
    print(fst_type, arc_type, "tables:", [len(t) for t in tables])
    words = tables[-1]
    with open(out_path, "w", encoding="utf-8") as o:
        for w, k in words:
            o.write(f"{w} {k}\n")

main(sys.argv[1], sys.argv[2])
