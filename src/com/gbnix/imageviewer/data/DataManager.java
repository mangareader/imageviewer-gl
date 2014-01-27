/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.gbnix.imageviewer.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.WeakHashMap;

import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;

import com.gbnix.imageviewer.app.GalleryApp;
import com.gbnix.imageviewer.common.Utils;
import com.gbnix.imageviewer.data.MediaSet.ItemConsumer;
import com.gbnix.imageviewer.data.MediaSource.PathId;

// DataManager manages all media sets and media items in the system.
//
// Each MediaSet and MediaItem has a unique 64 bits id. The most significant
// 32 bits represents its parent, and the least significant 32 bits represents
// the self id. For MediaSet the self id is is globally unique, but for
// MediaItem it's unique only relative to its parent.
//
// To make sure the id is the same when the MediaSet is re-created, a child key
// is provided to obtainSetId() to make sure the same self id will be used as
// when the parent and key are the same. A sequence of child keys is called a
// path. And it's used to identify a specific media set even if the process is
// killed and re-created, so child keys should be stable identifiers.

public class DataManager {
	public static final int INCLUDE_IMAGE = 1;
	public static final int INCLUDE_ALL = INCLUDE_IMAGE;
	public static final int INCLUDE_LOCAL_ONLY = 4;
	public static final int INCLUDE_LOCAL_IMAGE_ONLY = INCLUDE_LOCAL_ONLY | INCLUDE_IMAGE;
	public static final int INCLUDE_LOCAL_ALL_ONLY = INCLUDE_LOCAL_ONLY | INCLUDE_IMAGE;

	// Any one who would like to access data should require this lock
	// to prevent concurrency issue.
	public static final Object LOCK = new Object();

	private static final String TAG = "DataManager";

	// This is the path for the media set seen by the user at top level.
	private static final String TOP_LOCAL_SET_PATH = "/local/all";
	private static final String TOP_SET_PATH = TOP_LOCAL_SET_PATH;

	private static final String ACTION_DELETE_PICTURE = "com.gbnix.imageviewer.action.DELETE_PICTURE";

	public static final Comparator<MediaItem> sDateTakenComparator = new DateTakenComparator();

	private final Handler mDefaultMainHandler;

	private final GalleryApp mApplication;

	private int mActiveCount = 0;
	private final HashMap<Uri, NotifyBroker> mNotifierMap = new HashMap<Uri, NotifyBroker>();

	private final HashMap<String, MediaSource> mSourceMap = new LinkedHashMap<String, MediaSource>();

	public DataManager(final GalleryApp application) {
		mApplication = application;
		mDefaultMainHandler = new Handler(application.getMainLooper());
	}

	// Sends a local broadcast if a local image or video is deleted. This is
	// used to update the thumbnail shown in the camera app.
	public void broadcastLocalDeletion() {
		final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(mApplication.getAndroidContext());
		final Intent intent = new Intent(ACTION_DELETE_PICTURE);
		manager.sendBroadcast(intent);
	}

	public void delete(final Path path) {
		getMediaObject(path).delete();
	}

	public Path findPathByUri(final Uri uri, final String type) {
		if (uri == null) return null;
		for (final MediaSource source : mSourceMap.values()) {
			final Path path = source.findPathByUri(uri, type);
			if (path != null) return path;
		}
		return null;
	}

	public Uri getContentUri(final Path path) {
		return getMediaObject(path).getContentUri();
	}

	public Path getDefaultSetOf(final Path item) {
		final MediaSource source = mSourceMap.get(item.getPrefix());
		return source == null ? null : source.getDefaultSetOf(item);
	}

	public MediaObject getMediaObject(final Path path) {
		final MediaObject obj = path.getObject();
		if (obj != null) return obj;

		final MediaSource source = mSourceMap.get(path.getPrefix());
		if (source == null) {
			Log.w(TAG, "cannot find media source for path: " + path);
			return null;
		}

		try {
			final MediaObject object = source.createMediaObject(path);
			if (object == null) {
				Log.w(TAG, "cannot create media object: " + path);
			}
			return object;
		} catch (final Throwable t) {
			Log.w(TAG, "exception in creating media object: " + path, t);
			return null;
		}
	}

	public MediaObject getMediaObject(final String s) {
		return getMediaObject(Path.fromString(s));
	}

	public MediaSet getMediaSet(final Path path) {
		return (MediaSet) getMediaObject(path);
	}

	public MediaSet getMediaSet(final String s) {
		return (MediaSet) getMediaObject(s);
	}

	public MediaSet[] getMediaSetsFromString(final String segment) {
		final String[] seq = Path.splitSequence(segment);
		final int n = seq.length;
		final MediaSet[] sets = new MediaSet[n];
		for (int i = 0; i < n; i++) {
			sets[i] = getMediaSet(seq[i]);
		}
		return sets;
	}

	public int getMediaType(final Path path) {
		return getMediaObject(path).getMediaType();
	}

	// The following methods forward the request to the proper object.
	public int getSupportedOperations(final Path path) {
		return getMediaObject(path).getSupportedOperations();
	}

	public String getTopSetPath(final int typeBits) {

		switch (typeBits) {
			case INCLUDE_ALL:
				return TOP_SET_PATH;
			case INCLUDE_LOCAL_ALL_ONLY:
				return TOP_LOCAL_SET_PATH;
			default:
				throw new IllegalArgumentException();
		}
	}

	// Returns number of bytes used by cached pictures if all pending
	// downloads and removals are completed.
	public long getTotalTargetCacheSize() {
		long sum = 0;
		for (final MediaSource source : mSourceMap.values()) {
			sum += source.getTotalTargetCacheSize();
		}
		return sum;
	}

	// Returns number of bytes used by cached pictures currently downloaded.
	public long getTotalUsedCacheSize() {
		long sum = 0;
		for (final MediaSource source : mSourceMap.values()) {
			sum += source.getTotalUsedCacheSize();
		}
		return sum;
	}

	public synchronized void initializeSourceMap() {
		if (!mSourceMap.isEmpty()) return;

		// the order matters, the UriSource must come last
		addSource(new UriSource(mApplication));

		if (mActiveCount > 0) {
			for (final MediaSource source : mSourceMap.values()) {
				source.resume();
			}
		}
	}

	// Maps a list of Paths to MediaItems, and invoke consumer.consume()
	// for each MediaItem (may not be in the same order as the input list).
	// An index number is also passed to consumer.consume() to identify
	// the original position in the input list of the corresponding Path (plus
	// startIndex).
	public void mapMediaItems(final ArrayList<Path> list, final ItemConsumer consumer, final int startIndex) {
		final HashMap<String, ArrayList<PathId>> map = new HashMap<String, ArrayList<PathId>>();

		// Group the path by the prefix.
		final int n = list.size();
		for (int i = 0; i < n; i++) {
			final Path path = list.get(i);
			final String prefix = path.getPrefix();
			ArrayList<PathId> group = map.get(prefix);
			if (group == null) {
				group = new ArrayList<PathId>();
				map.put(prefix, group);
			}
			group.add(new PathId(path, i + startIndex));
		}

		// For each group, ask the corresponding media source to map it.
		for (final Entry<String, ArrayList<PathId>> entry : map.entrySet()) {
			final String prefix = entry.getKey();
			final MediaSource source = mSourceMap.get(prefix);
			source.mapMediaItems(entry.getValue(), consumer);
		}
	}

	public void pause() {
		if (--mActiveCount == 0) {
			for (final MediaSource source : mSourceMap.values()) {
				source.pause();
			}
		}
	}

	public MediaObject peekMediaObject(final Path path) {
		return path.getObject();
	}

	public void registerChangeNotifier(final Uri uri, final ChangeNotifier notifier) {
		NotifyBroker broker = null;
		synchronized (mNotifierMap) {
			broker = mNotifierMap.get(uri);
			if (broker == null) {
				broker = new NotifyBroker(mDefaultMainHandler);
				mApplication.getContentResolver().registerContentObserver(uri, true, broker);
				mNotifierMap.put(uri, broker);
			}
		}
		broker.registerNotifier(notifier);
	}

	public void resume() {
		if (++mActiveCount == 1) {
			for (final MediaSource source : mSourceMap.values()) {
				source.resume();
			}
		}
	}

	public void rotate(final Path path, final int degrees) {
		getMediaObject(path).rotate(degrees);
	}

	// open for debug
	void addSource(final MediaSource source) {
		mSourceMap.put(source.getPrefix(), source);
	}

	private static class DateTakenComparator implements Comparator<MediaItem> {
		@Override
		public int compare(final MediaItem item1, final MediaItem item2) {
			return -Utils.compare(item1.getDateInMs(), item2.getDateInMs());
		}
	}

	private static class NotifyBroker extends ContentObserver {
		private final WeakHashMap<ChangeNotifier, Object> mNotifiers = new WeakHashMap<ChangeNotifier, Object>();

		public NotifyBroker(final Handler handler) {
			super(handler);
		}

		@Override
		public synchronized void onChange(final boolean selfChange) {
			for (final ChangeNotifier notifier : mNotifiers.keySet()) {
				notifier.onChange(selfChange);
			}
		}

		public synchronized void registerNotifier(final ChangeNotifier notifier) {
			mNotifiers.put(notifier, null);
		}
	}
}
