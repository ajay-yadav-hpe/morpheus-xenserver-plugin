package com.morpheusdata.xen.support

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.lang.GroovyObjectSupport
import spock.lang.Specification

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Base Spock specification providing common factory and assertion helpers for plugin tests.
 */
abstract class TestSpecBase extends Specification {

	protected void stubServices(MorpheusContext context, Map<String, Object> services = [:]) {
		def directory = new DirectoryStub(services)
		context.getServices() >> directory.asType(com.morpheusdata.core.MorpheusServices)
	}

	protected void stubAsync(MorpheusContext context, Map<String, Object> asyncServices = [:]) {
		def directory = new DirectoryStub(asyncServices)
		context.getAsync() >> directory.asType(com.morpheusdata.core.MorpheusAsyncServices)
	}

	/**
	 * Creates a {@link Cloud} with sensible defaults for unit testing.
	 * @param overrides optional property overrides applied via Groovy map constructor
	 * @return configured {@link Cloud}
	 */
	protected Cloud newCloud(Map overrides = [:]) {
		Map defaults = [
			id    : 1L,
			name  : 'Test Cloud',
			code  : 'test-cloud',
			owner : new Account(id: 1L)
		]
		return new Cloud((defaults + overrides))
	}

	/**
	 * Convenience for building a {@link ServiceResponse} success payload.
	 */
	protected ServiceResponse successResponse(Object data = null) {
		return ServiceResponse.success(data)
	}

	/**
	 * Convenience for building a {@link ServiceResponse} error payload.
	 */
	protected ServiceResponse errorResponse(String message = 'error', Object data = null, Map opts = [:]) {
		return ServiceResponse.error(message, data, opts)
	}

	/**
	 * Builds an {@link OptionType} with defaults, useful for verifying option definitions.
	 */
	protected OptionType newOptionType(Map overrides = [:]) {
		Map defaults = [
			name      : 'option',
			code      : 'option.code',
			fieldName : 'optionField',
			fieldLabel: 'Option Field',
			inputType : OptionType.InputType.TEXT
		]
		return new OptionType(defaults + overrides)
	}

	/**
	 * Utility for constructing {@link Icon} objects when validating icon metadata.
	 */
	protected Icon newIcon(Map overrides = [:]) {
		Map defaults = [path: 'icon.svg', darkPath: 'icon-dark.svg']
		return new Icon(defaults + overrides)
	}

	private static Object adaptValue(Object value, Class<?> targetType = Object) {
		if (value == null) {
			return null
		}
		if (targetType && targetType != Object && targetType.isInstance(value)) {
			return value
		}
		if (value instanceof DirectoryStub) {
			return targetType && targetType != Object ? (value as DirectoryStub).asType(targetType) : value
		}
		if (value instanceof Map) {
			if (targetType && targetType != Object && targetType.isAssignableFrom(Map)) {
				return value
			}
			DirectoryStub directory = new DirectoryStub(value as Map<String, Object>)
			return targetType && targetType != Object ? directory.asType(targetType) : directory
		}
		return value
	}

	private static class DirectoryStub extends GroovyObjectSupport {
		private final Map<String, Object> delegates

		DirectoryStub(Map<String, Object> delegates) {
			this.delegates = delegates ?: [:]
		}

        @Override
        Object getProperty(String name) {
            if (!delegates.containsKey(name)) {
                throw new MissingPropertyException("No test double configured for '${name}'")
            }
            return adaptValue(delegates[name])
        }

		@Override
		Object invokeMethod(String name, Object args) {
			Object target = delegates[name]
			if (target instanceof Closure) {
				Object[] argArray
				if (args == null) {
					argArray = [] as Object[]
				} else if (args instanceof Object[]) {
					argArray = (Object[]) args
				} else {
					argArray = [args] as Object[]
				}
				return (target as Closure).call(*argArray)
			}
			return super.invokeMethod(name, args)
		}

		Object asType(Class clazz) {
			if (clazz.isInstance(this)) {
				return this
			}
			if (clazz.isInterface()) {
				return Proxy.newProxyInstance(
					clazz.classLoader,
					[clazz] as Class<?>[],
					new ServiceInvocationHandler(delegates)
				)
			}
			return super.asType(clazz)
		}
	}

	private static class ServiceInvocationHandler implements InvocationHandler {
		private final Map<String, Object> delegates

		ServiceInvocationHandler(Map<String, Object> delegates) {
			this.delegates = delegates ?: [:]
		}

		@Override
		Object invoke(Object proxy, Method method, Object[] args) {
			if (method.declaringClass == Object) {
				return handleObjectMethod(proxy, method, args)
			}
			String key = resolveKey(method.name)
			if (!delegates.containsKey(key)) {
				throw new MissingPropertyException("No test double configured for '${key}'")
			}
			Object value = delegates[key]
			if (value instanceof Closure) {
				Object[] safeArgs = args ?: [] as Object[]
				return (value as Closure).call(*safeArgs)
			}
			if (args && args.length > 0) {
				throw new UnsupportedOperationException("Test service stub received unexpected arguments for '${method.name}'")
			}
			return adaptValue(value, method.returnType)
		}

		private String resolveKey(String methodName) {
			if (delegates.containsKey(methodName)) {
				return methodName
			}
			if (methodName.startsWith('get') && methodName.length() > 3) {
				String property = methodName.substring(3)
				return property[0].toLowerCase() + property.substring(1)
			}
			return methodName
		}

		private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
			switch (method.name) {
				case 'toString':
					return "ServiceInvocationHandler(${delegates.keySet().join(',')})"
				case 'hashCode':
					return System.identityHashCode(proxy)
				case 'equals':
					return proxy.is(args ? args[0] : null)
				default:
					return method.invoke(this, args)
			}
		}
	}

}
