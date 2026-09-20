package ho.artisan.factorygaugeimprove;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory Gauge Improve - lifts the one-stack ceiling of Create's factory gauges.
 *
 * <p>The ceiling is lifted the way the gauge's own 3x3 input grid suggests:
 * every cell keeps holding one stack, and amounts beyond that spill into the
 * following empty cells. Nine cells of one stack is exactly one Create package,
 * so a gauge can still never describe an order a single package cannot hold -
 * which is what used to force one gauge per stack onto the wall.
 *
 * <p>Everything it does happens on the client: the amount the gauge stores is
 * plain Create data, which its server accepts unchanged.
 */
@Mod(FactoryGaugeImprove.MODID)
public class FactoryGaugeImprove {
	public static final String MODID = "factorygaugeimprove";
	public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

	/** Fallbacks, also the values written into a fresh config file. */
	private static final int DEFAULT_PER_CELL = 64;
	private static final int DEFAULT_MAX_CELLS = 9;
	private static final boolean DEFAULT_SPILL = true;
	private static final int DEFAULT_SHIFT_STEP = 8;
	private static final int DEFAULT_CTRL_STEP = 64;
	private static final int DEFAULT_MAX_REQUESTED_OUTPUT = 46_656;
	private static final boolean DEFAULT_DIAGNOSTICS = true;

	private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

	/**
	 * How much one cell of the input grid holds. 64 is one stack, which is also
	 * exactly one slot of a package.
	 */
	public static final ModConfigSpec.IntValue PER_CELL = BUILDER
			.comment("How much a single cell of the gauge's input grid holds.",
					"64 (default) is one stack, which is also exactly one slot of a Create package,",
					"so every cell of the grid maps to one slot of the package the request produces.",
					"Raise it (e.g. 128) only if you deliberately want a cell to mean two stacks;",
					"that trades the 'one package' guarantee for larger batches.")
			.defineInRange("limits.perCell", DEFAULT_PER_CELL, 1, 4096);

	/**
	 * How many cells the inputs may occupy in total. Nine is the whole grid.
	 */
	public static final ModConfigSpec.IntValue MAX_CELLS = BUILDER
			.comment("How many cells the gauge's inputs may occupy in total.",
					"The input area is the 3x3 grid, so 9 is both the maximum and the default.",
					"Nine cells of one stack is one full package, which is the point: with this",
					"ceiling a gauge can never request more than a single package can carry.",
					"Lower it to make the gauge stop accepting connections earlier.")
			.defineInRange("limits.maxCells", DEFAULT_MAX_CELLS, 1, 9);

	/** Whether amounts above one cell spill into the following empty cells. */
	public static final ModConfigSpec.BooleanValue SPILL = BUILDER
			.comment("Whether an amount above one cell is laid out over the following empty cells.",
					"true (default): a connection carries one amount and the grid shows it, one stack",
					"per cell, filling a cell to 64 before the next one appears - what placing a",
					"second, third ... gauge used to achieve, but with a single gauge.",
					"false: keep one cell per connection and let its number grow past 64 instead.")
			.define("limits.spillToEmptyCells", DEFAULT_SPILL);

	/**
	 * The ceiling of the "expected output" slot - how much product one request
	 * may ask for.
	 *
	 * <p>That slot is a single number with no grid behind it, so unlike the
	 * input cells it has nothing to spill into: this value is its only limit.
	 * The default is deliberately far above the input side's one package (576),
	 * so the output is never what caps a request.
	 */
	public static final ModConfigSpec.IntValue MAX_REQUESTED_OUTPUT = BUILDER
			.comment("How much product a single request may ask for - the ceiling of the expected output slot.",
					"That slot is one number with no grid to spill into, so this is its only limit.",
					"46656 (default) = 64 x 9 x 9 x 9: one stack, one package (9 cells), and two more",
					"factor-of-nine tiers. It is far above the input side's 576 on purpose, so the",
					"output is never the part that caps a request. Raising it further is allowed;",
					"the logistics network will simply plan an order of that size.")
			.defineInRange("limits.maxRequestedOutput", DEFAULT_MAX_REQUESTED_OUTPUT, 1, 1_000_000_000);

	/** Coarse scroll step while Shift is held; amounts snap to multiples of it. */
	public static final ModConfigSpec.IntValue SHIFT_STEP = BUILDER
			.comment("Coarse step while holding Shift over a grid cell or the output slot.",
					"The amount snaps to the next/previous multiple of this step,",
					"so exact values stay reachable by fine-tuning afterwards.")
			.defineInRange("scroll.shiftStep", DEFAULT_SHIFT_STEP, 1, 1_000_000);

	/** Coarse scroll step while Ctrl is held; amounts snap to multiples of it. */
	public static final ModConfigSpec.IntValue CTRL_STEP = BUILDER
			.comment("Coarse step while holding Ctrl over a grid cell or the output slot.",
					"The amount snaps to the next/previous multiple of this step, and it moves the",
					"connection's whole amount - not just the cell under the cursor. With 64 a",
					"notch is one whole stack, so nine of them take a gauge to a full package of 576.")
			.defineInRange("scroll.ctrlStep", DEFAULT_CTRL_STEP, 1, 1_000_000);

	/**
	 * Traces every layout and scroll decision into the game log.
	 *
	 * <p>On by default: when the grid does not behave as the README describes,
	 * these lines say whether the inputs were ever laid out over the cells, which
	 * cell the wheel was over, and what the mod decided to do with it. Turn it off
	 * once the panel behaves - it logs on every notch.
	 */
	public static final ModConfigSpec.BooleanValue DIAGNOSTICS = BUILDER
			.comment("Log every layout and scroll decision to the game log.",
					"Lines are prefixed with '[fgi]'. Turn off once the panel behaves;",
					"scrolling logs one line per notch.")
			.define("diagnostics.logToConsole", DEFAULT_DIAGNOSTICS);

	public static final ModConfigSpec SPEC = BUILDER.build();

	public FactoryGaugeImprove(IEventBus modEventBus, ModContainer modContainer) {
		modContainer.registerConfig(ModConfig.Type.CLIENT, SPEC);
		modEventBus.addListener(this::onConfigLoading);
		modEventBus.addListener(this::onConfigReloading);
		LOGGER.info("Factory Gauge Improve {} loaded - gauge inputs fill the 3x3 grid one stack at a time",
				modContainer.getModInfo().getVersion());
	}

	/** Prints the values this build will actually use, once the file has been read. */
	private void onConfigLoading(ModConfigEvent.Loading event) {
		logEffectiveConfig(event.getConfig());
	}

	private void onConfigReloading(ModConfigEvent.Reloading event) {
		logEffectiveConfig(event.getConfig());
	}

	/**
	 * The constructor runs before the config file is loaded, so the values logged
	 * there would be the fallbacks. This is the line to read when the panel does
	 * not behave as documented: it is what the mod is really running with, and it
	 * also says which build is in the mods folder.
	 */
	private static void logEffectiveConfig(ModConfig config) {
		if (config.getSpec() != SPEC) {
			return;
		}
		LOGGER.info("[fgi] effective config: perCell={} maxCells={} spill={} maxRequestedOutput={} shiftStep={} ctrlStep={} diagnostics={}",
				perCell(), maxCells(), spill(), maxRequestedOutput(), shiftStep(), ctrlStep(), diagnostics());
	}

	/** How much one grid cell holds. */
	public static int perCell() {
		return read(PER_CELL, DEFAULT_PER_CELL);
	}

	/** How many grid cells the inputs may occupy; 9 is the whole input area. */
	public static int maxCells() {
		return read(MAX_CELLS, DEFAULT_MAX_CELLS);
	}

	/** Whether amounts above one cell are spread over the following cells. */
	public static boolean spill() {
		return read(SPILL, DEFAULT_SPILL);
	}

	public static int shiftStep() {
		return read(SHIFT_STEP, DEFAULT_SHIFT_STEP);
	}

	public static int ctrlStep() {
		return read(CTRL_STEP, DEFAULT_CTRL_STEP);
	}

	/** Whether the layout and scroll decisions are traced into the log. */
	public static boolean diagnostics() {
		return read(DIAGNOSTICS, DEFAULT_DIAGNOSTICS);
	}

	/** Highest amount a lone connection may carry: a whole grid of full cells. */
	public static int maxAmount() {
		return perCell() * maxCells();
	}

	/**
	 * Ceiling of one request's product count - the "expected output" slot.
	 *
	 * <p>Independent of the input grid: that slot is a single number, so it has
	 * no cells to count and no grid to fill.
	 */
	public static int maxRequestedOutput() {
		return read(MAX_REQUESTED_OUTPUT, DEFAULT_MAX_REQUESTED_OUTPUT);
	}

	/**
	 * Config values are only safe to read once the file is loaded. The mixins run
	 * while a screen is open, which is always well after that, but a fallback
	 * beats an exception in a hot UI path - and on a dedicated server the client
	 * config is never loaded at all.
	 */
	private static int read(ModConfigSpec.IntValue value, int fallback) {
		try {
			return value.get();
		} catch (IllegalStateException notLoadedYet) {
			return fallback;
		}
	}

	private static boolean read(ModConfigSpec.BooleanValue value, boolean fallback) {
		try {
			return value.get();
		} catch (IllegalStateException notLoadedYet) {
			return fallback;
		}
	}
}
