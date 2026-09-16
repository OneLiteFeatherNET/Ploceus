package net.onelitefeather.ploceus.service;

import net.datafaker.Faker;
import net.onelitefeather.ploceus.domain.PloceusKey;

import java.util.Locale;
import java.util.function.Predicate;

/// Suggests world names. The result is always normalised to a legal key value, so a
/// suggestion can be accepted without further validation.
///
/// Every template below was verified against the actual Datafaker 2.7.0 jar (not just
/// trusted by name) before being kept here — see `task-18-report.md` for the check.
public class NameSuggester {

    private static final String[] TEMPLATES = {
            "#{Color.name}-#{Ancient.god}",
            "#{Space.moon}-#{Color.name}",
            "#{Ancient.titan}-#{Space.planet}",
            "#{Color.name}-#{Space.nebula}",
            "#{Nation.capitalCity}-#{Color.name}"
    };

    private final Faker faker;

    public NameSuggester() {
        this(new Faker(Locale.ENGLISH));
    }

    public NameSuggester(Faker faker) {
        this.faker = faker;
    }

    public String suggest() {
        String template = TEMPLATES[faker.random().nextInt(TEMPLATES.length)];
        return normalise(faker.expression(template));
    }

    /// Re-rolls until the name is free. Falls back to a numeric suffix so this always
    /// terminates.
    public String suggestUnique(Predicate<String> taken) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = suggest();
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        String base = suggest();
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }

    private String normalise(String raw) {
        String value = PloceusKey.fromWorldName(raw.replace(' ', '-')).value();
        return value.isBlank() ? "world" : value;
    }
}
