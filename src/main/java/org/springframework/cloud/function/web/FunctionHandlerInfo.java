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

package org.springframework.cloud.function.web;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * @author Dave Syer
 *
 */
public class FunctionHandlerInfo {

	private final List<String> names;
	private final List<HttpMethod> methods;
	private final Method method;
	private final ResolvableType inputType;
	private final ResolvableType outputType;
	private String prefix;

	public FunctionHandlerInfo(String prefix, Method method, List<String> names,
			List<HttpMethod> methods) {
		this.prefix = prefix;
		this.method = method;
		this.names = new ArrayList<>(names);
		this.methods = new ArrayList<>(methods);
		this.inputType = getInputType(method);
		this.outputType = getOutputType(method);
	}

	private ResolvableType getOutputType(Method method) {
		if (method.getParameterCount() > 0) {
			return ResolvableType.forMethodParameter(method, 0);
		}
		return null;
	}

	private ResolvableType getInputType(Method method) {
		if (method.getParameterCount() > 0) {
			return ResolvableType.forMethodParameter(method, 0);
		}
		return null;
	}

	public FunctionHandlerInfo match(HttpServletRequest request) {
		if (!methods.contains(HttpMethod.valueOf(request.getMethod()))) {
			return null;
		}
		String path = ServletUriComponentsBuilder.fromRequest(request).build().getPath();
		String name = path;
		while (name.startsWith("/")) {
			name = name.substring(1);
		}
		if (!names.contains(name)) {
			return null;
		}
		return new FunctionHandlerInfo(this.prefix, this.method,
				Collections.singletonList(path),
				Collections.singletonList(HttpMethod.valueOf(request.getMethod())));
	}

	@Override
	public String toString() {
		return "FunctionHandler [paths=" + names + ", methods=" + methods + "]";
	}

	public Set<String> getPaths() {
		Set<String> paths = new LinkedHashSet<>();
		for (String name : names) {
			if (inputType != null && outputType != null) {
				paths.add(prefix + "/" + name + "/{id}");
			}
			paths.add(prefix + "/" + name);
		}
		return paths;
	}

}
