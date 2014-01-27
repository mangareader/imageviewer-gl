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

package com.gbnix.imageviewer.ui;

import android.graphics.Bitmap;
import android.graphics.RectF;

import com.gbnix.imageviewer.common.Utils;
import com.gbnix.imageviewer.data.MediaItem;

// This is a ScreenNail wraps a Bitmap. There are some extra functions:
//
// - If we need to draw before the bitmap is available, we draw a rectange of
// placeholder color (gray).
//
// - When the the bitmap is available, and we have drawn the placeholder color
// before, we will do a fade-in animation.
public class BitmapScreenNail implements ScreenNail {
	private static final int PLACEHOLDER_COLOR = 0xFF222222;
	// The duration of the fading animation in milliseconds
	private static final int DURATION = 180;

	private static final int MAX_SIDE = 640;

	// These are special values for mAnimationStartTime
	private static final long ANIMATION_NOT_NEEDED = -1;
	private static final long ANIMATION_NEEDED = -2;
	private static final long ANIMATION_DONE = -3;

	private int mWidth;
	private int mHeight;
	private Bitmap mBitmap;
	private BitmapTexture mTexture;
	private long mAnimationStartTime = ANIMATION_NOT_NEEDED;

	public BitmapScreenNail(final Bitmap bitmap) {
		mWidth = bitmap.getWidth();
		mHeight = bitmap.getHeight();
		mBitmap = bitmap;
		// We create mTexture lazily, so we don't incur the cost if we don't
		// actually need it.
	}

	public BitmapScreenNail(final int width, final int height) {
		setSize(width, height);
	}

	// Combines the two ScreenNails.
	// Returns the used one and recycle the unused one.
	public ScreenNail combine(final ScreenNail other) {
		if (other == null) return this;

		if (!(other instanceof BitmapScreenNail)) {
			recycle();
			return other;
		}

		// Now both are BitmapScreenNail. Move over the information about width,
		// height, and Bitmap, then recycle the other.
		final BitmapScreenNail newer = (BitmapScreenNail) other;
		mWidth = newer.mWidth;
		mHeight = newer.mHeight;
		if (newer.mBitmap != null) {
			if (mBitmap != null) {
				MediaItem.getThumbPool().recycle(mBitmap);
			}
			mBitmap = newer.mBitmap;
			newer.mBitmap = null;

			if (mTexture != null) {
				mTexture.recycle();
				mTexture = null;
			}
		}

		newer.recycle();
		return this;
	}

	@Override
	public void draw(final GLCanvas canvas, final int x, final int y, final int width, final int height) {
		if (mBitmap == null) {
			if (mAnimationStartTime == ANIMATION_NOT_NEEDED) {
				mAnimationStartTime = ANIMATION_NEEDED;
			}
			canvas.fillRect(x, y, width, height, PLACEHOLDER_COLOR);
			return;
		}

		if (mTexture == null) {
			mTexture = new BitmapTexture(mBitmap);
		}

		if (mAnimationStartTime == ANIMATION_NEEDED) {
			mAnimationStartTime = now();
		}

		if (isAnimating()) {
			canvas.drawMixed(mTexture, PLACEHOLDER_COLOR, getRatio(), x, y, width, height);
		} else {
			mTexture.draw(canvas, x, y, width, height);
		}
	}

	@Override
	public void draw(final GLCanvas canvas, final RectF source, final RectF dest) {
		if (mBitmap == null) {
			canvas.fillRect(dest.left, dest.top, dest.width(), dest.height(), PLACEHOLDER_COLOR);
			return;
		}

		if (mTexture == null) {
			mTexture = new BitmapTexture(mBitmap);
		}

		canvas.drawTexture(mTexture, source, dest);
	}

	@Override
	public int getHeight() {
		return mHeight;
	}

	@Override
	public int getWidth() {
		return mWidth;
	}

	public boolean isAnimating() {
		if (mAnimationStartTime < 0) return false;
		if (now() - mAnimationStartTime >= DURATION) {
			mAnimationStartTime = ANIMATION_DONE;
			return false;
		}
		return true;
	}

	@Override
	public boolean isReady() {
		return mBitmap != null && !mBitmap.isRecycled();
	}

	public boolean isShowingPlaceholder() {
		return mBitmap == null || isAnimating();
	}

	@Override
	public void noDraw() {
	}

	@Override
	public void recycle() {
		if (mTexture != null) {
			mTexture.recycle();
			mTexture = null;
		}
		if (mBitmap != null) {
			MediaItem.getThumbPool().recycle(mBitmap);
			mBitmap = null;
		}
	}

	public void updatePlaceholderSize(final int width, final int height) {
		if (mBitmap != null) return;
		if (width == 0 || height == 0) return;
		setSize(width, height);
	}

	private float getRatio() {
		final float r = (float) (now() - mAnimationStartTime) / DURATION;
		return Utils.clamp(1.0f - r, 0.0f, 1.0f);
	}

	private void setSize(int width, int height) {
		if (width == 0 || height == 0) {
			width = 640;
			height = 480;
		}
		final float scale = Math.min(1, (float) MAX_SIDE / Math.max(width, height));
		mWidth = Math.round(scale * width);
		mHeight = Math.round(scale * height);
	}

	private static long now() {
		return AnimationTime.get();
	}
}
