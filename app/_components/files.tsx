"use client";
import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import dynamic from "next/dynamic";
import { api, size } from "./api";
import { ErrorBox } from "./shell";
import { json as jsonLanguage } from "@codemirror/lang-json";

const CodeMirror = dynamic(() => import("@uiw/react-codemirror"), {
  ssr: false,
  loading: () => <p>Загружаем редактор…</p>,
});
type Entry = {
  name: string;
  type: "file" | "directory" | "unsupported";
  size: number;
  modified: number;
};
type Upload = {
  id: string;
  file: File;
  path: string;
  offset: number;
  paused: boolean;
};
function FileTree({
  appId,
  path,
  onOpen,
  depth = 0,
}: {
  appId: string;
  path: string;
  onOpen: (path: string) => void;
  depth?: number;
}) {
  const [expanded, setExpanded] = useState(depth === 0);
  const entries = useQuery<{ items: Entry[] }>({
    queryKey: ["files", appId, path],
    queryFn: () =>
      api(`/applications/${appId}/files`, "POST", { action: "list", path }),
    enabled: expanded,
  });
  return (
    <div style={{ paddingLeft: depth ? 16 : 0 }}>
      <button className="text-button" onClick={() => setExpanded(!expanded)}>
        {expanded ? "▾" : "▸"}
      </button>
      <button className="text-button" onClick={() => onOpen(path)}>
        {path ? path.split("/").pop() : "Файлы приложения"}
      </button>
      {expanded &&
        depth < 32 &&
        entries.data?.items
          .filter((entry) => entry.type === "directory")
          .slice(0, 100)
          .map((entry) => (
            <FileTree
              key={entry.name}
              appId={appId}
              path={path ? `${path}/${entry.name}` : entry.name}
              onOpen={onOpen}
              depth={depth + 1}
            />
          ))}
    </div>
  );
}
export default function Files({
  appId,
  writable,
}: {
  appId: string;
  writable: boolean;
}) {
  const [path, setPath] = useState("");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [sort, setSort] = useState("name");
  const [error, setError] = useState<unknown>();
  const [editor, setEditor] = useState<{
    path: string;
    text: string;
    etag: string;
  }>();
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState("");
  const [trash, setTrash] = useState(false);
  const [hasTransfer, setHasTransfer] = useState(false);
  const transfer = useRef<Upload | null>(null);
  const pause = useRef(false);
  const client = useQueryClient();
  const endpoint = `/applications/${appId}/files`;
  function navigate(next: string) {
    setPath(next);
    setSelected([]);
  }
  function file<T>(body: unknown) {
    return api<T>(endpoint, "POST", body);
  }
  const listing = useQuery<{ items: Entry[] }>({
    queryKey: ["files", appId, path],
    queryFn: () => file({ action: "list", path }),
  });
  const removed = useQuery<{
    items: { id: string; path: string; deleted: number }[];
  }>({
    queryKey: ["trash", appId],
    queryFn: () => file({ action: "trashList", path: "" }),
    enabled: trash,
  });
  async function perform(body: unknown) {
    setError(undefined);
    setBusy(true);
    try {
      await file(body);
      await client.invalidateQueries({ queryKey: ["files", appId] });
      await client.invalidateQueries({ queryKey: ["trash", appId] });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  const full = (name: string) => (path ? `${path}/${name}` : name);
  async function edit(name: string) {
    setError(undefined);
    try {
      const result = await file<{ text: string; etag: string }>({
        action: "read",
        path: full(name),
      });
      setEditor({ ...result, path: full(name) });
    } catch (e) {
      setError(e);
    }
  }
  async function download(name: string) {
    setBusy(true);
    setError(undefined);
    try {
      await file({ action: "read", path: full(name), metadataOnly: true });
      const a = document.createElement("a");
      a.href = `/api/v1${endpoint}/download?path=${encodeURIComponent(full(name))}`;
      a.download = name;
      a.click();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  async function continueUpload() {
    const u = transfer.current;
    if (!u) return;
    setBusy(true);
    pause.current = false;
    setError(undefined);
    try {
      const status = await file<{ offset: number }>({
        action: "uploadStatus",
        id: u.id,
      });
      u.offset = status.offset;
      while (u.offset < u.file.size && !pause.current) {
        const data = new Uint8Array(
          await u.file.slice(u.offset, u.offset + 512 * 1024).arrayBuffer(),
        );
        let binary = "";
        for (const byte of data) binary += String.fromCharCode(byte);
        const result = await file<{ offset: number }>({
          action: "uploadChunk",
          id: u.id,
          offset: u.offset,
          data: btoa(binary),
        });
        u.offset = result.offset;
        setProgress(`${u.file.name}: ${size(u.offset)} / ${size(u.file.size)}`);
      }
      if (pause.current) {
        u.paused = true;
        setProgress(
          `Пауза: ${u.file.name}, ${size(u.offset)}. Можно продолжить.`,
        );
      } else {
        await file({ action: "uploadFinish", id: u.id, path: u.path });
        transfer.current = null;
        setHasTransfer(false);
        localStorage.removeItem(`jvm-upload-${appId}`);
        setProgress("Загрузка завершена");
        await client.invalidateQueries({ queryKey: ["files", appId] });
      }
    } catch (e) {
      u.paused = true;
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  async function upload(files: FileList | File[]) {
    for (const f of Array.from(files)) {
      if (transfer.current) {
        setError(new Error("Продолжите или отмените предыдущую загрузку"));
        return;
      }
      const target = full(f.name);
      try {
        const saved = JSON.parse(
          localStorage.getItem(`jvm-upload-${appId}`) || "null",
        );
        if (
          saved &&
          saved.path === target &&
          saved.size === f.size &&
          saved.lastModified === f.lastModified &&
          window.confirm(`Продолжить прерванную загрузку ${f.name}?`)
        ) {
          await file({ action: "uploadStatus", id: saved.id });
          transfer.current = {
            id: saved.id,
            file: f,
            path: target,
            offset: 0,
            paused: false,
          };
          setHasTransfer(true);
          await continueUpload();
          if (transfer.current) break;
          continue;
        }
      } catch (e) {
        setError(e);
        localStorage.removeItem(`jvm-upload-${appId}`);
      }
      let etag;
      if (listing.data?.items.some((e) => e.name === f.name)) {
        if (!window.confirm(`Заменить ${f.name}?`)) continue;
        try {
          etag = (
            await file<{ etag: string }>({
              action: "read",
              path: target,
              metadataOnly: true,
            })
          ).etag;
        } catch (e) {
          setError(e);
          return;
        }
      }
      try {
        const session = await file<{ id: string }>({
          action: "uploadStart",
          path: target,
          size: f.size,
          etag,
        });
        transfer.current = {
          id: session.id,
          file: f,
          path: target,
          offset: 0,
          paused: false,
        };
        setHasTransfer(true);
        localStorage.setItem(
          `jvm-upload-${appId}`,
          JSON.stringify({
            id: session.id,
            path: target,
            size: f.size,
            lastModified: f.lastModified,
          }),
        );
        await continueUpload();
        if (transfer.current) break;
      } catch (e) {
        setError(e);
        break;
      }
    }
  }
  return (
    <div
      className="panel file-panel"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        if (writable && !busy) void upload(e.dataTransfer.files);
      }}
    >
      <details className="file-tree">
        <summary>Дерево каталогов</summary>
        <FileTree appId={appId} path="" onOpen={navigate} />
      </details>
      <div className="toolbar">
        <button onClick={() => navigate("")}>⌂</button>
        {path
          .split("/")
          .filter(Boolean)
          .map((part, i) => (
            <button
              key={i}
              onClick={() =>
                navigate(
                  path
                    .split("/")
                    .slice(0, i + 1)
                    .join("/"),
                )
              }
            >
              {part} /
            </button>
          ))}
        {path && (
          <button
            onClick={() => navigate(path.split("/").slice(0, -1).join("/"))}
          >
            ↑
          </button>
        )}
        <input
          aria-label="Поиск файлов"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Поиск в каталоге…"
        />
        <button onClick={() => listing.refetch()}>↻</button>
        <select
          aria-label="Сортировка файлов"
          value={sort}
          onChange={(e) => setSort(e.target.value)}
        >
          <option value="name">Имя</option>
          <option value="size">Размер</option>
          <option value="modified">Изменён</option>
        </select>
      </div>
      <div className="toolbar">
        {writable && (
          <>
            <label className="button">
              ↑ Загрузить
              <input
                className="visually-hidden"
                type="file"
                multiple
                disabled={busy}
                onChange={(e) => {
                  if (e.target.files) void upload(e.target.files);
                  e.target.value = "";
                }}
              />
            </label>
            <button
              onClick={() => {
                const name = window.prompt("Имя каталога");
                if (name) void perform({ action: "mkdir", path: full(name) });
              }}
            >
              + Каталог
            </button>
            <button
              onClick={() => {
                const name = window.prompt("Имя файла");
                if (name)
                  void perform({ action: "write", path: full(name), text: "" });
              }}
            >
              + Файл
            </button>
            <button onClick={() => setTrash(!trash)}>Корзина</button>
            <button
              onClick={() => {
                const name = window.prompt("Имя ZIP-архива", "download.zip");
                if (name)
                  void perform({ action: "archive", path, target: full(name) });
              }}
            >
              Создать ZIP
            </button>
          </>
        )}
        <span className="muted">
          Файлы доступны при остановленном приложении
        </span>
      </div>
      <ErrorBox error={error || listing.error || removed.error} />
      {!!selected.length && (
        <div className="toolbar">
          <span>Выбрано: {selected.length}</span>
          {writable && (
            <>
              <button
                disabled={busy}
                onClick={async () => {
                  if (
                    !window.confirm(
                      `Переместить ${selected.length} объектов в корзину?`,
                    )
                  )
                    return;
                  for (const name of selected)
                    await perform({ action: "trash", path: full(name) });
                  setSelected([]);
                }}
              >
                В корзину
              </button>
              <button
                disabled={busy}
                onClick={async () => {
                  const name = window.prompt("Имя ZIP-архива", "selection.zip");
                  if (!name) return;
                  await perform({
                    action: "archive",
                    path,
                    paths: selected.map(full),
                    target: full(name),
                  });
                }}
              >
                Архив выбранного
              </button>
            </>
          )}
          <button onClick={() => setSelected([])}>Снять выделение</button>
        </div>
      )}
      <p className="muted">
        Для продолжения загрузки после обновления страницы выберите тот же файл.
      </p>
      {progress && (
        <div className="notice" role="status">
          {progress}
          {busy && hasTransfer && (
            <button
              onClick={() => {
                pause.current = true;
              }}
            >
              Пауза
            </button>
          )}
          {!busy && hasTransfer && (
            <>
              <button onClick={continueUpload}>Продолжить</button>
              <button
                onClick={async () => {
                  await perform({
                    action: "uploadCancel",
                    id: transfer.current?.id,
                  });
                  transfer.current = null;
                  setHasTransfer(false);
                  localStorage.removeItem(`jvm-upload-${appId}`);
                  setProgress("");
                }}
              >
                Отменить
              </button>
            </>
          )}
        </div>
      )}
      {trash && (
        <div className="notice">
          <h3>Корзина (7 дней)</h3>
          {removed.data?.items.map((t) => (
            <div className="section-heading" key={t.id}>
              <span>{t.path}</span>
              <button onClick={() => perform({ action: "restore", id: t.id })}>
                Восстановить
              </button>
            </div>
          ))}
          {!removed.data?.items.length && <p>Корзина пуста</p>}
        </div>
      )}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Выбор</th>
              <th>Имя</th>
              <th>Размер</th>
              <th>Изменён</th>
              <th>Действия</th>
            </tr>
          </thead>
          <tbody>
            {listing.data?.items
              .filter((e) =>
                e.name.toLowerCase().includes(search.toLowerCase()),
              )
              .sort((a, b) =>
                sort === "size"
                  ? b.size - a.size
                  : sort === "modified"
                    ? b.modified - a.modified
                    : a.name.localeCompare(b.name),
              )
              .map((e) => (
                <tr key={e.name}>
                  <td>
                    <input
                      aria-label={`Выбрать ${e.name}`}
                      type="checkbox"
                      disabled={e.type === "unsupported"}
                      checked={selected.includes(e.name)}
                      onChange={(event) =>
                        setSelected(
                          event.target.checked
                            ? [...selected, e.name]
                            : selected.filter((name) => name !== e.name),
                        )
                      }
                    />
                  </td>
                  <td>
                    <button
                      className="text-button"
                      disabled={e.type === "unsupported"}
                      onClick={() =>
                        e.type === "directory"
                          ? navigate(full(e.name))
                          : edit(e.name)
                      }
                    >
                      {e.type === "directory" ? "▣" : "≡"} {e.name}
                    </button>
                  </td>
                  <td>{e.type === "file" ? size(e.size) : "—"}</td>
                  <td>{new Date(e.modified).toLocaleString("ru")}</td>
                  <td>
                    <div className="row-actions">
                      {e.type === "file" && (
                        <button
                          disabled={busy}
                          onClick={() => download(e.name)}
                        >
                          ↓
                        </button>
                      )}
                      {writable && e.type !== "unsupported" && (
                        <>
                          <button
                            onClick={() => {
                              const target = window.prompt(
                                "Новый путь относительно корня",
                                full(e.name),
                              );
                              if (target)
                                void perform({
                                  action: "rename",
                                  path: full(e.name),
                                  target,
                                });
                            }}
                          >
                            Переименовать
                          </button>
                          {(e.type === "file" || e.type === "directory") && (
                            <button
                              onClick={() => {
                                const target = window.prompt(
                                  "Путь копии",
                                  full(e.name) + ".copy",
                                );
                                if (target)
                                  void perform({
                                    action: "copy",
                                    path: full(e.name),
                                    target,
                                  });
                              }}
                            >
                              Копия
                            </button>
                          )}
                          {/\.(zip|tar|tar\.gz|tgz)$/i.test(e.name) && (
                            <button
                              onClick={() => {
                                if (
                                  window.confirm(
                                    "Распаковать архив в корень приложения? Существующие файлы не заменяются.",
                                  )
                                )
                                  void perform({
                                    action: "extract",
                                    path: full(e.name),
                                  });
                              }}
                            >
                              Распаковать
                            </button>
                          )}
                          <button
                            className="danger-text"
                            onClick={() => {
                              if (
                                window.confirm(
                                  `Переместить ${e.name} в корзину?`,
                                )
                              )
                                void perform({
                                  action: "trash",
                                  path: full(e.name),
                                });
                            }}
                          >
                            Удалить
                          </button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
      {!listing.data?.items.length && (
        <div className="empty-state">
          <p>
            Каталог пуст.{" "}
            {writable && "Загрузите JAR или перетащите файлы сюда."}
          </p>
        </div>
      )}
      {editor && (
        <div className="modal-backdrop">
          <section
            className="modal wide"
            role="dialog"
            aria-modal="true"
            aria-label="Редактор файла"
          >
            <div className="section-heading">
              <h2>{editor.path}</h2>
              <button onClick={() => setEditor(undefined)}>Закрыть</button>
            </div>
            <ErrorBox error={error} />
            <CodeMirror
              value={editor.text}
              height="440px"
              theme="dark"
              extensions={editor.path.endsWith(".json") ? [jsonLanguage()] : []}
              readOnly={!writable}
              onChange={(text) => setEditor({ ...editor, text })}
            />
            {writable && (
              <button
                className="primary"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  setError(undefined);
                  try {
                    const result = await file<{ etag: string }>({
                      action: "write",
                      path: editor.path,
                      text: editor.text,
                      etag: editor.etag,
                    });
                    setEditor({ ...editor, etag: result.etag });
                    await listing.refetch();
                  } catch (e) {
                    setError(e);
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Сохранить
              </button>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
