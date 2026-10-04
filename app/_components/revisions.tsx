"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import { ErrorBox } from "./shell";

export default function Revisions({
  appId,
  current,
  onRestored,
}: {
  appId: string;
  current: number;
  onRestored: () => Promise<unknown>;
}) {
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const revisions = useQuery<{
    items: { revision: number; created: number }[];
  }>({
    queryKey: ["revisions", appId, current],
    queryFn: () => api(`/applications/${appId}/revisions`),
  });
  return (
    <div className="panel">
      <h3>История конфигурации</h3>
      <p className="muted">
        Восстановление создаёт новую ревизию. Затем примените её с перезапуском.
      </p>
      <ErrorBox error={error || revisions.error} />
      {revisions.data?.items.map((row) => (
        <div className="section-heading" key={row.revision}>
          <span>
            Ревизия {row.revision} ·{" "}
            {new Date(row.created).toLocaleString("ru")}
          </span>
          <button
            disabled={busy || row.revision === current}
            onClick={async () => {
              if (
                !window.confirm(
                  `Восстановить конфигурацию и ENV из ревизии ${row.revision}?`,
                )
              )
                return;
              setBusy(true);
              setError(undefined);
              try {
                await api(
                  `/applications/${appId}/revisions/${row.revision}/restore`,
                  "POST",
                  { currentRevision: current },
                );
                await onRestored();
              } catch (error) {
                setError(error);
              } finally {
                setBusy(false);
              }
            }}
          >
            Восстановить
          </button>
        </div>
      ))}
    </div>
  );
}
