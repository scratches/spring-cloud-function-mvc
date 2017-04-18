/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.web.flux;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.cloud.function.support.FluxConsumer;
import org.springframework.cloud.function.support.FluxFunction;
import org.springframework.cloud.function.support.FluxSupplier;
import org.springframework.cloud.function.support.FunctionUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import reactor.core.publisher.Flux;

@Component
class ContextFunctionPostProcessor
		implements BeanPostProcessor, BeanDefinitionRegistryPostProcessor {

	private Map<Object, String> functions = new HashMap<>();

	private Map<Object, Object> handlers = new HashMap<>();

	private BeanDefinitionRegistry registry;

	private ConfigurableListableBeanFactory factory;

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory factory)
			throws BeansException {
		this.factory = factory;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String name)
			throws BeansException {

		return bean;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String name)
			throws BeansException {
		Class<?> beanType = bean.getClass();
		if (Function.class.isAssignableFrom(beanType)
				|| Supplier.class.isAssignableFrom(beanType)
				|| Consumer.class.isAssignableFrom(beanType)) {
			this.functions.put(bean, name);
		}
		return bean;
	}

	private Class<?> findType(AbstractBeanDefinition definition, int index) {
		Object source = definition.getSource();
		Type param;
		if (source instanceof StandardMethodMetadata) {
			ParameterizedType type;
			type = (ParameterizedType) ((StandardMethodMetadata) source)
					.getIntrospectedMethod().getGenericReturnType();
			Type typeArgumentAtIndex = type.getActualTypeArguments()[index];
			if (typeArgumentAtIndex instanceof ParameterizedType) {
				param = ((ParameterizedType) typeArgumentAtIndex)
						.getActualTypeArguments()[0];
			}
			else {
				param = typeArgumentAtIndex;
			}
		}
		else if (source instanceof FileSystemResource) {
			try {
				Type type = ClassUtils.forName(definition.getBeanClassName(), null);
				if (type instanceof ParameterizedType) {
					Type typeArgumentAtIndex = ((ParameterizedType) type)
							.getActualTypeArguments()[index];
					if (typeArgumentAtIndex instanceof ParameterizedType) {
						param = ((ParameterizedType) typeArgumentAtIndex)
								.getActualTypeArguments()[0];
					}
					else {
						param = typeArgumentAtIndex;
					}
				}
				else {
					param = type;
				}
			}
			catch (ClassNotFoundException e) {
				throw new IllegalStateException("Cannot instrospect bean: " + definition,
						e);
			}
		}
		else {
			ResolvableType resolvable = (ResolvableType) getField(definition,
					"targetType");
			param = resolvable.getGeneric(index).getGeneric(0).getType();
		}
		if (param instanceof ParameterizedType) {
			ParameterizedType concrete = (ParameterizedType) param;
			param = concrete.getRawType();
		}
		return ClassUtils.resolveClassName(param.getTypeName(),
				registry.getClass().getClassLoader());
	}

	private Object getField(Object target, String name) {
		Field field = ReflectionUtils.findField(target.getClass(), name);
		ReflectionUtils.makeAccessible(field);
		return ReflectionUtils.getField(field, target);
	}

	public Class<?> findInputType(Object bean) {
		if (functions.containsKey(bean)) {
			return findType((AbstractBeanDefinition) registry
					.getBeanDefinition(functions.get(bean)), 0);
		}
		return null;
	}

	public Class<?> findOutputType(Object bean) {
		if (functions.containsKey(bean)) {
			return findType((AbstractBeanDefinition) registry
					.getBeanDefinition(functions.get(bean)), 1);
		}
		return null;
	}

	@SuppressWarnings({ "unchecked" })
	public <T> T handler(Object source) {
		if (!handlers.containsKey(source)) {
			T handler;
			if (source instanceof String) {
				handler = (T) factory.getBean((String) source);
			}
			else {
				handler = (T) source;
			}
			String name = functions.get(handler);
			if (handler instanceof Function
					&& !isFluxFunction(name, (Function<?, ?>) handler)) {
				handler = (T) new FluxFunction<Object, Object>(
						(Function<Object, Object>) handler);
			}
			else if (handler instanceof Consumer
					&& !isFluxConsumer(name, (Consumer<?>) handler)) {
				handler = (T) new FluxConsumer<Object>((Consumer<Object>) handler);
			}
			else if (handler instanceof Supplier
					&& !isFluxSupplier(name, (Supplier<?>) handler)) {
				handler = (T) new FluxSupplier<Object>((Supplier<Object>) handler);
			}
			handlers.put(source, handler);
		}
		return (T) handlers.get(source);
	}

	private boolean isFluxFunction(String name, Function<?, ?> function) {
		Boolean fluxTypes = this.hasFluxTypes(name, 2);
		return (fluxTypes != null) ? fluxTypes : FunctionUtils.isFluxFunction(function);
	}

	private boolean isFluxConsumer(String name, Consumer<?> consumer) {
		Boolean fluxTypes = this.hasFluxTypes(name, 1);
		return (fluxTypes != null) ? fluxTypes : FunctionUtils.isFluxConsumer(consumer);
	}

	private boolean isFluxSupplier(String name, Supplier<?> supplier) {
		Boolean fluxTypes = this.hasFluxTypes(name, 1);
		return (fluxTypes != null) ? fluxTypes : FunctionUtils.isFluxSupplier(supplier);
	}

	private Boolean hasFluxTypes(String name, int numTypes) {
		if (this.registry.containsBeanDefinition(name)) {
			BeanDefinition beanDefinition = this.registry.getBeanDefinition(name);
			Object source = beanDefinition.getSource();
			if (source instanceof StandardMethodMetadata) {
				StandardMethodMetadata metadata = (StandardMethodMetadata) source;
				Type returnType = metadata.getIntrospectedMethod().getGenericReturnType();
				if (returnType instanceof ParameterizedType) {
					Type[] types = ((ParameterizedType) returnType)
							.getActualTypeArguments();
					if (types != null && types.length == numTypes) {
						String fluxClassName = Flux.class.getName();
						for (Type t : types) {
							if (!(t.getTypeName().startsWith(fluxClassName))) {
								return false;
							}
						}
						return true;
					}
				}
			}
		}
		return null;
	}
}