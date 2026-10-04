"use client";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import Shell, { ErrorBox, Status, useMe } from "./shell";
import { api, App, Metrics, Operation, uptime, size, labels } from "./api";
import SpecForm from "./spec-form";
import Files from "./files";
import HistoryChart from "./history-chart";

function Console({ appId }: { appId: string }) {
  const [paused, setPaused] = useState(false);
  const [search, setSearch] = useState("");
  const logs = useQuery<{ text: string }>({
    queryKey: ["logs", appId],
    queryFn: () => api(`/applications/${appId}/logs`),
    refetchInterval: paused ? false : 2500,
  });
  return (
    <div className="panel">
      <div className="toolbar">
        <input
          placeholder="Поиск в последних 300 строках…"
          aria-label="Поиск логов"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <button onClick={() => setPaused(!paused)}>
          {paused ? "Продолжить" : "Пауза"}
        </button>
        <button onClick={() => logs.refetch()}>Обновить</button>
        <button
          disabled={!logs.data}
          onClick={() => {
            const url = URL.createObjectURL(
              new Blob([logs.data?.text ?? ""], { type: "text/plain" }),
            );
            const a = document.createElement("a");
            a.href = url;
            a.download = "logs.txt";
            a.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
          }}
        >
          Скачать
        </button>
      </div>
      <ErrorBox error={logs.error} />
      <pre className="console" aria-live={paused ? "off" : "polite"}>
        {logs.data?.text
          .split("\n")
          .filter((line) => line.toLowerCase().includes(search.toLowerCase()))
          .join("\n") || "Логи появятся после первого запуска."}
      </pre>
    </div>
  );
}
export default function Application({ appId }: { appId: string }) {
  const me = useMe();
  const client = useQueryClient();
  const router = useRouter();
  const [tab, setTab] = useState("Обзор");
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [operation, setOperation] = useState<string>();
  const [stream, setStream] = useState("Подключение…");
  const app = useQuery<App>({
    queryKey: ["app", appId],
    queryFn: () => api(`/applications/${appId}`),
    enabled: !!me.data,
    refetchInterval: 3000,
  });
  const metrics = useQuery<Metrics>({
    queryKey: ["metrics", appId],
    queryFn: () => api(`/applications/${appId}/metrics`),
    enabled: !!app.data,
    refetchInterval: 2000,
  });
  const opId = operation || app.data?.active_operation;
  const op = useQuery<Operation>({
    queryKey: ["operation", opId],
    queryFn: () => api(`/operations/${opId}`),
    enabled: !!opId,
    refetchInterval: (q) =>
      q.state.data?.status === "SUCCEEDED" || q.state.data?.status === "FAILED"
        ? false
        : 1000,
  });
  useEffect(() => {
    if (!me.data) return;
    let ws: WebSocket;
    let retry: ReturnType<typeof setTimeout>;
    let stopped = false;
    let attempts = 0;
    function connect() {
      if (stopped) return;
      ws = new WebSocket(
        `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/applications/${appId}`,
      );
      ws.onopen = () => {
        attempts = 0;
        setStream("Live");
      };
      ws.onmessage = (e) => {
        try {
          client.setQueryData(["metrics", appId], JSON.parse(e.data));
        } catch {
          /* next snapshot replaces a malformed event */
        }
      };
      ws.onclose = () => {
        setStream("Переподключение · HTTP-обновление активно");
        retry = setTimeout(connect, Math.min(30000, 1000 * 2 ** attempts++));
      };
    }
    connect();
    return () => {
      stopped = true;
      clearTimeout(retry);
      ws?.close();
    };
  }, [appId, client, me.data]);
  const workspace = me.data?.workspaces.find(
    (w) => w.id === app.data?.workspace_id,
  );
  const owner = !!me.data?.admin || workspace?.role === "OWNER";
  const writable = owner || workspace?.role === "OPERATOR";
  const pending = busy || !!app.data?.active_operation;
  async function action(name: string) {
    setBusy(true);
    setError(undefined);
    try {
      const o = await api<{ operationId: string }>(
        `/applications/${appId}/actions/${name}`,
        "POST",
        {},
        crypto.randomUUID(),
      );
      setOperation(o.operationId);
      await app.refetch();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  const m = metrics.data;
  const a = app.data;
  return (
    <Shell
      title={a?.name ?? "Приложение"}
      description={
        a
          ? `Temurin ${a.spec.jdk} · ${a.spec.cpu} vCPU · ${size(a.spec.memoryMiB * 1024 ** 2)} RAM`
          : "Загружаем конфигурацию…"
      }
      actions={
        <Link className="button" href="/">
          ← Приложения
        </Link>
      }
    >
      <ErrorBox error={error || app.error || metrics.error || op.error} />
      {a && (
        <>
          <div className="app-toolbar">
            <Status state={a.observed} />
            <span className="muted">
              {m?.nodeOnline
                ? stream
                : "Узел недоступен · состояние может быть устаревшим"}
            </span>
            {writable && (
              <div className="row-actions">
                <button
                  className="primary"
                  disabled={pending || !m?.nodeOnline}
                  onClick={() => action("start")}
                >
                  ▶ Запустить
                </button>
                <button
                  disabled={pending || !m?.nodeOnline}
                  onClick={() => action("stop")}
                >
                  ■ Остановить
                </button>
                <button
                  disabled={pending || !m?.nodeOnline}
                  onClick={() => action("restart")}
                >
                  ↻ Перезапустить
                </button>
              </div>
            )}
          </div>
          {op.data && (
            <div
              className={`notice ${op.data.status === "FAILED" ? "error" : ""}`}
              role="status"
            >
              Операция: {labels[op.data.status] ?? op.data.status}
              {op.data.result && (
                <p>
                  {JSON.parse(op.data.result).message ??
                    (op.data.status === "SUCCEEDED"
                      ? "Узел подтвердил выполнение"
                      : "")}
                </p>
              )}
            </div>
          )}
          {a.revision !== a.applied_revision && (
            <div className="notice">
              Сохранена ревизия {a.revision}; применена {a.applied_revision}.{" "}
              {writable && (
                <button disabled={pending} onClick={() => action("apply")}>
                  Применить с перезапуском
                </button>
              )}
            </div>
          )}
          <nav className="tabs" aria-label="Разделы приложения">
            {["Обзор", "Консоль", "Файлы", "Запуск и ENV", "Сеть"].map((t) => (
              <button
                key={t}
                className={tab === t ? "active" : ""}
                onClick={() => setTab(t)}
              >
                {t}
              </button>
            ))}
          </nav>
          {tab === "Обзор" && (
            <>
              <div className="stats-grid">
                <div className="stat-card">
                  <small>CPU / ВЫДЕЛЕННАЯ КВОТА</small>
                  <strong>
                    {m?.nodeOnline ? (m.cpuPercent ?? 0).toFixed(1) : "—"}
                    <em>%</em>
                  </strong>
                  <progress
                    max="100"
                    value={Math.min(100, m?.cpuPercent ?? 0)}
                  />
                  <span className="muted">
                    {(m?.cpuCores ?? 0).toFixed(2)} из {a.spec.cpu} vCPU
                  </span>
                </div>
                <div className="stat-card">
                  <small>RAM / CGROUP</small>
                  <strong>{m?.nodeOnline ? m.memory || "0 B" : "—"}</strong>
                  <progress max="100" value={m?.memoryPercent ?? 0} />
                  <span className="muted">
                    Heap до {Math.floor(a.spec.memoryMiB * 0.7)} MiB
                  </span>
                </div>
                <div className="stat-card">
                  <small>ДИСК / ЖЁСТКАЯ КВОТА</small>
                  <strong>{size(a.spec.diskMiB * 1024 ** 2)}</strong>
                  <span className="muted">
                    XFS project quota · 100 000 inode
                  </span>
                </div>
                <div className="stat-card">
                  <small>UPTIME</small>
                  <strong className="small-value">
                    {m?.nodeOnline ? uptime(m?.uptimeSeconds) : "—"}
                  </strong>
                  <span className="muted">
                    {m?.healthy == null
                      ? "Health check не задан"
                      : m.healthy
                        ? "TCP health: доступен"
                        : "TCP health: ошибка"}
                  </span>
                </div>
              </div>
              <HistoryChart appId={appId} />
              <div className="panel">
                <h2>Состояние выполнения</h2>
                {m?.oomKilled && (
                  <div className="notice error">
                    Контейнер завершён из-за превышения RAM (OOM).
                  </div>
                )}
                <dl className="details">
                  <dt>Состояние узла</dt>
                  <dd>{m?.nodeOnline ? "На связи" : "Недоступен"}</dd>
                  <dt>Обновлено</dt>
                  <dd>
                    {m?.sampledAt
                      ? new Date(m.sampledAt).toLocaleString("ru")
                      : "—"}
                  </dd>
                  <dt>Диск (блоки XFS)</dt>
                  <dd>
                    <pre>
                      {m?.diskReport ||
                        "Данные появятся после подготовки каталога"}
                    </pre>
                  </dd>
                  <dt>Disk I/O</dt>
                  <dd>{m?.io || "—"}</dd>
                  <dt>Сеть RX / TX</dt>
                  <dd>{m?.network || "—"}</dd>
                  <dt>Exit code</dt>
                  <dd>{m?.exitCode ?? "—"}</dd>
                  <dt>Автозапуск</dt>
                  <dd>{a.spec.autostart ? "Включён" : "Выключен"}</dd>
                </dl>
              </div>
              <p className="muted">
                stdout/stderr ротируются: до 3 × 10 MiB, отдельно от файловой
                квоты.
              </p>
            </>
          )}
          {tab === "Консоль" && <Console appId={appId} />}
          {tab === "Файлы" && <Files appId={appId} writable={writable} />}
          {tab === "Запуск и ENV" &&
            (owner ? (
              <div className="panel">
                <SpecForm
                  key={a.revision}
                  initial={a.spec}
                  onSave={async (spec) => {
                    await api(`/applications/${appId}`, "PATCH", {
                      spec,
                      revision: a.revision,
                    });
                    await app.refetch();
                  }}
                />
                <div className="danger-zone">
                  <h3>Удаление приложения</h3>
                  <p>
                    Контейнер и выделенные порты будут удалены. По умолчанию
                    файлы сохраняются.
                  </p>
                  <button
                    className="danger"
                    disabled={pending}
                    onClick={async () => {
                      if (
                        window.prompt(
                          `Введите имя «${a.name}» для удаления`,
                        ) !== a.name
                      )
                        return;
                      const keep = !window.confirm(
                        "Также безвозвратно удалить все файлы? Отмена сохранит файлы.",
                      );
                      try {
                        await api(
                          `/applications/${appId}?keepFiles=${keep}`,
                          "DELETE",
                        );
                        router.push("/");
                      } catch (e) {
                        setError(e);
                      }
                    }}
                  >
                    Удалить приложение
                  </button>
                </div>
              </div>
            ) : (
              <div className="notice">
                Изменение конфигурации доступно владельцу рабочей области.
              </div>
            ))}
          {tab === "Сеть" && (
            <div className="panel">
              <h2>Выделенные порты</h2>
              <table>
                <thead>
                  <tr>
                    <th>Хост</th>
                    <th>Контейнер</th>
                    <th>Протокол</th>
                    <th>Доступ</th>
                  </tr>
                </thead>
                <tbody>
                  {a.spec.ports.map((p) => (
                    <tr key={`${p.host}/${p.protocol}`}>
                      <td>{p.host}</td>
                      <td>{p.container}</td>
                      <td>{p.protocol.toUpperCase()}</td>
                      <td>{p.public ? "Публичный IPv4" : "127.0.0.1"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!a.spec.ports.length && (
                <p className="muted">Порты не опубликованы.</p>
              )}
              <p className="muted">
                Изменения портов применяются вместе с ревизией запуска. Частные
                сети и служебные адреса хоста закрыты политикой Docker.
              </p>
              {owner && (
                <button onClick={() => setTab("Запуск и ENV")}>
                  Настроить порты
                </button>
              )}
            </div>
          )}
        </>
      )}
    </Shell>
  );
}
