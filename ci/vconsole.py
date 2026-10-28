"""Pleos Connect Virtual Console(에뮬레이터 8088, adb forward 필요) HTTP 조작 도우미.
사용: vconsole.py catalog OUT_DIR | vconsole.py find REGEX | vconsole.py set REGEX VALUE [AREA]"""
import json, re, sys, urllib.request, urllib.parse
BASE = "http://127.0.0.1:8088"

def get(path):
    return json.loads(urllib.request.urlopen(BASE + path, timeout=15).read().decode())

def put(path):
    req = urllib.request.Request(BASE + path, method="PUT")
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.status

def features():
    cat = get("/vehicle/catalog")
    out = []
    def walk(o):
        if isinstance(o, dict):
            if "write_endpoint" in o and "control" in o:
                out.append(o)
            for v in o.values(): walk(v)
        elif isinstance(o, list):
            for v in o: walk(v)
    walk(cat)
    return cat, out

cmd = sys.argv[1]
if cmd == "catalog":
    d = sys.argv[2]
    cat, fs = features()
    open(f"{d}/vc_catalog.json", "w").write(json.dumps(cat, ensure_ascii=False, indent=1))
    open(f"{d}/vc_state.json", "w").write(json.dumps(get("/vehicle/state"), ensure_ascii=False, indent=1))
    print(len(fs), "writable features")
elif cmd == "find":
    _, fs = features()
    for f in fs:
        if re.search(sys.argv[2], json.dumps(f, ensure_ascii=False), re.I):
            print(json.dumps({k: f.get(k) for k in ("id", "name", "title", "write_endpoint", "control")}, ensure_ascii=False)[:600])
elif cmd == "set":
    _, fs = features()
    rx, value = sys.argv[2], sys.argv[3]
    area = sys.argv[4] if len(sys.argv) > 4 else None
    hit = [f for f in fs if re.search(rx, json.dumps({k: f.get(k) for k in ("id", "name", "title", "write_endpoint")}, ensure_ascii=False), re.I)]
    if not hit:
        print("no feature for", rx); sys.exit(1)
    f = hit[0]; c = f["control"]
    p = {k: str(v) for k, v in (c.get("params") or {}).items()}
    field = (c.get("field_by_area") or {}).get(str(area)) or c.get("field")
    p[field] = value
    if c.get("area_field") and area is not None:
        p[c["area_field"]] = area
    path = f["write_endpoint"] + "?" + urllib.parse.urlencode(p)
    print(path, "->", put(path))
