package com.claudecode.core.pokemon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PokemonRollerTest {

    @Test
    void evolutionDocumentsExposeRootsAndAllSpecies() {
        assertEquals(38, PokemonRoster.STARTER_NAMES.size());
        assertEquals(90, PokemonRoster.NAMES.size());
        assertEquals(90, PokemonRoster.ENTRIES.size());
        assertEquals("皮卡丘", PokemonRoster.chineseName("pikachu"));
        assertEquals("耿鬼", PokemonRoster.chineseName("gengar"));
        assertTrue(PokemonRoster.contains("growlithe"));
        assertFalse(PokemonRoster.STARTER_NAMES.contains("gengar"));
        assertTrue(PokemonRoster.STARTER_NAMES.contains("gastly"));
    }

    @Test
    void rerollAvoidsCurrentPokemonAndBoundsStats() {
        PokemonProfile profile = PokemonRoller.roll(new Random(7), "pikachu");

        assertNotEquals("pikachu", profile.name());
        assertEquals(5, profile.stats().size());
        assertTrue(profile.stats().values().stream().allMatch(value -> value >= 1 && value <= 100));
    }

    @Test
    void defaultIsRegularPikachu() {
        PokemonProfile profile = PokemonRoller.defaultPikachu();
        assertEquals("pikachu", profile.name());
        assertEquals(PokemonProfile.Rarity.COMMON, profile.rarity());
        assertFalse(profile.shiny());
    }
}
