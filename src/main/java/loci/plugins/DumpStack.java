/*
 * #%L
 * Various plugins for ImageJ.
 * %%
 * Copyright (C) 2010 - 2014 Board of Regents of the University of
 * Wisconsin-Madison.
 * %%
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
 * #L%
 */

package loci.plugins;

import ij.IJ;
import ij.plugin.PlugIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

/**
 * Provides a complete stack dump of all threads.
 * <p>
 * The output is similar to a subset of that given when Ctrl+\ (or Ctrl+Pause on
 * Windows) is pressed from the console.
 * </p>
 * 
 * @author Curtis Rueden
 */
public class DumpStack implements PlugIn {

	// -- Constants --

	private static final String NL = System.getProperty("line.separator");

	// -- Runnable methods --

	@Override
	public void run(final String arg) {
		final StringBuilder sb = new StringBuilder();

		final Map<Thread, StackTraceElement[]> stackTraces =
			Thread.getAllStackTraces();

		// sort list of threads by name
		final ArrayList<Thread> threads =
			new ArrayList<Thread>(stackTraces.keySet());
		Collections.sort(threads, new Comparator<Thread>() {

			@Override
			public int compare(final Thread t1, final Thread t2) {
				return t1.getName().compareTo(t2.getName());
			}
		});

		for (final Thread t : threads) {
			dumpThread(t, stackTraces.get(t), sb);
		}

		IJ.log(sb.toString());
	}

	// -- Helper methods --

	private void dumpThread(final Thread t, final StackTraceElement[] trace,
		final StringBuilder sb)
	{
		threadInfo(t, sb);
		for (final StackTraceElement element : trace) {
			sb.append("        at ");
			sb.append(element);
			sb.append(NL);
		}
		sb.append(NL);
	}

	private void threadInfo(final Thread t, final StringBuilder sb) {
		sb.append("\"");
		sb.append(t.getName());
		sb.append("\"");
		if (!t.isAlive()) sb.append(" DEAD");
		if (t.isInterrupted()) sb.append(" INTERRUPTED");
		if (t.isDaemon()) sb.append(" daemon");
		sb.append(" prio=");
		sb.append(t.getPriority());
		sb.append(" id=");
		sb.append(t.getId());
		sb.append(" group=");
		sb.append(t.getThreadGroup().getName());
		sb.append(NL);
		sb.append("   java.lang.Thread.State: ");
		sb.append(t.getState());
		sb.append(NL);
	}

}
