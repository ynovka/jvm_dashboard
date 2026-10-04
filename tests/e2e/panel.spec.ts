import { test, expect } from "@playwright/test";
test("registration consumes the fragment and requires an invitation", async ({ page }) => {
  await page.goto("/register");
  await expect(page.getByRole("button", { name: "Создать аккаунт" })).toBeDisabled();
  await page.goto("/register#token=single-use-secret");
  await expect(page).toHaveURL(/\/register$/);
  await expect(page.getByRole("button", { name: "Создать аккаунт" })).toBeEnabled();
  await page.getByLabel("Имя", { exact: true }).fill("Администратор");
  await page.getByLabel("Email", { exact: true }).fill("admin@example.com");
  await page.getByLabel("Пароль", { exact: true }).fill("a-long-password-123");
  await page.route("**/api/v1/auth/register", async route => {
    expect(route.request().postDataJSON().token).toBe("single-use-secret");
    await route.fulfill({ status: 400, json: { code: "INVALID_INVITATION", message: "Приглашение недействительно" } });
  });
  await page.getByRole("button", { name: "Создать аккаунт" }).click();
  await expect(page.getByRole("alert")).toHaveText("Приглашение недействительно");
});
test("API failure shows a useful retry state", async ({ page }) => {
  await page.route("**/api/v1/auth/me", route => route.fulfill({ status: 503, body: "backend unavailable" }));
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("API недоступен");
  await expect(page.getByRole("button", { name: "Повторить подключение" })).toBeVisible();
});
test("create application sends the real specification and pending operation", async ({ page }) => {
  await page.route("**/api/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/auth/me")) return route.fulfill({ json: { id: "user", name: "Admin", email: "admin@example.com", admin: true, csrf: "csrf", workspaces: [{ id: "ws", name: "Основная", role: "OWNER", cpu: 2, memory_mib: 2048, disk_mib: 4096 }] } });
    if (path.endsWith("/runtimes")) return route.fulfill({ json: { items: [{ jdk: 21, image: "pinned" }] } });
    if (route.request().method() === "POST") {
      expect(route.request().headers()["x-csrf-token"]).toBe("csrf");
      expect(route.request().postDataJSON().spec.name).toBe("My JVM");
      return route.fulfill({ status: 202, json: { id: "new-app", operationId: "operation" } });
    }
    return route.fulfill({ json: { items: [], total: 0 } });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "+ Создать приложение", exact: true }).click();
  await page.getByLabel("Название", { exact: true }).fill("My JVM");
  await page.getByRole("button", { name: "Создать приложение", exact: true }).click();
  await expect(page.getByText("Создание поставлено в очередь.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Открыть приложение →" })).toHaveAttribute("href", "/applications/new-app");
});
