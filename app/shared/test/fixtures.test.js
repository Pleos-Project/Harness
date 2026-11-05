// 앱 엔진(app/shared/chagok-engine.js)이 서버와 같은 fixture를 통과하는지.
// 실행: node --test app/shared/test   (의존성 없음, Node 20+)
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const E = require('../chagok-engine.js');

const ROOT = path.resolve(__dirname, '../../..');
const load = (p) => JSON.parse(fs.readFileSync(path.join(ROOT, p), 'utf8'));
const list = (dir, prefix) => fs.readdirSync(path.join(ROOT, dir))
  .filter((f) => f.startsWith(prefix) && f.endsWith('.json'))
  .sort((a, b) => parseInt(a.slice(1), 10) - parseInt(b.slice(1), 10))
  .map((f) => `${dir}/${f}`);

const CONST = load('fixtures/constants.json');
const CANDS = load('fixtures/candidates_demo.json').candidates;
const MASK = '<session>';
const ORIGIN = { lat: CONST.origin.lat, lng: CONST.origin.lng };

function appLocal(inp) {
  const v = E.vehicleInputs(CONST, inp.model, inp.level_pct, {
    remainingRangeM: inp.remaining_range_m, capacitySdk: inp.capacity_sdk, efficiencySdk: inp.efficiency_sdk, price: inp.price,
  });
  const r = E.computeRange(v, inp.extra_krw || 0, inp.days || 1);
  const expected = {
    fuel_type: v.fuel_type, capacity: v.capacity, efficiency: v.efficiency, price_per_unit: v.price_per_unit,
    price_source: v.price_source, balance_km: E.round1(r.balance_km), balance_krw: r.balance_krw,
    balance_text: r.balance_text, margin_km: E.round1(r.margin_km), usable_km: E.round1(r.usable_km),
    radius_km: E.round1(r.radius_km), range_source: r.range_source, zero_available: r.zero_available,
  };
  return { v, r, expected };
}

const masked = (res) => (res.session_id ? { ...res, session_id: MASK } : res);

for (const p of list('fixtures/cards', 'D')) {
  const fx = load(p);

  if (fx.app_local) {
    test(`${fx.id} 앱 로컬 E1 · 요청`, () => {
      const { v, r, expected } = appLocal(fx.app_local.inputs);
      assert.deepStrictEqual(expected, fx.app_local.expected);
      if (fx.request) {
        const req = E.cardsRequest({
          origin: ORIGIN, vehicle: v, range: r, extraKrw: fx.app_local.inputs.extra_krw || 0,
          days: fx.app_local.inputs.days || 1, seats: fx.request.seats_occupied, themes: fx.request.themes,
          recentVisits: fx.request.recent_visits, demoSeed: CONST.demo_seed,
        });
        const want = { ...fx.request };
        delete want.session_id;
        assert.deepStrictEqual(req, want);
      }
    });
  }

  if (fx.request) {
    test(`${fx.id} 오프라인 데모 응답 = 서버 응답`, () => {
      const server = E.createDemoServer({ candidates: CANDS });
      let req = fx.request;
      if (fx.session_from) {
        const first = server.cards(load(`fixtures/cards/${fx.session_from}.json`).request);
        req = { ...req, session_id: first.session_id };
      }
      const res = server.cards(req);
      assert.deepStrictEqual(masked(res), fx.expected);
      for (const want of fx.expected_swaps || []) {
        const s = server.swap(res.session_id);
        if (want.error) assert.equal(s.error, want.error);
        else assert.deepStrictEqual({ card: s.card, swaps_left: s.swaps_left, stack_left: s.stack_left }, want);
      }
    });
  }
}

test('D5 정산', () => {
  const fx = load('fixtures/cards/D5.json');
  assert.deepStrictEqual(E.settle(fx.snapshot_prev, fx.odometer_now, fx.last_destination, fx.span_days), fx.expected);
});

test('D6 충전 직후', () => {
  const fx = load('fixtures/cards/D6.json');
  const [, krw, text] = E.chargeAmount(fx.charge_start_soc, fx.charge_end_soc, fx.capacity_kwh, fx.price);
  assert.equal(krw, fx.expected.krw);
  assert.equal(text, fx.expected.text);
});

for (const p of list('fixtures/settle', 'T')) {
  const fx = load(p);
  test(`${fx.id} ${fx.title}`, () => {
    assert.deepStrictEqual(E.settle(fx.snapshot_prev, fx.odometer_now, fx.last_destination, fx.span_days), fx.expected);
  });
}

for (const p of list('fixtures/ledger', 'L')) {
  const fx = load(p);
  test(`${fx.id} ${fx.title}`, () => {
    let st = fx.state0;
    fx.steps.forEach((step, i) => {
      const [next, out] = E.ledgerStep(st, step.event);
      assert.deepStrictEqual(out, step.expected.output, `step ${i + 1} output`);
      assert.deepStrictEqual(next, step.expected.state, `step ${i + 1} state`);
      st = next;
    });
    assert.deepStrictEqual(E.monthSummary(st.rows, fx.month), fx.expected_month);
  });
}

test('일러스트 변형은 같은 장소에 늘 같은 그림', () => {
  assert.equal(E.illustrationPath('river', 'p_dumulmeori', 4), E.illustrationPath('river', 'p_dumulmeori', 4));
  assert.match(E.illustrationPath('sea', 'p_eulwangri', 3), /^illustrations\/sea-[1-3]\.svg$/);
});
