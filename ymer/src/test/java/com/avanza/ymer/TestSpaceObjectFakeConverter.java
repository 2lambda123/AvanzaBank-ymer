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

import java.util.Optional;

import org.bson.Document;
import org.springframework.data.mongodb.core.query.Query;

public class TestSpaceObjectFakeConverter {

	static DocumentConverter create() {
		return DocumentConverter.create(new DocumentConverter.Provider() {
			@Override
			public Document convertToBsonDocument(Object type) {
				if (type instanceof TestSpaceObject) {
					TestSpaceObject testSpaceObject = (TestSpaceObject) type;
					if (testSpaceObject.isFailConversion()) {
						throw new RuntimeException("Could not convert " + testSpaceObject);
					}
					Document document = new Document();
					document.put("_id", testSpaceObject.getId());
					if (testSpaceObject.getMessage() != null) {
						document.put("message", testSpaceObject.getMessage());
					}
					return document;
				} else if (type instanceof TestSpaceOtherObject) {
					TestSpaceOtherObject testSpaceOtherObject = (TestSpaceOtherObject) type;
					Document document = new Document();
					document.put("_id", testSpaceOtherObject.getId());
					if (testSpaceOtherObject.getMessage() != null) {
						document.put("message", testSpaceOtherObject.getMessage());
					}
					return document;
				} else if (type instanceof TestReloadableSpaceObject) {
					TestReloadableSpaceObject testSpaceObject = (TestReloadableSpaceObject) type;
					Document document = new Document();
					document.put("_id", testSpaceObject.getId());
					document.put("patched", testSpaceObject.isPatched());
					document.put("versionID", testSpaceObject.getVersionID());
					if (testSpaceObject.getLatestRestoreVersion() != null) {
						document.put("latestRestoreVersion", testSpaceObject.getLatestRestoreVersion());
					}
					return document;
				} else {
					throw new RuntimeException("Unknown object type: " + type.getClass());
				}
			}

			@Override
			public <T> T convert(Class<T> toType, Document document) {
				if (toType.equals(TestSpaceObject.class)) {
					TestSpaceObject testSpaceObject = new TestSpaceObject();
					testSpaceObject.setId(document.getString("_id"));
					testSpaceObject.setMessage(document.getString("message"));
					return toType.cast(testSpaceObject);
				} else if (toType.equals(TestReloadableSpaceObject.class)){
					TestReloadableSpaceObject testSpaceObject = new TestReloadableSpaceObject();
					testSpaceObject.setId(Optional.ofNullable(document.getInteger("_id")).orElseThrow(NullPointerException::new));
					if (document.containsKey("patched")) {
						testSpaceObject.setPatched(document.getBoolean("patched"));
					}
					testSpaceObject.setVersionID(Optional.ofNullable(document.getInteger("versionID")).orElseThrow(NullPointerException::new));
					if (document.containsKey("latestRestoreVersion")) {
						testSpaceObject.setLatestRestoreVersion(Optional.ofNullable(document.getInteger("latestRestoreVersion")).orElseThrow(NullPointerException::new));
					}
					return toType.cast(testSpaceObject);
				} else {
					throw new RuntimeException("Unknown object type: " + toType);
				}
			}

			@Override
			public Object convert(Object type) {
				if (type instanceof Number) {
					return type;
				}
				return type.toString();
			}

			@Override
			public Query toQuery(Object template) {
				throw new UnsupportedOperationException();
			}
		});



	}

}
