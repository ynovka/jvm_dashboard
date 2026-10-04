"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ColumnDef,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { api, App, emptySpec, size } from "./api";
import Shell, { ErrorBox, Status, useMe } from "./shell";
import SpecForm from "./spec-form";

const columns: ColumnDef<App>[] = [
  {
    accessorKey: "name",
    header: "Приложение",
    cell: ({ row }) => (
      <Link className="app-link" href={`/applications/${row.original.id}`}>
        <span className="app-icon">J</span>
        <span>
          {row.original.name}
          <small>Temurin {row.original.spec.jdk}</small>
        </span>
      </Link>
    ),
  },
  {
    accessorKey: "observed",
    header: "Статус",
    cell: ({ getValue }) => <Status state={getValue<string>()} />,
  },
  {
    id: "cpu",
    header: "CPU",
    cell: ({ row }) => `${row.original.spec.cpu} vCPU`,
  },
  {
    id: "ram",
    header: "RAM",
    cell: ({ row }) => size(row.original.spec.memoryMiB * 1024 ** 2),
  },
  {
    id: "disk",
    header: "Диск",
    cell: ({ row }) => size(row.original.spec.diskMiB * 1024 ** 2),
  },
  {
    id: "open",
    header: "",
    cell: ({ row }) => (
      <Link
        aria-label={`Открыть ${row.original.name}`}
        href={`/applications/${row.original.id}`}
      >
        ↗
      </Link>
    ),
  },
];
export default function Dashboard() {
  const me = useMe();
  const client = useQueryClient();
  const [workspace, setWorkspace] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState("name");
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState("");
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    setSearch(q.get("search") ?? "");
    setWorkspace(q.get("workspaceId") ?? "");
    setPage(Number(q.get("page")) || 0);
    setSort(q.get("sort") ?? "name");
  }, []);
  const selected = workspace || me.data?.workspaces[0]?.id || "";
  const apps = useQuery<{ items: App[]; total: number }>({
    queryKey: ["apps", selected, search, page, sort],
    queryFn: () =>
      api(
        `/applications?workspaceId=${selected}&search=${encodeURIComponent(search)}&page=${page}&sort=${sort}`,
      ),
    enabled: !!selected,
    refetchInterval: 3000,
  });
  // TanStack Table maintains its own state; this call is outside React Compiler memoization.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data: apps.data?.items ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    manualSorting: true,
  });
  function filters(values: {
    search?: string;
    workspace?: string;
    page?: number;
    sort?: string;
  }) {
    const next = { search, workspace, page, sort, ...values };
    setSearch(next.search);
    setWorkspace(next.workspace);
    setPage(next.page);
    setSort(next.sort);
    const q = new URLSearchParams({
      workspaceId: next.workspace || selected,
      search: next.search,
      page: String(next.page),
      sort: next.sort,
    });
    window.history.replaceState(null, "", `/?${q}`);
  }
  const ws = me.data?.workspaces.find((w) => w.id === selected);
  const canCreate = !!me.data?.admin || ws?.role === "OWNER";
  return (
    <Shell
      title="Приложения"
      description="Запускайте JVM-приложения и следите за выделенными ресурсами."
      actions={
        canCreate && (
          <button className="primary" onClick={() => setCreating(true)}>
            + Создать приложение
          </button>
        )
      }
    >
      <div className="stats-grid">
        <div className="stat-card">
          <small>ПРИЛОЖЕНИЯ</small>
          <strong>{apps.data?.total ?? "—"}</strong>
          <span className="muted">в выбранной рабочей области</span>
        </div>
        <div className="stat-card">
          <small>КВОТА CPU</small>
          <strong>
            {ws?.cpu ?? "—"}
            <em> vCPU</em>
          </strong>
        </div>
        <div className="stat-card">
          <small>КВОТА RAM</small>
          <strong>{ws ? size(ws.memory_mib * 1024 ** 2) : "—"}</strong>
        </div>
        <div className="stat-card">
          <small>КВОТА ДИСКА</small>
          <strong>{ws ? size(ws.disk_mib * 1024 ** 2) : "—"}</strong>
        </div>
      </div>
      {created && (
        <div className="notice">
          Создание поставлено в очередь.{" "}
          <Link href={`/applications/${created}`}>Открыть приложение →</Link>
        </div>
      )}
      <ErrorBox error={apps.error} />
      <div className="panel">
        <div className="toolbar">
          <input
            aria-label="Поиск приложений"
            placeholder="Поиск приложений…"
            value={search}
            onChange={(e) => filters({ search: e.target.value, page: 0 })}
          />
          <select
            aria-label="Рабочая область"
            value={selected}
            onChange={(e) => filters({ workspace: e.target.value, page: 0 })}
          >
            {me.data?.workspaces.map((w) => (
              <option value={w.id} key={w.id}>
                {w.name}
              </option>
            ))}
          </select>
          <select
            aria-label="Сортировка"
            value={sort}
            onChange={(e) => filters({ sort: e.target.value })}
          >
            <option value="name">По имени</option>
            <option value="id">По ID</option>
          </select>
        </div>
        {apps.isPending ? (
          <p role="status">Загружаем приложения…</p>
        ) : !apps.data?.items.length ? (
          <div className="empty-state">
            <span className="empty-icon">▦</span>
            <h2>Здесь будут ваши приложения</h2>
            <p className="muted">
              Создайте приложение, загрузите JAR и запустите его.
            </p>
            {canCreate && (
              <button onClick={() => setCreating(true)}>
                Создать первое приложение
              </button>
            )}
          </div>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                {table.getHeaderGroups().map((g) => (
                  <tr key={g.id}>
                    {g.headers.map((h) => (
                      <th key={h.id}>
                        {flexRender(h.column.columnDef.header, h.getContext())}
                      </th>
                    ))}
                  </tr>
                ))}
              </thead>
              <tbody>
                {table.getRowModel().rows.map((r) => (
                  <tr key={r.id}>
                    {r.getVisibleCells().map((c) => (
                      <td key={c.id}>
                        {flexRender(c.column.columnDef.cell, c.getContext())}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="pagination">
          <span className="muted">
            {apps.data?.total ?? 0} приложений · страница {page + 1}
          </span>
          <button
            disabled={page === 0}
            onClick={() => filters({ page: page - 1 })}
          >
            ←
          </button>
          <button
            disabled={(page + 1) * 25 >= (apps.data?.total ?? 0)}
            onClick={() => filters({ page: page + 1 })}
          >
            →
          </button>
        </div>
      </div>
      {creating && (
        <div className="modal-backdrop">
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-title"
          >
            <div className="section-heading">
              <h2 id="create-title">Новое приложение</h2>
              <button aria-label="Закрыть" onClick={() => setCreating(false)}>
                ×
              </button>
            </div>
            <SpecForm
              initial={emptySpec}
              button="Создать приложение"
              onSave={async (spec) => {
                const result = await api<{ id: string }>(
                  "/applications",
                  "POST",
                  { workspaceId: selected, spec },
                  crypto.randomUUID(),
                );
                setCreating(false);
                setCreated(result.id);
                await client.invalidateQueries({ queryKey: ["apps"] });
              }}
            />
          </section>
        </div>
      )}
    </Shell>
  );
}
