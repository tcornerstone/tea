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
package org.eclipse.tea.core.internal.stats;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.di.extensions.Service;
import org.eclipse.tea.core.TaskExecutionContext;
import org.eclipse.tea.core.TaskingInjectionHelper;
import org.eclipse.tea.core.annotations.lifecycle.FinishTaskChain;
import org.eclipse.tea.core.internal.listeners.TaskingStatusTracker;
import org.eclipse.tea.core.internal.model.TaskingModel;
import org.eclipse.tea.core.services.TaskingLifeCycleListener;
import org.eclipse.tea.core.services.TaskingLog;
import org.eclipse.tea.core.services.TaskingStatisticsContribution;
import org.eclipse.tea.core.services.TaskingStatisticsContribution.TaskingStatisticProvider;
import org.osgi.service.component.annotations.Component;

import com.google.common.base.Strings;
import com.google.gson.GsonBuilder;

import jakarta.inject.Named;

/**
 * Handles the actual posting of statistics to a remote server (if configured).
 */
@Component
public class TaskingStatListener implements TaskingLifeCycleListener {

	@FinishTaskChain
	public void postStats(TaskingLog log, TaskingStatConfig config, TaskExecutionContext context,
			@Named(TaskingInjectionHelper.CTX_PREPARED_TASKS) List<Object> allTasks, TaskingStatusTracker tracker,
			@Service List<TaskingStatisticsContribution> contributions) {

		// only post info if server is configured and status is not error
		if (!config.statDebugMode) {
			if (Strings.isNullOrEmpty(config.statServer) || tracker.getStatus(context).getSeverity() >= IStatus.ERROR) {
				return;
			}
		}

		StatDTO dto = new StatDTO();
		dto.timestamp = System.currentTimeMillis();
		dto.duration = tracker.getDuration(context);
		dto.taskChainClass = context.getUnderlyingChain().getClass().getName();
		dto.taskChainName = TaskingModel.getTaskChainName(context.getUnderlyingChain());

		try {
			dto.sysInfo = gatherSystemInfo();
		} catch (Exception e) {
			log.error("cannot gather system information", e);
		}
		gatherTasks(dto, allTasks, context, tracker);

		// add additional informations if present from any contribution
		for (TaskingStatisticsContribution c : contributions) {
			Object contribution = ContextInjectionFactory.invoke(c, TaskingStatisticProvider.class,
					context.getContext());
			if (contribution != null) {
				dto.contributions.put(getContributionQualifier(c), contribution);
			}
		}

		try {
			post(log, config, dto);
		} catch (Exception e) {
			// no stack trace, don't want to clutter output
			log.warn("failed to post statistics: " + e.toString());
		}
	}

	private String getContributionQualifier(TaskingStatisticsContribution c) {
		for (Method m : c.getClass().getMethods()) {
			TaskingStatisticProvider p = m.getAnnotation(TaskingStatisticProvider.class);
			if (p != null) {
				if (!Strings.isNullOrEmpty(p.qualifier())) {
					return p.qualifier();
				}

				break;
			}
		}
		return c.getClass().getSimpleName();
	}

	private StatusDTO convertStatus(IStatus status) {
		StatusDTO result = new StatusDTO();
		if (status == null) {
			result.message = "Skipped";
			return result;
		}
		result.severity = status.getSeverity();
		result.message = status.getMessage();

		if (status.getException() != null) {
			try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
				status.getException().printStackTrace(new PrintStream(bos));
				result.exception = bos.toString();
			} catch (Exception e) {
				result.exception = "<unknown exception>";
			}
		}
		return result;
	}

	private void gatherTasks(StatDTO dto, List<Object> allTasks, TaskExecutionContext context,
			TaskingStatusTracker tracker) {
		for (Object o : allTasks) {
			TaskDTO t = new TaskDTO();
			t.taskClass = o.getClass().getName();
			t.taskName = TaskingModel.getTaskName(o);
			t.duration = tracker.getDuration(context, o);
			t.status = convertStatus(tracker.getStatus(context, o));
			dto.tasks.add(t);
		}
	}

	private SysDTO gatherSystemInfo() {
		SysDTO dto = new SysDTO();
		OperatingSystemMXBean osb = ManagementFactory.getOperatingSystemMXBean();
		dto.processors = osb.getAvailableProcessors();
		dto.os = osb.getName() + ":" + osb.getArch() + ":" + osb.getVersion();
		dto.loadavg = osb.getSystemLoadAverage();

		System.out.println("Available methods are: "
				+ Arrays.stream(osb.getClass().getDeclaredMethods()).map(Method::getName).toList());

		// infos that are not on the public API
		dto.totalMem = tryGet(osb, "getTotalMemorySize", 0L);
		dto.freeMem = tryGet(osb, "getFreeMemorySize", 0L);

		dto.totalSwap = tryGet(osb, "getTotalSwapSpaceSize", 0L);
		dto.freeSwap = tryGet(osb, "getFreeSwapSpaceSize", 0L);

		dto.processLoad = tryGet(osb, "getProcessCpuLoad", 0.0d);
		dto.systemLoad = tryGet(osb, "getCpuLoad", 0.0d);

		dto.processCpuTime = tryGet(osb, "getProcessCpuTime", 0l);

		try {
			String javaVersion = System.getProperty("java.version");
			if (javaVersion != null) {
				dto.javaVersion = javaVersion;
			}
		} catch (SecurityException e) {

		}
		return dto;
	}

	@SuppressWarnings("unchecked")
	private <T> T tryGet(Object o, String methodName, T defaultValue) {
		try {
			Method m = o.getClass().getDeclaredMethod(methodName);
			m.setAccessible(true);
			T rv = (T) m.invoke(o);
			if (rv == null) {
				return defaultValue;
			}
			return rv;
		} catch (Exception e) {
			return defaultValue;
		}
	}

	private void post(TaskingLog log, TaskingStatConfig config, StatDTO dto) throws Exception {
		// serialize
		String content = new GsonBuilder().setPrettyPrinting().create().toJson(dto);
		try {
			Files.writeString(new File(new File(System.getProperty("user.home")), "telemetry.json").toPath(), content,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException ex) {
			log.warn("failed", ex);
		}

		// post
		if (config.statDebugMode) {
			log.debug("will post to server (debug mode enabled): ");
			log.debug(content);
		}
		HttpURLConnection connection = null;
		try {
			String charset = Charset.defaultCharset().name();
			connection = (HttpURLConnection) new URI(config.statServer).toURL().openConnection();
			connection.setDoOutput(true);
			connection.setRequestProperty("Accept-Charset", charset);
			connection.setRequestProperty("Content-Type", "application/json");
			try (OutputStream output = connection.getOutputStream()) {
				output.write(content.getBytes(charset));
			}
			connection.getResponseCode();
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}

		log.debug("posted statistics to " + config.statServer);
	}

	@SuppressWarnings("unused")
	private static final class StatDTO {
		public long timestamp;
		public String taskChainClass;
		public String taskChainName;
		public long duration;

		public List<TaskDTO> tasks = new ArrayList<>();
		public SysDTO sysInfo = new SysDTO();

		public Map<String, Object> contributions = new TreeMap<>();
	}

	@SuppressWarnings("unused")
	private static class SysDTO {
		public String os = "<Unkown>";
		public long processCpuTime = 0;
		public double systemLoad = 0.0;
		public double processLoad = 0.0;
		public long totalSwap = 0;
		public long freeSwap = 0;
		public long freeMem = 0;
		public long totalMem = 0;
		public double loadavg = 0;
		public int processors = 0;
		public String javaVersion = "<Unkown>";
	}

	@SuppressWarnings("unused")
	private static class TaskDTO {
		public String taskClass;
		public String taskName;

		public StatusDTO status;
		public long duration;
	}

	@SuppressWarnings("unused")
	private static class StatusDTO {
		public int severity = 0;
		public String message;
		public String exception;
	}

}
