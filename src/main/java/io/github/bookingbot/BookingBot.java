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

        log("AUTH loginForm=true loginField=" + safeFieldName(login.attr("name"))
                + " passwordField=" + safeFieldName(pass.attr("name"))
                + " formFieldCount=" + data.size()
                + " rememberFieldCount=" + rememberFields);

        URI action = formAction(loginUri, form);
        postForm(action, data, loginUri, "LOGIN_POST");

        Document check = getDocument(resolve(cfg.verifyPath), "LOGIN_VERIFY");
        boolean authenticated = isAuthenticated(check);
        log("AUTH verify authenticated=" + authenticated + " cookieCount=" + cookieCount());
        if (!authenticated) throw new BotException("LOGIN_FAILED", 12);
        log("AUTH freshLogin=success");
    }

    private boolean trySlot(Slot slot) throws Exception {
        log("SLOT try start=" + slot.start.toLocalTime());

        Document first = getDocument(slot.uri, "SLOT_GET");
        ensureAuthenticated(first, "SLOT_GET");

        Element form = findReservationForm(first);
        if (form == null) throw new BotException("RESERVATION_FORM_NOT_FOUND", 21);

        DurationChoice duration = chooseDuration(form, slot.start.toLocalTime());
        if (duration == null) throw new BotException("NO_ACCEPTABLE_DURATION", 21);

        Map<String, String> step2 = formFields(form);
        step2.put("ile_czasu", duration.value);
        acceptMandatoryConsents(first, form, step2);
        step2.put("nowa_rezerwacja_kroki", "2");

        log("BOOKING validate start=" + slot.start.toLocalTime()
                + " durationMin=" + duration.minutes
                + " formFieldCount=" + step2.size());

        URI action = formAction(slot.uri, form);
        HttpResponse<String> r2 = postForm(action, step2, slot.uri, "RESERVATION_VALIDATE");
        Document confirmation = parse(r2.body());
        ensureAuthenticated(confirmation, "RESERVATION_VALIDATE");

        if (containsValidationError(confirmation)) {
            log("BOOKING validationResult=serverRejected");
            throw new BotException("SERVER_VALIDATION_REJECTED", 22);
        }

        BigDecimal price = extractExplicitPrice(confirmation);
        if (price == null) throw new BotException("PRICE_NOT_DETECTED", 23);

        log("BOOKING validationResult=accepted durationMin=" + duration.minutes
                + " price=" + price.toPlainString());

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

        log("BOOKING finalSubmit fieldCount=" + step3.size());

        URI action3 = formAction(action, confirmForm);
        HttpResponse<String> r3 = postForm(action3, step3, action, "RESERVATION_FINAL");
        Document result = parse(r3.body());
        ensureAuthenticated(result, "RESERVATION_FINAL");

        boolean successMessage = result.text().contains("Właśnie dokonałeś rezerwacji");
        boolean reservationLink = !result.select("a[href*=/uslugi/rezerwacje/]").isEmpty();
        boolean success = successMessage || reservationLink;

        log("BOOKING finalResult successMessage=" + successMessage
                + " reservationLink=" + reservationLink);

        if (!success) throw new BotException("FINAL_SUCCESS_NOT_CONFIRMED", 25);

        log("SUCCESS start=" + slot.start.toLocalTime() + " durationMin=" + duration.minutes);
        return true;
    }

    private List<Slot> findCandidateSlots(Document schedule) {
        Pattern p = Pattern.compile("/grafik/(?:rezerwuj-standard|rezerwuj)/"
                + Pattern.quote(cfg.objectId) + "/(\\d+)");
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
                    ? new DurationChoice("3", cfg.preferredDurationMinutes)
                    : null;
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

    private URI scheduleUri() {
        return resolve(cfg.clubPath + "/grafik?data_grafiku=" + enc(cfg.targetDate.toString())
                + "&dyscyplina=" + enc(cfg.discipline) + "&strona=0");
    }

    private Document getDocument(URI uri, String stage) throws Exception {
        HttpRequest req = baseRequest(uri).GET().build();
        HttpResponse<String> r = send(req, stage, "GET");
        return parse(r.body());
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

    private HttpResponse<String> send(HttpRequest req, String stage, String method) throws Exception {
        long started = System.nanoTime();
        log("HTTP stage=" + stage + " method=" + method + " event=request");

        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            long ms = elapsedMs(started);
            log("HTTP stage=" + stage + " method=" + method
                    + " event=io_error elapsedMs=" + ms
                    + " exception=" + e.getClass().getSimpleName());
            throw new BotException(stage + "_IO_ERROR", 40);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long ms = elapsedMs(started);
            log("HTTP stage=" + stage + " method=" + method
                    + " event=interrupted elapsedMs=" + ms);
            throw new BotException(stage + "_INTERRUPTED", 40);
        }

        long ms = elapsedMs(started);
        traceResponse(stage, method, r, ms);
        failOnChallenge(stage, r);

        if (r.statusCode() < 200 || r.statusCode() >= 400) {
            throw new BotException(stage + "_HTTP_" + r.statusCode(), 40);
        }

        return r;
    }

    private void traceResponse(String stage, String method, HttpResponse<String> r, long elapsedMs) {
        String server = header(r, "server");
        String cfRay = header(r, "cf-ray");
        String cfMitigated = header(r, "cf-mitigated");
        String cfCache = header(r, "cf-cache-status");
        String contentType = header(r, "content-type");
        int redirects = redirectCount(r);
        String body = r.body() == null ? "" : r.body();
        int setCookieCount = r.headers().allValues("set-cookie").size();

        log("HTTP stage=" + stage
                + " method=" + method
                + " event=response"
                + " status=" + r.statusCode()
                + " elapsedMs=" + elapsedMs
                + " version=" + r.version()
                + " redirects=" + redirects
                + " bodyBytes=" + body.getBytes(StandardCharsets.UTF_8).length
                + " bodySha256=" + sha256Prefix(body)
                + " contentType=" + safeToken(contentType)
                + " server=" + safeToken(server)
                + " cfRay=" + safeToken(cfRay)
                + " cfMitigated=" + safeToken(cfMitigated)
                + " cfCacheStatus=" + safeToken(cfCache)
                + " setCookieCount=" + setCookieCount
                + " cookieJarCount=" + cookieCount());
    }

    private void failOnChallenge(String stage, HttpResponse<String> r) {
        String body = r.body() == null ? "" : r.body();
        String low = body.toLowerCase(Locale.ROOT);

        boolean bodyMarker = low.contains("cf-chl-")
                || low.contains("challenge-platform")
                || low.contains("just a moment")
                || low.contains("attention required! | cloudflare")
                || low.contains("enable javascript and cookies to continue");

        boolean cfHeader = header(r, "server").toLowerCase(Locale.ROOT).contains("cloudflare")
                || !header(r, "cf-ray").equals("-")
                || !header(r, "cf-mitigated").equals("-");

        boolean challengeStatus = r.statusCode() == 403
                || r.statusCode() == 429
                || r.statusCode() == 503;

        boolean challenge = bodyMarker || (cfHeader && challengeStatus);

        if (challenge) {
            log("SECURITY stage=" + stage
                    + " cloudflareChallenge=true"
                    + " status=" + r.statusCode()
                    + " cfHeader=" + cfHeader
                    + " bodyMarker=" + bodyMarker
                    + " action=stop");
            throw new BotException("CLOUDFLARE_CHALLENGE_" + stage + "_" + r.statusCode(), 50);
        }

        if (challengeStatus) {
            log("HTTP stage=" + stage
                    + " elevatedStatus=true"
                    + " cloudflareEvidence=false"
                    + " status=" + r.statusCode());
        }
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
        System.out.println("[" + ZonedDateTime.now(ZONE).format(LOG_TIME) + "]"
                + " trace=" + traceId + " " + msg);
    }

    private record Slot(ZonedDateTime start, URI uri) {}
    private record DurationChoice(String value, int minutes) {}

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

        private Config(URI baseUri, String login, String password, String clubPath, String loginPath,
                       String verifyPath, String objectId, String discipline, LocalDate targetDate,
                       LocalTime minStart, LocalTime preferredStart, LocalTime secondaryStart,
                       LocalTime latestEnd, int preferredDurationMinutes, int minDurationMinutes,
                       BigDecimal maxPrice, boolean dryRun, String userAgent, String acceptLanguage) {
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
                    optional("BOOKING_USER_AGENT",
                            "Mozilla/5.0 (X11; Linux x86_64; rv:156.0) Gecko/20100101 Firefox/156.0"),
                    optional("BOOKING_ACCEPT_LANGUAGE", "en-US,en;q=0.9")
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
