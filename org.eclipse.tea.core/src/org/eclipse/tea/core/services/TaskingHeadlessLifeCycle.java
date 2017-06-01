/*******************************************************************************
 *  Copyright (c) 2017 SSI Schaefer IT Solutions GmbH and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *      SSI Schaefer IT Solutions GmbH
 *******************************************************************************/
package org.eclipse.tea.core.services;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

import org.eclipse.tea.core.internal.TaskingEngineApplication;

/**
 * Services that implement this interface are asked to check for startup
 * preconditions of the headless {@link TaskingEngineApplication}, and may
 * request a restart of the application. This allows to implement features like
 * automatic updating of the actual installation.
 * <p>
 * This interface is not used when tasking from the IDE.
 */
public interface TaskingHeadlessLifeCycle {

	public enum StartupAction {
		CONTINUE, RESTART
	}

	/**
	 * Annotates a method that is to be called on startup of the application.
	 * The method MUST return a result of type {@link StartupAction}.
	 */
	@Documented
	@Qualifier
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface HeadlessStartup {

	}

	/**
	 * Annotates a method that is to be called on shutdown of the application,
	 * after everything else has been done.
	 */
	@Documented
	@Qualifier
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface HeadlessShutdown {

	}

}
