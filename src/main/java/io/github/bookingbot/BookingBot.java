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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BookingBot {
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z");

    private final Config cfg;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

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
        log("Start. Target date=" + cfg.targetDate + ", dryRun=" + cfg.dryRun);
        loginFresh();
        Document schedule = getDocument(scheduleUri(), "SCHEDULE");
        ensureAuthenticated(schedule);

        List<Slot> slots = findCandidateSlots(schedule);
        if (slots.isEmpty()) throw new BotException("NO_CANDIDATE_SLOTS", 20);

        log("Candidate slots: " + slots.stream().map(s -> s.start.toLocalTime().toString()).toList());
        for (Slot slot : slots) {
            try {
                if (trySlot(slot)) return;
            } catch (BotException e) {
                if ("SESSION_EXPIRED".equals(e.code)) {
                    log("Session expired; re-login.");
                    loginFresh();
                    if (trySlot(slot)) return;
                }
                if (e.code.startsWith("CLOUDFLARE_")) throw e;
                log("Slot " + slot.start.toLocalTime() + " rejected: " + e.code);
            }
        }
        throw new BotException("NO_RESERVATION_CREATED", 30);
    }

    private void loginFresh() throws Exception {
        cookies.getCookieStore().removeAll();
        URI loginUri = resolve(cfg.loginPath);
        Document loginPage = getDocument(loginUri, "LOGIN_GET");
        Element form = findLoginForm(loginPage);
        if (form == null) throw new BotException("LOGIN_FORM_NOT_FOUND", 11);

        Map<String, String> data = formFields(form);
        Element pass = form.selectFirst("input[type=password][name]");
        if (pass == null) throw new BotException("PASSWORD_FIELD_NOT_FOUND", 11);

        Element login = form.selectFirst("input[type=email][name]");
        if (login == null) {
            login = form.select("input[type=text][name]").stream()
                    .filter(el -> !looksLikeSearch(el)).findFirst().orElse(null);
        }
        if (login == null) throw new BotException("LOGIN_FIELD_NOT_FOUND", 11);

        data.put(login.attr("name"), cfg.login);
        data.put(pass.attr("name"), cfg.password);

        for (Element cb : form.select("input[type=checkbox][name]")) {
            String hay = (cb.attr("name") + " " + labelText(loginPage, cb)).toLowerCase(Locale.ROOT);
            if (hay.contains("remember") || hay.contains("pamiętaj") || hay.contains("pamietaj")) {
                data.put(cb.attr("name"), cb.hasAttr("value") ? cb.attr("value") : "1");
            }
        }

        URI action = formAction(loginUri, form);
        postForm(action, data, loginUri, "LOGIN_POST");
        Document check = getDocument(resolve(cfg.verifyPath), "LOGIN_VERIFY");
        if (!isAuthenticated(check)) throw new BotException("LOGIN_FAILED", 12);
        log("Fresh HTTP login successful.");
    }

    private boolean trySlot(Slot slot) throws Exception {
        log("Trying slot " + slot.start.toLocalTime());
        Document first = getDocument(slot.uri, "SLOT_GET");
        ensureAuthenticated(first);

        Element form = findReservationForm(first);
        if (form == null) throw new BotException("RESERVATION_FORM_NOT_FOUND", 21);

        DurationChoice duration = chooseDuration(form, slot.start.toLocalTime());
        if (duration == null) throw new BotException("NO_ACCEPTABLE_DURATION", 21);

        Map<String, String> step2 = formFields(form);
        step2.put("ile_czasu", duration.value);
        acceptMandatoryConsents(first, form, step2);
        step2.put("nowa_rezerwacja_kroki", "2");

        URI action = formAction(slot.uri, form);
        HttpResponse<String> r2 = postForm(action, step2, slot.uri, "RESERVATION_VALIDATE");
        Document confirmation = parse(r2.body());
        ensureAuthenticated(confirmation);
        if (containsValidationError(confirmation)) throw new BotException("SERVER_VALIDATION_REJECTED", 22);

        BigDecimal price = extractExplicitPrice(confirmation);
        if (price == null) throw new BotException("PRICE_NOT_DETECTED", 23);
        log("Validated duration=" + duration.minutes + "min, price=" + price.toPlainString());
        if (price.compareTo(cfg.maxPrice) > 0) throw new BotException("PRICE_ABOVE_LIMIT", 23);

        if (cfg.dryRun) {
            log("DRY RUN: final acceptance skipped.");
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

        URI action3 = formAction(action, confirmForm);
        HttpResponse<String> r3 = postForm(action3, step3, action, "RESERVATION_FINAL");
        Document result = parse(r3.body());
        ensureAuthenticated(result);

        boolean success = result.text().contains("Właśnie dokonałeś rezerwacji")
                || !result.select("a[href*=/uslugi/rezerwacje/]").isEmpty();
        if (!success) throw new BotException("FINAL_SUCCESS_NOT_CONFIRMED", 25);

        log("SUCCESS: reservation created for " + slot.start.toLocalTime() + ", duration=" + duration.minutes + "min.");
        return true;
    }

    private List<Slot> findCandidateSlots(Document schedule) {
        Pattern p = Pattern.compile("/grafik/(?:rezerwuj-standard|rezerwuj)/" + Pattern.quote(cfg.objectId) + "/(\\d+)");
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
        if (x == secondary) return 1000;
        return 2000 + Math.abs(x - preferred);
    }

    private DurationChoice chooseDuration(Element form, LocalTime start) {
        Element select = form.selectFirst("select[name=ile_czasu]");
        if (select == null) {
            return !start.plusMinutes(cfg.preferredDurationMinutes).isAfter(cfg.latestEnd)
                    ? new DurationChoice("3", cfg.preferredDurationMinutes) : null;
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
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private void acceptMandatoryConsents(Document doc, Element form, Map<String, String> data) {
        for (Element cb : form.select("input[type=checkbox][name]")) {
            String hay = (cb.attr("name") + " " + labelText(doc, cb)).toLowerCase(Locale.ROOT);
            if (cb.hasAttr("required") || hay.contains("regulamin") || hay.contains("privacy")
                    || hay.contains("rodo") || hay.contains("dane osob") || hay.contains("przetwarz")) {
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
        return t.contains("musisz zaakceptować") || t.contains("musisz zaakceptowac")
                || t.contains("termin jest już zajęty") || t.contains("termin jest juz zajety")
                || t.contains("brak wolnych") || t.contains("nie można dokonać rezerwacji")
                || t.contains("nie mozna dokonac rezerwacji");
    }

    private boolean isAuthenticated(Document doc) {
        if (!doc.select("a[href*=wyloguj]").isEmpty()) return true;
        String t = doc.text().toLowerCase(Locale.ROOT);
        return !t.contains("aby zarezerwować należy się")
                && !t.contains("aby zarezerwowac nalezy sie")
                && findLoginForm(doc) == null;
    }

    private void ensureAuthenticated(Document doc) {
        if (!isAuthenticated(doc)) throw new BotException("SESSION_EXPIRED", 13);
    }

    private URI scheduleUri() {
        return resolve(cfg.clubPath + "/grafik?data_grafiku=" + enc(cfg.targetDate.toString())
                + "&dyscyplina=" + enc(cfg.discipline) + "&strona=0");
    }

    private Document getDocument(URI uri, String stage) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", cfg.userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl,en;q=0.8")
                .GET().build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BotException(stage + "_IO_ERROR", 40);
        }
        failOnChallenge(r.body(), r.statusCode());
        if (r.statusCode() < 200 || r.statusCode() >= 400) {
            throw new BotException(stage + "_HTTP_" + r.statusCode(), 40);
        }
        return parse(r.body());
    }

    private HttpResponse<String> postForm(URI uri, Map<String, String> data, URI referer, String stage) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", cfg.userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", cfg.origin)
                .header("Referer", referer.toString())
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(data), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BotException(stage + "_IO_ERROR", 40);
        }
        failOnChallenge(r.body(), r.statusCode());
        if (r.statusCode() < 200 || r.statusCode() >= 400) {
            throw new BotException(stage + "_HTTP_" + r.statusCode(), 40);
        }
        return r;
    }

    private void failOnChallenge(String body, int status) {
        String low = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 403 || status == 429 || status == 503 || low.contains("cf-chl-")
                || low.contains("challenge-platform") || low.contains("just a moment")
                || low.contains("attention required! | cloudflare")) {
            throw new BotException("CLOUDFLARE_CHALLENGE", 50);
        }
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

    private static void log(String msg) {
        System.out.println("[" + ZonedDateTime.now(ZONE).format(LOG_TIME) + "] " + msg);
    }

    private record Slot(ZonedDateTime start, URI uri) {}
    private record DurationChoice(String value, int minutes) {}

    private static final class BotException extends RuntimeException {
        final String code;
        final int exitCode;
        BotException(String code, int exitCode) {
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

        private Config(URI baseUri, String login, String password, String clubPath, String loginPath,
                       String verifyPath, String objectId, String discipline, LocalDate targetDate,
                       LocalTime minStart, LocalTime preferredStart, LocalTime secondaryStart,
                       LocalTime latestEnd, int preferredDurationMinutes, int minDurationMinutes,
                       BigDecimal maxPrice, boolean dryRun, String userAgent) {
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
        }

        static Config fromEnvironment() {
            URI base = URI.create(required("BOOKING_BASE_URL"));
            if (base.getScheme() == null || base.getHost() == null) throw new BotException("INVALID_BASE_URL", 2);

            String club = required("BOOKING_CLUB_PATH");
            String loginPath = optional("BOOKING_LOGIN_PATH", normalizePath(club) + "/logowanie");
            String verifyPath = optional("BOOKING_VERIFY_PATH", normalizePath(club) + "/profil");

            return new Config(
                    base,
                    required("BOOKING_LOGIN"),
                    required("BOOKING_PASSWORD"),
                    club,
                    loginPath,
                    verifyPath,
                    required("BOOKING_OBJECT_ID"),
                    required("BOOKING_DISCIPLINE"),
                    LocalDate.parse(required("BOOKING_TARGET_DATE")),
                    LocalTime.parse(optional("BOOKING_MIN_START", "18:00")),
                    LocalTime.parse(optional("BOOKING_PREFERRED_START", "19:30")),
                    LocalTime.parse(optional("BOOKING_SECONDARY_START", "18:00")),
                    LocalTime.parse(optional("BOOKING_LATEST_END", "21:30")),
                    Integer.parseInt(optional("BOOKING_PREFERRED_DURATION_MIN", "90")),
                    Integer.parseInt(optional("BOOKING_MIN_DURATION_MIN", "60")),
                    new BigDecimal(optional("BOOKING_MAX_PRICE", "0.00")),
                    Boolean.parseBoolean(optional("BOOKING_DRY_RUN", "false")),
                    optional("BOOKING_USER_AGENT", "Mozilla/5.0 (X11; Linux x86_64; rv:156.0) Gecko/20100101 Firefox/156.0")
            );
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
