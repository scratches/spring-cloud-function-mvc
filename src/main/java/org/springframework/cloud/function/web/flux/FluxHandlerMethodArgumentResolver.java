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

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Converter for request bodies of type <code>Flux<String></code>.
 * 
 * @author Dave Syer
 *
 */
class FluxHandlerMethodArgumentResolver
		implements HandlerMethodArgumentResolver, Ordered {

	private final ObjectMapper mapper;

	public FluxHandlerMethodArgumentResolver(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter,
			ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory) throws Exception {
		DelegateHandler<?> handler = (DelegateHandler<?>) webRequest.getAttribute(
				FunctionHandlerMapping.HANDLER, NativeWebRequest.SCOPE_REQUEST);
		Class<?> type = handler.type();
		if (type == null) {
			type = Object.class;
		}
		List<Object> body = mapper.readValue(
				webRequest.getNativeRequest(HttpServletRequest.class).getInputStream(),
				mapper.getTypeFactory().constructCollectionLikeType(ArrayList.class,
						type));
		return new FluxRequest<Object>(body);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return FluxRequest.class.isAssignableFrom(parameter.getParameterType());
	}

}
