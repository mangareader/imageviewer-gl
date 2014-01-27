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

import android.content.Context;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import com.gbnix.imageviewer.common.Utils;

public class ScrollerHelper {
	private final OverScroller mScroller;
	private final int mOverflingDistance;
	private boolean mOverflingEnabled;

	public ScrollerHelper(final Context context) {
		mScroller = new OverScroller(context);
		final ViewConfiguration configuration = ViewConfiguration.get(context);
		mOverflingDistance = configuration.getScaledOverflingDistance();
	}

	/**
	 * Call this when you want to know the new location. The position will be
	 * updated and can be obtained by getPosition(). Returns true if the
	 * animation is not yet finished.
	 */
	public boolean advanceAnimation(final long currentTimeMillis) {
		return mScroller.computeScrollOffset();
	}

	public void fling(final int velocity, final int min, final int max) {
		final int currX = getPosition();
		mScroller.fling(currX, 0, // startX, startY
				velocity, 0, // velocityX, velocityY
				min, max, // minX, maxX
				0, 0, // minY, maxY
				mOverflingEnabled ? mOverflingDistance : 0, 0);
	}

	public void forceFinished() {
		mScroller.forceFinished(true);
	}

	public float getCurrVelocity() {
		return mScroller.getCurrVelocity();
	}

	public int getPosition() {
		return mScroller.getCurrX();
	}

	public boolean isFinished() {
		return mScroller.isFinished();
	}

	public void setOverfling(final boolean enabled) {
		mOverflingEnabled = enabled;
	}

	public void setPosition(final int position) {
		mScroller.startScroll(position, 0, // startX, startY
				0, 0, 0); // dx, dy, duration

		// This forces the scroller to reach the final position.
		mScroller.abortAnimation();
	}

	// Returns the distance that over the scroll limit.
	public int startScroll(final int distance, final int min, final int max) {
		final int currPosition = mScroller.getCurrX();
		final int finalPosition = mScroller.isFinished() ? currPosition : mScroller.getFinalX();
		final int newPosition = Utils.clamp(finalPosition + distance, min, max);
		if (newPosition != currPosition) {
			mScroller.startScroll(currPosition, 0, // startX, startY
					newPosition - currPosition, 0, 0); // dx, dy, duration
		}
		return finalPosition + distance - newPosition;
	}
}
