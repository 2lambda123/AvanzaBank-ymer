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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import com.gigaspaces.datasource.SpaceDataSource;
import com.gigaspaces.sync.SpaceSynchronizationEndpoint;

public class TestSpaceMirrorFactory {

	private final MongoDatabaseFactory mongoDbFactory;
	private final MirroredObjectsConfiguration mirroredObjectsConfiguration = new TestSpaceMirrorObjectDefinitions();
	private final AtomicReference<Integer> nextNumberOfInstances = new AtomicReference<>(null);
	private boolean exportExceptionHandlerMBean;
	private boolean useBulkWrites = false;

	@Autowired
	public TestSpaceMirrorFactory(MongoDatabaseFactory mongoDbFactory) {
		this.mongoDbFactory = mongoDbFactory;
	}

	public void setExportExceptionHandlerMBean(boolean exportExceptionHandlerMBean) {
		this.exportExceptionHandlerMBean = exportExceptionHandlerMBean;
	}

	public void setNextNumberOfInstances(Integer nextNumberOfInstances) {
		this.nextNumberOfInstances.set(nextNumberOfInstances);
	}

	public void setUseBulkWrites(boolean useBulkWrites) {
		this.useBulkWrites = useBulkWrites;
	}

	public SpaceDataSource createSpaceDataSource() {
		YmerFactory ymerFactory = new YmerFactory(mongoDbFactory, mirroredObjectsConfiguration);
		ymerFactory.setExportExceptionHandlerMBean(exportExceptionHandlerMBean);
		ymerFactory.setPlugins(Collections.singleton(new TestProcessor.TestPlugin()));
		ymerFactory.setNumParallelCollections(2);
		return ymerFactory.createSpaceDataSource();
	}

	public SpaceSynchronizationEndpoint createSpaceSynchronizationEndpoint() {
		YmerFactory ymerFactory = new YmerFactory(mongoDbFactory, mirroredObjectsConfiguration);
		ymerFactory.setExportExceptionHandlerMBean(exportExceptionHandlerMBean);
		ymerFactory.setPlugins(Collections.singleton(new TestProcessor.TestPlugin()));
		ymerFactory.withProperties(configurer -> {
			configurer.nextNumberOfInstances(() -> Optional.ofNullable(nextNumberOfInstances.get()));
			configurer.useBulkWrites(() -> useBulkWrites);
		});
		return ymerFactory.createSpaceSynchronizationEndpoint();
	}
}
