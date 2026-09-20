package ho.artisan.factorygaugeimprove;

import java.util.Arrays;

/**
 * Pure arithmetic behind the factory gauge amount scroller and its input grid.
 *
 * <p>Kept free of Minecraft types on purpose: this is where all of the mod's
 * real logic lives, so it can be exercised by a plain JVM test without
 * launching the game (which the mod's compileOnly Create dependency makes
 * impractical).
 *
 * <h2>Scrolling</h2>
 * Semantics of {@code step}:
 * <ul>
 * <li>{@code 1} - plain scrolling, move by one.</li>
 * <li>{@code > 1} - "coarse" scrolling. The value snaps to the next/previous
 *     multiple of {@code step} rather than being shifted by it, which is what
 *     makes exact target amounts reachable: from any value you can always
 *     coarse-scroll to a nearby multiple and then fine-tune by one.</li>
 * </ul>
 *
 * <p>With {@code step = 64} from 1: 64, 128, 192 ... 576 - nine notches to the
 * nine stacks that used to require nine separate gauges.
 *
 * <p>The direction always comes from the wheel delta ({@link #direction}), never
 * from the amount vanilla would have stored. Vanilla clamps that amount at 64,
 * so at a full cell it comes back unchanged - indistinguishable from not
 * scrolling at all, which is precisely the case this mod exists for.
 *
 * <h2>The input grid</h2>
 * Create shows a gauge's inputs in a 3x3 grid, one cell per connection, and one
 * package holds exactly nine stacks. This mod ties those two facts together: an
 * amount larger than a stack is laid out over the following cells instead of
 * being written into a single cell as a number above 64, so a connection can
 * never describe an order a single package could not hold.
 *
 * <p>A connection has <em>one</em> amount, and the cells are a view of it:
 * whole stacks first, the remainder in one trailing cell ({@link #slices}).
 * Scrolling therefore moves the connection's total and lets the cells follow
 * ({@link #resize}), rather than editing one cell in isolation - editing a cell
 * alone would open a fresh cell on every notch once the first one is full,
 * covering the whole grid with mostly empty cells instead of filling the one
 * under the cursor to 64 first.
 */
public final class AmountStepping {

    private AmountStepping() {
    }

    /**
     * Coarse step to use for the current modifier keys.
     *
     * @param controlDown whether Ctrl is held
     * @param shiftDown   whether Shift is held
     * @param controlStep step to use while Ctrl is held
     * @param shiftStep   step to use while Shift is held
     * @return 1 when no stepping modifier is held
     */
    public static int stepFor(boolean controlDown, boolean shiftDown, int controlStep, int shiftStep) {
        if (controlDown) {
            return Math.max(1, controlStep);
        }
        if (shiftDown) {
            return Math.max(1, shiftStep);
        }
        return 1;
    }

    /**
     * Applies one scroll notch within a single amount.
     *
     * @param current   the amount before the scroll
     * @param direction +1 for a notch up, -1 for a notch down, 0 for none
     * @param max       the ceiling to clamp to
     * @param step      1 for fine scrolling, &gt;1 to snap to multiples
     * @return the new amount, never below 1 and never above {@code max}
     */
    public static int next(int current, int direction, int max, int step) {
        int ceiling = Math.max(1, max);
        int value = clamp(current, 1, ceiling);
        if (direction == 0) {
            return value;
        }
        if (step <= 1) {
            return clamp(value + direction, 1, ceiling);
        }
        if (direction > 0) {
            if (value >= ceiling) {
                return ceiling;
            }
            return Math.min(ceiling, multipleAbove(value, step));
        }
        if (value <= 1) {
            return 1;
        }
        return Math.max(1, multipleBelow(value, step));
    }

    /** Smallest multiple of {@code step} that is strictly greater than {@code value}. */
    static int multipleAbove(int value, int step) {
        return (value / step + 1) * step;
    }

    /** Largest multiple of {@code step} that is strictly smaller than {@code value}. */
    static int multipleBelow(int value, int step) {
        return value % step == 0 ? value - step : value / step * step;
    }

    /**
     * The scrolling direction, from the wheel rather than from the amount it
     * produced - see {@link #scroll} for why that distinction matters.
     *
     * @param scrollY the raw wheel delta
     * @return +1 up, -1 down, 0 for no movement (or a non-finite delta)
     */
    public static int direction(double scrollY) {
        return scrollY > 0 ? 1 : scrollY < 0 ? -1 : 0;
    }

    /**
     * A connection's amount after a scroll, and the cells it is displayed as.
     */
    public static final class Layout {

        /** The connection's amount after the scroll; never below 1. */
        public final int total;

        /** Per-cell amounts, whole cells first and the remainder last; never empty. */
        public final int[] cells;

        /**
         * Whether this notch asked for more than the grid could hold. The total
         * stays where it was, which is what stops a gauge from quietly
         * describing an order that no longer fits into one package.
         */
        public final boolean blocked;

        private Layout(int total, int[] cells, boolean blocked) {
            this.total = total;
            this.cells = cells;
            this.blocked = blocked;
        }

        /** How many grid cells this amount occupies. */
        public int cellsUsed() {
            return this.cells.length;
        }

        @Override
        public String toString() {
            return this.total + " = " + Arrays.toString(this.cells) + (this.blocked ? " (grid full)" : "");
        }
    }

    /**
     * Applies one scroll notch to a connection.
     *
     * <p>This is the entry point the gauge screen uses, and it takes the raw
     * scroll delta rather than a direction on purpose. Vanilla computes the new
     * amount as {@code clamp(previous + signum(scrollY), 1, 64)} and then stores
     * it - so at a full cell the stored value is {@code 64} again, and a
     * direction derived from it would read as "no scrolling happened" exactly
     * when the player is trying to grow past a stack. Deriving the direction
     * from the wheel itself is what makes that case work.
     *
     * @param total    the connection's amount before the scroll - the sum of the
     *                 cells it currently occupies
     * @param scrollY  the raw wheel delta; only its sign is used
     * @param step     1 fine, &gt;1 snap to multiples
     * @param perCell  one cell's capacity (64 = one stack = one package slot)
     * @param maxTotal the highest total this connection may reach
     */
    public static Layout scroll(int total, double scrollY, int step, int perCell, int maxTotal) {
        return resize(total, direction(scrollY), step, perCell, maxTotal);
    }

    /**
     * Applies one scroll notch to a connection's amount and re-derives its cells.
     *
     * <p>The step moves the connection's <em>total</em>, never a single cell, and
     * the cells are recomputed from the result. That is what keeps the grid
     * packed: a notch carrying the total from 64 to 72 widens the cell under the
     * cursor into {@code 64 + 8}, and only a total past 128 opens a third cell.
     * Editing one cell at a time would instead push a new, mostly empty cell
     * into the grid on every notch - nine cells of eight after nine Shift
     * notches, which fills the grid without ever asking for anything near a
     * package.
     *
     * @param total     the connection's amount before the scroll
     * @param direction +1 up, -1 down, 0 for none
     * @param step      1 fine, &gt;1 snap to multiples
     * @param perCell   one cell's capacity
     * @param maxTotal  the highest total this connection may reach - one cell per
     *                  grid cell the other connections leave free
     */
    public static Layout resize(int total, int direction, int step, int perCell, int maxTotal) {
        int unit = Math.max(1, perCell);
        int ceiling = Math.max(unit, maxTotal);
        int value = clamp(total, 1, ceiling);
        // "This connection cannot take more": the grid is already carrying as
        // much as the free cells allow, so the notch changes nothing.
        boolean blocked = direction > 0 && value >= ceiling;
        int next = next(value, direction, ceiling, step);
        return new Layout(next, slices(next, unit), blocked);
    }

    /**
     * How many grid cells an amount occupies.
     *
     * <p>A connection always holds at least one cell, even at amount 0 - that is
     * how vanilla lays its inputs out, and the cell is what the player clicks to
     * remove the connection.
     *
     * @param amount  the amount carried by the connection
     * @param perCell how much a single cell holds (64 = one stack = one package slot)
     */
    public static int cells(int amount, int perCell) {
        int unit = Math.max(1, perCell);
        int value = Math.max(0, amount);
        if (value == 0) {
            return 1;
        }
        return (value + unit - 1) / unit;
    }

    /**
     * Splits an amount into the per-cell amounts it is displayed as: whole cells
     * first, the remainder in the last one.
     *
     * <p>576 with a cell size of 64 becomes nine cells of 64 - one full package.
     * 100 becomes 64 + 36. 0 becomes a single empty cell.
     */
    public static int[] slices(int amount, int perCell) {
        int unit = Math.max(1, perCell);
        int value = Math.max(0, amount);
        if (value == 0) {
            return new int[]{0};
        }
        int full = value / unit;
        int rest = value % unit;
        int[] result = new int[full + (rest > 0 ? 1 : 0)];
        Arrays.fill(result, 0, full, unit);
        if (rest > 0) {
            result[full] = rest;
        }
        return result;
    }

    /**
     * Ceiling for one connection, leaving the cells the other connections need.
     *
     * <p>This is what keeps the grid honest: with nine cells of 64 and nothing
     * else connected a single arrow may carry 576, but every extra connection
     * that takes a cell lowers that ceiling by one stack.
     *
     * @param perCell           how much one cell holds
     * @param maxCells          how many cells the grid has (9)
     * @param cellsUsedByOthers cells the other connections occupy
     * @return the highest amount the connection may be set to, never below one cell
     */
    public static int maxAmountFor(int perCell, int maxCells, int cellsUsedByOthers) {
        int unit = Math.max(1, perCell);
        int free = Math.max(1, Math.max(1, maxCells) - Math.max(0, cellsUsedByOthers));
        return unit * free;
    }

    /**
     * Whether another connection still fits into the grid.
     *
     * @param cellsAlreadyUsed cells occupied by the existing connections
     * @param maxCells         how many cells the grid has (9)
     */
    public static boolean fitsGrid(int cellsAlreadyUsed, int maxCells) {
        return Math.max(0, cellsAlreadyUsed) + 1 <= Math.max(1, maxCells);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
