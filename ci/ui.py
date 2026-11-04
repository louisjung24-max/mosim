"""uiautomator로 화면에서 글자를 찾아 그 위치를 누릅니다. 사용: ui.py tap "글자" | ui.py has "글자" """
import re, subprocess, sys

def dump():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/data/local/tmp/ui.xml"], capture_output=True)
    return subprocess.run(["adb", "shell", "cat", "/data/local/tmp/ui.xml"], capture_output=True, text=True).stdout

def find(text):
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump()):
        if m.group(1) == text:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None

cmd, text = sys.argv[1], sys.argv[2]
pos = find(text)
if cmd == "has":
    print("yes" if pos else "no"); sys.exit(0)
if not pos:
    print(f"NOT FOUND: {text}"); sys.exit(1)
subprocess.run(["adb", "shell", "input", "tap", str(pos[0]), str(pos[1])])
print(f"tap {text} @ {pos}")
