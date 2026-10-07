from pathlib import Path

path = Path("src/main/java/io/github/bookingbot/BookingBot.java")
src = path.read_text(encoding="utf-8")

old = '''        log("AUTH loginForm=true loginField=" + safeFieldName(login.attr("name"))
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

new = '''        Element submit = chooseLoginSubmit(form);
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
        Document afterPost = parse(loginResponse.body());
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

    private static Element chooseLoginSubmit(Element form) {
        for (Element el : form.select("input[type=submit],button[type=submit],button:not([type])")) {
            String hay = (el.attr("name") + " " + el.attr("value") + " " + el.text()).toLowerCase(Locale.ROOT);
            if (hay.contains("zalog") || hay.contains("login") || hay.contains("sign in") || hay.contains("signin")) {
                return el;
            }
        }
        Element named = form.selectFirst("input[type=submit][name],button[type=submit][name],button:not([type])[name]");
        if (named != null) return named;
        return form.selectFirst("input[type=submit],button[type=submit],button:not([type])");
    }

    private boolean trySlot(Slot slot) throws Exception {'''

if old not in src:
    raise SystemExit("Expected login block not found; refusing to patch unknown source")

path.write_text(src.replace(old, new, 1), encoding="utf-8")
print("Login diagnostic patch applied")
