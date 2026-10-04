"use client";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, Me } from "./api";
import { useRouter } from "next/navigation";

export function useMe() {
  return useQuery<Me>({
    queryKey: ["me"],
    queryFn: () => api("/auth/me"),
    staleTime: 60000,
  });
}
export function ErrorBox({ error }: { error: unknown }) {
  return error ? (
    <div className="notice error" role="alert">
      {error instanceof Error ? error.message : String(error)}
    </div>
  ) : null;
}
export function Status({ state }: { state: string }) {
  return (
    <span className={`status status-${state.toLowerCase()}`}>
      <span aria-hidden>●</span> {labelsFor(state)}
    </span>
  );
}
function labelsFor(s: string) {
  return (
    (
      {
        RUNNING: "Работает",
        STOPPED: "Остановлено",
        FAILED: "Ошибка",
        STARTING: "Запускается",
        STOPPING: "Останавливается",
        PROVISIONING: "Создаётся",
        DELETING: "Удаляется",
      } as Record<string, string>
    )[s] ?? s
  );
}
export default function Shell({
  children,
  title,
  description,
  actions,
}: {
  children: React.ReactNode;
  title: string;
  description?: string;
  actions?: React.ReactNode;
}) {
  const me = useMe();
  const client = useQueryClient();
  const router = useRouter();
  if (me.isPending)
    return (
      <main className="auth-wrap">
        <p role="status">Подключаемся к панели…</p>
      </main>
    );
  if (me.error)
    return (
      <main className="auth-wrap">
        <div className="panel">
          <h1>JVM Dashboard</h1>
          {!(me.error instanceof ApiError && me.error.status === 401) && (
            <ErrorBox error={me.error} />
          )}
          <p>Войдите, чтобы управлять приложениями.</p>
          <Link className="button primary" href="/login">
            Войти
          </Link>
          <button onClick={() => me.refetch()}>Повторить подключение</button>
        </div>
      </main>
    );
  return (
    <div className="dashboard-shell">
      <aside className="sidebar">
        <Link href="/" className="brand">
          <span className="brand-mark">J</span>
          <span>
            JVM<span className="brand-sub">DASHBOARD</span>
          </span>
        </Link>
        <p className="nav-caption">ПАНЕЛЬ УПРАВЛЕНИЯ</p>
        <nav aria-label="Основная навигация">
          <Link href="/">▦ Приложения</Link>
          <Link href="/members">♧ Участники</Link>
          {!!me.data.admin && (
            <>
              <Link href="/admin">⚙ Узел и firewall</Link>
              <Link href="/audit">≡ Журнал действий</Link>
            </>
          )}
        </nav>
        <div className="sidebar-bottom">
          <span className="avatar">{me.data.name[0].toUpperCase()}</span>
          <div>
            {me.data.name}
            <small>{me.data.admin ? "Администратор" : "Участник"}</small>
          </div>
          <button
            aria-label="Выйти"
            onClick={async () => {
              await api("/auth/logout", "POST");
              client.clear();
              router.push("/login");
            }}
          >
            ↪
          </button>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <span className="muted">Собственный сервер / JVM</span>
          <span className="pill">MVP · один узел</span>
        </header>
        <main className="content">
          <div className="page-heading">
            <div>
              <h1>{title}</h1>
              {description && <p className="muted">{description}</p>}
            </div>
            {actions}
          </div>
          {children}
        </main>
        <footer>JVM Dashboard · Docker / Temurin</footer>
      </div>
    </div>
  );
}
