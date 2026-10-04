# Приёмка MVP

Дата проверок: 4 октября 2026 года. **MVP принят:** реализованы этапы 0–5 и 5а исходного плана. [Выпуск v0.1.2](https://github.com/ynovka/jvm_dashboard/releases/tag/v0.1.2) опубликован после успешной проверки новой Ubuntu 24.04 VM и установлен из GitHub Release на [тестовый VPS](https://test.myshore.party).

Проверяемый commit: `dd48c762d3a4abc9575eeb20b062298b0831a334`. [Основной CI](https://github.com/ynovka/jvm_dashboard/actions/runs/37229054626) и [проверка выпуска](https://github.com/ynovka/jvm_dashboard/actions/runs/37229054478) прошли. Release workflow содержит отдельную свежую Ubuntu 24.04 VM и сохраняет её журналы.

| Проверка | Результат |
| --- | --- |
| Next.js production standalone / ESLint | Прошли локально и в CI |
| Kotlin API/agent installDist | Прошёл локально и в CI |
| Validation / crypto / XFS report parsing | Kotlin tests прошли |
| File/firewall helpers | 10 tests прошли на Linux; traversal, ZIP/TAR links, ETag, trash, upload offsets, directory copy |
| MariaDB 10.11 integration | Прошёл: конкурентный bootstrap, одноразовые/email-bound приглашения, cookies/CSRF/logout, квоты/idempotency, последний администратор, object permissions |
| Отзыв доступа | Изменение прав отзывает сессию; VIEWER не выполняет Start; disable закрывает настоящий активный WebSocket |
| UI Playwright | 3 сценария прошли на production standalone: fragment, ошибка API, создание приложения с CSRF; ответы API в этих UI tests контролируемые |
| Ubuntu foundation | На VPS и в итоговой VM подтверждены JDK 8/21, UID, bubblewrap без capabilities, CPU throttling (0.250 vCPU при лимите 0.25), cgroup OOM, XFS hard quota, symlink isolation |
| Ubuntu VM: installation | Fresh install с готовыми pre-release архивами, systemd/Caddy/MariaDB/Prometheus, CSS/public assets, HTTPS с внутренним CA, повтор installer — прошли |
| Ubuntu VM: API / files | Bootstrap → upload JAR → Start → логи/метрики → Stop на JDK 8/21; HTTP Range, работающий JAR нельзя подменить, ETag conflict, directory copy, 32 MiB copy/archive и ranged download — прошли |
| Ubuntu VM: firewall / reboot | Независимый UFW rollback через 90 секунд; reboot сохраняет аккаунты и XFS, запускает RUNNING/autostart приложение и оставляет вручную остановленное STOPPED — прошли |
| VPS: GitHub Release / HTTPS / persistence | Опубликованная команда установила v0.1.2; публичный Let's Encrypt HTTPS работает; после reboot все шесть служб активны, XFS project quota и firewall восстановлены, heartbeat свежий, хеши ключей не изменились |
| VPS: первый аккаунт / браузер | Свежая bootstrap-ссылка открывается по HTTPS, кнопка регистрации доступна, fragment удаляется, ошибок загрузки assets нет; регистрация оставлена владельцу |
| VPS: UFW / Docker filtering | Проверены внешний closed/public port, восстановление после Docker restart, запрет контейнер → SSH хоста; временный fixture удалён |

На тестовом VPS доступны 1920 MiB RAM и 14 GiB root disk. После системного резерва бюджет приложений — **1 vCPU, 384 MiB RAM, 864 MiB диска** в отдельном XFS volume 1 GiB. Начальная конфигурация — одно приложение с 256 MiB RAM. Без пользовательских приложений после reboot использовано около 850 MiB RAM; нагрузочная ёмкость большого узла не заявляется.

Системные MariaDB и Prometheus работают только на loopback. Пользовательские базы MariaDB, SQL-редактор, backup/restore, TOTP и проверка несовместимых upgrades относятся к следующим этапам плана. Docker container networking в MVP — IPv4; host UFW поддерживает IPv4/IPv6. Численные ограничения файлов и эксплуатационные команды приведены в [MVP.md](MVP.md).

Тестовые аккаунты создаются только в одноразовой VM. На пользовательском VPS первое bootstrap-приглашение оставлено для владельца; токены, SSH-пароль и GitHub credentials в репозиторий не включаются.
