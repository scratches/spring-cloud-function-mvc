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

package org.springframework.cloud.function.web.flux;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
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

	public static final String HANDLER = FunctionHandlerMapping.class.getName()
			+ ".HANDLER";
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
		if (DelegateHandler.class.isAssignableFrom(type)) {
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
		DelegateHandler<?> delegate = (DelegateHandler<?>) handler;
		for (String name : delegate.getNames()) {
			if (method.getName().equals("single")) {
				name = name + "/{input}";
			}
			paths.add(prefix + "/" + name);
		}
		mapping = mapping
				.combine(RequestMappingInfo.paths(paths.toArray(new String[0])).build());
		super.registerHandlerMethod(handler, method, mapping);
	}

	@Override
	protected HandlerMethod lookupHandlerMethod(String lookupPath,
			HttpServletRequest request) throws Exception {
		HandlerMethod method = super.lookupHandlerMethod(lookupPath, request);
		if (method == null) {
			return null;
		}
		request.setAttribute(HANDLER, method.getBean());
		return method;
	}

	public static class SupplierDelegate extends DelegateHandler<Supplier<Flux<?>>> {
		public SupplierDelegate(ListableBeanFactory factory, Object source) {
			super(factory, source);
		}

		@GetMapping
		@ResponseBody
		public Flux<?> get() {
			return handler().get();
		}
	}

	public static class FunctionDelegate
			extends DelegateHandler<Function<Flux<?>, Flux<?>>> {

		public FunctionDelegate(ListableBeanFactory factory, Object source) {
			super(factory, source);
		}

		@PostMapping
		@ResponseBody
		public Flux<?> apply(@RequestBody FluxRequest<?> input) {
			return handler().apply(input.flux());
		}

		@GetMapping
		@ResponseBody
		public Mono<?> single(@PathVariable String input) {
			Object converted = convert(input);
			return Mono.from(handler().apply(Flux.just(converted)));
		}

	}

	public static class ConsumerDelegate extends DelegateHandler<Consumer<Flux<?>>> {

		public ConsumerDelegate(ListableBeanFactory factory, Object source) {
			super(factory, source);
		}

		@PostMapping
		@ResponseBody
		public ResponseEntity<List<?>> accept(@RequestBody FluxRequest<?> input) {
			handler().accept(input.flux());
			return ResponseEntity.accepted().body(input.body());
		}
	}

}
