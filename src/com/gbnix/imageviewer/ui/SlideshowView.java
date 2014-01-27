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

package com.gbnix.imageviewer.ui;

import java.util.Random;

import javax.microedition.khronos.opengles.GL11;

import android.graphics.Bitmap;
import android.graphics.PointF;

import com.gbnix.imageviewer.anim.CanvasAnimation;
import com.gbnix.imageviewer.anim.FloatAnimation;

public class SlideshowView extends GLView {
	@SuppressWarnings("unused")
	private static final String TAG = "SlideshowView";

	private static final int SLIDESHOW_DURATION = 3500;
	private static final int TRANSITION_DURATION = 1000;

	private static final float SCALE_SPEED = 0.20f;
	private static final float MOVE_SPEED = SCALE_SPEED;

	private int mCurrentRotation;
	private BitmapTexture mCurrentTexture;
	private SlideshowAnimation mCurrentAnimation;

	private int mPrevRotation;
	private BitmapTexture mPrevTexture;
	private SlideshowAnimation mPrevAnimation;

	private final FloatAnimation mTransitionAnimation = new FloatAnimation(0, 1, TRANSITION_DURATION);

	private final Random mRandom = new Random();

	public void next(final Bitmap bitmap, final int rotation) {

		mTransitionAnimation.start();

		if (mPrevTexture != null) {
			mPrevTexture.getBitmap().recycle();
			mPrevTexture.recycle();
		}

		mPrevTexture = mCurrentTexture;
		mPrevAnimation = mCurrentAnimation;
		mPrevRotation = mCurrentRotation;

		mCurrentRotation = rotation;
		mCurrentTexture = new BitmapTexture(bitmap);
		if ((rotation / 90 & 0x01) == 0) {
			mCurrentAnimation = new SlideshowAnimation(mCurrentTexture.getWidth(), mCurrentTexture.getHeight(), mRandom);
		} else {
			mCurrentAnimation = new SlideshowAnimation(mCurrentTexture.getHeight(), mCurrentTexture.getWidth(), mRandom);
		}
		mCurrentAnimation.start();

		invalidate();
	}

	public void release() {
		if (mPrevTexture != null) {
			mPrevTexture.recycle();
			mPrevTexture = null;
		}
		if (mCurrentTexture != null) {
			mCurrentTexture.recycle();
			mCurrentTexture = null;
		}
	}

	@Override
	protected void render(final GLCanvas canvas) {
		final long animTime = AnimationTime.get();
		boolean requestRender = mTransitionAnimation.calculate(animTime);
		final GL11 gl = canvas.getGLInstance();
		gl.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
		final float alpha = mPrevTexture == null ? 1f : mTransitionAnimation.get();

		if (mPrevTexture != null && alpha != 1f) {
			requestRender |= mPrevAnimation.calculate(animTime);
			canvas.save(GLCanvas.SAVE_FLAG_ALPHA | GLCanvas.SAVE_FLAG_MATRIX);
			canvas.setAlpha(1f - alpha);
			mPrevAnimation.apply(canvas);
			canvas.rotate(mPrevRotation, 0, 0, 1);
			mPrevTexture.draw(canvas, -mPrevTexture.getWidth() / 2, -mPrevTexture.getHeight() / 2);
			canvas.restore();
		}
		if (mCurrentTexture != null) {
			requestRender |= mCurrentAnimation.calculate(animTime);
			canvas.save(GLCanvas.SAVE_FLAG_ALPHA | GLCanvas.SAVE_FLAG_MATRIX);
			canvas.setAlpha(alpha);
			mCurrentAnimation.apply(canvas);
			canvas.rotate(mCurrentRotation, 0, 0, 1);
			mCurrentTexture.draw(canvas, -mCurrentTexture.getWidth() / 2, -mCurrentTexture.getHeight() / 2);
			canvas.restore();
		}
		if (requestRender) {
			invalidate();
		}
		gl.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
	}

	private class SlideshowAnimation extends CanvasAnimation {
		private final int mWidth;
		private final int mHeight;

		private final PointF mMovingVector;
		private float mProgress;

		public SlideshowAnimation(final int width, final int height, final Random random) {
			mWidth = width;
			mHeight = height;
			mMovingVector = new PointF(MOVE_SPEED * mWidth * (random.nextFloat() - 0.5f), MOVE_SPEED * mHeight
					* (random.nextFloat() - 0.5f));
			setDuration(SLIDESHOW_DURATION);
		}

		@Override
		public void apply(final GLCanvas canvas) {
			final int viewWidth = getWidth();
			final int viewHeight = getHeight();

			final float initScale = Math.min(2f, Math.min((float) viewWidth / mWidth, (float) viewHeight / mHeight));
			final float scale = initScale * (1 + SCALE_SPEED * mProgress);

			final float centerX = viewWidth / 2 + mMovingVector.x * mProgress;
			final float centerY = viewHeight / 2 + mMovingVector.y * mProgress;

			canvas.translate(centerX, centerY);
			canvas.scale(scale, scale, 0);
		}

		@Override
		public int getCanvasSaveFlags() {
			return GLCanvas.SAVE_FLAG_MATRIX;
		}

		@Override
		protected void onCalculate(final float progress) {
			mProgress = progress;
		}
	}
}
