package ho.artisan.factorygaugeimprove.mixin;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnectionHandler;
import ho.artisan.factorygaugeimprove.AmountStepping;
import ho.artisan.factorygaugeimprove.FactoryGaugeImprove;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-expresses vanilla's input-grid ceiling in cells instead of arrows.
 *
 * <h2>Which side is which</h2>
 *
 * <p>{@code checkForIssues(FactoryPanelBehaviour from, FactoryPanelBehaviour to)}
 * is the vanilla guard that decides whether a new connection may be laid. Both
 * names come from its only caller, {@code panelClicked}:
 *
 * <pre>{@code
 * FactoryPanelBehaviour at = FactoryPanelBehaviour.at(level, connectingFrom);
 * String issue = checkForIssues(at, panel);   // panel == the gauge under the cursor
 * }</pre>
 *
 * <p>{@code from} is the gauge the player clicked <em>first</em>, {@code to} the
 * second one. That is the whole story for this method, and it is worth spelling
 * out because the network packet names the same two gauges the other way round:
 * {@code panelClicked} sends
 * {@code new FactoryPanelConnectionPacket(panel.getPanelPosition(), connectingFrom, false)},
 * whose constructor is {@code (fromPos, toPos, relocate)} - so the packet's
 * {@code toPos} is this method's {@code from}, and its {@code fromPos} is this
 * method's {@code to}.
 *
 * <p>The server side then lands on the first-clicked gauge:
 * {@code applySettings} looks up {@code toPos} and calls
 * {@code behaviour.addConnection(fromPos)}. So it is {@code from} that receives
 * the connection, {@code from} that stores it, and {@code from.targetedBy} that
 * is the first-clicked gauge's own set of inputs. Every vanilla test in this
 * method is read off {@code from} for exactly that reason, and none of them
 * should be moved to {@code to} - doing so would let one gauge's input count
 * refuse a connection into a different, empty gauge.
 *
 * <h2>What {@code targetedBy} holds</h2>
 *
 * <p>{@code addConnection(fromPos)} does
 * {@code this.targetedBy.put(fromPos, new FactoryPanelConnection(fromPos, 1))},
 * so the map is keyed by the <em>source</em> of each connection - the gauge the
 * arrow comes from - and valued by that connection's amount. It is the receiving
 * gauge's list of inputs, and it is the grid the player sees in the panel's 3x3
 * area:
 *
 * <ul>
 * <li>{@code from.targetedBy} - the inputs of the first-clicked gauge. This is
 *     the grid being filled by the connection under test.</li>
 * <li>{@code from.targeting} - the gauges it feeds, updated on the same line
 *     ({@code source.targeting.add(...)}, where {@code source} is
 *     {@code at(fromPos)}). Feeding others is not limited here in any way.</li>
 * </ul>
 *
 * <h2>What this mod changes</h2>
 *
 * <p>Only the unit. Vanilla counts one arrow per input and refuses the tenth,
 * which is right while one input carries one stack. Here an input may carry
 * several stacks ({@link AmountStepping#cells}), so the same rule is expressed
 * in the cells those amounts actually occupy: the grid holds
 * {@code maxCells} cells, and a new connection is refused when the inputs
 * already claim all of them. At one stack per input the two formulations agree,
 * so nine inputs remain nine inputs; what changes is that one input carrying a
 * whole package is now seen as the full grid it is.
 *
 * <p>Nothing is capped in the other direction: how many gauges a gauge feeds
 * ({@code from.targeting}) is not consulted, and being connected by somebody
 * else never takes a cell away from a gauge's own inputs.
 *
 * <p>Vanilla's own {@code Map.size() >= 9} test is left in place and only runs
 * when this hook steps aside (see {@link FactoryGaugeImprove#spill()}), where
 * one connection per cell is the rule anyway and an arrow count is the right
 * measure of it.
 */
@Mixin(value = FactoryPanelConnectionHandler.class, remap = false)
public class FactoryPanelConnectionHandlerMixin {

	private static final String CHECK_FOR_ISSUES = "checkForIssues("
			+ "Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;"
			+ "Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;"
			+ ")Ljava/lang/String;";

	/**
	 * Refuses a new connection when the receiving gauge's own grid has no cell
	 * left for it.
	 *
	 * <p>{@code from} is the gauge that will hold the connection (see the class
	 * notes), so its {@code targetedBy} is the set of inputs already shown in its
	 * grid. Each of those claims {@code cells(amount, perCell)} cells, and
	 * {@link AmountStepping#fitsGrid} answers whether one more cell is still
	 * free.
	 *
	 * <p>The duplicate test comes first and is vanilla's own, unchanged in
	 * meaning: a source that is already an input of this gauge must not be added
	 * twice, or one gauge could fill the grid with repeats of itself. Note that
	 * it reads {@code from.targetedBy} - the receiving side - for the same reason
	 * as everything else here; checking {@code to} instead would ask whether the
	 * <em>other</em> gauge already feeds from this one, which is a different
	 * question and not a reason to refuse.
	 *
	 * <p>Returning the message rather than applying anything keeps the player in
	 * charge: nothing is written, the grid on screen keeps the shape it had, and
	 * the refusal names the panel whose inputs are full.
	 */
	@Inject(method = CHECK_FOR_ISSUES, at = @At("HEAD"), cancellable = true)
	private static void factorygaugeimprove$checkInCells(FactoryPanelBehaviour from, FactoryPanelBehaviour to,
			CallbackInfoReturnable<String> cir) {
		if (from == null || to == null || !FactoryGaugeImprove.spill()) {
			return;
		}

		// Vanilla's own duplicate test, kept as it is: one source, one input.
		if (from.targetedBy.containsKey(to.getPanelPosition())) {
			cir.setReturnValue("factory_panel.already_connected");
			return;
		}

		int perCell = FactoryGaugeImprove.perCell();
		int used = 0;
		for (FactoryPanelConnection connection : from.targetedBy.values()) {
			used += AmountStepping.cells(connection.amount, perCell);
		}

		if (!AmountStepping.fitsGrid(used, FactoryGaugeImprove.maxCells())) {
			if (FactoryGaugeImprove.diagnostics()) {
				FactoryGaugeImprove.LOGGER.info(
						"[fgi] refusing a connection: that gauge's inputs already spend {} of {} cells",
						used, FactoryGaugeImprove.maxCells());
			}
			cir.setReturnValue("factory_panel.fgi_no_free_cell");
		}
	}
}
