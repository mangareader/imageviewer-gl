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

package com.gbnix.imageviewer.app;

import java.util.Stack;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Menu;
import android.view.MenuItem;

import com.gbnix.imageviewer.common.Utils;

public class StateManager {
	@SuppressWarnings("unused")
	private static final String TAG = "StateManager";
	private boolean mIsResumed = false;

	private static final String KEY_MAIN = "activity-state";
	private static final String KEY_DATA = "data";
	private static final String KEY_STATE = "bundle";
	private static final String KEY_CLASS = "class";

	private final GalleryActivity mContext;
	private final Stack<StateEntry> mStack = new Stack<StateEntry>();
	private ActivityState.ResultEntry mResult;

	public StateManager(final GalleryActivity context) {
		mContext = context;
	}

	public boolean createOptionsMenu(final Menu menu) {
		if (mStack.isEmpty())
			return false;
		else
			return getTopState().onCreateActionBar(menu);
	}

	public void destroy() {
		Log.v(TAG, "destroy");
		while (!mStack.isEmpty()) {
			mStack.pop().activityState.onDestroy();
		}
		mStack.clear();
	}

	public int getStateCount() {
		return mStack.size();
	}

	public ActivityState getTopState() {
		Utils.assertTrue(!mStack.isEmpty());
		return mStack.peek().activityState;
	}

	public boolean hasStateClass(final Class<? extends ActivityState> klass) {
		for (final StateEntry entry : mStack) {
			if (klass.isInstance(entry.activityState)) return true;
		}
		return false;
	}

	public boolean itemSelected(final MenuItem item) {
		if (!mStack.isEmpty()) {
			if (getTopState().onItemSelected(item)) return true;
			if (item.getItemId() == android.R.id.home) {
				if (mStack.size() > 1) {
					getTopState().onBackPressed();
				}
				return true;
			}
		}
		return false;
	}

	public void notifyActivityResult(final int requestCode, final int resultCode, final Intent data) {
		getTopState().onStateResult(requestCode, resultCode, data);
	}

	public void onBackPressed() {
		if (!mStack.isEmpty()) {
			getTopState().onBackPressed();
		}
	}

	public void onConfigurationChange(final Configuration config) {
		for (final StateEntry entry : mStack) {
			entry.activityState.onConfigurationChanged(config);
		}
	}

	public void pause() {
		if (!mIsResumed) return;
		mIsResumed = false;
		if (!mStack.isEmpty()) {
			getTopState().onPause();
		}
	}

	@SuppressWarnings("unchecked")
	public void restoreFromState(final Bundle inState) {
		Log.v(TAG, "restoreFromState");
		final Parcelable list[] = inState.getParcelableArray(KEY_MAIN);
		for (final Parcelable parcelable : list) {
			final Bundle bundle = (Bundle) parcelable;
			final Class<? extends ActivityState> klass = (Class<? extends ActivityState>) bundle
					.getSerializable(KEY_CLASS);

			final Bundle data = bundle.getBundle(KEY_DATA);
			final Bundle state = bundle.getBundle(KEY_STATE);

			ActivityState activityState;
			try {
				Log.v(TAG, "restoreFromState " + klass);
				activityState = klass.newInstance();
			} catch (final Exception e) {
				throw new AssertionError(e);
			}
			activityState.initialize(mContext, data);
			activityState.onCreate(data, state);
			mStack.push(new StateEntry(data, activityState));
		}
	}

	public void resume() {
		if (mIsResumed) return;
		mIsResumed = true;
		if (!mStack.isEmpty()) {
			getTopState().resume();
		}
	}

	public void saveState(final Bundle outState) {
		Log.v(TAG, "saveState");

		final Parcelable list[] = new Parcelable[mStack.size()];
		int i = 0;
		for (final StateEntry entry : mStack) {
			final Bundle bundle = new Bundle();
			bundle.putSerializable(KEY_CLASS, entry.activityState.getClass());
			bundle.putBundle(KEY_DATA, entry.data);
			final Bundle state = new Bundle();
			entry.activityState.onSaveState(state);
			bundle.putBundle(KEY_STATE, state);
			Log.v(TAG, "saveState " + entry.activityState.getClass());
			list[i++] = bundle;
		}
		outState.putParcelableArray(KEY_MAIN, list);
	}

	public void startState(final Class<? extends ActivityState> klass, final Bundle data) {
		Log.v(TAG, "startState " + klass);
		ActivityState state = null;
		try {
			state = klass.newInstance();
		} catch (final Exception e) {
			throw new AssertionError(e);
		}
		if (!mStack.isEmpty()) {
			final ActivityState top = getTopState();
			if (mIsResumed) {
				top.onPause();
			}
		}
		state.initialize(mContext, data);

		mStack.push(new StateEntry(data, state));
		state.onCreate(data, null);
		if (mIsResumed) {
			state.resume();
		}
	}

	public void startStateForResult(final Class<? extends ActivityState> klass, final int requestCode, final Bundle data) {
		Log.v(TAG, "startStateForResult " + klass + ", " + requestCode);
		ActivityState state = null;
		try {
			state = klass.newInstance();
		} catch (final Exception e) {
			throw new AssertionError(e);
		}
		state.initialize(mContext, data);
		state.mResult = new ActivityState.ResultEntry();
		state.mResult.requestCode = requestCode;

		if (!mStack.isEmpty()) {
			final ActivityState as = getTopState();
			as.mReceivedResults = state.mResult;
			if (mIsResumed) {
				as.onPause();
			}
		} else {
			mResult = state.mResult;
		}

		mStack.push(new StateEntry(data, state));
		state.onCreate(data, null);
		if (mIsResumed) {
			state.resume();
		}
	}

	void finishState(final ActivityState state) {
		// The finish() request could be rejected (only happens under Monkey),
		// If it is rejected, we won't close the last page.
		if (mStack.size() == 1) {
			final Activity activity = (Activity) mContext.getAndroidContext();
			if (mResult != null) {
				activity.setResult(mResult.resultCode, mResult.resultData);
			}
			activity.finish();
			if (!activity.isFinishing()) {
				Log.w(TAG, "finish is rejected, keep the last state");
				return;
			}
			Log.v(TAG, "no more state, finish activity");
		}

		Log.v(TAG, "finishState " + state);
		if (state != mStack.peek().activityState) {
			if (state.isDestroyed()) {
				Log.d(TAG, "The state is already destroyed");
				return;
			} else
				throw new IllegalArgumentException("The stateview to be finished" + " is not at the top of the stack: "
						+ state + ", " + mStack.peek().activityState);
		}

		// Remove the top state.
		mStack.pop();
		state.mIsFinishing = true;
		if (mIsResumed) {
			state.onPause();
		}
		mContext.getGLRoot().setContentPane(null);
		state.onDestroy();

		if (!mStack.isEmpty()) {
			// Restore the immediately previous state
			final ActivityState top = mStack.peek().activityState;
			if (mIsResumed) {
				top.resume();
			}
		}
	}

	void switchState(final ActivityState oldState, final Class<? extends ActivityState> klass, final Bundle data) {
		Log.v(TAG, "switchState " + oldState + ", " + klass);
		if (oldState != mStack.peek().activityState)
			throw new IllegalArgumentException("The stateview to be finished" + " is not at the top of the stack: "
					+ oldState + ", " + mStack.peek().activityState);
		// Remove the top state.
		mStack.pop();
		if (mIsResumed) {
			oldState.onPause();
		}
		oldState.onDestroy();

		// Create new state.
		ActivityState state = null;
		try {
			state = klass.newInstance();
		} catch (final Exception e) {
			throw new AssertionError(e);
		}
		state.initialize(mContext, data);
		mStack.push(new StateEntry(data, state));
		state.onCreate(data, null);
		if (mIsResumed) {
			state.resume();
		}
	}

	private static class StateEntry {
		public Bundle data;
		public ActivityState activityState;

		public StateEntry(final Bundle data, final ActivityState state) {
			this.data = data;
			activityState = state;
		}
	}
}
