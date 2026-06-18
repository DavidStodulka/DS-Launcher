/*
 * Výchozí seznam vozidel — vygenerováno ze souboru "Seznam aut.txt".
 * side: 'left' = Levá strana, 'right' = Pravá strana
 * row: číslo řady, model, vin (č. vozu), key (číslo klíče), note (poznámka)
 * Vlaječky z TXT: 📸 = focení, ❗️/! = pozor — uloženo do poznámky.
 */
window.SEED_DATA = [
  // ───────────── LEVÁ STRANA ─────────────
  { side:'left', row:1,  model:'Scala',                vin:'45853', key:'',    note:'' },
  { side:'left', row:1,  model:'Karoq sportline',      vin:'20818', key:'',    note:'' },

  { side:'left', row:2,  model:'Kodiaq sportline',     vin:'20181', key:'22',  note:'' },
  { side:'left', row:2,  model:'Kodiaq sportline',     vin:'11768', key:'',    note:'Chybí číslo klíče' },

  { side:'left', row:3,  model:'Scala dynamic',        vin:'43026', key:'',    note:'' },
  { side:'left', row:3,  model:'Kodiaq ex',            vin:'27869', key:'',    note:'' },

  { side:'left', row:8,  model:'Scala dynamic',        vin:'45864', key:'',    note:'' },

  { side:'left', row:9,  model:'Scala monte carlo',    vin:'45808', key:'',    note:'' },

  { side:'left', row:10, model:'Kodiaq sportline',     vin:'27772', key:'112', note:'' },
  { side:'left', row:10, model:'Kodiaq ex',            vin:'28067', key:'123', note:'' },
  { side:'left', row:10, model:'Kodiaq rs',            vin:'27961', key:'110', note:'' },
  { side:'left', row:10, model:'Kodiaq ex',            vin:'27803', key:'101', note:'' },

  // ───────────── PRAVÁ STRANA ─────────────
  { side:'right', row:1,  model:'Scala',               vin:'44404', key:'63',  note:'' },
  { side:'right', row:1,  model:'Fabia',               vin:'24817', key:'6',   note:'' },
  { side:'right', row:1,  model:'Karoq bílý',          vin:'76926', key:'56',  note:'' },
  { side:'right', row:1,  model:'Fabia',               vin:'10179', key:'75',  note:'📸 Focení' },

  { side:'right', row:2,  model:'Kodiaq ex',           vin:'27843', key:'102', note:'' },
  { side:'right', row:2,  model:'Kodiaq ex',           vin:'27857', key:'111', note:'' },
  { side:'right', row:2,  model:'Kamiq monte carlo',   vin:'44921', key:'26',  note:'' },
  { side:'right', row:2,  model:'Kamiq monte carlo',   vin:'44686', key:'62',  note:'' },

  { side:'right', row:3,  model:'Kamiq monte carlo',   vin:'44728', key:'120', note:'' },
  { side:'right', row:3,  model:'Kamiq monte carlo',   vin:'44771', key:'92',  note:'' },
  { side:'right', row:3,  model:'Fabia',               vin:'29033', key:'7',   note:'' },
  { side:'right', row:3,  model:'Karoq sportline',     vin:'75797', key:'37',  note:'' },

  { side:'right', row:4,  model:'Kodiaq sportline',    vin:'11313', key:'34',  note:'' },
  { side:'right', row:4,  model:'Karoq sportline',     vin:'76398', key:'55',  note:'' },
  { side:'right', row:4,  model:'Karoq sportline',     vin:'79901', key:'97',  note:'' },
  { side:'right', row:4,  model:'Karoq sportline',     vin:'78809', key:'74',  note:'' },

  { side:'right', row:5,  model:'Fabia monte carlo',   vin:'29319', key:'42',  note:'' },
  { side:'right', row:5,  model:'Karoq sportline',     vin:'67303', key:'48',  note:'' },
  { side:'right', row:5,  model:'Karoq sportline',     vin:'67838', key:'78',  note:'' },
  { side:'right', row:5,  model:'Karoq sportline',     vin:'77808', key:'50',  note:'' },

  { side:'right', row:6,  model:'Fabia monte carlo',   vin:'11735', key:'79',  note:'' },
  { side:'right', row:6,  model:'Scala',               vin:'39737', key:'43',  note:'' },
  { side:'right', row:6,  model:'Fabia',               vin:'33002', key:'119', note:'' },
  { side:'right', row:6,  model:'Fabia',               vin:'27982', key:'',    note:'❗️ Pozor' },

  { side:'right', row:7,  model:'Kamiq dynamic',       vin:'45366', key:'67',  note:'' },
  { side:'right', row:7,  model:'Kamiq',               vin:'45527', key:'35',  note:'' },
  { side:'right', row:7,  model:'Kamiq',               vin:'40737', key:'43',  note:'📸 Focení' },
  { side:'right', row:7,  model:'Kamiq',               vin:'40097', key:'14',  note:'📸 Focení' },

  { side:'right', row:8,  model:'Kamiq',               vin:'41399', key:'72',  note:'❗️ Pozor' },
  { side:'right', row:8,  model:'Fabia monte carlo',   vin:'29974', key:'70',  note:'' },
  { side:'right', row:8,  model:'Kamiq',               vin:'40031', key:'65',  note:'📸 Focení' },
  { side:'right', row:8,  model:'Fabia monte carlo',   vin:'12091', key:'33',  note:'' },

  { side:'right', row:9,  model:'Fabia monte carlo',   vin:'30221', key:'51',  note:'' },
  { side:'right', row:9,  model:'Fabia monte carlo',   vin:'29690', key:'45',  note:'' },
  { side:'right', row:9,  model:'Karoq sportline',     vin:'78310', key:'40',  note:'' },
  { side:'right', row:9,  model:'Karoq sportline',     vin:'68351', key:'10',  note:'' },

  { side:'right', row:10, model:'Kamiq monte carlo',   vin:'44715', key:'132', note:'' },
  { side:'right', row:10, model:'Kamiq monte carlo',   vin:'42262', key:'87',  note:'' },
  { side:'right', row:10, model:'Kodiaq sportline',    vin:'10883', key:'16',  note:'' },
  { side:'right', row:10, model:'Kodiaq ex',           vin:'12074', key:'28',  note:'' },

  { side:'right', row:11, model:'Kamiq monte carlo',   vin:'44899', key:'137', note:'' },
  { side:'right', row:11, model:'Kamiq monte carlo',   vin:'44748', key:'116', note:'' },
  { side:'right', row:11, model:'Octavia',             vin:'20910', key:'20',  note:'' },
  { side:'right', row:11, model:'Fabia monte carlo',   vin:'30078', key:'52',  note:'' },

  { side:'right', row:12, model:'Fabia monte carlo',   vin:'29890', key:'13',  note:'' },
  { side:'right', row:12, model:'Fabia monte carlo',   vin:'29851', key:'3',   note:'' },
  { side:'right', row:12, model:'Enyaq',               vin:'20373', key:'8',   note:'' },

  { side:'right', row:13, model:'Kamiq monte carlo',   vin:'43796', key:'9',   note:'' },
  { side:'right', row:13, model:'Kamiq monte carlo',   vin:'43866', key:'5',   note:'' },

  { side:'right', row:14, model:'Kodiaq sportline',    vin:'27488', key:'6',   note:'' },
  { side:'right', row:14, model:'Kodiaq ex',           vin:'27806', key:'94',  note:'' },
  { side:'right', row:14, model:'Kodiaq sportline',    vin:'11928', key:'117', note:'' },
];
