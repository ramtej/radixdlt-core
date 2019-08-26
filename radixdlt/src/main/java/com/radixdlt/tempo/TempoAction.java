package com.radixdlt.tempo;

import com.radixdlt.tempo.actions.control.RepeatScheduleAction;
import com.radixdlt.tempo.actions.control.ScheduleAction;

import java.util.concurrent.TimeUnit;

/**
 * Marker interface for Tempo actions
 */
public interface TempoAction {
	/**
	 * Schedule this action to be dispatched after some delay
	 * @param delay The delay
	 * @param unit The unit of the delay
	 * @return A scheduled action
	 */
	default ScheduleAction delay(long delay, TimeUnit unit) {
		return new ScheduleAction(this, delay, unit);
	}

	/**
	 * Schedule an action to be dispatched repeatedly with some delay after some delay
	 * @param initialDelay The initial delay
	 * @param recurrentDelay The recurrent delay
	 * @param unit The unit of the delays
	 * @return A repeatedly scheduled action
	 */
	default RepeatScheduleAction repeat(long initialDelay, long recurrentDelay, TimeUnit unit) {
		return new RepeatScheduleAction(this, initialDelay, recurrentDelay, unit);
	}
}