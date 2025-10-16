package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Account
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.UpdateDataUtil
import spock.lang.Subject

class XenserverPluginSpec extends TestSpecBase {

	@Subject
	XenserverPlugin plugin

	def setup() {
		plugin = GroovySpy(XenserverPlugin, constructorArgs: [])
	}

	def "initialize registers providers and updates container stat types"() {
		given:
		def context = GroovyMock(MorpheusContext)
		GroovySpy(UpdateDataUtil, global: true)
		plugin.@morpheus = context
		plugin.getMorpheus() >> context

		when:
		plugin.initialize()

		then:
		1 * plugin.registerProviders(_, _, _, _)
		1 * UpdateDataUtil.updateContainerTypeStatTypeCode(context)
	}

	def "getAuthConfig merges credential data and cloud config"() {
		given:
		Cloud cloud = newCloud([
			id         : 42L,
			account    : new Account(id: 7L),
			configMap  : [apiUrl: 'https://xcp.example.com', username: 'cfgUser', password: 'cfgPass'],
			accountCredentialLoaded: true,
			accountCredentialData : [username: 'credUser', password: 'credPass']
		])

		def context = GroovyMock(MorpheusContext)
		stubServices(context, [
			cloud: [get: { Long id -> cloud }]
		])
		stubAsync(context)
		plugin.@morpheus = context

		when:
		def result = plugin.getAuthConfig(cloud)

		then:
		result.hostname == 'xcp.example.com'
		result.username == 'credUser'
		result.password == 'credPass'
		result.isSecure == true
		result.apiVersion
	}

	def "getAuthConfig falls back to config map when credential data missing"() {
		given:
		Cloud cloud = newCloud([
			configMap: [apiUrl: 'http://xcp.example.com', apiPort: '8443', username: 'cfgUser', password: 'cfgPass'],
			accountCredentialLoaded: true,
			accountCredentialData : [:]
		])
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context)
		plugin.@morpheus = context

		when:
		def result = plugin.getAuthConfig(cloud)

		then:
		result.hostname == 'xcp.example.com:8443'
		result.username == 'cfgUser'
		result.password == 'cfgPass'
		result.isSecure == false
	}

	// def "getAuthConfig for args uses credential service when type not local"() {
	// 	given:
	// 	def args = [credential: [type: 'remote'], config: [username: 'ignored', password: 'ignored']]
	// 	def context = GroovyMock(MorpheusContext)
	// 	plugin.@morpheus = context
	// 	assert args.credential.type == 'remote'

	// 	when:
	// 	def result = plugin.getAuthConfig(args)

	// 	then:
	// 	1 * context.services.accountCredential.loadCredentialConfig(args.credential, _) >> [data: [username: 'svcUser', password: 'svcPass']]
	// 	result.username == 'svcUser'
	// 	result.password == 'svcPass'
	// }

	def "getAuthConfig for args falls back to config when credential type local"() {
		given:
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context)
		plugin.@morpheus = context
		def args = [credential: [type: 'local'], config: [username: 'cfgUser', password: 'cfgPass']]

		when:
		def result = plugin.getAuthConfig(args)

		then:
		result.username == 'cfgUser'
		result.password == 'cfgPass'
	}
}
