package com.morpheusdata.xen.util

/**
 * Test implementation of PollingStrategy that executes immediately without waiting.
 * Used in unit tests to avoid sleep delays and enable fast test execution.
 */
class TestPollingStrategy implements PollingStrategy {

	@Override
	boolean pollUntil(Closure<Boolean> condition, long timeoutMillis, long intervalMillis) {
		// Execute condition once, no waiting - for unit tests
		try {
			return condition()
		} catch (Exception e) {
			return false
		}
	}

	@Override
	def pollForResult(Closure operation, Closure<Boolean> successCondition, long timeoutMillis, long intervalMillis) {
		// Execute operation once, no waiting - for unit tests
		try {
			def result = operation()
			return result
		} catch (Exception e) {
			return null
		}
	}
}
