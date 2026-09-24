const fs = require('fs');
const path = require('path');
const j = JSON.parse(fs.readFileSync(path.join(__dirname, 'regions_geom.json'), 'utf8'));

const META = {
  59065: { id: 'belarus', name: 'Беларусь', type: 'republic', area: 207600 },
  59161: { id: 'gomel_oblast', name: 'Гомельская область', type: 'oblast', area: 33469 },
  59162: { id: 'mogilev_oblast', name: 'Могилёвская область', type: 'oblast', area: 29068 },
  59189: { id: 'brest_oblast', name: 'Брестская область', type: 'oblast', area: 32787 },
  59195: { id: 'minsk', name: 'Минск', type: 'city', area: 348.84 },
  59275: { id: 'grodno_oblast', name: 'Гродненская область', type: 'oblast', area: 25127 },
  59506: { id: 'vitebsk_oblast', name: 'Витебская область', type: 'oblast', area: 40051 },
  59752: { id: 'minsk_oblast', name: 'Минская область', type: 'oblast', area: 39846 },
  62145: { id: 'mogilev', name: 'Могилёв', type: 'city', area: 118.5 },
  72615: { id: 'brest', name: 'Брест', type: 'city', area: 146.1 },
  130921: { id: 'grodno', name: 'Гродно', type: 'city', area: 142.1 },
  163244: { id: 'gomel', name: 'Гомель', type: 'city', area: 160.4 },
  6825777: { id: 'vitebsk', name: 'Витебск', type: 'city', area: 134.6 },
};

function ptKey(p) {
  return p[0].toFixed(7) + ',' + p[1].toFixed(7);
}

function assembleRings(ways) {
  const segs = ways.map((w) => w.slice());
  const rings = [];
  const used = new Array(segs.length).fill(false);
  for (let i = 0; i < segs.length; i++) {
    if (used[i]) continue;
    used[i] = true;
    let ring = segs[i].slice();
    let grew = true;
    while (grew) {
      grew = false;
      if (ring.length >= 2 && ptKey(ring[0]) === ptKey(ring[ring.length - 1])) break;
      for (let k = 0; k < segs.length; k++) {
        if (used[k]) continue;
        const s = segs[k];
        if (s.length < 2) {
          used[k] = true;
          continue;
        }
        const rEnd = ptKey(ring[ring.length - 1]);
        const rStart = ptKey(ring[0]);
        if (rEnd === ptKey(s[0])) {
          ring = ring.concat(s.slice(1));
          used[k] = true;
          grew = true;
          break;
        }
        if (rEnd === ptKey(s[s.length - 1])) {
          ring = ring.concat(s.slice(0, -1).reverse());
          used[k] = true;
          grew = true;
          break;
        }
        if (rStart === ptKey(s[s.length - 1])) {
          ring = s.slice(0, -1).concat(ring);
          used[k] = true;
          grew = true;
          break;
        }
        if (rStart === ptKey(s[0])) {
          ring = s.slice(1).reverse().concat(ring);
          used[k] = true;
          grew = true;
          break;
        }
      }
    }
    if (ring.length >= 4 && ptKey(ring[0]) === ptKey(ring[ring.length - 1])) {
      rings.push(ring);
    } else if (ring.length >= 3) {
      ring.push(ring[0]);
      rings.push(ring);
    }
  }
  return rings;
}

function ringArea(ring) {
  let a = 0;
  for (let i = 0; i < ring.length - 1; i++) {
    a += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1];
  }
  return Math.abs(a / 2);
}

function dp(ring, eps) {
  const pts = ring.slice(0, -1);
  if (pts.length <= 4) return ring;
  const keep = new Array(pts.length).fill(false);
  keep[0] = keep[pts.length - 1] = true;
  const stack = [[0, pts.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    if (b <= a + 1) continue;
    let maxd = -1;
    let idx = -1;
    const [x1, y1] = pts[a];
    const [x2, y2] = pts[b];
    const dx = x2 - x1;
    const dy = y2 - y1;
    const len2 = dx * dx + dy * dy || 1;
    for (let i = a + 1; i < b; i++) {
      const [x, y] = pts[i];
      const t = ((x - x1) * dx + (y - y1) * dy) / len2;
      const px = x1 + Math.max(0, Math.min(1, t)) * dx;
      const py = y1 + Math.max(0, Math.min(1, t)) * dy;
      const d = (x - px) * (x - px) + (y - py) * (y - py);
      if (d > maxd) {
        maxd = d;
        idx = i;
      }
    }
    if (maxd > eps * eps) {
      keep[idx] = true;
      stack.push([a, idx], [idx, b]);
    }
  }
  const out = pts.filter((_, i) => keep[i]);
  out.push(out[0]);
  return out;
}

function bboxOf(rings) {
  let minLon = Infinity;
  let minLat = Infinity;
  let maxLon = -Infinity;
  let maxLat = -Infinity;
  for (const r of rings) {
    for (const [lon, lat] of r) {
      if (lon < minLon) minLon = lon;
      if (lon > maxLon) maxLon = lon;
      if (lat < minLat) minLat = lat;
      if (lat > maxLat) maxLat = lat;
    }
  }
  return { minLon, minLat, maxLon, maxLat };
}

const epsById = (type) => (type === 'city' ? 0.0004 : type === 'oblast' ? 0.001 : 0.002);

const regions = [];
for (const e of j.elements) {
  const meta = META[e.id];
  if (!meta) {
    console.error('no meta', e.id);
    continue;
  }
  const outerWays = [];
  for (const m of e.members || []) {
    if (!m.geometry || !m.geometry.length) continue;
    if (m.role === 'inner') continue;
    outerWays.push(m.geometry.map((p) => [p.lon, p.lat]));
  }
  let rings = assembleRings(outerWays);
  rings = rings.filter((r) => ringArea(r) >= 1e-6 || rings.length === 1);
  if (!rings.length) {
    console.error('no rings', e.id, meta.id);
    continue;
  }
  const eps = epsById(meta.type);
  rings = rings.map((r) => dp(r, eps));
  rings.sort((a, b) => ringArea(b) - ringArea(a));
  const bbox = bboxOf(rings);
  regions.push({ ...meta, osm: e.id, rings, bbox });
}

function raycast(rings, lon, lat) {
  let inside = false;
  for (const r of rings) {
    for (let i = 0, j = r.length - 1; i < r.length; j = i++) {
      const [xi, yi] = r[i];
      const [xj, yj] = r[j];
      if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
    }
  }
  return inside;
}

const tests = [
  ['minsk', 27.56, 53.9, true],
  ['belarus', 27.56, 53.9, true],
  ['belarus', 30.0, 50.0, false],
  ['minsk_oblast', 27.56, 53.9, true],
  ['brest', 23.7, 52.1, true],
  ['gomel', 31.0, 52.44, true],
  ['vitebsk', 30.2, 55.19, true],
  ['grodno', 23.83, 53.68, true],
  ['mogilev', 30.33, 53.9, true],
];
let failed = 0;
for (const [id, lon, lat, exp] of tests) {
  const r = regions.find((x) => x.id === id);
  const got = raycast(r.rings, lon, lat);
  const ok = got === exp;
  if (!ok) failed++;
  console.log('test', id, lon, lat, 'got', got, 'exp', exp, ok ? 'OK' : 'FAIL');
}

console.log('regions', regions.length, 'failed', failed);
for (const r of regions) {
  console.log(r.id, 'rings', r.rings.length, 'pts', r.rings.reduce((s, x) => s + x.length, 0));
}

function fmt(n) {
  return Number(n).toFixed(7);
}

function areaLit(n) {
  const s = String(n);
  return /[.]/.test(s) ? s : s + '.0';
}

/** Кольца → компактная строка "lon lat,lon lat;lon lat,..." (одна константа, < 64KB в методе). */
function ringsToSpec(rings) {
  return rings
    .map((ring) => ring.map(([lon, lat]) => `${fmt(lon)} ${fmt(lat)}`).join(','))
    .join(';');
}

function regionFn(r) {
  const spec = ringsToSpec(r.rings);
  return `    private fun ${r.id}(): Region = Region(
        id = "${r.id}",
        name = "${r.name}",
        type = RegionType.${r.type.toUpperCase()},
        totalAreaKm2 = ${areaLit(r.area)},
        rings = parseRings("${spec}")
    )`;
}

const order = { city: 0, oblast: 1, republic: 2 };
const sorted = regions.slice().sort((a, b) => {
  if (order[a.type] !== order[b.type]) return order[a.type] - order[b.type];
  return a.name.localeCompare(b.name, 'ru');
});

const kotlin = `package ru.fogmap.region

/**
 * 13 регионов Беларуси (change add-region-progress).
 * Полигоны из OpenStreetMap (Overpass, admin_level=2/4/6),
 * снимок 2026-09-24; упрощение Douglas-Peucker (city 0.0004°, oblast 0.001°,
 * republic 0.002°). Хранятся только outer-кольца: точка в зоне пересечения
 * (Минск в Минской области) попадает в оба счётчика (spec region-progress).
 *
 * Рёбра — строки вида "lon lat,lon lat;..." (одна константа на регион),
 * чтобы не превысить лимит 64KB на метод JVM. parseRings() разбирает их
 * при первой загрузке Regions.ALL.
 *
 * totalAreaKm2 — официальные площади (Wikipedia/OSM), denominator процента.
 */
data class Region(
    val id: String,
    val name: String,
    val type: RegionType,
    val totalAreaKm2: Double,
    /** Внешние кольца: каждое — список (lon, lat), замкнутое. */
    val rings: List<List<Pair<Double, Double>>>
)

enum class RegionType { CITY, OBLAST, REPUBLIC }

object Regions {
    val BY_ID: Map<String, Region> by lazy { ALL.associateBy { it.id } }

    /** Lazy: разбор строк и построение пар — не в <clinit> (лимит 64KB). */
    val ALL: List<Region> by lazy {
        listOf(
${sorted.map((r) => `${r.id}()`).join(', ')}
        )
    }

${sorted.map(regionFn).join('\n\n')}

    /** "lon lat,lon lat;lon lat,..." → List of closed rings as (lon, lat) pairs. */
    private fun parseRings(spec: String): List<List<Pair<Double, Double>>> =
        spec.split(';').map { ringStr ->
            ringStr.split(',').map { pt ->
                val i = pt.indexOf(' ')
                pt.substring(0, i).toDouble() to pt.substring(i + 1).toDouble()
            }
        }
}
`;

const outPath = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'ru', 'fogmap', 'region', 'Regions.kt');
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, kotlin);
console.log('written', outPath, kotlin.length, 'chars');

fs.writeFileSync(
  path.join(__dirname, 'regions_simplified.json'),
  JSON.stringify(
    regions.map((r) => ({
      id: r.id,
      name: r.name,
      type: r.type,
      area: r.area,
      bbox: r.bbox,
      ringCounts: r.rings.map((x) => x.length),
    })),
    null,
    2
  )
);

if (failed > 0) process.exit(1);
