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

import android.graphics.Rect;
import android.opengl.Matrix;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.gbnix.imageviewer.common.Utils;

// This class follows the structure of frameworks's EdgeEffect class.
class EdgeAnimation {

	private static final int STATE_IDLE = 0;
	private static final int STATE_PULL = 1;
	private static final int STATE_ABSORB = 2;
	private static final int STATE_RELEASE = 3;

	// Time it will take the effect to fully done in ms
	private static final int ABSORB_TIME = 200;
	private static final int RELEASE_TIME = 500;

	private static final float VELOCITY_FACTOR = 0.1f;

	private final Interpolator mInterpolator;

	private int mState;
	private float mValue;

	private float mValueStart;
	private float mValueFinish;
	private long mStartTime;
	private long mDuration;

	public EdgeAnimation() {
		mInterpolator = new DecelerateInterpolator();
		mState = STATE_IDLE;
	}

	public float getValue() {
		return mValue;
	}

	public void onAbsorb(final float velocity) {
		final float finish = Utils.clamp(mValue + velocity * VELOCITY_FACTOR, -1.0f, 1.0f);
		startAnimation(mValue, finish, ABSORB_TIME, STATE_ABSORB);
	}

	// The deltaDistance's magnitude is in the range of -1 (no change) to 1.
	// The value 1 is the full length of the view. Negative values means the
	// movement is in the opposite direction.
	public void onPull(final float deltaDistance) {
		if (mState == STATE_ABSORB) return;
		mValue = Utils.clamp(mValue + deltaDistance, -1.0f, 1.0f);
		mState = STATE_PULL;
	}

	public void onRelease() {
		if (mState == STATE_IDLE || mState == STATE_ABSORB) return;
		startAnimation(mValue, 0, RELEASE_TIME, STATE_RELEASE);
	}

	public boolean update() {
		if (mState == STATE_IDLE) return false;
		if (mState == STATE_PULL) return true;

		final float t = Utils.clamp((float) (now() - mStartTime) / mDuration, 0.0f, 1.0f);
		/* Use linear interpolation for absorb, quadratic for others */
		final float interp = mState == STATE_ABSORB ? t : mInterpolator.getInterpolation(t);

		mValue = mValueStart + (mValueFinish - mValueStart) * interp;

		if (t >= 1.0f) {
			switch (mState) {
				case STATE_ABSORB:
					startAnimation(mValue, 0, RELEASE_TIME, STATE_RELEASE);
					break;
				case STATE_RELEASE:
					mState = STATE_IDLE;
					break;
			}
		}

		return true;
	}

	private long now() {
		return AnimationTime.get();
	}

	private void startAnimation(final float start, final float finish, final long duration, final int newState) {
		mValueStart = start;
		mValueFinish = finish;
		mDuration = duration;
		mStartTime = now();
		mState = newState;
	}
}

// This class does the overscroll effect.
class Paper {
	private static final String TAG = "Paper";
	private static final int ROTATE_FACTOR = 4;
	private final EdgeAnimation mAnimationLeft = new EdgeAnimation();
	private final EdgeAnimation mAnimationRight = new EdgeAnimation();
	private int mWidth, mHeight;
	private final float[] mMatrix = new float[16];

	public boolean advanceAnimation() {
		// Note that we use "|" because we want both animations get updated.
		return mAnimationLeft.update() | mAnimationRight.update();
	}

	public void edgeReached(float velocity) {
		velocity /= mWidth; // make it relative to width
		if (velocity < 0) {
			mAnimationRight.onAbsorb(-velocity);
		} else {
			mAnimationLeft.onAbsorb(velocity);
		}
	}

	public float[] getTransform(final Rect rect, final float scrollX) {
		final float left = mAnimationLeft.getValue();
		final float right = mAnimationRight.getValue();
		final float screenX = rect.centerX() - scrollX;
		// We linearly interpolate the value [left, right] for the screenX
		// range int [-1/4, 5/4]*mWidth. So if part of the thumbnail is outside
		// the screen, we still get some transform.
		final float x = screenX + mWidth / 4;
		final int range = 3 * mWidth / 2;
		final float t = ((range - x) * left - x * right) / range;
		// compress t to the range (-1, 1) by the function
		// f(t) = (1 / (1 + e^-t) - 0.5) * 2
		// then multiply by 90 to make the range (-45, 45)
		final float degrees = (1 / (1 + (float) Math.exp(-t * ROTATE_FACTOR)) - 0.5f) * 2 * -45;
		Matrix.setIdentityM(mMatrix, 0);
		Matrix.translateM(mMatrix, 0, mMatrix, 0, rect.centerX(), rect.centerY(), 0);
		Matrix.rotateM(mMatrix, 0, degrees, 0, 1, 0);
		Matrix.translateM(mMatrix, 0, mMatrix, 0, -rect.width() / 2, -rect.height() / 2, 0);
		return mMatrix;
	}

	public void onRelease() {
		mAnimationLeft.onRelease();
		mAnimationRight.onRelease();
	}

	public void overScroll(float distance) {
		distance /= mWidth; // make it relative to width
		if (distance < 0) {
			mAnimationLeft.onPull(-distance);
		} else {
			mAnimationRight.onPull(distance);
		}
	}

	public void setSize(final int width, final int height) {
		mWidth = width;
		mHeight = height;
	}
}
