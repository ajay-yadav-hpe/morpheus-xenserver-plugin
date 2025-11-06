package com.morpheusdata.xen.util

import spock.lang.Specification

class DefaultPollingStrategySpec extends Specification {

	DefaultPollingStrategy strategy

	def setup() {
		strategy = new DefaultPollingStrategy()
	}

	def "pollUntil returns true when condition met immediately"() {
		given:
		int callCount = 0
		def condition = { callCount++; true }

		when:
		boolean result = strategy.pollUntil(condition, 5000, 100)

		then:
		result == true
		callCount == 1
	}

	def "pollUntil returns true when condition met after retries"() {
		given:
		int callCount = 0
		def condition = { callCount++; callCount >= 3 }

		when:
		boolean result = strategy.pollUntil(condition, 5000, 100)

		then:
		result == true
		callCount == 3
	}

	def "pollUntil returns false when condition never met"() {
		given:
		int callCount = 0
		def condition = { callCount++; false }

		when:
		boolean result = strategy.pollUntil(condition, 500, 100)

		then:
		result == false
		callCount >= 4 // Should attempt multiple times
	}

	def "pollUntil handles exceptions in condition"() {
		given:
		int callCount = 0
		def condition = {
			callCount++
			if (callCount < 3) {
				throw new RuntimeException("Test error")
			}
			true
		}

		when:
		boolean result = strategy.pollUntil(condition, 5000, 100)

		then:
		result == true
		callCount == 3
	}

	def "pollUntil respects timeout"() {
		given:
		int callCount = 0
		def condition = { callCount++; false }

		when:
		long startTime = System.currentTimeMillis()
		boolean result = strategy.pollUntil(condition, 300, 100)
		long elapsed = System.currentTimeMillis() - startTime

		then:
		result == false
		elapsed >= 300
		elapsed < 500 // Should not take much longer than timeout
	}

	def "pollForResult returns result when condition met immediately"() {
		given:
		int callCount = 0
		def operation = { callCount++; "success" }
		def successCondition = { it == "success" }

		when:
		def result = strategy.pollForResult(operation, successCondition, 5000, 100)

		then:
		result == "success"
		callCount == 1
	}

	def "pollForResult returns result when condition met after retries"() {
		given:
		int callCount = 0
		def operation = {
			callCount++
			callCount >= 3 ? "success" : "pending"
		}
		def successCondition = { it == "success" }

		when:
		def result = strategy.pollForResult(operation, successCondition, 5000, 100)

		then:
		result == "success"
		callCount == 3
	}

	def "pollForResult returns last result when condition never met"() {
		given:
		int callCount = 0
		def operation = {
			callCount++
			"attempt-${callCount}"
		}
		def successCondition = { false }

		when:
		def result = strategy.pollForResult(operation, successCondition, 500, 100)

		then:
		result != null
		result.startsWith("attempt-")
		callCount >= 4
	}

	def "pollForResult handles exceptions in operation"() {
		given:
		int callCount = 0
		def operation = {
			callCount++
			if (callCount < 3) {
				throw new RuntimeException("Test error")
			}
			"success"
		}
		def successCondition = { it == "success" }

		when:
		def result = strategy.pollForResult(operation, successCondition, 5000, 100)

		then:
		result == "success"
		callCount == 3
	}

	def "pollForResult respects timeout"() {
		given:
		int callCount = 0
		def operation = { callCount++; "pending" }
		def successCondition = { false }

		when:
		long startTime = System.currentTimeMillis()
		def result = strategy.pollForResult(operation, successCondition, 300, 100)
		long elapsed = System.currentTimeMillis() - startTime

		then:
		result == "pending"
		elapsed >= 300
		elapsed < 500
	}

	def "pollUntil continues after exception until condition met"() {
		given:
		int callCount = 0
		def condition = {
			callCount++
			if (callCount < 5) {
				throw new RuntimeException("Keep failing")
			}
			true
		}

		when:
		boolean result = strategy.pollUntil(condition, 5000, 100)

		then:
		result == true
		callCount == 5
	}

	def "pollForResult returns null when all attempts fail with exceptions"() {
		given:
		int callCount = 0
		def operation = {
			callCount++
			throw new RuntimeException("Always fails")
		}
		def successCondition = { it != null }

		when:
		def result = strategy.pollForResult(operation, successCondition, 500, 100)

		then:
		result == null
		callCount >= 4
	}
}
