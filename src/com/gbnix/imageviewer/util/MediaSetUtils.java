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

package com.gbnix.imageviewer.util;

import java.util.Comparator;

import android.os.Environment;

import com.gbnix.imageviewer.data.MediaSet;
import com.gbnix.imageviewer.data.Path;

public class MediaSetUtils {
	public static final Comparator<MediaSet> NAME_COMPARATOR = new NameComparator();

	public static final int CAMERA_BUCKET_ID = GalleryUtils.getBucketId(Environment.getExternalStorageDirectory()
			.toString() + "/DCIM/Camera");
	public static final int DOWNLOAD_BUCKET_ID = GalleryUtils.getBucketId(Environment.getExternalStorageDirectory()
			.toString() + "/" + BucketNames.DOWNLOAD);
	public static final int IMPORTED_BUCKET_ID = GalleryUtils.getBucketId(Environment.getExternalStorageDirectory()
			.toString() + "/" + BucketNames.IMPORTED);
	public static final int SNAPSHOT_BUCKET_ID = GalleryUtils.getBucketId(Environment.getExternalStorageDirectory()
			.toString() + "/Pictures/Screenshots");

	private static final Path[] CAMERA_PATHS = { Path.fromString("/local/all/" + CAMERA_BUCKET_ID),
			Path.fromString("/local/image/" + CAMERA_BUCKET_ID), Path.fromString("/local/video/" + CAMERA_BUCKET_ID) };

	public static boolean isCameraSource(final Path path) {
		return CAMERA_PATHS[0] == path || CAMERA_PATHS[1] == path || CAMERA_PATHS[2] == path;
	}

	// Sort MediaSets by name
	public static class NameComparator implements Comparator<MediaSet> {
		@Override
		public int compare(final MediaSet set1, final MediaSet set2) {
			final int result = set1.getName().compareToIgnoreCase(set2.getName());
			if (result != 0) return result;
			return set1.getPath().toString().compareTo(set2.getPath().toString());
		}
	}
}
