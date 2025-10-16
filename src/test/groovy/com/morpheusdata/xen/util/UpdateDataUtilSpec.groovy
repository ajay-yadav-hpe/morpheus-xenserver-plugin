package com.morpheusdata.xen.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.WorkloadType
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import com.morpheusdata.xen.support.TestSpecBase

class UpdateDataUtilSpec extends TestSpecBase {

	def "updates stat type code for xen workloads"() {
		given:
		WorkloadType workloadType = new WorkloadType(statTypeCode: 'xen')
		List saved = []

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			workloadType: [
				list    : { query -> Observable.fromIterable([workloadType]) },
				bulkSave: { List items ->
					saved.addAll(items)
					Single.just(items)
				}
			]
		])

		when:
		UpdateDataUtil.updateContainerTypeStatTypeCode(context)

		then:
		saved.size() == 1
		saved.first().statTypeCode == 'vm'
	}

	def "handles exceptions without throwing"() {
		given:
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			workloadType: [
				list    : { query -> { throw new RuntimeException('boom') }() },
				bulkSave: { List items -> Single.just(items) }
			]
		])

		when:
		UpdateDataUtil.updateContainerTypeStatTypeCode(context)

		then:
		notThrown(Exception)
	}
}
