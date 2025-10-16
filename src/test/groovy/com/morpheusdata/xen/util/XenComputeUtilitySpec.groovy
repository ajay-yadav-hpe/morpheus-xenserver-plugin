package com.morpheusdata.xen.util

import com.morpheusdata.model.Cloud
import com.morpheusdata.core.util.NetworkUtility
import spock.lang.Specification

class XenComputeUtilitySpec extends Specification {

	def "getXenApiUrl honors secure flag"() {
		given:
		Cloud cloud = new Cloud(configMap: [apiUrl: 'https://xcp.example.com'])

		expect:
		XenComputeUtility.getXenApiUrl(cloud) == 'https://xcp.example.com'
		XenComputeUtility.getXenApiUrl(cloud, true) == 'https://xcp.example.com'
	}

	def "getXenApiHost strips protocol and appends custom port"() {
		given:
		Cloud cloud = new Cloud(configMap: [apiUrl: 'http://xcp.example.com', apiPort: '8443'])

		when:
		def host = XenComputeUtility.getXenApiHost(cloud)

		then:
		host.address == 'xcp.example.com:8443'
		!host.isSecure
	}

	def "isValidIpv6Address validates address formats"() {
		given:
		GroovySpy(NetworkUtility, global: true)
		NetworkUtility.validateIpAddr('2001:0db8:85a3:0000:0000:8a2e:0370:7334', false) >> false
		NetworkUtility.validateIpAddr('2001:0db8:85a3:0000:0000:8a2e:0370:7334', true) >> true
		NetworkUtility.validateIpAddr('192.168.1.1', false) >> true
		NetworkUtility.validateIpAddr('192.168.1.1', true) >> false

		expect:
		XenComputeUtility.isValidIpv6Address('2001:0db8:85a3:0000:0000:8a2e:0370:7334')
		!XenComputeUtility.isValidIpv6Address('192.168.1.1')
	}

	def "buildSyncLists separates add update and remove collections"() {
		given:
		def existing = [[id: 1, name: 'existing']]
		def master = [[id: 1, name: 'updated'], [id: 2, name: 'new']]

		when:
		def result = XenComputeUtility.buildSyncLists(existing, master, { e, m -> e.id == m.id })

		then:
		result.updateList*.masterItem.name == ['updated']
		result.addList*.name == ['new']
		result.removeList == []
	}

	def "validateServerConfig identifies missing fields"() {
		given:
		def options = [imageId: null, networkInterfaces: [[network: [:]]], nodeCount: null]

		when:
		def result = XenComputeUtility.validateServerConfig(options)

		then:
		!result.success
		result.errors*.field.containsAll(['networkInterface', 'imageId', 'nodeCount'])
	}
}
