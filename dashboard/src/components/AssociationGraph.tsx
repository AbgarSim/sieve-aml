// The parties an entity is linked to, drawn as a graph on a world map: one node per entity, placed at
// its country, one edge per relation the lists state. Relations follow the Follow the Money model:
// ownership, directorship, family, associate and plain (unknown) links.
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import * as d3 from 'd3';
import * as topojson from 'topojson-client';
import type { Feature, Geometry } from 'geojson';
import type { Topology, GeometryCollection } from 'topojson-specification';
import WORLD from 'world-atlas/countries-110m.json?url';
import { Flag } from './Flag';
import { TypeBadge } from './Badges';
import { Icon, PATHS, TYPE_ICON } from '../lib/icons';
import { SIEVE, TYPE_LABEL, type EntityType } from '../data/snapshot';
import { ISO_NUMERIC, UNNUMBERED_FEATURES, countryName } from '../data/iso';
import { DIRECTED, REL_LABEL, REL_TYPES, loadGraph, other, phrase, type Graph, type Link, type RelType } from '../data/relations';
import type { Entry, Index } from '../data/search';
import { fmt } from '../lib/format';
import './AssociationGraph.css';

type F = Feature<Geometry, { name?: string }> & { id: string };

/** Most links of one entity drawn on the map; the list view has them all. */
const MAX_DRAWN = 40;
/** With this many nodes or fewer every node is labelled; above it only the focused ones. */
const LABEL_ALL = 16;
const R = 12, RC = 16;

/**
 * Where a country's entities are placed, [lon, lat], when its shape's centre would mislead: large
 * countries whose centre lies far from where people and companies are (Russia's is in Siberia), and
 * countries too small for the 110m map geometry, at their capital.
 */
const ANCHOR: Record<string, [number, number]> = {
  RU: [37.62, 55.75], US: [-95, 38.5], CA: [-79.4, 45.5], FR: [2.35, 46.8], NO: [10.75, 59.9], CN: [112, 33], AU: [146, -33],
  BR: [-46.6, -20], CL: [-70.65, -33.45], NZ: [174.8, -41.3], DK: [10, 56], GR: [23.7, 38.5], KI: [173.03, 1.45], FJ: [178.44, -18.14],
  SG: [103.82, 1.35], BH: [50.56, 26.07], MT: [14.51, 35.9], MC: [7.42, 43.74], LI: [9.52, 47.14], AD: [1.52, 42.51],
  SM: [12.46, 43.94], VA: [12.45, 41.9], HK: [114.17, 22.32], MO: [113.54, 22.2], MV: [73.51, 4.18], MU: [57.5, -20.16],
  SC: [55.45, -4.62], BB: [-59.6, 13.1], AG: [-61.85, 17.12], KN: [-62.73, 17.3], LC: [-60.98, 13.91], VC: [-61.2, 13.25],
  GD: [-61.68, 12.12], DM: [-61.37, 15.41], KM: [43.26, -11.7], CV: [-23.51, 14.93], ST: [6.73, 0.34], GI: [-5.35, 36.14],
  JE: [-2.13, 49.21], GG: [-2.54, 49.45], IM: [-4.48, 54.15], BM: [-64.78, 32.3], KY: [-81.25, 19.31], VG: [-64.62, 18.42],
  MH: [171.18, 7.13], PW: [134.58, 7.51], FM: [158.16, 6.92], TV: [179.2, -8.52], NR: [166.93, -0.52],
  TO: [-175.2, -21.14], WS: [-171.76, -13.83], AW: [-70.03, 12.52], CW: [-68.99, 12.17], SX: [-63.05, 18.04], TC: [-71.8, 21.69],
  AI: [-63.07, 18.22], MS: [-62.19, 16.74], GU: [144.79, 13.44], MP: [145.67, 15.18], AS: [-170.7, -14.28], PF: [-149.57, -17.54],
  NC: [166.46, -22.27], RE: [55.45, -20.88], GP: [-61.55, 16.25], MQ: [-61.02, 14.64], YT: [45.17, -12.83], BN: [114.73, 4.54],
};

interface Node {
  key: string; name: string; type: EntityType; cc?: string; entry?: Entry;
  /** Distance from the selected entity in links. */
  depth: number;
  x: number; y: number; placed: boolean;
}

const NUM_TO_CC: Record<string, string> = Object.fromEntries(Object.entries(ISO_NUMERIC).map(([a, n]) => [n, a]));
const short = (s: string, n = 26) => (s.length > n ? s.slice(0, n - 1) + '…' : s);
const cname = (cc?: string) => (cc ? SIEVE.countryByCc[cc]?.name ?? countryName(cc) : 'No country on record');
const sw = (t: RelType) => ({ background: `var(--rel-${t})` });

let world: Promise<F[]> | null = null;
const loadWorld = () => (world ??= fetch(WORLD).then(r => r.json()).then((t: Topology) =>
  (topojson.feature(t, t.objects.countries as GeometryCollection) as unknown as { features: F[] }).features
    .map(f => ({ ...f, id: f.id ?? UNNUMBERED_FEATURES[f.properties?.name ?? ''] ?? '' }))));

export function AssociationGraph({ entry, ix }: { entry: Entry; ix: Index }) {
  const center = entry.group;
  const nav = useNavigate();
  const [graph, setGraph] = useState<Graph | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [features, setFeatures] = useState<F[] | null>(null);
  const [expanded, setExpanded] = useState<string[]>([center]);
  const [hidden, setHidden] = useState<Set<RelType>>(new Set());
  const [view, setView] = useState<'map' | 'list'>('map');
  const [hover, setHover] = useState<string | null>(null);
  const [sel, setSel] = useState<string | null>(null);
  const [tip, setTip] = useState<{ x: number; y: number; key: string } | null>(null);
  const [size, setSize] = useState({ w: 800, h: 480 });
  const [zt, setZt] = useState(d3.zoomIdentity);
  const wrap = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown>>();

  useEffect(() => { let live = true; loadGraph(ix).then(g => live && setGraph(g), e => live && setErr(String(e))); return () => { live = false; }; }, [ix]);
  useEffect(() => { loadWorld().then(setFeatures, () => setFeatures([])); }, []);
  useEffect(() => { setExpanded([center]); setSel(null); setHover(null); setTip(null); }, [center]);
  useEffect(() => { const el = wrap.current; if (!el) return; const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight })); ro.observe(el); return () => ro.disconnect(); }, [graph, view]);

  const node0 = (key: string): Omit<Node, 'depth' | 'x' | 'y' | 'placed'> => {
    const e = ix.byKey.get(key);
    return { key, entry: e, name: e?.name ?? key, type: e?.type ?? 'entity', cc: graph?.home.get(key) };
  };

  /** Every link of the expanded entities, and the share of them drawn. */
  const visible = useMemo(() => {
    if (!graph) return null;
    const depth = new Map<string, number>([[center, 0]]);
    const all = new Map<string, Link>(), drawn = new Map<string, Link>();
    const overflow: { key: string; shown: number; total: number }[] = [];
    expanded.forEach(g => {
      const ls = (graph.links.get(g) ?? []).filter(l => !hidden.has(l.type));
      const d = depth.get(g) ?? 1;
      // Typed links first, then entities linked to many others, so the most telling ones are drawn
      const deg = (k: string) => graph.links.get(k)?.length ?? 0;
      const ranked = [...ls].sort((x, y) => Number(x.type === 'LINKED') - Number(y.type === 'LINKED') || deg(other(y, g)) - deg(other(x, g)) || x.id.localeCompare(y.id));
      ranked.forEach((l, i) => {
        all.set(l.id, l);
        if (i < MAX_DRAWN || expanded.includes(other(l, g))) { drawn.set(l.id, l); const o = other(l, g); if (!depth.has(o)) depth.set(o, d + 1); }
      });
      if (ranked.length > MAX_DRAWN) overflow.push({ key: g, shown: MAX_DRAWN, total: ranked.length });
    });
    return { depth, all: [...all.values()], drawn: [...drawn.values()], overflow };
  }, [graph, expanded, hidden, center]);

  const counts = useMemo(() => {
    const c = Object.fromEntries(REL_TYPES.map(t => [t, 0])) as Record<RelType, number>;
    expanded.forEach(g => (graph?.links.get(g) ?? []).forEach(l => c[l.type]++));
    return c;
  }, [graph, expanded]);

  const layout = useMemo(() => {
    if (!visible || !features) return null;
    const { w, h } = size;
    const byCc = new Map<string, F>();
    features.forEach(f => { const cc = NUM_TO_CC[f.id]; if (cc) byCc.set(cc, f); });
    const lonlat = (cc?: string): [number, number] | null => (!cc ? null : ANCHOR[cc] ?? (byCc.has(cc) ? (d3.geoCentroid(byCc.get(cc)!) as [number, number]) : null));
    const nodes: Node[] = [...visible.depth.entries()].map(([key, depth]) => ({ ...node0(key), depth, x: 0, y: 0, placed: false }));
    const at = new Map(nodes.map(n => [n.key, lonlat(n.cc)]));
    const pts = [...at.values()].filter((p): p is [number, number] => !!p);
    if (!pts.length) return { nodes, edges: [] as { l: Link; s: Node; t: Node }[], path: null, projection: null, onMap: new Set<string>(), land: null };
    // Fit the countries in play, with room around them; never closer in than a region
    let [x0, y0, x1, y1] = [d3.min(pts, p => p[0])!, d3.min(pts, p => p[1])!, d3.max(pts, p => p[0])!, d3.max(pts, p => p[1])!];
    const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, sx = Math.max(x1 - x0, 28) / 2 + 6, sy = Math.max(y1 - y0, 16) / 2 + 5;
    [x0, x1, y0, y1] = [Math.max(-180, cx - sx), Math.min(180, cx + sx), Math.max(-85, cy - sy), Math.min(85, cy + sy)];
    const pad = 36;
    const projection = d3.geoNaturalEarth1().fitExtent([[pad, pad], [w - pad, h - pad]], { type: 'MultiPoint', coordinates: [[x0, y0], [x1, y1], [x0, y1], [x1, y0]] });
    // Entities of one country start on a sunflower spiral around it, then push apart
    type S = d3.SimulationNodeDatum & { n: Node; ax: number; ay: number };
    const perCc = new Map<string, number>();
    const sims: S[] = [];
    [...nodes].sort((a, b) => a.depth - b.depth || a.key.localeCompare(b.key)).forEach(n => {
      const p = at.get(n.key); if (!p) return;
      const [ax, ay] = projection(p)!;
      const i = perCc.get(n.cc!) ?? 0; perCc.set(n.cc!, i + 1);
      const r = 22 * Math.sqrt(i), th = i * 2.39996;
      sims.push({ n, ax, ay, x: ax + r * Math.cos(th), y: ay + r * Math.sin(th), ...(n.key === center ? { fx: ax, fy: ay } : {}) });
      n.placed = true;
    });
    const sim = d3.forceSimulation(sims).force('x', d3.forceX<S>(d => d.ax).strength(0.22)).force('y', d3.forceY<S>(d => d.ay).strength(0.22))
      .force('c', d3.forceCollide<S>(d => (d.n.key === center ? RC : R) + 22).strength(1)).stop();
    for (let i = 0; i < 160; i++) sim.tick();
    sims.forEach(s => { s.n.x = s.x!; s.n.y = s.y!; });
    // An entity with no country sits among the entities it is linked to, drawn dashed
    const byKey = new Map(nodes.map(n => [n.key, n]));
    nodes.filter(n => !n.placed).forEach(n => {
      const nb = visible.drawn.filter(l => l.a === n.key || l.b === n.key).map(l => byKey.get(other(l, n.key))!).filter(o => o?.placed);
      if (!nb.length) return;
      n.x = d3.mean(nb, o => o.x)! + 34; n.y = d3.mean(nb, o => o.y)! - 30; n.placed = true; n.cc = undefined;
    });
    const edges = visible.drawn.map(l => ({ l, s: byKey.get(l.a)!, t: byKey.get(l.b)! })).filter(e => e.s?.placed && e.t?.placed);
    const onMap = new Set(nodes.filter(n => n.placed).map(n => n.key));
    const path = d3.geoPath(projection), ccs = new Set(nodes.map(n => n.cc).filter(Boolean));
    const land = features.map((f, i) => <path key={i} d={path(f) ?? ''} vectorEffect="non-scaling-stroke" style={{ fill: ccs.has(NUM_TO_CC[f.id]) ? 'var(--ag-land-on)' : 'var(--ag-land)', stroke: 'var(--map-stroke)', strokeWidth: 0.6 }} />);
    return { nodes, edges, path, projection, onMap, land };
  }, [visible, features, size, graph, center]);

  useEffect(() => {
    if (!svgRef.current || !layout?.projection) return;
    const svg = d3.select(svgRef.current);
    const zoom = d3.zoom<SVGSVGElement, unknown>().scaleExtent([0.6, 12]).on('zoom', e => setZt(e.transform));
    svg.call(zoom).on('dblclick.zoom', null); zoomRef.current = zoom;
    svg.call(zoom.transform, d3.zoomIdentity);
  }, [layout?.projection, view]);
  const zoomBy = (k: number) => { if (svgRef.current && zoomRef.current) d3.select(svgRef.current).transition().duration(200).call(zoomRef.current.scaleBy, k); };
  const fit = () => { if (svgRef.current && zoomRef.current) d3.select(svgRef.current).transition().duration(250).call(zoomRef.current.transform, d3.zoomIdentity); };

  if (err) return <section className="card ag" aria-labelledby="ag-h"><div className="card-h"><h3 id="ag-h">Associations</h3></div><div className="ag-empty"><Icon name="warn" size={18} />Links could not be loaded: {err}</div></section>;
  if (!graph || !visible) return <section className="card ag" aria-busy="true"><div className="card-h"><h3>Associations</h3></div><div className="card-b"><div className="skl" style={{ height: 280 }} /></div></section>;

  const own = graph.links.get(center) ?? [];
  if (!own.length) return (
    <section className="card ag" aria-labelledby="ag-h">
      <div className="card-h"><h3 id="ag-h">Associations</h3><span className="xs muted">links stated by the lists</span></div>
      <div className="ag-empty"><Icon name="info" size={18} /><span>No list in this snapshot states a link between this {TYPE_LABEL[entry.type].toLowerCase()} and another listed party. Lists that state links include OFAC, the UN, the EU, the UK, Switzerland and the GLEIF ownership register.</span></div>
    </section>
  );

  const nodes = new Map(layout?.nodes.map(n => [n.key, n]) ?? []);
  const nodeOf = (k: string) => nodes.get(k) ?? { ...node0(k), depth: 1, x: 0, y: 0, placed: false };
  const focus = hover ?? sel;
  const near = new Set<string>(); const hotE = new Set<string>();
  if (focus) { near.add(focus); visible.drawn.forEach(l => { if (l.a === focus || l.b === focus) { near.add(l.a); near.add(l.b); hotE.add(l.id); } }); }
  const onMapCount = layout?.onMap.size ?? 0;
  const labelAll = onMapCount <= LABEL_ALL;
  const unplaced = [...visible.depth.keys()].filter(k => !layout?.onMap.has(k));
  const countries = new Set([...visible.depth.keys()].map(k => graph.home.get(k)).filter(Boolean));
  const toggleType = (t: RelType) => setHidden(h => { const n = new Set(h); n.has(t) ? n.delete(t) : n.add(t); return n; });
  const toggleExpand = (k: string) => setExpanded(x => (x.includes(k) ? x.filter(y => y !== k) : [...x, k]));
  const open = (k: string) => { const e = ix.byKey.get(k); if (e) nav(`/entity/${e.source}/${encodeURIComponent(e.id)}`); };
  const P = (n: Node) => zt.apply([n.x, n.y]);

  // Labels go below a node, else above, right or left, wherever they hit no other label or node;
  // the selected entity, the focused one and its neighbours first. A label with no free spot is left
  // to the tooltip, unless it belongs to one of those.
  const labels = new Map<string, { x: number; y: number; anchor: 'middle' | 'start' | 'end' }>();
  if (layout) {
    type Box = [number, number, number, number];
    const taken: Box[] = layout.nodes.filter(n => n.placed).map(n => { const [x, y] = P(n), r = n.key === center ? RC : R; return [x - r, y - r, x + r, y + r]; });
    const hit = (b: Box) => taken.some(t => b[0] < t[2] && b[2] > t[0] && b[1] < t[3] && b[3] > t[1]);
    const must = (n: Node) => n.key === center || n.key === sel || near.has(n.key);
    const order = layout.nodes.filter(n => n.placed && (labelAll || must(n) || zt.k > 1.8)).sort((a, b) => Number(must(b)) - Number(must(a)) || a.depth - b.depth || a.key.localeCompare(b.key));
    for (const n of order) {
      const [x, y] = P(n), r = n.key === center ? RC : R, w = Math.min(short(n.name).length, 26) * 6.2, h = 13;
      const spots: [number, number, 'middle' | 'start' | 'end', Box][] = [
        [0, r + 13, 'middle', [x - w / 2, y + r + 2, x + w / 2, y + r + 2 + h]],
        [0, -r - 6, 'middle', [x - w / 2, y - r - 4 - h, x + w / 2, y - r - 4]],
        [r + 6, 4, 'start', [x + r + 4, y - h / 2, x + r + 4 + w, y + h / 2]],
        [-r - 6, 4, 'end', [x - r - 4 - w, y - h / 2, x - r - 4, y + h / 2]],
      ];
      const spot = spots.find(s => !hit(s[3])) ?? (must(n) ? spots[0] : null);
      if (spot) { labels.set(n.key, { x: spot[0], y: spot[1], anchor: spot[2] }); taken.push(spot[3]); }
    }
  }

  const curve = (s: Node, t: Node, bend: number, rs: number, rt: number) => {
    const [x1, y1] = P(s), [x2, y2] = P(t);
    const dx = x2 - x1, dy = y2 - y1, d = Math.hypot(dx, dy) || 1;
    const cx = (x1 + x2) / 2 - (dy / d) * bend * d, cy = (y1 + y2) / 2 + (dx / d) * bend * d;
    const cut = (px: number, py: number, r: number) => { const ux = cx - px, uy = cy - py, u = Math.hypot(ux, uy) || 1; return [px + (ux / u) * r, py + (uy / u) * r]; };
    const [sx, sy] = cut(x1, y1, rs), [tx, ty] = cut(x2, y2, rt + 2);
    return { d: `M${sx},${sy} Q${cx},${cy} ${tx},${ty}`, mx: (sx + 2 * cx + tx) / 4, my: (sy + 2 * cy + ty) / 4 };
  };
  // Several links between the same two entities fan out
  const pairIdx = new Map<string, number>(), pairN = new Map<string, number>();
  layout?.edges.forEach(({ l }) => { const p = [l.a, l.b].sort().join('|'); pairN.set(p, (pairN.get(p) ?? 0) + 1); });

  const showTip = (e: React.MouseEvent | React.FocusEvent, key: string) => {
    const r = wrap.current?.getBoundingClientRect(); if (!r) return;
    let x: number, y: number;
    if ('clientX' in e) { x = e.clientX - r.left + 14; y = e.clientY - r.top + 14; } else { const [px, py] = P(nodeOf(key)); x = px + 18; y = py + 18; }
    if (x > r.width - 270) x -= 290; if (y > r.height - 150) y -= 170;
    setTip({ x, y, key }); setHover(key);
  };
  const hideTip = () => { setTip(null); setHover(null); };

  const relRow = (l: Link, from: string) => {
    const o = other(l, from), on = nodeOf(o);
    return (
      <div key={l.id} className="ag-rel">
        <div className="h"><span className="sw" style={sw(l.type)} aria-hidden="true" /><span className="muted small" style={{ flex: 'none' }}>{phrase(l, from)}</span><button onClick={() => setSel(o)} title={on.name}>{on.name}</button></div>
        <div className="xs muted" style={{ paddingLeft: 16 }}>{REL_LABEL[l.type]}{l.roles.length > 0 && ` · “${l.roles.slice(0, 2).join('”, “')}”`}{l.share != null && ` · ${l.share}%`}{l.start && ` · since ${l.start}`}{l.end && ` · until ${l.end}`} · {l.lists.map(s => SIEVE.byId[s]?.name ?? s).join(', ')}</div>
      </div>
    );
  };

  const selNode = sel ? nodeOf(sel) : null;
  const selLinks = sel ? visible.all.filter(l => l.a === sel || l.b === sel) : [];
  const selAll = sel ? (graph.links.get(sel) ?? []).filter(l => !hidden.has(l.type)) : [];

  return (
    <section className="card ag" aria-labelledby="ag-h">
      <div className="card-h">
        <h3 id="ag-h">Associations</h3>
        <span className="xs muted">{fmt(own.length)} link{own.length === 1 ? '' : 's'} stated by the lists · {countries.size} countr{countries.size === 1 ? 'y' : 'ies'}</span>
      </div>
      <div className="filters">
        <div role="group" aria-label="Relation types" style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {REL_TYPES.filter(t => counts[t] > 0).map(t => (
            <button key={t} className="ag-key" aria-pressed={!hidden.has(t)} onClick={() => toggleType(t)} title={`${hidden.has(t) ? 'Show' : 'Hide'} ${REL_LABEL[t].toLowerCase()} links`}>
              <span className={'sw' + (t === 'LINKED' ? ' dash' : '')} style={{ borderColor: `var(--rel-${t})` }} />{REL_LABEL[t]}<span className="num">{counts[t]}</span>
            </button>
          ))}
        </div>
        <span className="sp" />
        <div className="seg" role="group" aria-label="View"><button aria-pressed={view === 'map'} onClick={() => setView('map')}>Map</button><button aria-pressed={view === 'list'} onClick={() => setView('list')}>List</button></div>
      </div>

      {view === 'map' ? (
        <div className="ag-body">
          <div className="ag-map" ref={wrap} onMouseLeave={hideTip}>
            {layout?.path ? (
              <svg ref={svgRef} viewBox={`0 0 ${size.w} ${size.h}`} role="group" aria-label={`Map of the entities linked to ${entry.name}`} onClick={e => { if (e.target === svgRef.current) setSel(null); }}>
                <defs>{REL_TYPES.filter(t => DIRECTED.has(t)).map(t => <marker key={t} id={`ag-a-${t}`} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,1 L10,5 L0,9 z" style={{ fill: `var(--rel-${t})` }} /></marker>)}</defs>
                <g transform={zt.toString()} aria-hidden="true">{layout.land}</g>
                <g>
                  {layout.edges.map(({ l, s, t }) => {
                    const p = [l.a, l.b].sort().join('|'); const i = pairIdx.get(p) ?? 0; pairIdx.set(p, i + 1);
                    const n = pairN.get(p) ?? 1, bend = 0.12 + (i - (n - 1) / 2) * 0.22;
                    const c = curve(s, t, bend, s.key === center ? RC : R, t.key === center ? RC : R);
                    const cls = 'ag-e ' + l.type + (focus ? (hotE.has(l.id) ? ' hot' : ' dim') : '');
                    return <path key={l.id} className={cls} d={c.d} style={{ stroke: `var(--rel-${l.type})` }} markerEnd={DIRECTED.has(l.type) ? `url(#ag-a-${l.type})` : undefined}><title>{`${s.name} ${phrase(l, l.a)} ${t.name}`}</title></path>;
                  })}
                </g>
                <g>
                  {layout.nodes.filter(n => n.placed).sort((a, b) => b.depth - a.depth).map(n => {
                    const [x, y] = P(n), r = n.key === center ? RC : R, isSel = sel === n.key;
                    const cls = 'ag-n' + (n.key === center ? ' center' : '') + (!n.cc ? ' unplaced' : '') + (isSel ? ' sel' : '') + (focus && !near.has(n.key) ? ' dim' : '');
                    const label = labels.get(n.key);
                    const more = (graph.links.get(n.key)?.length ?? 0) - (expanded.includes(n.key) ? (graph.links.get(n.key)?.length ?? 0) : 1);
                    return (
                      <g key={n.key} className={cls} transform={`translate(${x},${y})`} tabIndex={0} role="button"
                        aria-label={`${n.name}, ${TYPE_LABEL[n.type]}, ${cname(n.cc)}${n.key === center ? ', this entity' : ''}`} aria-pressed={isSel}
                        onMouseMove={e => showTip(e, n.key)} onMouseLeave={hideTip} onFocus={e => showTip(e, n.key)} onBlur={hideTip}
                        onClick={e => { e.stopPropagation(); setSel(isSel ? null : n.key); }}
                        onDoubleClick={() => n.key !== center && toggleExpand(n.key)}
                        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSel(isSel ? null : n.key); } }}>
                        <circle className="ring" r={r + 4} />
                        <circle className="disc" r={r} />
                        <svg className="ico" x={-r * 0.62} y={-r * 0.62} width={r * 1.24} height={r * 1.24} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}><path strokeLinecap="round" strokeLinejoin="round" d={PATHS[TYPE_ICON[n.type]]} /></svg>
                        {more > 0 && !expanded.includes(n.key) && <text className="ag-cnt" x={r + 2} y={-r + 2}>+{more}</text>}
                        {label && <text className="ag-lbl" x={label.x} y={label.y} textAnchor={label.anchor}>{short(n.name)}</text>}
                      </g>
                    );
                  })}
                </g>
              </svg>
            ) : layout ? (
              <div className="ag-empty" style={{ height: '100%', alignItems: 'center', justifyContent: 'center' }}><Icon name="globe" size={18} />None of these entities has a country on record, so they are shown as a list.</div>
            ) : <div className="skl" style={{ position: 'absolute', inset: 16 }} />}
            {layout?.path && <div className="map-ctl"><button aria-label="Zoom in" onClick={() => zoomBy(1.6)}>+</button><button aria-label="Zoom out" onClick={() => zoomBy(1 / 1.6)}>−</button><button aria-label="Fit all entities" style={{ fontSize: 11 }} onClick={fit}>⟲</button></div>}
            {tip && (() => {
              const n = nodeOf(tip.key);
              const toCenter = tip.key === center ? [] : own.filter(l => other(l, center) === tip.key);
              const links = graph.links.get(tip.key)?.length ?? 0;
              return (
                <div className="tip on" style={{ left: tip.x, top: tip.y }} role="tooltip">
                  <div className="t"><Flag cc={n.cc} />{n.name}</div>
                  <div className="xs muted">{TYPE_LABEL[n.type]} · {cname(n.cc)}</div>
                  {toCenter.map(l => <div key={l.id} className="row" style={{ marginTop: 6 }}><span><i style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 2, marginRight: 6, ...sw(l.type) }} />{phrase(l, tip.key)} {short(entry.name, 22)}</span></div>)}
                  <div className="xs muted" style={{ marginTop: 6 }}>{fmt(links)} link{links === 1 ? '' : 's'} in total · click for details</div>
                </div>
              );
            })()}
          </div>

          <aside className="ag-side" aria-live="polite">
            {selNode ? <>
              <div>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}><TypeBadge type={selNode.type} />{selNode.key === center && <span className="badge acc">This entity</span>}</div>
                <div className="nm">{selNode.name}</div>
                <div className="small muted" style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}><Flag cc={graph.home.get(selNode.key)} />{cname(graph.home.get(selNode.key))}</div>
              </div>
              <div className="ag-acts">
                {selNode.key !== center && <button className="btn pri sm" onClick={() => open(selNode.key)}>Open profile <Icon name="right" size={12} /></button>}
                {selNode.key !== center && selAll.length > 1 && <button className="btn sm" aria-pressed={expanded.includes(selNode.key)} onClick={() => toggleExpand(selNode.key)}>{expanded.includes(selNode.key) ? 'Hide its links' : `Show its ${fmt(selAll.length)} links`}</button>}
              </div>
              <div><h4>Links shown ({selLinks.length})</h4>{selLinks.map(l => relRow(l, selNode.key))}</div>
            </> : <>
              <div><h4>Linked parties</h4><div className="nm" style={{ marginTop: 4 }}>{fmt(visible.depth.size - 1)} <span className="small muted" style={{ fontWeight: 400 }}>in {countries.size} countr{countries.size === 1 ? 'y' : 'ies'}</span></div></div>
              <p className="small muted">Select a party to see how it is linked, open its profile or show its own links. Drag to pan, scroll or use + and − to zoom.</p>
              <div><h4>Links of {short(entry.name, 30)}</h4>{own.filter(l => !hidden.has(l.type)).slice(0, 60).map(l => relRow(l, center))}</div>
            </>}
          </aside>
        </div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table className="tbl ag-tbl">
            <thead><tr><th>Party</th><th>Relation</th><th>Linked party</th><th>Country</th><th>Stated by</th></tr></thead>
            <tbody>{visible.all.map(l => {
              const a = nodeOf(l.a), b = nodeOf(l.b);
              const [from, to] = l.b === center || (!DIRECTED.has(l.type) && l.b !== center && expanded.includes(l.b) && !expanded.includes(l.a)) ? [b, a] : [a, b];
              const ent = (n: Node) => n.entry ? <RouterLink to={`/entity/${n.entry.source}/${encodeURIComponent(n.entry.id)}`}>{n.name}</RouterLink> : n.name;
              return (
                <tr key={l.id}>
                  <td>{ent(from)}</td>
                  <td><span className="sw" style={sw(l.type)} aria-hidden="true" />{phrase(l, from.key)}<div className="xs muted">{REL_LABEL[l.type]}{l.roles.length > 0 && ` · “${l.roles[0]}”`}{l.share != null && ` · ${l.share}%`}</div></td>
                  <td>{ent(to)}</td>
                  <td><span className="cl"><Flag cc={graph.home.get(to.key)} />{cname(graph.home.get(to.key))}</span></td>
                  <td className="small">{l.lists.map(s => SIEVE.byId[s]?.name ?? s).join(', ')}</td>
                </tr>
              );
            })}</tbody>
          </table>
        </div>
      )}

      {view === 'map' && unplaced.length > 0 && layout && (
        <div className="ag-tray"><span>Not on the map, no country on record:</span>{unplaced.map(k => { const n = nodeOf(k); return <button key={k} onClick={() => setSel(k)}><Icon name={TYPE_ICON[n.type]} size={12} />{short(n.name, 32)}</button>; })}</div>
      )}
      {visible.overflow.length > 0 && view === 'map' && (
        <div className="ag-note"><span>{visible.overflow.map(o => `${short(nodeOf(o.key).name, 30)} has ${fmt(o.total)} links; the map shows ${o.shown}`).join(' · ')}.</span><button className="btn sm" onClick={() => setView('list')}><Icon name="list" size={14} />See all as a list</button></div>
      )}
      <div className="ag-note"><span>Links as the lists state them, folded across lists; each party sits at its nationality, else its address or flag. Relation kinds follow the Follow the Money model.</span>{expanded.length > 1 && <button className="btn sm" onClick={() => { setExpanded([center]); setSel(null); }}>Back to direct links</button>}</div>
    </section>
  );
}
