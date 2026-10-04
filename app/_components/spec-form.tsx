"use client";
import { FormEvent, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, Spec, Env, Port } from "./api";
import { ErrorBox } from "./shell";

export default function SpecForm({
  initial,
  onSave,
  button = "Сохранить",
}: {
  initial: Spec;
  onSave: (spec: Spec) => Promise<void>;
  button?: string;
}) {
  const [spec, setSpec] = useState(initial);
  const [jvm, setJvm] = useState(JSON.stringify(initial.jvmArgs));
  const [args, setArgs] = useState(JSON.stringify(initial.appArgs));
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const runtimes = useQuery<{ items: { jdk: number; image: string }[] }>({
    queryKey: ["runtimes"],
    queryFn: () => api("/runtimes"),
  });
  function field<K extends keyof Spec>(key: K, value: Spec[K]) {
    setSpec({ ...spec, [key]: value });
  }
  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(undefined);
    setPending(true);
    try {
      const jvmArgs: unknown = JSON.parse(jvm);
      const appArgs: unknown = JSON.parse(args);
      if (
        !Array.isArray(jvmArgs) ||
        !Array.isArray(appArgs) ||
        [...jvmArgs, ...appArgs].some((a) => typeof a !== "string")
      )
        throw new Error("Аргументы должны быть JSON-массивом строк");
      await onSave({ ...spec, jvmArgs, appArgs });
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  function envRow(index: number, value: Partial<Env>) {
    field(
      "env",
      spec.env.map((row, i) => (i === index ? { ...row, ...value } : row)),
    );
  }
  function portRow(index: number, value: Partial<Port>) {
    field(
      "ports",
      spec.ports.map((row, i) => (i === index ? { ...row, ...value } : row)),
    );
  }
  return (
    <form onSubmit={submit} className="spec-form">
      <ErrorBox error={error || runtimes.error} />
      <div className="form-grid">
        <label>
          Название
          <input
            value={spec.name}
            onChange={(e) => field("name", e.target.value)}
            maxLength={80}
            required
          />
        </label>
        <label>
          JDK
          <select
            value={spec.jdk}
            onChange={(e) => field("jdk", +e.target.value)}
          >
            {runtimes.data?.items.map((r) => (
              <option key={r.jdk} value={r.jdk}>
                Temurin {r.jdk}
              </option>
            ))}
            {!runtimes.data?.items.length && (
              <option value={spec.jdk}>
                JDK {spec.jdk} (каталог недоступен)
              </option>
            )}
          </select>
        </label>
        <label>
          CPU, vCPU
          <input
            type="number"
            min="0.25"
            max="64"
            step="0.25"
            value={spec.cpu}
            onChange={(e) => field("cpu", +e.target.value)}
            required
          />
        </label>
        <label>
          RAM, MiB
          <input
            type="number"
            min="256"
            value={spec.memoryMiB}
            onChange={(e) => field("memoryMiB", +e.target.value)}
            required
          />
          <small>
            Heap: {Math.floor(spec.memoryMiB * 0.7)} MiB; остальное — native
            memory
          </small>
        </label>
        <label>
          Диск, MiB
          <input
            type="number"
            min="128"
            value={spec.diskMiB}
            onChange={(e) => field("diskMiB", +e.target.value)}
            required
          />
        </label>
        <label>
          Путь к JAR
          <input
            value={spec.jar}
            onChange={(e) => field("jar", e.target.value)}
            required
            placeholder="app.jar"
          />
        </label>
        <label>
          Остановка, секунд
          <input
            type="number"
            min="1"
            max="120"
            value={spec.stopSeconds}
            onChange={(e) => field("stopSeconds", +e.target.value)}
          />
        </label>
        <label>
          TCP health port (необязательно)
          <input
            type="number"
            min="1"
            max="65535"
            value={spec.healthPort ?? ""}
            onChange={(e) =>
              field("healthPort", e.target.value ? +e.target.value : null)
            }
          />
        </label>
      </div>
      <label>
        JVM-аргументы (JSON-массив)
        <input
          value={jvm}
          onChange={(e) => setJvm(e.target.value)}
          placeholder={'["-Dfile.encoding=UTF-8"]'}
        />
        <small>
          Heap задаётся панелью. Разрешены -Dkey=value, -ea/-da и G1/Serial GC.
        </small>
      </label>
      <label>
        Аргументы приложения (JSON-массив)
        <input value={args} onChange={(e) => setArgs(e.target.value)} />
      </label>
      <div className="check-row">
        <label>
          <input
            type="checkbox"
            checked={spec.autostart}
            onChange={(e) => field("autostart", e.target.checked)}
          />{" "}
          Автозапуск после reboot
        </label>
        <label>
          <input
            type="checkbox"
            checked={spec.crashRestart}
            onChange={(e) => field("crashRestart", e.target.checked)}
          />{" "}
          До 3 рестартов при сбое
        </label>
      </div>
      <p className="muted">
        Ручная остановка сохраняется после перезагрузки сервера.
      </p>
      <div className="section-heading">
        <h3>Переменные окружения</h3>
        <button
          type="button"
          onClick={() =>
            field("env", [...spec.env, { key: "", value: "", secret: false }])
          }
        >
          + ENV
        </button>
      </div>
      {spec.env.map((row, i) => (
        <div className="env-row" key={i}>
          <input
            aria-label={`Ключ ENV ${i + 1}`}
            placeholder="KEY"
            value={row.key}
            onChange={(e) => envRow(i, { key: e.target.value })}
            required
          />
          <input
            aria-label={`Значение ENV ${i + 1}`}
            type={row.secret ? "password" : "text"}
            placeholder={
              row.secret ? "Новое значение; пусто = сохранить" : "Значение"
            }
            value={row.value}
            onChange={(e) => envRow(i, { value: e.target.value })}
          />
          <label>
            <input
              type="checkbox"
              checked={row.secret}
              onChange={(e) => envRow(i, { secret: e.target.checked })}
            />{" "}
            Секрет
          </label>
          <button
            type="button"
            aria-label={`Удалить ENV ${i + 1}`}
            onClick={() =>
              field(
                "env",
                spec.env.filter((_, index) => index !== i),
              )
            }
          >
            ×
          </button>
        </div>
      ))}
      <div className="section-heading">
        <h3>Сеть</h3>
        <button
          type="button"
          onClick={() =>
            field("ports", [
              ...spec.ports,
              { host: 10000, container: 8080, protocol: "tcp", public: false },
            ])
          }
        >
          + Порт
        </button>
      </div>
      <p className="muted">
        Диапазон хоста: 10000–60000. Закрытые порты доступны только на loopback.
        Публикация IPv4.
      </p>
      {spec.ports.map((p, i) => (
        <div className="port-row" key={i}>
          <label>
            Хост
            <input
              type="number"
              min="10000"
              max="60000"
              value={p.host}
              onChange={(e) => portRow(i, { host: +e.target.value })}
            />
          </label>
          <span>→</span>
          <label>
            Контейнер
            <input
              type="number"
              min="1"
              max="65535"
              value={p.container}
              onChange={(e) => portRow(i, { container: +e.target.value })}
            />
          </label>
          <select
            aria-label="Протокол"
            value={p.protocol}
            onChange={(e) =>
              portRow(i, { protocol: e.target.value as "tcp" | "udp" })
            }
          >
            <option>tcp</option>
            <option>udp</option>
          </select>
          <label>
            <input
              type="checkbox"
              checked={p.public}
              onChange={(e) => portRow(i, { public: e.target.checked })}
            />{" "}
            Публичный
          </label>
          <button
            type="button"
            aria-label="Удалить порт"
            onClick={() =>
              field(
                "ports",
                spec.ports.filter((_, index) => index !== i),
              )
            }
          >
            ×
          </button>
        </div>
      ))}
      <button className="primary" disabled={pending}>
        {pending ? "Сохраняем…" : button}
      </button>
    </form>
  );
}
