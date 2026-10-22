/**
 * 모심 알림 서버 (Cloudflare Workers)
 *  POST /api/event          차량 앱이 보낸 이상 신호 저장 + 카카오톡 전송
 *  GET  /api/events/:code   가족 코드의 알림 목록(JSON)
 *  GET  /f/:code            자녀용 알림 페이지(HTML)
 *  GET  /                   안내
 */
export interface Env {
  EVENTS: KVNamespace;
  KAKAO_REST_KEY?: string;
  KAKAO_REFRESH_TOKEN?: string;
  KAKAO_ACCESS_TOKEN?: string;
  DEVICE_TOKEN?: string;
}

type Ev = { type: string; title: string; body: string; ts: number };

const MAX_EVENTS = 200;
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8" } });

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const p = url.pathname;

    if (req.method === "POST" && p === "/api/event") return postEvent(req, env);
    if (req.method === "GET" && p.startsWith("/api/events/")) return json(await load(env, code(p.slice(12))));
    if (req.method === "GET" && p.startsWith("/f/")) return page(env, code(p.slice(3)));
    if (req.method === "GET" && p === "/") return new Response("mosim alert server", { status: 200 });
    return new Response("not found", { status: 404 });
  },
};

function code(s: string): string {
  return s.replace(/[^A-Z0-9]/gi, "").toUpperCase().slice(0, 8);
}

async function load(env: Env, c: string): Promise<Ev[]> {
  if (!c) return [];
  return (await env.EVENTS.get<Ev[]>(`ev:${c}`, "json")) ?? [];
}

async function postEvent(req: Request, env: Env): Promise<Response> {
  if (env.DEVICE_TOKEN && req.headers.get("x-device-token") !== env.DEVICE_TOKEN) return json({ error: "unauthorized" }, 401);
  let b: any;
  try { b = await req.json(); } catch { return json({ error: "bad json" }, 400); }
  const c = code(String(b.code ?? ""));
  if (!c || !b.title) return json({ error: "code/title required" }, 400);
  const ev: Ev = { type: String(b.type ?? "info"), title: String(b.title).slice(0, 80), body: String(b.body ?? "").slice(0, 300), ts: Number(b.ts) || Date.now() };

  const list = await load(env, c);
  list.unshift(ev);
  await env.EVENTS.put(`ev:${c}`, JSON.stringify(list.slice(0, MAX_EVENTS)));

  let kakao = "skipped";
  try { kakao = await sendKakao(env, ev, c); } catch (e: any) { kakao = `error: ${e?.message ?? e}`; }
  return json({ ok: true, kakao });
}

/** 카카오톡 '나에게 보내기'. 리프레시 토큰으로 액세스 토큰을 갱신해 KV에 캐시합니다. */
async function sendKakao(env: Env, ev: Ev, c: string): Promise<string> {
  if (!env.KAKAO_REST_KEY || (!env.KAKAO_REFRESH_TOKEN && !env.KAKAO_ACCESS_TOKEN)) return "not configured";
  let token = (await env.EVENTS.get("kakao:access")) ?? env.KAKAO_ACCESS_TOKEN ?? "";

  const attempt = async (t: string) => {
    const body = new URLSearchParams({
      template_object: JSON.stringify({
        object_type: "text",
        text: `[모심] ${ev.title}\n${ev.body}`,
        link: { web_url: `https://mosim-alert.workers.dev/f/${c}`, mobile_web_url: `https://mosim-alert.workers.dev/f/${c}` },
        button_title: "자세히 보기",
      }),
    });
    return fetch("https://kapi.kakao.com/v2/api/talk/memo/default/send", {
      method: "POST",
      headers: { Authorization: `Bearer ${t}`, "content-type": "application/x-www-form-urlencoded;charset=utf-8" },
      body,
    });
  };

  let r = token ? await attempt(token) : null;
  if (!r || r.status === 401) {
    if (!env.KAKAO_REFRESH_TOKEN) return "token expired";
    const rr = await fetch("https://kauth.kakao.com/oauth/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded;charset=utf-8" },
      body: new URLSearchParams({ grant_type: "refresh_token", client_id: env.KAKAO_REST_KEY, refresh_token: env.KAKAO_REFRESH_TOKEN }),
    });
    const j: any = await rr.json();
    if (!j.access_token) return `refresh failed: ${JSON.stringify(j).slice(0, 120)}`;
    token = j.access_token;
    await env.EVENTS.put("kakao:access", token, { expirationTtl: Math.max(60, (j.expires_in ?? 21600) - 60) });
    r = await attempt(token);
  }
  return r.ok ? "sent" : `kakao ${r.status}`;
}

const ICON: Record<string, string> = {
  night_departure: "🌙", long_distance: "🛣️", long_stop: "⏸️", hard_brake_spike: "⚠️", vehicle_care: "🔧",
};

function fmt(ts: number): string {
  const d = new Date(ts + 9 * 3600 * 1000);
  return `${d.getUTCMonth() + 1}/${d.getUTCDate()} ${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
}

function esc(s: string): string {
  return s.replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch] as string));
}

async function page(env: Env, c: string): Promise<Response> {
  const list = await load(env, c);
  const items = list.length
    ? list.map((e) => `
      <li class="ev ${esc(e.type)}">
        <div class="ic">${ICON[e.type] ?? "•"}</div>
        <div class="tx"><div class="t">${esc(e.title)}</div><div class="b">${esc(e.body)}</div><div class="d">${fmt(e.ts)}</div></div>
      </li>`).join("")
    : `<li class="empty">아직 전달된 신호가 없어요. 조용한 한 주입니다.</li>`;

  const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>모심 · 가족 알림</title>
<style>
:root{--bg:#0F1115;--card:#1A1E26;--line:#2A3040;--t:#F3F4F6;--m:#9AA3B2;--a:#F2B544}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font-family:-apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif}
.wrap{max-width:560px;margin:0 auto;padding:20px 16px 40px}
h1{font-size:14px;letter-spacing:.1em;color:var(--a);margin:0 0 4px}
h2{font-size:26px;margin:0 0 4px}.sub{color:var(--m);font-size:14px;margin-bottom:18px}
.card{background:var(--card);border-radius:16px;padding:16px}
ul{list-style:none;margin:0;padding:0}.ev{display:flex;gap:12px;padding:14px 0;border-top:1px solid var(--line)}.ev:first-child{border-top:0;padding-top:0}
.ic{font-size:26px;width:36px;text-align:center}.t{font-weight:700;font-size:17px}.b{color:var(--t);opacity:.85;font-size:15px;margin-top:3px;line-height:1.45}.d{color:var(--m);font-size:12px;margin-top:6px}
.empty{color:var(--m);padding:14px 0}
.cta{display:flex;gap:10px;margin-top:16px}.cta a{flex:1;text-align:center;padding:14px;border-radius:12px;text-decoration:none;font-weight:700}
.call{background:var(--a);color:#0F1115}.fix{background:transparent;border:1px solid var(--line);color:var(--t)}
.note{color:var(--m);font-size:12px;margin-top:18px;line-height:1.5}
</style></head><body><div class="wrap">
<h1>모심</h1><h2>부모님 차에서 온 소식</h2><div class="sub">가족 코드 ${esc(c)} · 부모님이 켜 둔 항목만 보입니다</div>
<div class="card"><ul>${items}</ul>
<div class="cta"><a class="call" href="tel:">📞 전화하기</a><a class="fix" href="https://www.hyundai.com/kr/ko/service/bluehands" target="_blank">정비 예약</a></div></div>
<div class="note">실시간 위치·운전 점수는 부모님이 공유를 켜지 않으면 표시되지 않습니다. 이 페이지는 부모님이 언제든 끌 수 있습니다.</div>
</div></body></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}
