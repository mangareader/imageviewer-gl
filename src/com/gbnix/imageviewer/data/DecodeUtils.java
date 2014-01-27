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

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.BitmapRegionDecoder;
import android.util.FloatMath;

import com.gbnix.imageviewer.common.BitmapUtils;
import com.gbnix.imageviewer.common.Utils;
import com.gbnix.imageviewer.util.ThreadPool.CancelListener;
import com.gbnix.imageviewer.util.ThreadPool.JobContext;

public class DecodeUtils {
	private static final String TAG = "DecodeService";

	public static BitmapRegionDecoder createBitmapRegionDecoder(final JobContext jc, final byte[] bytes,
			final int offset, final int length, final boolean shareable) {
		if (offset < 0 || length <= 0 || offset + length > bytes.length)
			throw new IllegalArgumentException(String.format("offset = %s, length = %s, bytes = %s", offset, length,
					bytes.length));

		try {
			return BitmapRegionDecoder.newInstance(bytes, offset, length, shareable);
		} catch (final Throwable t) {
			Log.w(TAG, t);
			return null;
		}
	}

	public static BitmapRegionDecoder createBitmapRegionDecoder(final JobContext jc, final FileDescriptor fd,
			final boolean shareable) {
		try {
			return BitmapRegionDecoder.newInstance(fd, shareable);
		} catch (final Throwable t) {
			Log.w(TAG, t);
			return null;
		}
	}

	public static BitmapRegionDecoder createBitmapRegionDecoder(final JobContext jc, final InputStream is,
			final boolean shareable) {
		try {
			return BitmapRegionDecoder.newInstance(is, shareable);
		} catch (final Throwable t) {
			// We often cancel the creating of bitmap region decoder,
			// so just log one line.
			Log.w(TAG, "requestCreateBitmapRegionDecoder: " + t);
			return null;
		}
	}

	public static BitmapRegionDecoder createBitmapRegionDecoder(final JobContext jc, final String filePath,
			final boolean shareable) {
		try {
			return BitmapRegionDecoder.newInstance(filePath, shareable);
		} catch (final Throwable t) {
			Log.w(TAG, t);
			return null;
		}
	}

	public static Bitmap decode(final JobContext jc, final byte[] bytes, final int offset, final int length,
			Options options) {
		if (options == null) {
			options = new Options();
		}
		jc.setCancelListener(new DecodeCanceller(options));
		return ensureGLCompatibleBitmap(BitmapFactory.decodeByteArray(bytes, offset, length, options));
	}

	public static Bitmap decode(final JobContext jc, final byte[] bytes, final Options options) {
		return decode(jc, bytes, 0, bytes.length, options);
	}

	public static Bitmap decode(final JobContext jc, final FileDescriptor fd, Options options) {
		if (options == null) {
			options = new Options();
		}
		jc.setCancelListener(new DecodeCanceller(options));
		return ensureGLCompatibleBitmap(BitmapFactory.decodeFileDescriptor(fd, null, options));
	}

	public static void decodeBounds(final JobContext jc, final byte[] bytes, final int offset, final int length,
			final Options options) {
		Utils.assertTrue(options != null);
		options.inJustDecodeBounds = true;
		jc.setCancelListener(new DecodeCanceller(options));
		BitmapFactory.decodeByteArray(bytes, offset, length, options);
		options.inJustDecodeBounds = false;
	}

	public static void decodeBounds(final JobContext jc, final FileDescriptor fd, final Options options) {
		Utils.assertTrue(options != null);
		options.inJustDecodeBounds = true;
		jc.setCancelListener(new DecodeCanceller(options));
		BitmapFactory.decodeFileDescriptor(fd, null, options);
		options.inJustDecodeBounds = false;
	}

	/**
	 * Decodes the bitmap from the given byte array if the image size is larger
	 * than the given requirement.
	 * 
	 * Note: The returned image may be resized down. However, both width and
	 * height must be larger than the <code>targetSize</code>.
	 */
	public static Bitmap decodeIfBigEnough(final JobContext jc, final byte[] data, Options options, final int targetSize) {
		if (options == null) {
			options = new Options();
		}
		jc.setCancelListener(new DecodeCanceller(options));

		options.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(data, 0, data.length, options);
		if (jc.isCancelled()) return null;
		if (options.outWidth < targetSize || options.outHeight < targetSize) return null;
		options.inSampleSize = BitmapUtils.computeSampleSizeLarger(options.outWidth, options.outHeight, targetSize);
		options.inJustDecodeBounds = false;
		return ensureGLCompatibleBitmap(BitmapFactory.decodeByteArray(data, 0, data.length, options));
	}

	public static Bitmap decodeThumbnail(final JobContext jc, final FileDescriptor fd, Options options,
			final int targetSize, final int type) {
		if (options == null) {
			options = new Options();
		}
		jc.setCancelListener(new DecodeCanceller(options));

		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFileDescriptor(fd, null, options);
		if (jc.isCancelled()) return null;

		final int w = options.outWidth;
		final int h = options.outHeight;

		if (type == MediaItem.TYPE_MICROTHUMBNAIL) {
			// We center-crop the original image as it's micro thumbnail. In
			// this case,
			// we want to make sure the shorter side >= "targetSize".
			final float scale = (float) targetSize / Math.min(w, h);
			options.inSampleSize = BitmapUtils.computeSampleSizeLarger(scale);

			// For an extremely wide image, e.g. 300x30000, we may got OOM when
			// decoding
			// it for TYPE_MICROTHUMBNAIL. So we add a max number of pixels
			// limit here.
			final int MAX_PIXEL_COUNT = 640000; // 400 x 1600
			if (w / options.inSampleSize * (h / options.inSampleSize) > MAX_PIXEL_COUNT) {
				options.inSampleSize = BitmapUtils.computeSampleSize(FloatMath.sqrt((float) MAX_PIXEL_COUNT / (w * h)));
			}
		} else {
			// For screen nail, we only want to keep the longer side >=
			// targetSize.
			final float scale = (float) targetSize / Math.max(w, h);
			options.inSampleSize = BitmapUtils.computeSampleSizeLarger(scale);
		}

		options.inJustDecodeBounds = false;

		Bitmap result = BitmapFactory.decodeFileDescriptor(fd, null, options);
		if (result == null) return null;

		// We need to resize down if the decoder does not support inSampleSize
		// (For example, GIF images)
		final float scale = (float) targetSize
				/ (type == MediaItem.TYPE_MICROTHUMBNAIL ? Math.min(result.getWidth(), result.getHeight()) : Math.max(
						result.getWidth(), result.getHeight()));

		if (scale <= 0.5) {
			result = BitmapUtils.resizeBitmapByScale(result, scale, true);
		}
		return ensureGLCompatibleBitmap(result);
	}

	public static Bitmap decodeThumbnail(final JobContext jc, final String filePath, final Options options,
			final int targetSize, final int type) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(filePath);
			final FileDescriptor fd = fis.getFD();
			return decodeThumbnail(jc, fd, options, targetSize, type);
		} catch (final Exception ex) {
			Log.w(TAG, ex);
			return null;
		} finally {
			Utils.closeSilently(fis);
		}
	}

	// TODO: This function should not be called directly from
	// DecodeUtils.requestDecode(...), since we don't have the knowledge
	// if the bitmap will be uploaded to GL.
	public static Bitmap ensureGLCompatibleBitmap(final Bitmap bitmap) {
		if (bitmap == null || bitmap.getConfig() != null) return bitmap;
		final Bitmap newBitmap = bitmap.copy(Config.ARGB_8888, false);
		bitmap.recycle();
		return newBitmap;
	}

	private static class DecodeCanceller implements CancelListener {
		Options mOptions;

		public DecodeCanceller(final Options options) {
			mOptions = options;
		}

		@Override
		public void onCancel() {
			mOptions.requestCancelDecode();
		}
	}
}
