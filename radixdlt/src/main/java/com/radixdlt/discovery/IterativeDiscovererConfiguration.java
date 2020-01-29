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

package com.radixdlt.discovery;

import org.radix.properties.RuntimeProperties;

/**
 * Static configuration for an {@link IterativeDiscoverer}
 */
public interface IterativeDiscovererConfiguration {
	int requestTimeoutSeconds(int defaultValue);

	int maxBackoff(int defaultValue);

	int responseLimit(int defaultValue);

	int requestQueueCapacity(int defaultValue);

	int requestProcessorThreads(int defaultValue);

	static IterativeDiscovererConfiguration fromRuntimeProperties(RuntimeProperties properties) {
		return new IterativeDiscovererConfiguration() {
			@Override
			public int requestTimeoutSeconds(int defaultValue) {
				return properties.get("tempo.discovery.iterative.request_timeout", defaultValue);
			}

			@Override
			public int maxBackoff(int defaultValue) {
				return properties.get("tempo.discovery.iterative.max_backoff", defaultValue);
			}

			@Override
			public int responseLimit(int defaultValue) {
				return properties.get("tempo.discovery.iterative.response_limit", defaultValue);
			}

			@Override
			public int requestQueueCapacity(int defaultValue) {
				return properties.get("tempo.discovery.iterative.request_queue_capacity", defaultValue);
			}

			@Override
			public int requestProcessorThreads(int defaultValue) {
				return properties.get("tempo.discovery.iterative.request_processor_threads", defaultValue);
			}
		};
	}
}
