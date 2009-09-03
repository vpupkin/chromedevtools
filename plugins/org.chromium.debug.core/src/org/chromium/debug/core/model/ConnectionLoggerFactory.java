// Copyright (c) 2009 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.debug.core.model;

import org.chromium.sdk.ConnectionLogger;

/**
 * The factory provides {@link ConnectionLogger} that can be used to output connection
 * traffic to UI.
 */
public interface ConnectionLoggerFactory {
  ConnectionLogger createLogger(String title);
}