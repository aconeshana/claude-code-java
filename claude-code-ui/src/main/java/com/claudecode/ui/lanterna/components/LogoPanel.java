package com.claudecode.ui.lanterna.components;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.pokemon.PokemonRoller;
import com.claudecode.core.pokemon.PokemonRoster;
import com.claudecode.core.pokemon.PokemonEvolution;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Renders the borderless condensed welcome block shown on startup and after {@code /clear}.
 */
public final class LogoPanel {

    private static final int OUTER_INDENT = 1;
    private static final int COLUMN_GAP = 2;
    private static final int POKEMON_MIN_TERMINAL_WIDTH = 56;
    private static final int WELCOME_POKEMON_STAGE_HEIGHT = 12;
    private static final String CONFIG_KEY = "welcomePokemon";

    public record WelcomeBlock(int firstLine, int lineCount, int modelLine, long contentEpoch) {}
    private record Banner(String name, List<SpriteRow> sprite) {}
    private record SpriteRow(String left, String bg, String right) {}
    private record RenderSprite(int width, List<List<MessagePanel.Segment>> rows) {
        int height() { return rows.size(); }
    }
    record SpriteArtwork(int width, List<List<MessagePanel.Segment>> rows) {
        int height() { return rows.size(); }
    }
    record ProgressBar(String filled, String empty) {}

    private static final Banner BANNER = loadBanner();
    private volatile PokemonProfile pokemon;
    private volatile SpriteVariant cachedSpriteVariant;
    private volatile RenderSprite cachedPokemonSprite;

    private record SpriteVariant(String name, boolean shiny) {}

    public LogoPanel() {
        this(loadPokemon());
    }

    LogoPanel(PokemonProfile pokemon) {
        this.pokemon = pokemon;
    }

    @Explanation("Adds an opt-in pokemon-colorscripts welcome sprite; absent configuration keeps the compact Clawd banner and never enters model context.")
    public PokemonProfile pokemon() { return pokemon; }

    /** Renders the complete welcome block and returns its replaceable source range. */
    public WelcomeBlock show(MessagePanel panel, int terminalWidth, String model) {
        int firstLine = panel.snapshotLineCount();
        List<List<MessagePanel.Segment>> rows = renderRows(terminalWidth, model);
        for (List<MessagePanel.Segment> row : rows) panel.appendMixed(row);
        panel.setHistoryTopAnchor(firstLine, rows);
        return block(panel, firstLine, rows, terminalWidth);
    }

    /** Replaces the existing startup block in place after {@code /pokemon}. */
    public WelcomeBlock replacePokemon(MessagePanel panel, WelcomeBlock existing,
                                       int terminalWidth, String model,
                                       PokemonProfile replacement) {
        pokemon = replacement == null ? PokemonRoller.defaultPikachu() : replacement;
        List<List<MessagePanel.Segment>> rows = renderRows(terminalWidth, model);
        int firstLine = panel.replaceHistoryTopAnchor(rows);
        return block(panel, firstLine, rows, terminalWidth);
    }

    /** Replaces only the live model row after a model switch. */
    public void updateModelLine(MessagePanel panel, WelcomeBlock block,
                                int terminalWidth, String model) {
        if (panel == null || block == null) {
            return;
        }
        List<List<MessagePanel.Segment>> rows = renderRows(terminalWidth, model);
        if (block.modelLine() < 0 || panel.contentEpoch() != block.contentEpoch()) {
            panel.replaceHistoryTopAnchor(rows);
            return;
        }
        int relative = block.modelLine() - block.firstLine();
        if (relative >= 0 && relative < rows.size()) panel.updateLine(block.modelLine(), rows.get(relative));
    }

    private WelcomeBlock block(MessagePanel panel, int firstLine,
                               List<List<MessagePanel.Segment>> rows, int terminalWidth) {
        RenderSprite sprite = selectSprite(terminalWidth);
        int metadataHeight = pokemon == null ? 3 : 4;
        int bodyHeight = welcomeBodyHeight(sprite, metadataHeight);
        int metadataStart = Math.max(0, (bodyHeight - metadataHeight) / 2);
        int modelLine = firstLine < 0 ? -1 : firstLine + metadataStart + 1;
        return new WelcomeBlock(firstLine, rows.size(), modelLine,
            panel.contentEpoch());
    }

    private List<List<MessagePanel.Segment>> renderRows(int terminalWidth, String model) {
        RenderSprite sprite = selectSprite(terminalWidth);
        List<List<MessagePanel.Segment>> metadata = metadataRows(terminalWidth, sprite.width(), model);
        int bodyHeight = welcomeBodyHeight(sprite, metadata.size());
        int spriteStart = Math.max(0, (bodyHeight - sprite.height()) / 2);
        int metadataStart = Math.max(0, (bodyHeight - metadata.size()) / 2);
        List<List<MessagePanel.Segment>> rows = new ArrayList<>(bodyHeight + 1);

        for (int row = 0; row < bodyHeight; row++) {
            List<MessagePanel.Segment> output = new ArrayList<>();
            output.add(defaultBackground(" ".repeat(OUTER_INDENT)));
            appendSpriteRow(output, sprite, row - spriteStart);
            output.add(new MessagePanel.Segment(" ".repeat(COLUMN_GAP), TextColor.ANSI.DEFAULT));
            int metadataRow = row - metadataStart;
            if (metadataRow >= 0 && metadataRow < metadata.size()) output.addAll(metadata.get(metadataRow));
            rows.add(List.copyOf(output));
        }
        rows.add(List.of(new MessagePanel.Segment("", LanternaTheme.inputText())));
        return List.copyOf(rows);
    }

    private List<List<MessagePanel.Segment>> metadataRows(
            int terminalWidth, int spriteWidth, String model) {
        int available = Math.max(1, terminalWidth - OUTER_INDENT - spriteWidth - COLUMN_GAP - 1);
        String displayModel = StringUtils.isBlank(model) ? "unknown" : ModelDisplayName.render(model);
        String modelBilling = displayModel + UiSettings.readEffortSuffix(model) + " · API Usage Billing";
        String cwd = shortenCwd(System.getProperty("user.dir", ""), available);
        List<List<MessagePanel.Segment>> rows = new ArrayList<>(4);
        rows.add(titleSegments(available));
        rows.add(List.of(new MessagePanel.Segment(
            truncate(modelBilling, available), LanternaTheme.welcomeDim())));
        rows.add(List.of(new MessagePanel.Segment(cwd, LanternaTheme.welcomeDim())));
        if (pokemon != null) rows.add(pokemonTip(available));
        return List.copyOf(rows);
    }

    private List<MessagePanel.Segment> pokemonTip(int available) {
        PokemonProfile current = pokemon;
        PokemonEvolution.Progress progress = PokemonEvolution.progress(current);
        String identity = current.displayName() + " · " + title(current.rarity())
            + " " + current.stars() + (current.shiny() ? " · shiny" : "")
            + " · Lv " + progress.level() + " ";
        int barWidth = Math.max(4, Math.min(14,
            available - FormatUtils.displayWidth(identity) - 7));
        ProgressBar bar = progressBar(progress, barWidth);
        String percent = " " + progressPercent(progress);
        String prefix = truncate(identity, Math.max(1, available - barWidth - percent.length()));
        List<MessagePanel.Segment> segments = new ArrayList<>(4);
        segments.add(new MessagePanel.Segment(prefix, rarityColor(current.rarity())));
        segments.addAll(progressSegments(rarityColor(current.rarity()),
            bar.filled(), bar.empty(), LanternaTheme.welcomeDim()));
        segments.add(new MessagePanel.Segment(percent, LanternaTheme.welcomeDim()));
        return List.copyOf(segments);
    }

    /**
     * Builds the experience-bar segments with a shared {@code track} background.
     * Both the filled portion (which may contain partial eighth-blocks like {@code ▍})
     * and the empty rail must carry the same background: a partial block's transparent
     * right edge otherwise renders against the bare terminal background, which reads as
     * a gap between the earned and remaining portions. Sharing a background keeps the
     * transition continuous, matching the official ProgressBar's single background.
     */
    static List<MessagePanel.Segment> progressSegments(
            TextColor fillColor, String filled, String empty, TextColor track) {
        return List.of(
            new MessagePanel.Segment(filled, fillColor, track),
            new MessagePanel.Segment(empty, track, track));
    }

    static String progressPercent(PokemonEvolution.Progress progress) {
        if (progress == null || progress.required() <= 0 || progress.current() <= 0) return "0%";
        double percent = Math.min(100.0,
            progress.current() * 100.0 / progress.required());
        if (percent >= 100.0) return "100%";
        DecimalFormat format = new DecimalFormat("0.00",
            DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.DOWN);
        return format.format(percent) + "%";
    }

    static ProgressBar progressBar(PokemonEvolution.Progress progress, int width) {
        int safeWidth = Math.max(0, width);
        if (safeWidth == 0 || progress == null || progress.required() <= 0
                || progress.current() <= 0) {
            return new ProgressBar("", "░".repeat(safeWidth));
        }
        long totalEighths = safeWidth * 8L;
        long scaled = Math.min(totalEighths,
            Math.max(1L, ceilingDivide(
                Math.min(progress.current(), progress.required()) * totalEighths,
                progress.required())));
        int full = (int) (scaled / 8L);
        int partial = (int) (scaled % 8L);
        String partialGlyph = partial == 0 ? "" : "▏▎▍▌▋▊▉".substring(partial - 1, partial);
        int occupied = full + (partial == 0 ? 0 : 1);
        return new ProgressBar("█".repeat(full) + partialGlyph,
            "░".repeat(Math.max(0, safeWidth - occupied)));
    }

    private static long ceilingDivide(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private RenderSprite selectSprite(int terminalWidth) {
        PokemonProfile current = pokemon;
        if (current == null || terminalWidth < POKEMON_MIN_TERMINAL_WIDTH) return clawdSprite();
        SpriteVariant variant = new SpriteVariant(current.name(), current.shiny());
        RenderSprite cached = cachedPokemonSprite;
        if (variant.equals(cachedSpriteVariant) && cached != null) return cached;
        RenderSprite loaded = fitWelcomeStage(loadPokemonSprite(current));
        RenderSprite resolved = loaded != null ? loaded : clawdSprite();
        cachedSpriteVariant = variant;
        cachedPokemonSprite = resolved;
        return resolved;
    }

    private static int welcomeBodyHeight(RenderSprite sprite, int metadataHeight) {
        return Math.max(sprite.height(), metadataHeight);
    }

    /**
     * Keeps large pixel sprites subordinate to the welcome metadata. Small
     * sprites retain their native size; only oversized sprites are sampled down
     * proportionally into the fixed-height welcome stage. Full-size artwork is
     * still returned by {@link #spriteArtwork(PokemonProfile)} for cards and the
     * evolution cut-in.
     */
    @Explanation("Caps the UI-only Pokémon welcome stage without changing full-size artwork.")
    private static RenderSprite fitWelcomeStage(RenderSprite source) {
        if (source == null || source.height() <= WELCOME_POKEMON_STAGE_HEIGHT) return source;
        int targetHeight = WELCOME_POKEMON_STAGE_HEIGHT;
        int targetWidth = Math.max(1, (int) Math.round(
            source.width() * (double) targetHeight / source.height()));
        List<List<MessagePanel.Segment>> sourceCells = source.rows().stream()
            .map(row -> expandCells(row, source.width()))
            .toList();
        List<List<MessagePanel.Segment>> rows = new ArrayList<>(targetHeight);
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = Math.min(source.height() - 1,
                (int) ((long) y * source.height() / targetHeight));
            List<MessagePanel.Segment> sampled = new ArrayList<>(targetWidth);
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = Math.min(source.width() - 1,
                    (int) ((long) x * source.width() / targetWidth));
                sampled.add(sourceCells.get(sourceY).get(sourceX));
            }
            rows.add(mergeCells(sampled));
        }
        return new RenderSprite(targetWidth, List.copyOf(rows));
    }

    private static List<MessagePanel.Segment> expandCells(
            List<MessagePanel.Segment> row, int width) {
        List<MessagePanel.Segment> cells = new ArrayList<>(width);
        for (MessagePanel.Segment segment : row) {
            for (int offset = 0; offset < segment.text().length(); offset++) {
                cells.add(new MessagePanel.Segment(
                    String.valueOf(segment.text().charAt(offset)), segment.color(),
                    segment.bgColor(), segment.hyperlinkUrl(), segment.modifiers()));
            }
        }
        while (cells.size() < width) {
            cells.add(new MessagePanel.Segment(" ", TextColor.ANSI.DEFAULT));
        }
        return List.copyOf(cells.subList(0, width));
    }

    private static List<MessagePanel.Segment> mergeCells(List<MessagePanel.Segment> cells) {
        List<MessagePanel.Segment> merged = new ArrayList<>();
        for (MessagePanel.Segment cell : cells) {
            if (!merged.isEmpty()) {
                MessagePanel.Segment previous = merged.getLast();
                if (Objects.equals(previous.color(), cell.color())
                        && Objects.equals(previous.bgColor(), cell.bgColor())
                        && Objects.equals(previous.hyperlinkUrl(), cell.hyperlinkUrl())
                        && previous.modifiers().equals(cell.modifiers())) {
                    merged.set(merged.size() - 1, new MessagePanel.Segment(
                        previous.text() + cell.text(), previous.color(), previous.bgColor(),
                        previous.hyperlinkUrl(), previous.modifiers()));
                    continue;
                }
            }
            merged.add(cell);
        }
        return List.copyOf(merged);
    }

    private static RenderSprite loadPokemonSprite(PokemonProfile profile) {
        String variant = profile.shiny() ? "shiny" : "regular";
        String name = PokemonRoster.contains(profile.name()) ? profile.name() : "pikachu";
        try {
            AnsiSpriteParser.Sprite parsed = AnsiSpriteParser.parse(
                readResource("/welcome/pokemon/" + variant + "/" + name + ".ansi"));
            if (parsed.width() <= 0 || parsed.height() <= 0) return null;
            return new RenderSprite(parsed.width(), parsed.rows());
        } catch (RuntimeException _) {
            return null;
        }
    }

    static SpriteArtwork spriteArtwork(PokemonProfile profile) {
        RenderSprite sprite = profile == null ? null : loadPokemonSprite(profile);
        if (sprite == null) sprite = clawdSprite();
        return new SpriteArtwork(sprite.width(), sprite.rows());
    }

    private static PokemonProfile loadPokemon() {
        PokemonProfile stored = PokemonProfile.fromJson(UiSettings.readGlobalNode(CONFIG_KEY));
        return stored != null && PokemonRoster.contains(stored.name())
            ? stored : null;
    }

    private static TextColor rarityColor(PokemonProfile.Rarity rarity) {
        return switch (rarity) {
            case COMMON -> LanternaTheme.welcomeDim();
            case UNCOMMON -> LanternaTheme.toolSuccess();
            case RARE -> LanternaTheme.permission();
            case EPIC -> LanternaTheme.acceptPurple();
            case LEGENDARY -> LanternaTheme.autoYellow();
        };
    }

    private static String title(PokemonProfile.Rarity rarity) {
        String lower = rarity.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static void appendSpriteRow(List<MessagePanel.Segment> output, RenderSprite sprite, int rowIndex) {
        if (rowIndex < 0 || rowIndex >= sprite.height()) {
            output.add(new MessagePanel.Segment(" ".repeat(sprite.width()), TextColor.ANSI.DEFAULT));
            return;
        }
        List<MessagePanel.Segment> row = sprite.rows().get(rowIndex);
        output.addAll(row);
        int rowWidth = row.stream().mapToInt(segment -> FormatUtils.displayWidth(segment.text())).sum();
        if (rowWidth < sprite.width()) {
            output.add(new MessagePanel.Segment(" ".repeat(sprite.width() - rowWidth), TextColor.ANSI.DEFAULT));
        }
    }

    private static List<MessagePanel.Segment> titleSegments(int available) {
        String name = "Claude Code";
        String version = " v" + appVersion();
        if (FormatUtils.displayWidth(name + version) <= available) {
            return List.of(
                new MessagePanel.Segment(name, LanternaTheme.inputText(), null, null, Set.of(SGR.BOLD)),
                new MessagePanel.Segment(version, LanternaTheme.welcomeDim()));
        }
        if (available <= FormatUtils.displayWidth(name)) {
            return List.of(new MessagePanel.Segment(truncate(name, available),
                LanternaTheme.inputText(), null, null, Set.of(SGR.BOLD)));
        }
        return List.of(
            new MessagePanel.Segment(name, LanternaTheme.inputText(), null, null, Set.of(SGR.BOLD)),
            new MessagePanel.Segment(truncate(version, available - FormatUtils.displayWidth(name)),
                LanternaTheme.welcomeDim()));
    }

    private static MessagePanel.Segment defaultBackground(String text) {
        return new MessagePanel.Segment(text, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
    }

    private static RenderSprite clawdSprite() {
        TextColor fg = LanternaTheme.clawdBody();
        TextColor bg = LanternaTheme.clawdBackground();
        int width = BANNER.sprite().stream().mapToInt(LogoPanel::spriteRowWidth).max().orElse(9);
        List<List<MessagePanel.Segment>> rows = new ArrayList<>();
        for (SpriteRow source : BANNER.sprite()) {
            List<MessagePanel.Segment> row = new ArrayList<>();
            row.add(new MessagePanel.Segment(source.left(), fg));
            if (!source.bg().isEmpty()) row.add(new MessagePanel.Segment(source.bg(), fg, bg));
            row.add(new MessagePanel.Segment(source.right(), fg));
            int padding = width - spriteRowWidth(source);
            if (padding > 0) row.add(new MessagePanel.Segment(" ".repeat(padding), TextColor.ANSI.DEFAULT));
            rows.add(List.copyOf(row));
        }
        return new RenderSprite(width, List.copyOf(rows));
    }

    private static int spriteRowWidth(SpriteRow row) {
        return FormatUtils.displayWidth(row.left() + row.bg() + row.right());
    }

    private static Banner loadBanner() {
        String[] lines = readResource("/banner.txt").replace("\r\n", "\n").split("\n", -1);
        if (lines.length < 4) throw new IllegalStateException("banner.txt must contain a name and sprite rows");
        List<SpriteRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) if (!lines[i].isEmpty()) rows.add(parseSpriteRow(lines[i]));
        return new Banner(lines[0].strip(), List.copyOf(rows));
    }

    private static SpriteRow parseSpriteRow(String line) {
        int start = line.indexOf('{');
        int end = line.indexOf('}');
        if (start >= 0 && end > start) {
            return new SpriteRow(line.substring(0, start), line.substring(start + 1, end), line.substring(end + 1));
        }
        return new SpriteRow(line, "", "");
    }

    private static String readResource(String path) {
        InputStream input = LogoPanel.class.getResourceAsStream(path);
        if (input == null) throw new IllegalStateException(path + " not found in classpath");
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static String truncate(String value, int width) {
        return FormatUtils.displayWidth(value) <= width ? value : FormatUtils.truncate(value, width);
    }

    public static String appVersion() {
        String version = LogoPanel.class.getPackage().getImplementationVersion();
        return StringUtils.isNotBlank(version) ? version : "0.1.0";
    }

    private static String shortenCwd(String path, int width) {
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty() && Strings.CS.startsWith(path, home)) {
            path = "~" + path.substring(home.length());
        }
        if (FormatUtils.displayWidth(path) <= width) return path;
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String last = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return FormatUtils.truncateNoEllipsis("~/…/" + last, width);
    }
}
