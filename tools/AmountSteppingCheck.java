import ho.artisan.factorygaugeimprove.AmountStepping;
import java.util.Arrays;

/**
 * Standalone check for the only piece of real logic in the mod.
 *
 * <p>The mod's Create dependency is compileOnly, so the game cannot be launched
 * from the dev environment; this harness is how AmountStepping gets verified.
 *
 * <p>Run from the project root:
 * <pre>
 *   gradlew.bat checkAmounts
 * </pre>
 * or by hand:
 * <pre>
 *   javac -d build/tools-classes -cp build/classes/java/main tools/AmountSteppingCheck.java
 *   java -cp build/tools-classes;build/classes/java/main AmountSteppingCheck
 * </pre>
 */
public final class AmountSteppingCheck {

	private static int checks = 0;
	private static int failures = 0;

	public static void main(String[] args) {
		int ctrl = 64;
		int shift = 8;
		int perCell = 64;
		int budget = 9;
		int max = perCell * budget;

		scrolling(ctrl, shift, max);
		reachability(max, ctrl);
		gridLayout(perCell, budget);
		fillOrder(perCell, budget, ctrl, shift);
		ceiling(perCell, budget, ctrl, shift);
		requestedOutput(ctrl, shift);
		saturation(perCell, budget, ctrl, shift);
		degenerate(max);

		System.out.printf("%d checks, %d failures%n", checks, failures);
		if (failures > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------

	private static void scrolling(int ctrl, int shift, int max) {
		// --- plain scrolling still behaves like vanilla, minus the ceiling ---
		expect(2, AmountStepping.next(1, +1, max, 1), "fine up");
		expect(1, AmountStepping.next(2, -1, max, 1), "fine down");
		expect(1, AmountStepping.next(1, -1, max, 1), "fine down clamps at 1");
		expect(576, AmountStepping.next(576, +1, max, 1), "fine up clamps at max");
		expect(64, AmountStepping.next(63, +1, 64, 1), "a single cell still clamps at its size");

		// --- no scroll must not move anything, but must repair out-of-range values ---
		expect(300, AmountStepping.next(300, 0, max, 1), "no direction keeps value");
		expect(1, AmountStepping.next(0, 0, max, 1), "no direction repairs a zero");
		expect(576, AmountStepping.next(9999, 0, max, 1), "no direction repairs an oversized value");

		// --- coarse scrolling snaps to multiples so exact amounts stay reachable ---
		expect(64, AmountStepping.next(1, +1, max, ctrl), "ctrl from 1 -> 64");
		expect(128, AmountStepping.next(64, +1, max, ctrl), "ctrl 64 -> 128");
		expect(512, AmountStepping.next(576, -1, max, ctrl), "ctrl down from a multiple");
		expect(504, AmountStepping.next(508, -1, max, shift), "shift down from a non-multiple");
		expect(1, AmountStepping.next(1, -1, max, ctrl), "ctrl down from 1 stays at 1");
		expect(64, AmountStepping.next(64, +1, 64, ctrl), "ctrl up at a cell's ceiling stays at 64");

		// --- modifier resolution ---
		expect(1, AmountStepping.stepFor(false, false, ctrl, shift), "no modifier -> fine");
		expect(8, AmountStepping.stepFor(false, true, ctrl, shift), "shift -> shift step");
		expect(64, AmountStepping.stepFor(true, false, ctrl, shift), "ctrl -> ctrl step");
		expect(64, AmountStepping.stepFor(true, true, ctrl, shift), "ctrl wins over shift");
	}

	private static void reachability(int max, int ctrl) {
		// every amount must stay reachable: coarse steps land on multiples, fine
		// scrolling covers everything in between
		for (int target : new int[]{9, 100, 128, 576, 300}) {
			int value = 1;
			if (target > 1) {
				value = AmountStepping.next(1, +1, max, ctrl);
			}
			while (value > target) {
				value = AmountStepping.next(value, -1, max, ctrl);
			}
			while (value < target) {
				value = AmountStepping.next(value, +1, max, 1);
			}
			expect(target, value, "reachable: " + target);
		}
	}

	private static void gridLayout(int perCell, int budget) {
		// --- how many cells an amount needs ---
		expect(1, AmountStepping.cells(0, perCell), "cells(0) = 1, a connection always has its cell");
		expect(1, AmountStepping.cells(1, perCell), "cells(1)");
		expect(1, AmountStepping.cells(64, perCell), "cells(64) = one full cell");
		expect(2, AmountStepping.cells(65, perCell), "cells(65) = 64 + 1");
		expect(2, AmountStepping.cells(100, perCell), "cells(100)");
		expect(9, AmountStepping.cells(576, perCell), "cells(576) = the whole grid");

		// --- the layout itself: whole cells first, remainder last ---
		expectInts(new int[]{0}, AmountStepping.slices(0, perCell), "slices(0)");
		expectInts(new int[]{64}, AmountStepping.slices(64, perCell), "slices(64)");
		expectInts(new int[]{64, 1}, AmountStepping.slices(65, perCell), "slices(65)");
		expectInts(new int[]{64, 36}, AmountStepping.slices(100, perCell), "slices(100)");
		expect(9, AmountStepping.slices(576, perCell).length, "slices(576) = nine cells");
		expectInts(new int[]{64, 64, 64, 64, 64, 64, 64, 64, 64},
				AmountStepping.slices(576, perCell), "slices(576) = nine full cells");

		// the slices must always add back up to the amount they came from
		for (int amount : new int[]{0, 1, 63, 64, 65, 127, 128, 129, 300, 575, 576}) {
			int[] slices = AmountStepping.slices(amount, perCell);
			int sum = Arrays.stream(slices).sum();
			expect(amount, sum, "slices sum back to " + amount);
			if (slices.length > 1) {
				expect(amount, slices[0] * (slices.length - 1) + slices[slices.length - 1],
						"only the last slice may be partial (" + amount + ")");
			}
		}

		// --- "packed" as an exhaustive property, not a few spot values ---
		boolean packed = true;
		for (int amount = 1; amount <= perCell * budget; amount++) {
			int[] slices = AmountStepping.slices(amount, perCell);
			packed &= sum(slices) == amount
					&& slices.length == AmountStepping.cells(amount, perCell)
					&& slices.length <= budget;
			for (int i = 0; i < slices.length - 1; i++) {
				packed &= slices[i] == perCell;
			}
		}
		expect(true, packed, "every amount 1..576 slices into packed cells inside the grid");

		// --- the ceiling each connection gets, given what the others occupy ---
		expect(576, AmountStepping.maxAmountFor(perCell, budget, 0), "lone connection: nine stacks");
		expect(512, AmountStepping.maxAmountFor(perCell, budget, 1), "one cell taken: eight stacks");
		expect(64, AmountStepping.maxAmountFor(perCell, budget, 8), "eight cells taken: one stack");
		expect(64, AmountStepping.maxAmountFor(perCell, budget, 9), "full grid: still one cell");
		expect(1024, AmountStepping.maxAmountFor(128, budget, 1), "a bigger cell carries more");

		// --- refusing an extra connection ---
		expect(true, AmountStepping.fitsGrid(8, budget), "8 cells used, a ninth arrow fits");
		expect(false, AmountStepping.fitsGrid(9, budget), "a full grid refuses more arrows");
		expect(true, AmountStepping.fitsGrid(0, budget), "an empty grid always fits one");
	}

	/**
	 * How a batch fills the grid, which is the whole point of the mod: a cell
	 * must reach 64 before the next one appears.
	 *
	 * <p>Replayed through the same entry point the screen calls, because the
	 * first version of this feature moved one cell in isolation and therefore
	 * opened a fresh cell on every notch: scrolling with Shift from a full cell
	 * covered the grid with nine cells of eight instead of reaching 128, which is
	 * two full cells. The contrast is pinned here - the same eight notches have
	 * to end at 128 shown as two cells, and must never need more than two.
	 */
	private static void fillOrder(int perCell, int budget, int ctrl, int shift) {
		int max = perCell * budget;

		// --- Shift, from a fresh gauge: eight notches per cell, in order ---
		int total = 1;
		total = expectStep(total, +1, shift, perCell, max, "8|[8]|open", "shift 1/8");
		total = expectStep(total, +1, shift, perCell, max, "16|[16]|open", "shift 2/8");
		total = expectStep(total, +1, shift, perCell, max, "24|[24]|open", "shift 3/8");
		total = expectStep(total, +1, shift, perCell, max, "32|[32]|open", "shift 4/8");
		total = expectStep(total, +1, shift, perCell, max, "40|[40]|open", "shift 5/8");
		total = expectStep(total, +1, shift, perCell, max, "48|[48]|open", "shift 6/8");
		total = expectStep(total, +1, shift, perCell, max, "56|[56]|open", "shift 7/8");
		total = expectStep(total, +1, shift, perCell, max, "64|[64]|open", "shift 8/8: one full cell");

		// the ninth notch opens the second cell, and only 8 of it
		total = expectStep(total, +1, shift, perCell, max, "72|[64, 8]|open", "a ninth shift notch opens the next cell");
		expect(2, AmountStepping.slices(total, perCell).length, "and that is still only two cells");

		// Reaching the second stack takes eight more notches - one per 8 items,
		// not one per cell. Counted rather than hard-coded, so the assertion says
		// exactly what a player experiences: sixteen Shift notches from a fresh
		// gauge, ending on two cells of 64.
		int notches = 9;
		while (total < perCell * 2) {
			total = AmountStepping.resize(total, +1, shift, perCell, max).total;
			notches++;
		}
		expect(16, notches, "two full stacks take sixteen shift notches from a fresh gauge");
		expect(perCell * 2, total, "and land exactly on 128");
		expect(2, AmountStepping.slices(total, perCell).length, "shown as two full cells, not nine near-empty ones");

		// --- and the same run must never occupy more cells than the amount needs ---
		total = 1;
		int widest = 1;
		boolean neverWiderThanNeeded = true;
		for (int notch = 0; notch < 40; notch++) {
			AmountStepping.Layout layout = AmountStepping.resize(total, +1, shift, perCell, max);
			total = layout.total;
			widest = Math.max(widest, layout.cellsUsed());
			neverWiderThanNeeded &= layout.cellsUsed() == AmountStepping.cells(total, perCell);
		}
		expect(320, total, "forty shift notches reach 320");
		expect(5, widest, "which needs five cells");
		expect(true, neverWiderThanNeeded, "and no notch ever used more cells than the amount needs");

		// --- Ctrl: one notch per stack, nine notches to a whole package ---
		total = 1;
		for (int notch = 1; notch <= 9; notch++) {
			AmountStepping.Layout layout = AmountStepping.resize(total, +1, ctrl, perCell, max);
			total = layout.total;
			expect(perCell * notch, total, "ctrl notch " + notch + " -> " + (perCell * notch));
			expect(notch, layout.cellsUsed(), "ctrl notch " + notch + " occupies " + notch + " cell(s)");
		}
		expectLayout("576|[64, 64, 64, 64, 64, 64, 64, 64, 64]|open",
				AmountStepping.resize(512, +1, ctrl, perCell, max), "nine ctrl notches = one whole package");

		// --- and it comes back down the same way, giving cells back ---
		total = 576;
		for (int notch = 8; notch >= 1; notch--) {
			AmountStepping.Layout layout = AmountStepping.resize(total, -1, ctrl, perCell, max);
			total = layout.total;
			expect(perCell * notch, total, "ctrl down -> " + (perCell * notch));
			expect(notch, layout.cellsUsed(), "ctrl down leaves " + notch + " cell(s)");
		}
		expectLayout("1|[1]|open", AmountStepping.resize(total, -1, ctrl, perCell, max),
				"ctrl down off the last stack keeps the connection, at 1");

		// --- fine scrolling grows the trailing cell one at a time ---
		expectLayout("65|[64, 1]|open", AmountStepping.resize(64, +1, 1, perCell, max), "fine up past a full cell");
		expectLayout("66|[64, 2]|open", AmountStepping.resize(65, +1, 1, perCell, max), "and one more");
		expectLayout("64|[64]|open", AmountStepping.resize(65, -1, 1, perCell, max), "fine down takes the cell back");
		expectLayout("63|[63]|open", AmountStepping.resize(64, -1, 1, perCell, max), "fine down inside one cell");
	}

	/**
	 * The grid is the ceiling: a connection may grow its number of cells, never
	 * its cell size, and once the free cells are used up the notch stops instead
	 * of quietly describing an order that no longer fits into one package.
	 */
	private static void ceiling(int perCell, int budget, int ctrl, int shift) {
		int max = perCell * budget;

		// --- a lone connection may fill the whole grid, and then stops ---
		expectLayout("576|[64, 64, 64, 64, 64, 64, 64, 64, 64]|blocked",
				AmountStepping.resize(576, +1, ctrl, perCell, max), "a full grid refuses to grow");
		expectLayout("576|[64, 64, 64, 64, 64, 64, 64, 64, 64]|blocked",
				AmountStepping.resize(576, +1, 1, perCell, max), "even a fine notch is refused");
		expectLayout("576|[64, 64, 64, 64, 64, 64, 64, 64, 64]|blocked",
				AmountStepping.resize(576, +1, shift, perCell, max), "so is a coarse one");
		expectLayout("512|[64, 64, 64, 64, 64, 64, 64, 64]|open",
				AmountStepping.resize(576, -1, ctrl, perCell, max), "scrolling down is never blocked");

		// --- eight cells, but a ninth cell belongs to someone else ---
		int capped = AmountStepping.maxAmountFor(perCell, budget, 1);
		expect(512, capped, "one cell taken leaves eight stacks");
		expectLayout("512|[64, 64, 64, 64, 64, 64, 64, 64]|blocked",
				AmountStepping.resize(512, +1, ctrl, perCell, capped), "the capped connection stops at its ceiling");
		expectLayout("512|[64, 64, 64, 64, 64, 64, 64, 64]|blocked",
				AmountStepping.resize(512, +1, 1, perCell, capped), "and a fine notch cannot sneak past it");
		expectLayout("512|[64, 64, 64, 64, 64, 64, 64, 64]|open",
				AmountStepping.resize(448, +1, ctrl, perCell, capped), "up to the ceiling is allowed");
		expectLayout("64|[64]|blocked", AmountStepping.resize(64, +1, 1, perCell, perCell),
				"a connection down to its last cell cannot grow either");

		// --- one cell is the floor even when there is no room at all ---
		expectLayout("64|[64]|blocked", AmountStepping.resize(64, +1, ctrl, perCell, 0),
				"a grid with no free cell still shows the connection's own stack");
		expectLayout("1|[1]|open", AmountStepping.resize(64, -1, ctrl, perCell, 0),
				"and it can still be scrolled back down");

		// --- an amount already over the ceiling is brought back to it ---
		expectLayout("576|[64, 64, 64, 64, 64, 64, 64, 64, 64]|open",
				AmountStepping.resize(9999, 0, 1, perCell, max), "a stale oversized amount is repaired, not kept");
	}

	/**
	 * The "expected output" ceiling: how much product one request may ask for.
	 *
	 * <p>That slot is not a grid - it is one number, so it has no cell to spill
	 * into and no package to stay inside. Its ceiling is a config value of its
	 * own ({@code limits.maxRequestedOutput}), 46656 by default, and the checks
	 * below pin that default's arithmetic: it is exactly landable with both
	 * coarse steps, it is never overshot, and a stale larger amount is repaired
	 * back down to it.
	 */
	private static void requestedOutput(int ctrl, int shift) {
		int ceiling = 46_656;

		// --- the default is a whole number of both coarse steps, so it is landable ---
		expect(0, ceiling % ctrl, "the request ceiling is a whole number of ctrl steps");
		expect(0, ceiling % shift, "and a whole number of shift steps");
		expect(729, ceiling / ctrl, "which is 729 ctrl notches, one per stack, from 1");

		// --- ctrl: one notch per stack, exactly onto the ceiling and no further ---
		int total = 1;
		for (int notch = 0; notch < ceiling / ctrl; notch++) {
			total = AmountStepping.next(total, +1, ceiling, ctrl);
		}
		expect(ceiling, total, "729 ctrl notches from 1 land exactly on the ceiling");
		expect(ceiling, AmountStepping.next(ceiling - 1, +1, ceiling, ctrl),
				"the last ctrl notch lands on the ceiling, never above it");
		expect(ceiling, AmountStepping.next(ceiling, +1, ceiling, ctrl), "a ctrl notch at the ceiling stays there");
		expect(ceiling, AmountStepping.next(ceiling, +1, ceiling, 1), "so does a fine notch");
		expect(ceiling, AmountStepping.next(ceiling, +1, ceiling, shift), "and a shift notch");
		expect(ceiling - ctrl, AmountStepping.next(ceiling, -1, ceiling, ctrl),
				"a ctrl notch down gives a whole stack back");

		// --- shift: eight notches per stack ---
		total = 1;
		int notches = 0;
		while (total < ceiling && notches < 100_000) {
			total = AmountStepping.next(total, +1, ceiling, shift);
			notches++;
		}
		expect(ceiling, total, "shift notches also reach the ceiling and stop there");
		expect(ceiling / shift, notches, "taking one notch per eighth of a stack");

		// --- and the value is repaired, not kept, when it is already too big ---
		expect(ceiling, AmountStepping.next(99_999, 0, ceiling, 1), "a stale larger amount is repaired to the ceiling");
		expect(ceiling - ctrl, AmountStepping.next(Integer.MAX_VALUE, -1, ceiling, ctrl),
				"even an int-sized one comes back inside, on a notch down too");
	}

	/**
	 * The wheel delta decides the direction, not the amount vanilla stored.
	 *
	 * <p>Vanilla stores {@code clamp(previous + signum(scrollY), 1, 64)}, so on a
	 * full cell the stored value comes back as 64 again - indistinguishable from
	 * a wheel that never turned. A direction derived from that value makes the
	 * whole feature unreachable exactly at the moment it is needed, so the case
	 * is pinned here through the same entry point the screen calls.
	 */
	private static void saturation(int perCell, int budget, int ctrl, int shift) {
		int max = perCell * budget;

		// --- a full cell still knows that the wheel turned ---
		expectLayout("65|[64, 1]|open", AmountStepping.scroll(64, +1, 1, perCell, max),
				"a fine notch up on a full connection still grows it");
		expectLayout("128|[64, 64]|open", AmountStepping.scroll(64, +1, ctrl, perCell, max),
				"a ctrl notch up on a full connection adds a full cell");
		expectLayout("72|[64, 8]|open", AmountStepping.scroll(64, +1, shift, perCell, max),
				"a shift notch up on a full connection adds a part cell");
		expectLayout("576|[64, 64, 64, 64, 64, 64, 64, 64, 64]|blocked",
				AmountStepping.scroll(576, +0.5, 1, perCell, max), "and it stops once the grid is full");

		// --- down is never mistaken for up ---
		expectLayout("63|[63]|open", AmountStepping.scroll(64, -1, 1, perCell, max),
				"a notch down from a full connection is 63, not a spill");
		expectLayout("1|[1]|open", AmountStepping.scroll(1, -1, 1, perCell, max),
				"a notch down off the bottom keeps the connection at 1");
		expectLayout("2|[2]|open", AmountStepping.scroll(1, +1, 1, perCell, max),
				"a notch up from an almost empty cell is 2");

		// --- a wheel that did not turn changes nothing ---
		expectLayout("30|[30]|open", AmountStepping.scroll(30, 0, 1, perCell, max),
				"no wheel movement leaves the amount alone");

		// --- the direction helper itself ---
		expect(1, AmountStepping.direction(0.5), "a small up delta is up");
		expect(-1, AmountStepping.direction(-0.5), "a small down delta is down");
		expect(0, AmountStepping.direction(0.0), "a zero delta is no direction");
		expect(0, AmountStepping.direction(Double.NaN), "a broken delta is no direction");
	}

	private static void degenerate(int max) {
		expect(1, AmountStepping.next(1, +1, 0, 1), "max below 1 behaves as 1");
		expect(1, AmountStepping.next(1, +1, -5, 1), "negative max behaves as 1");
		expect(2, AmountStepping.next(1, +1, max, 0), "step 0 falls back to fine");
		expect(5, AmountStepping.slices(5, 0).length, "a cell size of 0 is treated as 1: one cell per item");
		expect(1, AmountStepping.slices(5, 0)[0], "a cell size of 0 is treated as 1 (amount per cell)");
		expectLayout("1|[1]|blocked", AmountStepping.resize(1, +1, 1, 0, 0),
				"a cell size of 0 still leaves an amount of 1");
		expectLayout("2|[1, 1]|open", AmountStepping.resize(1, +1, 1, 0, 5),
				"and a cell size of 0 grows one item at a time");
	}

	// ------------------------------------------------------------------

	private static int expectStep(int total, int direction, int step, int perCell, int max,
			String expected, String what) {
		AmountStepping.Layout layout = AmountStepping.resize(total, direction, step, perCell, max);
		expectLayout(expected, layout, what);
		return layout.total;
	}

	private static String describe(AmountStepping.Layout layout) {
		return layout.total + "|" + Arrays.toString(layout.cells) + "|" + (layout.blocked ? "blocked" : "open");
	}

	private static void expectLayout(String expected, AmountStepping.Layout actual, String what) {
		expect(expected, describe(actual), what);
	}

	private static int sum(int[] values) {
		return Arrays.stream(values).sum();
	}

	private static void expectInts(int[] expected, int[] actual, String what) {
		expect(Arrays.toString(expected), Arrays.toString(actual), what);
	}

	private static void expect(int expected, int actual, String what) {
		expect(Integer.toString(expected), Integer.toString(actual), what);
	}

	private static void expect(boolean expected, boolean actual, String what) {
		expect(Boolean.toString(expected), Boolean.toString(actual), what);
	}

	private static void expect(String expected, String actual, String what) {
		checks++;
		if (!expected.equals(actual)) {
			failures++;
			System.out.printf("FAIL %-52s expected %s, got %s%n", what, expected, actual);
		} else {
			System.out.printf("ok   %-52s %s%n", what, actual);
		}
	}
}
