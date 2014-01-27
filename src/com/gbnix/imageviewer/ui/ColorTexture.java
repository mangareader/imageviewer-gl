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

import com.gbnix.imageviewer.common.Utils;

// ColorTexture is a texture which fills the rectangle with the specified color.
public class ColorTexture implements Texture {

	private final int mColor;
	private int mWidth;
	private int mHeight;

	public ColorTexture(final int color) {
		mColor = color;
		mWidth = 1;
		mHeight = 1;
	}

	@Override
	public void draw(final GLCanvas canvas, final int x, final int y) {
		draw(canvas, x, y, mWidth, mHeight);
	}

	@Override
	public void draw(final GLCanvas canvas, final int x, final int y, final int w, final int h) {
		canvas.fillRect(x, y, w, h, mColor);
	}

	@Override
	public int getHeight() {
		return mHeight;
	}

	@Override
	public int getWidth() {
		return mWidth;
	}

	@Override
	public boolean isOpaque() {
		return Utils.isOpaque(mColor);
	}

	public void setSize(final int width, final int height) {
		mWidth = width;
		mHeight = height;
	}
}
