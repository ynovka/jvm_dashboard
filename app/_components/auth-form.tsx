"use client";
import { useEffect, useState, FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "./api";
import { ErrorBox } from "./shell";

// Keep the invitation in this browser's module memory across an App Router remount.
// The fragment is consumed immediately; it never enters storage or a request URL.
let invitationToken = "";

export default function AuthForm({ register = false }: { register?: boolean }) {
  const [token, setToken] = useState(() => (register ? invitationToken : ""));
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const router = useRouter();
  const client = useQueryClient();
  useEffect(() => {
    function consume() {
      const value = new URLSearchParams(window.location.hash.slice(1)).get(
        "token",
      );
      if (register && value) {
        invitationToken = value;
        setToken(value);
        window.history.replaceState(
          window.history.state,
          "",
          window.location.pathname,
        );
      }
    }
    consume();
    window.addEventListener("hashchange", consume);
    return () => window.removeEventListener("hashchange", consume);
  }, [register]);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    setError(undefined);
    const form = new FormData(e.currentTarget);
    try {
      await api(register ? "/auth/register" : "/auth/login", "POST", {
        email: form.get("email"),
        password: form.get("password"),
        ...(register ? { name: form.get("name"), token } : {}),
      });
      if (register) invitationToken = "";
      client.clear();
      router.replace("/");
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  return (
    <main className="auth-wrap">
      <div className="auth-card">
        <Link href="/" className="brand">
          <span className="brand-mark">J</span>JVM Dashboard
        </Link>
        <h1>{register ? "Добро пожаловать" : "Вход в панель"}</h1>
        <p className="muted">
          {register
            ? "Создайте аккаунт по приглашению."
            : "Ваши приложения, файлы и ресурсы в одном месте."}
        </p>
        <ErrorBox error={error} />
        {register && !token && (
          <div className="notice">
            Нужна ссылка с приглашением. Получите её у администратора или из
            журнала первого запуска API.
          </div>
        )}
        <form onSubmit={submit}>
          {register && (
            <label>
              Имя
              <input name="name" required maxLength={80} autoComplete="name" />
            </label>
          )}
          <label>
            Email
            <input
              name="email"
              type="email"
              required
              maxLength={254}
              autoComplete="email"
            />
          </label>
          <label>
            Пароль
            <input
              name="password"
              type="password"
              required
              minLength={register ? 12 : 1}
              maxLength={128}
              autoComplete={register ? "new-password" : "current-password"}
            />
          </label>
          <button
            className="primary"
            disabled={pending || (register && !token)}
          >
            {pending ? "Подождите…" : register ? "Создать аккаунт" : "Войти"}
          </button>
        </form>
        <p className="muted">
          {register ? (
            <Link href="/login">Уже есть аккаунт? Войти</Link>
          ) : (
            "Регистрация доступна только по приглашению."
          )}
        </p>
      </div>
    </main>
  );
}
