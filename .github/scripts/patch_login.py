from pathlib import Path

path = Path("src/main/java/io/github/bookingbot/BookingBot.java")
src = path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if old not in source:
        raise SystemExit(f"Expected {label} block not found; refusing to patch unknown source")
    return source.replace(old, new, 1)


login_old = '''        log("AUTH loginForm=true loginField=" + safeFieldName(login.attr("name"))
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

    private boolean trySlot(Slot slot) throws Exception {'''

login_new = '''        Element submit = chooseSubmit(form, "zalog", "login", "sign in", "signin");
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

    private boolean trySlot(Slot slot) throws Exception {'''

src = replace_once(src, login_old, login_new, "login")

get_doc_old = '''    private Document getDocument(URI uri, String stage) throws Exception {
        HttpRequest req = baseRequest(uri).GET().build();
        return parse(send(req, stage, "GET").body());
    }'''

get_doc_new = '''    private Document getDocument(URI uri, String stage) throws Exception {
        HttpRequest req = baseRequest(uri).GET().build();
        HttpResponse<String> response = send(req, stage, "GET");
        return Jsoup.parse(response.body() == null ? "" : response.body(), response.uri().toString());
    }'''

src = replace_once(src, get_doc_old, get_doc_new, "GET final URI")

step2_old = '''        Map<String, String> step2 = formFields(form);
        step2.put("ile_czasu", duration.value);
        acceptMandatoryConsents(first, form, step2);
        step2.put("nowa_rezerwacja_kroki", "2");

        log("BOOKING validate start=" + slot.start.toLocalTime()
                + " durationMin=" + duration.minutes
                + " formFieldCount=" + step2.size());

        URI action = formAction(slot.uri, form);
        HttpResponse<String> r2 = postForm(action, step2, slot.uri, "RESERVATION_VALIDATE");
        Document confirmation = parse(r2.body());'''

step2_new = '''        Map<String, String> step2 = formFields(form);
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
        log("BOOKING validateResponse finalRoute=" + routeKind(r2.uri()));'''

src = replace_once(src, step2_old, step2_new, "reservation validation")

price_old = '''        BigDecimal price = extractExplicitPrice(confirmation);
        if (price == null) throw new BotException("PRICE_NOT_DETECTED", 23);

        log("BOOKING validationResult=accepted durationMin=" + duration.minutes
                + " price=" + price.toPlainString());'''

price_new = '''        BigDecimal price = extractExplicitPrice(confirmation);
        if (price == null) {
            String confirmationText = confirmation.text().toLowerCase(Locale.ROOT);
            boolean priceWord = confirmationText.contains("cena") || confirmationText.contains("price");
            boolean moneyToken = Pattern.compile("(?iu)\\d+[,.]\\d{2}\\s*(?:PLN|zł|zl)")
                    .matcher(confirmation.text()).find();
            log("BOOKING validationResult=noPrice"
                    + " reservationFormPresent=" + (findReservationForm(confirmation) != null)
                    + " priceWord=" + priceWord
                    + " moneyToken=" + moneyToken
                    + " finalRoute=" + routeKind(URI.create(confirmation.baseUri())));
            throw new BotException("PRICE_NOT_DETECTED", 23);
        }

        log("BOOKING validationResult=accepted durationMin=" + duration.minutes
                + " price=" + price.toPlainString());'''

src = replace_once(src, price_old, price_new, "price diagnostics")

step3_old = '''        step3.putIfAbsent("czy_podzial_rozliczenia", "0");
        step3.putIfAbsent("czy_mecz_publiczny", "0");
        step3.put("nowa_rezerwacja_kroki", "3");

        log("BOOKING finalSubmit fieldCount=" + step3.size());

        URI action3 = formAction(action, confirmForm);
        HttpResponse<String> r3 = postForm(action3, step3, action, "RESERVATION_FINAL");
        Document result = parse(r3.body());'''

step3_new = '''        step3.putIfAbsent("czy_podzial_rozliczenia", "0");
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
        Document result = Jsoup.parse(r3.body() == null ? "" : r3.body(), r3.uri().toString());'''

src = replace_once(src, step3_old, step3_new, "final reservation")

path.write_text(src, encoding="utf-8")
print("Booking redirect/base-URI patch applied")
