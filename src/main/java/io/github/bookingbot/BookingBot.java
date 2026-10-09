package io.github.bookingbot;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BookingBot {
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z");

    private final Config cfg;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private final String traceId = UUID.randomUUID().toString().substring(0, 8);

    private BookingBot(Config cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static void main(String[] args) {
        try {
            new BookingBot(Config.fromEnvironment()).run();
        } catch (BotException e) {
            System.err.println("BOOKING_BOT_ERROR=" + e.code);
            System.exit(e.exitCode);
        } catch (Throwable e) {
            System.err.println("BOOKING_BOT_ERROR=UNEXPECTED_" + e.getClass().getSimpleName());
            System.exit(99);
        }
    }

    private void run() throws Exception {
        log("START targetDate=" + cfg.targetDate + " dryRun=" + cfg.dryRun
                + " polling=" + pollingEnabled());
        if (pollingEnabled()) {
            runPolling();
            return;
        }

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

    private void runPolling() throws Exception {
        loginFresh();
        Facility primary = new Facility("PRIMARY", cfg.clubPath, cfg.objectId, cfg.discipline);
        Facility fallback = configuredFallback();
        ZonedDateTime deadline = pollingDeadline();
        int pollCount = 0;
        boolean relogged = false;
        boolean opened = false;

        log("POLL start targetDate=" + cfg.targetDate
                + " deadline=" + deadline.format(LOG_TIME)
                + " slowIntervalSeconds=180 fastFrom=23:54 fastIntervalSeconds=" + fastPollingIntervalSeconds());

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

            int sleepSeconds = pollingIntervalSeconds();
            log("POLL nextPollInSeconds=" + sleepSeconds);
            sleepPolling(sleepSeconds);
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
        return Boolean.parseBoolean(envTrim("BOOKING_POLL_MODE"));
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
        LocalTime now = ZonedDateTime.now(ZONE).toLocalTime();
        LocalTime fastFrom = LocalTime.of(23, 54);
        LocalTime fastUntil = LocalTime.of(0, 5);

        boolean fastWindow = !now.isBefore(fastFrom) || !now.isAfter(fastUntil);
        if (fastWindow) return fastPollingIntervalSeconds();

        long secondsUntilFast = Duration.between(now, fastFrom).getSeconds();
        return (int) Math.max(1, Math.min(180, secondsUntilFast));
    }

    private int fastPollingIntervalSeconds() {
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
                + Pattern.quote(facility.objectId) + "/([0-9]+)");
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
                if (e.code.startsWith("CLOUDFLARE_") || e.code.startsWith("FINAL_")) throw e;
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

    private void loginFresh() throws Exception {
        cookies.getCookieStore().removeAll();
        log("AUTH freshSession=true cookieCount=0");

        URI loginUri = resolve(cfg.loginPath);
        Document loginPage = getDocument(loginUri, "LOGIN_GET");
        Element form = findLoginForm(loginPage);
        if (form == null) {
            log("AUTH loginForm=false");
            throw new BotException("LOGIN_FORM_NOT_FOUND", 11);
        }

        Map<String, String> data = formFields(form);
        Element pass = form.selectFirst("input[type=password][name]");
        if (pass == null) throw new BotException("PASSWORD_FIELD_NOT_FOUND", 11);

        Element login = form.selectFirst("input[type=email][name]");
        if (login == null) {
            login = form.select("input[type=text][name]").stream()
                    .filter(el -> !looksLikeSearch(el))
                    .findFirst().orElse(null);
        }
        if (login == null) throw new BotException("LOGIN_FIELD_NOT_FOUND", 11);

        data.put(login.attr("name"), cfg.login);
        data.put(pass.attr("name"), cfg.password);

        int rememberFields = 0;
        for (Element cb : form.select("input[type=checkbox][name]")) {
            String hay = (cb.attr("name") + " " + labelText(loginPage, cb)).toLowerCase(Locale.ROOT);
            if (hay.contains("remember") || hay.contains("pamiętaj") || hay.contains("pamietaj")) {
                data.put(cb.attr("name"), cb.hasAttr("value") ? cb.attr("value") : "1");
                rememberFields++;
            }
        }

        Element submit = chooseSubmit(form, "zalog", "login", "sign in", "signin");
        String submitField = "-";
        if (submit != null && submit.hasAttr("name") && !submit.attr("name").isBlank()) {
            submitField = safeFieldName(submit.attr("name"));
            data.put(submit.attr("name"), submit.hasAttr("value") ? submit.attr("value") : submit.text());
        }

        log("AUTH loginForm=true loginField=" + safeFieldName(login.attr("name"))
                + " passwordField=" + safeFieldName(pass.attr("name"))
                + " submitField=" + submitField
                + " formFieldCount=" + data.size()
                + " rememberFieldCount=" + rememberFields);

        URI action = formAction(loginUri, form);
        HttpResponse<String> loginResponse = postForm(action, data, loginUri, "LOGIN_POST");
        Document afterPost = Jsoup.parse(loginResponse.body() == null ? "" : loginResponse.body(),
                loginResponse.uri().toString());
        boolean loginFormStillPresent = findLoginForm(afterPost) != null;
        boolean logoutLink = !afterPost.select("a[href*=wyloguj]").isEmpty();
        log("AUTH postResult loginFormStillPresent=" + loginFormStillPresent
                + " logoutLink=" + logoutLink
                + " cookieCount=" + cookieCount());

        if (loginFormStillPresent && !logoutLink) {
            throw new BotException("LOGIN_FAILED_FORM_STILL_PRESENT", 12);
        }
        log("AUTH freshLogin=accepted_by_post");
    }

    private static Element chooseSubmit(Element form, String... keywords) {
        for (Element el : form.select("input[type=submit],button[type=submit],button:not([type])")) {
            String hay = (el.attr("name") + " " + el.attr("value") + " " + el.text()).toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (hay.contains(keyword.toLowerCase(Locale.ROOT))) return el;
            }
        }
        Element named = form.selectFirst("input[type=submit][name],button[type=submit][name],button:not([type])[name]");
        if (named != null) return named;
        return form.selectFirst("input[type=submit],button[type=submit],button:not([type])");
    }

    private static String routeKind(URI uri) {
        if (uri == null) return "unknown";
        String p = uri.getPath() == null ? "" : uri.getPath();
        if (p.contains("/rezerwuj-standard/")) return "rezerwuj-standard";
        if (p.contains("/rezerwuj/")) return "rezerwuj";
        if (p.endsWith("/grafik") || p.contains("/grafik/")) return "grafik";
        return "other";
    }

    private boolean trySlot(Slot slot) throws Exception {
        log("SLOT try start=" + slot.start.toLocalTime());

        Document first = getDocument(slot.uri, "SLOT_GET");
        ensureAuthenticated(first, "SLOT_GET");

        Element form = findReservationForm(first);
        if (form == null) throw new BotException("RESERVATION_FORM_NOT_FOUND", 21);

        List<DurationChoice> durations = durationChoices(form, slot.start.toLocalTime());
        if (durations.isEmpty()) throw new BotException("NO_ACCEPTABLE_DURATION", 21);

        log("BOOKING durationCandidates start=" + slot.start.toLocalTime()
                + " minutes=" + durations.stream().map(d -> d.minutes).toList());

        BotException lastRejected = null;
        for (DurationChoice duration : durations) {
            try {
                if (trySlotDuration(slot, first, form, duration)) return true;
            } catch (BotException e) {
                if (e.code.startsWith("CLOUDFLARE_") || "SESSION_EXPIRED".equals(e.code)) throw e;
                if ("SERVER_VALIDATION_REJECTED".equals(e.code)
                        || "PRICE_ABOVE_LIMIT".equals(e.code)) {
                    lastRejected = e;
                    log("BOOKING durationRejected start=" + slot.start.toLocalTime()
                            + " durationMin=" + duration.minutes
                            + " reason=" + e.code
                            + " action=try_next_duration");
                    continue;
                }
                throw e;
            }
        }

        if (lastRejected != null) throw new BotException("NO_DURATION_VALIDATED", 22);
        throw new BotException("NO_ACCEPTABLE_DURATION", 21);
    }

    private boolean trySlotDuration(
            Slot slot,
            Document first,
            Element form,
            DurationChoice duration
    ) throws Exception {
        Map<String, String> step2 = formFields(form);
        step2.put("ile_czasu", duration.value);
        acceptMandatoryConsents(first, form, step2);
        step2.put("nowa_rezerwacja_kroki", "2");

        Element nextSubmit = chooseSubmit(form, "przejdź dalej", "przejdz dalej", "dalej", "continue", "next");
        String nextSubmitField = "-";
        if (nextSubmit != null && nextSubmit.hasAttr("name") && !nextSubmit.attr("name").isBlank()) {
            nextSubmitField = safeFieldName(nextSubmit.attr("name"));
            step2.put(nextSubmit.attr("name"), nextSubmit.hasAttr("value") ? nextSubmit.attr("value") : nextSubmit.text());
        }

        URI formBase = first.baseUri().isBlank() ? slot.uri : URI.create(first.baseUri());
        URI action = formAction(formBase, form);
        log("BOOKING validate start=" + slot.start.toLocalTime()
                + " durationMin=" + duration.minutes
                + " submitField=" + nextSubmitField
                + " formFieldCount=" + step2.size()
                + " baseRoute=" + routeKind(formBase)
                + " actionRoute=" + routeKind(action));

        HttpResponse<String> r2 = postForm(action, step2, formBase, "RESERVATION_VALIDATE");
        Document confirmation = Jsoup.parse(r2.body() == null ? "" : r2.body(), r2.uri().toString());
        log("BOOKING validateResponse finalRoute=" + routeKind(r2.uri()));
        ensureAuthenticated(confirmation, "RESERVATION_VALIDATE");

        if (containsValidationError(confirmation)) {
            log("BOOKING validationResult=serverRejected durationMin=" + duration.minutes);
            throw new BotException("SERVER_VALIDATION_REJECTED", 22);
        }

        BigDecimal price = extractExplicitPrice(confirmation);
        if (price == null) {
            String confirmationText = confirmation.text().toLowerCase(Locale.ROOT);
            boolean priceWord = confirmationText.contains("cena") || confirmationText.contains("price");
            boolean moneyToken = confirmationText.contains("pln") || confirmationText.contains("zł");
            log("BOOKING validationResult=noPrice"
                    + " durationMin=" + duration.minutes
                    + " reservationFormPresent=" + (findReservationForm(confirmation) != null)
                    + " priceWord=" + priceWord
                    + " moneyToken=" + moneyToken
                    + " finalRoute=" + routeKind(URI.create(confirmation.baseUri())));
            throw new BotException("PRICE_NOT_DETECTED", 23);
        }

        log("BOOKING validationResult=accepted durationMin=" + duration.minutes
                + " price=" + price.toPlainString());

        if (price.compareTo(BigDecimal.ZERO) != 0) throw new BotException("PRICE_NOT_ZERO", 23);
        if (price.compareTo(cfg.maxPrice) > 0) throw new BotException("PRICE_ABOVE_LIMIT", 23);

        if (cfg.dryRun) {
            log("BOOKING dryRun=true finalAcceptance=skipped");
            return true;
        }

        Element confirmForm = findReservationForm(confirmation);
        if (confirmForm == null) throw new BotException("CONFIRMATION_FORM_NOT_FOUND", 24);

        Map<String, String> step3 = formFields(confirmForm);
        step3.put("ile_czasu", duration.value);
        step3.putIfAbsent("id_opcji_ceny", "0");
        step3.putIfAbsent("kod_znizkowy_opcji", "");
        step3.putIfAbsent("uwagi", "");
        step3.put("regulamin_klubu", "1");
        step3.putIfAbsent("liczebnosc", "0");
        step3.putIfAbsent("czy_podzial_rozliczenia", "0");
        step3.putIfAbsent("czy_mecz_publiczny", "0");
        step3.put("nowa_rezerwacja_kroki", "3");

        Element acceptSubmit = chooseSubmit(confirmForm, "akcept", "accept", "confirm", "potwierd");
        String acceptSubmitField = "-";
        if (acceptSubmit != null && acceptSubmit.hasAttr("name") && !acceptSubmit.attr("name").isBlank()) {
            acceptSubmitField = safeFieldName(acceptSubmit.attr("name"));
            step3.put(acceptSubmit.attr("name"), acceptSubmit.hasAttr("value") ? acceptSubmit.attr("value") : acceptSubmit.text());
        }

        URI confirmBase = confirmation.baseUri().isBlank() ? action : URI.create(confirmation.baseUri());
        URI action3 = formAction(confirmBase, confirmForm);
        log("BOOKING finalSubmit submitField=" + acceptSubmitField
                + " fieldCount=" + step3.size()
                + " baseRoute=" + routeKind(confirmBase)
                + " actionRoute=" + routeKind(action3));

        HttpResponse<String> r3 = postForm(action3, step3, confirmBase, "RESERVATION_FINAL");
        Document result = Jsoup.parse(r3.body() == null ? "" : r3.body(), r3.uri().toString());

        boolean successMessage = result.text().contains("Właśnie dokonałeś rezerwacji");
        Element detailsLink = result.selectFirst("a[href*=/uslugi/rezerwacje/]");
        boolean reservationLink = detailsLink != null;
        boolean loginFormAfterFinal = findLoginForm(result) != null;
        log("BOOKING finalResult successMessage=" + successMessage
                + " reservationLink=" + reservationLink
                + " loginForm=" + loginFormAfterFinal);

        if (!successMessage && !reservationLink) {
            throw new BotException("FINAL_STATE_UNKNOWN", 25);
        }
        if (detailsLink == null) {
            throw new BotException("FINAL_DETAILS_LINK_MISSING", 25);
        }

        URI verifyUri = r3.uri().resolve(detailsLink.attr("href"));
        Document verified = getDocument(verifyUri, "RESERVATION_VERIFY");
        if (findLoginForm(verified) != null) {
            throw new BotException("FINAL_VERIFY_AUTH_FAILED", 25);
        }

        String verifyText = verified.text();
        String ymdDash = cfg.targetDate.toString();
        String ymdSlash = ymdDash.replace('-', '/');
        String dmyDot = String.format(Locale.ROOT, "%02d.%02d.%04d",
                cfg.targetDate.getDayOfMonth(), cfg.targetDate.getMonthValue(), cfg.targetDate.getYear());
        String startText = slot.start.toLocalTime().toString();
        boolean dateConfirmed = verifyText.contains(ymdDash)
                || verifyText.contains(ymdSlash)
                || verifyText.contains(dmyDot);
        boolean startConfirmed = verifyText.contains(startText);
        boolean detailVerified = dateConfirmed && startConfirmed;

        log("BOOKING verifyResult detailPage=true dateConfirmed=" + dateConfirmed
                + " startConfirmed=" + startConfirmed
                + " verified=" + detailVerified);

        if (!detailVerified) {
            throw new BotException("FINAL_DETAILS_NOT_MATCHED", 25);
        }

        log("SUCCESS verified=true start=" + slot.start.toLocalTime()
                + " durationMin=" + duration.minutes
                + " price=" + price.toPlainString());
        return true;
    }

    private List<DurationChoice> durationChoices(Element form, LocalTime start) {
        Element select = form.selectFirst("select[name=ile_czasu]");
        if (select == null) {
            if (!start.plusMinutes(cfg.preferredDurationMinutes).isAfter(cfg.latestEnd)) {
                return List.of(new DurationChoice("3", cfg.preferredDurationMinutes));
            }
            return List.of();
        }

        List<DurationChoice> choices = new ArrayList<>();
        for (Element opt : select.select("option[value]")) {
            if (opt.hasAttr("disabled")) continue;
            String value = opt.attr("value").trim();
            if (value.isEmpty() || "0".equals(value)) continue;

            Integer minutes = durationMinutes(value, opt.text());
            if (minutes == null || minutes < cfg.minDurationMinutes) continue;
            if (start.plusMinutes(minutes).isAfter(cfg.latestEnd)) continue;
            choices.add(new DurationChoice(value, minutes));
        }

        choices.sort(Comparator
                .comparingInt((DurationChoice d) -> d.minutes == cfg.preferredDurationMinutes ? 0 : 1)
                .thenComparingInt(d -> d.minutes == cfg.preferredDurationMinutes ? 0 : -d.minutes));
        return choices;
    }

    private List<Slot> findCandidateSlots(Document schedule, Facility facility) {
        Pattern p = Pattern.compile("/grafik/(?:rezerwuj-standard|rezerwuj)/"
                + Pattern.quote(facility.objectId) + "/(\\d+)");
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
    }

    private int slotPriority(Slot s) {
        int x = s.start.toLocalTime().toSecondOfDay() / 60;
        int preferred = cfg.preferredStart.toSecondOfDay() / 60;
        int secondary = cfg.secondaryStart.toSecondOfDay() / 60;
        if (x == preferred) return 0;
        if (x == secondary) return 1_000;
        return 2_000 + Math.abs(x - preferred);
    }

    private DurationChoice chooseDuration(Element form, LocalTime start) {
        Element select = form.selectFirst("select[name=ile_czasu]");
        if (select == null) {
            if (!start.plusMinutes(cfg.preferredDurationMinutes).isAfter(cfg.latestEnd)) {
                return new DurationChoice("3", cfg.preferredDurationMinutes);
            }
            return null;
        }

        List<DurationChoice> choices = new ArrayList<>();
        for (Element opt : select.select("option[value]")) {
            if (opt.hasAttr("disabled")) continue;
            String value = opt.attr("value").trim();
            if (value.isEmpty() || "0".equals(value)) continue;

            Integer minutes = durationMinutes(value, opt.text());
            if (minutes == null || minutes < cfg.minDurationMinutes) continue;
            if (start.plusMinutes(minutes).isAfter(cfg.latestEnd)) continue;
            choices.add(new DurationChoice(value, minutes));
        }

        choices.sort(Comparator
                .comparingInt((DurationChoice d) -> d.minutes == cfg.preferredDurationMinutes ? 0 : 1)
                .thenComparingInt(d -> Math.abs(d.minutes - cfg.preferredDurationMinutes))
                .thenComparingInt(d -> -d.minutes));

        return choices.isEmpty() ? null : choices.get(0);
    }

    private Integer durationMinutes(String value, String text) {
        String s = text.toLowerCase(Locale.ROOT).replace(',', '.');

        Matcher h = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:h|godz)").matcher(s);
        if (h.find()) return (int) Math.round(Double.parseDouble(h.group(1)) * 60.0);

        Matcher min = Pattern.compile("(\\d+)\\s*min").matcher(s);
        if (min.find()) return Integer.parseInt(min.group(1));

        try {
            int n = Integer.parseInt(value);
            if (n > 0 && n <= 12) return n * 30;
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private void acceptMandatoryConsents(Document doc, Element form, Map<String, String> data) {
        for (Element cb : form.select("input[type=checkbox][name]")) {
            String hay = (cb.attr("name") + " " + labelText(doc, cb)).toLowerCase(Locale.ROOT);
            if (cb.hasAttr("required")
                    || hay.contains("regulamin")
                    || hay.contains("privacy")
                    || hay.contains("rodo")
                    || hay.contains("dane osob")
                    || hay.contains("przetwarz")) {
                data.put(cb.attr("name"), cb.hasAttr("value") ? cb.attr("value") : "1");
            }
        }
    }

    private static Map<String, String> formFields(Element form) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();

        for (Element input : form.select("input[name]")) {
            if (input.hasAttr("disabled")) continue;
            String type = input.attr("type").toLowerCase(Locale.ROOT);
            if (Set.of("submit", "button", "image", "file", "password").contains(type)) continue;
            if (Set.of("checkbox", "radio").contains(type) && !input.hasAttr("checked")) continue;
            out.put(input.attr("name"), input.attr("value"));
        }

        for (Element area : form.select("textarea[name]")) {
            if (!area.hasAttr("disabled")) out.put(area.attr("name"), area.val());
        }

        for (Element select : form.select("select[name]")) {
            if (select.hasAttr("disabled")) continue;
            Element selected = select.selectFirst("option[selected]");
            if (selected == null) selected = select.selectFirst("option[value]");
            if (selected != null) out.put(select.attr("name"), selected.attr("value"));
        }
        return out;
    }

    private BigDecimal extractExplicitPrice(Document doc) {
        String text = doc.text();
        Pattern[] patterns = {
                Pattern.compile("(?iu)(?:cena|do zapłaty|do zaplaty|price)\\s*[:\\-]?\\s*(\\d+[,.]\\d{2})\\s*(?:PLN|zł|zl)?"),
                Pattern.compile("(?iu)(\\d+[,.]\\d{2})\\s*(?:PLN|zł|zl)")
        };
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) return new BigDecimal(m.group(1).replace(',', '.'));
        }
        return null;
    }

    private boolean containsValidationError(Document doc) {
        String t = doc.text().toLowerCase(Locale.ROOT);
        return t.contains("musisz zaakceptować")
                || t.contains("musisz zaakceptowac")
                || t.contains("termin jest już zajęty")
                || t.contains("termin jest juz zajety")
                || t.contains("brak wolnych")
                || t.contains("nie można dokonać rezerwacji")
                || t.contains("nie mozna dokonac rezerwacji");
    }

    private boolean isAuthenticated(Document doc) {
        if (!doc.select("a[href*=wyloguj]").isEmpty()) return true;
        String t = doc.text().toLowerCase(Locale.ROOT);
        return !t.contains("aby zarezerwować należy się")
                && !t.contains("aby zarezerwowac nalezy sie")
                && findLoginForm(doc) == null;
    }

    private void ensureAuthenticated(Document doc, String stage) {
        boolean ok = isAuthenticated(doc);
        log("AUTH stage=" + stage + " authenticated=" + ok);
        if (!ok) throw new BotException("SESSION_EXPIRED", 13);
    }

    private URI scheduleUri(Facility facility) {
        return resolve(facility.clubPath + "/grafik?data_grafiku=" + enc(cfg.targetDate.toString())
                + "&dyscyplina=" + enc(facility.discipline) + "&strona=0");
    }

    private Document getDocument(URI uri, String stage) throws Exception {
        HttpRequest req = baseRequest(uri).GET().build();
        HttpResponse<String> response = send(req, stage, "GET");
        return Jsoup.parse(response.body() == null ? "" : response.body(), response.uri().toString());
    }

    private HttpResponse<String> postForm(URI uri, Map<String, String> data, URI referer, String stage) throws Exception {
        HttpRequest req = baseRequest(uri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", cfg.origin)
                .header("Referer", referer.toString())
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(data), StandardCharsets.UTF_8))
                .build();
        return send(req, stage, "POST");
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", cfg.userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", cfg.acceptLanguage)
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1");
    }

    private HttpResponse<String> send(HttpRequest req, String stage, String method) {
        long started = System.nanoTime();
        log("HTTP stage=" + stage + " method=" + method + " event=request");

        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("HTTP stage=" + stage + " method=" + method
                    + " event=io_error elapsedMs=" + elapsedMs(started)
                    + " exception=" + e.getClass().getSimpleName());
            throw new BotException(stage + "_IO_ERROR", 40);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("HTTP stage=" + stage + " method=" + method
                    + " event=interrupted elapsedMs=" + elapsedMs(started));
            throw new BotException(stage + "_INTERRUPTED", 40);
        }

        traceResponse(stage, method, r, elapsedMs(started));
        failOnChallenge(stage, r);

        if (r.statusCode() < 200 || r.statusCode() >= 400) {
            throw new BotException(stage + "_HTTP_" + r.statusCode(), 40);
        }
        return r;
    }

    private void traceResponse(String stage, String method, HttpResponse<String> r, long elapsedMs) {
        String body = r.body() == null ? "" : r.body();
        ChallengeSignals signals = challengeSignals(r);

        log("HTTP stage=" + stage
                + " method=" + method
                + " event=response"
                + " status=" + r.statusCode()
                + " elapsedMs=" + elapsedMs
                + " version=" + r.version()
                + " redirects=" + redirectCount(r)
                + " bodyBytes=" + body.getBytes(StandardCharsets.UTF_8).length
                + " bodySha256=" + sha256Prefix(body)
                + " contentType=" + safeToken(header(r, "content-type"))
                + " server=" + safeToken(header(r, "server"))
                + " cfRay=" + safeToken(header(r, "cf-ray"))
                + " cfMitigated=" + safeToken(header(r, "cf-mitigated"))
                + " cfCacheStatus=" + safeToken(header(r, "cf-cache-status"))
                + " challengeSignals=" + signals.summary()
                + " setCookieCount=" + r.headers().allValues("set-cookie").size()
                + " cookieJarCount=" + cookieCount());
    }

    private void failOnChallenge(String stage, HttpResponse<String> r) {
        ChallengeSignals signals = challengeSignals(r);
        boolean challengeStatus = r.statusCode() == 403 || r.statusCode() == 429 || r.statusCode() == 503;

        boolean confirmedChallenge = signals.cfMitigatedChallenge
                || signals.fatalBodyMarker
                || (challengeStatus && signals.cloudflareEvidence);

        if (confirmedChallenge) {
            log("SECURITY stage=" + stage
                    + " cloudflareChallenge=true"
                    + " status=" + r.statusCode()
                    + " signals=" + signals.summary()
                    + " action=stop");
            throw new BotException("CLOUDFLARE_CHALLENGE_" + stage + "_" + r.statusCode(), 50);
        }

        if (signals.genericChallengeResource) {
            log("SECURITY stage=" + stage
                    + " genericCloudflareResource=true"
                    + " status=" + r.statusCode()
                    + " action=continue");
        }

        if (challengeStatus) {
            log("HTTP stage=" + stage
                    + " elevatedStatus=true"
                    + " cloudflareChallenge=false"
                    + " status=" + r.statusCode());
        }
    }

    private ChallengeSignals challengeSignals(HttpResponse<String> r) {
        String low = (r.body() == null ? "" : r.body()).toLowerCase(Locale.ROOT);
        String mitigated = header(r, "cf-mitigated").toLowerCase(Locale.ROOT);

        boolean cfMitigatedChallenge = mitigated.contains("challenge");
        boolean titleJustAMoment = low.contains("<title>just a moment") || low.contains(">just a moment...</title>");
        boolean attentionRequired = low.contains("attention required! | cloudflare");
        boolean enableJsCookies = low.contains("enable javascript and cookies to continue");
        boolean cfChl = low.contains("cf-chl-");
        boolean genericChallengeResource = low.contains("/cdn-cgi/challenge-platform/")
                || low.contains("challenge-platform");

        boolean fatalBodyMarker = titleJustAMoment || attentionRequired || enableJsCookies || cfChl;

        boolean cloudflareEvidence = cfMitigatedChallenge
                || !"-".equals(header(r, "cf-ray"))
                || header(r, "server").toLowerCase(Locale.ROOT).contains("cloudflare")
                || genericChallengeResource;

        return new ChallengeSignals(
                cfMitigatedChallenge,
                fatalBodyMarker,
                genericChallengeResource,
                cloudflareEvidence,
                titleJustAMoment,
                attentionRequired,
                enableJsCookies,
                cfChl
        );
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse("-");
    }

    private int cookieCount() {
        return cookies.getCookieStore().getCookies().size();
    }

    private static int redirectCount(HttpResponse<?> response) {
        int count = 0;
        Optional<? extends HttpResponse<?>> prev = response.previousResponse();
        while (prev.isPresent()) {
            count++;
            prev = prev.get().previousResponse();
        }
        return count;
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static String sha256Prefix(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(6, digest.length); i++) {
                out.append(String.format("%02x", digest[i]));
            }
            return out.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "-";
        String cleaned = value.replaceAll("[\\r\\n\\t ]+", "_");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String safeFieldName(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.replaceAll("[^A-Za-z0-9_\\-\\[\\]]", "?");
    }

    private static Document parse(String html) {
        return Jsoup.parse(html == null ? "" : html);
    }

    private static Element findLoginForm(Document doc) {
        for (Element f : doc.select("form")) {
            if (f.selectFirst("input[type=password][name]") != null) return f;
        }
        return null;
    }

    private static Element findReservationForm(Document doc) {
        Element fallback = null;
        for (Element f : doc.select("form")) {
            if (f.selectFirst("[name=ile_czasu]") != null) return f;
            if (f.selectFirst("[name=nowa_rezerwacja_kroki]") != null) fallback = f;
        }
        return fallback;
    }

    private static boolean looksLikeSearch(Element e) {
        String x = (e.attr("name") + " " + e.id() + " " + e.attr("placeholder")).toLowerCase(Locale.ROOT);
        return x.contains("search") || x.contains("szuk");
    }

    private static String labelText(Document doc, Element input) {
        String id = input.id();
        if (!id.isBlank()) {
            for (Element label : doc.select("label[for]")) {
                if (id.equals(label.attr("for"))) return label.text();
            }
        }
        Element parent = input.closest("label");
        return parent == null ? "" : parent.text();
    }

    private URI formAction(URI current, Element form) {
        String action = form.attr("action").trim();
        return action.isEmpty() ? current : resolveAgainst(current, action);
    }

    private URI resolve(String pathOrUri) {
        return resolveAgainst(cfg.baseUri, pathOrUri);
    }

    private static URI resolveAgainst(URI base, String pathOrUri) {
        URI u = URI.create(pathOrUri);
        return u.isAbsolute() ? u : base.resolve(u);
    }

    private static String encodeForm(Map<String, String> data) {
        StringJoiner j = new StringJoiner("&");
        data.forEach((k, v) -> j.add(enc(k) + "=" + enc(v == null ? "" : v)));
        return j.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void log(String msg) {
        System.out.println("[" + ZonedDateTime.now(ZONE).format(LOG_TIME) + "] trace=" + traceId + " " + msg);
    }

    private record Facility(String label, String clubPath, String objectId, String discipline) {}
    private record OpenSignal(boolean opened, boolean dateMarker, int targetRouteCount) {}
    private record Slot(ZonedDateTime start, URI uri) {}
    private record DurationChoice(String value, int minutes) {}

    private record ChallengeSignals(
            boolean cfMitigatedChallenge,
            boolean fatalBodyMarker,
            boolean genericChallengeResource,
            boolean cloudflareEvidence,
            boolean titleJustAMoment,
            boolean attentionRequired,
            boolean enableJsCookies,
            boolean cfChl
    ) {
        String summary() {
            List<String> parts = new ArrayList<>();
            if (cfMitigatedChallenge) parts.add("cf_mitigated");
            if (titleJustAMoment) parts.add("title_just_a_moment");
            if (attentionRequired) parts.add("attention_required");
            if (enableJsCookies) parts.add("enable_js_cookies");
            if (cfChl) parts.add("cf_chl");
            if (genericChallengeResource) parts.add("challenge_resource");
            if (parts.isEmpty() && cloudflareEvidence) parts.add("cf_edge_only");
            return parts.isEmpty() ? "none" : String.join(",", parts);
        }
    }

    private static final class BotException extends RuntimeException {
        final String code;
        final int exitCode;

        BotException(String code, int exitCode) {
            super(code);
            this.code = code;
            this.exitCode = exitCode;
        }
    }

    private static final class Config {
        final URI baseUri;
        final String origin;
        final String login;
        final String password;
        final String clubPath;
        final String loginPath;
        final String verifyPath;
        final String objectId;
        final String discipline;
        final LocalDate targetDate;
        final LocalTime minStart;
        final LocalTime preferredStart;
        final LocalTime secondaryStart;
        final LocalTime latestEnd;
        final int preferredDurationMinutes;
        final int minDurationMinutes;
        final BigDecimal maxPrice;
        final boolean dryRun;
        final String userAgent;
        final String acceptLanguage;

        private Config(
                URI baseUri,
                String login,
                String password,
                String clubPath,
                String loginPath,
                String verifyPath,
                String objectId,
                String discipline,
                LocalDate targetDate,
                LocalTime minStart,
                LocalTime preferredStart,
                LocalTime secondaryStart,
                LocalTime latestEnd,
                int preferredDurationMinutes,
                int minDurationMinutes,
                BigDecimal maxPrice,
                boolean dryRun,
                String userAgent,
                String acceptLanguage
        ) {
            this.baseUri = baseUri;
            this.origin = baseUri.getScheme() + "://" + baseUri.getAuthority();
            this.login = login;
            this.password = password;
            this.clubPath = normalizePath(clubPath);
            this.loginPath = normalizePath(loginPath);
            this.verifyPath = normalizePath(verifyPath);
            this.objectId = objectId;
            this.discipline = discipline;
            this.targetDate = targetDate;
            this.minStart = minStart;
            this.preferredStart = preferredStart;
            this.secondaryStart = secondaryStart;
            this.latestEnd = latestEnd;
            this.preferredDurationMinutes = preferredDurationMinutes;
            this.minDurationMinutes = minDurationMinutes;
            this.maxPrice = maxPrice;
            this.dryRun = dryRun;
            this.userAgent = userAgent;
            this.acceptLanguage = acceptLanguage;
        }

        static Config fromEnvironment() {
            URI base = URI.create(required("BOOKING_BASE_URL"));
            if (base.getScheme() == null || base.getHost() == null) {
                throw new BotException("INVALID_BASE_URL", 2);
            }

            String club = required("BOOKING_CLUB_PATH");
            String loginPath = optional("BOOKING_LOGIN_PATH", normalizePath(club) + "/logowanie");
            String verifyPath = optional("BOOKING_VERIFY_PATH", normalizePath(club));

            return new Config(
                    base,
                    required("BOOKING_LOGIN"),
                    required("BOOKING_PASSWORD"),
                    club,
                    loginPath,
                    verifyPath,
                    required("BOOKING_OBJECT_ID"),
                    required("BOOKING_DISCIPLINE"),
                    resolveTargetDate(),
                    LocalTime.parse(optional("BOOKING_MIN_START", "18:00")),
                    LocalTime.parse(optional("BOOKING_PREFERRED_START", "19:30")),
                    LocalTime.parse(optional("BOOKING_SECONDARY_START", "18:00")),
                    LocalTime.parse(optional("BOOKING_LATEST_END", "21:30")),
                    Integer.parseInt(optional("BOOKING_PREFERRED_DURATION_MIN", "90")),
                    Integer.parseInt(optional("BOOKING_MIN_DURATION_MIN", "60")),
                    new BigDecimal(optional("BOOKING_MAX_PRICE", "0.00")),
                    Boolean.parseBoolean(optional("BOOKING_DRY_RUN", "false")),
                    optional("BOOKING_USER_AGENT", "Mozilla/5.0 (X11; Linux x86_64; rv:156.0) Gecko/20100101 Firefox/156.0"),
                    optional("BOOKING_ACCEPT_LANGUAGE", "en-US,en;q=0.9")
            );
        }

        private static LocalDate resolveTargetDate() {
            String exactDate = optional("BOOKING_TARGET_DATE", "").trim();
            if (!exactDate.isBlank()) return LocalDate.parse(exactDate);

            int offsetDays = Integer.parseInt(optional("BOOKING_TARGET_OFFSET_DAYS", "4"));
            return ZonedDateTime.now(ZONE).toLocalDate().plusDays(offsetDays);
        }

        private static String required(String name) {
            String v = System.getenv(name);
            if (v == null || v.isBlank()) throw new BotException("MISSING_SECRET_" + name, 2);
            return v.trim();
        }

        private static String optional(String name, String def) {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? def : v.trim();
        }

        private static String normalizePath(String p) {
            String x = p.trim();
            if (!x.startsWith("/")) x = "/" + x;
            while (x.endsWith("/") && x.length() > 1) x = x.substring(0, x.length() - 1);
            return x;
        }
    }
}
