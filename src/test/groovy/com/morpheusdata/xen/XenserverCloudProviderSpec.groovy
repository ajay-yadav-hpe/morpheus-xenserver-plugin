package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkType
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import com.morpheusdata.core.util.ConnectionUtils
import spock.lang.Subject

class XenserverCloudProviderSpec extends TestSpecBase {

	MorpheusContext context
	XenserverPlugin plugin
	Map networkService

	def setup() {
		plugin = GroovyMock(XenserverPlugin)
		networkService = [list: { query -> [] }]
		context = GroovyMock(MorpheusContext)
		stubServices(context, [network: networkService])
		stubAsync(context)
		provider = new XenserverCloudProvider(plugin, context)
	}

	@Subject
	XenserverCloudProvider provider

	def "getDescription returns expected label"() {
		expect:
		provider.description == 'XCP-ng'
	}

	def "getIcon metadata is populated"() {
		when:
		def icon = provider.icon

		then:
		icon.path == 'xcpng-light.svg'
		icon.darkPath == 'xcpng-dark.svg'

		and:
		def circular = provider.circularIcon
		circular.path == 'xcpng-circular-light.svg'
		circular.darkPath == 'xcpng-circular-dark.svg'
	}

	def "option types include credential and username fields"() {
		when:
		def optionTypes = provider.optionTypes

		then:
		optionTypes*.code.containsAll([
			'zoneType.xen.apiUrl',
			'zoneType.xen.username',
			'zoneType.xen.password',
			'zoneType.xen.credential'
		])
	}

	def "available provision providers are sourced from plugin"() {
		given:
		def expected = [GroovyMock(ProvisionProvider)]

		when:
		plugin.getProvidersByType(ProvisionProvider) >> expected

		then:
		provider.availableProvisionProviders == expected
	}

	def "available backup providers are sourced from plugin"() {
		given:
		def expected = [new XenserverBackupProvider(plugin, context)]

		when:
		plugin.getProvidersByType(com.morpheusdata.core.backup.BackupProvider) >> expected

		then:
		provider.availableBackupProviders == expected
	}

	def "network types include custom xen network"() {
		given:
		def existing = [new NetworkType(code: 'dockerBridge')]
		networkService.list = { query -> existing }

		when:
		def networks = provider.networkTypes

		then:
		networks.any { it.code == 'xenNetwork' && it.overlay == false }
		networks.collect { it.code }.containsAll(existing*.code)
	}

	def "initializeCloud refreshes when connectivity succeeds"() {
		given:
		Cloud cloud = newCloud(configMap: [apiUrl: 'https://xcp.example.com'])
		GroovySpy(ConnectionUtils, global: true)
		GroovySpy(XenComputeUtility, global: true)

		and:
		XenserverCloudProvider spyProvider = GroovySpy(XenserverCloudProvider, constructorArgs: [plugin, context]) {
			refresh(_ as Cloud) >> ServiceResponse.success()
		}
		ConnectionUtils.testHostConnectivity(_, _, false, true, _) >> true
		XenComputeUtility.getXenApiUrl(cloud) >> 'https://xcp.example.com'

		when:
		def response = spyProvider.initializeCloud(cloud)

		then:
		response.success
		1 * spyProvider.refresh(cloud)
	}

	def "initializeCloud returns error when host unreachable"() {
		given:
		Cloud cloud = newCloud(configMap: [apiUrl: 'http://xcp.example.com'])
		GroovySpy(ConnectionUtils, global: true)
		GroovySpy(XenComputeUtility, global: true)
		ConnectionUtils.testHostConnectivity(_, _, false, true, _) >> false

		and:
		XenComputeUtility.getXenApiUrl(cloud) >> 'http://xcp.example.com'

		when:
		def response = provider.initializeCloud(cloud)

		then:
		!response.success
	}
}
