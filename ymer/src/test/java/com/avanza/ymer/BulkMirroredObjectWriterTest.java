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

import static com.avanza.ymer.TestSpaceMirrorObjectDefinitions.TEST_SPACE_OBJECT;
import static com.avanza.ymer.TestSpaceMirrorObjectDefinitions.TEST_SPACE_OTHER_OBJECT;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.bson.Document;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.mongodb.core.query.Query;

import com.avanza.ymer.helper.FakeBatchData;
import com.avanza.ymer.helper.FakeBulkItem;
import com.avanza.ymer.helper.MirrorExceptionSpy;
import com.gigaspaces.sync.DataSyncOperationType;

public class BulkMirroredObjectWriterTest {

	private FakeDocumentWriteExceptionHandler exceptionHandler;
	private BulkMirroredObjectWriter bulkMirroredObjectWriter;
	private final InstanceMetadata testMetadata = new InstanceMetadata(1, null);
	private DocumentDb documentDb;
	private SpaceMirrorContext mirror;
	private MirrorExceptionSpy mirrorExceptionSpy;
	private DocumentConverter documentConverter;
	private PerformedOperationMetrics metrics;

	@Before
	public void setUp() {
		documentDb = FakeDocumentDb.create();
		mirrorExceptionSpy = new MirrorExceptionSpy();
		exceptionHandler = new FakeDocumentWriteExceptionHandler();
		documentConverter = TestSpaceObjectFakeConverter.create();
		metrics = new PerformedOperationMetrics();
		TestSpaceMirrorObjectDefinitions definitions = new TestSpaceMirrorObjectDefinitions();
		mirror = new SpaceMirrorContext(
				new MirroredObjects(definitions.getMirroredObjectDefinitions().stream(), MirroredObjectDefinitionsOverride.noOverride()),
				documentConverter,
				documentDb,
				mirrorExceptionSpy,
				Plugins.empty(),
				1);

		bulkMirroredObjectWriter = new BulkMirroredObjectWriter(
				mirror,
				exceptionHandler,
				new MirroredObjectFilterer(mirror),
				metrics
		);
	}

	@After
	public void tearDown() {
		Configurator.reconfigure();
	}

	@Test
	public void shouldTryToWriteAllRowsAfterManyFailures() {
		// this test logs a lot of errors, so disable logs temporarily
		Configurator.setLevel(BulkMirroredObjectWriter.class, Level.OFF);

		TestSpaceObject[] objects = IntStream.rangeClosed(1, 1_000)
				.mapToObj(i -> new TestSpaceObject("id_" + i, "message" + i))
				.toArray(TestSpaceObject[]::new);

		// This will cause each write to fail as all the objects already exists in DB
		documentDb.getCollection(TEST_SPACE_OBJECT.collectionName())
				.insertAll(Stream.of(objects)
						// ensure the first 200 rows fail writing, the remaining should succeed
						.limit(200)
						.map(a -> mirror.toVersionedDocument(a, testMetadata))
						.toArray(Document[]::new));

		// Before bulkWrite, 200 rows should already be written
		assertThat(documentDb.getCollection(TEST_SPACE_OBJECT.collectionName()).findAll().count(), is(200L));

		bulkMirroredObjectWriter.executeBulk(testMetadata, new FakeBatchData(
				Stream.of(objects)
						.map(spaceObject -> new FakeBulkItem(spaceObject, DataSyncOperationType.WRITE))
						.toArray(FakeBulkItem[]::new)
		));

		assertThat(mirrorExceptionSpy.getExceptionCount(), is(200));
		assertThat(mirrorExceptionSpy.getLastException().getMessage(),
				containsString("Bulk write operation error")
		);

		// After bulkWrite, the last 800 rows should be written to db
		assertThat(documentDb.getCollection(TEST_SPACE_OBJECT.collectionName()).findAll().count(), is(1_000L));

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(800L));
		assertThat(metrics.getNumFailures(), is(200L));
	}

	@Test
	public void allRowsFailingShouldTryToWriteEachRow() {
		TestSpaceObject[] objects = IntStream.rangeClosed(1, 5)
				.mapToObj(i -> new TestSpaceObject("id_" + i, "message" + i))
				.toArray(TestSpaceObject[]::new);

		// This will cause each write to fail as all the objects already exists in DB
		documentDb.getCollection(TEST_SPACE_OBJECT.collectionName())
				.insertAll(Stream.of(objects)
						.map(o -> mirror.toVersionedDocument(o, testMetadata))
						.toArray(Document[]::new));

		// Before bulkWrite, 5 rows should already be written
		assertThat(documentDb.getCollection(TEST_SPACE_OBJECT.collectionName()).findAll().count(), is(5L));

		// This should log an error for each row, with the last error also including stack trace
		bulkMirroredObjectWriter.executeBulk(testMetadata, new FakeBatchData(
				Stream.of(objects)
						.map(spaceObject -> new FakeBulkItem(spaceObject, DataSyncOperationType.WRITE))
						.toArray(FakeBulkItem[]::new)
		));

		assertThat(mirrorExceptionSpy.getExceptionCount(), is(5));
		assertThat(mirrorExceptionSpy.getLastException().getMessage(),
				containsString("Bulk write operation error")
		);

		// no more rows should be added
		assertThat(documentDb.getCollection(TEST_SPACE_OBJECT.collectionName()).findAll().count(), is(5L));

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(0L));
		assertThat(metrics.getNumFailures(), is(5L));
	}

	@Test
	public void objectFailingConversionShouldBeFailedWithOtherItemsWritten() {
		// this test logs a lot of errors, so disable logs temporarily
		Configurator.setLevel(BulkMirroredObjectWriter.class, Level.OFF);

		TestSpaceObject[] objects = IntStream.rangeClosed(1, 100)
				.mapToObj(i -> {
					TestSpaceObject object = new TestSpaceObject("id_" + i, "message" + i);
					// every second item fails conversion
					if (i % 2 == 0) {
						object.setFailConversion(true);
					}
					return object;
				})
				.toArray(TestSpaceObject[]::new);

		// Cause a writing failure in the middle of the operation
		documentDb.getCollection(TEST_SPACE_OBJECT.collectionName())
				.insert(mirror.toVersionedDocument(objects[50], testMetadata));

		bulkMirroredObjectWriter.executeBulk(testMetadata, new FakeBatchData(
				Stream.of(objects)
						.map(spaceObject -> new FakeBulkItem(spaceObject, DataSyncOperationType.WRITE))
						.toArray(FakeBulkItem[]::new)
		));

		// Every second row should have failed conversion and been added as an exception
		assertThat(mirrorExceptionSpy.getExceptionCount(), is(51));

		// Every second row should have been written to db
		List<Document> objectsInDb = documentDb.getCollection(TEST_SPACE_OBJECT.collectionName()).findAll().collect(toList());
		assertThat(objectsInDb, iterableWithSize(50));
		assertThat(objectsInDb.stream().map(o -> o.getString("_id")).collect(toList()),
				containsInAnyOrder(
						Stream.of(objects)
								.filter(o -> !o.isFailConversion())
								.map(TestSpaceObject::getId)
								.toArray(String[]::new)
				)
		);

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(49L));
		assertThat(metrics.getNumFailures(), is(51L));
	}

	@Test
	public void unexpectedExceptionFromBulkWriteIsSentToExceptionHandler() {
		FakeDocumentCollection mockCollection = (FakeDocumentCollection) documentDb.getCollection(TEST_SPACE_OBJECT.collectionName());

		RuntimeException testException = new RuntimeException("Unexpected exception from MongoDB");
		mockCollection.setMockedBulkException(() -> testException);

		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(new FakeBulkItem(
				new TestSpaceObject("id", "message"),
				DataSyncOperationType.WRITE
		)));

		assertThat(exceptionHandler.getLastException(), is(testException));
		assertThat(exceptionHandler.getLastOperationDescription(), is("Operation: Bulk write, changes: [INSERT: TestSpaceObject [id=id, message=message]]"));
	}

	@Test
	public void writesAreInsertedInDb() {
		MirroredObject<TestSpaceObject> mirroredObject = TEST_SPACE_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestSpaceObject item1 = new TestSpaceObject("1", "hello");
		FakeBulkItem bulkItem = new FakeBulkItem(item1, DataSyncOperationType.WRITE);

		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem));

		Document expected = documentConverter.convertToBsonDocument(item1);
		mirroredObject.setDocumentVersion(expected, mirroredObject.getCurrentVersion());
		mirroredObject.setDocumentAttributes(expected, item1, testMetadata);

		List<Document> persisted = documentDb.getCollection(mirroredObject.getCollectionName()).findAll().collect(toList());
		assertEquals(1, persisted.size());
		assertEquals(expected, persisted.get(0));
	}

	@Test
	public void writesAllObjectsInDb() {
		MirroredObject<TestSpaceObject> mirroredObject = TEST_SPACE_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestSpaceObject item1 = new TestSpaceObject("1", "hello");
		TestSpaceObject item2 = new TestSpaceObject("2", "hello");
		TestSpaceObject item3 = new TestSpaceObject("3", "hello");
		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(new FakeBulkItem(item1, DataSyncOperationType.WRITE),
				new FakeBulkItem(item2, DataSyncOperationType.WRITE),
				new FakeBulkItem(item3, DataSyncOperationType.WRITE)));

		List<Document> persisted = documentDb.getCollection(mirroredObject.getCollectionName()).findAll().collect(toList());
		assertEquals(3, persisted.size());

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(3L));
		assertThat(metrics.getNumFailures(), is(0L));
	}

	@Test
	public void writesCurrentInstanceId() {
		MirroredObject<TestSpaceOtherObject> anotherMirroredDocument = TestSpaceMirrorObjectDefinitions.TEST_SPACE_OTHER_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestSpaceOtherObject item = new TestSpaceOtherObject("1", "message");
		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(new FakeBulkItem(item, DataSyncOperationType.WRITE)));

		List<Document> persisted = documentDb.getCollection(anotherMirroredDocument.getCollectionName()).findAll().collect(toList());
		assertThat(persisted, hasSize(1));
		assertThat(persisted.get(0).keySet().stream().filter(it -> it.startsWith("_instanceId")).count(), equalTo(1L));
		assertThat(persisted.get(0).getInteger("_instanceId_1"), equalTo(1));
	}

	@Test
	public void writesCurrentAndNextInstanceId() {
		MirroredObject<TestSpaceOtherObject> anotherMirroredDocument = TestSpaceMirrorObjectDefinitions.TEST_SPACE_OTHER_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestSpaceOtherObject item = new TestSpaceOtherObject("1", "message");
		InstanceMetadata metadataWithNext = new InstanceMetadata(1, 2);
		bulkMirroredObjectWriter.executeBulk(metadataWithNext, FakeBatchData.create(new FakeBulkItem(item, DataSyncOperationType.WRITE)));

		List<Document> persisted = documentDb.getCollection(anotherMirroredDocument.getCollectionName()).findAll().collect(toList());
		assertThat(persisted, hasSize(1));
		assertThat(persisted.get(0).keySet().stream().filter(it -> it.startsWith("_instanceId")).count(), equalTo(2L));
		assertThat(persisted.get(0).getInteger("_instanceId_1"), equalTo(1));
		assertThat(persisted.get(0).getInteger("_instanceId_2"), equalTo(2));
	}

	@Test
	public void writesOnlyOneWhenCurrentAndNextInstanceIdAreTheSame() {
		MirroredObject<TestSpaceOtherObject> anotherMirroredDocument =
				MirroredObjectDefinition.create(TestSpaceOtherObject.class)
						.keepPersistent(true)
						.persistInstanceId(true)
						.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		InstanceMetadata metadataWithNext = new InstanceMetadata(1, 1);
		TestSpaceOtherObject item = new TestSpaceOtherObject("1", "message");
		bulkMirroredObjectWriter.executeBulk(metadataWithNext, FakeBatchData.create(new FakeBulkItem(item, DataSyncOperationType.WRITE)));

		List<Document> persisted = documentDb.getCollection(anotherMirroredDocument.getCollectionName()).findAll().collect(toList());
		assertThat(persisted, hasSize(1));
		assertThat(persisted.get(0).keySet().stream().filter(it -> it.startsWith("_instanceId")).count(), equalTo(1L));
		assertThat(persisted.get(0).getInteger("_instanceId_1"), equalTo(1));
	}

	@Test
	public void partialUpdatesAreUpdatedInDb() {
		MirroredObject<TestSpaceObject> mirroredObject = TEST_SPACE_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestSpaceObject item1 = new TestSpaceObject("1", "hello");
		documentDb.getCollection(mirroredObject.getCollectionName()).insert(documentConverter.convertToBsonDocument(item1));
		item1.setMessage("updated");
		FakeBulkItem bulkItem = new FakeBulkItem(item1, DataSyncOperationType.PARTIAL_UPDATE);
		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem));

		Document expected = documentConverter.convertToBsonDocument(item1);
		mirroredObject.setDocumentVersion(expected, mirroredObject.getCurrentVersion());
		mirroredObject.setDocumentAttributes(expected, item1, testMetadata);

		List<Document> persisted = documentDb.getCollection(mirroredObject.getCollectionName()).findAll().collect(toList());
		assertEquals(1, persisted.size());
		assertEquals(expected, persisted.get(0));

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(0L));
		assertThat(metrics.getNumUpdates(), is(1L));
		assertThat(metrics.getNumFailures(), is(0L));
	}

	@Test
	public void updatesAreUpdatedInDb() {
		MirroredObject<TestSpaceObject> mirroredObject = TEST_SPACE_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestSpaceObject item1 = new TestSpaceObject("1", "hello");
		documentDb.getCollection(mirroredObject.getCollectionName()).insert(documentConverter.convertToBsonDocument(item1));
		item1.setMessage("updated");

		FakeBulkItem bulkItem = new FakeBulkItem(item1, DataSyncOperationType.UPDATE);
		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem));

		Document expected = documentConverter.convertToBsonDocument(item1);
		mirroredObject.setDocumentVersion(expected, mirroredObject.getCurrentVersion());
		mirroredObject.setDocumentAttributes(expected, item1, testMetadata);

		List<Document> persisted = documentDb.getCollection(mirroredObject.getCollectionName()).findAll().collect(toList());
		assertEquals(1, persisted.size());
		assertEquals(expected, persisted.get(0));

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(0L));
		assertThat(metrics.getNumUpdates(), is(1L));
		assertThat(metrics.getNumFailures(), is(0L));
	}

	@Test
	public void removesDocumentFromDb() {
		MirroredObject<TestSpaceObject> mirroredObject = TEST_SPACE_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestSpaceObject item1 = new TestSpaceObject("1", "hello");
		Document document = documentConverter.convertToBsonDocument(item1);
		documentDb.getCollection(mirroredObject.getCollectionName()).insert(document);

		TestSpaceObject bulkItemObject = new TestSpaceObject("1", null);
		FakeBulkItem bulkItem = new FakeBulkItem(bulkItemObject, DataSyncOperationType.REMOVE);
		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem));

		List<Document> persisted = documentDb.getCollection(mirroredObject.getCollectionName()).findAll().collect(toList());
		assertEquals(0, persisted.size());

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(0L));
		assertThat(metrics.getNumDeletes(), is(1L));
		assertThat(metrics.getNumFailures(), is(0L));
	}

	@Test
	public void documentsWithKeepPersistentFlagAreNotRemovedFromDb() {
		MirroredObject<TestSpaceOtherObject> anotherMirroredDocument = TEST_SPACE_OTHER_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestSpaceOtherObject item1 = new TestSpaceOtherObject("1", "hello");
		documentDb.getCollection(anotherMirroredDocument.getCollectionName()).insert(documentConverter.convertToBsonDocument(item1));

		FakeBulkItem bulkItem = new FakeBulkItem(item1, DataSyncOperationType.REMOVE);
		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem));
		Document expected = documentConverter.convertToBsonDocument(item1);

		List<Document> persisted = documentDb.getCollection(anotherMirroredDocument.getCollectionName()).findAll().collect(toList());
		assertEquals(1, persisted.size());
		assertEquals(expected, persisted.get(0));

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(0L));
		assertThat(metrics.getNumDeletes(), is(0L));
		assertThat(metrics.getNumFailures(), is(0L));
	}

	@Test
	public void ignoresNonMirroredTypes() {
		MirroredObject<TestSpaceObject> mirroredObject = MirroredObjectDefinition.create(TestSpaceObject.class)
				.documentPatches(new YmerInitialLoadIntegrationTest.TestSpaceObjectV1Patch())
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());
		class NonMirroredType {
		}
		FakeBulkItem bulkItem = new FakeBulkItem(new NonMirroredType(), DataSyncOperationType.WRITE);
		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem));

		List<Document> persisted = documentDb.getCollection(mirroredObject.getCollectionName()).findAll().collect(toList());
		assertEquals(0, persisted.size());
	}

	@Test
	public void exceptionFromExceptionHandlerIsPropagated() {
		documentDb = throwsOnUpdateDocumentDb();
		mirrorExceptionSpy = new MirrorExceptionSpy();
		MirroredObjects mirroredObjects = new MirroredObjects(new TestSpaceMirrorObjectDefinitions().getMirroredObjectDefinitions().stream(), MirroredObjectDefinitionsOverride.noOverride());

		SpaceMirrorContext mirror = new SpaceMirrorContext(mirroredObjects, documentConverter, documentDb, mirrorExceptionSpy, Plugins.empty(), 1);
		BulkMirroredObjectWriter bulkMirroredObjectWriter = new BulkMirroredObjectWriter(mirror, new FakeDocumentWriteExceptionHandler(
				new TransientDocumentWriteException(new Exception())), new MirroredObjectFilterer(mirror));

		TestSpaceObject item1 = new TestSpaceObject("1", "hello");
		FakeBulkItem bulkItem = new FakeBulkItem(item1, DataSyncOperationType.UPDATE);
		assertThrows(TransientDocumentWriteException.class, () -> bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem)));
	}

	@Test
	public void exceptionThrownDuringConversionToMongoDbObjectAreNotPropagated() {
		DocumentConverter documentConverter = DocumentConverter.create(new DocumentConverter.Provider() {
			@Override
			public Document convertToBsonDocument(Object type) {
				throw new RuntimeException("");
			}

			@Override
			public <T> T convert(Class<T> toType, Document document) {
				throw new RuntimeException("");
			}

			@Override
			public Object convert(Object type) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Query toQuery(Object template) {
				throw new UnsupportedOperationException();
			}
		});
		mirrorExceptionSpy = new MirrorExceptionSpy();
		SpaceMirrorContext mirror = new SpaceMirrorContext(new MirroredObjects(new TestSpaceMirrorObjectDefinitions().getMirroredObjectDefinitions().stream(), MirroredObjectDefinitionsOverride.noOverride()), documentConverter, documentDb, mirrorExceptionSpy, Plugins.empty(), 1);
		BulkMirroredObjectWriter bulkMirroredObjectWriter = new BulkMirroredObjectWriter(mirror, exceptionHandler, new MirroredObjectFilterer(mirror));

		TestSpaceObject item1 = new TestSpaceObject("1", "hello");
		FakeBulkItem bulkItem = new FakeBulkItem(item1, DataSyncOperationType.UPDATE);
		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem));

		assertNotNull(mirrorExceptionSpy.getLastException());
		assertEquals(RuntimeException.class, mirrorExceptionSpy.getLastException().getClass());
		assertEquals(
				"Conversion failed, operation: UPDATE, change: TestSpaceObject [id=1, message=hello]",
				exceptionHandler.getLastOperationDescription()
		);
	}

	@Test
	public void writesOfReloadedObjectsAreNotInsertedInDb() {
		MirroredObject<TestReloadableSpaceObject> mirroredReloadableDocument = TestSpaceMirrorObjectDefinitions.TEST_RELOADABLE_OBJECT
				.buildMirroredDocument(MirroredObjectDefinitionsOverride.noOverride());

		TestReloadableSpaceObject spaceObject = new TestReloadableSpaceObject(1, 1, false, 1, 1);
		TestReloadableSpaceObject spaceObject2 = new TestReloadableSpaceObject(2, 1, false, 2, 1);
		TestReloadableSpaceObject spaceObject3 = new TestReloadableSpaceObject(3, 1, false, 3, 2);
		FakeBulkItem bulkItem = new FakeBulkItem(spaceObject, DataSyncOperationType.WRITE);
		FakeBulkItem bulkItem2 = new FakeBulkItem(spaceObject2, DataSyncOperationType.WRITE);
		FakeBulkItem bulkItem3 = new FakeBulkItem(spaceObject3, DataSyncOperationType.WRITE);

		bulkMirroredObjectWriter.executeBulk(testMetadata, FakeBatchData.create(bulkItem, bulkItem2, bulkItem3));

		Document expected = documentConverter.convertToBsonDocument(spaceObject);
		mirroredReloadableDocument.setDocumentVersion(expected, mirroredReloadableDocument.getCurrentVersion());

		List<Document> persisted = documentDb.getCollection(mirroredReloadableDocument.getCollectionName()).findAll().collect(toList());
		assertEquals(2, persisted.size());
		assertEquals(2, persisted.get(0).get("_id"));
		assertEquals(3, persisted.get(1).get("_id"));

		// verify recorded metrics
		assertThat(metrics.getNumInserts(), is(2L));
		assertThat(metrics.getNumFailures(), is(0L));
	}

	private DocumentDb throwsOnUpdateDocumentDb() {
		return DocumentDb.create((name, readPreference) -> new FakeDocumentCollection() {
			@Override
			public void update(Document document) {
				throw new RuntimeException();
			}
		});
	}
}
