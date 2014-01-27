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

import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;

public abstract class SurfaceTextureScreenNail implements ScreenNail, SurfaceTexture.OnFrameAvailableListener {
	protected ExtTexture mExtTexture;
	private SurfaceTexture mSurfaceTexture;
	private int mWidth, mHeight;
	private final float[] mTransform = new float[16];
	private boolean mHasTexture = false;

	public SurfaceTextureScreenNail() {
	}

	public void acquireSurfaceTexture() {
		mExtTexture = new ExtTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
		mExtTexture.setSize(mWidth, mHeight);
		mSurfaceTexture = new SurfaceTexture(mExtTexture.getId());
		mSurfaceTexture.setDefaultBufferSize(mWidth, mHeight);
		mSurfaceTexture.setOnFrameAvailableListener(this);
		synchronized (this) {
			mHasTexture = true;
		}
	}

	@Override
	public void draw(final GLCanvas canvas, final int x, final int y, final int width, final int height) {
		synchronized (this) {
			if (!mHasTexture) return;
			mSurfaceTexture.updateTexImage();
			mSurfaceTexture.getTransformMatrix(mTransform);

			// Flip vertically.
			canvas.save(GLCanvas.SAVE_FLAG_MATRIX);
			final int cx = x + width / 2;
			final int cy = y + height / 2;
			canvas.translate(cx, cy);
			canvas.scale(1, -1, 1);
			canvas.translate(-cx, -cy);
			canvas.drawTexture(mExtTexture, mTransform, x, y, width, height);
			canvas.restore();
		}
	}

	@Override
	public void draw(final GLCanvas canvas, final RectF source, final RectF dest) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getHeight() {
		return mHeight;
	}

	public SurfaceTexture getSurfaceTexture() {
		return mSurfaceTexture;
	}

	@Override
	public int getWidth() {
		return mWidth;
	}

	@Override
	abstract public void noDraw();

	@Override
	abstract public void onFrameAvailable(SurfaceTexture surfaceTexture);

	@Override
	abstract public void recycle();

	public void releaseSurfaceTexture() {
		synchronized (this) {
			mHasTexture = false;
		}
		mExtTexture.recycle();
		mExtTexture = null;
		mSurfaceTexture.release();
		mSurfaceTexture = null;
	}

	public void setSize(final int width, final int height) {
		mWidth = width;
		mHeight = height;
	}
}
