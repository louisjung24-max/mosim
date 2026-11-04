"""호스트에서 실제 알림 서버의 자녀 페이지를 휴대폰 크기로 캡처합니다. 사용: family_shot.py URL OUT.png"""
import sys
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 390, "height": 844}, device_scale_factor=3)
    pg.goto(sys.argv[1], wait_until="load", timeout=30000)
    pg.wait_for_timeout(1500)
    pg.screenshot(path=sys.argv[2])
    b.close()
print("shot", sys.argv[2])
