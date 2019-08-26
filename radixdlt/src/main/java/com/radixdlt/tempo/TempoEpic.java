package com.radixdlt.tempo;

import com.google.common.collect.ImmutableSet;

import java.util.Set;
import java.util.stream.Stream;

/**
 * A TempoEpic that participates in the {@link TempoAction} flow
 */
public interface TempoEpic {
	/**
	 * Gets the set of required state of this epic
	 */
	default Set<Class<? extends TempoState>> requiredState() {
		return ImmutableSet.of();
	}

	/**
	 * Execute this epic with the given action
	 *
	 * @param bundle The bundle of requested states
	 * @param action The action
	 * @return the next actions to be executed given the action
	 */
	Stream<TempoAction> epic(TempoStateBundle bundle, TempoAction action);

	/**
	 * Get the initial actions to be executed once upon starting
	 * @return The initial actions
	 */
	default Stream<TempoAction> initialActions() {
		return Stream.empty();
	}
}