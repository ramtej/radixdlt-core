/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.radixdlt.consensus.tempo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SingleThreadedScheduler implements Scheduler {
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	@Override
	public Cancellable schedule(Runnable command, long delay, TimeUnit unit) {
		ScheduledFuture<?> future = executor.schedule(command, delay, unit);
		return new Cancellable() {
			@Override
			public boolean cancel() {
				return future.cancel(false);
			}

			@Override
			public boolean isTerminated() {
				return future.isCancelled() || future.isDone();
			}
		};
	}
}
