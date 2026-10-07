from pathlib import Path

path = Path("src/main/java/io/github/bookingbot/BookingBot.java")
src = path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if old not in source:
        raise SystemExit(f"Expected {label} block not found; refusing to patch unknown source")
    return source.replace(old, new, 1)


# A final POST may already have created a reservation. Any ambiguity after that
# point must stop the whole run instead of trying another slot/facility.
attempt_old = '''                if (e.code.startsWith("CLOUDFLARE_")) throw e;
                log("FACILITY label=" + facility.label'''
attempt_new = '''                if (e.code.startsWith("CLOUDFLARE_") || e.code.startsWith("FINAL_")) throw e;
                log("FACILITY label=" + facility.label'''
src = replace_once(src, attempt_old, attempt_new, "final-state no-retry guard")

final_old = '''        HttpResponse<String> r3 = postForm(action3, step3, confirmBase, "RESERVATION_FINAL");
        Document result = Jsoup.parse(r3.body() == null ? "" : r3.body(), r3.uri().toString());
        ensureAuthenticated(result, "RESERVATION_FINAL");

        boolean successMessage = result.text().contains("Właśnie dokonałeś rezerwacji");
        boolean reservationLink = !result.select("a[href*=/uslugi/rezerwacje/]").isEmpty();
        log("BOOKING finalResult successMessage=" + successMessage + " reservationLink=" + reservationLink);

        if (!successMessage && !reservationLink) {
            throw new BotException("FINAL_SUCCESS_NOT_CONFIRMED", 25);
        }

        log("SUCCESS start=" + slot.start.toLocalTime() + " durationMin=" + duration.minutes);
        return true;'''

final_new = '''        HttpResponse<String> r3 = postForm(action3, step3, confirmBase, "RESERVATION_FINAL");
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
        return true;'''

src = replace_once(src, final_old, final_new, "final reservation verification")

# Live booking is allowed only at exactly 0.00, not merely <= configured cap.
price_old = '''        if (price.compareTo(cfg.maxPrice) > 0) throw new BotException("PRICE_ABOVE_LIMIT", 23);'''
price_new = '''        if (price.compareTo(BigDecimal.ZERO) != 0) throw new BotException("PRICE_NOT_ZERO", 23);
        if (price.compareTo(cfg.maxPrice) > 0) throw new BotException("PRICE_ABOVE_LIMIT", 23);'''
src = replace_once(src, price_old, price_new, "exact-zero price guard")

path.write_text(src, encoding="utf-8")
print("Live final verification patch applied")
