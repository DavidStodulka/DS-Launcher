/*
 * Cloudflare Worker — API pro sdílenou správu parkování.
 * Data v D1 (binding DB). Statická appka přes ASSETS.
 * Endpoints (vše JSON):
 *   GET    /api/state                 -> { vehicles: [...] }
 *   POST   /api/vehicles              -> vytvoří vůz, vrátí ho
 *   PUT    /api/vehicles/:id          -> upraví vůz, vrátí ho
 *   DELETE /api/vehicles/:id          -> smaže vůz
 *   POST   /api/rows/delete {side,row}-> smaže celou řadu
 *   POST   /api/reset                 -> obnoví původní seznam (64 vozů)
 */

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS',
  'access-control-allow-headers': 'content-type',
  'cache-control': 'no-store',
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}
function uid() {
  return 'v' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}
function rowToVehicle(r) {
  return {
    id: r.id, side: r.side, row: r.rownum, pos: r.pos,
    model: r.model || '', vin: r.vin || '', key: r.keynum || '',
    note: r.note || '', status: r.status || 'parked',
  };
}
const VALID_STATUS = ['parked', 'workshop', 'received', 'departure'];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const { pathname } = url;

    if (!pathname.startsWith('/api/')) {
      return env.ASSETS.fetch(request); // statická appka
    }
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: JSON_HEADERS });
    }

    try {
      // GET /api/state
      if (pathname === '/api/state' && request.method === 'GET') {
        const { results } = await env.DB.prepare(
          'SELECT * FROM vehicles ORDER BY side, rownum, pos, id'
        ).all();
        return json({ vehicles: (results || []).map(rowToVehicle) });
      }

      // POST /api/vehicles
      if (pathname === '/api/vehicles' && request.method === 'POST') {
        const b = await request.json();
        const side = b.side === 'left' ? 'left' : 'right';
        const row = parseInt(b.row, 10) || 1;
        const status = VALID_STATUS.includes(b.status) ? b.status : 'parked';
        const id = uid();
        const posRow = await env.DB.prepare(
          'SELECT COALESCE(MAX(pos)+1,0) AS p FROM vehicles WHERE side=? AND rownum=?'
        ).bind(side, row).first();
        const pos = posRow ? posRow.p : 0;
        await env.DB.prepare(
          'INSERT INTO vehicles (id,side,pos,rownum,model,vin,keynum,note,status,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)'
        ).bind(id, side, pos, row, b.model || '', b.vin || '', b.key || '', b.note || '', status, Date.now()).run();
        return json(rowToVehicle({ id, side, pos, rownum: row, model: b.model, vin: b.vin, keynum: b.key, note: b.note, status }), 201);
      }

      // PUT /api/vehicles/:id
      const mPut = pathname.match(/^\/api\/vehicles\/([^/]+)$/);
      if (mPut && request.method === 'PUT') {
        const id = decodeURIComponent(mPut[1]);
        const b = await request.json();
        const status = VALID_STATUS.includes(b.status) ? b.status : 'parked';
        const res = await env.DB.prepare(
          'UPDATE vehicles SET model=?, vin=?, keynum=?, note=?, status=?, updated_at=? WHERE id=?'
        ).bind(b.model || '', b.vin || '', b.key || '', b.note || '', status, Date.now(), id).run();
        if (!res.meta.changes) return json({ error: 'not found' }, 404);
        const r = await env.DB.prepare('SELECT * FROM vehicles WHERE id=?').bind(id).first();
        return json(rowToVehicle(r));
      }

      // DELETE /api/vehicles/:id
      if (mPut && request.method === 'DELETE') {
        const id = decodeURIComponent(mPut[1]);
        await env.DB.prepare('DELETE FROM vehicles WHERE id=?').bind(id).run();
        return json({ ok: true });
      }

      // POST /api/move  { side, row, order:[ids] } — přesun/seřazení dlaždic
      if (pathname === '/api/move' && request.method === 'POST') {
        const b = await request.json();
        const side = b.side === 'left' ? 'left' : 'right';
        const row = parseInt(b.row, 10);
        const order = Array.isArray(b.order) ? b.order : [];
        if (isNaN(row) || !order.length) return json({ error: 'bad request' }, 400);
        const now = Date.now();
        const stmt = env.DB.prepare(
          'UPDATE vehicles SET side=?, rownum=?, pos=?, updated_at=? WHERE id=?'
        );
        const batch = order.map((id, i) => stmt.bind(side, row, i, now, String(id)));
        await env.DB.batch(batch);
        return json({ ok: true, count: batch.length });
      }

      // POST /api/rows/delete
      if (pathname === '/api/rows/delete' && request.method === 'POST') {
        const b = await request.json();
        const side = b.side === 'left' ? 'left' : 'right';
        const row = parseInt(b.row, 10);
        await env.DB.prepare('DELETE FROM vehicles WHERE side=? AND rownum=?').bind(side, row).run();
        return json({ ok: true });
      }

      // POST /api/reset
      if (pathname === '/api/reset' && request.method === 'POST') {
        await env.DB.prepare('DELETE FROM vehicles').run();
        const now = Date.now();
        const stmt = env.DB.prepare(
          'INSERT INTO vehicles (id,side,pos,rownum,model,vin,keynum,note,status,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)'
        );
        const batch = SEED.map((v, i) => {
          const id = 'seed-' + v.side[0] + v.row + '-' + v.vin;
          return stmt.bind(id, v.side, v.pos, v.row, v.model, v.vin, v.key || '', v.note || '', 'parked', now);
        });
        await env.DB.batch(batch);
        return json({ ok: true, count: SEED.length });
      }

      return json({ error: 'not found' }, 404);
    } catch (err) {
      return json({ error: String(err && err.message || err) }, 500);
    }
  },
};

// Výchozí seznam pro /api/reset (pos = pořadí v řadě).
const RAW = [
  ['left',1,'Scala','45853',''],['left',1,'Karoq sportline','20818',''],
  ['left',2,'Kodiaq sportline','20181','22'],['left',2,'Kodiaq sportline','11768','','Chybí číslo klíče'],
  ['left',3,'Scala dynamic','43026',''],['left',3,'Kodiaq ex','27869',''],
  ['left',8,'Scala dynamic','45864',''],
  ['left',9,'Scala monte carlo','45808',''],
  ['left',10,'Kodiaq sportline','27772','112'],['left',10,'Kodiaq ex','28067','123'],
  ['left',10,'Kodiaq rs','27961','110'],['left',10,'Kodiaq ex','27803','101'],
  ['right',1,'Scala','44404','63'],['right',1,'Fabia','24817','6'],
  ['right',1,'Karoq bílý','76926','56'],['right',1,'Fabia','10179','75','📸 Focení'],
  ['right',2,'Kodiaq ex','27843','102'],['right',2,'Kodiaq ex','27857','111'],
  ['right',2,'Kamiq monte carlo','44921','26'],['right',2,'Kamiq monte carlo','44686','62'],
  ['right',3,'Kamiq monte carlo','44728','120'],['right',3,'Kamiq monte carlo','44771','92'],
  ['right',3,'Fabia','29033','7'],['right',3,'Karoq sportline','75797','37'],
  ['right',4,'Kodiaq sportline','11313','34'],['right',4,'Karoq sportline','76398','55'],
  ['right',4,'Karoq sportline','79901','97'],['right',4,'Karoq sportline','78809','74'],
  ['right',5,'Fabia monte carlo','29319','42'],['right',5,'Karoq sportline','67303','48'],
  ['right',5,'Karoq sportline','67838','78'],['right',5,'Karoq sportline','77808','50'],
  ['right',6,'Fabia monte carlo','11735','79'],['right',6,'Scala','39737','43'],
  ['right',6,'Fabia','33002','119'],['right',6,'Fabia','27982','','❗️ Pozor'],
  ['right',7,'Kamiq dynamic','45366','67'],['right',7,'Kamiq','45527','35'],
  ['right',7,'Kamiq','40737','43','📸 Focení'],['right',7,'Kamiq','40097','14','📸 Focení'],
  ['right',8,'Kamiq','41399','72','❗️ Pozor'],['right',8,'Fabia monte carlo','29974','70'],
  ['right',8,'Kamiq','40031','65','📸 Focení'],['right',8,'Fabia monte carlo','12091','33'],
  ['right',9,'Fabia monte carlo','30221','51'],['right',9,'Fabia monte carlo','29690','45'],
  ['right',9,'Karoq sportline','78310','40'],['right',9,'Karoq sportline','68351','10'],
  ['right',10,'Kamiq monte carlo','44715','132'],['right',10,'Kamiq monte carlo','42262','87'],
  ['right',10,'Kodiaq sportline','10883','16'],['right',10,'Kodiaq ex','12074','28'],
  ['right',11,'Kamiq monte carlo','44899','137'],['right',11,'Kamiq monte carlo','44748','116'],
  ['right',11,'Octavia','20910','20'],['right',11,'Fabia monte carlo','30078','52'],
  ['right',12,'Fabia monte carlo','29890','13'],['right',12,'Fabia monte carlo','29851','3'],
  ['right',12,'Enyaq','20373','8'],
  ['right',13,'Kamiq monte carlo','43796','9'],['right',13,'Kamiq monte carlo','43866','5'],
  ['right',14,'Kodiaq sportline','27488','6'],['right',14,'Kodiaq ex','27806','94'],
  ['right',14,'Kodiaq sportline','11928','117'],
];
const _posCount = {};
const SEED = RAW.map((r) => {
  const k = r[0] + '|' + r[1];
  const pos = (_posCount[k] = (_posCount[k] || 0)); _posCount[k]++;
  return { side: r[0], row: r[1], model: r[2], vin: r[3], key: r[4] || '', note: r[5] || '', pos };
});
