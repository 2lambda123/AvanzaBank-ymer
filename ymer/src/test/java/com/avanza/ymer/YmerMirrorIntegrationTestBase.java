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

import static com.avanza.ymer.TestSpaceMirrorObjectDefinitions.TEST_OBJECT_WITH_COMPLEX_KEY;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.bson.Document;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.openspaces.core.GigaSpace;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import com.avanza.gs.test.PuConfigurers;
import com.avanza.gs.test.RunningPu;
import com.avanza.ymer.space.ComplexId;
import com.avanza.ymer.space.SpaceObjectWithComplexKey;
import com.gigaspaces.client.WriteModifiers;

public abstract class YmerMirrorIntegrationTestBase {

	protected MongoOperations mongo;
	protected GigaSpace gigaSpace;

	private static final MirrorEnvironment mirrorEnvironment = new MirrorEnvironment();

	protected static final RunningPu pu = PuConfigurers.partitionedPu("classpath:/test-pu.xml")
			.numberOfBackups(1)
			.numberOfPrimaries(1)
			.parentContext(mirrorEnvironment.getMongoClientContext())
			.configure();

	protected static final RunningPu mirrorPu = PuConfigurers.mirrorPu("classpath:/test-mirror-pu.xml")
			.contextProperty("exportExceptionHandlerMBean", "true")
			.parentContext(mirrorEnvironment.getMongoClientContext())
			.configure();

	@ClassRule
	public static TestRule spaces = RuleChain.outerRule(mirrorEnvironment).around(pu).around(mirrorPu);

	@Before
	public void setUp() {
		mongo = mirrorEnvironment.getMongoTemplate();
		gigaSpace = pu.getClusteredGigaSpace();

		configureTestCase(pu, mirrorPu);
	}

	@After
	public void cleanup() {
		mirrorEnvironment.reset();
		gigaSpace.clear(null);
	}

	abstract void configureTestCase(RunningPu pu, RunningPu mirrorPu);

	@Test
	public void mirrorsInsertOfTestSpaceObjects() throws Exception {
		TestSpaceObject o1 = new TestSpaceObject();
		o1.setId("id_23");
		o1.setMessage("mirror_test");

		gigaSpace.write(o1, WriteModifiers.WRITE_ONLY);
		assertEquals(1, gigaSpace.count(new TestSpaceObject()));

		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(o1));
	}

	@Test
	public void testInsertUpdateAndRemove() {
		TestSpaceObject o1 = new TestSpaceObject("id_1", "message1");
		TestSpaceObject o2 = new TestSpaceObject("id_2", "message2");
		TestSpaceObject o3 = new TestSpaceObject("id_3", "message3");

		gigaSpace.writeMultiple(new TestSpaceObject[] {o1, o2, o3}, WriteModifiers.WRITE_ONLY);
		assertEquals(3, gigaSpace.count(new TestSpaceObject()));
		assertEventually(() -> mongo.findAll(TestSpaceObject.class), containsInAnyOrder(o1, o2, o3));

		o2.setMessage("updated");

		gigaSpace.write(o2, WriteModifiers.UPDATE_ONLY);
		gigaSpace.takeById(TestSpaceObject.class, "id_1");

		assertEventually(() -> mongo.findAll(TestSpaceObject.class), containsInAnyOrder(o2, o3));
	}

	@Test
	public void testUpdatingSpaceObjectFieldToNullIsSaved() throws Exception {
		TestSpaceObject withMessageSet = new TestSpaceObject();
		withMessageSet.setId("object_id");
		withMessageSet.setMessage("not_null_value");

		gigaSpace.write(withMessageSet, WriteModifiers.WRITE_ONLY);
		assertEquals(1, gigaSpace.count(new TestSpaceObject()));
		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(withMessageSet));

		TestSpaceObject withMessageAsNull = new TestSpaceObject();
		withMessageAsNull.setId("object_id");
		withMessageAsNull.setMessage(null);

		gigaSpace.write(withMessageAsNull, WriteModifiers.UPDATE_OR_WRITE);
		assertEquals(1, gigaSpace.count(new TestSpaceObject()));
		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(withMessageAsNull));
	}

	@Test
	public void mirrorsUpdatesOfTestSpaceObjects() throws Exception {
		TestSpaceObject inserted = new TestSpaceObject();
		inserted.setId("id_23");
		inserted.setMessage("inserted_mirror_test");
		gigaSpace.write(inserted, WriteModifiers.WRITE_ONLY);

		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(inserted));

		TestSpaceObject updated = new TestSpaceObject();
		updated.setId("id_23");
		updated.setMessage("updated_mirror_test");
		gigaSpace.write(updated, WriteModifiers.UPDATE_ONLY);

		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(updated));
	}

	@Test
	public void mirrorsDeletesOfTestSpaceObjects() throws Exception {
		TestSpaceObject inserted = new TestSpaceObject();
		inserted.setId("id_23");
		inserted.setMessage("inserted_mirror_test");
		gigaSpace.write(inserted, WriteModifiers.WRITE_ONLY);

		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(inserted));

		gigaSpace.takeById(TestSpaceObject.class, "id_23");

		assertEventually(() -> mongo.count(new Query(), TestSpaceObject.class), equalTo(0L));
	}

	@Test
	public void mbeanInvoke() throws Exception {
		MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		ObjectName nameTemplate = ObjectName
				.getInstance("se.avanzabank.space.mirror:type=DocumentWriteExceptionHandler,name=documentWriteExceptionHandler");
		Set<ObjectName> names = server.queryNames(nameTemplate, null);
		assertThat(names, hasSize(greaterThan(0)));
		server.invoke(names.toArray(new ObjectName[0])[0], "useCatchesAllHandler", null, null);
	}

	@Test
	public void findByTemplate() throws Exception {
		SpaceObjectLoader persister = pu.getPrimaryInstanceApplicationContext(0).getBean(SpaceObjectLoader.class);

		gigaSpace.write(new TestSpaceObject("id1", "m1"));
		gigaSpace.write(new TestSpaceObject("id2", "m2"));
		gigaSpace.write(new TestSpaceObject("id3", "m1"));

		assertEventually(() -> mongo.count(new Query(), TestSpaceObject.class), equalTo(3L));

		assertEquals(2, persister.loadObjects(TestSpaceObject.class, new TestSpaceObject(null, "m1")).size());
		assertEquals(1, persister.loadObjects(TestSpaceObject.class, new TestSpaceObject(null, "m2")).size());
		assertEquals(0, persister.loadObjects(TestSpaceObject.class, new TestSpaceObject(null, "m3")).size());
		assertEquals(1, persister.loadObjects(TestSpaceObject.class, new TestSpaceObject("id3", null)).size());
		assertEquals(0, persister.loadObjects(TestSpaceObject.class, new TestSpaceObject("id3", "m2")).size());
		assertEquals(1, persister.loadObjects(TestSpaceObject.class, new TestSpaceObject("id3", "m1")).size());
	}

	@Test
	public void processingIsApplied() throws Exception {
		SpaceObjectLoader persister = pu.getPrimaryInstanceApplicationContext(0).getBean(SpaceObjectLoader.class);

		gigaSpace.write(new TestSpaceThirdObject("1", "|"));
		assertEventually(() -> mongo.findAll(TestSpaceThirdObject.class), hasItem(new TestSpaceThirdObject("1", "a|"))); // prewrite processor prepends a
		assertEquals("|", persister.loadObjects(TestSpaceThirdObject.class, new TestSpaceThirdObject("1", null)).iterator().next().getName()); // a removed by postread processor

		mirrorEnvironment.removeFormatVersion(TestSpaceThirdObject.class, "1");
		assertEquals("b|", persister.loadObjects(TestSpaceThirdObject.class, new TestSpaceThirdObject("1", null)).iterator().next().getName()); // patch adds b to read data (after postread processor)
		assertEventually(() -> mongo.findAll(TestSpaceThirdObject.class), hasItem(new TestSpaceThirdObject("1", "ab|"))); // a is prepended after patching by prewrite processor
		assertEquals("b|", persister.loadObjects(TestSpaceThirdObject.class, new TestSpaceThirdObject("1", null)).iterator().next().getName()); // nothing happens once patch has been applied
		assertEventually(() -> mongo.findAll(TestSpaceThirdObject.class), hasItem(new TestSpaceThirdObject("1", "ab|")));  // same in mongo
	}

	@Test
	public void testSettingInstanceIdFieldsOnWrite() throws Exception {
		TestSpaceMirrorFactory spaceMirrorFactory = mirrorPu.getPrimaryApplicationContexts().iterator().next().getBean(TestSpaceMirrorFactory.class);

		TestSpaceObject object = new TestSpaceObject();
		object.setId("id_23");

		// First, write a message without setting the next number of instances
		object.setMessage("mirror_test_1");
		gigaSpace.write(object, WriteModifiers.WRITE_ONLY);
		assertEquals(1, gigaSpace.count(new TestSpaceObject()));

		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(object));
		Document document = mongo.getCollection(TestSpaceMirrorObjectDefinitions.TEST_SPACE_OBJECT.collectionName()).find().first();
		assertEquals(1, (int) document.getInteger("_instanceId_1"));

		// Set next number of instances to 4, causing next write to also write id for 4 partitions
		spaceMirrorFactory.setNextNumberOfInstances(4);
		object.setMessage("mirror_test_2");
		gigaSpace.write(object, WriteModifiers.UPDATE_ONLY);

		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(object));

		document = mongo.getCollection(TestSpaceMirrorObjectDefinitions.TEST_SPACE_OBJECT.collectionName()).find().first();
		assertEquals(1, (int) document.getInteger("_instanceId_1"));
		assertEquals(2, (int) document.getInteger("_instanceId_4"));

		// Set next number of instances to 6, causing next write to also write id for 6 partitions & remove id for 4
		spaceMirrorFactory.setNextNumberOfInstances(6);

		object.setMessage("mirror_test_3");
		gigaSpace.write(object, WriteModifiers.UPDATE_ONLY);

		assertEventually(() -> mongo.findAll(TestSpaceObject.class), hasItem(object));

		document = mongo.getCollection(TestSpaceMirrorObjectDefinitions.TEST_SPACE_OBJECT.collectionName()).find().first();
		assertEquals(1, (int) document.getInteger("_instanceId_1"));
		assertNull(document.get("_instanceId_4"));
		assertEquals(6, (int) document.getInteger("_instanceId_6"));
	}

	@Test
	public void shouldAllowCustomRoutingKeyWithPersistInstanceId() {
		// Arrange
		final String id = "id_24";
		final var object = new TestSpaceObjectWithCustomRoutingKey(id, "routing_24");

		gigaSpace.write(object, WriteModifiers.WRITE_ONLY);
		assertEquals(1, gigaSpace.count(new TestSpaceObjectWithCustomRoutingKey()));
		assertEventually(() -> mongo.findAll(TestSpaceObjectWithCustomRoutingKey.class), hasItem(object));

		gigaSpace.takeById(TestSpaceObjectWithCustomRoutingKey.class, id);
		assertEquals(0, gigaSpace.count(new TestSpaceObjectWithCustomRoutingKey()));
		assertEventually(() -> mongo.findAll(TestSpaceObjectWithCustomRoutingKey.class), not(hasItem(object)));
	}

	@Test
	public void testWritingObjectWithComplexKey() {
		// Write two objects to space
		SpaceObjectWithComplexKey spaceObject1 = new SpaceObjectWithComplexKey(new ComplexId("key", 1), "test1");
		SpaceObjectWithComplexKey spaceObject2 = new SpaceObjectWithComplexKey(new ComplexId("key", 2), "test1");

		gigaSpace.writeMultiple(new SpaceObjectWithComplexKey[] {spaceObject1, spaceObject2}, WriteModifiers.WRITE_ONLY);

		assertEquals(2, gigaSpace.count(new SpaceObjectWithComplexKey()));
		assertEventually(() -> mongo.findAll(SpaceObjectWithComplexKey.class), hasItems(spaceObject1, spaceObject2));

		// Delete collection and write again with UPDATE, triggering these objects to be inserted on an update operation,
		// which should log warnings about this
		mongo.dropCollection(TEST_OBJECT_WITH_COMPLEX_KEY.collectionName());

		spaceObject1.setMessage("test2");
		spaceObject2.setMessage("test2");

		// Before the commit that added this test, this would result in an exception thrown in the test log about
		// not being able to handle the complex key
		gigaSpace.writeMultiple(new SpaceObjectWithComplexKey[] {spaceObject1, spaceObject2}, WriteModifiers.UPDATE_ONLY);

		// Assert objects added with UPDATE will still be written to space (with a warning in the log)
		assertEventually(() -> mongo.findAll(SpaceObjectWithComplexKey.class), hasItems(spaceObject1, spaceObject2));
	}

	@Test
	public void shouldExposeOperationStatistics() throws Exception {
		mirrorPu.stop();
		String name = "se.avanzabank.space.mirror:type=OperationStatistics,name=operationStatistics";
		final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		final ObjectName oname = new ObjectName(name);
		try {
			mbs.unregisterMBean(oname);
		} catch (Exception ignore) {
		}
		mirrorPu.start();

		// Reconfigure test case for restarted Mirror PU
		configureTestCase(pu, mirrorPu);

		// Arrange
		final TestSpaceObject o1 = new TestSpaceObject("id1", "message");
		gigaSpace.write(o1);
		o1.setMessage("updated message");
		gigaSpace.write(o1);
		gigaSpace.takeById(TestSpaceObject.class, o1.getId());

		assertEventually(() -> mbs.getAttribute(oname, "NumPerformedOperations"), equalTo(3L));
		assertEventually(() -> mbs.getAttribute(oname, "NumInserts"), equalTo(1L));
		assertEventually(() -> mbs.getAttribute(oname, "NumUpdates"), equalTo(1L));
		assertEventually(() -> mbs.getAttribute(oname, "NumDeletes"), equalTo(1L));
	}

	public static <T> void assertEventually(Callable<T> poller, Matcher<? super T> matcher) {
		await().atMost(7, SECONDS).pollInterval(50, MILLISECONDS).until(poller, matcher);
	}

}
