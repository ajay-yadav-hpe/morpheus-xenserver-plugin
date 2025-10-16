package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.xen.support.TestSpecBase
import spock.lang.Subject

class XenserverBackupTypeProviderSpec extends TestSpecBase {

	XenserverPlugin plugin = GroovyMock(XenserverPlugin)
	MorpheusContext context = GroovyMock(MorpheusContext)

	@Subject
	XenserverBackupTypeProvider provider = new XenserverBackupTypeProvider(plugin, context)

	def "metadata flags match snapshot expectations"() {
		expect:
		provider.code == 'xenSnapshot'
		provider.name == 'XCP-ng VM Snapshot'
		provider.copyToStore
		provider.downloadEnabled
		provider.restoreExistingEnabled
		provider.restoreNewEnabled
		provider.snapshot
	}

	def "execution provider is instantiated lazily"() {
		when:
		BackupExecutionProvider execution1 = provider.executionProvider
		BackupExecutionProvider execution2 = provider.executionProvider

		then:
		execution1 instanceof XenserverBackupExecutionProvider
		execution1.is(execution2)
	}

	def "restore provider is instantiated lazily"() {
		when:
		BackupRestoreProvider restore1 = provider.restoreProvider
		BackupRestoreProvider restore2 = provider.restoreProvider

		then:
		restore1 instanceof XenserverBackupRestoreProvider
		restore1.is(restore2)
	}

	def "refresh returns success response"() {
		expect:
		provider.refresh([:], null).success
	}

	def "clean returns success response"() {
		expect:
		provider.clean(null, [:]).success
	}
}
