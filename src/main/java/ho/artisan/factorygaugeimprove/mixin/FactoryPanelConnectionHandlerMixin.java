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
 * Keeps a gauge's inputs inside one package when a new arrow is connected.
 *
 * <p>Vanilla refuses the tenth arrow ({@code cannot_add_more_inputs}), which
 * mirrors one package holding nine stacks as long as every arrow carries a
 * single stack. This mod lets an arrow carry several stacks - as several cells
 * of the same 3x3 grid - so the rule that used to be "at most nine arrows" is
 * now "at most nine occupied cells", and it has to be checked here: the counts
 * live on the connections, which is exactly what {@link FactoryPanelBehaviour
 * #targetedBy} holds.
 *
 * <p>Refusing the connection rather than quietly shrinking an existing amount
 * keeps the player in control: nothing changes behind their back, they are told
 * why, and they decide which input gives up a cell.
 */
@Mixin(value = FactoryPanelConnectionHandler.class, remap = false)
public class FactoryPanelConnectionHandlerMixin {

	private static final String CHECK_FOR_ISSUES = "checkForIssues("
			+ "Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;"
			+ "Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;"
			+ ")Ljava/lang/String;";

	/**
	 * @param from the gauge the arrow starts at, {@code to} the one it points to
	 *             (the one that gains a cell)
	 */
	@Inject(method = CHECK_FOR_ISSUES, at = @At("HEAD"), cancellable = true)
	private static void factorygaugeimprove$refuseWhenGridIsFull(FactoryPanelBehaviour from, FactoryPanelBehaviour to,
			CallbackInfoReturnable<String> cir) {
		if (to == null || !FactoryGaugeImprove.spill()) {
			return;
		}

		int used = 0;
		int perCell = FactoryGaugeImprove.perCell();
		for (FactoryPanelConnection connection : to.targetedBy.values()) {
			used += AmountStepping.cells(connection.amount, perCell);
		}

		if (!AmountStepping.fitsGrid(used, FactoryGaugeImprove.maxCells())) {
			if (FactoryGaugeImprove.diagnostics()) {
				FactoryGaugeImprove.LOGGER.info(
						"[fgi] refusing a connection: {} of {} cells are taken", used,
						FactoryGaugeImprove.maxCells());
			}
			cir.setReturnValue("factory_panel.fgi_no_free_cell");
		}
	}
}
