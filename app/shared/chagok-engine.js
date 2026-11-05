/* 차곡 엔진 — 앱(WebView)용. server/chagok/engine.py · cards.py 를 그대로 옮긴 것.
 *
 * 쓰는 곳
 *   - 앱 로컬 계산: 잔고·범위(E1), 정산(E4), 충전 직후(F9), 장부(F8·F10·F17)
 *   - 오프라인 데모 모드·서버 실패 fallback: createDemoServer() 가 /v1/cards · /v1/cards/swap 과 같은 응답
 *
 * 규칙
 *   - Python 쪽을 바꾸면 여기도 같은 PR에서 바꾼다. 둘 다 fixtures/ 를 통과해야 한다:
 *       python -m pytest (server/)  ·  node --test app/shared/test
 *   - 반올림은 roundHalfUp / roundTo / round1 만. 곱셈·나눗셈 순서도 Python과 같게 적는다.
 *   - 데이터 객체 필드는 계약(contract/openapi.yaml)과 같은 snake_case.
 *
 * 브라우저: <script src="chagok/engine.js"></script> → window.ChagokEngine
 * Node:     const E = require('./chagok-engine.js')
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ChagokEngine = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const ROAD_FACTOR = 1.3;
  const MARGIN_MIN_KM = 50.0;
  const MARGIN_RATIO = 0.15;
  const OVERNIGHT_KM = 30.0;
  const TIGHT_LOW = 0.90;
  const OFFICIAL_TO_REAL = 0.85;
  const RING_KRW = [0, 10000, 30000];
  const MAX_SWAPS = 2;
  const NEAR_MINUTES = 75;
  const FAR_MINUTES = 90;
  const PIN_SAME_KM = 0.3;
  const AVG_PRICE_SOURCES = ['default'];
  const SESSION_TTL_MS = 30 * 60 * 1000;
  const STACK_SIZE = 3;

  // ------------------------------------------------------------ 숫자·문구
  const roundHalfUp = (x) => Math.floor(x + 0.5);
  const roundTo = (n, unit) => Math.floor((n + unit / 2) / unit) * unit;
  const round1 = (x) => Math.floor(x * 10 + 0.5) / 10;
  const lPer100kmToKmPerL = (v) => (v > 0 ? 100.0 / v : 0.0);

  function krwText(n) {
    const man = Math.floor(n / 10000);
    const cheon = Math.floor((n % 10000) / 1000);
    const parts = [];
    if (man) parts.push(`${man}만`);
    if (cheon) parts.push(`${cheon}천`);
    return (parts.join(' ') || '0') + ' 원';
  }

  /** unit 단위 어림. 0으로 떨어지면 '{unit} 미만'. 반환 [어림값, 문구, 미만 여부] */
  function approx(n, unit) {
    const r = roundTo(n, unit);
    if (r > 0) return [r, krwText(r), false];
    return [0, `${krwText(unit)} 미만`, true];
  }

  // ------------------------------------------------------------ E1 잔고·범위 (앱 로컬)
  /** F1 입력 우선순위: 용량 SDK > 차종 표, 연비 SDK(실연비) > 차종 표 × 0.85, 단가 설정 > 앱 기본값 */
  function vehicleInputs(constants, model, levelPct, opts = {}) {
    const spec = constants.vehicles[model];
    const ev = spec.fuel_type === 'ev';
    let price = opts.price;
    let source = 'user';
    if (price == null) {
      price = constants.price[ev ? 'home_charge_krw_per_kwh' : 'gasoline_krw_per_l'];
      source = 'default';
    }
    return {
      fuel_type: spec.fuel_type,
      level_pct: levelPct,
      capacity: opts.capacitySdk ? opts.capacitySdk : spec.capacity,
      efficiency: opts.efficiencySdk ? opts.efficiencySdk : spec.official_efficiency * OFFICIAL_TO_REAL,
      price_per_unit: price,
      remaining_range_m: opts.remainingRangeM || 0,
      price_source: source,
    };
  }

  function balanceKm(v) {
    if (v.remaining_range_m && v.remaining_range_m > 0) return [v.remaining_range_m / 1000.0, 'range_remaining'];
    return [v.level_pct / 100.0 * v.capacity * v.efficiency, 'assembled'];
  }

  const extraKm = (b, extraKrw) => (extraKrw ? extraKrw / b.price_per_unit * b.efficiency : 0.0);
  const radiusKm = (b, extraKrw) => Math.max(0.0, (b.usable_km + extraKm(b, extraKrw)) / 2.0 / ROAD_FACTOR);

  function computeRange(v, extraKrw = 0, days = 1) {
    const [km, src] = balanceKm(v);
    const [krw, krwTxt, under] = approx(v.level_pct / 100.0 * v.capacity * v.price_per_unit, 1000);
    const margin = Math.max(MARGIN_MIN_KM, km * MARGIN_RATIO);
    const usable = km - margin - (days === 2 ? OVERNIGHT_KM : 0.0);
    const b = budgetOf({ usable_km: usable, margin_km: margin }, v);
    const where = v.fuel_type === 'ev' ? '배터리에' : '탱크에';
    const amount = under ? krwTxt : `${krwTxt}어치`;
    return {
      balance_km: km, balance_krw: krw, margin_km: margin, usable_km: usable,
      radius_km: radiusKm(b, extraKrw), range_source: src, zero_available: usable > 0,
      balance_text: `${where} ${amount} · 약 ${roundHalfUp(km)}km`, // 단가 출처는 화면에 따로 작게(F1)
    };
  }

  /** /v1/cards 로 보내는 budget — 원시 차량값이 아니라 파생값만(PRD 5-2) */
  function budgetOf(r, v) {
    return {
      fuel_type: v.fuel_type, usable_km: r.usable_km, margin_km: r.margin_km,
      efficiency: v.efficiency, price_per_unit: v.price_per_unit, price_source: v.price_source,
    };
  }

  /** 앱이 서버로 보내는 요청 전체. origin은 내비 현재 위치(또는 fallback). */
  function cardsRequest({ origin, vehicle, range, extraKrw = 0, days = 1, seats = 1, themes = [], recentVisits = [], demoSeed = null, sessionId = null }) {
    const req = {
      origin: { lat: origin.lat, lng: origin.lng }, budget: budgetOf(range, vehicle),
      seats_occupied: seats, extra_krw: extraKrw, days, themes, recent_visits: recentVisits, demo_seed: demoSeed,
    };
    if (sessionId) req.session_id = sessionId;
    return req;
  }

  // ------------------------------------------------------------ E3 비용·배지
  function computeCost(c, b, seats) {
    const fuel = roundHalfUp(2 * c.route_km / b.efficiency * b.price_per_unit);
    const toll = 2 * c.toll_krw;
    const parking = c.has_parking_info ? c.parking_krw : null;
    const total = fuel + toll + (parking || 0);
    const [, txt, under] = approx(total, 5000);
    let pp = null;
    let ppText = null;
    if (seats >= 2) {
      const [v, t, u] = approx(total / seats, 1000);
      pp = v;
      ppText = u ? `1인 ${t}` : `1인 ${t} 안팎`;
    }
    return { fuel, toll, parking, total, total_text: under ? txt : `${txt} 안팎`, per_person: pp, per_person_text: ppText };
  }

  function computeBadge(c, b) {
    const rt = 2 * c.route_km;
    let ring = null;
    for (const r of RING_KRW) {
      if (rt <= b.usable_km + extraKm(b, r)) { ring = r; break; }
    }
    const zero = ring === 0;
    const tight = zero && rt >= b.usable_km * TIGHT_LOW;
    const margin = roundHalfUp(b.margin_km);
    let text;
    if (tight) text = '빠듯';
    else if (zero) text = `추가 ${b.fuel_type === 'ev' ? '충전' : '주유'} 없이 · 여유 ${margin}km`;
    else if (ring !== null) text = `+${krwText(ring)} 안팎`;
    else text = '범위 밖';
    return { zero_extra: zero, tight, ring_krw: ring, text, margin_km: margin };
  }

  function timeLabel(minutes) {
    if (minutes <= NEAR_MINUTES) return '75분 안';
    if (minutes <= FAR_MINUTES) return '조금 먼 곳';
    return null;
  }

  // ------------------------------------------------------------ E2 점수·이유
  const WEIGHTS = { zero: 0.30, time: 0.25, theme: 0.20, diversity: 0.15, parking: 0.10 };
  const PHRASE = { zero: '추가 0원 안', time: '75분 안', diversity: '지난번과 다른 테마' };
  const THEME_KO = { sea: '바다', lake: '호수', river: '강', mountain: '산', park: '공원', city: '도시', island: '섬', drive: '드라이브길' };
  const KEYS = ['zero', 'time', 'theme', 'diversity', 'parking'];

  function score(c, badge, themes, recentThemes) {
    const s = {};
    s.zero = badge.zero_extra && !badge.tight ? 1.0 : (badge.tight ? 0.5 : 0.0);
    if (c.minutes <= NEAR_MINUTES) s.time = 1.0;
    else if (c.minutes <= FAR_MINUTES) s.time = 1.0 - (c.minutes - NEAR_MINUTES) / 15.0;
    else s.time = 0.0;
    s.theme = themes.length === 0 ? 0.5 : (themes.includes(c.theme) ? 1.0 : 0.0);
    s.diversity = recentThemes.includes(c.theme) ? 0.0 : 1.0;
    s.parking = c.has_parking_info ? 1.0 : 0.0;
    return s;
  }

  function weighted(s) {
    let total = 0.0;
    for (const k of KEYS) total += WEIGHTS[k] * s[k];
    return Math.floor(total * 10000 + 0.5) / 10000;
  }

  function reasonLine(c, s, badge) {
    const contrib = ['zero', 'time', 'theme', 'diversity'].map((k) => ({ val: WEIGHTS[k] * s[k], key: k }));
    contrib.sort((a, b) => (b.val - a.val) || (a.key < b.key ? 1 : a.key > b.key ? -1 : 0));
    const phrases = [];
    for (const { val, key } of contrib) {
      if (val <= 0) continue;
      if (key === 'zero' && !(badge.zero_extra && !badge.tight)) continue;
      if (key === 'time' && c.minutes > NEAR_MINUTES) continue;
      const phrase = key === 'theme' ? (THEME_KO[c.theme] || c.theme) : PHRASE[key];
      if (!phrases.includes(phrase)) phrases.push(phrase);
      if (phrases.length === 3) break;
    }
    const themeKo = THEME_KO[c.theme] || c.theme;
    if (phrases.length < 3 && !phrases.includes(themeKo)) phrases.push(themeKo);
    return phrases.join(' · ');
  }

  function cardJson(c, cost, badge, reason) {
    return {
      id: c.id, name: c.name, theme: c.theme, lat: c.lat, lng: c.lng,
      route_km: c.route_km, minutes: c.minutes, time_label: timeLabel(c.minutes),
      cost: { fuel: cost.fuel, toll: cost.toll, parking: cost.parking, total: cost.total, per_person: cost.per_person },
      display: {
        total_text: cost.total_text, per_person_text: cost.per_person_text,
        zero_extra: badge.zero_extra, tight: badge.tight, ring_krw: badge.ring_krw,
        badge_text: badge.text, margin_km: badge.margin_km,
      },
      reason,
    };
  }

  // ------------------------------------------------------------ F5 순위·스택·교체 (cards.py)
  function evaluate(candidates, b, seats, themes, recentIds) {
    const recentThemes = candidates.filter((c) => recentIds.includes(c.id)).map((c) => c.theme);
    const out = [];
    for (const c of candidates) {
      if (recentIds.includes(c.id) || timeLabel(c.minutes) === null) continue;
      const badge = computeBadge(c, b);
      if (badge.ring_krw === null) continue;
      const s = score(c, badge, themes, recentThemes);
      const cj = cardJson(c, computeCost(c, b, seats), badge, reasonLine(c, s, badge));
      cj._score = weighted(s);
      cj._route_km = c.route_km;
      out.push(cj);
    }
    return out;
  }

  function sortRanked(cards, radius) {
    const target = radius * 0.5;
    return cards.slice().sort((a, b) =>
      (b._score - a._score) ||
      (Math.abs(a._route_km - target) - Math.abs(b._route_km - target)) ||
      (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  }

  const eligible = (card, extraKrw, themes) =>
    card.display.ring_krw <= extraKrw && (themes.length === 0 || themes.includes(card.theme));

  function buildStack(picked, pool, size = STACK_SIZE) {
    pool = pool.filter((c) => c.id !== picked.id).slice(0, 9);
    const stack = [];
    const used = new Set([picked.theme]);
    const far = pool.find((c) => c.time_label === '조금 먼 곳') || null;
    if (far) { stack.push(far); used.add(far.theme); }
    for (const c of pool) {
      if (stack.length >= size) break;
      if (c === far || used.has(c.theme)) continue;
      stack.push(c); used.add(c.theme);
    }
    for (const c of pool) {
      if (stack.length >= size) break;
      if (!stack.includes(c)) stack.push(c);
    }
    return stack;
  }

  function fewHint(n, extraKrw, themes) {
    if (n >= 3) return null;
    const actions = themes.length ? ['clear_themes'] : [];
    const nxt = RING_KRW.find((r) => r > extraKrw);
    if (nxt) actions.push(`extra_${nxt}`);
    return { code: 'few_candidates', text: '범위 안 후보가 적어요', actions };
  }

  function strip(card) {
    if (!card) return null;
    const out = {};
    for (const k of Object.keys(card)) if (!k.startsWith('_')) out[k] = card[k];
    return out;
  }

  function ranked(req, candidates) {
    const b = req.budget;
    const extra = req.extra_krw || 0;
    const themes = req.themes || [];
    const radius = radiusKm(b, extra);
    const all = sortRanked(evaluate(candidates, b, req.seats_occupied || 1, themes, req.recent_visits || []), radius);
    return { all, pool: all.filter((c) => eligible(c, extra, themes)), radius };
  }

  const withoutSession = (req) => { const r = { ...req }; delete r.session_id; return r; };

  /** 오프라인 데모 모드·서버 실패 fallback. /v1/cards · /v1/cards/swap 과 같은 응답을 낸다. */
  function createDemoServer({ candidates, now = () => Date.now(), ids } = {}) {
    let n = 0;
    const nextId = ids || (() => `demo-${++n}`);
    const sessions = new Map();
    const prune = () => { const t = now(); for (const [k, s] of sessions) if (t - s.t > SESSION_TTL_MS) sessions.delete(k); };
    const get = (sid) => { prune(); const s = sessions.get(sid); if (s) s.t = now(); return s || null; };

    function cards(req) {
      const { all, pool, radius } = ranked(req, candidates);
      let sid = req.session_id || null;
      const sess = sid ? get(sid) : null;
      let picked;
      let stack;
      if (sess) {
        sess.request = withoutSession(req);
        const live = pool.filter((c) => !sess.excluded.includes(c.id));
        picked = all.find((c) => c.id === sess.picked_id) || null;
        if (!picked) { picked = live[0] || null; sess.picked_id = picked ? picked.id : null; }
        stack = picked ? buildStack(picked, live) : [];
        sess.stack_ids = stack.map((c) => c.id);
      } else {
        picked = pool[0] || null;
        stack = picked ? buildStack(picked, pool) : [];
        sid = null;
        if (picked) {
          prune();
          sid = nextId();
          sessions.set(sid, { picked_id: picked.id, stack_ids: stack.map((c) => c.id), excluded: [], swaps: 0, request: withoutSession(req), t: now() });
        }
      }
      return {
        session_id: sid, radius_km: round1(radius), candidates_in_range: pool.length,
        hint: fewHint(pool.length, req.extra_krw || 0, req.themes || []),
        picked: strip(picked), stack: stack.map(strip),
      };
    }

    function swap(sessionId, excludeIds = []) {
      const sess = get(sessionId);
      if (!sess) return { error: 'session_not_found' };
      if (sess.swaps >= MAX_SWAPS) return { error: 'swap_limit', hint: '테마를 바꿔 볼까요?' };
      const { all, pool } = ranked(sess.request, candidates);
      const byId = new Map(all.map((c) => [c.id, c]));
      const excluded = [...new Set([...sess.excluded, ...excludeIds, sess.picked_id])];
      let nxt = null;
      for (const id of sess.stack_ids) if (byId.has(id) && !excluded.includes(id)) { nxt = byId.get(id); break; }
      if (!nxt) nxt = pool.find((c) => !excluded.includes(c.id)) || null;
      if (!nxt) return { error: 'no_more_candidates', hint: '테마를 바꿔 볼까요?' };
      sess.excluded = excluded;
      sess.swaps += 1;
      sess.picked_id = nxt.id;
      sess.stack_ids = sess.stack_ids.filter((id) => id !== nxt.id);
      return { session_id: sessionId, card: strip(nxt), swaps_used: sess.swaps, swaps_left: MAX_SWAPS - sess.swaps, stack_left: sess.stack_ids.length };
    }

    return { cards, swap };
  }

  // ------------------------------------------------------------ E4 정산 · F9 충전
  function settle(prev, odometerNow, lastDestination, spanDays = 0) {
    const skip = (reason) => ({ km: 0, krw: 0, per_person: null, text: '', skipped: true, reason });
    if (!prev) return skip('no_snapshot');
    const delta = odometerNow - prev.odometer_km;
    if (delta < 0) return skip('odometer_decreased');
    if (delta === 0) return skip('no_distance');
    const [krw, txt, under] = approx(delta / prev.efficiency * prev.price_per_unit, 1000);
    let pp = null;
    if (prev.seats >= 2) pp = approx(delta / prev.efficiency * prev.price_per_unit / prev.seats, 1000)[0];
    const head = spanDays >= 1 ? `지난번 이후 ${spanDays}일 · ` : '지난번 이후 ';
    let text = `${head}${roundHalfUp(delta)}km · ${under ? txt : `약 ${txt}`}`;
    if (AVG_PRICE_SOURCES.includes(prev.price_source)) text += ' (평균 단가)';
    if (lastDestination) text += ` · 마지막 목적지 ${lastDestination}`;
    return { km: delta, krw, per_person: pp, text, skipped: false, reason: null };
  }

  function chargeAmount(startSoc, endSoc, capacityKwh, price) {
    const kwh = Math.max(0.0, endSoc - startSoc) / 100.0 * capacityKwh;
    const [krw, txt, under] = approx(kwh * price, 1000);
    return [kwh, krw, under ? `방금 ${txt} 충전` : `방금 약 ${txt}어치 충전`];
  }

  // ------------------------------------------------------------ F8·F10·F17 로컬 장부
  const DEG = Math.PI / 180;
  function distKm(a, b) {
    const la1 = a.lat * DEG;
    const la2 = b.lat * DEG;
    const dla = la2 - la1;
    const dlo = (b.lng - a.lng) * DEG;
    const h = Math.sin(dla / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dlo / 2) ** 2;
    return 2 * 6371.0 * Math.asin(Math.sqrt(h));
  }

  function samePlace(a, b) {
    if (!a || !b) return false;
    if (a.name && a.name === b.name) return true;
    if ([a.lat, a.lng, b.lat, b.lng].some((v) => v == null)) return false;
    return distKm(a, b) < PIN_SAME_KM;
  }

  const daysBetween = (from, to) => Math.max(0, Math.floor((Date.parse(to) - Date.parse(from)) / 1000 / 86400));
  const snapshotOf = (ev) => ({ odometer_km: ev.odometer_km, seats: ev.seats, price_per_unit: ev.price, efficiency: ev.efficiency, ts: ev.ts, price_source: ev.price_source ?? 'user' });
  const placeOf = (p) => (p ? { name: p.name, lat: p.lat ?? null, lng: p.lng ?? null } : null);
  const newLedger = () => ({ snapshot: null, trip: null, rows: [], last_pin: null });

  /** engine.py ledger_step 과 같은 규칙. 반환 [다음 state, {card, row, skipped}] */
  function ledgerStep(state, event) {
    const st = { snapshot: state.snapshot, trip: state.trip, rows: state.rows.slice(), last_pin: state.last_pin };
    const t = event.type;
    if (t === 'depart') {
      st.trip = { place: event.place, route_km: event.route_km, start_odometer_km: event.odometer_km, departed_ts: event.ts };
      return [st, { card: null, row: null, skipped: null }];
    }
    if (t === 'open') {
      const prev = st.snapshot;
      const trip = st.trip;
      if (!prev) { st.snapshot = snapshotOf(event); return [st, { card: null, row: null, skipped: 'no_snapshot' }]; }
      const dest = trip ? placeOf(trip.place) : placeOf(event.recent_destination);
      const s = settle(prev, event.odometer_km, dest ? dest.name : null, daysBetween(prev.ts, event.ts));
      if (s.skipped) {
        if (s.reason === 'odometer_decreased') st.snapshot = snapshotOf(event);
        return [st, { card: null, row: null, skipped: s.reason }];
      }
      const pinned = !!dest && !samePlace(dest, st.last_pin);
      const row = { type: 'settle', from_ts: prev.ts, to_ts: event.ts, km: s.km, krw: s.krw, per_person: s.per_person, place: dest, pinned, demo: false };
      st.rows.push(row);
      if (pinned) st.last_pin = dest;
      st.snapshot = snapshotOf(event);
      st.trip = null;
      return [st, { card: s.text, row, skipped: null }];
    }
    if (t === 'arrive') {
      const trip = st.trip;
      if (!trip) return [st, { card: null, row: null, skipped: 'no_trip' }];
      const delta = event.odometer_km - trip.start_odometer_km;
      const demo = delta <= 0;
      const km = demo ? trip.route_km : delta;
      const krw = approx(km / event.efficiency * event.price, 1000)[0];
      const dest = placeOf(trip.place);
      const pinned = !samePlace(dest, st.last_pin);
      const row = { type: 'arrive', from_ts: trip.departed_ts, to_ts: event.ts, km, krw, per_person: null, place: dest, pinned, demo };
      st.rows.push(row);
      if (pinned) st.last_pin = dest;
      st.snapshot = snapshotOf(event);
      st.trip = null;
      return [st, { card: null, row, skipped: null }];
    }
    throw new Error(`unknown event type: ${t}`);
  }

  function monthSummary(rows, month) {
    const mine = rows.filter((r) => r.to_ts.slice(0, 7) === month);
    if (!mine.length) return { krw: 0, places: 0, demo: false, text: '첫 드라이브를 기다리는 중' };
    const total = mine.reduce((a, r) => a + r.krw, 0);
    const places = mine.filter((r) => r.pinned).length;
    const [krw, txt, under] = approx(total, 1000);
    return { krw, places, demo: mine.some((r) => r.demo), text: `이번 달 이동비 ${under ? txt : `약 ${txt}`} · 다녀온 곳 ${places}곳` };
  }

  // ------------------------------------------------------------ 화면 helper (Python 짝 없음)
  /** 테마 일러스트 변형 고르기: design/illustrations/<theme>-<n>.svg, 같은 장소는 늘 같은 그림 */
  function illustrationPath(theme, placeId, variants = 1) {
    let h = 0;
    for (const ch of String(placeId)) h = (h + ch.charCodeAt(0)) % 9973;
    return `illustrations/${theme}-${(h % Math.max(1, variants)) + 1}.svg`;
  }

  return {
    // 상수
    ROAD_FACTOR, MARGIN_MIN_KM, MARGIN_RATIO, OVERNIGHT_KM, TIGHT_LOW, OFFICIAL_TO_REAL, RING_KRW, MAX_SWAPS,
    NEAR_MINUTES, FAR_MINUTES, THEME_KO,
    // 숫자·문구
    roundHalfUp, roundTo, round1, lPer100kmToKmPerL, krwText, approx,
    // E1
    vehicleInputs, computeRange, budgetOf, radiusKm, cardsRequest,
    // E2·E3·F5
    computeCost, computeBadge, timeLabel, score, weighted, reasonLine, createDemoServer,
    // E4·F9·장부
    settle, chargeAmount, samePlace, newLedger, ledgerStep, monthSummary,
    // 화면
    illustrationPath,
  };
});
