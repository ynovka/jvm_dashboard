"use client";
import { FormEvent, useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, App } from "./api";
import Shell, { ErrorBox, useMe } from "./shell";
type Member = {
  id: string;
  name: string;
  email: string;
  role: string;
  disabled: boolean;
};
type Invitation = {
  id: string;
  email: string | null;
  role: string;
  expires: number;
  used: boolean;
  revoked: boolean;
};
export default function Members() {
  const me = useMe();
  const [clock, setClock] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setClock(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const [ws, setWs] = useState("");
  const [url, setUrl] = useState("");
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const selected = ws || me.data?.workspaces[0]?.id;
  const owner =
    !!me.data?.admin ||
    me.data?.workspaces.find((w) => w.id === selected)?.role === "OWNER";
  const data = useQuery<{ items: Member[]; invitations: Invitation[] }>({
    queryKey: ["members", selected],
    queryFn: () => api(`/workspaces/${selected}/members`),
    enabled: !!selected && owner,
  });
  const apps = useQuery<{ items: App[] }>({
    queryKey: ["member-apps", selected],
    queryFn: () => api(`/applications?workspaceId=${selected}&size=100`),
    enabled: !!selected && owner,
  });
  async function invite(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    const f = new FormData(e.currentTarget);
    try {
      const result = await api<{ url: string }>(
        `/workspaces/${selected}/invitations`,
        "POST",
        { role: f.get("role"), email: f.get("email") },
      );
      setUrl(result.url);
      await data.refetch();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Shell
      title="Участники"
      description="Приглашения действуют 24 часа и используются один раз."
    >
      <div className="toolbar">
        <select
          aria-label="Рабочая область"
          value={selected}
          onChange={(e) => {
            setWs(e.target.value);
            setUrl("");
          }}
        >
          {me.data?.workspaces.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name}
            </option>
          ))}
        </select>
        {!!me.data?.admin && (
          <button
            onClick={async () => {
              const name = window.prompt("Имя рабочей области");
              if (!name) return;
              const cpu = window.prompt("Квота vCPU", "1");
              const ram = window.prompt("Квота RAM MiB", "1024");
              const disk = window.prompt("Квота диска MiB", "2048");
              try {
                await api("/workspaces", "POST", {
                  name,
                  cpu: Number(cpu),
                  memory_mib: Number(ram),
                  disk_mib: Number(disk),
                });
                await me.refetch();
              } catch (e) {
                setError(e);
              }
            }}
          >
            + Рабочая область
          </button>
        )}
      </div>
      <ErrorBox error={error || data.error || apps.error} />
      {owner ? (
        <>
          <div className="panel">
            <h2>Пригласить участника</h2>
            <form onSubmit={invite} className="toolbar">
              <label>
                Email (необязательно)
                <input type="email" name="email" maxLength={254} />
              </label>
              <label>
                Роль
                <select name="role">
                  <option value="VIEWER">Наблюдатель</option>
                  <option value="OPERATOR">Оператор</option>
                  <option value="OWNER">Владелец</option>
                </select>
              </label>
              <button className="primary" disabled={busy}>
                Создать приглашение
              </button>
            </form>
            {url && (
              <div className="notice">
                <p>Скопируйте ссылку и передайте приглашённому пользователю.</p>
                <input aria-label="Ссылка приглашения" readOnly value={url} />
                <button onClick={() => navigator.clipboard.writeText(url)}>
                  Копировать
                </button>
              </div>
            )}
          </div>
          <div className="panel">
            <h2>Участники рабочей области</h2>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Участник</th>
                    <th>Роль</th>
                    <th>Доступ и права</th>
                  </tr>
                </thead>
                <tbody>
                  {data.data?.items.map((m) => (
                    <tr key={m.id}>
                      <td>
                        {m.name}
                        <small>{m.email}</small>
                      </td>
                      <td>{m.role}</td>
                      <td>
                        <button
                          disabled={m.id === me.data?.id}
                          onClick={async () => {
                            const role = window.prompt(
                              "Роль: OWNER, OPERATOR или VIEWER",
                              m.role,
                            );
                            if (!role) return;
                            const ids = window.prompt(
                              `ID назначенных приложений через запятую (для оператора/наблюдателя):\n${apps.data?.items.map((a) => `${a.name}: ${a.id}`).join("\n")}`,
                              "",
                            );
                            if (ids === null) return;
                            try {
                              await api(
                                `/workspaces/${selected}/members/${m.id}`,
                                "PATCH",
                                {
                                  role,
                                  appIds: ids
                                    .split(",")
                                    .map((s) => s.trim())
                                    .filter(Boolean),
                                },
                              );
                              await data.refetch();
                            } catch (e) {
                              setError(e);
                            }
                          }}
                        >
                          Изменить доступ
                        </button>
                        {!!me.data?.admin && m.id !== me.data.id && (
                          <button
                            onClick={async () => {
                              if (
                                !window.confirm(
                                  `${m.disabled ? "Включить" : "Отключить"} аккаунт ${m.email}?`,
                                )
                              )
                                return;
                              try {
                                await api(`/users/${m.id}`, "PATCH", {
                                  disabled: !m.disabled,
                                });
                                await data.refetch();
                              } catch (e) {
                                setError(e);
                              }
                            }}
                          >
                            {m.disabled ? "Включить" : "Отключить"}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="muted">
              Оператор и наблюдатель видят только назначенные приложения. После
              изменения доступа сессии отзываются.
            </p>
          </div>
          <div className="panel">
            <h2>Приглашения</h2>
            <table>
              <thead>
                <tr>
                  <th>Email</th>
                  <th>Роль</th>
                  <th>Истекает</th>
                  <th>Состояние</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.data?.invitations.map((i) => (
                  <tr key={i.id}>
                    <td>{i.email || "Любой"}</td>
                    <td>{i.role}</td>
                    <td>{new Date(i.expires).toLocaleString("ru")}</td>
                    <td>
                      {i.used
                        ? "Использовано"
                        : i.revoked
                          ? "Отозвано"
                          : i.expires < clock
                            ? "Истекло"
                            : "Ожидает"}
                    </td>
                    <td>
                      {!i.used && !i.revoked && (
                        <button
                          onClick={async () => {
                            try {
                              await api(
                                `/workspaces/${selected}/invitations/${i.id}`,
                                "DELETE",
                              );
                              await data.refetch();
                            } catch (e) {
                              setError(e);
                            }
                          }}
                        >
                          Отозвать
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : (
        <div className="notice">
          Управление участниками доступно владельцу рабочей области.
        </div>
      )}
    </Shell>
  );
}
