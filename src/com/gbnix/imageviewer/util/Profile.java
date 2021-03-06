/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.gbnix.imageviewer.util;

import java.util.ArrayList;
import java.util.Random;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

// The Profile class is used to collect profiling information for a thread. It
// samples stack traces for a thread periodically. enable() and disable() is
// used to enable and disable profiling for the calling thread. The profiling
// information can then be dumped to a file using the dumpToFile() method.
//
// The disableAll() method can be used to disable profiling for all threads and
// can be called in onPause() to ensure all profiling is disabled when an
// activity is paused.
public class Profile {
	private static final int NS_PER_MS = 1000000;

	// This is a watchdog thread which dumps stacks of other threads
	// periodically.
	private static Watchdog sWatchdog = new Watchdog();

	public static void commit() {
		sWatchdog.commit(Thread.currentThread());
	}

	// Disable profiling for the calling thread.
	public static void disable() {
		sWatchdog.removeWatchEntry(Thread.currentThread());
	}

	// Disable profiling for all threads.
	public static void disableAll() {
		sWatchdog.removeAllWatchEntries();
	}

	public static void drop() {
		sWatchdog.drop(Thread.currentThread());
	}

	// Dump the profiling data to a file.
	public static void dumpToFile(final String filename) {
		sWatchdog.dumpToFile(filename);
	}

	// Enable profiling for the calling thread. Periodically (every
	// cycleTimeInMs
	// milliseconds) sample the stack trace of the calling thread.
	public static void enable(final int cycleTimeInMs) {
		final Thread t = Thread.currentThread();
		sWatchdog.addWatchEntry(t, cycleTimeInMs);
	}

	// Hold the future samples coming from current thread until commit() or
	// drop() is called, and those samples are recorded or ignored as a result.
	// This must called after enable() to be effective.
	public static void hold() {
		sWatchdog.hold(Thread.currentThread());
	}

	// Reset the collected profiling data.
	public static void reset() {
		sWatchdog.reset();
	}

	private static class Watchdog {
		private final ArrayList<WatchEntry> mList = new ArrayList<WatchEntry>();
		private final HandlerThread mHandlerThread;
		private final Handler mHandler;
		private final Runnable mProcessRunnable = new Runnable() {
			@Override
			public void run() {
				synchronized (Watchdog.this) {
					processList();
				}
			}
		};
		private final Random mRandom = new Random();
		private final ProfileData mProfileData = new ProfileData();

		public Watchdog() {
			mHandlerThread = new HandlerThread("Watchdog Handler", Process.THREAD_PRIORITY_FOREGROUND);
			mHandlerThread.start();
			mHandler = new Handler(mHandlerThread.getLooper());
		}

		public synchronized void addWatchEntry(final Thread thread, final int cycleTime) {
			final WatchEntry e = new WatchEntry();
			e.thread = thread;
			e.cycleTime = cycleTime;
			final int firstDelay = 1 + mRandom.nextInt(cycleTime);
			e.wakeTime = (int) (System.nanoTime() / NS_PER_MS) + firstDelay;
			mList.add(e);
			processList();
		}

		public synchronized void commit(final Thread t) {
			final WatchEntry entry = findEntry(t);
			if (entry == null) return;
			final ArrayList<String[]> stacks = entry.holdingStacks;
			for (int i = 0; i < stacks.size(); i++) {
				mProfileData.addSample(stacks.get(i));
			}
			entry.isHolding = false;
			entry.holdingStacks.clear();
		}

		public synchronized void drop(final Thread t) {
			final WatchEntry entry = findEntry(t);
			if (entry == null) return;
			entry.isHolding = false;
			entry.holdingStacks.clear();
		}

		public synchronized void dumpToFile(final String filename) {
			mProfileData.dumpToFile(filename);
		}

		public synchronized void hold(final Thread t) {
			final WatchEntry entry = findEntry(t);

			// This can happen if the profiling is disabled (probably from
			// another thread). Same check is applied in commit() and drop()
			// below.
			if (entry == null) return;

			entry.isHolding = true;
		}

		public synchronized void removeAllWatchEntries() {
			mList.clear();
			processList();
		}

		public synchronized void removeWatchEntry(final Thread thread) {
			for (int i = 0; i < mList.size(); i++) {
				if (mList.get(i).thread == thread) {
					mList.remove(i);
					break;
				}
			}
			processList();
		}

		public synchronized void reset() {
			mProfileData.reset();
		}

		private WatchEntry findEntry(final Thread thread) {
			for (int i = 0; i < mList.size(); i++) {
				final WatchEntry entry = mList.get(i);
				if (entry.thread == thread) return entry;
			}
			return null;
		}

		private void processList() {
			mHandler.removeCallbacks(mProcessRunnable);
			if (mList.size() == 0) return;

			final int currentTime = (int) (System.nanoTime() / NS_PER_MS);
			int nextWakeTime = 0;

			for (final WatchEntry entry : mList) {
				if (currentTime > entry.wakeTime) {
					entry.wakeTime += entry.cycleTime;
					sampleStack(entry);
				}

				if (entry.wakeTime > nextWakeTime) {
					nextWakeTime = entry.wakeTime;
				}
			}

			final long delay = nextWakeTime - currentTime;
			mHandler.postDelayed(mProcessRunnable, delay);
		}

		private void sampleStack(final WatchEntry entry) {
			final Thread thread = entry.thread;
			final StackTraceElement[] stack = thread.getStackTrace();
			final String[] lines = new String[stack.length];
			for (int i = 0; i < stack.length; i++) {
				lines[i] = stack[i].toString();
			}
			if (entry.isHolding) {
				entry.holdingStacks.add(lines);
			} else {
				mProfileData.addSample(lines);
			}
		}
	}

	// This is a watchdog entry for one thread.
	// For every cycleTime period, we dump the stack of the thread.
	private static class WatchEntry {
		Thread thread;

		// Both are in milliseconds
		int cycleTime;
		int wakeTime;

		boolean isHolding;
		ArrayList<String[]> holdingStacks = new ArrayList<String[]>();
	}
}
