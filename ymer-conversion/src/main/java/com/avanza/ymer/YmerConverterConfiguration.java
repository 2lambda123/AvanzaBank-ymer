/*
 * Copyright 2015 Avanza Bank AB
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
package com.avanza.ymer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.core.convert.converter.Converter;

public interface YmerConverterConfiguration {

	default List<Converter<?, ?>> getCustomConverters() {
		return Collections.emptyList();
	}

	/**
	 * Defines the replacement string for map keys with a dot.
	 * Default value is {@link Optional#empty()}
	 *
	 * @return an optional value defining the replacement for
	 * @see org.springframework.data.mongodb.core.convert.MappingMongoConverter#setMapKeyDotReplacement(String)
	 */
	default Optional<String> getMapKeyDotReplacement() {
		return Optional.empty();
	}
}