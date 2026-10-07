from pathlib import Path

path = Path("src/main/java/io/github/bookingbot/BookingBot.java")
src = path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if old not in source:
        raise SystemExit(f"Expected {label} block not found; refusing to patch unknown source")
    return source.replace(old, new, 1)


run_start_old = '''    private void run() throws Exception {
        log("START targetDate=" + cfg.targetDate + " dryRun=" + cfg.dryRun);
        loginFresh();

        Facility primary = new Facility("PRIMARY", cfg.clubPath, cfg.objectId, cfg.discipline);'''

run_start_new = '''    private void run() throws Exception {
        log("START targetDate=" + cfg.targetDate + " dryRun=" + cfg.dryRun
                + " polling=" + pollingEnabled());
        if (pollingEnabled()) {
            runPolling();
            return;
        }

        loginFresh();
        Facility primary = new Facility("PRIMARY", cfg.clubPath, cfg.objectId, cfg.discipline);'''

src = replace_once(src, run_start_old, run_start_new, "polling branch")

attempt_marker = '''    private boolean attemptFacility(Facility facility, boolean diagnosticOnly) throws Exception {'''

polling_helpers = '''    private void runPolling() throws Exception {
        loginFresh();
        Facility primary = new Facility("PRIMARY", cfg.clubPath, cfg.objectId, cfg.discipline);
        Facility fallback = configuredFallback();
        ZonedDateTime deadline = pollingDeadline();
        int intervalSeconds = pollingIntervalSeconds();
        int pollCount = 0;
        boolean relogged = false;
        boolean opened = false;

        log("POLL start targetDate=" + cfg.targetDate
                + " deadline=" + deadline.format(LOG_TIME)
                + " intervalSeconds=" + intervalSeconds);

        while (!ZonedDateTime.now(ZONE).isAfter(deadline)) {
            pollCount++;
            try {
                Document schedule = getDocument(scheduleUri(primary), "POLL_PRIMARY_SCHEDULE");
                ensureAuthenticated(schedule, "POLL_PRIMARY_SCHEDULE");
                OpenSignal signal = inspectOpenSignal(schedule, primary);
                log("POLL count=" + pollCount
                        + " dateMarker=" + signal.dateMarker
                        + " targetRouteCount=" + signal.targetRouteCount
                        + " opened=" + signal.opened);

                if (signal.opened) {
                    opened = true;
                    log("POLL targetOpened=true firstObserved=true pollCount=" + pollCount);
                    break;
                }
            } catch (BotException e) {
                if ("SESSION_EXPIRED".equals(e.code) && !relogged) {
                    relogged = true;
                    log("AUTH pollingSessionExpired=true action=relogin_once");
                    loginFresh();
                    continue;
                }
                throw e;
            }

            sleepPolling(intervalSeconds);
        }

        if (!opened) {
            log("POLL targetOpened=false pollCount=" + pollCount + " action=stop");
            throw new BotException("NEW_DAY_NOT_OPENED_BY_DEADLINE", 31);
        }

        if (attemptFacility(primary, false)) return;

        if (fallback != null) {
            log("FALLBACK reason=PRIMARY_OPEN_BUT_NO_ACCEPTABLE_RESERVATION action=try_fallback");
            if (attemptFacility(fallback, false)) return;
        }

        throw new BotException("NO_RESERVATION_CREATED", 30);
    }

    private boolean pollingEnabled() {
        String eventName = envTrim("GITHUB_EVENT_NAME");
        return "schedule".equalsIgnoreCase(eventName)
                || Boolean.parseBoolean(envTrim("BOOKING_POLL_MODE"));
    }

    private ZonedDateTime pollingDeadline() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalTime sixPastMidnight = LocalTime.of(0, 6);
        LocalDate deadlineDate = now.toLocalTime().isBefore(sixPastMidnight)
                ? now.toLocalDate()
                : now.toLocalDate().plusDays(1);
        return deadlineDate.atTime(0, 5).atZone(ZONE);
    }

    private int pollingIntervalSeconds() {
        String raw = envTrim("BOOKING_POLL_INTERVAL_SECONDS");
        int value = raw.isBlank() ? 5 : Integer.parseInt(raw);
        return Math.max(2, Math.min(60, value));
    }

    private void sleepPolling(int seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BotException("POLL_INTERRUPTED", 40);
        }
    }

    private OpenSignal inspectOpenSignal(Document schedule, Facility facility) {
        String html = schedule.outerHtml();
        String target = cfg.targetDate.toString();
        boolean dateMarker = html.contains("data_grafiku=" + target)
                || html.contains("data_grafiku%3D" + target)
                || html.contains("data_grafiku%3d" + target);

        Pattern p = Pattern.compile("/grafik/(?:rezerwuj-standard|rezerwuj)/"
                + Pattern.quote(facility.objectId) + "/(\\\\d+)");
        Matcher m = p.matcher(html);
        java.util.Set<Long> epochs = new java.util.HashSet<>();
        while (m.find()) {
            long epoch = Long.parseLong(m.group(1));
            ZonedDateTime start = Instant.ofEpochSecond(epoch).atZone(ZONE);
            if (start.toLocalDate().equals(cfg.targetDate)) epochs.add(epoch);
        }

        int targetRouteCount = epochs.size();
        return new OpenSignal(dateMarker || targetRouteCount > 0, dateMarker, targetRouteCount);
    }

    private boolean attemptFacility(Facility facility, boolean diagnosticOnly) throws Exception {'''

src = replace_once(src, attempt_marker, polling_helpers, "polling helpers")

config_target_old = '''                    LocalDate.parse(required("BOOKING_TARGET_DATE")),'''
config_target_new = '''                    resolveTargetDate(),'''
src = replace_once(src, config_target_old, config_target_new, "automatic target date")

config_required_marker = '''        private static String required(String name) {'''
config_helpers = '''        private static LocalDate resolveTargetDate() {
            String eventName = optional("GITHUB_EVENT_NAME", "");
            boolean automatic = "schedule".equalsIgnoreCase(eventName)
                    || Boolean.parseBoolean(optional("BOOKING_POLL_MODE", "false"));
            if (!automatic) return LocalDate.parse(required("BOOKING_TARGET_DATE"));

            int offsetDays = Integer.parseInt(optional("BOOKING_TARGET_OFFSET_DAYS", "4"));
            return ZonedDateTime.now(ZONE).toLocalDate().plusDays(offsetDays);
        }

        private static String required(String name) {'''
src = replace_once(src, config_required_marker, config_helpers, "target date resolver")

record_marker = '''    private record Facility(String label, String clubPath, String objectId, String discipline) {}
    private record Slot(ZonedDateTime start, URI uri) {}'''
record_new = '''    private record Facility(String label, String clubPath, String objectId, String discipline) {}
    private record OpenSignal(boolean opened, boolean dateMarker, int targetRouteCount) {}
    private record Slot(ZonedDateTime start, URI uri) {}'''
src = replace_once(src, record_marker, record_new, "open signal record")

path.write_text(src, encoding="utf-8")
print("Server-driven polling patch applied")
