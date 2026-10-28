"""Pleos Connect Virtual Console(웹, 에뮬레이터 포트 8088) 구조 파악용 프로브.
페이지 HTML·스크립트·네트워크(HTTP/WebSocket) 기록과 스크린샷을 out 폴더에 남깁니다."""
import json, sys, pathlib, re, urllib.request
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "demo/pleos/console")
OUT.mkdir(parents=True, exist_ok=True)
BASE = "http://127.0.0.1:8088/"
net = []

def save(name, data):
    (OUT / name).write_text(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False, indent=1))

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000})
    pg.on("request", lambda r: net.append({"t": "req", "m": r.method, "u": r.url, "body": (r.post_data or "")[:800]}))
    pg.on("response", lambda r: net.append({"t": "res", "s": r.status, "u": r.url}))
    def on_ws(ws):
        net.append({"t": "ws", "u": ws.url})
        ws.on("framesent", lambda f: net.append({"t": "ws>", "d": str(f)[:800]}))
        ws.on("framereceived", lambda f: net.append({"t": "ws<", "d": str(f)[:400]}))
    pg.on("websocket", on_ws)
    pg.goto(BASE, wait_until="networkidle", timeout=60000)
    pg.wait_for_timeout(4000)
    pg.screenshot(path=str(OUT / "c0_home.png"))
    save("page.html", pg.content())
    save("text.txt", pg.inner_text("body"))
    # 클릭 가능한 요소 목록
    items = pg.eval_on_selector_all(
        "button, [role=button], a, input, select, li, [class*=card], [class*=Card]",
        "els => els.slice(0, 600).map(e => ({tag: e.tagName, text: (e.innerText||e.value||'').trim().slice(0,60), cls: (e.className||'').toString().slice(0,80), id: e.id, aria: e.getAttribute('aria-label')}))")
    save("elements.json", items)
    # 스크립트 원본
    for i, src in enumerate(pg.eval_on_selector_all("script[src]", "els => els.map(e => e.src)")):
        try:
            js = urllib.request.urlopen(src, timeout=20).read().decode("utf-8", "ignore")
            save(f"script_{i}.js", js)
            hits = sorted(set(re.findall(r"""["'`](/[A-Za-z0-9_\-/{}$.]*(?:api|vhal|prop|ws|socket|set|emul)[A-Za-z0-9_\-/{}$.]*)["'`]""", js, re.I)))
            save(f"script_{i}_paths.txt", "\n".join(hits))
        except Exception as e:
            save(f"script_{i}.err", f"{src} {e}")
    # 기어 카드 찾아 D 눌러 보기 (네트워크 형태 확인용)
    try:
        for t in ["Motion", "모션"]:
            loc = pg.get_by_text(t, exact=True)
            if loc.count():
                loc.first.click(); pg.wait_for_timeout(1500); break
        pg.screenshot(path=str(OUT / "c1_motion.png"))
        save("text_motion.txt", pg.inner_text("body"))
        d = pg.get_by_role("button", name=re.compile(r"^D$"))
        if d.count():
            d.first.click(); pg.wait_for_timeout(2000)
        pg.screenshot(path=str(OUT / "c2_gear_d.png"))
    except Exception as e:
        save("click.err", str(e))
    save("network.json", net)
    b.close()
print("console probe done:", len(net), "network events")
