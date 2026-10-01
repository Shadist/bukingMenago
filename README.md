# Java HTTP Booking Bot

Java 21 + GitHub Actions booking client intended for a public repository.

## Secrets

The repository contains no real domain, login, password, venue path, object ID or discipline ID.

Configure these in:

`Settings -> Secrets and variables -> Actions -> New repository secret`

Required:
- `BOOKING_BASE_URL`
- `BOOKING_LOGIN`
- `BOOKING_PASSWORD`
- `BOOKING_CLUB_PATH`
- `BOOKING_OBJECT_ID`
- `BOOKING_DISCIPLINE`

Optional:
- `BOOKING_LOGIN_PATH` (defaults to `<club path>/logowanie`)
- `BOOKING_VERIFY_PATH` (defaults to `<club path>/profil`)

## Runtime behavior

Each run:
1. starts with an empty cookie jar,
2. opens the login form,
3. detects the username and password fields,
4. submits credentials,
5. verifies authentication,
6. loads the target schedule,
7. selects an acceptable slot,
8. submits validation,
9. checks the explicit price,
10. submits final confirmation only when price <= `BOOKING_MAX_PRICE`.

Cookies stay in memory only. URLs, cookies, credentials and full HTML bodies are not logged.

If a Cloudflare/browser challenge appears, the program exits instead of trying to bypass it.

## Test workflow

The checked-in workflow supports both scheduled and manual runs.

Manual runs default to `dry_run=true`, meaning the reservation is validated but final acceptance is skipped unless you explicitly disable dry-run.

The current scheduled smoke test is guarded to run only on 2026-09-30 at 16:00 Europe/Warsaw. For future use, change the schedule and/or remove the one-time date guard.

## Build

```bash
mvn -DskipTests package
```

## Notes

GitHub-hosted scheduled workflows can start later than the nominal cron time under load. For competitive booking windows near midnight, a VPS or self-hosted runner may be preferable.
