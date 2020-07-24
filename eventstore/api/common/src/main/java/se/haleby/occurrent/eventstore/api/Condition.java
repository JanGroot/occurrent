package se.haleby.occurrent.eventstore.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static se.haleby.occurrent.eventstore.api.Condition.MultiOperandConditionName.*;
import static se.haleby.occurrent.eventstore.api.Condition.SingleOperandConditionName.*;

public abstract class Condition<T> {
    public final String description;

    private Condition(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return description;
    }

    public static class SingleOperandCondition<T> extends Condition<T> {
        public final SingleOperandConditionName singleOperandConditionName;
        public final T operand;

        private SingleOperandCondition(SingleOperandConditionName singleOperandConditionName, T operand, String description) {
            super(description);
            this.singleOperandConditionName = singleOperandConditionName;
            this.operand = operand;
        }
    }

    public static class MultiOperandCondition<T> extends Condition<T> {
        public final MultiOperandConditionName operationName;
        public final List<Condition<T>> operations;

        private MultiOperandCondition(final MultiOperandConditionName operationName, List<Condition<T>> operations, String description) {
            super(description);
            this.operationName = operationName;
            this.operations = Collections.unmodifiableList(operations);
        }
    }

    public static <T> Condition<T> eq(T t) {
        return new SingleOperandCondition<>(EQ, t, String.format("to be equal to %s", t));
    }

    public static <T> Condition<T> lt(T t) {
        return new SingleOperandCondition<>(LT, t, String.format("to be less than %s", t));
    }

    public static <T> Condition<T> gt(T t) {
        return new SingleOperandCondition<>(GT, t, String.format("to be greater than %s", t));
    }

    public static <T> Condition<T> lte(T t) {
        return new SingleOperandCondition<>(LTE, t, String.format("to be less than or equal to %s", t));
    }

    public static <T> Condition<T> gte(T t) {
        return new SingleOperandCondition<>(GTE, t, String.format("to be greater than or equal to %s", t));
    }

    public static <T> Condition<T> ne(T t) {
        return new SingleOperandCondition<>(NE, t, String.format("to not be equal to %s", t));
    }

    @SafeVarargs
    public static <T> Condition<T> and(Condition<T> firstCondition, Condition<T> secondCondition, Condition<T>... additionalConditions) {
        List<Condition<T>> conditions = createConditionsFrom(firstCondition, secondCondition, additionalConditions);
        return new MultiOperandCondition<>(AND, conditions, conditions.stream().map(Condition::toString).collect(Collectors.joining(" and ")));
    }

    @SafeVarargs
    public static <T> Condition<T> or(Condition<T> firstCondition, Condition<T> secondCondition, Condition<T>... additionalConditions) {
        List<Condition<T>> conditions = createConditionsFrom(firstCondition, secondCondition, additionalConditions);
        return new MultiOperandCondition<>(OR, conditions, conditions.stream().map(Condition::toString).collect(Collectors.joining(" or ")));
    }

    public static <T> Condition<T> not(Condition<T> condition) {
        return new MultiOperandCondition<>(NOT, Collections.singletonList(condition), "not " + condition);
    }

    private static <T> List<Condition<T>> createConditionsFrom(Condition<T> firstCondition, Condition<T> secondCondition, Condition<T>[] additionalConditions) {
        List<Condition<T>> conditions = new ArrayList<>(2 + additionalConditions.length);
        conditions.add(firstCondition);
        conditions.add(secondCondition);
        Collections.addAll(conditions, additionalConditions);
        return conditions;
    }

    public enum SingleOperandConditionName {
        EQ, LT, GT, LTE, GTE, NE
    }

    public enum MultiOperandConditionName {
        AND, OR, NOT
    }
}