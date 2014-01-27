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

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.TreeMap;

import android.media.ExifInterface;

import com.gbnix.imageviewer.R;

public class MediaDetails implements Iterable<Entry<Integer, Object>> {
	@SuppressWarnings("unused")
	private static final String TAG = "MediaDetails";

	private final TreeMap<Integer, Object> mDetails = new TreeMap<Integer, Object>();
	private final HashMap<Integer, Integer> mUnits = new HashMap<Integer, Integer>();

	public static final int INDEX_TITLE = 1;
	public static final int INDEX_DESCRIPTION = 2;
	public static final int INDEX_DATETIME = 3;
	public static final int INDEX_LOCATION = 4;
	public static final int INDEX_WIDTH = 5;
	public static final int INDEX_HEIGHT = 6;
	public static final int INDEX_ORIENTATION = 7;
	public static final int INDEX_DURATION = 8;
	public static final int INDEX_MIMETYPE = 9;
	public static final int INDEX_SIZE = 10;

	// for EXIF
	public static final int INDEX_MAKE = 100;
	public static final int INDEX_MODEL = 101;
	public static final int INDEX_FLASH = 102;
	public static final int INDEX_FOCAL_LENGTH = 103;
	public static final int INDEX_WHITE_BALANCE = 104;
	public static final int INDEX_APERTURE = 105;
	public static final int INDEX_SHUTTER_SPEED = 106;
	public static final int INDEX_EXPOSURE_TIME = 107;
	public static final int INDEX_ISO = 108;

	// Put this last because it may be long.
	public static final int INDEX_PATH = 200;

	public void addDetail(final int index, final Object value) {
		mDetails.put(index, value);
	}

	public Object getDetail(final int index) {
		return mDetails.get(index);
	}

	public int getUnit(final int index) {
		return mUnits.get(index);
	}

	public boolean hasUnit(final int index) {
		return mUnits.containsKey(index);
	}

	@Override
	public Iterator<Entry<Integer, Object>> iterator() {
		return mDetails.entrySet().iterator();
	}

	public void setUnit(final int index, final int unit) {
		mUnits.put(index, unit);
	}

	public int size() {
		return mDetails.size();
	}

	public static void extractExifInfo(final MediaDetails details, final String filePath) {
		try {
			final ExifInterface exif = new ExifInterface(filePath);
			setExifData(details, exif, ExifInterface.TAG_FLASH, MediaDetails.INDEX_FLASH);
			setExifData(details, exif, ExifInterface.TAG_IMAGE_WIDTH, MediaDetails.INDEX_WIDTH);
			setExifData(details, exif, ExifInterface.TAG_IMAGE_LENGTH, MediaDetails.INDEX_HEIGHT);
			setExifData(details, exif, ExifInterface.TAG_MAKE, MediaDetails.INDEX_MAKE);
			setExifData(details, exif, ExifInterface.TAG_MODEL, MediaDetails.INDEX_MODEL);
			setExifData(details, exif, ExifInterface.TAG_APERTURE, MediaDetails.INDEX_APERTURE);
			setExifData(details, exif, ExifInterface.TAG_ISO, MediaDetails.INDEX_ISO);
			setExifData(details, exif, ExifInterface.TAG_WHITE_BALANCE, MediaDetails.INDEX_WHITE_BALANCE);
			setExifData(details, exif, ExifInterface.TAG_EXPOSURE_TIME, MediaDetails.INDEX_EXPOSURE_TIME);

			final double data = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0);
			if (data != 0f) {
				details.addDetail(MediaDetails.INDEX_FOCAL_LENGTH, data);
				details.setUnit(MediaDetails.INDEX_FOCAL_LENGTH, R.string.unit_mm);
			}
		} catch (final IOException ex) {
			// ignore it.
			Log.w(TAG, "", ex);
		}
	}

	private static void setExifData(final MediaDetails details, final ExifInterface exif, final String tag,
			final int key) {
		final String value = exif.getAttribute(tag);
		if (value != null) {
			if (key == MediaDetails.INDEX_FLASH) {
				final MediaDetails.FlashState state = new MediaDetails.FlashState(Integer.valueOf(value.toString()));
				details.addDetail(key, state);
			} else {
				details.addDetail(key, value);
			}
		}
	}

	public static class FlashState {
		private static int FLASH_FIRED_MASK = 1;
		private static int FLASH_RETURN_MASK = 2 | 4;
		private static int FLASH_MODE_MASK = 8 | 16;
		private static int FLASH_FUNCTION_MASK = 32;
		private static int FLASH_RED_EYE_MASK = 64;
		private final int mState;

		public FlashState(final int state) {
			mState = state;
		}

		public boolean isFlashFired() {
			return (mState & FLASH_FIRED_MASK) != 0;
		}
	}
}
