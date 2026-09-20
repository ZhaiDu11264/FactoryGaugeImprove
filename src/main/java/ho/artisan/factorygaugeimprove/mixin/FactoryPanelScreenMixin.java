package ho.artisan.factorygaugeimprove.mixin;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelScreen;
import ho.artisan.factorygaugeimprove.AmountStepping;
import ho.artisan.factorygaugeimprove.FactoryGaugeImprove;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lays the factory gauge's inputs out over its 3x3 grid, one stack per cell.
 *
 * <h2>What vanilla does</h2>
 * The gauge screen keeps one {@code BigItemStack} per connection - one grid cell
 * each - and {@code mouseScrolled} writes
 *
 * <pre>{@code
 * itemStack.count = Mth.clamp((int) (itemStack.count + signum(scrollY) * (hasShiftDown() ? 10 : 1)), 1, 64);
 * }</pre>
 *
 * into the cell under the cursor (and once more into the "expected output" slot).
 * The 64 therefore bounds <em>one gauge's whole connection</em>, which is why
 * nine stacks of iron ingots needed nine gauges on the wall, and why a request
 * could never exceed a stack - so it could never be brought in line with a
 * production line's batch size.
 *
 * <h2>What this mixin does instead</h2>
 * The single number is replaced by the grid it was always implicitly drawn in.
 * Vanilla leaves one cell per connection; this mixin rewrites the client's
 * {@code connections} and {@code inputConfig} lists so that every cell holds one
 * stack and a connection carrying more than that owns several cells in a row:
 *
 * <ul>
 * <li>576 shows up as nine cells of 64 - one full package, the same amount nine
 *     separate gauges used to carry between them.</li>
 * <li>{@code mouseScrolled} is only redirected where the <em>value</em> is
 *     decided, and it reads its direction from the wheel delta rather than from
 *     that value - vanilla clamps it to 64, so "scrolled up on a full cell" and
 *     "did not scroll" would otherwise look identical. The notch moves the
 *     <em>connection's</em> amount and the cells are re-derived from it, so a
 *     cell fills to 64 before the next one appears and disappears again as the
 *     batch shrinks. Editing a single cell in isolation would open one more
 *     cell on every notch instead, covering the grid with near-empty cells.</li>
 * <li>Because the two lists stay index-aligned and one entry long per cell, the
 *     rest of vanilla needs no patches at all: rendering, tooltips, the
 *     left-click-disconnects-a-cell gesture and the recipe search in
 *     {@code searchForCraftingRecipe} all walk the grid exactly as they always
 *     did - they simply see more cells.</li>
 * </ul>
 *
 * <p>Since a package holds nine stacks, the grid is also the ceiling: a
 * connection may grow its number of cells, never its cell size, and once all
 * nine cells are taken the gauge refuses more. That is what keeps
 * "one gauge, one package" true, which is why connecting a tenth arrow can no
 * longer produce an order that does not fit.
 *
 * <p>The "expected output" slot is the one part that is <em>not</em> a grid - it
 * is a single number, so it has no cells to spill into and no package to stay
 * inside. Its ceiling is therefore its own config value,
 * {@code limits.maxRequestedOutput} (46656 by default, see
 * {@link FactoryGaugeImprove#maxRequestedOutput()}), so a single request may ask
 * for far more product than the input side could ever hold - one request, many
 * packages.
 *
 * <h2>Injection points</h2>
 * The amount arithmetic itself lives in {@link AmountStepping} and is verified
 * by a plain JVM test; this class only moves data around. All targets are
 * resolved by name and descriptor, and {@code require = 1} makes Mixin fail
 * loudly at load time if Create ever reshapes one of them, instead of silently
 * restoring the one-stack limit.
 */
@Mixin(value = FactoryPanelScreen.class, remap = false)
public abstract class FactoryPanelScreenMixin {

	private static final String SCROLL = "mouseScrolled(DDDD)Z";
	private static final String AMOUNT_FIELD = "Lcom/simibubi/create/content/logistics/BigItemStack;count:I";

	/** The connection behind each cell; one entry per grid cell after layout. */
	@Shadow private List<FactoryPanelConnection> connections;

	/** The cell amounts; one entry per grid cell after layout. */
	@Shadow private List<BigItemStack> inputConfig;

	/** In crafting mode the grid shows the recipe instead, and cells are not editable. */
	@Shadow private boolean craftingActive;

	/**
	 * True for a gauge placed on a Packager. It has no expected-output slot, but
	 * its input grid is the ordinary one, so it is laid out like any other gauge.
	 */
	@Shadow private boolean restocker;

	/** How many connections the current layout was built from; -1 when not laid out. */
	@Unique private int factorygaugeimprove$laidOutFor;

	/**
	 * The exact {@link #inputConfig} instance this mod installed, or null.
	 *
	 * <p>This is what makes the layout self-maintaining. Vanilla rebuilds both
	 * lists from scratch in {@code updateConfigs} whenever the set of connections
	 * changes, so "the field still holds my list" means "my layout is still in
	 * place" - and a different list means it has to be laid out again. Spilling
	 * and removing cells mutate the list in place, so they keep satisfying it.
	 */
	@Unique private List<BigItemStack> factorygaugeimprove$layout;

	/** Whether {@link #connections} and {@link #inputConfig} currently hold one entry per cell. */
	@Unique private boolean factorygaugeimprove$laidOut;

	/**
	 * The wheel delta of the {@code mouseScrolled} call in progress.
	 *
	 * <p>Needed because vanilla clamps the amount it is about to store back to 64:
	 * scrolling up on a full cell yields "64 again", the same value a missing
	 * notch would produce, so the stored number cannot say which way the wheel
	 * turned. The raw delta still can.
	 */
	@Unique private double factorygaugeimprove$scrollY;

	// ------------------------------------------------------------------
	// layout
	// ------------------------------------------------------------------

	/**
	 * Brings the layout up to date and remembers the wheel delta for the two
	 * amount stores further down the method.
	 *
	 * <p>The layout is refreshed here rather than only in a hook on
	 * {@code updateConfigs}, because this panel is a crowded place: several Create
	 * addons wrap {@code updateConfigs} and {@code mouseScrolled} as well, and an
	 * injection another mixin reshapes away disappears *silently* - no crash, no
	 * warning, the gauge simply keeps its one-number behaviour. This hook runs on
	 * every wheel notch, so it cannot be lost that way.
	 */
	@Inject(method = SCROLL, at = @At("HEAD"), require = 1)
	private void factorygaugeimprove$beforeScroll(double mouseX, double mouseY, double scrollX, double scrollY,
			CallbackInfoReturnable<Boolean> cir) {
		factorygaugeimprove$layOutIfNeeded("scroll");
		factorygaugeimprove$scrollY = scrollY;
	}

	/**
	 * Runs as soon as vanilla has rebuilt its per-connection lists - the earliest
	 * moment a layout can be built at all. It is not the only moment: see
	 * {@link #factorygaugeimprove$layOutIfNeeded(String)}.
	 */
	@Inject(method = "updateConfigs", at = @At("TAIL"), require = 0)
	private void factorygaugeimprove$afterUpdateConfigs(CallbackInfo ci) {
		factorygaugeimprove$spread("updateConfigs");
	}

	/**
	 * Keeps the layout current from the screen's own tick, so a gauge that already
	 * carried more than a stack when its panel is opened shows the spill right
	 * away instead of only after the first scroll.
	 *
	 * <p>Optional on purpose: it is a convenience, and losing it costs only that
	 * first frame - the wheel hook above still lays the grid out before any amount
	 * is read. {@code require = 0} means this save costs nothing rather than taking
	 * the game down with it.
	 */
	@Inject(method = "tick", at = @At("HEAD"), require = 0)
	private void factorygaugeimprove$layOutOnTick(CallbackInfo ci) {
		factorygaugeimprove$layOutIfNeeded("tick");
	}

	/**
	 * Spreads the connections over the grid unless the lists are still the ones
	 * this mod left, which is what makes the layout survive vanilla rebuilding
	 * them without anyone having to be told that it happened.
	 */
	@Unique
	private void factorygaugeimprove$layOutIfNeeded(String trigger) {
		if (this.connections == null || this.inputConfig == null
				|| this.inputConfig == factorygaugeimprove$layout) {
			return;
		}
		factorygaugeimprove$spread(trigger);
	}

	/**
	 * Gives every connection one cell per stack it asks for, in the order cells
	 * are drawn and hit-tested, and installs the result as the two lists the rest
	 * of the screen works from.
	 */
	@Unique
	private void factorygaugeimprove$spread(String trigger) {
		factorygaugeimprove$layout = this.inputConfig;
		factorygaugeimprove$laidOut = false;
		factorygaugeimprove$laidOutFor = -1;

		int connectionCount = Math.min(this.connections.size(), this.inputConfig.size());
		if (connectionCount == 0 || !FactoryGaugeImprove.spill()) {
			if (FactoryGaugeImprove.diagnostics()) {
				FactoryGaugeImprove.LOGGER.info(
						"[fgi] not laid out ({}): connections={} cells={} restocker={} spill={} crafting={}",
						trigger, this.connections.size(), this.inputConfig.size(), this.restocker,
						FactoryGaugeImprove.spill(), this.craftingActive);
			}
			return;
		}
		// Restockers are laid out like any other gauge on purpose. A gauge placed
		// on a Packager is flagged restocker by FactoryPanelBlockEntity, and it
		// differs from a normal one only in its background texture and in having no
		// expected-output slot ("if (!this.restocker)" guards that one write in
		// mouseScrolled). Its 3x3 input grid - same list, same cell hit boxes, same
		// store - is exactly the one this mod lays out, so skipping restockers
		// would leave the setups that feed a Packager on the old one-number path.

		int perCell = FactoryGaugeImprove.perCell();
		int budget = FactoryGaugeImprove.maxCells();
		List<FactoryPanelConnection> laidOutConnections = new ArrayList<>(budget);
		List<BigItemStack> laidOutCells = new ArrayList<>(budget);
		boolean dropped = false;

		for (int i = 0; i < connectionCount; i++) {
			FactoryPanelConnection connection = this.connections.get(i);
			BigItemStack config = this.inputConfig.get(i);
			int[] slices = AmountStepping.slices(config.count, perCell);
			if (laidOutCells.size() + slices.length > budget) {
				// Only reachable with an edited config or a save made by an older
				// version. Those cells stay hidden rather than being drawn outside
				// the panel; their amount is kept, it is simply not shown.
				dropped = true;
				continue;
			}
			for (int slice : slices) {
				laidOutConnections.add(connection);
				laidOutCells.add(new BigItemStack(config.stack, slice));
			}
		}

		this.connections = laidOutConnections;
		this.inputConfig = laidOutCells;
		factorygaugeimprove$layout = laidOutCells;
		factorygaugeimprove$laidOutFor = connectionCount;
		factorygaugeimprove$laidOut = true;

		if (FactoryGaugeImprove.diagnostics()) {
			FactoryGaugeImprove.LOGGER.info(
					"[fgi] laid out ({}): connections={} -> cells={} perCell={} maxCells={} crafting={}",
					trigger, connectionCount, laidOutCells.size(), perCell, budget, this.craftingActive);
		}

		if (dropped) {
			FactoryGaugeImprove.LOGGER.warn(
					"Gauge inputs need more than {} grid cells; the excess is not shown. Lower an amount, or raise limits.perCell.",
					budget);
			factorygaugeimprove$notify("create.factory_panel.fgi_grid_overflow");
		}
	}

	/**
	 * Vanilla rebuilds its lists in {@code tick} whenever
	 *
	 * <pre>{@code if (inputConfig.size() != behaviour.targetedBy.size()) { updateConfigs(); init(); }}</pre>
	 *
	 * The laid-out {@code inputConfig} is deliberately longer than the number of
	 * connections - it has one entry per <em>cell</em> - so a plain comparison
	 * would rebuild the panel every tick and re-run {@code init()} with it.
	 *
	 * <p>Only the left-hand side is redirected, and it reports the connection
	 * count the layout was built from. The comparison therefore turns back into
	 * its original meaning: "does the grid still match the connections?" A
	 * connection appearing or disappearing still rebuilds exactly once; a layout
	 * that is merely spread over more cells than connections does not rebuild at
	 * all. Answering with {@code inputConfig.size()} instead (the other side, the
	 * {@code Map.size()} call in the same expression) could not do that, since it
	 * is never equal to a connection count once anything has spilled.
	 */
	@Redirect(method = "tick", require = 1, at = @At(
			value = "INVOKE",
			target = "Ljava/util/List;size()I"))
	private int factorygaugeimprove$compareAgainstLaidOutSize(List<BigItemStack> cells) {
		return factorygaugeimprove$laidOut ? factorygaugeimprove$laidOutFor : cells.size();
	}

	/**
	 * One connection now owns several cells, so the amounts being sent have to be
	 * added back together - otherwise the last cell would overwrite the sum and
	 * the gauge would ask for a remainder instead of the whole batch.
	 *
	 * <p>Crafting mode is left alone: there the amount comes from the recipe
	 * arrangement and is the same for every cell of a connection.
	 */
	@Redirect(method = "sendIt", require = 1, at = @At(
			value = "INVOKE",
			target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object factorygaugeimprove$sendOneAmountPerConnection(Map<Object, Object> inputs, Object key, Object value) {
		if (factorygaugeimprove$laidOut && !this.craftingActive && value instanceof Integer amount) {
			Integer alreadyThere = (Integer) inputs.get(key);
			if (alreadyThere != null) {
				return inputs.put(key, alreadyThere + amount);
			}
		}
		return inputs.put(key, value);
	}

	// ------------------------------------------------------------------
	// scrolling
	// ------------------------------------------------------------------

	/**
	 * The cell store for an input: the only place where a number is decided.
	 *
	 * <p>Addressed by field ordinal 0 of {@code mouseScrolled}, the input store;
	 * ordinal 1 is the "expected output" slot right below.
	 *
	 * <p>{@code vanillaResult} is deliberately unused: it is vanilla's already
	 * clamped answer, which cannot distinguish "the wheel turned" from "it did
	 * not" at a full cell. The direction comes from the wheel instead.
	 *
	 * <p>The notch moves the whole connection, not just the cell under the
	 * cursor - see {@link AmountStepping#resize}. The cell is looked up only to
	 * find out <em>which</em> connection was scrolled.
	 */
	@Redirect(method = SCROLL, require = 1, at = @At(
			value = "FIELD",
			opcode = Opcodes.PUTFIELD,
			target = AMOUNT_FIELD,
			ordinal = 0))
	private void factorygaugeimprove$scrollInputCell(BigItemStack cell, int vanillaResult) {
		int direction = AmountStepping.direction(factorygaugeimprove$scrollY);

		if (!factorygaugeimprove$laidOut) {
			// Spilling turned off: the cell keeps its number, the ceiling is
			// simply raised. This is the behaviour of the previous version.
			int raised = AmountStepping.next(cell.count, direction, FactoryGaugeImprove.maxAmount(),
					factorygaugeimprove$step());
			if (FactoryGaugeImprove.diagnostics()) {
				FactoryGaugeImprove.LOGGER.info(
						"[fgi] input cell NOT laid out -> one number up to {}: scrollY={} {} -> {} (cells={})",
						FactoryGaugeImprove.maxAmount(), factorygaugeimprove$scrollY, cell.count, raised,
						this.connections.size());
			}
			cell.count = raised;
			return;
		}

		int index = factorygaugeimprove$indexOfCell(cell);
		if (index < 0) {
			cell.count = AmountStepping.next(cell.count, direction, FactoryGaugeImprove.perCell(),
					factorygaugeimprove$step());
			if (FactoryGaugeImprove.diagnostics()) {
				FactoryGaugeImprove.LOGGER.info("[fgi] input cell not in the grid -> kept as one cell, count={}",
						cell.count);
			}
			return;
		}

		int perCell = FactoryGaugeImprove.perCell();
		FactoryPanelConnection owner = this.connections.get(index);
		int first = index;
		int last = index;
		while (first > 0 && this.connections.get(first - 1) == owner) {
			first--;
		}
		while (last + 1 < this.connections.size() && this.connections.get(last + 1) == owner) {
			last++;
		}

		// The connection's amount is the sum of the cells it is currently shown
		// as; that is what the notch moves, and the cells are re-derived from it.
		int total = 0;
		for (int i = first; i <= last; i++) {
			total += this.inputConfig.get(i).count;
		}
		int owned = last - first + 1;
		int maxTotal = AmountStepping.maxAmountFor(perCell, FactoryGaugeImprove.maxCells(),
				this.inputConfig.size() - owned);

		AmountStepping.Layout layout = AmountStepping.scroll(total, factorygaugeimprove$scrollY,
				factorygaugeimprove$step(), perCell, maxTotal);
		factorygaugeimprove$applyLayout(first, last, owner, cell.stack, layout, total);

		if (layout.blocked) {
			// Wanted to grow past a full grid: every cell is taken.
			factorygaugeimprove$notify("create.factory_panel.fgi_grid_full");
		}
	}

	/**
	 * Writes a connection's cells back into the two lists, keeping them
	 * index-aligned and its own cells contiguous.
	 *
	 * <p>When the cell count does not change the amounts are written into the
	 * existing {@link BigItemStack}s, so nothing in the panel is recreated and
	 * the cell the player is pointing at keeps its identity. When it does change
	 * the connection's entries are replaced in place: the new objects go exactly
	 * where the old ones were, so every other connection keeps its cells and the
	 * grid stays packed in panel order.
	 *
	 * <p>Growing and shrinking are the same operation. That is what makes the
	 * cells fill in order - 64 then 72 stays two cells, 128 becomes two full
	 * ones - and what takes a cell away again when the batch shrinks below a
	 * multiple of the cell size.
	 */
	@Unique
	private void factorygaugeimprove$applyLayout(int first, int last, FactoryPanelConnection owner,
			ItemStack stack, AmountStepping.Layout layout, int previousTotal) {
		int owned = last - first + 1;
		if (layout.cells.length == owned) {
			for (int i = 0; i < owned; i++) {
				this.inputConfig.get(first + i).count = layout.cells[i];
			}
		} else {
			// Remove from the back so the earlier indices stay valid.
			for (int i = last; i >= first; i--) {
				this.inputConfig.remove(i);
				this.connections.remove(i);
			}
			for (int i = 0; i < layout.cells.length; i++) {
				this.inputConfig.add(first + i, new BigItemStack(stack, layout.cells[i]));
				this.connections.add(first + i, owner);
			}
		}

		if (FactoryGaugeImprove.diagnostics()) {
			FactoryGaugeImprove.LOGGER.info(
					"[fgi] connection cells {}..{}: scrollY={} step={} total {} -> {} cells={} gridCells={}",
					first, last, factorygaugeimprove$scrollY, factorygaugeimprove$step(), previousTotal,
					layout.total, Arrays.toString(layout.cells), this.inputConfig.size());
		}
	}

	/**
	 * The "expected output" store: how much product one request asks for.
	 *
	 * <p>It is a single slot rather than a grid, so it keeps one number and has
	 * no cells to spill into - its ceiling is {@code limits.maxRequestedOutput}
	 * (46656 by default), which is what raises "one request" from one stack to
	 * an order far larger than the input side could ever carry.
	 */
	@Redirect(method = SCROLL, require = 1, at = @At(
			value = "FIELD",
			opcode = Opcodes.PUTFIELD,
			target = AMOUNT_FIELD,
			ordinal = 1))
	private void factorygaugeimprove$scrollOutputAmount(BigItemStack output, int vanillaResult) {
		int raised = AmountStepping.next(output.count, AmountStepping.direction(factorygaugeimprove$scrollY),
				FactoryGaugeImprove.maxRequestedOutput(), factorygaugeimprove$step());
		if (FactoryGaugeImprove.diagnostics()) {
			FactoryGaugeImprove.LOGGER.info(
					"[fgi] OUTPUT slot scrolled (single number, no cells to spill into): scrollY={} {} -> {} (ceiling {})",
					factorygaugeimprove$scrollY, output.count, raised, FactoryGaugeImprove.maxRequestedOutput());
		}
		output.count = raised;
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	@Unique
	private int factorygaugeimprove$indexOfCell(BigItemStack cell) {
		for (int i = 0; i < this.inputConfig.size(); i++) {
			if (this.inputConfig.get(i) == cell) {
				return i;
			}
		}
		return -1;
	}

	/** No assumption is made about vanilla's step size or its clamp, only about the wheel. */
	@Unique
	private static int factorygaugeimprove$step() {
		return AmountStepping.stepFor(
				Screen.hasControlDown(),
				Screen.hasShiftDown(),
				FactoryGaugeImprove.ctrlStep(),
				FactoryGaugeImprove.shiftStep());
	}

	@Unique
	private static void factorygaugeimprove$notify(String translationKey) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.displayClientMessage(
					Component.translatable(translationKey).withStyle(ChatFormatting.RED), true);
		}
	}
}
