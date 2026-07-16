/*
 * Testuje API logiku Workeru proti mocku D1 (in-memory).
 * Mock pokrývá přesně ty SQL dotazy, které worker.js používá.
 * Spuštění:  node test/worker.test.mjs
 */
import worker from '../src/worker.js';

// ---- Mock D1 ----
function makeDB(initial) {
  let rows = initial.slice();
  let archiveRows = [];
  function prepare(sql) {
    // Jako reálné D1: .bind() vrací NOVÝ statement, .run() vrací { meta }.
    const make = (args) => ({
      bind: (...a) => make(a),
      all: async () => ({ results: run(sql, args).rows }),
      first: async () => run(sql, args).rows[0] || null,
      run: async () => ({ meta: run(sql, args).meta }),
    });
    return make([]);
  }
  function run(sql, a) {
    sql = sql.replace(/\s+/g, ' ').trim();
    if (sql.startsWith('SELECT * FROM vehicles ORDER BY')) {
      const r = rows.slice().sort((x, y) =>
        x.side.localeCompare(y.side) || x.rownum - y.rownum || x.pos - y.pos || x.id.localeCompare(y.id));
      return { rows: r, meta: { changes: 0 } };
    }
    if (sql.startsWith('SELECT COALESCE(MAX(pos)+1,0)')) {
      const [side, rownum] = a;
      const ps = rows.filter(r => r.side === side && r.rownum === rownum).map(r => r.pos);
      return { rows: [{ p: ps.length ? Math.max(...ps) + 1 : 0 }], meta: {} };
    }
    if (sql.startsWith('INSERT INTO vehicles')) {
      const [id, side, pos, rownum, model, vin, keynum, note, status, updated_at] = a;
      rows.push({ id, side, pos, rownum, model, vin, keynum, note, status, updated_at });
      return { rows: [], meta: { changes: 1 } };
    }
    if (sql.startsWith('UPDATE vehicles SET side=?')) { // přesun/seřazení
      const [side, rownum, pos, updated_at, id] = a;
      const r = rows.find(x => x.id === id);
      if (!r) return { rows: [], meta: { changes: 0 } };
      Object.assign(r, { side, rownum, pos, updated_at });
      return { rows: [], meta: { changes: 1 } };
    }
    if (sql.startsWith('UPDATE vehicles SET')) {
      const [model, vin, keynum, note, status, updated_at, id] = a;
      const r = rows.find(x => x.id === id);
      if (!r) return { rows: [], meta: { changes: 0 } };
      Object.assign(r, { model, vin, keynum, note, status, updated_at });
      return { rows: [], meta: { changes: 1 } };
    }
    if (sql.startsWith('SELECT * FROM vehicles WHERE id=?')) {
      return { rows: rows.filter(r => r.id === a[0]), meta: {} };
    }
    if (sql.startsWith('DELETE FROM vehicles WHERE id=?')) {
      const before = rows.length; rows = rows.filter(r => r.id !== a[0]);
      return { rows: [], meta: { changes: before - rows.length } };
    }
    if (sql.startsWith('DELETE FROM vehicles WHERE side=? AND rownum=?')) {
      const before = rows.length; rows = rows.filter(r => !(r.side === a[0] && r.rownum === a[1]));
      return { rows: [], meta: { changes: before - rows.length } };
    }
    if (sql.startsWith('DELETE FROM vehicles')) { const n = rows.length; rows = []; return { rows: [], meta: { changes: n } }; }
    if (sql.startsWith('CREATE TABLE IF NOT EXISTS archive')) { return { rows: [], meta: { changes: 0 } }; }
    if (sql.startsWith('INSERT INTO archive')) {
      const [id, vin, keynum, archived_at] = a;
      archiveRows.push({ id, vin, keynum, archived_at });
      return { rows: [], meta: { changes: 1 } };
    }
    if (sql.startsWith('SELECT * FROM archive ORDER BY')) {
      const r = archiveRows.slice().sort((x, y) => y.archived_at - x.archived_at);
      return { rows: r, meta: {} };
    }
    throw new Error('Mock D1: neznámý SQL: ' + sql);
  }
  return {
    prepare,
    async batch(stmts) { for (const s of stmts) await s.run(); return []; },
    _rows: () => rows,
    _archiveRows: () => archiveRows,
  };
}

const env = { DB: makeDB([
  { id: 'a', side: 'left', pos: 0, rownum: 1, model: 'Scala', vin: '111', keynum: '1', note: '', status: 'parked', updated_at: 1 },
  { id: 'b', side: 'left', pos: 1, rownum: 1, model: 'Fabia', vin: '222', keynum: '2', note: '', status: 'parked', updated_at: 1 },
  { id: 'c', side: 'right', pos: 0, rownum: 2, model: 'Kamiq', vin: '333', keynum: '3', note: '', status: 'parked', updated_at: 1 },
]) };

function req(method, path, body) {
  return new Request('https://x' + path, {
    method,
    headers: { 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
}
async function call(method, path, body) {
  const res = await worker.fetch(req(method, path, body), env);
  let data = null;
  try { data = await res.json(); } catch (e) {}
  return { status: res.status, data };
}

let pass = 0, fail = 0;
function ok(cond, msg) { if (cond) { pass++; console.log('  ✓ ' + msg); } else { fail++; console.log('  ✗ ' + msg); } }

const tests = async () => {
  console.log('GET /api/state');
  let r = await call('GET', '/api/state');
  ok(r.status === 200, 'status 200');
  ok(r.data.vehicles.length === 3, 'vrací 3 vozy');
  ok(r.data.vehicles[0].row === 1 && r.data.vehicles[0].key === '1', 'mapuje rownum→row, keynum→key');

  console.log('POST /api/vehicles (nový vůz, auto pos)');
  r = await call('POST', '/api/vehicles', { side: 'left', row: 1, model: 'Kodiaq', vin: '444', key: '4', status: 'received' });
  ok(r.status === 201, 'status 201');
  ok(r.data.pos === 2, 'pos se dopočítal na 2');
  const newId = r.data.id;
  r = await call('GET', '/api/state');
  ok(r.data.vehicles.length === 4, 'po přidání 4 vozy');

  console.log('PUT /api/vehicles/:id (změna stavu na dílnu)');
  r = await call('PUT', '/api/vehicles/' + newId, { model: 'Kodiaq', vin: '444', key: '4', note: 'oprava', status: 'workshop' });
  ok(r.status === 200 && r.data.status === 'workshop', 'stav = workshop');
  ok(r.data.note === 'oprava', 'poznámka uložena');

  console.log('PUT stav "departure" (připravit na odjezd)');
  r = await call('PUT', '/api/vehicles/' + newId, { model: 'Kodiaq', vin: '444', key: '4', status: 'departure' });
  ok(r.status === 200 && r.data.status === 'departure', 'stav = departure');

  console.log('PUT neexistující → 404');
  r = await call('PUT', '/api/vehicles/nope', { model: 'x' });
  ok(r.status === 404, 'status 404');

  console.log('DELETE /api/vehicles/:id');
  r = await call('DELETE', '/api/vehicles/' + newId);
  ok(r.status === 200, 'status 200');
  r = await call('GET', '/api/state');
  ok(r.data.vehicles.length === 3, 'zpět na 3 vozy');

  console.log('POST /api/rows/delete (smazání řady left/1)');
  r = await call('POST', '/api/rows/delete', { side: 'left', row: 1 });
  ok(r.status === 200, 'status 200');
  r = await call('GET', '/api/state');
  ok(r.data.vehicles.length === 1 && r.data.vehicles[0].side === 'right', 'zbyl jen pravý vůz');

  console.log('POST /api/reset (obnova 64 vozů)');
  r = await call('POST', '/api/reset', {});
  ok(r.status === 200 && r.data.count === 64, 'reset vložil 64 vozů');
  r = await call('GET', '/api/state');
  const left = r.data.vehicles.filter(v => v.side === 'left').length;
  ok(r.data.vehicles.length === 64, 'state má 64 vozů');
  ok(left === 12, 'z toho 12 vlevo');
  ok(r.data.vehicles.some(v => v.note.indexOf('📸') > -1), 'vlaječky 📸 zachovány');

  console.log('POST /api/move (přesun mezi řadami + přečíslování pozic)');
  // přesuneme vůz z left/1 do left/2 a seřadíme cílovou řadu
  let l1 = r.data.vehicles.filter(v => v.side === 'left' && v.row === 1);
  let l2 = r.data.vehicles.filter(v => v.side === 'left' && v.row === 2);
  const movedId = l1[0].id;
  const targetOrder = [l2[0].id, movedId, l2[1].id]; // vložíme doprostřed
  r = await call('POST', '/api/move', { side: 'left', row: 2, order: targetOrder });
  ok(r.status === 200 && r.data.count === 3, 'move vrátil count 3');
  r = await call('GET', '/api/state');
  const mv = r.data.vehicles.find(v => v.id === movedId);
  ok(mv.side === 'left' && mv.row === 2 && mv.pos === 1, 'přesunutý vůz je left/2 na pozici 1');
  const newL2 = r.data.vehicles.filter(v => v.side === 'left' && v.row === 2);
  ok(newL2.length === 3 && newL2.map(v => v.id).join() === targetOrder.join(), 'cílová řada má 3 vozy ve správném pořadí');

  console.log('POST /api/move bez order → 400');
  r = await call('POST', '/api/move', { side: 'left', row: 1, order: [] });
  ok(r.status === 400, 'status 400');

  console.log('POST /api/vehicles na plac Dílna (side=dilna, row=0, bez řad)');
  r = await call('POST', '/api/vehicles', { side: 'dilna', row: 0, model: 'Octavia', vin: '555', key: '9', status: 'workshop' });
  ok(r.status === 201, 'status 201');
  ok(r.data.side === 'dilna' && r.data.row === 0, 'row zůstal 0 (nespadl na fallback 1)');
  const dilnaId = r.data.id;

  console.log('POST /api/vehicles na plac Kaufmann + FIFO append');
  r = await call('POST', '/api/vehicles', { side: 'kaufmann', row: 0, model: 'Superb', vin: '666', key: '11', status: 'kaufmann' });
  ok(r.status === 201 && r.data.pos === 0, 'první vůz na Kaufmannu má pos 0');
  r = await call('POST', '/api/vehicles', { side: 'kaufmann', row: 0, model: 'Superb', vin: '777', key: '12', status: 'kaufmann' });
  ok(r.data.pos === 1, 'druhý vůz na Kaufmannu se přidal na konec (FIFO, pos 1)');

  console.log('POST /api/move mezi placy (dilna → kaufmann)');
  r = await call('POST', '/api/move', { side: 'kaufmann', row: 0, order: [dilnaId] });
  ok(r.status === 200, 'move na kaufmann OK');
  r = await call('GET', '/api/state');
  const movedPlace = r.data.vehicles.find(v => v.id === dilnaId);
  ok(movedPlace.side === 'kaufmann' && movedPlace.row === 0, 'vůz přesunut z dílny na kaufmann, row 0');

  console.log('POST /api/archive (vyřazení po předprodeji)');
  r = await call('POST', '/api/archive', { id: dilnaId });
  ok(r.status === 200 && r.data.ok === true, 'archivace OK');
  r = await call('GET', '/api/state');
  ok(!r.data.vehicles.some(v => v.id === dilnaId), 'vůz zmizel z aktivních parkovišť');

  console.log('GET /api/archive');
  r = await call('GET', '/api/archive');
  ok(r.status === 200, 'status 200');
  ok(r.data.items.length === 1, 'archiv obsahuje 1 záznam');
  ok(r.data.items[0].vin === '555' && r.data.items[0].key === '9', 'archiv uchoval jen VIN a číslo klíče');
  ok(r.data.items[0].model === undefined, 'archiv neuchovává model');

  console.log('POST /api/archive na neexistující vůz → 404');
  r = await call('POST', '/api/archive', { id: 'nope' });
  ok(r.status === 404, 'status 404');

  console.log('Neznámá cesta → 404');
  r = await call('GET', '/api/blah');
  ok(r.status === 404, 'status 404');

  console.log('\n' + pass + ' OK, ' + fail + ' chyb');
  if (fail) process.exit(1);
};
tests();
