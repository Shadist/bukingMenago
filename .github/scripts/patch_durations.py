from pathlib import Path

path = Path("src/main/java/io/github/bookingbot/BookingBot.java")
src = path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if old not in source:
        raise SystemExit(f"Expected {label} block not found; refusing to patch unknown source")
    return source.replace(old, new, 1)


start = src.index("    private boolean trySlot(Slot slot) throws Exception {")
end = src.index("    private List<Slot> findCandidateSlots", start)
old_block = src[start:end]

new_block = '''    private boolean trySlot(Slot slot) throws Exception {
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
        ensureAuthenticated(result, "RESERVATION_FINAL");

        boolean successMessage = result.text().contains("Właśnie dokonałeś rezerwacji");
        boolean reservationLink = !result.select("a[href*=/uslugi/rezerwacje/]").isEmpty();
        log("BOOKING finalResult successMessage=" + successMessage + " reservationLink=" + reservationLink);

        if (!successMessage && !reservationLink) {
            throw new BotException("FINAL_SUCCESS_NOT_CONFIRMED", 25);
        }

        log("SUCCESS start=" + slot.start.toLocalTime() + " durationMin=" + duration.minutes);
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

'''

src = src[:start] + new_block + src[end:]
path.write_text(src, encoding="utf-8")
print("Duration retry patch applied")
