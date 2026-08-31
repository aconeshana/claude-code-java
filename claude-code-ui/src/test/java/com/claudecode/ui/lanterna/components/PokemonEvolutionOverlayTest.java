package com.claudecode.ui.lanterna.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PokemonEvolutionOverlayTest {

    @Test
    void fullAnimationUsesOldFlashGlowFlashNewSequence() {
        List<PokemonEvolutionOverlay.Frame> frames =
            PokemonEvolutionOverlay.animationFrames();

        assertEquals(List.of(
            PokemonEvolutionOverlay.Stage.OLD,
            PokemonEvolutionOverlay.Stage.FLASH_OLD,
            PokemonEvolutionOverlay.Stage.GLOW,
            PokemonEvolutionOverlay.Stage.FLASH_NEW,
            PokemonEvolutionOverlay.Stage.NEW),
            frames.stream().map(PokemonEvolutionOverlay.Frame::stage).toList());
        assertEquals(7_000L,
            frames.stream().mapToLong(PokemonEvolutionOverlay.Frame::durationMs).sum());
    }
}
