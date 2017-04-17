/*
 * Copyright 2012-2015 the original author or authors.
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

package org.springframework.cloud.function.web;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author Dave Syer
 *
 */
@Configuration
@ConditionalOnClass(RequestMappingHandlerMapping.class)
public class FunctionHandlerMapping extends RequestMappingHandlerMapping {

	@Value("${spring.cloud.function.web.path:}")
	private String prefix = "";
	private ListableBeanFactory beanFactory;

	@Autowired
	public FunctionHandlerMapping(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		setOrder(super.getOrder() - 5);
	}

	@Override
	public void afterPropertiesSet() {
		super.afterPropertiesSet();
		while (prefix.endsWith("/")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		if (prefix.length() > 0 && !prefix.startsWith("/")) {
			prefix = "/" + prefix;
		}
	}

	@Override
	protected boolean isHandler(Class<?> beanType) {
		return Function.class.isAssignableFrom(beanType)
				|| Supplier.class.isAssignableFrom(beanType)
				|| Consumer.class.isAssignableFrom(beanType);
	}

	private Object createDelegate(Class<?> type, Object handler) {
		if (Delegate.class.isAssignableFrom(type)) {
			return handler;
		}
		if (Function.class.isAssignableFrom(type)) {
			return new FunctionDelegate(beanFactory, handler);
		}
		if (Consumer.class.isAssignableFrom(type)) {
			return new ConsumerDelegate(beanFactory, handler);
		}
		if (Supplier.class.isAssignableFrom(type)) {
			return new SupplierDelegate(beanFactory, handler);
		}
		return handler;
	}

	@Override
	protected void detectHandlerMethods(Object handler) {
		Class<?> handlerType = (handler instanceof String
				? getApplicationContext().getType((String) handler) : handler.getClass());
		final Class<?> userType = ClassUtils.getUserClass(handlerType);
		Object delegate = createDelegate(userType, handler);
		super.detectHandlerMethods(delegate);
	}
	
	@Override
	protected void registerHandlerMethod(Object handler, Method method,
			RequestMappingInfo mapping) {
		List<String> paths = new ArrayList<>();
		Delegate<?> delegate = (Delegate<?>) handler;
		for (String name : delegate.getNames()) {
			if (method.getName().equals("single")) {
				name = name + "/{input}";
			}
			paths.add("/" + name);
		}
		mapping = mapping.combine(RequestMappingInfo.paths(paths.toArray(new String[0])).build());
		super.registerHandlerMethod(handler, method, mapping);
	}

	static abstract class Delegate<T> {
		private T handler;
		private ListableBeanFactory factory;
		private Object source;

		public Delegate(ListableBeanFactory factory, Object source) {
			this.factory = factory;
			this.source = source;
		}

		public String[] getNames() {
			if (source instanceof String) {
				return StringUtils.addStringToArray(factory.getAliases((String) source), (String) source);
			}
			else {
				return factory.getBeanNamesForType(source.getClass());
			}
		}

		@SuppressWarnings("unchecked")
		public T handler() {
			if (handler == null) {
				if (source instanceof String) {
					handler = (T) factory.getBean((String) source);
				}
				else {
					handler = (T) source;
				}
			}
			return handler;
		}

	}

	public static class SupplierDelegate extends Delegate<Supplier<Flux<?>>> {
		public SupplierDelegate(ListableBeanFactory factory, Object source) {
			super(factory, source);
		}

		@GetMapping
		@ResponseBody
		public Flux<?> get() {
			return handler().get();
		}
	}

	public static class FunctionDelegate extends Delegate<Function<Flux<?>, Flux<?>>> {

		public FunctionDelegate(ListableBeanFactory factory, Object source) {
			super(factory, source);
		}

		@PostMapping
		@ResponseBody
		public Flux<?> apply(@RequestBody List<?> input) {
			return handler().apply(Flux.fromIterable(input));
		}

		@GetMapping
		@ResponseBody
		public Mono<?> single(@PathVariable String input) {
			return Mono.from(handler().apply(Flux.just(input)));
		}

	}

	public static class ConsumerDelegate extends Delegate<Consumer<Flux<?>>> {

		public ConsumerDelegate(ListableBeanFactory factory, Object source) {
			super(factory, source);
		}

		@PostMapping
		public void accept(@RequestBody List<?> input) {
			handler().accept(Flux.fromIterable(input));
		}
	}

}

@Component
class ContextFunctionPostProcessor
		implements BeanPostProcessor, BeanDefinitionRegistryPostProcessor {

	private Map<Object, String> functions = new HashMap<>();

	private BeanDefinitionRegistry registry;

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory factory)
			throws BeansException {
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

}
