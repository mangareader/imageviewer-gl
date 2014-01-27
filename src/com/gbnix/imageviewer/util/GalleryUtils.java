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

import java.util.Arrays;
import java.util.Locale;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ConditionVariable;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.gbnix.imageviewer.R;
import com.gbnix.imageviewer.data.DataManager;
import com.gbnix.imageviewer.data.MediaItem;
import com.gbnix.imageviewer.util.ThreadPool.CancelListener;
import com.gbnix.imageviewer.util.ThreadPool.JobContext;

public class GalleryUtils {
	private static final String TAG = "GalleryUtils";
	private static final String MAPS_PACKAGE_NAME = "com.google.android.apps.maps";
	private static final String MAPS_CLASS_NAME = "com.google.android.maps.MapsActivity";

	private static final String MIME_TYPE_IMAGE = "image/*";
	private static final String MIME_TYPE_ALL = "*/*";
	private static final String DIR_TYPE_IMAGE = "vnd.android.cursor.dir/image";

	private static float sPixelDensity = -1f;

	private static volatile Thread sCurrentThread;

	private static volatile boolean sWarned;

	private static final double RAD_PER_DEG = Math.PI / 180.0;

	private static final double EARTH_RADIUS_METERS = 6367000.0;

	public static double accurateDistanceMeters(final double lat1, final double lng1, final double lat2,
			final double lng2) {
		final double dlat = Math.sin(0.5 * (lat2 - lat1));
		final double dlng = Math.sin(0.5 * (lng2 - lng1));
		final double x = dlat * dlat + dlng * dlng * Math.cos(lat1) * Math.cos(lat2);
		return 2 * Math.atan2(Math.sqrt(x), Math.sqrt(Math.max(0.0, 1.0 - x))) * EARTH_RADIUS_METERS;
	}

	// Below are used the detect using database in the render thread. It only
	// works most of the time, but that's ok because it's for debugging only.

	public static void assertNotInRenderThread() {
		if (!sWarned) {
			if (Thread.currentThread() == sCurrentThread) {
				sWarned = true;
				Log.w(TAG, new Throwable("Should not do this in render thread"));
			}
		}
	}

	public static int determineTypeBits(final Context context, final Intent intent) {
		int typeBits = 0;
		final String type = intent.resolveType(context);

		if (MIME_TYPE_ALL.equals(type)) {
			typeBits = DataManager.INCLUDE_ALL;
		} else if (MIME_TYPE_IMAGE.equals(type) || DIR_TYPE_IMAGE.equals(type)) {
			typeBits = DataManager.INCLUDE_IMAGE;
		} else {
			typeBits = DataManager.INCLUDE_ALL;
		}

		if (intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)) {
			typeBits |= DataManager.INCLUDE_LOCAL_ONLY;
		}

		return typeBits;
	}

	public static float dpToPixel(final float dp) {
		return sPixelDensity * dp;
	}

	public static int dpToPixel(final int dp) {
		return Math.round(dpToPixel((float) dp));
	}

	// For debugging, it will block the caller for timeout millis.
	public static void fakeBusy(final JobContext jc, final int timeout) {
		final ConditionVariable cv = new ConditionVariable();
		jc.setCancelListener(new CancelListener() {
			@Override
			public void onCancel() {
				cv.open();
			}
		});
		cv.block(timeout);
		jc.setCancelListener(null);
	}

	public static double fastDistanceMeters(final double latRad1, final double lngRad1, final double latRad2,
			final double lngRad2) {
		if (Math.abs(latRad1 - latRad2) > RAD_PER_DEG || Math.abs(lngRad1 - lngRad2) > RAD_PER_DEG)
			return accurateDistanceMeters(latRad1, lngRad1, latRad2, lngRad2);
		// Approximate sin(x) = x.
		final double sineLat = latRad1 - latRad2;

		// Approximate sin(x) = x.
		final double sineLng = lngRad1 - lngRad2;

		// Approximate cos(lat1) * cos(lat2) using
		// cos((lat1 + lat2)/2) ^ 2
		double cosTerms = Math.cos((latRad1 + latRad2) / 2.0);
		cosTerms = cosTerms * cosTerms;
		double trigTerm = sineLat * sineLat + cosTerms * sineLng * sineLng;
		trigTerm = Math.sqrt(trigTerm);

		// Approximate arcsin(x) = x
		return EARTH_RADIUS_METERS * trigTerm;
	}

	// Returns a (localized) string for the given duration (in seconds).
	public static String formatDuration(final Context context, final int duration) {
		final int h = duration / 3600;
		final int m = (duration - h * 3600) / 60;
		final int s = duration - (h * 3600 + m * 60);
		String durationValue;
		if (h == 0) {
			durationValue = String.format(context.getString(R.string.details_ms), m, s);
		} else {
			durationValue = String.format(context.getString(R.string.details_hms), h, m, s);
		}
		return durationValue;
	}

	public static String formatLatitudeLongitude(final String format, final double latitude, final double longitude) {
		// We need to specify the locale otherwise it may go wrong in some
		// language
		// (e.g. Locale.FRENCH)
		return String.format(Locale.ENGLISH, format, latitude, longitude);
	}

	public static int getBucketId(final String path) {
		return path.toLowerCase().hashCode();
	}

	public static byte[] getBytes(final String in) {
		final byte[] result = new byte[in.length() * 2];
		int output = 0;
		for (final char ch : in.toCharArray()) {
			result[output++] = (byte) (ch & 0xFF);
			result[output++] = (byte) (ch >> 8);
		}
		return result;
	}

	public static int getSelectionModePrompt(final int typeBits) {
		return R.string.select_image;
	}

	public static boolean hasSpaceForSize(final long size) {
		final String state = Environment.getExternalStorageState();
		if (!Environment.MEDIA_MOUNTED.equals(state)) return false;

		final String path = Environment.getExternalStorageDirectory().getPath();
		try {
			final StatFs stat = new StatFs(path);
			return stat.getAvailableBlocks() * (long) stat.getBlockSize() > size;
		} catch (final Exception e) {
			Log.i(TAG, "Fail to access external storage", e);
		}
		return false;
	}

	public static void initialize(final Context context) {
		if (sPixelDensity < 0) {
			final DisplayMetrics metrics = new DisplayMetrics();
			final WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
			wm.getDefaultDisplay().getMetrics(metrics);
			sPixelDensity = metrics.density;
		}
	}

	public static boolean isPanorama(final MediaItem item) {
		if (item == null) return false;
		final int w = item.getWidth();
		final int h = item.getHeight();
		return h > 0 && w / h >= 2;
	}

	public static boolean isValidLocation(final double latitude, final double longitude) {
		// TODO: change || to && after we fix the default location issue
		return latitude != MediaItem.INVALID_LATLNG || longitude != MediaItem.INVALID_LATLNG;
	}

	public static int meterToPixel(final float meter) {
		// 1 meter = 39.37 inches, 1 inch = 160 dp.
		return Math.round(dpToPixel(meter * 39.37f * 160));
	}

	public static void setRenderThread() {
		sCurrentThread = Thread.currentThread();
	}

	public static void setViewPointMatrix(final float matrix[], final float x, final float y, final float z) {
		// The matrix is
		// -z, 0, x, 0
		// 0, -z, y, 0
		// 0, 0, 1, 0
		// 0, 0, 1, -z
		Arrays.fill(matrix, 0, 16, 0);
		matrix[0] = matrix[5] = matrix[15] = -z;
		matrix[8] = x;
		matrix[9] = y;
		matrix[10] = matrix[11] = 1;
	}

	public static void showOnMap(final Context context, final double latitude, final double longitude) {
		try {
			// We don't use "geo:latitude,longitude" because it only centers
			// the MapView to the specified location, but we need a marker
			// for further operations (routing to/from).
			// The q=(lat, lng) syntax is suggested by geo-team.
			final String uri = formatLatitudeLongitude("http://maps.google.com/maps?f=q&q=(%f,%f)", latitude, longitude);
			final ComponentName compName = new ComponentName(MAPS_PACKAGE_NAME, MAPS_CLASS_NAME);
			final Intent mapsIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setComponent(compName);
			context.startActivity(mapsIntent);
		} catch (final ActivityNotFoundException e) {
			// Use the "geo intent" if no GMM is installed
			Log.e(TAG, "GMM activity not found!", e);
			final String url = formatLatitudeLongitude("geo:%f,%f", latitude, longitude);
			final Intent mapsIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			context.startActivity(mapsIntent);
		}
	}

	public static final double toMile(final double meter) {
		return meter / 1609;
	}
}
