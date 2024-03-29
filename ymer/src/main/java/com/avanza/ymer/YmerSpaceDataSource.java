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

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.bson.Document;
import org.openspaces.core.cluster.ClusterInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.avanza.ymer.MirroredObjectLoader.LoadedDocument;
import com.gigaspaces.datasource.DataIterator;

final class YmerSpaceDataSource extends AbstractSpaceDataSource {

    private static final Logger logger = LoggerFactory.getLogger(YmerSpaceDataSource.class);

    private final SpaceMirrorContext spaceMirrorContext;
    private ClusterInfo clusterInfo;

    public YmerSpaceDataSource(SpaceMirrorContext spaceMirror) {
        this.spaceMirrorContext = spaceMirror;
    }

    @Override
    public DataIterator<Object> initialDataLoad() {
        InitialLoadCompleteDispatcher initialLoadCompleteDispatcher = new InitialLoadCompleteDispatcher();

        Stream<Object> objectStream = spaceMirrorContext.getMirroredDocuments().stream()
                .sorted(comparing(MirroredObject::getCollectionName)) // Make load order same for all partitions to reduce mongo cache misses
                .collect(toList()).stream() // Pass through a list to make sorting not block the whole stream on iterator.next which will be called later
                .filter(md -> !md.excludeFromInitialLoad())
                .flatMap(mirroredObject -> load(mirroredObject, initialLoadCompleteDispatcher));

        return new IteratorAdapter(objectStream.iterator(), initialLoadCompleteDispatcher::initialLoadComplete);
    }

    <T> Stream<T> load(MirroredObject<T> mirroredObject, InitialLoadCompleteDispatcher initialLoadCompleteDispatcher) {
        logger.info("Loading all documents for type: {}", mirroredObject.getMirroredType().getName());
        MirroredObjectLoader<T> documentLoader = spaceMirrorContext.createDocumentLoader(
                mirroredObject,
                getInstanceId(),
                getPartitionCount());

        AtomicInteger counter = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        return documentLoader.streamAllObjects()
                .map(createPatchedDocumentWriteBack(mirroredObject, initialLoadCompleteDispatcher))
                .peek(d -> counter.incrementAndGet())
                .onClose(() -> logger.info("Loaded {} documents from {} in {} milliseconds!", counter.get(), mirroredObject.getCollectionName(), System.currentTimeMillis() - start));
    }

    private <T> Function<LoadedDocument<T>, T> createPatchedDocumentWriteBack(MirroredObject<T> document, InitialLoadCompleteDispatcher initialLoadCompleteDispatcher) {
        AtomicInteger totalWritebackCount = new AtomicInteger(0);
        initialLoadCompleteDispatcher.onInitialLoadComplete(() -> logger.debug("Updated {} documents in db for {}", totalWritebackCount.get(), document.getMirroredType().getName()));
        return loadedDocument -> {
            if (document.writeBackPatchedDocuments()) {
                loadedDocument.getPatchedDocument().ifPresent(patchedDocument -> doWriteBackPatchedDocument(document, patchedDocument));
                totalWritebackCount.incrementAndGet();
            }
            return loadedDocument.getDocument();
        };
    }

    private <T> PatchedDocument doWriteBackPatchedDocument(MirroredObject<T> document, PatchedDocument patchedDocument) {
        DocumentCollection documentCollection = spaceMirrorContext.getDocumentCollection(document);
        Document newVersion = spaceMirrorContext.getPreWriteProcessing(document.getMirroredType()).preWrite(patchedDocument.getNewVersion());
        documentCollection.replace(patchedDocument.getOldVersion(), newVersion);
        return patchedDocument;
    }

    @Override
    public void setClusterInfo(ClusterInfo clusterInfo) {
        this.clusterInfo = clusterInfo;
    }

    @Override
    public <T> T loadObject(Class<T> spaceType, Object documentId) {
        MirroredObject<T> mirroredObject = spaceMirrorContext.getMirroredDocument(spaceType);
        MirroredObjectLoader<T> documentLoader = spaceMirrorContext.createDocumentLoader(mirroredObject, getInstanceId(), getPartitionCount());
        Optional<LoadedDocument<T>> loadDocument = documentLoader.loadById(documentId);
        writeBackPatchedDocuments(mirroredObject, loadDocument.map(Arrays::asList).orElse(Collections.emptyList()));
        return loadDocument
                .map(LoadedDocument::getDocument)
                .orElse(null);
    }

    private Integer getPartitionCount() {
        return clusterInfo.getNumberOfInstances();
    }

    private Integer getInstanceId() {
        return clusterInfo.getInstanceId();
    }

    @Override
    public <T> Collection<T> loadObjects(Class<T> spaceType, T template) {
        MirroredObject<T> mirroredObject = spaceMirrorContext.getMirroredDocument(spaceType);
        MirroredObjectLoader<T> documentLoader = spaceMirrorContext.createDocumentLoader(mirroredObject, getInstanceId(), getPartitionCount());
        List<LoadedDocument<T>> loadedDocuments = documentLoader.loadByQuery(template);
        writeBackPatchedDocuments(mirroredObject, loadedDocuments);
        return loadedDocuments
                .stream()
                .map(LoadedDocument::getDocument)
                .collect(toList());
    }

    private <T> void writeBackPatchedDocuments(MirroredObject<T> document, List<LoadedDocument<T>> loadedDocuments) {
        if (!document.writeBackPatchedDocuments()) {
            return;
        }
        long patchCount = loadedDocuments.stream()
                .flatMap(loadedDocument -> loadedDocument.getPatchedDocument().stream())
                .map(patchedDocument -> doWriteBackPatchedDocument(document, patchedDocument))
                .count();
        logger.debug("Updated {} documents in db for {}", patchCount, document.getMirroredType().getName());
    }

    // Helper classes

    private static class IteratorAdapter implements DataIterator<Object> {
        private final Iterator<Object> it;
        private final Runnable iterationDone;

        public IteratorAdapter(Iterator<Object> it, Runnable itrationDoneCallback) {
            this.it = it;
            this.iterationDone = itrationDoneCallback;
        }

        @Override
        public boolean hasNext() {
            boolean hasNext = it.hasNext();
            if (!hasNext) {
                iterationDone.run();
            }
            return hasNext;
        }

        @Override
        public Object next() {
            return it.next();
        }

        @Override
        public void remove() {
            it.remove();
        }

        @Override
        public void close() {
        }
    }

    static class InitialLoadCompleteDispatcher {
        private final List<Runnable> l = new CopyOnWriteArrayList<>();

        public void onInitialLoadComplete(Runnable callback) {
            l.add(callback);
        }

        public void initialLoadComplete() {
            l.forEach(Runnable::run);
        }
    }
}
