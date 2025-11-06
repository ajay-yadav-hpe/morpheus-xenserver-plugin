package com.morpheusdata.xen.util

/**
 * Strategy interface for polling operations with configurable retry logic.
 * Separates polling mechanism from business logic to enable testing.
 */
interface PollingStrategy {
	/**
	 * Poll until condition is met or timeout occurs
	 * @param condition Closure that returns true when polling should stop
	 * @param timeoutMillis Maximum time to poll in milliseconds
	 * @param intervalMillis Time between polling attempts in milliseconds
	 * @return true if condition was met, false if timeout
	 */
	boolean pollUntil(Closure<Boolean> condition, long timeoutMillis, long intervalMillis)

	/**
	 * Execute operation with result polling
	 * @param operation Closure that performs the operation and returns result
	 * @param successCondition Closure that checks if result indicates success
	 * @param timeoutMillis Maximum time to poll in milliseconds
	 * @param intervalMillis Time between polling attempts in milliseconds
	 * @return Operation result or null if timeout
	 */
	def pollForResult(Closure operation, Closure<Boolean> successCondition, long timeoutMillis, long intervalMillis)
}
