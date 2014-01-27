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

import java.util.ArrayList;
import java.util.HashMap;

public class PathMatcher {
	public static final int NOT_FOUND = -1;

	private final ArrayList<String> mVariables = new ArrayList<String>();
	private Node mRoot = new Node();

	public PathMatcher() {
		mRoot = new Node();
	}

	public void add(final String pattern, final int kind) {
		final String[] segments = Path.split(pattern);
		Node current = mRoot;
		for (final String segment : segments) {
			current = current.addChild(segment);
		}
		current.setKind(kind);
	}

	public int getIntVar(final int index) {
		return Integer.parseInt(mVariables.get(index));
	}

	public long getLongVar(final int index) {
		return Long.parseLong(mVariables.get(index));
	}

	public String getVar(final int index) {
		return mVariables.get(index);
	}

	public int match(final Path path) {
		final String[] segments = path.split();
		mVariables.clear();
		Node current = mRoot;
		for (final String segment : segments) {
			Node next = current.getChild(segment);
			if (next == null) {
				next = current.getChild("*");
				if (next != null) {
					mVariables.add(segment);
				} else
					return NOT_FOUND;
			}
			current = next;
		}
		return current.getKind();
	}

	private static class Node {
		private HashMap<String, Node> mMap;
		private int mKind = NOT_FOUND;

		Node addChild(final String segment) {
			if (mMap == null) {
				mMap = new HashMap<String, Node>();
			} else {
				final Node node = mMap.get(segment);
				if (node != null) return node;
			}

			final Node n = new Node();
			mMap.put(segment, n);
			return n;
		}

		Node getChild(final String segment) {
			if (mMap == null) return null;
			return mMap.get(segment);
		}

		int getKind() {
			return mKind;
		}

		void setKind(final int kind) {
			mKind = kind;
		}
	}
}
