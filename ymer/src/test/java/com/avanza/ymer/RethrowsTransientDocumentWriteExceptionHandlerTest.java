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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.ServerAddress;

/**
 * @author Kristoffer Erlandsson (krierl), kristoffer.erlandsson@avanzabank.se
 */
public class RethrowsTransientDocumentWriteExceptionHandlerTest {

	private final DocumentWriteExceptionHandler handler = new RethrowsTransientDocumentWriteExceptionHandler();

	@Test
	public void throwsOnTransientException() throws Exception {
		assertThrows(TransientDocumentWriteException.class, () -> handler.handleException(newMongoNetworkException(), ""));
	}

	@Test
	public void doesNotThrowOnNonTransientException() throws Exception {
		try {
			handler.handleException(new RuntimeException(), "");
		} catch (RuntimeException e) {
			fail("Exception should not be thrown");
		}
	}

	@Test	
	public void throwsOnTransientExceptionByErrorMessage() throws Exception {		
		List<String> transientErrorMessages = Arrays.asList("No replica set members available for query with", "not master", 
				"Command failed with error 13: 'not authorized on dbxyz");
		for (String message : transientErrorMessages) {
			try {
				handler.handleException(new RuntimeException(message), "");
				fail("Exception should not be thrown for exception with message " + message);
			} catch (TransientDocumentWriteException ignored) {
			}
		}
	}

	private MongoSocketException newMongoNetworkException() {
		return new MongoSocketReadException("", new ServerAddress());
	}

}
