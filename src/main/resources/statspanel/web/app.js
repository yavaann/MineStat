'use strict';

/* =====================================================================
   Stats Panel — front
   ===================================================================== */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const nf = new Intl.NumberFormat('fr-FR');
const nf1 = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 });
const nf2 = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const STYLE_KEY = 'statspanel.style';

const state = {
  view: 'serveur',
  refresh: 5,
  timer: null,
  ui: { theme: 'auto', headingFont: 'pixel', density: 'confortable', showIcons: true, allowVisitorStyle: true, assetsReady: false, background: 'paysage' },
  me: { user: null, perms: [], lockedForVisitors: [] },
  style: null,
  lang: {},
  server: null,
  history: { points: [] },
  players: [],
  global: null,
  player: null,
  playerCat: 'minecraft:custom',
  playerFilter: '',
  invView: 'grille',
  admin: null,
  keys: {},
};

const can = p => state.me.perms.includes(p);
const isAdmin = () => state.me.user?.role === 'admin';

// ------------------------------------------------------------------ données de présentation

const CATEGORIES = [
  ['minecraft:custom', 'Général', 'writable_book'],
  ['minecraft:mined', 'Minés', 'diamond_pickaxe'],
  ['minecraft:crafted', 'Fabriqués', 'crafting_table'],
  ['minecraft:used', 'Utilisés', 'flint_and_steel'],
  ['minecraft:picked_up', 'Ramassés', 'hopper'],
  ['minecraft:dropped', 'Jetés', 'dropper'],
  ['minecraft:broken', 'Usés', 'anvil'],
  ['minecraft:killed', 'Tués', 'iron_sword'],
  ['minecraft:killed_by', 'Tué par', 'totem_of_undying'],
];

// Regroupement des stats générales
const CUSTOM_GROUPS = [
  ['Temps', 'clock', k => ['play_time', 'play_one_minute', 'total_world_time', 'time_since_death', 'time_since_rest', 'sneak_time'].includes(k)],
  ['Combat', 'iron_sword', k => /^(deaths|mob_kills|player_kills|damage_|raid_|target_hit)/.test(k)],
  ['Déplacements', 'leather_boots', k => k.endsWith('_one_cm') || k === 'jump'],
  ['Blocs et machines', 'crafting_table', k => /^(interact_with_|open_|inspect_|fill_cauldron|use_cauldron|play_noteblock|tune_noteblock|play_record|pot_flower|trigger_trapped_chest|bell_ring|clean_)/.test(k)],
  ['Vie quotidienne', 'bread', () => true],
];

const CUSTOM_FALLBACK = {
  play_time: 'Temps de jeu', total_world_time: 'Temps avec le monde ouvert', time_since_death: 'Depuis la dernière mort',
  time_since_rest: 'Depuis le dernier sommeil', sneak_time: 'Temps accroupi', deaths: 'Morts', mob_kills: 'Mobs tués',
  player_kills: 'Joueurs tués', jump: 'Sauts', leave_game: 'Déconnexions', damage_dealt: 'Dégâts infligés',
  damage_taken: 'Dégâts subis', walk_one_cm: 'Distance à pied', sprint_one_cm: 'Distance en sprint',
  swim_one_cm: 'Distance à la nage', fall_one_cm: 'Distance de chute', fly_one_cm: 'Distance en vol',
  aviate_one_cm: 'Distance en élytres', boat_one_cm: 'Distance en bateau', fish_caught: 'Poissons pêchés',
  animals_bred: 'Animaux reproduits', traded_with_villager: 'Échanges avec des villageois', enchant_item: 'Objets enchantés',
  sleep_in_bed: 'Nuits passées au lit', open_chest: 'Coffres ouverts', drop: 'Objets jetés',
};

const CUSTOM_ICONS = {
  play_time: 'clock', total_world_time: 'clock', time_since_death: 'recovery_compass', time_since_rest: 'white_bed',
  sneak_time: 'leather_leggings', deaths: 'totem_of_undying', mob_kills: 'iron_sword', player_kills: 'diamond_sword',
  jump: 'rabbit_foot', leave_game: 'oak_door', damage_dealt: 'netherite_sword', damage_dealt_absorbed: 'golden_apple',
  damage_dealt_resisted: 'iron_chestplate', damage_taken: 'iron_chestplate', damage_blocked_by_shield: 'shield',
  damage_absorbed: 'golden_apple', damage_resisted: 'potion', walk_one_cm: 'leather_boots', sprint_one_cm: 'sugar',
  crouch_one_cm: 'chainmail_boots', swim_one_cm: 'water_bucket', walk_on_water_one_cm: 'lily_pad',
  walk_under_water_one_cm: 'turtle_helmet', fall_one_cm: 'feather', climb_one_cm: 'ladder', fly_one_cm: 'firework_rocket',
  aviate_one_cm: 'elytra', boat_one_cm: 'oak_boat', minecart_one_cm: 'minecart', horse_one_cm: 'saddle',
  pig_one_cm: 'carrot_on_a_stick', strider_one_cm: 'warped_fungus_on_a_stick', happy_ghast_one_cm: 'snowball',
  drop: 'dropper', fish_caught: 'cod', animals_bred: 'wheat', traded_with_villager: 'emerald',
  talked_to_villager: 'book', enchant_item: 'enchanted_book', sleep_in_bed: 'red_bed', raid_win: 'ominous_bottle',
  raid_trigger: 'crossbow', bell_ring: 'bell', eat_cake_slice: 'cake', fill_cauldron: 'cauldron', use_cauldron: 'cauldron',
  clean_armor: 'leather_chestplate', clean_banner: 'white_banner', clean_shulker_box: 'shulker_box',
  play_noteblock: 'note_block', tune_noteblock: 'note_block', play_record: 'music_disc_cat', pot_flower: 'flower_pot',
  trigger_trapped_chest: 'trapped_chest', open_chest: 'chest', open_enderchest: 'ender_chest', open_shulker_box: 'shulker_box',
  open_barrel: 'barrel', inspect_dispenser: 'dispenser', inspect_dropper: 'dropper', inspect_hopper: 'hopper',
  interact_with_crafting_table: 'crafting_table', interact_with_furnace: 'furnace', interact_with_blast_furnace: 'blast_furnace',
  interact_with_smoker: 'smoker', interact_with_brewingstand: 'brewing_stand', interact_with_beacon: 'beacon',
  interact_with_anvil: 'anvil', interact_with_grindstone: 'grindstone', interact_with_lectern: 'lectern',
  interact_with_loom: 'loom', interact_with_stonecutter: 'stonecutter', interact_with_cartography_table: 'cartography_table',
  interact_with_smithing_table: 'smithing_table', interact_with_campfire: 'campfire', target_hit: 'target',
};

const SUMMARY_FIELDS = [
  ['playTime', 'Temps de jeu', v => duration(v), 'clock'],
  ['deaths', 'Morts', v => nf.format(v), 'totem_of_undying'],
  ['mobKills', 'Mobs tués', v => nf.format(v), 'iron_sword'],
  ['playerKills', 'Joueurs tués', v => nf.format(v), 'diamond_sword'],
  ['distanceCm', 'Distance parcourue', v => distance(v), 'leather_boots'],
  ['mined', 'Blocs minés', v => nf.format(v), 'diamond_pickaxe'],
  ['crafted', 'Objets fabriqués', v => nf.format(v), 'crafting_table'],
  ['used', 'Objets utilisés', v => nf.format(v), 'flint_and_steel'],
  ['jumps', 'Sauts', v => nf.format(v), 'rabbit_foot'],
  ['damageDealt', 'Dégâts infligés', v => hearts(v), 'netherite_sword'],
  ['damageTaken', 'Dégâts subis', v => hearts(v), 'iron_chestplate'],
  ['fishCaught', 'Poissons pêchés', v => nf.format(v), 'cod'],
  ['trades', 'Échanges villageois', v => nf.format(v), 'emerald'],
  ['animalsBred', 'Animaux reproduits', v => nf.format(v), 'wheat'],
];
const FIELD = Object.fromEntries(SUMMARY_FIELDS.map(f => [f[0], f]));
const BOARDS = ['playTime', 'mined', 'mobKills', 'deaths', 'distanceCm', 'playerKills', 'crafted', 'jumps', 'damageDealt'];
const TOTALS = ['playTime', 'mined', 'mobKills', 'deaths', 'distanceCm', 'crafted', 'jumps', 'fishCaught'];
const DIMENSIONS = {
  'minecraft:overworld': ['Overworld', 'grass_block'],
  'minecraft:the_nether': ['Nether', 'netherrack'],
  'minecraft:the_end': ['End', 'end_stone'],
};

// ------------------------------------------------------------------ formatage

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function duration(ticks) {
  let s = Math.floor(ticks / 20);
  const d = Math.floor(s / 86400); s -= d * 86400;
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60);
  if (d > 0) return `${d} j ${h} h`;
  if (h > 0) return `${h} h ${String(m).padStart(2, '0')}`;
  if (m > 0) return `${m} min`;
  return `${s} s`;
}
const durationMs = ms => duration(ms / 50);
const distance = cm => (cm / 100 >= 1000 ? `${nf1.format(cm / 100000)} km` : `${nf.format(Math.round(cm / 100))} m`);
const hearts = v => `${nf1.format(v / 20)} ♥`;
const pct = v => `${nf.format(Math.round(v * 100))} %`;
function bytes(b) {
  if (b == null || b < 0) return '–';
  const u = ['o', 'Ko', 'Mo', 'Go', 'To'];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return `${i >= 3 ? nf2.format(b) : nf.format(Math.round(b))} ${u[i]}`;
}
function compact(n) {
  if (n < 1000) return String(n);
  if (n < 1e6) return `${nf1.format(n / 1000)}k`;
  return `${nf1.format(n / 1e6)}M`;
}
function ago(ts) {
  const s = Math.max(0, (Date.now() - ts) / 1000);
  if (s < 90) return 'à l’instant';
  if (s < 3600) return `il y a ${Math.round(s / 60)} min`;
  if (s < 86400) return `il y a ${Math.round(s / 3600)} h`;
  if (s < 86400 * 60) return `il y a ${Math.round(s / 86400)} j`;
  return `le ${new Date(ts).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' })}`;
}
const bare = id => String(id).replace(/^minecraft:/, '');
function pretty(id) {
  const t = bare(id).replace(/[_:.]/g, ' ');
  return t.charAt(0).toUpperCase() + t.slice(1);
}
function nameOf(cat, id) {
  const k = bare(id);
  const L = state.lang;
  if (cat === 'minecraft:custom') return L[`stat.minecraft.${k}`] || CUSTOM_FALLBACK[k] || pretty(k);
  if (cat === 'minecraft:killed' || cat === 'minecraft:killed_by') return L[`entity.minecraft.${k}`] || pretty(k);
  return L[`block.minecraft.${k}`] || L[`item.minecraft.${k}`] || pretty(k);
}
function customValue(id, v) {
  const k = bare(id);
  if (k.endsWith('_one_cm')) return distance(v);
  if (['play_time', 'play_one_minute', 'total_world_time', 'time_since_death', 'time_since_rest', 'sneak_time'].includes(k)) return duration(v);
  if (k.startsWith('damage_')) return hearts(v);
  return nf.format(v);
}
function level(v, good, mid, inverted = false) {
  if (!inverted) return v >= good ? 'good' : v >= mid ? 'mid' : 'bad';
  return v <= good ? 'good' : v <= mid ? 'mid' : 'bad';
}
const displayName = p => p.name || `${p.uuid.slice(0, 8)}…`;

// ------------------------------------------------------------------ textures et icônes (dessinées dans le navigateur)

const recipeCache = new Map();
const recipeWaiters = new Map();
const pendingRecipes = new Set();
let recipeTimer = null;
const iconCache = new Map();
const texCache = new Map();

function icon(kind, id, size = 32, label = '') {
  const k = bare(id);
  const letter = esc((label || k).charAt(0).toUpperCase());
  return `<span class="ico" style="--s:${size}px" data-icon="${kind}:${esc(k)}" data-l="${letter}"></span>`;
}
function iconFor(cat, id, size, label) {
  if (cat === 'minecraft:custom') {
    const it = CUSTOM_ICONS[bare(id)];
    return it ? icon('item', it, size, label) : `<span class="ico miss" style="--s:${size}px" data-l="${esc((label || '?').charAt(0))}"></span>`;
  }
  if (cat === 'minecraft:killed' || cat === 'minecraft:killed_by') return icon('entity', id, size, label);
  return icon('item', id, size, label);
}

function requestRecipe(key) {
  if (recipeCache.has(key)) return Promise.resolve(recipeCache.get(key));
  return new Promise(resolve => {
    if (!recipeWaiters.has(key)) recipeWaiters.set(key, []);
    recipeWaiters.get(key).push(resolve);
    pendingRecipes.add(key);
    if (!recipeTimer) recipeTimer = setTimeout(flushRecipes, 15);
  });
}

async function flushRecipes() {
  recipeTimer = null;
  const keys = [...pendingRecipes];
  pendingRecipes.clear();
  for (let i = 0; i < keys.length; i += 120) {
    const chunk = keys.slice(i, i + 120);
    let data = null;
    try {
      const r = await fetch(`api/iconinfo?k=${encodeURIComponent(chunk.join(','))}`);
      if (r.ok) data = await r.json();
    } catch { /* réseau */ }
    for (const k of chunk) {
      const val = data ? (data[k] ?? null) : null;
      if (data) recipeCache.set(k, val);
      (recipeWaiters.get(k) || []).forEach(fn => fn(val));
      recipeWaiters.delete(k);
    }
  }
}

function loadTex(src) {
  if (!src) return Promise.resolve(null);
  if (!texCache.has(src)) {
    texCache.set(src, new Promise(resolve => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => resolve(null);
      img.src = `api/tex/${src}.png`;
    }));
  }
  return texCache.get(src);
}

function square(img) {
  const s = Math.min(img.naturalWidth || img.width, img.naturalHeight || img.height);
  const c = document.createElement('canvas');
  c.width = c.height = s;
  const g = c.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.drawImage(img, 0, 0, s, s, 0, 0, s, s);
  return c;
}

function mapPixels(c, fn) {
  const g = c.getContext('2d');
  const d = g.getImageData(0, 0, c.width, c.height);
  const a = d.data;
  for (let i = 0; i < a.length; i += 4) fn(a, i);
  g.putImageData(d, 0, 0);
  return c;
}
function hexRgb(hex) {
  const n = parseInt(hex.replace('#', ''), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function tint(c, hex) {
  if (!c || !hex) return c;
  const [r, g, b] = hexRgb(hex);
  return mapPixels(c, (a, i) => { a[i] = a[i] * r / 255; a[i + 1] = a[i + 1] * g / 255; a[i + 2] = a[i + 2] * b / 255; });
}
function shade(c, f) { return mapPixels(c, (a, i) => { a[i] *= f; a[i + 1] *= f; a[i + 2] *= f; }); }
function transparency(c) {
  const a = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
  let clear = 0;
  for (let i = 3; i < a.length; i += 4) if (a[i] < 16) clear++;
  return clear / (a.length / 4);
}
function overlayOn(base, ov) {
  if (!ov) return base;
  const g = base.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.drawImage(ov, 0, 0, base.width, base.height);
  return base;
}

// ---- Modèles 3D simplifiés, dessinés en isométrique comme dans l'inventaire ----
// Coordonnées en pixels de bloc (0–16), comme dans les modèles du jeu. Les boîtes sont dessinées dans l'ordre.
const box = (f, t, tex = {}) => ({ f, t, ...tex });
const anvil = top => ({ tex: 'anvil', boxes: [
  box([2, 0, 2], [14, 4, 14]), box([4, 4, 3], [12, 5, 13]), box([6, 5, 4], [10, 10, 12]), box([3, 10, 0], [13, 16, 16], { top }),
] });
const MODELS = {
  anvil: anvil('anvil_top'),
  chipped_anvil: anvil('chipped_anvil_top'),
  damaged_anvil: anvil('damaged_anvil_top'),
  beacon: { boxes: [
    box([2, 0, 2], [14, 3, 14], { all: 'obsidian' }), box([3, 3, 3], [13, 14, 13], { all: 'beacon' }), box([0, 0, 0], [16, 16, 16], { all: 'glass' }),
  ] },
  enchanting_table: { boxes: [box([0, 0, 0], [16, 12, 16], { top: 'enchanting_table_top', side: 'enchanting_table_side' })] },
  end_portal_frame: { boxes: [box([0, 0, 0], [16, 13, 16], { top: 'end_portal_frame_top', side: 'end_portal_frame_side' })] },
  daylight_detector: { boxes: [box([0, 0, 0], [16, 6, 16], { top: 'daylight_detector_top', side: 'daylight_detector_side' })] },
  stonecutter: { boxes: [box([0, 0, 0], [16, 9, 16], { top: 'stonecutter_top', side: 'stonecutter_side' })] },
  cactus: { boxes: [box([1, 0, 1], [15, 16, 15], { top: 'cactus_top', side: 'cactus_side' })] },
  farmland: { boxes: [box([0, 0, 0], [16, 15, 16], { top: 'farmland', side: 'dirt' })] },
  dirt_path: { boxes: [box([0, 0, 0], [16, 15, 16], { top: 'dirt_path_top', side: 'dirt_path_side' })] },
  chest: { boxes: [box([1, 0, 1], [15, 14, 15], { all: 'oak_planks' }), box([7, 6, 15], [9, 10, 16], { all: 'iron_block' })] },
  trapped_chest: { boxes: [box([1, 0, 1], [15, 14, 15], { all: 'oak_planks' }), box([7, 6, 15], [9, 10, 16], { all: 'redstone_block' })] },
  ender_chest: { boxes: [box([1, 0, 1], [15, 14, 15], { all: 'obsidian' }), box([7, 6, 15], [9, 10, 16], { all: 'emerald_block' })] },
  snow: { boxes: [box([0, 0, 0], [16, 2, 16], { all: 'snow' })] },
  lectern: { boxes: [box([0, 0, 0], [16, 2, 16], { all: 'lectern_base' }), box([4, 2, 4], [12, 13, 12], { all: 'lectern_sides' }),
    box([0, 12, 0], [16, 16, 16], { top: 'lectern_top', side: 'lectern_sides' })] },
};

/** Forme déduite du nom (dalles, escaliers…) appliquée aux textures fournies par le serveur. */
function shapeFor(id) {
  if (id.endsWith('_slab')) return [box([0, 0, 0], [16, 8, 16])];
  if (id.endsWith('_stairs')) return [box([0, 0, 0], [16, 8, 16]), box([8, 8, 0], [16, 16, 16])];
  if (id.endsWith('_carpet') || id === 'moss_carpet') return [box([0, 0, 0], [16, 1, 16])];
  if (id.endsWith('_pressure_plate')) return [box([1, 0, 1], [15, 1, 15])];
  if (id.endsWith('_button')) return [box([5, 0, 6], [11, 2, 10])];
  if (id.endsWith('_trapdoor')) return [box([0, 0, 0], [16, 3, 16])];
  if (id.endsWith('_wall')) return [box([5, 0, 0], [11, 13, 4]), box([4, 0, 4], [12, 16, 12]), box([5, 0, 12], [11, 13, 16])];
  if (id.endsWith('_fence')) return [box([6, 0, 0], [10, 16, 4]), box([7, 6, 4], [9, 9, 12]), box([7, 12, 4], [9, 15, 12]), box([6, 0, 12], [10, 16, 16])];
  if (id.endsWith('_fence_gate')) return [box([0, 5, 7], [2, 16, 9]), box([2, 6, 7], [14, 9, 9]), box([2, 12, 7], [14, 15, 9]), box([14, 5, 7], [16, 16, 9])];
  return [box([0, 0, 0], [16, 16, 16])];
}

const RENDER = 128; // résolution de rendu : l'image est ensuite réduite avec lissage, sans escaliers
const shadeCache = new Map();

function faceTex(canvas, tintHex, overlay, factor) {
  let c = document.createElement('canvas');
  c.width = canvas.width; c.height = canvas.height;
  const g = c.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.drawImage(canvas, 0, 0);
  if (tintHex) tint(c, tintHex);
  if (overlay) overlayOn(c, overlay);
  if (factor < 1) shade(c, factor);
  return c;
}

/** Dessine une liste de boîtes texturées (dessus, face avant, côté droit) en vue isométrique. */
function drawBoxes(boxes, faces) {
  const S = RENDER;
  const u = S / 32, v = S / 64, w = S / 32;
  const P = (x, y, z) => [S / 2 + (x - z) * u, (x + z) * v + (16 - y) * w];
  const out = document.createElement('canvas');
  out.width = out.height = S;
  const g = out.getContext('2d');
  g.imageSmoothingEnabled = false;
  const k = 1.006;
  for (const b of boxes) {
    const [x0, y0, z0] = b.f, [x1, y1, z1] = b.t;
    const f = faces(b);
    const quad = (tex, sx, sy, sw, sh, o, A, B) => {
      if (!tex || sw <= 0 || sh <= 0) return;
      const r = tex.width / 16;
      g.setTransform(A[0] / r * k, A[1] / r * k, B[0] / r * k, B[1] / r * k, o[0], o[1]);
      g.drawImage(tex, sx * r, sy * r, sw * r, sh * r, 0, 0, sw * r, sh * r);
    };
    // côté droit (x = x1), face avant (z = z1), puis le dessus
    quad(f.right, 16 - z1, 16 - y1, z1 - z0, y1 - y0, P(x1, y1, z1), [u, -v], [0, w]);
    quad(f.left, x0, 16 - y1, x1 - x0, y1 - y0, P(x0, y1, z1), [u, v], [0, w]);
    quad(f.top, x0, z0, x1 - x0, z1 - z0, P(x0, y1, z0), [u, v], [-u, v]);
  }
  g.setTransform(1, 0, 0, 1, 0, 0);
  return out.toDataURL();
}

/** Texture à plat, agrandie sans flou puis réduite proprement à l'affichage. */
function flatUrl(c) {
  const out = document.createElement('canvas');
  out.width = out.height = RENDER;
  const g = out.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.drawImage(c, 0, 0, RENDER, RENDER);
  return out.toDataURL();
}

async function blockTex(name) {
  const img = await loadTex(`block/${name}`);
  return img ? square(img) : null;
}

async function drawModel(model) {
  const names = new Set();
  for (const b of model.boxes) for (const n of [b.all, b.top, b.side, model.tex]) if (n) names.add(n);
  const tex = {};
  await Promise.all([...names].map(async n => { tex[n] = await blockTex(n); }));
  if (!Object.values(tex).some(Boolean)) return null;
  return drawBoxes(model.boxes, b => {
    const top = tex[b.top || b.all || model.tex];
    const side = tex[b.side || b.all || model.tex];
    return { top: top && faceTex(top, null, null, 1), left: side && faceTex(side, null, null, 0.8), right: side && faceTex(side, null, null, 0.62) };
  });
}

const FORCE_CUBE = id => /leaves|glass(?!_pane)|ice$|slime_block|honey_block|spawner|mangrove_roots|scaffolding/.test(id);

async function drawRecipe(r, id) {
  if (r.t === 'flat') {
    const img = await loadTex(r.src);
    return img ? flatUrl(tint(square(img), r.tint?.src)) : null;
  }
  const [ti, li, ri, oi] = await Promise.all([loadTex(r.top), loadTex(r.left), loadTex(r.right), loadTex(r.overlay)]);
  const base = ri || li || ti;
  if (!base) return null;
  const right = square(base);
  if (!FORCE_CUBE(id) && transparency(right) > 0.22) return flatUrl(tint(right, r.tint?.right)); // plantes, torches, rails…
  const top = square(ti || base), left = square(li || base);
  const ov = oi ? tint(square(oi), r.overlayTint) : null;
  const faces = {
    top: faceTex(top, r.tint?.top, null, 1),
    left: faceTex(left, r.tint?.left, ov, 0.8),
    right: faceTex(right, r.tint?.right, ov, 0.62),
  };
  return drawBoxes(shapeFor(id), () => faces);
}

function iconUrl(key) {
  if (!iconCache.has(key)) {
    const [kind, id] = key.split(':');
    iconCache.set(key, (async () => {
      if (kind === 'item' && MODELS[id]) {
        const m = await drawModel(MODELS[id]);
        if (m) return m;
      }
      const r = await requestRecipe(key);
      return r ? drawRecipe(r, id) : null;
    })().catch(() => null));
  }
  return iconCache.get(key);
}

function hydrateIcons(root = document) {
  $$('.ico[data-icon]:not([data-state])', root).forEach(span => {
    span.dataset.state = 'loading';
    iconUrl(span.dataset.icon).then(url => {
      if (url) {
        span.innerHTML = `<img src="${url}" alt="">`;
        span.dataset.state = 'ok';
        span.classList.remove('miss');
      } else {
        span.classList.add('miss');
        span.dataset.state = 'miss';
      }
    });
  });
}

function resetIcons() {
  recipeCache.clear();
  iconCache.clear();
  texCache.clear();
  state.keys = {};
  $$('.ico[data-icon]').forEach(s => { delete s.dataset.state; s.classList.remove('miss'); s.innerHTML = ''; });
  hydrateIcons();
}

// ------------------------------------------------------------------ skins

const skins = new Map();
function loadSkin(uuid) {
  if (!skins.has(uuid)) {
    skins.set(uuid, fetch(`api/skin/${uuid}`)
      .then(async r => (r.ok ? { bmp: await createImageBitmap(await r.blob()), model: r.headers.get('X-Skin-Model') || 'classic' } : null))
      .catch(() => null));
  }
  return skins.get(uuid);
}
const headHtml = (uuid, size) =>
  `<canvas class="head" width="8" height="8" style="width:${size}px;height:${size}px" data-skin="${esc(uuid)}" aria-hidden="true"></canvas>`;

function hydrate(root = document) {
  $$('canvas[data-skin]:not([data-done])', root).forEach(c => {
    c.dataset.done = '1';
    loadSkin(c.dataset.skin).then(s => drawHead(c, s));
  });
  $$('canvas[data-body]:not([data-done])', root).forEach(c => {
    c.dataset.done = '1';
    loadSkin(c.dataset.body).then(s => drawBody(c, s));
  });
  hydrateIcons(root);
}

function genericFace(g, seed) {
  let h = 0;
  for (const ch of seed || '') h = (h * 31 + ch.charCodeAt(0)) | 0;
  const hue = Math.abs(h) % 360;
  g.fillStyle = `hsl(${hue} 35% 62%)`; g.fillRect(0, 0, 8, 8);
  g.fillStyle = `hsl(${hue} 30% 28%)`; g.fillRect(0, 0, 8, 2); g.fillRect(0, 2, 1, 2); g.fillRect(7, 2, 1, 2);
  g.fillStyle = '#fff'; g.fillRect(1, 4, 2, 1); g.fillRect(5, 4, 2, 1);
  g.fillStyle = '#2c3e8f'; g.fillRect(2, 4, 1, 1); g.fillRect(5, 4, 1, 1);
  g.fillStyle = `hsl(${hue} 30% 40%)`; g.fillRect(3, 6, 2, 1);
}
function drawHead(c, s) {
  const g = c.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.clearRect(0, 0, 8, 8);
  if (!s) return genericFace(g, c.dataset.skin);
  g.drawImage(s.bmp, 8, 8, 8, 8, 0, 0, 8, 8);
  g.drawImage(s.bmp, 40, 8, 8, 8, 0, 0, 8, 8);
}
function drawBody(c, s) {
  const g = c.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.clearRect(0, 0, 16, 32);
  if (!s) {
    const tmp = document.createElement('canvas');
    tmp.width = tmp.height = 8;
    genericFace(tmp.getContext('2d'), c.dataset.body);
    g.drawImage(tmp, 4, 0);
    g.fillStyle = 'rgba(127,127,127,.55)';
    g.fillRect(4, 8, 8, 12); g.fillRect(0, 8, 4, 12); g.fillRect(12, 8, 4, 12); g.fillRect(4, 20, 8, 12);
    return;
  }
  const b = s.bmp;
  const legacy = b.height === 32;
  const aw = s.model === 'slim' ? 3 : 4;
  const part = (sx, sy, w, h, dx, dy, mirror = false) => {
    if (!mirror) return g.drawImage(b, sx, sy, w, h, dx, dy, w, h);
    g.save(); g.translate(dx + w, dy); g.scale(-1, 1); g.drawImage(b, sx, sy, w, h, 0, 0, w, h); g.restore();
  };
  part(8, 8, 8, 8, 4, 0);
  part(20, 20, 8, 12, 4, 8);
  part(44, 20, aw, 12, 4 - aw, 8);
  if (legacy) part(44, 20, aw, 12, 12, 8, true); else part(36, 52, aw, 12, 12, 8);
  part(4, 20, 4, 12, 4, 20);
  if (legacy) part(4, 20, 4, 12, 8, 20, true); else part(20, 52, 4, 12, 8, 20);
  part(40, 8, 8, 8, 4, 0);
  if (!legacy) {
    part(20, 36, 8, 12, 4, 8);
    part(44, 36, aw, 12, 4 - aw, 8);
    part(52, 52, aw, 12, 12, 8);
    part(4, 36, 4, 12, 4, 20);
    part(4, 52, 4, 12, 8, 20);
  }
}

// ------------------------------------------------------------------ paysage de l'accueil

function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function hashStr(s) {
  let h = 2166136261;
  for (const ch of s) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
  return h >>> 0;
}

const FALLBACK_COLORS = {
  grass_block_side: '#6a8f3a', grass_block_top: '#7cb342', dirt: '#866043', stone: '#7d7d7d', deepslate: '#4d4d55',
  sand: '#dbd3a0', water_still: '#3f76e4', oak_log: '#6b5131', oak_leaves: '#3f8f22', birch_log: '#d8d4c8', birch_leaves: '#6f9b4a',
  coal_ore: '#5a5a5a', iron_ore: '#a08a7a', copper_ore: '#8f6a4a', gold_ore: '#b9a24a', diamond_ore: '#6fb8b8',
  netherrack: '#6f2e2e', crimson_nylium_side: '#8a2a2a', soul_sand: '#5a4434', lava_still: '#e8681c', glowstone: '#e6c46e',
  crimson_stem: '#6a2b3c', nether_wart_block: '#7d0d0d', shroomlight: '#f09a4a', nether_quartz_ore: '#8a5050', nether_gold_ore: '#8a5a3a',
  end_stone: '#dbdea0', obsidian: '#1b1428', purpur_block: '#a77ba7', chorus_plant: '#5e3a6a', chorus_flower: '#9a7aa6',
  short_grass: null, poppy: null, dandelion: null, crimson_roots: null,
};

async function heroTextures(names) {
  const out = {};
  await Promise.all(names.map(async n => {
    const img = state.ui.assetsReady ? await loadTex(`block/${n}`) : null;
    if (img) {
      let c = square(img);
      const t = { grass_block_top: '#91bd59', grass_block_side_overlay: '#91bd59', short_grass: '#91bd59', oak_leaves: '#48b518', birch_leaves: '#80a755', water_still: '#3f76e4' }[n];
      if (t) c = tint(c, t);
      out[n] = c;
    } else if (FALLBACK_COLORS[n]) {
      out[n] = FALLBACK_COLORS[n];
    }
  }));
  if (out.grass_block_side && out.grass_block_side_overlay && typeof out.grass_block_side !== 'string') {
    out.grass_block_side = overlayOn(out.grass_block_side, out.grass_block_side_overlay);
  }
  return out;
}

let heroJob = 0;
async function drawHero() {
  const job = ++heroJob;
  const hero = $('#hero');
  const canvas = $('#hero-canvas');
  const img = $('#hero-img');
  const mode = state.ui.background;
  img.hidden = mode !== 'image';
  canvas.hidden = mode !== 'paysage';
  if (mode === 'image') img.style.backgroundImage = `url("api/background?v=${state.ui.backgroundVersion || 0}")`;
  if (mode !== 'paysage') return;

  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const W = hero.clientWidth, H = hero.clientHeight;
  const B = Math.round(Math.max(28, Math.min(40, W / 42)) * dpr);
  canvas.width = Math.round(W * dpr);
  canvas.height = Math.round(H * dpr);
  const cols = Math.ceil(canvas.width / B) + 1;
  const rows = Math.ceil(canvas.height / B);
  const land = getComputedStyle(document.documentElement).getPropertyValue('--landscape').trim() || 'overworld';
  const theme = document.documentElement.dataset.theme;
  const R = rng(hashStr((state.server?.title || state.ui.title || 'serveur') + land));

  const names = {
    overworld: ['grass_block_side', 'grass_block_side_overlay', 'dirt', 'stone', 'deepslate', 'sand', 'water_still', 'oak_log', 'oak_leaves',
      'birch_log', 'birch_leaves', 'coal_ore', 'iron_ore', 'copper_ore', 'gold_ore', 'diamond_ore', 'short_grass', 'poppy', 'dandelion'],
    nether: ['netherrack', 'crimson_nylium_side', 'soul_sand', 'lava_still', 'glowstone', 'crimson_stem', 'nether_wart_block', 'shroomlight',
      'nether_quartz_ore', 'nether_gold_ore', 'crimson_roots'],
    end: ['end_stone', 'obsidian', 'purpur_block', 'chorus_plant', 'chorus_flower'],
  }[land] || [];
  const T = await heroTextures(names);
  if (job !== heroJob) return;

  const g = canvas.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.clearRect(0, 0, canvas.width, canvas.height);
  const put = (name, cx, row, alpha = 1) => {
    const t = T[name];
    if (!t) return;
    const x = cx * B, y = row * B;
    g.globalAlpha = alpha;
    if (typeof t === 'string') { g.fillStyle = t; g.fillRect(x, y, B, B); } else g.drawImage(t, x, y, B, B);
    g.globalAlpha = 1;
  };
  const wave = (x, base, amp, seed) =>
    Math.round(base + amp * (Math.sin(x * 0.21 + seed) * 0.6 + Math.sin(x * 0.083 + seed * 2.3) * 0.9 + Math.sin(x * 0.47 + seed * 0.7) * 0.25));
  const shadeDepth = (fromRow) => {
    for (let r = fromRow; r < rows; r++) {
      g.fillStyle = `rgba(0,0,0,${Math.min(0.55, (r - fromRow) * 0.07)})`;
      g.fillRect(0, r * B, canvas.width, B);
    }
  };

  if (land === 'overworld') {
    const night = theme === 'deepslate';
    // astre
    g.fillStyle = night ? '#e8ecf5' : '#fff3b0';
    g.fillRect(canvas.width - B * 4, B * 1.2, B * 1.6, B * 1.6);
    if (night) {
      g.fillStyle = '#c9d0de';
      g.fillRect(canvas.width - B * 3.6, B * 1.6, B * 0.4, B * 0.4);
      for (let i = 0; i < 60; i++) { g.fillStyle = `rgba(255,255,255,${0.3 + R() * 0.6})`; g.fillRect(R() * canvas.width, R() * canvas.height * 0.5, dpr * 2, dpr * 2); }
    }
    // nuages
    g.fillStyle = night ? 'rgba(200,210,230,.18)' : 'rgba(255,255,255,.85)';
    for (let i = 0; i < 6; i++) {
      const cx = R() * canvas.width, cy = B * (0.6 + R() * 3), w = B * (2 + R() * 4);
      g.fillRect(cx, cy, w, B * 0.6);
      g.fillRect(cx + B * 0.6, cy - B * 0.4, w * 0.5, B * 0.5);
    }
    const sea = Math.round(rows * 0.24);
    const heights = [];
    for (let x = 0; x < cols; x++) heights.push(Math.max(2, wave(x, rows * 0.33, 2.2, 1.7 + (hashStr(state.ui.title || '') % 7))));
    let lastTree = -5;
    for (let x = 0; x < cols; x++) {
      const h = heights[x];
      const top = rows - h;
      const beach = h <= sea;
      for (let r = top; r < rows; r++) {
        const d = r - top;
        let n;
        if (d === 0) n = beach ? 'sand' : 'grass_block_side';
        else if (d <= 3) n = beach ? 'sand' : 'dirt';
        else if (r >= rows - 1) n = 'deepslate';
        else {
          const p = R();
          n = p < 0.06 ? 'coal_ore' : p < 0.09 ? 'iron_ore' : p < 0.11 ? 'copper_ore' : (p < 0.12 && r >= rows - 3) ? 'diamond_ore' : p < 0.125 ? 'gold_ore' : 'stone';
        }
        put(n, x, r);
      }
      if (beach) for (let r = rows - sea; r < top; r++) put('water_still', x, r, 0.78);
      if (!beach && x - lastTree > 3 && R() < 0.16 && x > 1) {
        lastTree = x;
        const birch = R() < 0.3;
        const log = birch ? 'birch_log' : 'oak_log', leaves = birch ? 'birch_leaves' : 'oak_leaves';
        const th = 4 + Math.floor(R() * 2);
        for (let i = 1; i <= th; i++) put(log, x, top - i);
        const crown = top - th;
        for (let dx = -2; dx <= 2; dx++) for (let dy = 0; dy <= 1; dy++) if (!(Math.abs(dx) === 2 && dy === 0 && R() < 0.5)) put(leaves, x + dx, crown + dy);
        for (let dx = -1; dx <= 1; dx++) { put(leaves, x + dx, crown - 1); if (dx === 0 || R() < 0.5) put(leaves, x + dx, crown - 2); }
      } else if (!beach) {
        const p = R();
        if (p < 0.28) put('short_grass', x, top - 1); else if (p < 0.33) put('poppy', x, top - 1); else if (p < 0.38) put('dandelion', x, top - 1);
      }
    }
    shadeDepth(rows - Math.round(rows * 0.18));
  } else if (land === 'nether') {
    // plafond
    for (let x = 0; x < cols; x++) {
      const c = Math.max(1, wave(x, 1.6, 1.2, 4.1));
      for (let r = 0; r < c; r++) put('netherrack', x, r);
      if (R() < 0.12) { put('glowstone', x, c); if (R() < 0.6) put('glowstone', x, c + 1); }
    }
    const lava = Math.round(rows * 0.2);
    for (let x = 0; x < cols; x++) {
      const h = Math.max(1, wave(x, rows * 0.3, 2.4, 2.9));
      const top = rows - h;
      const low = h <= lava;
      for (let r = top; r < rows; r++) {
        const d = r - top;
        const p = R();
        put(d === 0 && !low ? (R() < 0.2 ? 'soul_sand' : 'crimson_nylium_side') : p < 0.05 ? 'nether_quartz_ore' : p < 0.08 ? 'nether_gold_ore' : 'netherrack', x, r);
      }
      if (low) for (let r = rows - lava; r < top; r++) put('lava_still', x, r);
      if (!low && R() < 0.1 && x > 1) {
        const th = 3 + Math.floor(R() * 3);
        for (let i = 1; i <= th; i++) put('crimson_stem', x, top - i);
        for (let dx = -2; dx <= 2; dx++) put(R() < 0.12 ? 'shroomlight' : 'nether_wart_block', x + dx, top - th - 1);
        for (let dx = -1; dx <= 1; dx++) put('nether_wart_block', x + dx, top - th - 2);
      } else if (!low && R() < 0.25) put('crimson_roots', x, top - 1);
    }
    g.fillStyle = 'rgba(120,20,10,.18)';
    g.fillRect(0, 0, canvas.width, canvas.height);
    shadeDepth(rows - Math.round(rows * 0.15));
  } else if (land === 'end') {
    for (let i = 0; i < 160; i++) { g.fillStyle = `rgba(255,255,255,${0.25 + R() * 0.7})`; g.fillRect(R() * canvas.width, R() * canvas.height * 0.8, dpr * 2, dpr * 2); }
    // îles flottantes
    let x = -2;
    while (x < cols) {
      const w = 5 + Math.floor(R() * 9);
      const surf = Math.round(rows * (0.55 + R() * 0.25));
      for (let dx = 0; dx < w; dx++) {
        const depth = Math.max(1, Math.round((1 - Math.abs((dx - w / 2) / (w / 2))) * (2 + w / 3)));
        for (let r = 0; r < depth; r++) put('end_stone', x + dx, surf + r);
        if (R() < 0.12) {
          const th = 2 + Math.floor(R() * 4);
          for (let i = 1; i <= th; i++) put('chorus_plant', x + dx, surf - i);
          put('chorus_flower', x + dx, surf - th - 1);
        }
      }
      if (R() < 0.5) {
        const px = x + Math.floor(w / 2), ph = 4 + Math.floor(R() * 5);
        for (let i = 1; i <= ph; i++) { put('obsidian', px, surf - i); put('obsidian', px + 1, surf - i); }
      } else if (R() < 0.5) {
        const px = x + 1, ph = 3 + Math.floor(R() * 3);
        for (let i = 1; i <= ph; i++) for (let dx = 0; dx < 3; dx++) put('purpur_block', px + dx, surf - i);
      }
      x += w + 2 + Math.floor(R() * 4);
    }
  }
}

let heroResize = null;
new ResizeObserver(() => { clearTimeout(heroResize); heroResize = setTimeout(drawHero, 150); }).observe($('#hero'));

// ------------------------------------------------------------------ apparence

function loadSavedStyle() {
  try {
    const s = JSON.parse(localStorage.getItem(STYLE_KEY) || 'null');
    if (!s) return null;
    // Réglages d'une ancienne version du panel (sans révision) ou antérieurs au dernier changement de l'admin : oubliés
    if (typeof s.rev !== 'number' || s.rev !== (state.ui.styleRevision || 0)) { localStorage.removeItem(STYLE_KEY); return null; }
    return s;
  } catch { return null; }
}
function effectiveStyle() {
  const base = { theme: state.ui.theme, headingFont: state.ui.headingFont, density: state.ui.density, showIcons: state.ui.showIcons };
  if (!state.ui.allowVisitorStyle) return base;
  const saved = loadSavedStyle() || {};
  const out = { ...base };
  for (const k of Object.keys(base)) if (k in saved) out[k] = saved[k];
  return out;
}
const darkQuery = matchMedia('(prefers-color-scheme: dark)');
function applyStyle() {
  const s = effectiveStyle();
  const prevTheme = document.documentElement.dataset.theme;
  state.style = s;
  const d = document.documentElement;
  d.dataset.theme = s.theme === 'auto' ? (darkQuery.matches ? 'deepslate' : 'calcite') : s.theme;
  d.dataset.font = s.headingFont;
  d.dataset.density = s.density;
  document.body.classList.toggle('no-icons', !s.showIcons);
  if (state.view === 'serveur') drawCharts();
  if (prevTheme !== d.dataset.theme) drawHero();
  for (const [name, value] of Object.entries(s)) {
    const input = $(`#settings-dialog input[name="${name}"][value="${String(value)}"]`);
    if (input) input.checked = true;
  }
  const custom = !!loadSavedStyle();
  $('#settings-reset').disabled = !custom;
  $('#settings-origin').textContent = custom
    ? 'Tu utilises ton propre style. Le bouton ci-dessous remet celui choisi par l’administrateur.'
    : 'Tu utilises le style choisi par l’administrateur du serveur.';
}
darkQuery.addEventListener('change', () => { if (state.style?.theme === 'auto') applyStyle(); });
$('#settings-dialog').addEventListener('change', e => {
  const input = e.target;
  if (!(input instanceof HTMLInputElement)) return;
  const saved = loadSavedStyle() || {};
  saved[input.name] = input.name === 'showIcons' ? input.value === 'true' : input.value;
  saved.rev = state.ui.styleRevision || 0;
  localStorage.setItem(STYLE_KEY, JSON.stringify(saved));
  applyStyle();
});
$('#settings-reset').addEventListener('click', () => { localStorage.removeItem(STYLE_KEY); applyStyle(); });
$('#open-settings').addEventListener('click', () => $('#settings-dialog').showModal());

// ------------------------------------------------------------------ réseau

async function api(path) {
  const r = await fetch(path, { cache: 'no-store' });
  if (!r.ok) {
    const err = new Error(`${path} → HTTP ${r.status}`);
    err.status = r.status;
    throw err;
  }
  return r.json();
}
async function post(path, body, file) {
  let r;
  try {
    r = await fetch(path, {
    method: 'POST',
    headers: { 'X-Panel': '1', 'Content-Type': file ? (file.type || 'application/octet-stream') : 'application/json' },
    body: file || JSON.stringify(body || {}),
    });
  } catch {
    throw new Error(file
      ? 'La connexion a été coupée pendant l’envoi. Si le panel passe par un reverse proxy, sa taille d’envoi maximale est sans doute trop basse.'
      : 'Le serveur ne répond pas.');
  }
  if (r.status === 413 && !(r.headers.get('Content-Type') || '').includes('json')) {
    throw new Error('Image refusée par le reverse proxy : elle dépasse sa taille d’envoi maximale.');
  }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `Erreur ${r.status}`);
  return j;
}

async function loadUi() {
  const before = state.ui.assetsReady;
  try { state.ui = { ...state.ui, ...(await api('api/ui')) }; } catch { /* valeurs par défaut */ }
  $('#open-settings').hidden = !state.ui.allowVisitorStyle;
  applyStyle();
  drawHero();
  if (state.ui.assetsReady) {
    try { const r = await fetch('api/lang'); if (r.ok) state.lang = await r.json(); } catch { /* noms de secours */ }
    if (!before) resetIcons();
  } else {
    setTimeout(loadUi, 15000); // les textures se téléchargent au premier démarrage
  }
  hydrateIcons();
}

async function loadMe() {
  try { state.me = await api('api/me'); } catch { /* garde l'état précédent */ }
  applyPerms();
}

function applyPerms() {
  const me = state.me;
  $$('[data-perm]').forEach(el => { el.hidden = !can(el.dataset.perm); });
  $$('[data-admin]').forEach(el => { el.hidden = !isAdmin(); });
  $('#account-label').textContent = me.user ? me.user.username : 'Se connecter';

  const wall = !can('panel.view');
  $('main').hidden = wall;
  $('.tabs-wrap').hidden = wall && !isAdmin();
  const banner = $('#locked-banner');
  if (wall) {
    banner.hidden = false;
    banner.querySelector('b').textContent = 'Ce panel est privé.';
    $('#locked-list').textContent = me.user ? 'Ton compte n’a pas accès au panel. Demande à un administrateur.' : 'Connecte-toi pour voir les statistiques du serveur.';
  } else if (!me.user && me.lockedForVisitors?.length) {
    banner.hidden = false;
    banner.querySelector('b').textContent = 'Certaines infos sont réservées aux membres.';
    $('#locked-list').textContent = `En te connectant : ${me.lockedForVisitors.join(', ').toLowerCase()}.`;
  } else {
    banner.hidden = true;
  }
  const allowed = { serveur: true, joueurs: can('players.list'), classements: can('leaderboards'), admin: isAdmin() };
  if (!allowed[state.view]) setView('serveur');
}

function showError(msg) {
  const b = $('#error-banner');
  b.hidden = !msg;
  if (msg) b.textContent = msg;
}

async function refresh() {
  clearTimeout(state.timer);
  if (!can('panel.view')) { renderHeroOffline(); return; }
  try {
    const jobs = [api('api/server')];
    const v = state.view;
    if (v === 'serveur' && can('server.history')) jobs.push(api('api/history'));
    if (v === 'joueurs') jobs.push(api('api/players'));
    if (v === 'classements') jobs.push(can('players.list') ? api('api/players') : Promise.resolve({ players: [] }), api('api/global'));
    const [server, a, b] = await Promise.all(jobs);
    state.server = server;
    state.refresh = server.refreshSeconds || 5;
    renderHero();
    if (v === 'serveur') { if (a) state.history = a; renderServer(); }
    if (v === 'joueurs') { state.players = a.players; renderPlayers(); }
    if (v === 'classements') { state.players = a.players; state.global = b; renderBoards(); }
    showError(null);
  } catch (e) {
    console.error(e);
    if (e.status === 403 || e.status === 401) { await loadMe(); }
    else {
      setStatus('bad', 'Injoignable');
      showError(`Le panel ne répond pas : le serveur est peut-être arrêté. Nouvel essai dans ${state.refresh} s.`);
    }
  }
  state.timer = setTimeout(() => { if (!document.hidden) refresh(); }, state.refresh * 1000);
}
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });

function setHtml(el, html) {
  if (state.keys[el.id] === html) return;
  state.keys[el.id] = html;
  el.innerHTML = html;
  hydrate(el);
}

// ------------------------------------------------------------------ accueil

function setStatus(kind, text) {
  $('#status-dot').className = `dot ${kind}`;
  $('#status-text').textContent = text;
}

function renderHeroOffline() {
  $('#server-title').textContent = state.ui.title || 'Serveur Minecraft';
  setStatus('', 'Panel privé');
}

function renderHero() {
  const s = state.server;
  const title = (s.title || 'Serveur Minecraft').trim();
  $('#server-title').textContent = title;
  document.title = `${title} · Stats`;
  $('#server-motd').textContent = s.motd && s.motd !== title ? s.motd : '';
  $('#server-meta').textContent = `Minecraft ${s.versions.minecraft} · en ligne depuis ${durationMs(s.uptimeMs)}`;

  const t = s.ticks;
  if (!t) setStatus('ok', 'En ligne');
  else if (t.paused) setStatus('warn', 'En veille, personne en ligne');
  else if (t.tps < 15) setStatus('bad', 'Le serveur rame');
  else if (t.tps < 19) setStatus('warn', 'Léger ralentissement');
  else setStatus('ok', 'Tout est fluide');

  if (t) {
    const el = $('#h-tps');
    el.textContent = t.paused ? 'zZz' : nf2.format(Math.min(t.tps, 99));
    el.className = `hs-value ${t.paused ? '' : level(t.tps, 19, 15)}`;
    $('#tps-fill').style.width = `${t.paused ? 0 : Math.min(1, t.tps / 20) * 100}%`;
    $('#tps-fill').style.background = t.paused ? '#999' : { good: '#6ff0a0', mid: '#ffd166', bad: '#ff7b6e' }[level(t.tps, 19, 15)];
    $('#h-players').textContent = `${s.players.online} / ${s.players.max}`;
  }
  const list = s.online || [];
  setHtml($('#h-heads'), list.slice(0, 7).map(p => headHtml(p.uuid, 26)).join('')
    + (list.length > 7 ? `<span class="more">+${list.length - 7}</span>` : ''));
}

// ------------------------------------------------------------------ vue serveur

function renderServer() {
  const s = state.server;
  if (s.ticks) {
    const t = s.ticks;
    const mspt = $('#v-mspt');
    mspt.textContent = `${nf1.format(t.msptAvg)} ms`;
    mspt.className = `big ${level(t.msptAvg, 30, 50, true)}`;
    $('#v-mspt-sub').textContent = `Pic à ${nf1.format(t.msptMax)} ms. Au-delà de 50 ms, le serveur rame.`;
  }
  if (s.cpu) {
    $('#v-cpu').textContent = pct(s.cpu.process);
    $('#v-cpu-sub').textContent = `Machine entière : ${pct(s.cpu.system)} sur ${s.cpu.cores} cœurs`;
  }
  if (s.memory) {
    renderHotbar(s.memory.heapUsed, s.memory.heapMax);
    $('#v-ram-sub').textContent = `${bytes(s.memory.heapUsed)} utilisés sur ${bytes(s.memory.heapMax)}`;
  }
  if (can('server.history')) drawCharts();

  if (s.online) {
    const clickable = can('players.list');
    setHtml($('#online-list'), s.online.length === 0
      ? '<li class="empty">Aucun joueur connecté pour l’instant.</li>'
      : s.online.map(p => {
          const bits = [];
          if (p.dimension) bits.push(DIMENSIONS[p.dimension]?.[0] ?? pretty(p.dimension));
          if (p.x !== undefined) bits.push(`${nf.format(p.x)} / ${nf.format(p.y)} / ${nf.format(p.z)}`);
          if (p.xpLevel !== undefined) bits.push(`niveau ${p.xpLevel}`);
          return `<li><button class="online-row" ${clickable ? `data-uuid="${esc(p.uuid)}"` : 'disabled'}>
            ${headHtml(p.uuid, 44)}
            <span><span class="name">${esc(p.name)}</span>${bits.length ? `<br><span class="info">${esc(bits.join(' · '))}</span>` : ''}</span>
            ${p.ping !== undefined ? `<span class="ping ${level(p.ping, 80, 200, true)}">${p.ping} ms</span>` : '<span></span>'}
          </button></li>`;
        }).join(''));
  }

  if (s.worlds) {
    setHtml($('#worlds-list'), s.worlds.map(w => {
      const [label, ico] = DIMENSIONS[w.dimension] ?? [pretty(w.dimension), null];
      return `<li class="world">${ico ? icon('item', ico, 44, label) : `<span class="ico miss" style="--s:44px" data-l="${esc(label.charAt(0))}"></span>`}
        <span><span class="wname">${esc(label)}</span><br>
        <span class="wnums">${nf.format(w.loadedChunks)} chunks · ${nf.format(w.entities)} entités · ${w.players} joueur${w.players > 1 ? 's' : ''}</span></span></li>`;
    }).join('') || '<li class="empty">Collecte en cours…</li>');
  }

  if (s.system) {
    const sys = s.system;
    const kv = [
      ['Système', `${sys.os} (${sys.arch})`],
      ['Java', sys.java],
      ['Mémoire de la machine', sys.ramTotal ? `${bytes(sys.ramTotal - sys.ramFree)} sur ${bytes(sys.ramTotal)}` : '–'],
      ['Mémoire hors tas Java', bytes(sys.nonHeapUsed)],
      ['Taille du monde', sys.worldSize >= 0 ? bytes(sys.worldSize) : 'calcul en cours…'],
      ['Disque', sys.diskTotal ? `${bytes(sys.diskFree)} libres sur ${bytes(sys.diskTotal)}` : '–'],
      ['Charge moyenne', sys.loadAverage >= 0 ? nf2.format(sys.loadAverage) : 'indisponible'],
      ['Threads', nf.format(sys.threads)],
      ['Ramasse-miettes', `${nf.format(sys.gcCount)} passages, ${nf1.format(sys.gcTimeMs / 1000)} s au total`],
      ['Fabric', `Loader ${s.versions.fabricLoader}, API ${s.versions.fabricApi}, ${s.versions.mods} mods`],
    ];
    setHtml($('#system-info'), kv.map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join(''));
  }
}

function renderHotbar(used, max) {
  const bar = $('#ram-hotbar');
  if (bar.children.length !== 9) bar.innerHTML = '<div class="slot"><i></i></div>'.repeat(9);
  const ratio = max > 0 ? used / max : 0;
  $$('.slot > i', bar).forEach((el, i) => { el.style.height = `${Math.max(0, Math.min(1, ratio * 9 - i)) * 100}%`; });
  bar.classList.toggle('high', ratio >= 0.75 && ratio < 0.9);
  bar.classList.toggle('full', ratio >= 0.9);
  bar.setAttribute('aria-label', `Mémoire utilisée : ${pct(ratio)}`);
}

// ------------------------------------------------------------------ graphiques

const CHARTS = {
  tps: { key: 'tps', color: '--c-tps', min: 0, maxFrom: () => Math.max(20, ...(state.history.points || []).map(p => p.tps)), fmt: v => nf.format(Math.round(v)), ref: 20 },
  mspt: { key: 'mspt', color: '--c-mspt', min: 0, floor: 55, fmt: v => `${nf.format(Math.round(v))} ms`, ref: 50, refColor: '--bad' },
  heap: { key: 'heap', color: '--c-heap', min: 0, fmt: v => bytes(v), maxFrom: () => state.history.heapMax },
  cpu: { key: 'cpu', color: '--c-cpu', min: 0, floor: 0.25, fmt: v => pct(v) },
  players: { key: 'players', color: '--c-players', min: 0, floor: 2, fmt: v => nf.format(Math.round(v)), integer: true },
};
function drawCharts() {
  $$('canvas[data-chart]').forEach(c => { if (!c.closest('[hidden]')) drawChart(c, CHARTS[c.dataset.chart]); });
}
function drawChart(canvas, cfg) {
  const pts = (state.history.points || []).filter(p => p[cfg.key] !== undefined);
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  const c = canvas.getContext('2d');
  c.setTransform(dpr, 0, 0, dpr, 0, 0);
  c.clearRect(0, 0, w, h);
  const css = getComputedStyle(document.documentElement);
  const col = n => css.getPropertyValue(n).trim();
  const muted = col('--muted'), grid = col('--line'), color = col(cfg.color);
  c.font = `12px ${css.getPropertyValue('--sans')}`;
  if (pts.length < 2) { c.fillStyle = muted; c.fillText('Les premières mesures arrivent…', 8, h / 2); return; }

  const vals = pts.map(p => p[cfg.key]);
  let max = cfg.maxFrom ? cfg.maxFrom() : Math.max(...vals) * 1.1;
  if (cfg.floor) max = Math.max(max, cfg.floor);
  const min = cfg.min ?? Math.min(...vals);
  if (!(max > min)) max = min + 1;
  const padL = 64, padR = 8, padT = 8, padB = 22;
  const iw = w - padL - padR, ih = h - padT - padB;
  const t0 = pts[0].t, t1 = pts[pts.length - 1].t;
  const x = t => padL + ((t - t0) / Math.max(1, t1 - t0)) * iw;
  const y = v => padT + ih - ((v - min) / (max - min)) * ih;

  c.strokeStyle = grid; c.lineWidth = 1; c.fillStyle = muted; c.textAlign = 'right'; c.textBaseline = 'middle';
  for (let i = 0; i <= 3; i++) {
    const v = min + ((max - min) * i) / 3;
    const yy = Math.round(y(v)) + 0.5;
    c.beginPath(); c.moveTo(padL, yy); c.lineTo(w - padR, yy); c.stroke();
    c.fillText(cfg.fmt(v), padL - 8, yy);
  }
  if (cfg.ref !== undefined && cfg.ref <= max) {
    c.save(); c.setLineDash([5, 5]); c.strokeStyle = cfg.refColor ? col(cfg.refColor) : muted;
    const yy = Math.round(y(cfg.ref)) + 0.5;
    c.beginPath(); c.moveTo(padL, yy); c.lineTo(w - padR, yy); c.stroke(); c.restore();
  }
  c.beginPath();
  pts.forEach((p, i) => {
    const px = x(p.t), py = y(p[cfg.key]);
    if (i === 0) c.moveTo(px, py);
    else if (cfg.integer) { c.lineTo(px, y(pts[i - 1][cfg.key])); c.lineTo(px, py); }
    else c.lineTo(px, py);
  });
  c.strokeStyle = color; c.lineWidth = 2.5; c.lineJoin = 'round'; c.stroke();
  c.lineTo(x(t1), y(min)); c.lineTo(x(t0), y(min)); c.closePath();
  if (/^#[0-9a-f]{6}$/i.test(color)) {
    const grad = c.createLinearGradient(0, padT, 0, padT + ih);
    grad.addColorStop(0, color + '55'); grad.addColorStop(1, color + '00');
    c.fillStyle = grad; c.fill();
  }
  const last = pts[pts.length - 1];
  c.fillStyle = color; c.beginPath(); c.arc(x(last.t), y(last[cfg.key]), 4, 0, Math.PI * 2); c.fill();
  c.fillStyle = muted; c.textBaseline = 'alphabetic'; c.textAlign = 'left';
  c.fillText(`il y a ${durationMs(t1 - t0)}`, padL, h - 4);
  c.textAlign = 'right'; c.fillText('maintenant', w - padR, h - 4);
}
new ResizeObserver(() => { if (state.view === 'serveur') drawCharts(); }).observe($('#charts'));

// ------------------------------------------------------------------ vue joueurs

function renderPlayers() {
  const q = $('#player-search').value.trim().toLowerCase();
  const sort = $('#player-sort').value;
  const list = state.players
    .filter(p => !q || (p.name || '').toLowerCase().includes(q) || p.uuid.includes(q))
    .sort((a, b) => {
      if (sort === 'name') return displayName(a).localeCompare(displayName(b), 'fr');
      if (sort === 'lastSeen') return (b.online - a.online) || (b.lastSeen - a.lastSeen);
      return b.summary[sort] - a.summary[sort];
    });
  const grid = $('#player-grid');
  if (state.players.length === 0) return setHtml(grid, '<li class="empty">Aucune statistique pour l’instant. Elles apparaissent dès qu’un joueur s’est connecté et que le serveur a sauvegardé.</li>');
  if (list.length === 0) return setHtml(grid, `<li class="empty">Aucun joueur ne correspond à « ${esc(q)} ».</li>`);
  setHtml(grid, list.map(p => {
    const extra = sort !== 'playTime' && FIELD[sort] ? `<span class="line">${FIELD[sort][1]} : <b>${FIELD[sort][2](p.summary[sort])}</b></span>` : '';
    return `<li><button class="panel player-card" data-uuid="${esc(p.uuid)}">
      ${headHtml(p.uuid, 64)}
      <span>
        <span class="name">${p.online ? '<span class="on" title="Connecté"></span>' : ''}${esc(displayName(p))}</span>
        <span class="line"><b>${duration(p.summary.playTime)}</b> de jeu</span>
        <span class="line">${p.online ? 'Connecté en ce moment' : `Vu ${ago(p.lastSeen)}`}</span>
        ${extra}
      </span>
    </button></li>`;
  }).join(''));
}
$('#player-search').addEventListener('input', renderPlayers);
$('#player-sort').addEventListener('change', renderPlayers);

// ------------------------------------------------------------------ vue classements

const CAT_VERB = {
  'minecraft:custom': 'Général', 'minecraft:mined': 'Miné', 'minecraft:crafted': 'Fabriqué', 'minecraft:used': 'Utilisé',
  'minecraft:picked_up': 'Ramassé', 'minecraft:dropped': 'Jeté', 'minecraft:broken': 'Usé jusqu’à casser',
  'minecraft:killed': 'Tué', 'minecraft:killed_by': 'A tué des joueurs',
};
const ex = { cat: 'minecraft:mined', q: '', sort: 'value', limit: 60, open: null, details: {} };
const norm = t => String(t).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
const statValue = (cat, id, v) => (cat === 'minecraft:custom' ? customValue(id, v) : nf.format(v));

function renderBoards() {
  const g = state.global;
  setHtml($('#totals'), [
    `<div class="total"><span class="ico" style="--s:40px" data-icon="item:armor_stand" data-l="J"></span><div><dt>Joueurs différents</dt><dd>${nf.format(g.playerCount)}</dd></div></div>`,
    ...TOTALS.map(k => {
      const [, label, f, ico] = FIELD[k];
      return `<div class="total">${icon('item', ico, 40, label)}<div><dt>${esc(label)}</dt><dd>${esc(f(g.totals[k]))}</dd></div></div>`;
    }),
  ].join(''));

  renderExplorer();

  const showBoards = can('players.list');
  $('#boards-title').hidden = $('#boards').hidden = !showBoards;
  if (showBoards) {
    setHtml($('#boards'), BOARDS.map(key => {
      const [, label, f, ico] = FIELD[key];
      const top = state.players.filter(p => p.summary[key] > 0).sort((a, b) => b.summary[key] - a.summary[key]).slice(0, 10);
      return `<section class="panel board"><h3>${icon('item', ico, 28, label)}${esc(label)}</h3>${top.length
        ? `<ol>${top.map(p => `<li>${headHtml(p.uuid, 28)}
            <span class="who"><button data-uuid="${esc(p.uuid)}">${esc(displayName(p))}</button></span>
            <span class="val">${esc(f(p.summary[key]))}</span></li>`).join('')}</ol>`
        : '<p class="empty">Personne pour l’instant.</p>'}</section>`;
    }).join(''));
  }
}

/** Toutes les entrées correspondant à la recherche (ou à la catégorie choisie). */
function explorerEntries() {
  const stats = state.global?.stats || {};
  const q = norm(ex.q);
  const qId = q.replace(/\s+/g, '_');
  const cats = q ? CATEGORIES.map(c => c[0]) : [ex.cat];
  const out = [];
  for (const cat of cats) {
    for (const [id, [v, holders]] of Object.entries(stats[cat] || {})) {
      const label = nameOf(cat, id);
      if (q && !norm(label).includes(q) && !bare(id).includes(qId)) continue;
      out.push({ cat, id, v, holders, label });
    }
  }
  const order = Object.fromEntries(CATEGORIES.map((c, i) => [c[0], i]));
  const by = {
    value: (a, b) => b.v - a.v,
    holders: (a, b) => b.holders - a.holders || b.v - a.v,
    name: (a, b) => a.label.localeCompare(b.label, 'fr'),
  }[ex.sort];
  return out.sort((a, b) => (order[a.cat] - order[b.cat]) || by(a, b));
}

function renderExplorer() {
  const stats = state.global?.stats || {};
  const entries = explorerEntries();
  const searching = !!norm(ex.q);

  const counts = {};
  for (const e of entries) counts[e.cat] = (counts[e.cat] || 0) + 1;
  setHtml($('#ex-cats'), CATEGORIES.map(([id, label, ico]) => {
    const n = searching ? (counts[id] || 0) : Object.keys(stats[id] || {}).length;
    const pressed = searching ? n > 0 : id === ex.cat;
    return `<button class="cat" data-excat="${id}" aria-pressed="${pressed}" ${n ? '' : 'disabled'}>
      ${icon('item', ico, 32, label)}<span>${esc(label)}</span><span class="n">${nf.format(n)}</span></button>`;
  }).join(''));

  $('#ex-count').textContent = searching
    ? (entries.length ? `${nf.format(entries.length)} résultat${entries.length > 1 ? 's' : ''} pour « ${ex.q.trim()} »` : '')
    : `${nf.format(entries.length)} statistique${entries.length > 1 ? 's' : ''} dans « ${CATEGORIES.find(c => c[0] === ex.cat)[1]} »`;

  if (!entries.length) {
    setHtml($('#ex-list'), searching
      ? `<li class="empty">Rien ne correspond à « ${esc(ex.q.trim())} ». Essaie un autre mot, par exemple « diamant » ou « zombie ».</li>`
      : '<li class="empty">Aucune donnée dans cette catégorie pour l’instant.</li>');
    $('#ex-more').hidden = true;
    return;
  }

  const shown = entries.slice(0, ex.limit);
  const maxByCat = {};
  for (const e of entries) maxByCat[e.cat] = Math.max(maxByCat[e.cat] || 0, e.v);
  let html = '';
  let lastCat = null;
  let rank = 0;
  for (const e of shown) {
    if (e.cat !== lastCat) {
      rank = 0;
      lastCat = e.cat;
      if (searching) {
        const c = CATEGORIES.find(x => x[0] === e.cat);
        html += `<li class="ex-group">${icon('item', c[2], 24, c[1])}${esc(c[1])}<span class="n">${nf.format(counts[e.cat])}</span></li>`;
      }
    }
    rank++;
    const key = `${e.cat}|${e.id}`;
    const open = ex.open === key;
    const pctBar = Math.max(2, Math.round((e.v / maxByCat[e.cat]) * 100));
    html += `<li class="ex-row${open ? ' open' : ''}">
      <button class="ex-btn" data-ex="${esc(key)}" aria-expanded="${open}">
        <span class="ex-rank">${ex.sort === 'name' ? '' : rank}</span>
        ${iconFor(e.cat, e.id, 36, e.label)}
        <span class="ex-name"><b>${esc(e.label)}</b><span class="ex-sub">${esc(CAT_VERB[e.cat])} · ${e.holders} joueur${e.holders > 1 ? 's' : ''}</span></span>
        <span class="ex-bar" aria-hidden="true"><i style="width:${pctBar}%"></i></span>
        <span class="ex-val">${esc(statValue(e.cat, e.id, e.v))}</span>
        <span class="ex-chev" aria-hidden="true">▾</span>
      </button>
      ${open ? explorerDetail(e) : ''}
    </li>`;
  }
  setHtml($('#ex-list'), html);
  const more = $('#ex-more');
  more.hidden = entries.length <= ex.limit;
  more.textContent = `Afficher plus (${nf.format(entries.length - ex.limit)} restantes)`;
}

function explorerDetail(e) {
  const d = ex.details[`${e.cat}|${e.id}`];
  if (!d) return '<div class="ex-detail"><p class="empty">Chargement…</p></div>';
  if (d.error) return `<div class="ex-detail"><p class="empty">${esc(d.error)}</p></div>`;
  const clickable = can('players.list');
  return `<div class="ex-detail">
    <p class="ex-total">Total sur le serveur : <b>${esc(statValue(e.cat, e.id, d.total))}</b></p>
    <ol class="ex-players">${d.players.map((p, i) => {
      const share = d.total ? (p.value / d.total) * 100 : 0;
      return `<li>
        <span class="ex-rank">${i + 1}</span>
        ${headHtml(p.uuid, 28)}
        <span class="who">${clickable ? `<button data-uuid="${esc(p.uuid)}">${esc(p.name || p.uuid.slice(0, 8))}</button>` : esc(p.name || p.uuid.slice(0, 8))}</span>
        <span class="ex-bar" aria-hidden="true"><i style="width:${Math.max(2, share)}%"></i></span>
        <span class="ex-val">${esc(statValue(e.cat, e.id, p.value))}</span>
        <span class="ex-share">${nf1.format(share)} %</span>
      </li>`;
    }).join('')}</ol>
  </div>`;
}

async function openExplorerRow(key) {
  if (ex.open === key) { ex.open = null; renderExplorer(); return; }
  ex.open = key;
  renderExplorer();
  const [cat, id] = key.split('|');
  try {
    ex.details[key] = await api(`api/stat?cat=${encodeURIComponent(cat)}&id=${encodeURIComponent(id)}`);
  } catch {
    ex.details[key] = { error: 'Impossible de charger le détail de cette statistique.' };
  }
  if (ex.open === key) renderExplorer();
}

let exTimer = null;
$('#ex-search').addEventListener('input', e => {
  clearTimeout(exTimer);
  exTimer = setTimeout(() => { ex.q = e.target.value; ex.limit = 60; ex.open = null; renderExplorer(); }, 120);
});
$('#ex-sort').addEventListener('change', e => { ex.sort = e.target.value; renderExplorer(); });
$('#ex-more').addEventListener('click', () => { ex.limit += 120; renderExplorer(); });
$('#ex-cats').addEventListener('click', e => {
  const b = e.target.closest('button[data-excat]');
  if (!b) return;
  ex.cat = b.dataset.excat;
  ex.q = '';
  $('#ex-search').value = '';
  ex.limit = 60;
  ex.open = null;
  renderExplorer();
});
$('#ex-list').addEventListener('click', e => {
  if (e.target.closest('[data-uuid]')) return;
  const b = e.target.closest('button[data-ex]');
  if (b) openExplorerRow(b.dataset.ex);
});

// ------------------------------------------------------------------ fiche joueur

async function openPlayer(uuid) {
  if (!can('players.list')) return;
  const dlg = $('#player-dialog');
  $('#pd-content').innerHTML = '<p class="empty">Chargement…</p>';
  if (!dlg.open) dlg.showModal();
  try {
    state.player = await api(`api/player/${encodeURIComponent(uuid)}`);
    state.playerFilter = '';
    renderPlayer();
  } catch {
    $('#pd-content').innerHTML = '<p class="empty">Impossible de charger ce joueur : il est peut-être masqué, ou ses stats ont été supprimées.</p>';
  }
}

function renderPlayer() {
  const p = state.player;
  const live = p.live;
  let liveHtml = `<p class="pd-seen">Vu pour la dernière fois ${ago(p.lastSeen)}.</p>`;
  if (p.online) {
    const bits = ['<span><b>Connecté</b></span>'];
    if (live?.dimension) bits.push(`<span>${esc(DIMENSIONS[live.dimension]?.[0] ?? pretty(live.dimension))}</span>`);
    if (live?.x !== undefined) bits.push(`<span>Position <b>${nf.format(live.x)} / ${nf.format(live.y)} / ${nf.format(live.z)}</b></span>`);
    if (live?.health !== undefined) {
      bits.push(`<span>Vie <b>${nf1.format(live.health / 2)} / ${nf1.format(live.maxHealth / 2)} ♥</b></span>`,
        `<span>Faim <b>${live.food} / 20</b></span>`, `<span>Niveau <b>${live.xpLevel}</b></span>`, `<span>Ping <b>${live.ping} ms</b></span>`);
    }
    liveHtml = `<div class="pd-live">${bits.join('')}</div>`;
  }

  const details = p.stats
    ? `<div class="cat-bar" id="pd-cats">${CATEGORIES.map(([id, label, ico]) => {
        const n = Object.keys(p.stats[id] || {}).length;
        return `<button class="cat" data-cat="${id}" aria-pressed="${id === state.playerCat}" ${n ? '' : 'disabled'}>
          ${icon('item', ico, 32, label)}<span>${esc(label)}</span><span class="n">${n}</span></button>`;
      }).join('')}</div>
      <div class="cat-tools">
        <input type="search" class="field" id="pd-filter" placeholder="Filtrer cette catégorie" aria-label="Filtrer cette catégorie" value="${esc(state.playerFilter)}">
        <div class="seg" id="pd-view" role="group" aria-label="Affichage">
          <label><input type="radio" name="pdview" value="grille" ${state.invView === 'grille' ? 'checked' : ''}><span>Grille</span></label>
          <label><input type="radio" name="pdview" value="liste" ${state.invView === 'liste' ? 'checked' : ''}><span>Liste</span></label>
        </div>
      </div>
      <div class="cat-scroll" id="pd-rows"></div>`
    : `<p class="pd-locked">${state.me.user ? 'Ton compte n’a pas accès aux statistiques détaillées.' : 'Connecte-toi pour voir toutes les statistiques de ce joueur.'}</p>`;

  $('#pd-content').innerHTML = `<div class="pd-grid">
      <div class="pd-skin">
        <div class="stage"><canvas class="body" width="16" height="32" data-body="${esc(p.uuid)}" role="img" aria-label="Skin de ${esc(displayName(p))}"></canvas></div>
        <div class="pd-links">
          <a class="btn" href="https://namemc.com/profile/${p.uuid}" target="_blank" rel="noopener">${icon('item', 'name_tag', 20, 'N')}Profil NameMC</a>
          <a class="btn" href="api/skin/${p.uuid}" download="${esc(displayName(p))}.png">${icon('item', 'painting', 20, 'S')}Télécharger le skin</a>
        </div>
      </div>
      <div>
        <h2 class="pd-name" id="pd-name">${esc(displayName(p))}</h2>
        <button class="pd-uuid" id="pd-uuid" title="Copier l’UUID">${esc(p.uuid)}</button>
        ${liveHtml}
        <dl class="pd-summary">${SUMMARY_FIELDS.map(([k, label, f, ico]) =>
          `<div>${icon('item', ico, 32, label)}<span><dt>${esc(label)}</dt><dd>${esc(f(p.summary[k]))}</dd></span></div>`).join('')}</dl>
        ${details}
      </div>
    </div>`;
  hydrate($('#pd-content'));

  $('#pd-uuid').addEventListener('click', async e => {
    try { await navigator.clipboard.writeText(p.uuid); e.target.textContent = 'UUID copié'; setTimeout(() => { e.target.textContent = p.uuid; }, 1500); } catch { /* http */ }
  });
  if (!p.stats) return;
  if (!Object.keys(p.stats[state.playerCat] || {}).length) state.playerCat = 'minecraft:custom';
  renderPlayerRows();
  $('#pd-cats').addEventListener('click', e => {
    const b = e.target.closest('button[data-cat]');
    if (b) { state.playerCat = b.dataset.cat; renderPlayerRows(); }
  });
  $('#pd-filter').addEventListener('input', e => { state.playerFilter = e.target.value; renderPlayerRows(); });
  $('#pd-view').addEventListener('change', e => { state.invView = e.target.value; renderPlayerRows(); });
}

function statTable(cat, rows) {
  return `<table class="stat-table"><tbody>${rows.map(r => `<tr>
    <td><span class="lbl">${iconFor(cat, r.id, 28, r.label)}${esc(r.label)}</span></td>
    <td>${esc(cat === 'minecraft:custom' ? customValue(r.id, r.v) : nf.format(r.v))}</td></tr>`).join('')}</tbody></table>`;
}

function renderPlayerRows() {
  const p = state.player;
  const cat = state.playerCat;
  $$('#pd-cats .cat').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.cat === cat)));
  const isCustom = cat === 'minecraft:custom';
  $('#pd-view').hidden = isCustom;
  const q = state.playerFilter.trim().toLowerCase();
  const rows = Object.entries(p.stats[cat] || {})
    .map(([id, v]) => ({ id, v, label: nameOf(cat, id) }))
    .filter(r => !q || r.label.toLowerCase().includes(q) || r.id.includes(q))
    .sort((a, b) => b.v - a.v)
    .slice(0, 800);
  const box = $('#pd-rows');
  if (!rows.length) { box.innerHTML = '<p class="empty">Rien dans cette catégorie.</p>'; return; }

  if (isCustom) {
    const used = new Set();
    box.innerHTML = CUSTOM_GROUPS.map(([title, ico, test]) => {
      const g = rows.filter(r => !used.has(r.id) && test(bare(r.id)));
      g.forEach(r => used.add(r.id));
      return g.length ? `<section class="stat-group"><h4>${icon('item', ico, 24, title)}${esc(title)}</h4>${statTable(cat, g)}</section>` : '';
    }).join('');
  } else if (state.invView === 'grille') {
    box.innerHTML = `<div class="inv">${rows.map(r =>
      `<div class="inv-slot" title="${esc(r.label)} : ${nf.format(r.v)}">${iconFor(cat, r.id, 48, r.label).replace('class="ico"', 'class="ico keep"')}<span class="count">${compact(r.v)}</span></div>`
    ).join('')}</div>`;
  } else {
    box.innerHTML = statTable(cat, rows);
  }
  hydrateIcons(box);
}

// ------------------------------------------------------------------ connexion et compte

$('#account-btn').addEventListener('click', () => {
  if (state.me.user) {
    $('#account-info').textContent = `Connecté en tant que ${state.me.user.username} (${state.me.user.role === 'admin' ? 'administrateur' : 'membre'}).`;
    $('#password-msg').hidden = true;
    $('#account-dialog').showModal();
  } else openLogin();
});
document.addEventListener('click', e => {
  if (e.target.closest('[data-login]')) openLogin();
  const close = e.target.closest('[data-close]');
  if (close) close.closest('dialog').close();
});
function openLogin() {
  $('#login-error').hidden = true;
  $('#login-form').reset();
  $('#login-dialog').showModal();
}
$('#login-form').addEventListener('submit', async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  const btn = e.target.querySelector('button[type="submit"]');
  btn.disabled = true;
  try {
    state.me = await post('api/login', { username: f.get('username'), password: f.get('password') });
    $('#login-dialog').close();
    state.keys = {};
    applyPerms();
    refresh();
  } catch (err) {
    $('#login-error').textContent = err.message;
    $('#login-error').hidden = false;
  } finally {
    btn.disabled = false;
  }
});
$('#logout-btn').addEventListener('click', async () => {
  try { await post('api/logout'); } catch { /* déjà déconnecté */ }
  $('#account-dialog').close();
  state.keys = {};
  await loadMe();
  refresh();
});
$('#password-form').addEventListener('submit', async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  const msg = $('#password-msg');
  try {
    await post('api/account/password', { current: f.get('current'), next: f.get('next') });
    msg.textContent = 'Mot de passe changé.';
    msg.style.color = 'var(--good)';
    e.target.reset();
  } catch (err) {
    msg.textContent = err.message;
    msg.style.color = '';
  }
  msg.hidden = false;
});

// ------------------------------------------------------------------ administration

function flash(id, text, ok = true) {
  const el = $(id);
  el.textContent = text;
  el.className = `save-msg ${ok ? 'ok' : 'err'}`;
  if (ok) setTimeout(() => { if (el.textContent === text) el.textContent = ''; }, 3000);
}

async function loadAdmin() {
  try {
    state.admin = await api('api/admin/state');
    renderAdmin();
  } catch (e) {
    showError('Impossible de charger l’administration.');
  }
}

function renderAdmin() {
  const a = state.admin;
  const pub = new Set(a.permissions.public), mem = new Set(a.permissions.membre);
  $('#perm-rows').innerHTML = a.catalog.map(d => `<tr data-key="${esc(d.key)}">
      <td><div class="perm-name">${icon('item', d.icon, 32, d.label)}<span><b>${esc(d.label)}</b><span class="sub">${esc(d.help)}</span></span></div></td>
      <td><label class="toggle"><input type="checkbox" data-role="public" ${pub.has(d.key) ? 'checked' : ''} aria-label="${esc(d.label)} pour les visiteurs"><i></i></label></td>
      <td><label class="toggle"><input type="checkbox" data-role="membre" ${mem.has(d.key) || pub.has(d.key) ? 'checked' : ''} ${pub.has(d.key) ? 'disabled' : ''} aria-label="${esc(d.label)} pour les membres"><i></i></label></td>
      <td><label class="toggle"><input type="checkbox" checked disabled aria-label="${esc(d.label)} pour les admins"><i></i></label></td>
    </tr>`).join('');

  const me = state.me.user.username.toLowerCase();
  $('#user-rows').innerHTML = a.users.map(u => `<tr data-user="${esc(u.username)}">
      <td><b>${esc(u.username)}</b>${u.username.toLowerCase() === me ? ' <span class="role-pill">toi</span>' : ''}</td>
      <td><select class="field" data-f="role"><option value="membre" ${u.role === 'membre' ? 'selected' : ''}>Membre</option><option value="admin" ${u.role === 'admin' ? 'selected' : ''}>Admin</option></select></td>
      <td><input class="field" data-f="password" type="password" placeholder="Laisser vide pour garder" autocomplete="new-password"></td>
      <td><button class="btn" type="button" data-act="save">Enregistrer</button>${u.username.toLowerCase() === me ? '' : '<button class="btn danger" type="button" data-act="delete">Supprimer</button>'}</td>
    </tr>`).join('');

  const s = a.settings;
  $('#a-title').value = s.panelTitle;
  $(`#a-bg input[value="${s.backgroundMode}"]`).checked = true;
  $('#a-theme').value = s.theme;
  $('#a-font').value = s.headingFont;
  $('#a-density').value = s.density;
  $('#a-icons').checked = s.showIcons;
  $('#a-visitor').checked = s.allowVisitorStyle;
  $('#a-hidden').value = s.hiddenPlayers.join('\n');
  const prev = $('#bg-preview');
  prev.classList.toggle('has', a.hasBackground);
  prev.style.backgroundImage = a.hasBackground ? `url("api/background?v=${Date.now()}")` : '';
  $('#bg-delete').disabled = !a.hasBackground;

  const as = a.assets;
  $('#assets-info').innerHTML = [
    ['État', as.ready ? 'Prêtes' : as.running ? 'En cours…' : as.status],
    ['Version', as.version],
    ['Textures', `${nf.format(as.blocks)} blocs, ${nf.format(as.items)} objets`],
    ['Dossier', as.folder],
    ...(as.error ? [['Dernière erreur', as.error]] : []),
  ].map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('');
  if (as.running) setTimeout(() => { if (state.view === 'admin') loadAdmin(); }, 3000);
  hydrateIcons($('#view-admin'));
}

$('#perm-rows').addEventListener('change', e => {
  const input = e.target;
  if (input.dataset.role !== 'public') return;
  const member = input.closest('tr').querySelector('input[data-role="membre"]');
  if (input.checked) { member.checked = true; member.disabled = true; } else member.disabled = false;
});
$('#perm-save').addEventListener('click', async () => {
  const perms = { public: [], membre: [] };
  $$('#perm-rows tr').forEach(tr => {
    const key = tr.dataset.key;
    if (tr.querySelector('input[data-role="public"]').checked) perms.public.push(key);
    if (tr.querySelector('input[data-role="membre"]').checked) perms.membre.push(key);
  });
  try {
    state.admin = await post('api/admin/settings', { permissions: perms });
    renderAdmin();
    flash('#perm-msg', 'Droits enregistrés.');
  } catch (err) { flash('#perm-msg', err.message, false); }
});

$('#user-rows').addEventListener('click', async e => {
  const btn = e.target.closest('button[data-act]');
  if (!btn) return;
  const tr = btn.closest('tr');
  const username = tr.dataset.user;
  try {
    if (btn.dataset.act === 'delete') {
      if (!confirm(`Supprimer le compte ${username} ?`)) return;
      state.admin = await post('api/admin/users/delete', { username });
      flash('#user-msg', `Compte ${username} supprimé.`);
    } else {
      state.admin = await post('api/admin/users', {
        username, role: tr.querySelector('[data-f="role"]').value, password: tr.querySelector('[data-f="password"]').value,
      });
      flash('#user-msg', `Compte ${username} enregistré.`);
    }
    renderAdmin();
  } catch (err) { flash('#user-msg', err.message, false); }
});
$('#add-user').addEventListener('submit', async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    state.admin = await post('api/admin/users', { username: f.get('username'), password: f.get('password'), role: f.get('role') });
    e.target.reset();
    renderAdmin();
    flash('#user-msg', `Compte ${f.get('username')} créé.`);
  } catch (err) { flash('#user-msg', err.message, false); }
});

$('#settings-save').addEventListener('click', async () => {
  try {
    state.admin = await post('api/admin/settings', {
      panelTitle: $('#a-title').value.trim(),
      backgroundMode: $('#a-bg input:checked')?.value || 'auto',
      theme: $('#a-theme').value,
      headingFont: $('#a-font').value,
      density: $('#a-density').value,
      showIcons: $('#a-icons').checked,
      allowVisitorStyle: $('#a-visitor').checked,
      hiddenPlayers: $('#a-hidden').value.split('\n').map(s => s.trim()).filter(Boolean),
    });
    renderAdmin();
    flash('#settings-msg', 'Enregistré.');
    await loadUi();
    refresh();
  } catch (err) { flash('#settings-msg', err.message, false); }
});
/** Redimensionne (1920 px max) et recompresse l'image dans le navigateur : quelques centaines de Ko au lieu de 10 Mo. */
async function shrinkImage(file) {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, 1920 / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas');
  c.width = Math.round(bmp.width * scale);
  c.height = Math.round(bmp.height * scale);
  const g = c.getContext('2d');
  g.imageSmoothingQuality = 'high';
  g.drawImage(bmp, 0, 0, c.width, c.height);
  const toBlob = (type, q) => new Promise(res => c.toBlob(res, type, q));
  let blob = await toBlob('image/webp', 0.84);
  if (!blob || blob.type !== 'image/webp') blob = await toBlob('image/jpeg', 0.86);
  return blob;
}

$('#bg-file').addEventListener('change', async e => {
  const original = e.target.files[0];
  e.target.value = '';
  if (!original) return;
  try {
    flash('#settings-msg', 'Préparation de l’image…');
    let file = original;
    try { file = await shrinkImage(original); } catch { /* format non décodable : on envoie tel quel */ }
    flash('#settings-msg', `Envoi de l’image (${bytes(file.size)})…`);
    state.admin = await post('api/admin/background', null, file);
    renderAdmin();
    flash('#settings-msg', 'Image enregistrée.');
    await loadUi();
  } catch (err) { flash('#settings-msg', err.message, false); }
});
$('#bg-delete').addEventListener('click', async () => {
  try {
    state.admin = await post('api/admin/background/delete');
    renderAdmin();
    await loadUi();
  } catch (err) { flash('#settings-msg', err.message, false); }
});
for (const [id, force] of [['#assets-retry', false], ['#assets-force', true]]) {
  $(id).addEventListener('click', async () => {
    try {
      state.admin = await post('api/admin/assets/retry', { force });
      renderAdmin();
      flash('#assets-msg', 'Préparation relancée.');
      setTimeout(loadUi, 5000);
    } catch (err) { flash('#assets-msg', err.message, false); }
  });
}

// ------------------------------------------------------------------ navigation

for (const d of $$('dialog')) d.addEventListener('click', e => { if (e.target === d) d.close(); });
document.addEventListener('click', e => {
  const b = e.target.closest('[data-uuid]');
  if (b && !b.disabled && !b.closest('#player-dialog')) openPlayer(b.dataset.uuid);
});

function setView(view) {
  const allowed = { serveur: true, joueurs: can('players.list'), classements: can('leaderboards'), admin: isAdmin() };
  if (!allowed[view]) view = 'serveur';
  state.view = view;
  $$('.tabs button').forEach(b => b.setAttribute('aria-selected', String(b.dataset.view === view)));
  $$('.view').forEach(v => { v.hidden = v.id !== `view-${view}`; });
  if (location.hash.slice(1) !== view) history.replaceState(null, '', `#${view}`);
  if (view === 'admin') loadAdmin();
  refresh();
}
$$('.tabs button').forEach(b => b.addEventListener('click', () => setView(b.dataset.view)));
window.addEventListener('hashchange', () => setView(location.hash.slice(1)));

(async () => {
  await Promise.all([loadUi(), loadMe()]);
  setView(location.hash.slice(1) || 'serveur');
})();
