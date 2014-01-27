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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

// MultiLineTexture is a texture shows the content of a specified String.
//
// To create a MultiLineTexture, use the newInstance() method and specify
// the String, the font size, and the color.
class MultiLineTexture extends CanvasTexture {
	private final Layout mLayout;

	private MultiLineTexture(final Layout layout) {
		super(layout.getWidth(), layout.getHeight());
		mLayout = layout;
	}

	@Override
	protected void onDraw(final Canvas canvas, final Bitmap backing) {
		mLayout.draw(canvas);
	}

	public static MultiLineTexture newInstance(final String text, final int maxWidth, final float textSize,
			final int color, final Layout.Alignment alignment) {
		final TextPaint paint = StringTexture.getDefaultPaint(textSize, color);
		final Layout layout = new StaticLayout(text, 0, text.length(), paint, maxWidth, alignment, 1, 0, true, null, 0);

		return new MultiLineTexture(layout);
	}
}
