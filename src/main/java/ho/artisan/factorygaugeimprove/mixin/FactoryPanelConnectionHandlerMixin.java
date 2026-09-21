package ho.artisan.factorygaugeimprove.mixin;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnectionHandler;
import ho.artisan.factorygaugeimprove.AmountStepping;
import ho.artisan.factorygaugeimprove.FactoryGaugeImprove;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps vanilla's "at most nine arrows" rule exactly as wide as the grid behind
 * it, and no wider.
 *
 * <h2>Which side is which</h2>
 *
 * {@code checkForIssues(FactoryPanelBehaviour from, FactoryPanelBehaviour to)}
 * is the vanilla guard that decides whether a new arrow may be laid. Both names
 * are taken from the call site in {@code panelClicked}:
 *
 * <pre>{@code
 * FactoryPanelBehaviour from = FactoryPanelBehaviour.at(level, connectingFrom);
 * String issue = checkForIssues(from, panel);          // panel == the one clicked
 * new FactoryPanelConnectionPacket(panel.getPanelPosition(), connectingFrom, false);
 * }</pre>
 *
 * <p>{@code from} is the gauge the player clicked <em>first</em>, {@code to} the
 * one under the cursor. {@code to} is the side that stores the arrow - on the
 * server, {@code FactoryPanelConnectionPacket.applySettings} calls
 * {@code to.addConnection(from.getPanelPosition())} - and every vanilla test
 * inside {@code checkForIssues} is written against {@code from}:
 * {@code from.targetedBy.containsKey(to.getPanelPosition())} for
 * {@code already_connected} and {@code from.targetedBy.size() >= 9} for
 * {@code cannot_add_more_inputs}.
 *
 * <h2>Which side is a gauge's own grid</h2>
 *
 * {@code targetedBy} is keyed by {@code FactoryPanelConnection.from} - the
 * source of each arrow, see {@code FactoryPanelBehaviour.at(level, connection)}
 * and {@code addConnection}, which puts a connection keyed on the position it
 * was given. So {@code from.targetedBy} is the set of gauges {@code from}
 * <em>draws from</em>: the arrows leaving it, i.e. one entry per connection that
 * gauge has fed. That is the count vanilla caps at nine, and it is <em>not</em>
 * the gauge's own input grid.
 *
 * <p>A gauge's own 3x3 grid lives on whichever gauge the player installed the
 * connection into, and each cell is one {@code FactoryPanelConnection.amount} of
 * a connection keyed by its source. It is therefore exactly the {@code to} of
 * this method, and the cells it contains are what the rest of this mod lays out
 * and scrolls. Being pointed at is not a grid and must not cost a cell: an input
 * keeps the cells it owns, and a connection is never refused because of arrows
 * somebody else drew.
 *
 * <h2>What this mod changes</h2>
 *
 * Nothing about the number nine: nine arrows fed by one gauge is nine cells of
 * its counterpart's grid, which is one package, which is the invariant the whole
 * mod exists to keep. What changes is the <em>unit</em>. Vanilla assumed one
 * arrow is one cell, so it counted arrows; here an arrow may carry several
 * stacks and own several cells, so the cap is measured in cells. Both tests are
 * re-expressed that way, against the grid that is actually being filled -
 * {@code to}'s - because that is the grid the player is looking at.
 */
@Mixin(value = FactoryPanelConnectionHandler.class, remap = false)
public class FactoryPanelConnectionHandlerMixin {

	private static final String CHECK_FOR_ISSUES = "checkForIssues("
			+ "Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;"
			+ "Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;"
			+ ")Ljava/lang/String;";

	/**
	 * Re-expresses vanilla's two {@code targetedBy} tests in cells, against the
	 * gauge whose grid the new arrow would occupy.
	 *
	 * <p>{@code to} is the receiving gauge, so {@code to.targetedBy} is the set
	 * of arrows pointing at it: the inputs shown in its 3x3 grid. Counting each
	 * one's cells is the honest version of "does this grid still have room", and
	 * the hook keeps vanilla's {@code already_connected} verbatim - the incoming
	 * source must not already be present, which is what stops the same source
	 * from being connected twice, and what leaves the grid's width for other
	 * sources untouched.
	 *
	 * <p>{@code cancel} because vanilla's own versions of both are wrong here:
	 * its {@code size() >= 9} counts arrows where cells is the unit, so a grid
	 * holding 4 connections of 200 items each would still look half empty while
	 * describing an order no package could carry.
	 *
	 * <p>The refusal is returned rather than applied - nothing is changed behind
	 * the player's back, they are told which input does not fit, and the layout
	 * already on screen never shrinks on its own.
	 */
	@Inject(method = CHECK_FOR_ISSUES, at = @At("HEAD"), cancellable = true)
	private static void factorygaugeimprove$checkInCells(FactoryPanelBehaviour from, FactoryPanelBehaviour to,
			CallbackInfoReturnable<String> cir) {
		if (from == null || to == null || !FactoryGaugeImprove.spill()) {
			return;
		}

		// Vanilla's own duplicate test, kept as it is: one source, one arrow.
		if (to.targetedBy.containsKey(from.getPanelPosition())) {
			cir.setReturnValue("factory_panel.already_connected");
			return;
		}

		int perCell = FactoryGaugeImprove.perCell();
		int used = 0;
		for (FactoryPanelConnection connection : to.targetedBy.values()) {
			used += AmountStepping.cells(connection.amount, perCell);
		}

		if (!AmountStepping.fitsGrid(used, FactoryGaugeImprove.maxCells())) {
			if (FactoryGaugeImprove.diagnostics()) {
				FactoryGaugeImprove.LOGGER.info(
						"[fgi] refusing a connection: that grid already spends {} of {} cells",
						used, FactoryGaugeImprove.maxCells());
			}
			cir.setReturnValue("factory_panel.fgi_no_free_cell");
		}
	}

	/**
	 * Vanilla's ninth-arrow ceiling, re-aimed at the side that owns the grid.
	 *
	 * <p>It reads {@code from.targetedBy.size()} in Create's bytecode and refuses
	 * the tenth arrow. Left alone it would cap the number of arrows a single
	 * gauge may feed - a real strategy limit, but the opposite of what this hook
	 * is here for, and one the mod's own cell accounting already subsumes: the
	 * cells a source spends are counted on the receiving grid, so a source is
	 * bounded by the grids it feeds rather than by an arrow count of its own.
	 *
	 * <p>So the count is taken from {@code to} instead, where it still says
	 * something useful: a grid with {@code maxCells} cells can only ever hold
	 * {@code maxCells} connections, which is exactly the ceiling a
	 * cell-accurate last-resort check needs. It cannot refuse a connection the
	 * hook above accepted - an amount occupies at least one cell, so
	 * {@code used < maxCells} already implies {@code to.targetedBy.size() <
	 * maxCells}, leaving room for one more. What it catches is the case the hook
	 * above treats as free: a malformed or hand-edited save holding more
	 * connections than the grid has cells.
	 *
	 * <p>Optional on purpose. The hook above is what enforces the rule and
	 * produces the message the player sees; losing this one changes nothing.
	 */
	@Redirect(method = CHECK_FOR_ISSUES, require = 0, at = @At(
			value = "INVOKE",
			target = "Ljava/util/Map;size()I"))
	private static int factorygaugeimprove$countAgainstTheGrid(Map<?, ?> ignored) {
		return FactoryGaugeImprove.maxCells();
	}
}
