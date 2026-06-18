/* Parkoviště — správa vozidel. Čistý JS, bez frameworků. Data v localStorage. */
(function () {
  'use strict';

  var STORAGE_KEY = 'parking-app-v1';
  var BRAND_COLORS = {
    scala: '#4f9dff', kodiaq: '#7c5cff', kamiq: '#36c98d',
    karoq: '#f2a33c', fabia: '#ef5d6b', octavia: '#23c4d6', enyaq: '#a0e84f'
  };
  var STATUS_LABEL = { parked: '', workshop: 'DÍLNA', received: 'NOVÝ' };

  var state = { vehicles: [], side: 'left', filter: 'all', query: '' };
  var editingId = null;

  // ---------- Persistence ----------
  function uid() { return 'v' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }

  function load() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      if (raw) { state.vehicles = JSON.parse(raw); return; }
    } catch (e) {}
    seedFromDefault();
  }

  function seedFromDefault() {
    state.vehicles = (window.SEED_DATA || []).map(function (v) {
      return { id: uid(), side: v.side, row: v.row, model: v.model, vin: v.vin,
               key: v.key || '', note: v.note || '', status: 'parked' };
    });
    save();
  }

  function save() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state.vehicles)); } catch (e) {}
  }

  // ---------- Helpers ----------
  function brandColor(model) {
    var m = (model || '').toLowerCase();
    for (var k in BRAND_COLORS) { if (m.indexOf(k) === 0 || m.indexOf(k) > -1) return BRAND_COLORS[k]; }
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
      var hay = (v.model + ' ' + v.vin + ' ' + v.key + ' ' + v.note).toLowerCase();
      if (hay.indexOf(q) === -1) return false;
    }
    return true;
  }
  function flagsFor(v) {
    var s = (v.note || '');
    var out = '';
    if (s.indexOf('📸') > -1) out += '📸';
    if (s.indexOf('❗') > -1 || s.indexOf('!') > -1) out += '⚠️';
    return out;
  }

  // ---------- Rendering ----------
  function render() {
    renderStats();
    renderRows();
  }

  function renderStats() {
    var c = { all: 0, parked: 0, workshop: 0, received: 0 };
    state.vehicles.forEach(function (v) { c.all++; c[v.status]++; });
    var defs = [
      { key: 'all', label: 'Vše', color: '#8ea2c8' },
      { key: 'parked', label: 'Zaparkováno', color: getCss('--parked') },
      { key: 'workshop', label: 'Dílna', color: getCss('--workshop') },
      { key: 'received', label: 'Nově přijato', color: getCss('--received') }
    ];
    var html = defs.map(function (d) {
      return '<div class="chip ' + (state.filter === d.key ? 'active' : '') + '" data-filter="' + d.key + '">' +
        '<span class="dot" style="background:' + d.color + '"></span>' + d.label + ' <b>' + c[d.key] + '</b></div>';
    }).join('');
    document.getElementById('stats').innerHTML = html;
  }

  function getCss(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
  }

  function renderRows() {
    var container = document.getElementById('rows');
    var rows = rowsForSide(state.side);
    var html = '';

    rows.forEach(function (rn) {
      var all = state.vehicles.filter(function (v) { return v.side === state.side && v.row === rn; });
      var vis = all.filter(matchesFilter);
      if ((state.query || state.filter !== 'all') && vis.length === 0) return; // skip empty rows when filtering

      html += '<div class="row-card">';
      html += '<div class="row-head">' +
        '<span class="rnum">' + rn + '</span>' +
        '<span class="rtitle">Řada ' + rn + '</span>' +
        '<span class="rcount">' + all.length + ' voz' + plur(all.length) + '</span>' +
        '<button class="row-del" data-delrow="' + rn + '" title="Smazat řadu">🗑</button>' +
      '</div>';
      html += '<div class="tiles">';
      vis.forEach(function (v) { html += tileHTML(v); });
      html += '<button class="add-tile" data-addto="' + rn + '" title="Přidat vozidlo">+</button>';
      html += '</div></div>';
    });

    if (rows.length === 0) {
      html += '<div class="empty">Žádné řady na této straně.<br>Přidej první řadu níže.</div>';
    }
    html += '<button class="add-row" id="addRow">+ Přidat řadu</button>';
    container.innerHTML = html;
  }

  function plur(n) { return n === 1 ? '' : (n >= 2 && n <= 4 ? 'y' : 'ů'); }

  function tileHTML(v) {
    var cls = 'tile ' + v.status;
    var keyHtml = v.key
      ? '<div class="key">🔑 ' + esc(v.key) + '</div>'
      : '<div class="key nokey">🔑 —</div>';
    var badge = STATUS_LABEL[v.status] ? '<div class="stat-badge">' + STATUS_LABEL[v.status] + '</div>' : '';
    var fl = flagsFor(v);
    return '<div class="' + cls + '" data-id="' + v.id + '">' +
      '<div class="brand-bar" style="background:' + brandColor(v.model) + '"></div>' +
      badge +
      '<div class="model">' + esc(v.model || '—') + '</div>' +
      '<div>' +
        '<div class="vin">' + esc(v.vin || '—') + '</div>' +
        keyHtml +
      '</div>' +
      (fl ? '<div class="flags">' + fl + '</div>' : '') +
    '</div>';
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  // ---------- Vehicle sheet ----------
  function openVehicle(id) {
    var v = state.vehicles.find(function (x) { return x.id === id; });
    if (!v) return;
    editingId = id;
    document.getElementById('vehTitle').textContent = v.model || 'Vozidlo';
    document.getElementById('vehSub').textContent =
      (state.side === 'left' ? 'Levá strana' : 'Pravá strana') + ' · Řada ' + v.row;
    document.getElementById('fModel').value = v.model || '';
    document.getElementById('fVin').value = v.vin || '';
    document.getElementById('fKey').value = v.key || '';
    document.getElementById('fNote').value = v.note || '';
    setStatusButtons(v.status);
    document.getElementById('vehDelete').style.display = '';
    openOverlay('vehOverlay');
  }

  function openNew(rn) {
    editingId = null;
    document.getElementById('vehTitle').textContent = 'Nové vozidlo';
    document.getElementById('vehSub').textContent =
      (state.side === 'left' ? 'Levá strana' : 'Pravá strana') + ' · Řada ' + rn;
    document.getElementById('fModel').value = '';
    document.getElementById('fVin').value = '';
    document.getElementById('fKey').value = '';
    document.getElementById('fNote').value = '';
    setStatusButtons('received'); // nový vůz typicky "nově přijato"
    document.getElementById('vehDelete').style.display = 'none';
    document.getElementById('vehSheet').dataset.newrow = rn;
    openOverlay('vehOverlay');
  }

  var currentStatus = 'parked';
  function setStatusButtons(status) {
    currentStatus = status;
    var btns = document.querySelectorAll('#statusBtns button');
    btns.forEach(function (b) {
      var s = b.getAttribute('data-status');
      b.className = (s === status) ? 'sel-' + s : '';
    });
  }

  function saveVehicle() {
    var model = document.getElementById('fModel').value.trim();
    var vin = document.getElementById('fVin').value.trim();
    var key = document.getElementById('fKey').value.trim();
    var note = document.getElementById('fNote').value.trim();
    if (!model && !vin) { toast('Vyplň model nebo VIN'); return; }

    if (editingId) {
      var v = state.vehicles.find(function (x) { return x.id === editingId; });
      if (v) { v.model = model; v.vin = vin; v.key = key; v.note = note; v.status = currentStatus; }
    } else {
      var rn = parseInt(document.getElementById('vehSheet').dataset.newrow, 10);
      state.vehicles.push({ id: uid(), side: state.side, row: rn, model: model, vin: vin,
                            key: key, note: note, status: currentStatus });
    }
    save(); render(); closeOverlay('vehOverlay');
    toast('Uloženo ✓');
  }

  function deleteVehicle() {
    if (!editingId) return;
    if (!confirm('Smazat toto vozidlo?')) return;
    state.vehicles = state.vehicles.filter(function (x) { return x.id !== editingId; });
    save(); render(); closeOverlay('vehOverlay');
    toast('Smazáno');
  }

  // ---------- Rows ----------
  function addRow() {
    var rows = rowsForSide(state.side);
    var next = rows.length ? Math.max.apply(null, rows) + 1 : 1;
    var input = prompt('Číslo nové řady:', String(next));
    if (input === null) return;
    var rn = parseInt(input, 10);
    if (isNaN(rn) || rn < 1) { toast('Neplatné číslo řady'); return; }
    if (rows.indexOf(rn) > -1) { toast('Řada ' + rn + ' už existuje'); return; }
    openNew(rn); // založíme řadu přidáním prvního vozu
  }

  function deleteRow(rn) {
    var inRow = state.vehicles.filter(function (v) { return v.side === state.side && v.row === rn; });
    if (inRow.length && !confirm('Smazat řadu ' + rn + ' včetně ' + inRow.length + ' vozidel?')) return;
    state.vehicles = state.vehicles.filter(function (v) { return !(v.side === state.side && v.row === rn); });
    save(); render(); toast('Řada ' + rn + ' smazána');
  }

  // ---------- Import / Export ----------
  function exportData() {
    var blob = new Blob([JSON.stringify(state.vehicles, null, 2)], { type: 'application/json' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = 'parkoviste-' + new Date().toISOString().slice(0, 10) + '.json';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    closeOverlay('menuOverlay'); toast('Záloha stažena');
  }

  function importData(file) {
    var reader = new FileReader();
    reader.onload = function () {
      try {
        var data = JSON.parse(reader.result);
        if (!Array.isArray(data)) throw new Error('bad');
        state.vehicles = data.map(function (v) {
          return { id: v.id || uid(), side: v.side === 'left' ? 'left' : 'right',
                   row: Number(v.row) || 1, model: v.model || '', vin: v.vin || '',
                   key: v.key || '', note: v.note || '',
                   status: ['parked', 'workshop', 'received'].indexOf(v.status) > -1 ? v.status : 'parked' };
        });
        save(); render(); closeOverlay('menuOverlay'); toast('Data importována ✓');
      } catch (e) { toast('Soubor se nepodařilo načíst'); }
    };
    reader.readAsText(file);
  }

  function shareData() {
    var text = JSON.stringify(state.vehicles);
    if (navigator.share) {
      var file;
      try {
        file = new File([JSON.stringify(state.vehicles, null, 2)],
          'parkoviste.json', { type: 'application/json' });
      } catch (e) {}
      if (file && navigator.canShare && navigator.canShare({ files: [file] })) {
        navigator.share({ files: [file], title: 'Parkoviště — záloha' }).catch(function () {});
      } else {
        navigator.share({ title: 'Parkoviště', text: text }).catch(function () {});
      }
    } else {
      exportData();
    }
    closeOverlay('menuOverlay');
  }

  function resetData() {
    if (!confirm('Obnovit původní seznam z TXT? Tím se přepíšou všechny úpravy.')) return;
    seedFromDefault(); render(); closeOverlay('menuOverlay'); toast('Obnoveno');
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

  // ---------- Events ----------
  function bind() {
    // side tabs
    document.getElementById('sideTabs').addEventListener('click', function (e) {
      var b = e.target.closest('button'); if (!b) return;
      state.side = b.getAttribute('data-side');
      document.querySelectorAll('#sideTabs button').forEach(function (x) { x.classList.remove('active'); });
      b.classList.add('active');
      renderRows();
    });
    document.getElementById('btnSide').addEventListener('click', function () {
      state.side = state.side === 'left' ? 'right' : 'left';
      document.querySelectorAll('#sideTabs button').forEach(function (x) {
        x.classList.toggle('active', x.getAttribute('data-side') === state.side);
      });
      renderRows();
    });

    // stats filter
    document.getElementById('stats').addEventListener('click', function (e) {
      var c = e.target.closest('.chip'); if (!c) return;
      state.filter = c.getAttribute('data-filter');
      render();
    });

    // search
    document.getElementById('search').addEventListener('input', function (e) {
      state.query = e.target.value.trim();
      renderRows();
    });

    // rows container (delegation)
    document.getElementById('rows').addEventListener('click', function (e) {
      var tile = e.target.closest('.tile');
      if (tile) { openVehicle(tile.getAttribute('data-id')); return; }
      var add = e.target.closest('.add-tile');
      if (add) { openNew(parseInt(add.getAttribute('data-addto'), 10)); return; }
      var delr = e.target.closest('[data-delrow]');
      if (delr) { deleteRow(parseInt(delr.getAttribute('data-delrow'), 10)); return; }
      if (e.target.closest('#addRow')) { addRow(); return; }
    });

    // status buttons
    document.getElementById('statusBtns').addEventListener('click', function (e) {
      var b = e.target.closest('button'); if (!b) return;
      setStatusButtons(b.getAttribute('data-status'));
    });

    // vehicle sheet actions
    document.getElementById('vehSave').addEventListener('click', saveVehicle);
    document.getElementById('vehDelete').addEventListener('click', deleteVehicle);
    document.getElementById('vehCancel').addEventListener('click', function () { closeOverlay('vehOverlay'); });

    // menu
    document.getElementById('btnMenu').addEventListener('click', function () { openOverlay('menuOverlay'); });
    document.getElementById('miExport').addEventListener('click', exportData);
    document.getElementById('miShare').addEventListener('click', shareData);
    document.getElementById('miReset').addEventListener('click', resetData);
    document.getElementById('miImport').addEventListener('click', function () {
      document.getElementById('importFile').click();
    });
    document.getElementById('importFile').addEventListener('change', function (e) {
      if (e.target.files && e.target.files[0]) importData(e.target.files[0]);
      e.target.value = '';
    });

    // close overlays on backdrop tap
    ['vehOverlay', 'menuOverlay'].forEach(function (id) {
      document.getElementById(id).addEventListener('click', function (e) {
        if (e.target === this) closeOverlay(id);
      });
    });
  }

  // ---------- Init ----------
  load();
  bind();
  render();
})();
