package com.radixdlt.tempo.delivery;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.radixdlt.common.AID;
import com.radixdlt.common.EUID;
import com.radixdlt.ledger.LedgerEntry;
import com.radixdlt.tempo.Resource;
import com.radixdlt.tempo.store.LedgerEntryStoreView;
import com.radixdlt.tempo.Scheduler;
import com.radixdlt.tempo.delivery.messages.DeliveryRequestMessage;
import com.radixdlt.tempo.delivery.messages.DeliveryResponseMessage;
import org.radix.logging.Logger;
import org.radix.logging.Logging;
import org.radix.network2.addressbook.Peer;
import org.radix.network2.messaging.MessageCentral;
import org.radix.utils.SimpleThreadPool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public final class LazyRequestDeliverer implements Resource, RequestDeliverer {
	private static final Logger log = Logging.getLogger("deliverer.request");

	private static final int DEFAULT_REQUEST_QUEUE_CAPACITY = 8192;
	private static final int DEFAULT_REQUEST_PROCESSOR_THREADS = 2;
	private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 5;

	private final int requestTimeoutSeconds;

	@VisibleForTesting
	final PendingDeliveryState pendingDeliveries = new PendingDeliveryState();

	private final Scheduler scheduler;
	private final MessageCentral messageCentral;
	private final LedgerEntryStoreView storeView;

	private final BlockingQueue<AtomDeliveryRequest> requestQueue;
	private final SimpleThreadPool<AtomDeliveryRequest> requestThreadPool;

	@Inject
	public LazyRequestDeliverer(
		Scheduler scheduler,
		MessageCentral messageCentral,
		LedgerEntryStoreView storeView,
		LazyRequestDelivererConfiguration configuration
	) {
		this.scheduler = Objects.requireNonNull(scheduler);
		this.messageCentral = Objects.requireNonNull(messageCentral);
		this.storeView = Objects.requireNonNull(storeView);

		this.requestTimeoutSeconds = configuration.requestTimeoutSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS);

		this.messageCentral.addListener(DeliveryRequestMessage.class, this::onRequest);
		this.messageCentral.addListener(DeliveryResponseMessage.class, this::onResponse);

		this.requestQueue = new ArrayBlockingQueue<>(configuration.requestQueueCapacity(DEFAULT_REQUEST_QUEUE_CAPACITY));
		int processorThreads = configuration.requestProcessorThreads(DEFAULT_REQUEST_PROCESSOR_THREADS);
		this.requestThreadPool = new SimpleThreadPool<>("Atom delivery processing", processorThreads, requestQueue::take, this::processRequest, log);
		this.requestThreadPool.start();
	}

	private void onRequest(Peer peer, DeliveryRequestMessage message) {
		AtomDeliveryRequest request = new AtomDeliveryRequest(peer, message);
		requestQueue.add(request);
	}

	private void processRequest(AtomDeliveryRequest request) {
		if (log.hasLevel(Logging.DEBUG)) {
			log.debug(String.format("Processing atom delivery request for %d aids from %s",
				request.getMessage().getAids().size(), request.getPeer()));
		}

		request.getMessage().getAids().stream()
			.map(storeView::get)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(DeliveryResponseMessage::new)
			.forEach(response -> messageCentral.send(request.getPeer(), response));
	}

	private void onResponse(Peer peer, DeliveryResponseMessage message) {
		if (log.hasLevel(Logging.DEBUG)) {
			log.debug("Received delivery of '" + message.getLedgerEntry().getAID() + "' from " + peer);
		}
		LedgerEntry ledgerEntry = message.getLedgerEntry();
		pendingDeliveries.complete(ledgerEntry.getAID(), DeliveryResult.success(ledgerEntry, peer));
	}

	@Override
	public Map<AID, CompletableFuture<DeliveryResult>> deliver(Set<AID> aids, Set<Peer> peers) {
		// early out if there is nothing to do
		if (aids.isEmpty()) {
			return ImmutableMap.of();
		}
		if (peers.isEmpty()) {
			throw new IllegalArgumentException("peers cannot be empty");
		}

		final ImmutableMap.Builder<AID, CompletableFuture<DeliveryResult>> result = ImmutableMap.builder();
		final List<AID> unrequestedAids = new ArrayList<>();
		Peer primaryPeer = peers.iterator().next();
		for (AID aid : aids) {
			if (storeView.contains(aid)) {
				result.put(aid, CompletableFuture.completedFuture(DeliveryResult.alreadyStored()));
			} else {
				CompletableFuture<DeliveryResult> future = new CompletableFuture<>();
				// if this is the first peer added for that aid, we need to request it
				if (pendingDeliveries.add(aid, primaryPeer, peers, future)) {
					unrequestedAids.add(aid);
				}
				result.put(aid, future);
			}
		}
		requestDelivery(unrequestedAids, primaryPeer);

		return result.build();
	}

	private void requestDelivery(Collection<AID> aids, Peer peer) {
		// early out if there is nothing to do
		if (aids.isEmpty()) {
			return;
		}
		if (log.hasLevel(Logging.DEBUG)) {
			log.debug("Requesting delivery of " + aids.size() + " aids from " + peer);
		}

		DeliveryRequestMessage request = new DeliveryRequestMessage(aids);
		messageCentral.send(peer, request);

		// TODO aggregate cancellables and cancel on stop
		scheduler.schedule(() -> {
			ImmutableList<AID> missingAids = aids.stream()
				.filter(pendingDeliveries::isPending)
				.collect(ImmutableList.toImmutableList());
			if (!missingAids.isEmpty()) {
				handleFailedDelivery(missingAids, peer);
				// TODO retry
			}
		}, requestTimeoutSeconds, TimeUnit.SECONDS);
	}

	private void handleFailedDelivery(Collection<AID> missingAids, Peer peer) {
		if (log.hasLevel(Logging.DEBUG)) {
			log.debug("Delivery of " + missingAids.size() + " aids from primary peer " + peer + " failed, attempting retry with fallback peers");
		}

		// get fallback peers and aggregate all aids that can be requested from a peer
		Map<EUID, Set<AID>> retriesByNid = new HashMap<>();
		Map<EUID, Peer> peersByNid = new HashMap<>();
		for (AID missingAid : missingAids) {
			Peer fallbackPeer = pendingDeliveries.popFallback(missingAid);
			if (fallbackPeer != null) {
				EUID fallbackPeerNid = fallbackPeer.getNID();
				peersByNid.putIfAbsent(fallbackPeerNid, fallbackPeer);
				retriesByNid.computeIfAbsent(fallbackPeerNid, x -> new HashSet<>()).add(missingAid);
			} else {
				log.warn("Delivery of " + missingAid + " is currently impossible, no fallback peers are available");
				pendingDeliveries.complete(missingAid, DeliveryResult.failed());
			}
		}

		retriesByNid.forEach((nid, aids) -> requestDelivery(aids, peersByNid.get(nid)));
	}

	@Override
	public void reset() {
		pendingDeliveries.reset();
	}

	@Override
	public void close() {
		requestThreadPool.stop();
	}

	private static final class AtomDeliveryRequest {
		private final Peer peer;
		private final DeliveryRequestMessage message;

		private AtomDeliveryRequest(Peer peer, DeliveryRequestMessage message) {
			this.peer = peer;
			this.message = message;
		}

		private Peer getPeer() {
			return peer;
		}

		public DeliveryRequestMessage getMessage() {
			return message;
		}
	}
}
