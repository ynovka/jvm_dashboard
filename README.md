# JVM Dashboard

Панель управления JVM-приложениями: Next.js 16, Kotlin/Ktor API и агент Ubuntu, MariaDB метаданных, Docker, XFS-квоты, Caddy, Prometheus и UFW.

MVP включает регистрацию по приглашениям, права и квоты рабочих областей, запуск на Temurin 8/11/17/21/25, файлы и ENV, ревизии, логи, метрики и установку готовых сборок одной командой.

- [Установка, разработка и ограничения](docs/MVP.md)
- [Результаты проверок](docs/MVP_VALIDATION.md)
- [Архитектурные решения](docs/ADR-001-MVP.md)
- [HTTP API](docs/openapi.json) и [WebSocket](docs/events.schema.json)
- [Исходный план](docs/JVM_PANEL_PLAN.md)

```bash
npm ci
npm run dev
./gradlew test installDist
```

Исполнение приложений требует Ubuntu 24.04, Docker cgroups v2 и XFS project quotas. MariaDB приложений, SQL-редактор, backups и TOTP относятся к следующим этапам плана.
