/* Parkoviště (cloud) — společná online data přes Worker API. Čistý JS. */
(function () {
  'use strict';

  var API = '/api';
  var CACHE_KEY = 'parking-cloud-cache-v1';
  var POLL_MS = 12000; // automatické obnovení kvůli změnám od ostatních

  var BRAND_COLORS = {
    scala: '#4f9dff', kodiaq: '#7c5cff', kamiq: '#36c98d',
    karoq: '#f2a33c', fabia: '#ef5d6b', octavia: '#23c4d6', enyaq: '#a0e84f'
  };
  var STATUS_LABEL = { parked: '', workshop: 'DÍLNA', received: 'NOVÝ', departure: 'ODJEZD' };

  var state = { vehicles: [], side: 'left', filter: 'all', query: '' };
  var editingId = null;
  var currentStatus = 'parked';
  var pollTimer = null;
  var isDragging = false;
  var suppressClick = false;

  // ---------- API ----------
  function api(path, opts) {
    setSync('busy', 'ukládám…');
    return fetch(API + path, Object.assign({ headers: { 'content-type': 'application/json' } }, opts))
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.status === 204 ? null : r.json();
      })
      .then(function (data) { setSync('ok', 'synchronizováno'); return data; })
      .catch(function (e) { setSync('err', 'offline – zkouším znovu'); throw e; });
  }

  function loadState(silent) {
    return fetch(API + '/state', { cache: 'no-store' })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (data) {
        state.vehicles = data.vehicles || [];
        try { localStorage.setItem(CACHE_KEY, JSON.stringify(state.vehicles)); } catch (e) {}
        setSync('ok', 'synchronizováno');
        render();
      })
      .catch(function () {
        if (!silent) {
          try {
            var c = JSON.parse(localStorage.getItem(CACHE_KEY) || '[]');
            state.vehicles = c; render();
          } catch (e) {}
        }
        setSync('err', 'offline');
      });
  }

  // ---------- Sync indicator ----------
  var syncEl, syncText;
  function setSync(cls, txt) {
    if (!syncEl) { syncEl = document.getElementById('syncStatus'); syncText = document.getElementById('syncText'); }
    if (!syncEl) return;
    syncEl.className = 'sync ' + cls;
    if (txt) syncText.textContent = txt;
  }

  // ---------- Helpers ----------
  function brandColor(model) {
    var m = (model || '').toLowerCase();
    for (var k in BRAND_COLORS) { if (m.indexOf(k) > -1) return BRAND_COLORS[k]; }
    return '#5a6b8c';
  }
  function rowsForSide(side) {
    var nums = {};
    state.vehicles.forEach(function (v) { if (v.side === side) nums[v.row] = true; });
    return Object.keys(nums).map(Number).sort(function (a, b) { return a - b; });
  }
  function matchesFilter(v) {
    if (state.filter !== 'all' && v.status !== state.filter) return false;
    if (state.query) {
      var q = state.query.toLowerCase();
      if ((v.model + ' ' + v.vin + ' ' + v.key + ' ' + v.note).toLowerCase().indexOf(q) === -1) return false;
    }
    return true;
  }
  function flagsFor(v) {
    var s = v.note || '', out = '';
    if (s.indexOf('📸') > -1) out += '📸';
    if (s.indexOf('❗') > -1 || s.indexOf('!') > -1) out += '⚠️';
    return out;
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }
  function getCss(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
  }
  function plur(n) { return n === 1 ? '' : (n >= 2 && n <= 4 ? 'y' : 'ů'); }

  // ---------- Rendering ----------
  function render() { renderStats(); renderRows(); }

  function renderStats() {
    var c = { all: 0, parked: 0, workshop: 0, received: 0, departure: 0 };
    state.vehicles.forEach(function (v) { c.all++; c[v.status] = (c[v.status] || 0) + 1; });
    var defs = [
      { key: 'all', label: 'Vše', color: '#8ea2c8' },
      { key: 'parked', label: 'Zaparkováno', color: getCss('--parked') },
      { key: 'workshop', label: 'Dílna', color: getCss('--workshop') },
      { key: 'received', label: 'Nově přijato', color: getCss('--received') },
      { key: 'departure', label: 'Odjezd', color: getCss('--departure') }
    ];
    document.getElementById('stats').innerHTML = defs.map(function (d) {
      return '<div class="chip ' + (state.filter === d.key ? 'active' : '') + '" data-filter="' + d.key + '">' +
        '<span class="dot" style="background:' + d.color + '"></span>' + d.label + ' <b>' + c[d.key] + '</b></div>';
    }).join('');
  }

  function renderRows() {
    var container = document.getElementById('rows');
    var rows = rowsForSide(state.side);
    var filtering = state.query || state.filter !== 'all';
    var hint = document.getElementById('dragHint');
    if (hint) hint.style.display = filtering ? 'none' : '';
    var html = '';
    rows.forEach(function (rn) {
      var all = state.vehicles.filter(function (v) { return v.side === state.side && v.row === rn; });
      var vis = all.filter(matchesFilter);
      if (filtering && vis.length === 0) return;
      html += '<div class="row-card"><div class="row-head">' +
        '<span class="rnum">' + rn + '</span>' +
        '<span class="rtitle">Řada ' + rn + '</span>' +
        '<span class="rcount">' + all.length + ' voz' + plur(all.length) + '</span>' +
        '<button class="row-del" data-delrow="' + rn + '" title="Smazat řadu">🗑</button></div>';
      html += '<div class="tiles" data-row="' + rn + '">';
      vis.forEach(function (v) { html += tileHTML(v); });
      html += '<button class="add-tile" data-addto="' + rn + '" title="Přidat vozidlo">+</button>';
      html += '</div></div>';
    });
    if (rows.length === 0) html += '<div class="empty">Žádné řady na této straně.<br>Přidej první řadu níže.</div>';
    html += '<button class="add-row" id="addRow">+ Přidat řadu</button>';
    container.innerHTML = html;
  }

  function tileHTML(v) {
    var keyHtml = v.key ? '<div class="key">🔑 ' + esc(v.key) + '</div>' : '<div class="key nokey">🔑 —</div>';
    var badge = STATUS_LABEL[v.status] ? '<div class="stat-badge">' + STATUS_LABEL[v.status] + '</div>' : '';
    var fl = flagsFor(v);
    return '<div class="tile ' + v.status + '" data-id="' + esc(v.id) + '">' +
      '<div class="brand-bar" style="background:' + brandColor(v.model) + '"></div>' + badge +
      '<div class="model">' + esc(v.model || '—') + '</div>' +
      '<div><div class="vin">' + esc(v.vin || '—') + '</div>' + keyHtml + '</div>' +
      (fl ? '<div class="flags">' + fl + '</div>' : '') + '</div>';
  }

  // ---------- Vehicle sheet ----------
  function openVehicle(id) {
    var v = state.vehicles.find(function (x) { return x.id === id; });
    if (!v) return;
    editingId = id;
    document.getElementById('vehTitle').textContent = v.model || 'Vozidlo';
    document.getElementById('vehSub').textContent = (v.side === 'left' ? 'Levá strana' : 'Pravá strana') + ' · Řada ' + v.row;
    document.getElementById('fModel').value = v.model || '';
    document.getElementById('fVin').value = v.vin || '';
    document.getElementById('fKey').value = v.key || '';
    document.getElementById('fNote').value = v.note || '';
    setStatusButtons(v.status);
    document.getElementById('fSide').value = v.side;
    populateRowSelect(v.side, v.row);
    document.getElementById('moveSection').style.display = '';
    document.getElementById('vehDelete').style.display = '';
    openOverlay('vehOverlay');
  }

  function openNew(rn) {
    editingId = null;
    document.getElementById('vehTitle').textContent = 'Nové vozidlo';
    document.getElementById('vehSub').textContent = (state.side === 'left' ? 'Levá strana' : 'Pravá strana') + ' · Řada ' + rn;
    document.getElementById('fModel').value = '';
    document.getElementById('fVin').value = '';
    document.getElementById('fKey').value = '';
    document.getElementById('fNote').value = '';
    setStatusButtons('received');
    document.getElementById('moveSection').style.display = 'none';
    document.getElementById('vehDelete').style.display = 'none';
    document.getElementById('vehSheet').dataset.newrow = rn;
    openOverlay('vehOverlay');
  }

  // Naplní výběr řady pro danou stranu (existující řady + možnost nové).
  function populateRowSelect(side, selected) {
    var rows = rowsForSide(side);
    var html = rows.map(function (rn) { return '<option value="' + rn + '">Řada ' + rn + '</option>'; }).join('');
    html += '<option value="__new">+ Nová řada…</option>';
    var sel = document.getElementById('fRow');
    sel.innerHTML = html;
    if (selected != null && rows.indexOf(Number(selected)) > -1) sel.value = String(selected);
  }

  // Pořadí cílové řady s vozem přidaným na konec (pro /api/move).
  function destinationOrder(side, row, movedId) {
    var ids = state.vehicles
      .filter(function (v) { return v.side === side && v.row === row && v.id !== movedId; })
      .sort(function (a, b) { return a.pos - b.pos; })
      .map(function (v) { return v.id; });
    ids.push(movedId);
    return ids;
  }

  function setStatusButtons(status) {
    currentStatus = status;
    document.querySelectorAll('#statusBtns button').forEach(function (b) {
      var s = b.getAttribute('data-status');
      b.className = (s === status) ? 'sel-' + s : '';
    });
  }

  function saveVehicle() {
    var payload = {
      model: document.getElementById('fModel').value.trim(),
      vin: document.getElementById('fVin').value.trim(),
      key: document.getElementById('fKey').value.trim(),
      note: document.getElementById('fNote').value.trim(),
      status: currentStatus
    };
    if (!payload.model && !payload.vin) { toast('Vyplň model nebo VIN'); return; }

    var req;
    if (editingId) {
      var id = editingId;
      var v = state.vehicles.find(function (x) { return x.id === id; });
      // cíl přesunu z výběru Strana/Řada
      var destSide = document.getElementById('fSide').value === 'right' ? 'right' : 'left';
      var rowVal = document.getElementById('fRow').value;
      var destRow;
      if (rowVal === '__new') {
        var rowsThere = rowsForSide(destSide);
        var next = rowsThere.length ? Math.max.apply(null, rowsThere) + 1 : 1;
        var input = prompt('Číslo nové řady:', String(next));
        if (input === null) return;
        destRow = parseInt(input, 10);
        if (isNaN(destRow) || destRow < 1) { toast('Neplatné číslo řady'); return; }
      } else {
        destRow = parseInt(rowVal, 10);
      }
      var needMove = v && (destSide !== v.side || destRow !== v.row);
      req = api('/vehicles/' + encodeURIComponent(id), { method: 'PUT', body: JSON.stringify(payload) })
        .then(function () {
          if (needMove) {
            var order = destinationOrder(destSide, destRow, id);
            return api('/move', { method: 'POST', body: JSON.stringify({ side: destSide, row: destRow, order: order }) });
          }
        });
    } else {
      payload.side = state.side;
      payload.row = parseInt(document.getElementById('vehSheet').dataset.newrow, 10);
      req = api('/vehicles', { method: 'POST', body: JSON.stringify(payload) });
    }
    closeOverlay('vehOverlay');
    req.then(function () { return loadState(true); }).then(function () { toast('Uloženo ✓'); })
       .catch(function () { toast('Nepodařilo se uložit'); });
  }

  function deleteVehicle() {
    if (!editingId) return;
    if (!confirm('Smazat toto vozidlo? (pro všechny)')) return;
    var id = editingId;
    closeOverlay('vehOverlay');
    api('/vehicles/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function () { return loadState(true); }).then(function () { toast('Smazáno'); })
      .catch(function () { toast('Nepodařilo se smazat'); });
  }

  function addRow() {
    var rows = rowsForSide(state.side);
    var next = rows.length ? Math.max.apply(null, rows) + 1 : 1;
    var input = prompt('Číslo nové řady:', String(next));
    if (input === null) return;
    var rn = parseInt(input, 10);
    if (isNaN(rn) || rn < 1) { toast('Neplatné číslo řady'); return; }
    if (rows.indexOf(rn) > -1) { toast('Řada ' + rn + ' už existuje'); return; }
    openNew(rn);
  }

  function deleteRow(rn) {
    var inRow = state.vehicles.filter(function (v) { return v.side === state.side && v.row === rn; });
    if (inRow.length && !confirm('Smazat řadu ' + rn + ' včetně ' + inRow.length + ' vozidel? (pro všechny)')) return;
    api('/rows/delete', { method: 'POST', body: JSON.stringify({ side: state.side, row: rn }) })
      .then(function () { return loadState(true); }).then(function () { toast('Řada ' + rn + ' smazána'); })
      .catch(function () { toast('Nepodařilo se smazat řadu'); });
  }

  // ---------- Menu ----------
  function exportData() {
    var blob = new Blob([JSON.stringify(state.vehicles, null, 2)], { type: 'application/json' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url; a.download = 'parkoviste-' + new Date().toISOString().slice(0, 10) + '.json';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    closeOverlay('menuOverlay'); toast('Záloha stažena');
  }
  function shareLink() {
    var url = location.origin + location.pathname;
    if (navigator.share) { navigator.share({ title: 'Parkoviště', text: 'Správa parkování', url: url }).catch(function () {}); }
    else if (navigator.clipboard) { navigator.clipboard.writeText(url); toast('Odkaz zkopírován'); }
    else { prompt('Odkaz na appku:', url); }
    closeOverlay('menuOverlay');
  }
  function resetData() {
    if (!confirm('Obnovit původní seznam z TXT pro VŠECHNY uživatele? Přepíše všechny změny.')) return;
    api('/reset', { method: 'POST', body: '{}' })
      .then(function () { return loadState(true); }).then(function () { closeOverlay('menuOverlay'); toast('Obnoveno'); })
      .catch(function () { toast('Nepodařilo se obnovit'); });
  }

  // ---------- Overlays / toast ----------
  function openOverlay(id) { document.getElementById(id).classList.add('open'); }
  function closeOverlay(id) { document.getElementById(id).classList.remove('open'); }
  var toastTimer;
  function toast(msg) {
    var t = document.getElementById('toast');
    t.textContent = msg; t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove('show'); }, 1800);
  }

  // ---------- Drag & drop (dotyk + myš) ----------
  var drag = null;
  var DRAG_HOLD_MS = 220, MOVE_CANCEL = 9;

  function onPointerDown(e) {
    if (e.button != null && e.button !== 0) return;
    if (state.filter !== 'all' || state.query) return; // přesun jen v nefiltrovaném zobrazení
    var tile = e.target.closest('.tile');
    if (!tile) return;
    drag = {
      id: tile.getAttribute('data-id'), el: tile,
      startX: e.clientX, startY: e.clientY, pointerId: e.pointerId,
      active: false, moved: false, longTimer: null
    };
    drag.longTimer = setTimeout(function () { startDrag(); }, DRAG_HOLD_MS);
    window.addEventListener('pointermove', onPointerMove, { passive: false });
    window.addEventListener('pointerup', onPointerUp);
    window.addEventListener('pointercancel', onPointerUp);
  }

  function startDrag() {
    if (!drag) return;
    drag.active = true; isDragging = true;
    drag.lastX = drag.startX; drag.lastY = drag.startY;
    drag.scrollDir = 0; drag.scrollRaf = null;
    var tile = drag.el, rect = tile.getBoundingClientRect();
    drag.offX = drag.startX - rect.left; drag.offY = drag.startY - rect.top;
    var clone = tile.cloneNode(true);
    clone.classList.add('drag-clone');
    clone.style.width = rect.width + 'px'; clone.style.height = rect.height + 'px';
    document.body.appendChild(clone);
    drag.clone = clone;
    var ph = document.createElement('div');
    ph.className = 'tile-placeholder';
    tile.parentNode.insertBefore(ph, tile);
    drag.placeholder = ph;
    tile.style.display = 'none';
    document.body.classList.add('dragging');
    if (navigator.vibrate) { try { navigator.vibrate(15); } catch (e) {} }
    moveClone(drag.startX, drag.startY);
  }

  function moveClone(x, y) {
    if (!drag.clone) return;
    drag.clone.style.left = (x - drag.offX) + 'px';
    drag.clone.style.top = (y - drag.offY) + 'px';
  }

  function onPointerMove(e) {
    if (!drag) return;
    if (!drag.active) {
      if (Math.abs(e.clientX - drag.startX) > MOVE_CANCEL || Math.abs(e.clientY - drag.startY) > MOVE_CANCEL) {
        clearTimeout(drag.longTimer); cleanupDrag(false);
      }
      return;
    }
    e.preventDefault();
    drag.moved = true;
    drag.lastX = e.clientX; drag.lastY = e.clientY;
    moveClone(e.clientX, e.clientY);
    updateDropTarget(e.clientX, e.clientY);
    updateAutoScroll(e.clientY);
  }

  // Najde místo pro placeholder pod daným bodem (dlaždice / prázdná část řady).
  function updateDropTarget(x, y) {
    if (!drag || !drag.clone) return;
    drag.clone.style.display = 'none';
    var under = document.elementFromPoint(x, y);
    drag.clone.style.display = '';
    if (!under) return;
    var overAdd = under.closest('.add-tile');
    if (overAdd && overAdd.parentNode.classList.contains('tiles')) {
      overAdd.parentNode.insertBefore(drag.placeholder, overAdd); return;
    }
    var overTile = under.closest('.tile');
    if (overTile && overTile !== drag.el && overTile.parentNode.classList.contains('tiles')) {
      var r = overTile.getBoundingClientRect();
      var after = (y > r.top + r.height / 2) ||
                  (Math.abs(y - (r.top + r.height / 2)) < 4 && x > r.left + r.width / 2);
      overTile.parentNode.insertBefore(drag.placeholder, after ? overTile.nextSibling : overTile);
      return;
    }
    var overTiles = under.closest('.tiles');
    if (overTiles) {
      var addBtn = overTiles.querySelector('.add-tile');
      if (addBtn) overTiles.insertBefore(drag.placeholder, addBtn);
      else overTiles.appendChild(drag.placeholder);
    }
  }

  // Auto-rolování, když je prst u horního/dolního okraje (umožní přesun do vzdálené řady).
  var EDGE = 90;
  function updateAutoScroll(y) {
    var dir = 0;
    if (y < EDGE) dir = -1;
    else if (y > window.innerHeight - EDGE) dir = 1;
    drag.scrollDir = dir;
    if (dir !== 0 && !drag.scrollRaf) autoScrollTick();
  }
  function autoScrollTick() {
    if (!drag || !drag.active || !drag.scrollDir) { if (drag) drag.scrollRaf = null; return; }
    var before = window.scrollY;
    var speed = drag.scrollDir < 0
      ? Math.min(18, (EDGE - drag.lastY) / 5 + 4)
      : Math.min(18, (drag.lastY - (window.innerHeight - EDGE)) / 5 + 4);
    window.scrollBy(0, drag.scrollDir * speed);
    if (window.scrollY !== before) { moveClone(drag.lastX, drag.lastY); updateDropTarget(drag.lastX, drag.lastY); }
    drag.scrollRaf = requestAnimationFrame(autoScrollTick);
  }

  function onPointerUp() {
    if (!drag) return;
    clearTimeout(drag.longTimer);
    if (!drag.active) { cleanupDrag(false); return; }

    var ph = drag.placeholder, container = ph.parentNode, tile = drag.el;
    container.insertBefore(tile, ph);
    tile.style.display = '';
    ph.remove();
    if (drag.clone) drag.clone.remove();
    document.body.classList.remove('dragging');

    var destRow = parseInt(container.getAttribute('data-row'), 10);
    var ids = [];
    container.querySelectorAll('.tile').forEach(function (t) { ids.push(t.getAttribute('data-id')); });
    var moved = drag.moved, movedId = drag.id;

    cleanupDrag(true);

    if (moved) {
      api('/move', { method: 'POST', body: JSON.stringify({ side: state.side, row: destRow, order: ids }) })
        .then(function () { return loadState(true); })
        .then(function () { toast('Přesunuto ✓'); })
        .catch(function () { toast('Přesun se nepovedl'); loadState(true); });
    } else {
      openVehicle(movedId); // podržení bez tažení = otevřít detail
    }
  }

  function cleanupDrag(didDrag) {
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    window.removeEventListener('pointercancel', onPointerUp);
    isDragging = false;
    if (didDrag) { suppressClick = true; setTimeout(function () { suppressClick = false; }, 60); }
    drag = null;
  }

  // ---------- Polling ----------
  function startPolling() {
    stopPolling();
    pollTimer = setInterval(function () {
      if (document.querySelector('.overlay.open')) return; // nepřepisuj během editace
      if (isDragging) return; // nepřepisuj během přetahování
      if (document.hidden) return;
      loadState(true);
    }, POLL_MS);
  }
  function stopPolling() { if (pollTimer) clearInterval(pollTimer); }
  document.addEventListener('visibilitychange', function () { if (!document.hidden) loadState(true); });

  // ---------- Events ----------
  function bind() {
    document.getElementById('sideTabs').addEventListener('click', function (e) {
      var b = e.target.closest('button'); if (!b) return;
      state.side = b.getAttribute('data-side');
      document.querySelectorAll('#sideTabs button').forEach(function (x) { x.classList.remove('active'); });
      b.classList.add('active'); renderRows();
    });
    document.getElementById('btnSide').addEventListener('click', function () {
      state.side = state.side === 'left' ? 'right' : 'left';
      document.querySelectorAll('#sideTabs button').forEach(function (x) {
        x.classList.toggle('active', x.getAttribute('data-side') === state.side);
      });
      renderRows();
    });
    document.getElementById('stats').addEventListener('click', function (e) {
      var c = e.target.closest('.chip'); if (!c) return;
      state.filter = c.getAttribute('data-filter'); render();
    });
    document.getElementById('search').addEventListener('input', function (e) {
      state.query = e.target.value.trim(); renderRows();
    });
    document.getElementById('rows').addEventListener('pointerdown', onPointerDown);
    document.getElementById('rows').addEventListener('click', function (e) {
      if (suppressClick) return;
      var tile = e.target.closest('.tile');
      if (tile) { openVehicle(tile.getAttribute('data-id')); return; }
      var add = e.target.closest('.add-tile');
      if (add) { openNew(parseInt(add.getAttribute('data-addto'), 10)); return; }
      var delr = e.target.closest('[data-delrow]');
      if (delr) { deleteRow(parseInt(delr.getAttribute('data-delrow'), 10)); return; }
      if (e.target.closest('#addRow')) { addRow(); return; }
    });
    document.getElementById('statusBtns').addEventListener('click', function (e) {
      var b = e.target.closest('button'); if (!b) return;
      setStatusButtons(b.getAttribute('data-status'));
    });
    document.getElementById('fSide').addEventListener('change', function (e) {
      populateRowSelect(e.target.value);
    });
    document.getElementById('vehSave').addEventListener('click', saveVehicle);
    document.getElementById('vehDelete').addEventListener('click', deleteVehicle);
    document.getElementById('vehCancel').addEventListener('click', function () { closeOverlay('vehOverlay'); });

    document.getElementById('btnMenu').addEventListener('click', function () { openOverlay('menuOverlay'); });
    document.getElementById('miRefresh').addEventListener('click', function () { loadState(false); closeOverlay('menuOverlay'); toast('Načítám…'); });
    document.getElementById('miShare').addEventListener('click', shareLink);
    document.getElementById('miExport').addEventListener('click', exportData);
    document.getElementById('miReset').addEventListener('click', resetData);

    ['vehOverlay', 'menuOverlay'].forEach(function (id) {
      document.getElementById(id).addEventListener('click', function (e) { if (e.target === this) closeOverlay(id); });
    });
  }

  // ---------- Init ----------
  bind();
  loadState(false).then(startPolling);
})();
