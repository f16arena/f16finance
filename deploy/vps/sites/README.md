# Сайты с бывшего Vercel padash00 на cloud-001

| Сайт | Репозиторий | Папка | Порт | Домены |
|---|---|---|---|---|
| f16-arena (лендинг) | github.com/padash00/newf16extra-arena | /opt/sites/f16-arena | 127.0.0.1:3320 | f16-arena.kz → www.f16-arena.kz |
| tour (турниры CS2) | github.com/padash00/Tour | /opt/sites/tour | 127.0.0.1:3330 | tournament.f16-arena.kz |

- Сборка: `Dockerfile.next` (общий, кладётся в клон как `.Dockerfile.server`), `compose.yml`, параметры сайта — `/opt/sites/<site>/site.env`, ключи — `/opt/sites/<site>/web.env` (600; при сборке передаются секретом, в образ не попадают).
- Автодеплой: `/etc/cron.d/sites-deploy` → `site-deploy.sh <site>` раз в минуту, push в main → пересборка. Руками: `FORCE=1 sh /opt/sites/bin/site-deploy.sh tour`. Лог: `/var/log/site-<site>-deploy.log`.
- База Tour — облачный Supabase `dgpxlpjnjthyotjcccfl` (другой аккаунт), пока не переносилась.
- Caddy: `f16-arena.caddy` → `/etc/caddy/sites/` после переключения DNS в ps.kz (A-записи @, www, tournament → 185.113.132.3).
