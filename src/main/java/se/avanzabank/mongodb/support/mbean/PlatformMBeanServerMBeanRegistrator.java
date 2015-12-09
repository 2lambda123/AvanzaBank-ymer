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
package se.avanzabank.mongodb.support.mbean;

import java.lang.management.ManagementFactory;

import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Kristoffer Erlandsson (krierl), kristoffer.erlandsson@avanzabank.se
 */
public class PlatformMBeanServerMBeanRegistrator implements MBeanRegistrator {
	
	private static final Logger log = LoggerFactory.getLogger(PlatformMBeanServerMBeanRegistrator.class);
	
	@Override
	public void registerMBean(Object mbean, String name) {
		log.info("Registering mbean with name {}", name);
		try {
			ManagementFactory.getPlatformMBeanServer().registerMBean(mbean, ObjectName.getInstance(name));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void unregisterMBean(String name) {
		log.info("Unregistering mbean with name {}", name);
		try {
			ManagementFactory.getPlatformMBeanServer().unregisterMBean(ObjectName.getInstance(name));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
