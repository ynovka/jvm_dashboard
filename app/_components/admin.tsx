"use client";
import { useEffect, useState, FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import Shell, { ErrorBox, useMe } from "./shell";
type Firewall = {
  enabled: boolean;
  raw: string;
  generation: number;
  pending?: { id: string; expires: number };
  rules: {
    id: string;
    protocol: string;
    decision: string;
    port: string;
    source: string;
    description: string;
  }[];
};
export default function Admin() {
  const me = useMe();
  const [clock, setClock] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setClock(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const node = useQuery<{
    heartbeat: number;
    ready: boolean;
    cpu: number;
    memory_mib: number;
    disk_mib: number;
  }>({
    queryKey: ["node"],
    queryFn: () => api("/nodes/local"),
    enabled: !!me.data?.admin,
    refetchInterval: 5000,
  });
  const firewall = useQuery<Firewall>({
    queryKey: ["firewall"],
    queryFn: () => api("/nodes/local/firewall", "POST", { action: "status" }),
    enabled: !!me.data?.admin,
    refetchInterval: 10000,
  });
  async function change(body: unknown) {
    setBusy(true);
    setError(undefined);
    try {
      await api("/nodes/local/firewall", "POST", body);
      await firewall.refetch();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  async function add(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    await change({
      action: "add",
      generation: firewall.data?.generation,
      protocol: f.get("protocol"),
      decision: f.get("decision"),
      port: f.get("port"),
      source: f.get("source") || "any",
      description: f.get("description"),
    });
  }
  return (
    <Shell
      title="Узел и системные порты"
      description="Состояние сервера, ресурсы размещения и политика UFW."
    >
      {!me.data?.admin ? (
        <div className="notice">Требуется администратор платформы.</div>
      ) : (
        <>
          <ErrorBox error={error || node.error || firewall.error} />
          <div className="stats-grid">
            <div className="stat-card">
              <small>LOCAL NODE</small>
              <strong>
                {node.data &&
                clock - node.data.heartbeat < 15000 &&
                node.data.ready
                  ? "Готов"
                  : "Недоступен"}
              </strong>
            </div>
            <div className="stat-card">
              <small>CPU БЮДЖЕТ</small>
              <strong>{node.data?.cpu ?? "—"} vCPU</strong>
            </div>
            <div className="stat-card">
              <small>RAM БЮДЖЕТ</small>
              <strong>{node.data?.memory_mib ?? "—"} MiB</strong>
            </div>
            <div className="stat-card">
              <small>ДИСК БЮДЖЕТ</small>
              <strong>{node.data?.disk_mib ?? "—"} MiB</strong>
            </div>
          </div>
          <div className="panel">
            <div className="section-heading">
              <h2>UFW · {firewall.data?.enabled ? "включён" : "выключен"}</h2>
              <button
                disabled={busy || !firewall.data || !!firewall.data.pending}
                onClick={() => {
                  if (
                    window.confirm(
                      `Изменить состояние UFW? Потребуется подтверждение связи в течение 90 секунд.`,
                    )
                  )
                    void change({
                      action: "toggle",
                      enabled: !firewall.data?.enabled,
                      generation: firewall.data?.generation,
                    });
                }}
              >
                {firewall.data?.enabled ? "Выключить" : "Включить"}
              </button>
            </div>
            {firewall.data?.pending && (
              <div className="notice warning">
                <p>
                  Подтвердите доступность панели до{" "}
                  {new Date(firewall.data.pending.expires).toLocaleTimeString(
                    "ru",
                  )}
                  . Без подтверждения агент откатит изменение.
                </p>
                <button
                  className="primary"
                  disabled={busy}
                  onClick={() =>
                    change({
                      action: "confirm",
                      id: firewall.data?.pending?.id,
                    })
                  }
                >
                  Панель доступна — подтвердить
                </button>
                <button
                  onClick={() =>
                    change({
                      action: "rollback",
                      id: firewall.data?.pending?.id,
                    })
                  }
                >
                  Откатить сейчас
                </button>
              </div>
            )}
            <form onSubmit={add} className="firewall-form">
              <label>
                Действие
                <select name="decision">
                  <option value="allow">Allow</option>
                  <option value="deny">Deny</option>
                </select>
              </label>
              <label>
                Протокол
                <select name="protocol">
                  <option>tcp</option>
                  <option>udp</option>
                </select>
              </label>
              <label>
                Порт / диапазон
                <input
                  name="port"
                  required
                  placeholder="25565 или 10000:10100"
                  pattern="[0-9]{1,5}(:[0-9]{1,5})?"
                />
              </label>
              <label>
                Source IP / CIDR
                <input name="source" placeholder="any" />
              </label>
              <label>
                Описание
                <input name="description" maxLength={60} />
              </label>
              <button
                className="primary"
                disabled={busy || !firewall.data || !!firewall.data.pending}
              >
                Добавить правило
              </button>
            </form>
            <table>
              <thead>
                <tr>
                  <th>Действие</th>
                  <th>Порт</th>
                  <th>Источник</th>
                  <th>Описание</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {firewall.data?.rules.map((r) => (
                  <tr key={r.id}>
                    <td>{r.decision}</td>
                    <td>
                      {r.port}/{r.protocol}
                    </td>
                    <td>{r.source}</td>
                    <td>{r.description}</td>
                    <td>
                      <button
                        disabled={busy || !!firewall.data?.pending}
                        onClick={() => {
                          if (window.confirm("Удалить правило?"))
                            void change({
                              action: "remove",
                              id: r.id,
                              generation: firewall.data?.generation,
                            });
                        }}
                      >
                        Удалить
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <h3>Фактические правила, включая внешние</h3>
            <pre className="console compact">
              {firewall.data?.raw || "Загружаем UFW…"}
            </pre>
            <button
              disabled={busy || !!firewall.data?.pending}
              onClick={() => {
                const number = window.prompt(
                  "Номер внешнего правила из списка. Изменения SSH/панели также требуют подтверждения связи.",
                );
                if (number && /^\d+$/.test(number))
                  void change({
                    action: "removeExternal",
                    number: Number(number),
                    generation: firewall.data?.generation,
                  });
              }}
            >
              Удалить внешнее правило по номеру
            </button>
            <p className="muted">
              UFW защищает вход на хост. Docker-публикации фильтруются отдельно
              в DOCKER-USER. Служебные порты не открываются в Интернет.
            </p>
          </div>
        </>
      )}
    </Shell>
  );
}
