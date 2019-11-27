package com.radixdlt.ledger;

import com.radixdlt.common.AID;
import com.radixdlt.ledger.exceptions.LedgerException;
import com.radixdlt.ledger.exceptions.LedgerIndexConflictException;

import java.util.Optional;
import java.util.Set;

/**
 * An instance of a ledger which may be synchronised across a set of nodes.
 */
public interface Ledger {
	/**
	 * Observes this ledger, blocking until an observations becomes available.
	 *
	 * @return The ledger observation
	 *
	 * @throws LedgerException in case of internal errors
	 */
	LedgerObservation observe() throws InterruptedException;

	/**
	 * Gets the LedgerEntry associated with a certain {@link AID}.
	 *
	 * @param aid The {@link AID}
	 * @return The LedgerEntry associated with the given {@link AID}
	 *
	 * @throws LedgerException in case of internal errors
	 */
	Optional<LedgerEntry> get(AID aid);

	/**
	 * Stores an {@link LedgerEntry} with certain indices.
	 *
	 * @param ledgerEntry The ledgerEntry
	 * @param uniqueIndices The unique indices
	 * @param duplicateIndices The duplicate indices
	 *
	 * @throws LedgerIndexConflictException if the unique indices conflict with existing indices
	 * @throws LedgerException in case of internal errors
	 */
	void store(LedgerEntry ledgerEntry, Set<LedgerIndex> uniqueIndices, Set<LedgerIndex> duplicateIndices);

	/**
	 * Replaces a set of ledgerEntries with another ledgerEntry in an atomic operation.
	 *
	 * @param aids The aids to delete
	 * @param ledgerEntry The new ledgerEntry
	 * @param uniqueIndices The unique indices of that atom
	 * @param duplicateIndices The duplicate indices of that atom
	 *
	 * @throws LedgerIndexConflictException if the unique indices conflict with existing indices
	 * @throws LedgerException in case of internal errors
	 */
	void replace(Set<AID> aids, LedgerEntry ledgerEntry, Set<LedgerIndex> uniqueIndices, Set<LedgerIndex> duplicateIndices);

	/**
	 * Searches for a certain index.
	 *
	 * @param type The type of index
	 * @param index The index
	 * @param mode The mode
	 * @return The resulting ledger cursor
	 *
	 * @throws LedgerException in case of internal errors
	 */
	LedgerCursor search(LedgerIndex.LedgerIndexType type, LedgerIndex index, LedgerSearchMode mode);

	/**
	 * Checks whether a certain index is contained in this ledger.
	 *
	 * @param type The type of index
	 * @param index The index
	 * @param mode The mode
	 * @return The resulting ledger cursor
	 *
	 * @throws LedgerException in case of internal errors
	 */
	boolean contains(LedgerIndex.LedgerIndexType type, LedgerIndex index, LedgerSearchMode mode);

	/**
	 * Checks whether a certain index is contained in this ledger.
	 *
	 * @param aid
	 * @return true if ledgerEntry exists and false otherwise
	 */
	boolean contains(AID aid);
}
