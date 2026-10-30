package dev.binwatch.api;

/** Integrity of one multi-page collection of the auction endpoint. */
public enum SnapshotStatus {
	/** Every page was retrieved and all pages reported the same lastUpdated and totalPages. */
	COMPLETE,
	/** Every page was retrieved, but pages reported different lastUpdated or totalPages values. */
	INCONSISTENT,
	/** One or more pages could not be retrieved. */
	INCOMPLETE
}
