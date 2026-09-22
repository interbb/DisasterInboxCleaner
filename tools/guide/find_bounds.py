#!/usr/bin/env python3
"""Print the bounds "l t r b" of the first matching node in a uiautomator dump.

usage: find_bounds.py DUMP.xml QUERY [QUERY...]     text or content-desc contains any QUERY
       find_bounds.py DUMP.xml --switch QUERY       a Switch whose text/content-desc contains QUERY
       find_bounds.py DUMP.xml --button QUERY       a Button whose text equals QUERY
       find_bounds.py DUMP.xml --regex PATTERN      a node whose text fully matches PATTERN
Prints NOTFOUND (exit 1) when nothing matches.
"""
import re, sys, xml.etree.ElementTree as ET

def bounds_of(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return " ".join(m.groups()) if m else None

def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr); sys.exit(2)
    root = ET.parse(sys.argv[1]).getroot()
    mode, args = "text", sys.argv[2:]
    if args[0] in ("--switch", "--button", "--regex"):
        mode, args = args[0][2:], args[1:]
    for node in root.iter("node"):
        text = node.get("text") or ""
        desc = node.get("content-desc") or ""
        cls = node.get("class") or ""
        hay = text + "|" + desc
        if mode == "text":
            ok = any(q in hay for q in args)
        elif mode == "switch":
            ok = "Switch" in cls and any(q in hay for q in args)
        elif mode == "button":
            ok = "Button" in cls and text.strip() in args
        else:
            ok = re.fullmatch(args[0], text.strip()) is not None
        if ok:
            b = bounds_of(node)
            if b:
                print(b); return
    print("NOTFOUND"); sys.exit(1)

if __name__ == "__main__":
    main()
