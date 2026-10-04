# JVM Dashboard MVP

Исходный план: [JVM_PANEL_PLAN.md](JVM_PANEL_PLAN.md). Реализация включает Next.js UI, Ktor API, агент, MariaDB migrations, Caddy/systemd, Prometheus, UFW и упаковку GitHub Releases.

## Разработка

Требуются Node 24, JDK 21 и настоящая MariaDB 10.11. Linux-агент требует Ubuntu 24.04, Docker cgroups v2, XFS project quotas и bubblewrap. На Windows интерфейс/API работают отдельно; исполнение контейнеров и firewall проверяется на Linux.

```bash
npm ci
npm run dev
./gradlew test installDist
```

Создайте отдельную metadata DB с utf8mb4; сервисный пользователь получает права только на эту БД. Перед запуском API задайте `PUBLIC_URL`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `ENCRYPTION_KEY` (base64 32 random bytes), `AGENT_TOKEN`, `NODE_CPU`, `NODE_MEMORY_MIB`, `NODE_DISK_MIB`, `JDK_IMAGES_JSON`. Каталог образов содержит значения `eclipse-temurin@sha256:…`, а не mutable tags. Для разработки на localhost можно явно установить `ALLOW_INSECURE_LOCAL=true`; это не работает для внешнего HTTP URL.

```bash
services/backend/build/install/backend/bin/backend
```

Первый запуск выводит одноразовую bootstrap-ссылку. Страница `/register` читает токен из fragment и удаляет его из URL. Если приглашение истекло и аккаунтов ещё нет, локальный `backend bootstrap` перевыпускает его. `backend recover email` читает новый пароль из TTY, отзывает сессии и оставляет запись аудита.

В production браузер обращается к Caddy. В dev Next.js перенаправляет HTTP API на `127.0.0.1:8080`; WebSocket требует Caddy, при его отсутствии продолжает работать HTTP polling.

## Установка

Цель: Ubuntu 24.04 amd64, systemd, минимум 2 GiB RAM, DNS домена на сервер и открытые TCP 80/443. Кроме размера XFS требуется минимум 4 GiB свободного места под хост и runtime; существующие образы и данные учитываются отдельно. Для VPS 2 GiB системный резерв — 1536 MiB; доступный бюджет приложений считается по реальному MemTotal. В этой конфигурации разумно начать с одного приложения 256 MiB. Установщик сохраняет SSH и чужие UFW rules, отказывается изменять сторонние службы/данные и существующий не-XFS файл.

После успешного workflow **Verified Ubuntu release** команда конкретной версии:

```bash
sudo bash -c 'set -euo pipefail; apt-get update; apt-get install -y ca-certificates curl; curl -fsSL "https://github.com/ynovka/jvm_dashboard/releases/download/v0.1.2/install.sh" | bash -s -- --repo ynovka/jvm_dashboard --version v0.1.2 --domain test.myshore.party --storage-size 1G --ssh-port 22'
```

Эта команда требует опубликованного Release; workflow не публикует его при ошибке Ubuntu VM smoke. На сервере не нужны npm install, Gradle или компилятор Kotlin. Node runtime скачивается отдельно и проверяется по checksum в release.json.

Установщик принимает `--artifact-dir` для проверки тех же собранных архивов до публикации, `--tls-mode internal` для тестовой VM без публичного домена. Проверенные файлы распаковываются в staging, данные/ключи остаются в `/opt/jvm_dashboard/data` и `/opt/jvm_dashboard/secrets`, версии — в `releases/`, активная версия — `current`.

```bash
systemctl status jvm-dashboard-{api,agent,web} caddy mariadb prometheus
journalctl -u jvm-dashboard-agent -f
```

Повторный запуск той же версии сохраняет данные и секреты. Symlink `previous` сохраняет предыдущую сборку; несовместимые миграции БД нельзя откатывать одним переключением symlink. Автоматический upgrade через несовместимые схемы не входит в MVP.

## Проверки

```bash
npm run lint
npm run build
npx playwright install chromium
npm run test:e2e
./gradlew test installDist
python3 -m unittest discover -s tests/helpers -v
bash -n deployment/install.sh scripts/package-release.sh scripts/ubuntu-vm-smoke.sh
```

UI Playwright проверяет production standalone: fragment-токен, ошибки API и создание приложения с CSRF, используя контролируемые ответы API. Это не заменяет Linux smoke. `AccessIntegrationTest` с `RUN_DB_TESTS=true` проверяет настоящую MariaDB: миграции, регистрацию, конкурентное погашение bootstrap, повтор токена, email binding, Secure cookie, CSRF, logout, последнего администратора, исчерпание квот и повтор CREATE с тем же idempotency key. Назначение прав отзывает старую сессию; наблюдатель видит только назначенное приложение и не выполняет Start; блокировка пользователя закрывает действующий WebSocket. `scripts/run-local-db-tests.py` запускает одноразовый дочерний MariaDB процесс на Windows без регистрации службы.

GitHub check workflow использует MariaDB 10.11. Release workflow собирает standalone + API/agent, manifest/digests/SHA256SUMS, загружает архивы в новую Ubuntu cloud-image VM. Проверяются CPU throttling, cgroup OOM, XFS hard quota, UID/sandbox, два JDK, upload, HTTP Range download, защита работающего JAR, ETag conflict, копирование каталогов, 32 MiB copy/archive, свежие метрики, изоляция, независимый UFW rollback, повтор installer и reboot с autostart и ручным Stop. Логи сохраняются как artifact.

## Границы текущей версии

- Managed MariaDB приложений, SQL UI, backup/restore и TOTP — последующие этапы плана.
- Публикация контейнеров — IPv4; UFW host rules поддерживают IPv4/IPv6. Дополнительный Docker IPv6 adapter требует отдельной проверки.
- Копирование/архивирование ограничены 64 MiB, текстовый редактор — 1 MiB, upload — 8 GiB и 24 часа. Linux hard quota действует также на временные загрузки и корзину.
- После удаления с сохранением файлов volume остаётся на сервере и продолжает занимать дисковый резерв. Публичный restore архивов резервных копий относится к этапу 8.
- Метрики Prometheus — CPU cores, RAM percent, disk blocks и uptime; heap/GC/JMX не обещаются произвольному JAR.
- Dependency audit показывает advisory в dev-only цепочке braces/micromatch/eslint-config-next; runtime-зависимости проверяются отдельно. Next.js не понижается ради предложенного npm audit downgrade.
- Аудит хранится 90 дней; успешные/неуспешные временные FILE/LOGS/FIREWALL ответы удаляются после выдачи и очищаются по TTL 1 час при разрыве клиента.
- Каталог JDK хранит digest; изменение каталога влияет на новые ревизии. Откат создаёт новую ревизию с прежним digest и ENV, затем требует применения с перезапуском.

Фактические результаты сборок, установки и оставшиеся ограничения записываются в [MVP_VALIDATION.md](MVP_VALIDATION.md).
