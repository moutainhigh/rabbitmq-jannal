/*
 * Copyright 2002-2016 the original author or authors.
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

package org.springframework.amqp.support.converter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.ClassUtils;

/**
 * Maps to/from JSON using type information in the {@link MessageProperties}; the default
 * name of the message property containing the type is
 * {@value #DEFAULT_CLASSID_FIELD_NAME}. An optional property
 * {@link #setDefaultType(Class)} is provided that allows mapping to a statically defined
 * type, if no message property is found in the message properties.
 * {@link #setIdClassMapping(Map)} can be used to map tokens in the
 * {@value #DEFAULT_CLASSID_FIELD_NAME} header to classes. If this class is not a
 * Spring-managed bean, call {@link #afterPropertiesSet()} to set up the class to id
 * mapping.
 *
 * @author Mark Pollack
 * @author Gary Russell
 * @author Artem Bilan
 */
public class DefaultClassMapper implements ClassMapper, InitializingBean {

	public static final String DEFAULT_CLASSID_FIELD_NAME = "__TypeId__";

	private static final String DEFAULT_HASHTABLE_TYPE_ID = "Hashtable";

	private static final List<String> TRUSTED_PACKAGES =
			Arrays.asList(
					"java.util",
					"java.lang"
			);

	private final Set<String> trustedPackages = new LinkedHashSet<String>(TRUSTED_PACKAGES);

	private volatile Map<String, Class<?>> idClassMapping = new HashMap<String, Class<?>>();

	private volatile Map<Class<?>, String> classIdMapping = new HashMap<Class<?>, String>();

	private volatile Class<?> defaultHashtableClass = Hashtable.class;

	private volatile Class<?> defaultType;

	/**
	 * Construct an instance that trusts all packages.
	 */
	public DefaultClassMapper() {
		this("*");
	}

	/**
	 * Construct an instance that trusts certain packages, "*" means all.
	 * @param trustedPackages the packages to trust.
	 */
	public DefaultClassMapper(String... trustedPackages) {
		setTrustedPackages(trustedPackages);
	}

	/**
	 * The type returned by {@link #toClass(MessageProperties)} if no type information
	 * is found in the message properties.
	 * @param defaultType the defaultType to set
	 */
	public void setDefaultType(Class<?> defaultType) {
		this.defaultType = defaultType;
	}

	public void setDefaultHashtableClass(Class<?> defaultHashtableClass) {
		this.defaultHashtableClass = defaultHashtableClass;
	}

	public String getClassIdFieldName() {
		return DEFAULT_CLASSID_FIELD_NAME;
	}

	public void setIdClassMapping(Map<String, Class<?>> idClassMapping) {
		this.idClassMapping = idClassMapping;
	}

	/**
	 * Specify a set of packages to trust during deserialization.
	 * The asterisk ({@code *}) means trust all.
	 * @param trustedPackages the trusted Java packages for deserialization
	 * @since 1.6.11
	 */
	private void setTrustedPackages(String... trustedPackages) {
		if (trustedPackages != null) {
			for (String whiteListClass : trustedPackages) {
				if ("*".equals(whiteListClass)) {
					this.trustedPackages.clear();
					break;
				}
				else {
					if (this.trustedPackages.size() == 0) {
						this.trustedPackages.addAll(TRUSTED_PACKAGES);
					}
					this.trustedPackages.add(whiteListClass);
				}
			}
		}
	}

	private String fromClass(Class<?> classOfObjectToConvert) {
		if (this.classIdMapping.containsKey(classOfObjectToConvert)) {
			return this.classIdMapping.get(classOfObjectToConvert);
		}
		if (Map.class.isAssignableFrom(classOfObjectToConvert)) {
			return DEFAULT_HASHTABLE_TYPE_ID;
		}
		return classOfObjectToConvert.getName();
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		validateIdTypeMapping();
	}

	private void validateIdTypeMapping() {
		Map<String, Class<?>> finalIdClassMapping = new HashMap<String, Class<?>>();
		for (Entry<String, Class<?>> entry : this.idClassMapping.entrySet()) {
			String id = entry.getKey();
			Class<?> clazz = entry.getValue();
			finalIdClassMapping.put(id, clazz);
			this.classIdMapping.put(clazz, id);
		}
		this.idClassMapping = finalIdClassMapping;
	}

	@Override
	public void fromClass(Class<?> clazz, MessageProperties properties) {
		properties.getHeaders().put(getClassIdFieldName(), fromClass(clazz));
	}

	@Override
	public Class<?> toClass(MessageProperties properties) {
		Map<String, Object> headers = properties.getHeaders();
		Object classIdFieldNameValue = headers.get(getClassIdFieldName());
		String classId = null;
		if (classIdFieldNameValue != null) {
			classId = classIdFieldNameValue.toString();
		}
		if (classId == null) {
			if (this.defaultType != null) {
				return this.defaultType;
			}
			else {
				throw new MessageConversionException(
						"failed to convert Message content. Could not resolve "
								+ getClassIdFieldName() + " in header " +
								"and no defaultType provided");
			}
		}
		return toClass(classId);
	}

	private Class<?> toClass(String classId) {
		if (this.idClassMapping.containsKey(classId)) {
			return this.idClassMapping.get(classId);
		}
		if (classId.equals(DEFAULT_HASHTABLE_TYPE_ID)) {
			return this.defaultHashtableClass;
		}
		try {
			if (!isTrustedPackage(classId)) {
				throw new IllegalArgumentException("The class '" + classId + "' is not in the trusted packages: " +
						this.trustedPackages + ". " +
						"If you believe this class is safe to deserialize, please provide its name. " +
						"If the serialization is only done by a trusted source, you can also enable trust all (*).");
			}
			else {
				return ClassUtils.forName(classId, getClass().getClassLoader());
			}
		}
		catch (ClassNotFoundException e) {
			throw new MessageConversionException(
					"failed to resolve class name [" + classId + "]", e);
		}
		catch (LinkageError e) {
			throw new MessageConversionException(
					"failed to resolve class name [" + classId + "]", e);
		}
	}

	private boolean isTrustedPackage(String requestedType) {
		if (!this.trustedPackages.isEmpty()) {
			String packageName = ClassUtils.getPackageName(requestedType).replaceFirst("\\[L", "");
			for (String trustedPackage : this.trustedPackages) {
				if (packageName.equals(trustedPackage)) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

}
