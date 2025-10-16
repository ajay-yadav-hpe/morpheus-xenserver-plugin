package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.xen.support.TestSpecBase
import spock.lang.Subject

class XenserverBackupProviderSpec extends TestSpecBase {

	XenserverPlugin plugin
	MorpheusContext context

	def setup() {
		plugin = GroovyMock(XenserverPlugin)
		context = GroovyMock(MorpheusContext)
	}

	def "constructor registers backup type provider and scopes it"() {
		given:
		XenserverBackupProvider provider

		when:
		provider = GroovySpy(XenserverBackupProvider, constructorArgs: [plugin, context])

		then:
		1 * plugin.registerProvider({ it instanceof XenserverBackupTypeProvider })
	}
}
