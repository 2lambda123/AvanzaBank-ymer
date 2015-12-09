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
package se.avanzabank.mongodb.support;

import java.util.Arrays;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import se.avanzabank.mongodb.support.mirror.DocumentWriteTransientException;

/**
 * @author Kristoffer Erlandsson (krierl), kristoffer.erlandsson@avanzabank.se
 */
public class RethrowsTransientSpringExceptionHandler implements DocumentWriteExceptionHandler {

	private final Collection<Class<? extends Exception>> transientErrorClasses;
	private final Collection<String> transientErrorMessages;

	private Logger log = LoggerFactory.getLogger(RethrowsTransientSpringExceptionHandler.class);

	public RethrowsTransientSpringExceptionHandler() {
		this.transientErrorClasses = Arrays.<Class<? extends Exception>>asList(DataAccessResourceFailureException.class);
		this.transientErrorMessages = Arrays.asList("No replica set members available for query with", "not master");
	}

	@Override
	public void handleException(Exception exception, String operationDescription) {
		if (isTransient(exception)){
			logRecoverableError(exception, operationDescription);
			throw new DocumentWriteTransientException(exception);
		} else {
			logIrrecoverableError(exception, operationDescription);
		}
	}

	private boolean isTransient(Exception exception) {
		for (Class<? extends Exception> exceptionClass : transientErrorClasses) {
			if (exceptionClass.isAssignableFrom(exception.getClass())) {
				return true;
			}
		}
		for (String message : transientErrorMessages) {
			String exceptionMessage = exception.getMessage();
			if (exceptionMessage != null && exceptionMessage.startsWith(message)) {
				return true;
			}
		}
		return false;
	}
	
	private void logRecoverableError(Exception e, String operationDescription) {
		log.warn(
				"Network exception when executing mirror command! Attempted operation: " + operationDescription
						+ " - will propagate error",
				e);			
	}

	private void logIrrecoverableError(Exception e, String operationDescription) {
		log.error(
				"Exception when executing mirror command! Attempted operation: " + operationDescription
						+ " - This command will be ignored but the rest" +
						" of the commands in this bulk will be attempted." +
						" This can lead to data inconsistency in the mongo database." +
						" Must be investigated ASAP. If this error was preceeded by a DocumentWriteTransientException "
						+ "the cause might be that we reattempt already performed operations, which might be fine.",
				e);
	}

}
