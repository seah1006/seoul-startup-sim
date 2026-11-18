package com.seah.startupsim.game;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 검증된 노드만 평가하며 문자열 표현식이나 임의 코드를 실행하지 않는다. */
public final class EventDsl {
    private EventDsl() {}

    public sealed interface Condition permits Always, Group, Negation, Comparison {
        boolean matches(Map<String, Object> variables);
    }

    public record Always() implements Condition {
        public boolean matches(Map<String, Object> variables) {
            return true;
        }
    }

    public record Group(boolean all, List<Condition> children) implements Condition {
        public Group {
            children = List.copyOf(children);
        }

        public boolean matches(Map<String, Object> variables) {
            return all ? children.stream().allMatch(child -> child.matches(variables))
                    : children.stream().anyMatch(child -> child.matches(variables));
        }
    }

    public record Negation(Condition child) implements Condition {
        public boolean matches(Map<String, Object> variables) {
            return !child.matches(variables);
        }
    }

    public record Comparison(String variable, String operator, Object literal) implements Condition {
        public boolean matches(Map<String, Object> variables) {
            Object actual = variables.get(variable);
            if (actual == null) {
                return false;
            }
            return switch (operator) {
                case "==" -> equal(actual, literal);
                case "!=" -> !equal(actual, literal);
                case "in" -> ((List<?>) literal).stream().anyMatch(value -> equal(actual, value));
                case "between" -> actual instanceof Number number
                        && compare(number, ((List<?>) literal).get(0)) >= 0
                        && compare(number, ((List<?>) literal).get(1)) <= 0;
                case ">" -> actual instanceof Number number && compare(number, literal) > 0;
                case ">=" -> actual instanceof Number number && compare(number, literal) >= 0;
                case "<" -> actual instanceof Number number && compare(number, literal) < 0;
                case "<=" -> actual instanceof Number number && compare(number, literal) <= 0;
                default -> throw new IllegalStateException("검증되지 않은 연산자입니다.");
            };
        }

        private static boolean equal(Object left, Object right) {
            return left instanceof Number number && right instanceof Number
                    ? compare(number, right) == 0 : left.equals(right);
        }

        private static int compare(Number left, Object right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        }
    }

    public record StatBonus(String stat, double per10) {}

    public record Formula(double base, List<StatBonus> bonuses, double min, double max) {
        public Formula {
            bonuses = List.copyOf(bonuses);
        }

        public double probability(Map<String, Integer> stats) {
            double probability = base;
            for (StatBonus bonus : bonuses) {
                probability += (stats.get(bonus.stat()) - 40) / 10.0 * bonus.per10();
            }
            return Math.max(min, Math.min(max, probability));
        }

        public int ratePct(Map<String, Integer> stats) {
            return (int) Math.round(probability(stats) * 100);
        }
    }

    public sealed interface Effect permits Cash, Stat, ModifierEffect, Flag, ShopCount, RivalAdd, Text {}
    public record Cash(long amount, String memo) implements Effect {}
    public record Stat(String stat, int delta) implements Effect {}
    public record ModifierEffect(String target, String mode, double value, int quarters, String label) implements Effect {}
    public record Flag(String flag, boolean value) implements Effect {}
    public record ShopCount(int delta) implements Effect {}
    public record RivalAdd(int count, boolean franchise) implements Effect {}
    public record Text(String text) implements Effect {}
}
