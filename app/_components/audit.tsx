"use client";
import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import Shell, { ErrorBox, useMe } from "./shell";
export default function Audit() {
  const me = useMe();
  const data = useQuery<{
    items: {
      id: string;
      created: number;
      user_id: string;
      action: string;
      object_id: string;
      request_id: string;
    }[];
  }>({
    queryKey: ["audit"],
    queryFn: () => api("/audit"),
    enabled: !!me.data?.admin,
  });
  return (
    <Shell
      title="Журнал действий"
      description="Последние 200 событий. Значения секретов не записываются."
    >
      <ErrorBox error={data.error} />
      <div className="panel table-scroll">
        <table>
          <thead>
            <tr>
              <th>Время</th>
              <th>Действие</th>
              <th>Объект</th>
              <th>Инициатор / request ID</th>
            </tr>
          </thead>
          <tbody>
            {data.data?.items.map((e) => (
              <tr key={e.id}>
                <td>{new Date(e.created).toLocaleString("ru")}</td>
                <td>{e.action}</td>
                <td>
                  <code>{e.object_id}</code>
                </td>
                <td>
                  <small>
                    {e.user_id}
                    <br />
                    {e.request_id}
                  </small>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Shell>
  );
}
