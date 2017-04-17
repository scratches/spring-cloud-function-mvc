/*
 * Copyright 2013-2017 the original author or authors.
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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.AsyncHandlerMethodReturnValueHandler;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import reactor.core.publisher.Flux;

/**
 * @author Dave Syer
 * @author Mark Fisher
 */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnClass({ Flux.class, AsyncHandlerMethodReturnValueHandler.class })
public class ReactorAutoConfiguration {

	@Autowired
	private ApplicationContext context;

	@Configuration
	protected static class FluxMessageConverterConfiguration {

		@Bean
		public FluxHandlerMethodArgumentResolver fluxHttpMessageConverter(
				ObjectMapper mapper) {
			return new FluxHandlerMethodArgumentResolver(mapper);
		}

	}
	
	@Bean
	public BeanPostProcessor fluxRequestMappingHandlerAdapterProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName)
					throws BeansException {
				if (bean instanceof RequestMappingHandlerAdapter) {
					RequestMappingHandlerAdapter adapter = (RequestMappingHandlerAdapter) bean;
					List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>(adapter.getArgumentResolvers());
					resolvers.add(0,context.getBean(FluxHandlerMethodArgumentResolver.class)); 
					adapter.setArgumentResolvers(resolvers);
				}
				return bean;
			}
		};
	}

}
