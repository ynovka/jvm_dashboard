export type Env = {
  key: string;
  value: string;
  secret: boolean;
  description?: string;
};
export type Port = {
  host: number;
  container: number;
  protocol: "tcp" | "udp";
  public: boolean;
};
export type Spec = {
  name: string;
  jdk: number;
  cpu: number;
  memoryMiB: number;
  diskMiB: number;
  jar: string;
  jvmArgs: string[];
  appArgs: string[];
  env: Env[];
  ports: Port[];
  autostart: boolean;
  crashRestart: boolean;
  stopSeconds: number;
  healthPort: number | null;
};
export type App = {
  id: string;
  workspace_id: string;
  name: string;
  observed: string;
  desired: string;
  revision: number;
  applied_revision: number;
  active_operation: string | null;
  spec: Spec;
};
export type Workspace = {
  id: string;
  name: string;
  role: "OWNER" | "OPERATOR" | "VIEWER";
  cpu: number;
  memory_mib: number;
  disk_mib: number;
};
export type Me = {
  id: string;
  name: string;
  email: string;
  admin: boolean | number;
  csrf: string;
  workspaces: Workspace[];
};
export type Metrics = {
  nodeOnline: boolean;
  sampledAt: number;
  state?: string;
  observed: string;
  uptimeSeconds?: number;
  cpuCores?: number;
  cpuPercent?: number;
  memory?: string;
  memoryPercent?: number;
  diskReport?: string;
  diskMiB?: number;
  oomKilled?: boolean;
  healthy?: boolean | null;
  exitCode?: number;
  io?: string;
  network?: string;
  activeOperation?: string | null;
  revision: number;
  appliedRevision: number;
};
export type Operation = {
  id: string;
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";
  result: string | null;
};
let csrf = "";
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
  idempotencyKey?: string,
): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(method !== "GET" ? { "X-CSRF-Token": csrf } : {}),
      ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let result;
  try {
    result = await response.json();
  } catch {
    throw new ApiError(
      response.status,
      "API_UNAVAILABLE",
      "API недоступен. Проверьте запуск backend и связь с сервером.",
    );
  }
  if (!response.ok)
    throw new ApiError(response.status, result.code, result.message);
  if (path === "/auth/me") csrf = result.csrf;
  return result as T;
}
export const emptySpec: Spec = {
  name: "",
  jdk: 21,
  cpu: 1,
  memoryMiB: 512,
  diskMiB: 1024,
  jar: "app.jar",
  jvmArgs: [],
  appArgs: [],
  env: [],
  ports: [],
  autostart: false,
  crashRestart: false,
  stopSeconds: 30,
  healthPort: null,
};
export const labels: Record<string, string> = {
  RUNNING: "Работает",
  STOPPED: "Остановлено",
  STARTING: "Запускается",
  STOPPING: "Останавливается",
  PROVISIONING: "Создаётся",
  RESTARTING: "Перезапускается",
  FAILED: "Ошибка",
  UPDATING: "Обновляется",
  DELETING: "Удаляется",
  QUEUED: "В очереди",
  SUCCEEDED: "Завершено",
};
export function size(n: number) {
  return n >= 1024 ** 3
    ? `${(n / 1024 ** 3).toFixed(1)} GiB`
    : n >= 1024 ** 2
      ? `${(n / 1024 ** 2).toFixed(1)} MiB`
      : n >= 1024
        ? `${(n / 1024).toFixed(1)} KiB`
        : `${n} B`;
}
export function uptime(seconds = 0) {
  return `${Math.floor(seconds / 86400)}д ${Math.floor((seconds % 86400) / 3600)}ч ${Math.floor((seconds % 3600) / 60)}м ${seconds % 60}с`;
}
