import io, re, sys
paths = sys.argv[1:]
pat = re.compile(r"\\u([0-9a-fA-F]{4})")
for p in paths:
    s = io.open(p, encoding="utf-8").read()
    if "\\u" not in s:
        print("skip " + p)
        continue
    def repl(m):
        ch = chr(int(m.group(1), 16))
        return ch if ord(ch) > 127 else m.group(0)
    out = pat.sub(repl, s)
    io.open(p, "w", encoding="utf-8", newline="\n").write(out)
    print("decoded " + p)
