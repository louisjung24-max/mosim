/**
 * 모심 알림 서버 (Cloudflare Workers)
 *  POST /api/event    차량 앱이 보낸 신호 저장(부모님이 고르고 차 화면에서 먼저 확인한 것만 옴) + 카카오톡(선택)
 *  POST /api/unlink   가족 연결 해제: 이 가족 코드의 기록 삭제
 *  GET  /f/:code      자녀용 알림 페이지(HTML) — 조회 횟수 제한
 *  GET  /             안내
 * 비밀값(DEVICE_TOKEN, KAKAO_*)은 저장소에 두지 않고 `wrangler secret put`으로만 넣습니다.
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
const TYPES = new Set(["night_departure", "long_distance", "long_stop", "hard_brake_spike", "vehicle_care"]);
const CODE_RE = /^[A-Z2-9]{10}$/;
const PAGE_LIMIT_PER_MIN = 30;
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8" } });

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const p = url.pathname;

    if (req.method === "POST" && p === "/api/event") return postEvent(req, env, url.origin);
    if (req.method === "POST" && p === "/api/unlink") return unlink(req, env);
    if (req.method === "GET" && p.startsWith("/f/")) {
      if (!(await allowView(req, env))) return new Response("잠시 후 다시 시도해 주세요.", { status: 429 });
      return page(env, code(p.slice(3)));
    }
    if (req.method === "GET" && p === "/") return new Response("mosim alert server", { status: 200 });
    return new Response("not found", { status: 404 });
  },
};

function code(s: string): string {
  const c = s.replace(/[^A-Z0-9]/gi, "").toUpperCase();
  return CODE_RE.test(c) ? c : "";
}

async function load(env: Env, c: string): Promise<Ev[]> {
  if (!c) return [];
  return (await env.EVENTS.get<Ev[]>(`ev:${c}`, "json")) ?? [];
}

function authorized(req: Request, env: Env): boolean {
  return !env.DEVICE_TOKEN || req.headers.get("x-device-token") === env.DEVICE_TOKEN;
}

async function readJson(req: Request): Promise<any | null> {
  if (Number(req.headers.get("content-length") ?? 0) > 2048) return null;
  try { return await req.json(); } catch { return null; }
}

async function postEvent(req: Request, env: Env, origin: string): Promise<Response> {
  if (!authorized(req, env)) return json({ error: "unauthorized" }, 401);
  const b = await readJson(req);
  if (!b) return json({ error: "bad json" }, 400);
  const c = code(String(b.code ?? ""));
  const type = String(b.type ?? "");
  if (!c || !TYPES.has(type) || !b.title) return json({ error: "invalid event" }, 400);
  const now = Date.now();
  const ts = Number(b.ts);
  const ev: Ev = {
    type,
    title: String(b.title).slice(0, 80),
    body: String(b.body ?? "").slice(0, 300),
    ts: Number.isFinite(ts) && Math.abs(ts - now) < 86_400_000 ? ts : now,
  };

  const list = await load(env, c);
  // 같은 신호가 1분 안에 다시 오면(재전송) 한 번만 저장
  if (list[0] && list[0].type === ev.type && list[0].title === ev.title && Math.abs(list[0].ts - ev.ts) < 60_000) return json({ ok: true, duplicate: true });
  list.unshift(ev);
  await env.EVENTS.put(`ev:${c}`, JSON.stringify(list.slice(0, MAX_EVENTS)));

  let kakao = "skipped";
  try { kakao = await sendKakao(env, ev, c, origin); } catch (e: any) { kakao = `error: ${e?.message ?? e}`; }
  return json({ ok: true, kakao });
}

/** 가족 연결 해제: 차량에서 요청하면 이 코드의 기록을 지웁니다. 이후 이 주소는 "연결된 정보 없음"으로 보입니다. */
async function unlink(req: Request, env: Env): Promise<Response> {
  if (!authorized(req, env)) return json({ error: "unauthorized" }, 401);
  const b = await readJson(req);
  const c = code(String(b?.code ?? ""));
  if (!c) return json({ error: "invalid code" }, 400);
  await env.EVENTS.delete(`ev:${c}`);
  return json({ ok: true });
}

/** 자녀 페이지 조회 횟수 제한(접속 주소별 분당 30회): 가족 코드 무작위 대입을 늦춥니다. */
async function allowView(req: Request, env: Env): Promise<boolean> {
  const ip = req.headers.get("cf-connecting-ip") ?? "local";
  const key = `rl:${ip}:${Math.floor(Date.now() / 60_000)}`;
  const n = Number((await env.EVENTS.get(key)) ?? 0) + 1;
  await env.EVENTS.put(key, String(n), { expirationTtl: 120 });
  return n <= PAGE_LIMIT_PER_MIN;
}

/**
 * 카카오톡 '나에게 보내기'(선택, 기본 꺼짐). 설정된 카카오 계정 한 곳으로만 보내는 시연용 경로이며,
 * 가족별 카카오 알림은 사업자 알림톡 연동이 필요합니다(향후 과제). 리프레시 토큰으로 액세스 토큰을 갱신해 KV에 캐시합니다.
 */
async function sendKakao(env: Env, ev: Ev, c: string, origin: string): Promise<string> {
  if (!env.KAKAO_REST_KEY || (!env.KAKAO_REFRESH_TOKEN && !env.KAKAO_ACCESS_TOKEN)) return "not configured";
  let token = (await env.EVENTS.get("kakao:access")) ?? env.KAKAO_ACCESS_TOKEN ?? "";

  const attempt = async (t: string) => {
    const body = new URLSearchParams({
      template_object: JSON.stringify({
        object_type: "text",
        text: `[모심] ${ev.title}\n${ev.body}`,
        link: { web_url: `${origin}/f/${c}`, mobile_web_url: `${origin}/f/${c}` },
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
  night_departure: "🌙", long_distance: "🛣️", long_stop: "⏸️", hard_brake_spike: "⚠️", vehicle_care: "🔋",
};

function fmt(ts: number): string {
  const d = new Date(ts + 9 * 3600 * 1000);
  return `${d.getUTCMonth() + 1}/${d.getUTCDate()} ${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
}

function esc(s: string): string {
  return s.replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch] as string));
}

async function page(env: Env, c: string): Promise<Response> {
  const list = c ? await load(env, c) : [];
  const items = list.length
    ? list.map((e) => `
      <li class="ev ${esc(e.type)}">
        <div class="ic">${ICON[e.type] ?? "•"}</div>
        <div class="tx"><div class="t">${esc(e.title)}</div><div class="b">${esc(e.body)}</div><div class="d">${fmt(e.ts)}</div></div>
      </li>`).join("")
    : `<li class="empty">이 주소로 전달된 소식이 없습니다. 아직 보낸 소식이 없거나, 연결이 바뀌었을 수 있어요.</li>`;

  const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>모심 · 가족 알림</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link href="https://fonts.googleapis.com/css2?family=Asta+Sans:wght@300..800&display=swap" rel="stylesheet">
<style>
:root{--bg:#131417;--card:#1D1E21;--inner:#313236;--line:#313236;--t:#F4F5F7;--s:#C2C5CB;--m:#8E9199;--a:#2ED47A}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font-family:"Asta Sans",-apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif}
.wrap{max-width:560px;margin:0 auto;padding:20px 16px 40px}
h1{font-size:15px;letter-spacing:.02em;color:var(--m);margin:0 0 6px;font-weight:600}
h2{font-size:26px;margin:0 0 4px}.sub{color:var(--m);font-size:14px;margin-bottom:18px}
.card{background:var(--card);border-radius:24px;padding:18px}
ul{list-style:none;margin:0;padding:0}.ev{display:flex;gap:12px;padding:14px 0;border-top:1px solid var(--line)}.ev:first-child{border-top:0;padding-top:0}
.ic{font-size:24px;width:44px;height:44px;border-radius:14px;background:var(--inner);display:flex;align-items:center;justify-content:center;flex-shrink:0}.t{font-weight:700;font-size:17px}.b{color:var(--s);font-size:15px;margin-top:3px;line-height:1.5}.d{color:var(--m);font-size:12px;margin-top:6px}
.empty{color:var(--m);padding:14px 0}
.cta{display:flex;gap:10px;margin-top:16px}.cta a{flex:1;text-align:center;padding:15px;border-radius:999px;text-decoration:none;font-weight:700}
.call{background:var(--t);color:#131417}.fix{background:rgba(255,255,255,.08);color:var(--t)}
.note{color:var(--m);font-size:12px;margin-top:18px;line-height:1.5}
</style></head><body><div class="wrap">
<h1>모심</h1><h2>부모님 차에서 온 소식</h2><div class="sub">부모님이 고르고, 차 화면에서 먼저 확인한 소식만 옵니다</div>
<div class="card"><ul>${items}</ul>
<div class="cta"><a class="call" href="tel:">📞 전화하기</a><a class="fix" href="https://www.hyundai.com/kr/ko/service/bluehands" target="_blank">정비 예약</a></div></div>
<div class="note"><b>소식이 없다고 해서 안전하다는 뜻은 아닙니다.</b> 궁금하시면 직접 전화해 보세요.<br>위치와 속도는 어떤 경우에도 전달되지 않습니다. 부모님이 차에서 연결을 해제하면 이 기록은 지워집니다.</div>
</div></body></html>`;
  return new Response(html, { headers: {
    "content-type": "text/html; charset=utf-8", "cache-control": "no-store",
    "referrer-policy": "no-referrer", "x-robots-tag": "noindex, nofollow",
  } });
}
