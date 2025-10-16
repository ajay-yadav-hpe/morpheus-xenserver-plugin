package com.morpheusdata.xen.support

import groovy.transform.CompileStatic

/**
 * Provides utilities for temporarily overriding Groovy metaclass behaviour, typically used when
 * stubbing static helpers such as {@code XenComputeUtility}. Ensures meta class overrides are
 * reset after each specification to avoid cross-test contamination.
 */
@CompileStatic
class StaticMockSupport {

	private static final Map<Class<?>, MetaClass> originalMetaClasses = [:]

	static void captureMetaClass(Class<?> target) {
		originalMetaClasses.putIfAbsent(target, target.metaClass)
	}

	static void restoreMetaClass(Class<?> target) {
		MetaClass original = originalMetaClasses.remove(target)
		if (original) {
			GroovySystem.metaClassRegistry.setMetaClass(target, original)
		}
	}
}
