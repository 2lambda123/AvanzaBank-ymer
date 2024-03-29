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
package com.avanza.ymer.test;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import com.avanza.ymer.MirroredObjectDefinition;
import com.gigaspaces.annotation.pojo.SpaceClass;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class YmerMirroredTypesTestBase {

	@Test
	void allSpaceClassesAreIncludedInYmerFactory() {
		Set<String> mirroredClassNames = mirroredObjectDefinitions().stream()
				.map(mirroredObject -> mirroredObject.getMirroredType().getName())
				.collect(Collectors.toSet());

		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(SpaceClass.class));

		List<String> spaceClassesWithoutMirrorDefinition = new ArrayList<>();
		for (BeanDefinition bd : scanner.findCandidateComponents(basePackageForScanning())) {
			String spaceClassName = bd.getBeanClassName();
			if (!mirroredClassNames.contains(spaceClassName) && !getClassesToExclude().contains(spaceClassName)) {
				spaceClassesWithoutMirrorDefinition.add(spaceClassName);
			}
		}

		if (!spaceClassesWithoutMirrorDefinition.isEmpty()) {
			StringBuilder failMessageBuilder = new StringBuilder("The following classes are defined as @SpaceClass without having a mirrored object definition:\n");
			spaceClassesWithoutMirrorDefinition.forEach(c -> failMessageBuilder.append(c).append('\n'));

			failMessageBuilder.append('\n').append("This means that objects of these classes would not be persisted to database by Ymer.");
			failMessageBuilder.append('\n').append("To resolve this, add the SpaceClass to the mirrored object definitions.");
			failMessageBuilder.append('\n').append("Alternatively, if the class is intended to not be persisted, override spaceClassesToExcludeFromTest() to exclude the class from this test.");

			fail(failMessageBuilder.toString());
		}
	}

	private Set<String> getClassesToExclude() {
		return spaceClassesToExcludeFromTest().stream()
				.map(Class::getName)
				.collect(Collectors.toSet());
	}

	@ParameterizedTest
	@MethodSource("mirroredObjectDefinitions")
	void mirroredTypeIsAnnotatedWithSpaceClass(MirroredObjectDefinition<?> mirroredObjectDefinition) {
		Class<?> mirroredType = mirroredObjectDefinition.getMirroredType();
		boolean hasSpaceClassAnnotation = mirroredType.getAnnotation(SpaceClass.class) != null;

		if (!hasSpaceClassAnnotation) {
			String failMessage = "The following class is included in Ymer mirrored object definitions but not annotated with @SpaceClass:\n" + mirroredType.getName() + '\n'
					+ "\nAll classes marked for persisting through Ymer should be annotated with @SpaceClass."
					+ "\nTo resolve this, annotate this class above with @SpaceClass.";
			fail(failMessage);
		}
	}

	/**
	 * List of mirrored object definitions that would be sent to YmerFactory.
	 * The classes found by scanning the basePackage below are expected to be found in this list.
	 */
	protected abstract Collection<MirroredObjectDefinition<?>> mirroredObjectDefinitions();

	/**
	 * Base package to scan for classes annotated with @SpaceClass.
	 */
	protected abstract String basePackageForScanning();

	/**
	 * Space classes that are intended to not be persisted and should be excluded from this test.
	 */
	protected Set<Class<?>> spaceClassesToExcludeFromTest() {
		return Collections.emptySet();
	}

}
