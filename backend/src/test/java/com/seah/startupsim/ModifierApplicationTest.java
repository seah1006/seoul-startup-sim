package com.seah.startupsim;

import com.seah.startupsim.game.QuarterCalculator;
import com.seah.startupsim.game.QuarterCalculator.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 기존 산술·보정 순서·처리량 상한과 원가율 경계를 검증한다. */
class ModifierApplicationTest {
    private final QuarterCalculator calculator = new QuarterCalculator();
    private final QuarterCalculator.Input base = new QuarterCalculatorTest().input(100000000L, 10);

    @Test
    void emptyModifiersPreserveEverySerializedByteAndCopyInputList() {
        var json = new ObjectMapper();
        byte[] before = json.writeValueAsString(calculator.calculate(base)).getBytes(StandardCharsets.UTF_8);
        byte[] after = json.writeValueAsString(calculator.calculate(base.withModifiers(List.of()))).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(before, after);
        var mutable = new ArrayList<Modifier>();
        var copy = base.withModifiers(mutable);
        mutable.add(new Modifier("revenue", "ADD", 1000));
        assertEquals(List.of(), copy.modifiers());
    }

    @Test
    void addAlwaysPrecedesMultiplyRegardlessOfInputModeOrder() {
        long revenue = calculator.calculate(base).revenue();
        var result = calculator.calculate(base.withModifiers(List.of(
                new Modifier("revenue", "MULTIPLY", 1.5), new Modifier("revenue", "ADD", 1000))));
        assertEquals(Math.round((revenue + 1000) * 1.5), result.revenue());
        assertEquals(result.revenue() - result.costs().total(), result.profit());
        assertEquals(base.cash() + result.profit(), result.cashAfter());
    }

    @Test
    void visitorsAreModifiedBeforeCapacityAndTruncation() {
        var huge = new QuarterCalculatorTest().input(Long.MAX_VALUE, 10);
        assertEquals(7200, calculator.calculate(huge.withModifiers(List.of(new Modifier("visitors", "MULTIPLY", 3)))).visitors());
        assertEquals(0, calculator.calculate(base.withModifiers(List.of(new Modifier("visitors", "ADD", -1000000)))).visitors());
        double random = new java.util.SplittableRandom(base.seed()).nextDouble(.9, 1.1);
        double appeal = .25 * base.stats().get("cooking") / 40d + .2 * base.stats().get("service") / 40d
                + .15 * base.stats().get("marketing") / 40d + .2 + .2 / 3d;
        double raw = base.marketSales() / 11d * appeal * random / 12000;
        var modified = calculator.calculate(base.withModifiers(List.of(new Modifier("visitors", "ADD", 0.9))));
        assertEquals((long) (raw + 0.9), modified.visitors());
    }

    @Test
    void costRateClampsOnlyWhenItsTargetHasModifiers() {
        var low = calculator.calculate(base.withModifiers(List.of(new Modifier("costRate", "ADD", -2))));
        var high = calculator.calculate(base.withModifiers(List.of(new Modifier("costRate", "ADD", 2))));
        assertEquals(Math.round(low.revenue() * .05), low.costs().ingredients());
        assertEquals(Math.round(high.revenue() * .95), high.costs().ingredients());
        assertEquals(calculator.calculate(base).costs().ingredients(),
                calculator.calculate(base.withModifiers(List.of(new Modifier("rent", "ADD", 10)))).costs().ingredients());
    }

    @Test
    void rentCannotBecomeNegativeAndUsesAddThenMultiply() {
        assertEquals(0, calculator.calculate(base.withModifiers(List.of(new Modifier("rent", "ADD", -10000000)))).costs().rent());
        assertEquals(5000000, calculator.calculate(base.withModifiers(List.of(
                new Modifier("rent", "MULTIPLY", 0.5), new Modifier("rent", "ADD", 1000000)))).costs().rent());
    }

    @Test
    void rejectsInvalidTargetsModesAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> new Modifier("cash", "ADD", 1));
        assertThrows(IllegalArgumentException.class, () -> new Modifier("rent", "SET", 1));
        assertThrows(IllegalArgumentException.class, () -> new Modifier("rent", "ADD", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Modifier("rent", "MULTIPLY", 0));
    }
}
