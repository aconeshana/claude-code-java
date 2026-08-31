package com.claudecode.core.pokemon;

import com.claudecode.core.annotation.Explanation;
import java.util.List;
import java.util.Objects;

/**
 * Token-driven evolution and experience projection for the welcome Pokémon.
 *
 * <p>The progression consumes already-accounted API usage
 * after a turn completes and never participates in request construction. Each
 * billion-token evolution stage spans twenty levels (50M tokens per level),
 * and terminal forms continue at the same rate up to level 99.
 */
@Explanation("Long-lived token experience and deterministic Pokémon evolution.")
public final class PokemonEvolution {

    public static final int LEVELS_PER_EVOLUTION = 20;
    public static final int MAX_LEVEL = 99;
    public static final long FIRST_EVOLUTION_TOKENS = 1_000_000_000L;
    public static final long SECOND_EVOLUTION_TOKENS = 2_000_000_000L;
    public static final long TWO_STAGE_EVOLUTION_TOKENS = FIRST_EVOLUTION_TOKENS;
    public static final long LEVEL_TOKENS =
        FIRST_EVOLUTION_TOKENS / LEVELS_PER_EVOLUTION;

    private PokemonEvolution() {}

    public static PokemonProfile addExperience(PokemonProfile profile, long tokens) {
        if (profile == null || tokens <= 0) return profile;
        long experience = saturatedAdd(profile.experienceTokens(), tokens);
        PokemonRoster.Chain chain = PokemonRoster.chainForRoot(profile.rootName());
        if (chain == null) chain = PokemonRoster.chainContaining(profile.name());
        if (chain == null) return withProgress(profile, profile.name(), experience,
            profile.evolutionChoice());

        String choice = profile.evolutionChoice();
        String species = evolvedSpecies(chain, experience, choice);
        if (chain.branching() && experience >= TWO_STAGE_EVOLUTION_TOKENS) {
            if (choice == null || !chain.branches().contains(choice)) {
                choice = chooseBranch(profile, chain.branches());
            }
            species = choice;
        }
        return withProgress(profile, species, experience, choice);
    }

    public static Progress progress(PokemonProfile profile) {
        if (profile == null) return new Progress(1, 0, 1, true);
        PokemonRoster.Chain chain = PokemonRoster.chainForRoot(profile.rootName());
        if (chain == null) chain = PokemonRoster.chainContaining(profile.name());
        long experience = profile.experienceTokens();
        if (chain == null || !chain.canEvolve()) {
            return terminalProgress(1, experience);
        }
        if (chain.branching() || chain.linear().size() == 2) {
            if (experience < TWO_STAGE_EVOLUTION_TOKENS) {
                return new Progress(stageLevel(experience, 1, LEVELS_PER_EVOLUTION),
                    experience, TWO_STAGE_EVOLUTION_TOKENS, false);
            }
            return terminalProgress(LEVELS_PER_EVOLUTION,
                experience - TWO_STAGE_EVOLUTION_TOKENS);
        }
        if (experience < FIRST_EVOLUTION_TOKENS) {
            return new Progress(stageLevel(experience, 1, LEVELS_PER_EVOLUTION),
                experience, FIRST_EVOLUTION_TOKENS, false);
        }
        if (experience < SECOND_EVOLUTION_TOKENS) {
            long stageExperience = experience - FIRST_EVOLUTION_TOKENS;
            long stageLength = SECOND_EVOLUTION_TOKENS - FIRST_EVOLUTION_TOKENS;
            return new Progress(stageLevel(stageExperience, LEVELS_PER_EVOLUTION,
                    LEVELS_PER_EVOLUTION * 2), stageExperience, stageLength, false);
        }
        return terminalProgress(LEVELS_PER_EVOLUTION * 2,
            experience - SECOND_EVOLUTION_TOKENS);
    }

    private static String evolvedSpecies(PokemonRoster.Chain chain, long experience,
                                         String choice) {
        if (chain.branching()) {
            return experience >= TWO_STAGE_EVOLUTION_TOKENS
                && choice != null && chain.branches().contains(choice) ? choice : chain.root();
        }
        if (chain.linear().size() == 1) return chain.root();
        if (chain.linear().size() == 2) {
            return experience >= TWO_STAGE_EVOLUTION_TOKENS
                ? chain.linear().get(1) : chain.root();
        }
        if (experience >= SECOND_EVOLUTION_TOKENS) return chain.linear().get(2);
        if (experience >= FIRST_EVOLUTION_TOKENS) return chain.linear().get(1);
        return chain.root();
    }

    private static String chooseBranch(PokemonProfile profile, List<String> branches) {
        int hash = Objects.hash(profile.rootName(), profile.hatchedAt(), profile.stats(),
            profile.rarity(), profile.shiny());
        return branches.get(Math.floorMod(hash, branches.size()));
    }

    private static PokemonProfile withProgress(PokemonProfile profile, String species,
                                               long experience, String choice) {
        return new PokemonProfile(profile.rootName(), species, profile.rarity(), profile.shiny(),
            profile.stats(), profile.hatchedAt(), experience, choice);
    }

    private static int stageLevel(long stageExperience, int firstLevel, int lastLevel) {
        long gainedLevels = Math.max(0L, stageExperience) / LEVEL_TOKENS;
        return (int) Math.min(lastLevel, firstLevel + gainedLevels);
    }

    private static Progress terminalProgress(int startingLevel, long stageExperience) {
        long safeExperience = Math.max(0L, stageExperience);
        int level = (int) Math.min(MAX_LEVEL,
            startingLevel + safeExperience / LEVEL_TOKENS);
        if (level >= MAX_LEVEL) {
            return new Progress(MAX_LEVEL, LEVEL_TOKENS, LEVEL_TOKENS, true);
        }
        return new Progress(level, safeExperience % LEVEL_TOKENS, LEVEL_TOKENS, true);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    public record Progress(int level, long current, long required, boolean fullyEvolved) {
        public int percent() {
            if (required <= 0) return 100;
            return (int) Math.min(100L, Math.max(0L, current) * 100L / required);
        }
    }
}
