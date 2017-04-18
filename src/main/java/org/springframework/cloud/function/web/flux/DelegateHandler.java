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

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.util.StringUtils;

abstract class DelegateHandler<T> {
	private final ListableBeanFactory factory;
	private ContextFunctionPostProcessor processor;
	private final Object source;
	private final ConversionService conversionService;

	public DelegateHandler(ListableBeanFactory factory, Object source) {
		this.factory = factory;
		this.source = source;
		this.conversionService = factory.getBean(ConversionService.class);
	}

	public String[] getNames() {
		if (source instanceof String) {
			return StringUtils.addStringToArray(factory.getAliases((String) source),
					(String) source);
		}
		else {
			return factory.getBeanNamesForType(source.getClass());
		}
	}

	public Object convert(String input) {
		return conversionService.convert(input, type());
	}

	public Class<?> type() {
		return (Class<?>) processor().findInputType(handler());
	}

	public T handler() {
		return processor().handler(source);
	}

	private ContextFunctionPostProcessor processor() {
		if (processor == null) {
			processor = factory.getBean(ContextFunctionPostProcessor.class);
		}
		return processor;
	}
	
}