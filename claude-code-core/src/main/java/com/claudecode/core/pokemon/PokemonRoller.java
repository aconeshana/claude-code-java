package com.claudecode.core.pokemon;

import com.claudecode.core.annotation.Explanation;
import java.util.EnumMap;
import java.util.List;
import java.util.random.RandomGenerator;




@Explanation("Adds UI-only Pokémon hatching with resource-backed rarity and stats.")
public final class PokemonRoller {

    private PokemonRoller() {}

    public static PokemonProfile roll(RandomGenerator random, String previousName) {
        List<String> names = PokemonRoster.STARTER_NAMES;
        String name;
        do {
            name = names.get(random.nextInt(names.size()));
        } while (names.size() > 1 && name.equals(previousName));

        PokemonProfile.Rarity rarity = rollRarity(random.nextInt(100));
        boolean shiny = rarity == PokemonProfile.Rarity.LEGENDARY
            || random.nextInt(128) == 0;
        return new PokemonProfile(name, rarity, shiny,
            rollStats(random, rarity), System.currentTimeMillis());
    }

    public static PokemonProfile defaultPikachu() {
        var stats = new EnumMap<PokemonProfile.Stat, Integer>(PokemonProfile.Stat.class);
        stats.put(PokemonProfile.Stat.DEBUGGING, 72);
        stats.put(PokemonProfile.Stat.PATIENCE, 34);
        stats.put(PokemonProfile.Stat.CHAOS, 58);
        stats.put(PokemonProfile.Stat.WISDOM, 41);
        stats.put(PokemonProfile.Stat.SNARK, 63);
        return new PokemonProfile("pikachu", PokemonProfile.Rarity.COMMON,
            false, stats, 0L);
    }

    private static PokemonProfile.Rarity rollRarity(int roll) {
        if (roll < 60) return PokemonProfile.Rarity.COMMON;
        if (roll < 85) return PokemonProfile.Rarity.UNCOMMON;
        if (roll < 95) return PokemonProfile.Rarity.RARE;
        if (roll < 99) return PokemonProfile.Rarity.EPIC;
        return PokemonProfile.Rarity.LEGENDARY;
    }

    private static EnumMap<PokemonProfile.Stat, Integer> rollStats(
            RandomGenerator random, PokemonProfile.Rarity rarity) {
        int[] floors = {5, 15, 25, 35, 50};
        int floor = floors[rarity.ordinal()];
        PokemonProfile.Stat[] names = PokemonProfile.Stat.values();
        PokemonProfile.Stat peak = names[random.nextInt(names.length)];
        PokemonProfile.Stat dump;
        do { dump = names[random.nextInt(names.length)]; } while (dump == peak);

        var stats = new EnumMap<PokemonProfile.Stat, Integer>(PokemonProfile.Stat.class);
        for (PokemonProfile.Stat stat : names) {
            int value;
            if (stat == peak) value = Math.min(100, floor + 50 + random.nextInt(30));
            else if (stat == dump) value = Math.max(1, floor - 10 + random.nextInt(15));
            else value = Math.min(100, floor + random.nextInt(40));
            stats.put(stat, value);
        }
        return stats;
    }
}
