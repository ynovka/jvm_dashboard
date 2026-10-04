"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import { ErrorBox } from "./shell";
type Series = { metric: { generation?: string }; values: [number, string][] };
type History = { start: number; end: number; step: number; series: { cpu: Series[]; memory: Series[]; disk: Series[] } };
function Chart({ data, start, end, step, label, unit, scale = 1 }: { data: Series[]; start: number; end: number; step: number; label: string; unit: string; scale?: number }) {
  const values = data.flatMap(s => s.values.map(v => +v[1] / scale)).filter(Number.isFinite);
  const max = Math.max(1, ...values) * 1.1;
  return <div className="stat-card"><small>{label}</small>{!values.length ? <p className="muted">История появится после сбора метрик.</p> : <><svg viewBox="0 0 480 130" role="img" aria-label={`${label}, ${unit}`} style={{ width: "100%" }}><path d="M0 110 H480 M0 55 H480" stroke="#ffffff14" fill="none" />{data.map((s, i) => { let previous = 0; const path = s.values.filter(v => Number.isFinite(+v[1])).map(([t, v]) => { const command = !previous || t - previous > step * 2 ? "M" : "L"; previous = t; return `${command}${((t - start) / (end - start) * 480).toFixed(1)},${(110 - +v / scale / max * 100).toFixed(1)}`; }).join(" "); return <path key={i} d={path} stroke={i % 2 ? "#60a5fa" : "#6ee7b7"} strokeWidth="2" fill="none"><title>Generation {s.metric.generation} · {unit}</title></path>; })}</svg><span className="muted">Максимум: {Math.max(...values).toFixed(2)} {unit} · {new Date(start * 1000).toLocaleTimeString("ru")} — {new Date(end * 1000).toLocaleTimeString("ru")}</span></>}</div>;
}
export default function HistoryChart({ appId }: { appId: string }) {
  const [hours, setHours] = useState(1);
  const query = useQuery<History>({ queryKey: ["history", appId, hours], queryFn: () => api(`/applications/${appId}/history?hours=${hours}`), refetchInterval: 15000 });
  return <div className="panel"><div className="section-heading"><h2>История ресурсов</h2><select aria-label="Интервал метрик" value={hours} onChange={e => setHours(+e.target.value)} style={{ width: 170 }}><option value="1">Последний час</option><option value="6">6 часов</option><option value="24">24 часа</option></select></div><ErrorBox error={query.error} />{query.data && <div className="history-grid"><Chart {...query.data} data={query.data.series.cpu} label="CPU" unit="vCPU" /><Chart {...query.data} data={query.data.series.memory} label="RAM" unit="%" /><Chart {...query.data} data={query.data.series.disk} label="ДИСК" unit="MiB" scale={1024 ** 2} /></div>}<p className="muted">Prometheus · шаг от 15 секунд · разрывы означают отсутствие измерений.</p></div>;
}
