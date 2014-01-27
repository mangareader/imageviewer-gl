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

import android.net.Uri;

import com.gbnix.imageviewer.data.MediaSet.ItemConsumer;

public abstract class MediaSource {
	private static final String TAG = "MediaSource";
	private final String mPrefix;

	protected MediaSource(final String prefix) {
		mPrefix = prefix;
	}

	public abstract MediaObject createMediaObject(Path path);

	public Path findPathByUri(final Uri uri, final String type) {
		return null;
	}

	public Path getDefaultSetOf(final Path item) {
		return null;
	}

	public String getPrefix() {
		return mPrefix;
	}

	public long getTotalTargetCacheSize() {
		return 0;
	}

	public long getTotalUsedCacheSize() {
		return 0;
	}

	// Maps a list of Paths (all belong to this MediaSource) to MediaItems,
	// and invoke consumer.consume() for each MediaItem with the given id.
	//
	// This default implementation uses getMediaObject for each Path. Subclasses
	// may override this and provide more efficient implementation (like
	// batching the database query).
	public void mapMediaItems(final ArrayList<PathId> list, final ItemConsumer consumer) {
		final int n = list.size();
		for (int i = 0; i < n; i++) {
			final PathId pid = list.get(i);
			MediaObject obj = pid.path.getObject();
			if (obj == null) {
				try {
					obj = createMediaObject(pid.path);
				} catch (final Throwable th) {
					Log.w(TAG, "cannot create media object: " + pid.path, th);
				}
			}
			if (obj != null) {
				consumer.consume(pid.id, (MediaItem) obj);
			}
		}
	}

	public void pause() {
	}

	public void resume() {
	}

	public static class PathId {
		public Path path;
		public int id;

		public PathId(final Path path, final int id) {
			this.path = path;
			this.id = id;
		}
	}
}
