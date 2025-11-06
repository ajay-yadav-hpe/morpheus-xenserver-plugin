package com.morpheusdata.xen.util

import groovy.util.logging.Slf4j

/**
 * Production implementation of PollingStrategy that uses Thread.sleep()
 * for actual waiting between polling attempts.
 */
@Slf4j
class DefaultPollingStrategy implements PollingStrategy {

	@Override
	boolean pollUntil(Closure<Boolean> condition, long timeoutMillis, long intervalMillis) {
		long startTime = System.currentTimeMillis()
		int attempts = 0
		long maxAttempts = timeoutMillis / intervalMillis

		while (attempts < maxAttempts) {
			attempts++
			try {
				if (condition()) {
					log.debug("Polling succeeded after ${attempts} attempts")
					return true
				}
			} catch (Exception e) {
				log.warn("Error during polling attempt ${attempts}: ${e.message}")
				log.debug("Polling error details", e)
			}

			// Check if we've exceeded timeout
			if (System.currentTimeMillis() - startTime >= timeoutMillis) {
				log.warn("Polling timed out after ${attempts} attempts")
				break
			}

			Thread.sleep(intervalMillis)
		}

		log.warn("Polling failed after ${attempts} attempts (max: ${maxAttempts})")
		return false
	}

	@Override
	def pollForResult(Closure operation, Closure<Boolean> successCondition, long timeoutMillis, long intervalMillis) {
		long startTime = System.currentTimeMillis()
		int attempts = 0
		long maxAttempts = timeoutMillis / intervalMillis
		def result = null

		while (attempts < maxAttempts) {
			attempts++
			try {
				result = operation()
				if (successCondition(result)) {
					log.debug("Polling for result succeeded after ${attempts} attempts")
					return result
				}
			} catch (Exception e) {
				log.warn("Error during polling operation attempt ${attempts}: ${e.message}")
				log.debug("Polling operation error details", e)
			}

			// Check if we've exceeded timeout
			if (System.currentTimeMillis() - startTime >= timeoutMillis) {
				log.warn("Polling for result timed out after ${attempts} attempts")
				break
			}

			Thread.sleep(intervalMillis)
		}

		log.warn("Polling for result failed after ${attempts} attempts (max: ${maxAttempts})")
		return result // Return last result even if unsuccessful
	}
}
