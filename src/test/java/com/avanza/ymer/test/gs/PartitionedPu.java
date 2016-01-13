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
package com.avanza.ymer.test.gs;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.openspaces.core.GigaSpace;
import org.openspaces.core.cluster.ClusterInfo;
import org.openspaces.core.properties.BeanLevelProperties;
import org.openspaces.pu.container.integrated.IntegratedProcessingUnitContainer;
import org.openspaces.pu.container.integrated.IntegratedProcessingUnitContainerProvider;
import org.openspaces.pu.container.support.CompoundProcessingUnitContainer;
import org.springframework.context.ApplicationContext;

/**
 * 
 * @author Elias Lindholm (elilin)
 *
 */
public final class PartitionedPu implements PuRunner {

	private CompoundProcessingUnitContainer container;
	private String gigaSpaceBeanName = "gigaSpace";
	private String puXmlPath;
	private Integer numberOfPrimaries;
	private Integer numberOfBackups;
	private Properties contextProperties = new Properties();
	private Map<String, Properties> beanProperies = new HashMap<>();
	private String lookupGroupName;
	private boolean autostart;
	private ApplicationContext parentContext;

	public PartitionedPu(PartitionedPuConfigurer configurer) {
		this.puXmlPath = configurer.puXmlPath;
		this.numberOfBackups = configurer.numberOfBackups;
		this.numberOfPrimaries = configurer.numberOfPrimaries;
		this.contextProperties.putAll(configurer.contextProperties);
		this.beanProperies.putAll(configurer.beanProperies);
		this.lookupGroupName = configurer.lookupGroupName;
		this.autostart = configurer.autostart;
		this.parentContext = configurer.parentContext;
		this.contextProperties.put("spaceName", UniqueSpaceNameLookup.getSpaceNameWithSequence(configurer.spaceName));
	}

	@Override
	public void run() throws IOException {
		try {
			startContainers();
		} catch (Exception e) {
			throw new RuntimeException("Failed to start containers for puXmlPath: " + puXmlPath, e);
		}
	}

	private void startContainers() throws IOException {
		IntegratedProcessingUnitContainerProvider provider = new IntegratedProcessingUnitContainerProvider();
		provider.setBeanLevelProperties(createBeanLevelProperties());
		provider.setClusterInfo(createClusterInfo());
		provider.addConfigLocation(puXmlPath);
		if (parentContext != null) {
			provider.setParentContext(parentContext);
		}
		container = (CompoundProcessingUnitContainer) provider.createContainer();
	}

	private ClusterInfo createClusterInfo() {
		ClusterInfo clusterInfo = new ClusterInfo();
		clusterInfo.setSchema("partitioned-sync2backup");
		clusterInfo.setNumberOfInstances(numberOfPrimaries);
		clusterInfo.setNumberOfBackups(numberOfBackups);
		clusterInfo.setInstanceId(null);
		return clusterInfo;
	}

	private BeanLevelProperties createBeanLevelProperties() {
		BeanLevelProperties beanLevelProperties = new BeanLevelProperties();
		beanLevelProperties.setContextProperties(contextProperties);
		for (Map.Entry<String, Properties> beanProperties : beanProperies.entrySet()) {
			beanLevelProperties.setBeanProperties(beanProperties.getKey(), beanProperties.getValue());
		}
		// TODO: set lookup-group on space-bean name instead of using system-property in RunningPuImpl
//		beanLevelProperties.getBeanProperties("space").put("gs.space.url.arg.groups", getLookupGroupName()); 
		beanLevelProperties.getBeanProperties("space").put("gs.space.url.arg.timeout", "10");
		return beanLevelProperties;
	}
	
	@Override
	public void shutdown() {
		container.close();
	}

	@Override
	public String getLookupGroupName() {
		return this.lookupGroupName;
	}
	
	public boolean autostart() {
		return this.autostart ;
	}
	
	@Override
	public GigaSpace getClusteredGigaSpace() {
		return GigaSpace.class.cast(getPrimaryInstanceApplicationContext(0).getBean(this.gigaSpaceBeanName)).getClustered();
	}

	@Override
	public ApplicationContext getPrimaryInstanceApplicationContext(int partition) {
		IntegratedProcessingUnitContainer container = (IntegratedProcessingUnitContainer) this.container.getProcessingUnitContainers()[partition];
		return container.getApplicationContext();
	}

}
