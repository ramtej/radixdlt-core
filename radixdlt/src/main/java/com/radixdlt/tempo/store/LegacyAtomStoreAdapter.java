package com.radixdlt.tempo.store;

import com.google.common.collect.ImmutableList;
import com.radixdlt.atomos.SimpleRadixEngineAtom;
import com.radixdlt.common.AID;
import com.radixdlt.common.Pair;
import com.radixdlt.engine.RadixEngineAtom;
import com.radixdlt.atomos.RadixEngineUtils;
import com.radixdlt.ledger.LedgerCursor;
import com.radixdlt.ledger.LedgerIndex;
import com.radixdlt.ledger.LedgerSearchMode;
import com.radixdlt.tempo.AtomStore;
import com.radixdlt.tempo.AtomStoreView;
import com.radixdlt.tempo.LegacyUtils;
import com.radixdlt.tempo.TempoAtom;
import com.radixdlt.tempo.TempoException;
import com.radixdlt.tempo.LogicalClockCursor;
import com.radixdlt.utils.UInt384;
import org.radix.atoms.Atom;
import org.radix.atoms.AtomDiscoveryRequest;
import org.radix.atoms.PreparedAtom;
import org.radix.atoms.sync.AtomSyncStore;
import org.radix.database.exceptions.DatabaseException;
import org.radix.discovery.DiscoveryCursor;
import org.radix.discovery.DiscoveryException;
import org.radix.discovery.DiscoveryRequest;
import org.radix.logging.Logger;
import org.radix.logging.Logging;
import org.radix.shards.ShardSpace;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public class LegacyAtomStoreAdapter implements AtomStore {
	private final Logger logger = Logging.getLogger("Store");
	private final Supplier<org.radix.atoms.AtomStore> atomStoreSupplier;
	private final Supplier<AtomSyncStore> atomSyncStoreSupplier;
	private final AtomStoreView view;

	public LegacyAtomStoreAdapter(Supplier<org.radix.atoms.AtomStore> atomStoreSupplier, Supplier<AtomSyncStore> atomSyncStoreSupplier) {
		this.atomStoreSupplier = Objects.requireNonNull(atomStoreSupplier, "atomStoreSupplier is required");
		this.atomSyncStoreSupplier = Objects.requireNonNull(atomSyncStoreSupplier, "atomSyncStoreSupplier is required");
		this.view = new AtomStoreViewAdapter(LegacyAtomStoreAdapter.this);
	}

	@Override
	public AtomStoreView asReadOnlyView() {
		return view;
	}

	@Override
	public boolean contains(AID aid) {
		try {
			return atomStoreSupplier.get().hasAtom(aid);
		} catch (DatabaseException e) {
			throw new TempoException("Error while querying hasAtom(" + aid + ")", e);
		}
	}

	@Override
	public Optional<TempoAtom> get(AID aid) {
		try {
			// TODO awful conversion from legacy 'Atom'
			return atomStoreSupplier.get().getAtom(aid)
				.map(LegacyUtils::fromLegacyAtom);
		} catch (DatabaseException e) {
			throw new TempoException("Error while querying getAtom(" + aid + ")", e);
		}
	}

	@Override
	public boolean store(TempoAtom atom, Set<LedgerIndex> uniqueIndices, Set<LedgerIndex> duplicateIndices) {
		// TODO remove awful conversion
		final SimpleRadixEngineAtom radixEngineAtom = convertToCMAtom(atom);

		try {
			return atomStoreSupplier.get().storeAtom(new PreparedAtom(radixEngineAtom, UInt384.ONE)).isCompleted();
		} catch (IOException e) {
			throw new TempoException("Error while storing atom " + atom.getAID(), e);
		}
	}

	@Override
	public boolean delete(AID aid) {
		try {
			return atomStoreSupplier.get().deleteAtom(aid).isCompleted();
		} catch (DatabaseException e) {
			throw new TempoException("Error while deleting " + aid, e);
		}
	}

	@Override
	public boolean replace(Set<AID> aids, TempoAtom atom, Set<LedgerIndex> uniqueIndices, Set<LedgerIndex> duplicateIndices) {
		// TODO remove awful conversion
		final SimpleRadixEngineAtom radixEngineAtom = convertToCMAtom(atom);

		try {
			return atomStoreSupplier.get().replaceAtom(aids, new PreparedAtom(radixEngineAtom, UInt384.ONE)).isCompleted();
		} catch (IOException e) {
			throw new TempoException("Error while storing atom " + atom.getAID(), e);
		}
	}

	@Override
	public LedgerCursor search(LedgerCursor.Type type, LedgerIndex index, LedgerSearchMode mode) {
		try {
			return atomStoreSupplier.get().search(type, index, mode);
		} catch (DatabaseException e) {
			throw new TempoException("Error while searching for " + index, e);
		}
	}

	@Override
	public Pair<ImmutableList<AID>, LogicalClockCursor> getNext(LogicalClockCursor logicalClockCursor, int limit, ShardSpace shardSpace) {
		try {
			AtomDiscoveryRequest atomDiscoveryRequest = new AtomDiscoveryRequest(DiscoveryRequest.Action.DISCOVER);
			atomDiscoveryRequest.setLimit((short) 64);
			atomDiscoveryRequest.setCursor(new DiscoveryCursor(logicalClockCursor.getLcPosition()));
			atomDiscoveryRequest.setShards(shardSpace);
			atomSyncStoreSupplier.get().discovery(atomDiscoveryRequest);

			ImmutableList<AID> inventory = ImmutableList.copyOf(atomDiscoveryRequest.getInventory());
			DiscoveryCursor discoveryCursor = atomDiscoveryRequest.getCursor();
			LogicalClockCursor nextCursor = discoveryCursor.hasNext() ? new LogicalClockCursor(discoveryCursor.getNext().getPosition(), null) : null;
			LogicalClockCursor updatedCursor = new LogicalClockCursor(logicalClockCursor.getLcPosition(), nextCursor);
			return Pair.of(inventory, updatedCursor);
		} catch (DiscoveryException e) {
			throw new TempoException("Error while advancing cursor", e);
		}
	}

	@Override
	public void open() {
		// not implemented here as is already done in legacy AtomStore directly
	}

	@Override
	public void close() {
		// not implemented here as is already done in legacy AtomStore directly
	}

	@Override
	public void reset() {
		// not implemented here as is already done in legacy AtomStore directly
	}

	private SimpleRadixEngineAtom convertToCMAtom(TempoAtom atom) {
		try {
			Atom legacyAtom = LegacyUtils.toLegacyAtom(atom);
			return RadixEngineUtils.toCMAtom(legacyAtom);
		} catch (RadixEngineUtils.CMAtomConversionException e) {
			throw new TempoException("Error while converting atom", e);
		}
	}
}