package com.claudecode.core.pokemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PokemonEvolutionTest {

    @Test
    void threeStageChainEvolvesAtLongLivedThresholds() {
        PokemonProfile bulbasaur = profile("bulbasaur", 0L, null);

        PokemonProfile levelTwenty = PokemonEvolution.addExperience(
            bulbasaur, PokemonEvolution.FIRST_EVOLUTION_TOKENS
                - PokemonEvolution.LEVEL_TOKENS);

        PokemonProfile ivysaur = PokemonEvolution.addExperience(
            levelTwenty, PokemonEvolution.LEVEL_TOKENS);
        PokemonProfile venusaur = PokemonEvolution.addExperience(ivysaur,
            PokemonEvolution.SECOND_EVOLUTION_TOKENS
                - PokemonEvolution.FIRST_EVOLUTION_TOKENS);

        assertEquals("bulbasaur", levelTwenty.name());
        assertEquals(20, PokemonEvolution.progress(levelTwenty).level());
        assertEquals("ivysaur", ivysaur.name());
        assertEquals(20, PokemonEvolution.progress(ivysaur).level());
        assertEquals("venusaur", venusaur.name());
        assertEquals(40, PokemonEvolution.progress(venusaur).level());
        assertEquals(2_000_000_000L, venusaur.experienceTokens());
    }

    @Test
    void eeveeChoosesOneStableBranch() {
        PokemonProfile eevee = profile("eevee", 0L, null);
        PokemonProfile evolved = PokemonEvolution.addExperience(
            eevee, PokemonEvolution.TWO_STAGE_EVOLUTION_TOKENS);
        PokemonProfile later = PokemonEvolution.addExperience(evolved, 10_000_000L);

        assertNotNull(evolved.evolutionChoice());
        assertTrue(PokemonRoster.chainForRoot("eevee").branches()
            .contains(evolved.evolutionChoice()));
        assertEquals(evolved.evolutionChoice(), evolved.name());
        assertEquals(evolved.evolutionChoice(), later.name());
    }

    @Test
    void progressIsSlowAndMonotonic() {
        PokemonEvolution.Progress early = PokemonEvolution.progress(
            profile("bulbasaur", 100_000_000L, null));
        PokemonEvolution.Progress nearEvolution = PokemonEvolution.progress(
            profile("bulbasaur", 900_000_000L, null));

        assertEquals(10, early.percent());
        assertEquals(90, nearEvolution.percent());
        assertEquals(3, early.level());
        assertEquals(19, nearEvolution.level());
        assertTrue(nearEvolution.level() > early.level());
    }

    @Test
    void eachEvolutionStageSpansTwentyLevels() {
        PokemonEvolution.Progress firstStageLastLevel = PokemonEvolution.progress(
            profile("bulbasaur", 950_000_000L, null));
        PokemonEvolution.Progress secondStageStart = PokemonEvolution.progress(
            profile("bulbasaur", 1_000_000_000L, null));
        PokemonEvolution.Progress secondStageLastLevel = PokemonEvolution.progress(
            profile("bulbasaur", 1_950_000_000L, null));
        PokemonEvolution.Progress finalStageStart = PokemonEvolution.progress(
            profile("bulbasaur", 2_000_000_000L, null));

        assertEquals(20, firstStageLastLevel.level());
        assertEquals(20, secondStageStart.level());
        assertEquals(39, secondStageLastLevel.level());
        assertEquals(40, finalStageStart.level());
    }

    @Test
    void finalEvolutionContinuesAtSameRateAndCapsAtNinetyNine() {
        PokemonEvolution.Progress levelFortyOne = PokemonEvolution.progress(
            profile("bulbasaur", 2_000_000_000L + PokemonEvolution.LEVEL_TOKENS, null));
        PokemonEvolution.Progress max = PokemonEvolution.progress(
            profile("bulbasaur", Long.MAX_VALUE, null));

        assertEquals(41, levelFortyOne.level());
        assertEquals(99, max.level());
        assertEquals(100, max.percent());
    }

    @Test
    void twoStageAndNonEvolvingPokemonUseTheSameLevelRate() {
        PokemonEvolution.Progress evolvedPikachu = PokemonEvolution.progress(
            profile("pikachu", 1_000_000_000L, null));
        PokemonEvolution.Progress evolvedPikachuNextLevel = PokemonEvolution.progress(
            profile("pikachu", 1_050_000_000L, null));
        PokemonEvolution.Progress standalone = PokemonEvolution.progress(
            profile("ditto", 100_000_000L, null));

        assertEquals(20, evolvedPikachu.level());
        assertEquals(21, evolvedPikachuNextLevel.level());
        assertEquals(3, standalone.level());
    }

    @Test
    void legacyIntermediateSpeciesInfersItsChainRoot() {
        PokemonProfile legacy = new PokemonProfile("haunter",
            PokemonProfile.Rarity.RARE, false, Map.of(), 42L);

        assertEquals("gastly", legacy.rootName());
    }

    @Test
    void evolvedSpeciesAndExperienceSurvivePersistenceRoundTrip() {
        PokemonProfile ivysaur = PokemonEvolution.addExperience(
            profile("bulbasaur", 0L, null), PokemonEvolution.FIRST_EVOLUTION_TOKENS);
        PokemonProfile restoredIvysaur = PokemonProfile.fromJson(
            ivysaur.toJson());
        PokemonProfile venusaur = PokemonEvolution.addExperience(restoredIvysaur,
            PokemonEvolution.SECOND_EVOLUTION_TOKENS
                - PokemonEvolution.FIRST_EVOLUTION_TOKENS);
        PokemonProfile restoredVenusaur = PokemonProfile.fromJson(
            venusaur.toJson());

        assertEquals("ivysaur", restoredIvysaur.name());
        assertEquals(1_000_000_000L, restoredIvysaur.experienceTokens());
        assertEquals("venusaur", restoredVenusaur.name());
        assertEquals(2_000_000_000L, restoredVenusaur.experienceTokens());
    }

    @Test
    void explicitPersistenceProjectionContainsEveryNativeImageField() {
        var stats = new EnumMap<PokemonProfile.Stat, Integer>(PokemonProfile.Stat.class);
        for (PokemonProfile.Stat stat : PokemonProfile.Stat.values()) stats.put(stat, 0);
        stats.put(PokemonProfile.Stat.DEBUGGING, 72);
        PokemonProfile pikachu = new PokemonProfile("pikachu", "pikachu",
            PokemonProfile.Rarity.LEGENDARY, true,
            stats, 1234L, 999_999_000L, null);

        var json = pikachu.toJson();

        assertEquals("pikachu", json.path("rootName").asText());
        assertEquals("pikachu", json.path("name").asText());
        assertEquals("LEGENDARY", json.path("rarity").asText());
        assertTrue(json.path("shiny").asBoolean());
        assertEquals(72, json.path("stats").path("DEBUGGING").asInt());
        assertEquals(0, json.path("stats").path("PATIENCE").asInt());
        assertEquals(1234L, json.path("hatchedAt").asLong());
        assertEquals(999_999_000L, json.path("experienceTokens").asLong());
        assertTrue(json.path("evolutionChoice").isNull());
        assertEquals(pikachu, PokemonProfile.fromJson(json));
    }

    private static PokemonProfile profile(String name, long experience, String choice) {
        var stats = new EnumMap<PokemonProfile.Stat, Integer>(PokemonProfile.Stat.class);
        for (PokemonProfile.Stat stat : PokemonProfile.Stat.values()) stats.put(stat, 50);
        return new PokemonProfile(name, name, PokemonProfile.Rarity.COMMON, false,
            stats, 1234L, experience, choice);
    }
}
