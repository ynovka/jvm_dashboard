# ADR 001 — JVM Dashboard MVP

Дата: 4 октября 2026. Решение: Next.js 16.3.8 + Kotlin/Ktor, отдельный агент одного Linux-узла.

MVP охватывает этапы 0–5 и 5а плана. MariaDB приложений, SQL-редактор, backup/restore и TOTP относятся к этапам 6–8.

Next.js предоставляет интерфейс. Авторизация, квоты, ревизии и задания находятся в API; браузер не получает доступ к Docker или служебным credentials. Caddy сохраняет префиксы `/api/v1` и `/ws`. Внутренний listener Caddy на loopback:8444 проверяет клиентский сертификат; агент устанавливает исходящие HTTPS-соединения с отдельным node token. Веб-адрес не маршрутизирует `/internal`.

Метаданные: MariaDB 10.11 LTS, InnoDB, utf8mb4, JDBC/HikariCP и Liquibase SQL changesets. Пароли: Argon2id (64 MiB, 3 прохода, один поток; одна параллельная задача хеширования). Сессии: случайный 256-битный токен, хранение SHA256, абсолютный TTL 7 дней / idle 24 часа, Secure/HttpOnly/SameSite=Strict, Origin + CSRF. Регистрация сериализована блокировкой bootstrap_state. ENV секреты и журнал агента зашифрованы AES-256-GCM; ключ хранится вне MariaDB.

Docker запускает Temurin 8/11/17/21/25 по digest из manifest конкретного Release. Digest сохраняется в ревизии приложения. Non-root UID выделяется на volume; read-only rootfs, dropped capabilities, no-new-privileges, cgroups v2, RAM=swap limit, CPU quota, PID/nofile и ротация логов применяются явно. Docker restart policy — `no`; намерение и generation агента сохраняются атомарно с fsync.

Данные: новый preallocated XFS loopback, project block/inode quotas, бюджеты без overcommit. Остановленные приложения сохраняют резервы. При отложенном уменьшении ресурсов резерв остаётся на прежнем уровне до успешного Apply. Сохранённые после Delete volumes продолжают занимать дисковый резерв.

Файлы: отдельный Python worker в bubblewrap mount/PID/network namespace, UID приложения, без Docker socket. Разрешение пользовательских путей — openat2 RESOLVE_IN_ROOT/NO_SYMLINKS/NO_MAGICLINKS; специальные файлы и hardlink запрещены. Запись временного файла + fsync + replace, проверка ETag, resumable chunk uploads. Полная замена файла внешним JVM-процессом одновременно с последним compare/rename не образует транзакцию между двумя процессами; перед заменой JAR требуется Stop.

Сеть: отдельные Docker bridges, публикация IPv4; UFW защищает IPv4/IPv6 хоста. DOCKER-USER и INPUT ограничивают только управляемые подсети, блокируют частные/metadata endpoints и доступ к служебным портам. IPv6 публикация контейнеров пока не включена. UFW сохраняет внешние правила; каждое изменение требует подтверждения за 90 секунд, systemd timer откатывает его независимо от API.

Фиксированные зависимости: Kotlin 2.2.20, Gradle 8.14.3, Ktor 3.3.1, serialization 1.9.0, Connector/J 3.5.6, HikariCP 7.0.2, Liquibase 4.33.0, Argon2 2.12, Node 24.16.0. Пакеты Docker/Caddy/MariaDB/Prometheus поставляются подписанными apt repositories для Ubuntu 24.04; точные установленные patch-версии проверяются на стенде.

Первичные руководства: [Kotlin/Gradle](https://kotlinlang.org/docs/gradle-configure-project.html), [Ktor configuration](https://ktor.io/docs/server-configuration-code.html), [Docker Ubuntu](https://docs.docker.com/engine/install/ubuntu/), [Caddy mTLS](https://caddyserver.com/docs/caddyfile/directives/tls). Для Next.js прочитаны локальные docs Server/Client Components, Authentication, Self-hosting, output и rewrites.
