from pathlib import Path

path = Path("src/main/java/io/github/bookingbot/BookingBot.java")
src = path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if old not in source:
        raise SystemExit(f"Expected {label} block not found; refusing to patch unknown source")
    return source.replace(old, new, 1)


run_old = '''    private void run() throws Exception {
        log("START targetDate=" + cfg.targetDate + " dryRun=" + cfg.dryRun);
        loginFresh();

        Document schedule = getDocument(scheduleUri(), "SCHEDULE");
        ensureAuthenticated(schedule, "SCHEDULE");

        List<Slot> slots = findCandidateSlots(schedule);
        log("SCHEDULE candidateCount=" + slots.size()
                + " candidateStarts=" + slots.stream().map(s -> s.start.toLocalTime().toString()).toList());
        if (slots.isEmpty()) throw new BotException("NO_CANDIDATE_SLOTS", 20);

        for (Slot slot : slots) {
            try {
                if (trySlot(slot)) return;
            } catch (BotException e) {
                if ("SESSION_EXPIRED".equals(e.code)) {
                    log("AUTH sessionExpired=true action=relogin");
                    loginFresh();
                    if (trySlot(slot)) return;
                }
                if (e.code.startsWith("CLOUDFLARE_")) throw e;
                log("SLOT start=" + slot.start.toLocalTime() + " rejected=" + e.code);
            }
        }

        throw new BotException("NO_RESERVATION_CREATED", 30);
    }

    private void loginFresh() throws Exception {'''

run_new = '''    private void run() throws Exception {
        log("START targetDate=" + cfg.targetDate + " dryRun=" + cfg.dryRun);
        loginFresh();

        Facility primary = new Facility("PRIMARY", cfg.clubPath, cfg.objectId, cfg.discipline);
        Facility fallback = configuredFallback();

        boolean primarySucceeded = attemptFacility(primary, false);
        if (primarySucceeded) {
            if (cfg.dryRun && fallback != null) {
                log("FALLBACK probe=true action=diagnostic_only");
                try {
                    attemptFacility(fallback, true);
                } catch (BotException e) {
                    if (e.code.startsWith("CLOUDFLARE_") || "SESSION_EXPIRED".equals(e.code)) throw e;
                    log("FALLBACK probeResult=" + e.code);
                }
            }
            return;
        }

        if (fallback != null) {
            log("FALLBACK reason=PRIMARY_NO_ACCEPTABLE_RESERVATION action=try_fallback");
            if (attemptFacility(fallback, false)) return;
        }

        throw new BotException("NO_RESERVATION_CREATED", 30);
    }

    private boolean attemptFacility(Facility facility, boolean diagnosticOnly) throws Exception {
        String scheduleStage = facility.label + "_SCHEDULE";
        Document schedule = getDocument(scheduleUri(facility), scheduleStage);
        ensureAuthenticated(schedule, scheduleStage);

        List<Slot> slots = findCandidateSlots(schedule, facility);
        log("FACILITY label=" + facility.label
                + " candidateCount=" + slots.size()
                + " candidateStarts=" + slots.stream().map(s -> s.start.toLocalTime().toString()).toList()
                + " diagnosticOnly=" + diagnosticOnly);

        if (slots.isEmpty()) {
            log("FACILITY label=" + facility.label + " result=NO_CANDIDATE_SLOTS");
            return false;
        }

        for (Slot slot : slots) {
            try {
                if (trySlot(slot)) {
                    log("FACILITY label=" + facility.label + " result=ACCEPTED_BY_VALIDATION");
                    return true;
                }
            } catch (BotException e) {
                if ("SESSION_EXPIRED".equals(e.code)) {
                    log("AUTH sessionExpired=true facility=" + facility.label + " action=relogin");
                    loginFresh();
                    if (trySlot(slot)) {
                        log("FACILITY label=" + facility.label + " result=ACCEPTED_AFTER_RELOGIN");
                        return true;
                    }
                }
                if (e.code.startsWith("CLOUDFLARE_")) throw e;
                log("FACILITY label=" + facility.label
                        + " slot=" + slot.start.toLocalTime()
                        + " rejected=" + e.code);
            }
        }

        log("FACILITY label=" + facility.label + " result=NO_ACCEPTABLE_RESERVATION");
        return false;
    }

    private Facility configuredFallback() {
        String club = envTrim("BOOKING_FALLBACK_CLUB_PATH");
        String objectId = envTrim("BOOKING_FALLBACK_OBJECT_ID");
        String discipline = envTrim("BOOKING_FALLBACK_DISCIPLINE");
        boolean any = !club.isBlank() || !objectId.isBlank() || !discipline.isBlank();
        boolean all = !club.isBlank() && !objectId.isBlank() && !discipline.isBlank();
        if (any && !all) throw new BotException("INCOMPLETE_FALLBACK_CONFIG", 2);
        if (!all) return null;
        return new Facility("FALLBACK", Config.normalizePath(club), objectId, discipline);
    }

    private static String envTrim(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private void loginFresh() throws Exception {'''

src = replace_once(src, run_old, run_new, "run/fallback orchestration")

find_old = '''    private List<Slot> findCandidateSlots(Document schedule) {
        Pattern p = Pattern.compile("/grafik/(?:rezerwuj-standard|rezerwuj)/"
                + Pattern.quote(cfg.objectId) + "/(\\\\d+)");
        Map<Long, Slot> unique = new LinkedHashMap<>();

        for (Element el : schedule.select("[href]")) {
            Matcher m = p.matcher(el.attr("href"));
            if (m.find()) addCandidate(unique, Long.parseLong(m.group(1)), el.attr("href"));
        }

        Matcher raw = p.matcher(schedule.outerHtml());
        while (raw.find()) addCandidate(unique, Long.parseLong(raw.group(1)), raw.group(0));

        ArrayList<Slot> slots = new ArrayList<>(unique.values());
        slots.sort(Comparator.comparingInt(this::slotPriority).thenComparing(s -> s.start));
        return slots;
    }

    private void addCandidate(Map<Long, Slot> out, long epoch, String href) {
        ZonedDateTime start = Instant.ofEpochSecond(epoch).atZone(ZONE);
        if (!start.toLocalDate().equals(cfg.targetDate)) return;
        if (start.toLocalTime().isBefore(cfg.minStart)) return;
        if (!start.toLocalTime().isBefore(cfg.latestEnd)) return;
        out.putIfAbsent(epoch, new Slot(start, resolve(href)));
    }'''

find_new = '''    private List<Slot> findCandidateSlots(Document schedule, Facility facility) {
        Pattern p = Pattern.compile("/grafik/(?:rezerwuj-standard|rezerwuj)/"
                + Pattern.quote(facility.objectId) + "/(\\\\d+)");
        Map<Long, Slot> unique = new LinkedHashMap<>();

        for (Element el : schedule.select("[href]")) {
            Matcher m = p.matcher(el.attr("href"));
            if (m.find()) addCandidate(unique, Long.parseLong(m.group(1)), el.attr("href"));
        }

        Matcher raw = p.matcher(schedule.outerHtml());
        while (raw.find()) addCandidate(unique, Long.parseLong(raw.group(1)), raw.group(0));

        ArrayList<Slot> slots = new ArrayList<>(unique.values());
        slots.sort(Comparator.comparingInt(this::slotPriority).thenComparing(s -> s.start));
        return slots;
    }

    private void addCandidate(Map<Long, Slot> out, long epoch, String href) {
        ZonedDateTime start = Instant.ofEpochSecond(epoch).atZone(ZONE);
        if (!start.toLocalDate().equals(cfg.targetDate)) return;
        if (start.toLocalTime().isBefore(cfg.minStart)) return;
        if (!start.toLocalTime().isBefore(cfg.latestEnd)) return;
        out.putIfAbsent(epoch, new Slot(start, resolve(href)));
    }'''

src = replace_once(src, find_old, find_new, "facility-specific candidate parsing")

schedule_old = '''    private URI scheduleUri() {
        return resolve(cfg.clubPath + "/grafik?data_grafiku=" + enc(cfg.targetDate.toString())
                + "&dyscyplina=" + enc(cfg.discipline) + "&strona=0");
    }'''

schedule_new = '''    private URI scheduleUri(Facility facility) {
        return resolve(facility.clubPath + "/grafik?data_grafiku=" + enc(cfg.targetDate.toString())
                + "&dyscyplina=" + enc(facility.discipline) + "&strona=0");
    }'''

src = replace_once(src, schedule_old, schedule_new, "facility-specific schedule URI")

record_old = '''    private record Slot(ZonedDateTime start, URI uri) {}
    private record DurationChoice(String value, int minutes) {}'''
record_new = '''    private record Facility(String label, String clubPath, String objectId, String discipline) {}
    private record Slot(ZonedDateTime start, URI uri) {}
    private record DurationChoice(String value, int minutes) {}'''
src = replace_once(src, record_old, record_new, "facility record")

path.write_text(src, encoding="utf-8")
print("Fallback facility patch applied")

polling_script = Path(".github/scripts/patch_polling.py")
exec(compile(polling_script.read_text(encoding="utf-8"), str(polling_script), "exec"))
