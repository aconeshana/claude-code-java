package com.claudecode.commands.impl.config;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.pokemon.PokemonEvolution;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.pokemon.PokemonRoller;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * {@code /pokemon} — inspect the current Pokémon or explicitly hatch a replacement.
 */
@Explanation("UI-only Pokémon status and confirmed hatch command; does not inject companion context into model requests.")
@SlashCommand(name = "pokemon", description = "Show your Pokémon or hatch a new one")
public final class PokemonCommand implements AnnotatedCommand {

    public static final String CONFIG_KEY = "welcomePokemon";
    private static final String USAGE = "Usage: /pokemon [status|hatch]";
    public PokemonCommand() {}

    @Override public boolean isImmediate() { return true; }

    @Override public String argumentHint() { return "[status|hatch]"; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String action = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        PokemonProfile current = context.application().settings().preferences()
            .pokemon().orElse(null);
        if (current == null) current = PokemonRoller.defaultPikachu();
        if (action.isEmpty() || Strings.CS.equals("status", action)) {
            return showStatus(context, current);
        }
        if (!Strings.CS.equals("hatch", action)) return CommandResult.of(USAGE);
        if (context.presentation().pokemonHatchLauncher() == null) {
            return CommandResult.of(
                "Hatching requires interactive confirmation. Run /pokemon hatch in the interactive UI.");
        }
        PokemonProfile capturedCurrent = current;
        context.presentation().pokemonHatchLauncher().accept(new CommandContext.PokemonHatchRequest(
            capturedCurrent,
            () -> hatch(context, capturedCurrent),
            () -> CommandResult.of("Kept " + capturedCurrent.displayName()
                + " · Lv " + PokemonEvolution
                    .progress(capturedCurrent).level())));
        return CommandResult.skip();
    }

    private CommandResult showStatus(CommandContext context, PokemonProfile current) {
        if (context.presentation().pokemonStatusPresenter() != null) {
            context.presentation().pokemonStatusPresenter().accept(current);
            return CommandResult.skip();
        }
        return CommandResult.of(describe(current));
    }

    private CommandResult hatch(CommandContext context, PokemonProfile current) {
        PokemonProfile rolled = PokemonRoller.roll(
            ThreadLocalRandom.current(), current.rootName());
        context.application().settings().preferences().savePokemon(rolled);
        if (context.session().pokemonSetter() != null) {
            context.session().pokemonSetter().accept(rolled);
            return CommandResult.skip();
        }
        return CommandResult.of("Hatched " + describe(rolled));
    }

    private static String describe(PokemonProfile profile) {
        return profile.displayName() + " · "
            + title(profile.rarity()) + " " + profile.stars()
            + (profile.shiny() ? " · shiny" : "")
            + " · Lv " + PokemonEvolution.progress(profile).level()
            + "\n" + stats(profile);
    }

    private static String stats(PokemonProfile profile) {
        return profile.stats().entrySet().stream()
            .map(entry -> entry.getKey().name() + " " + entry.getValue())
            .collect(Collectors.joining(" · "));
    }

    private static String title(PokemonProfile.Rarity rarity) {
        String lower = rarity.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
