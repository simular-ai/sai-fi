/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// DebugMenuViewModel.swift
//
// Debug-only view model that provides access to mock devices for development and testing.
// This enables developers to test DAT SDK streaming functionality without physical Meta
// wearable devices. Mock devices simulate the behavior of real devices, allowing for
// comprehensive testing of streaming, photo capture, and error handling workflows.
//

#if DEBUG

import MWDATMockDevice
import Observation
import SwiftUI

@Observable
@MainActor
class DebugMenuViewModel {
  public var showDebugMenu: Bool
  public var mockDeviceKitViewModel: MockDeviceKitView.ViewModel

  init(mockDeviceKit: MockDeviceKitInterface) {
    self.mockDeviceKitViewModel = MockDeviceKitView.ViewModel(mockDeviceKit: mockDeviceKit)
    self.showDebugMenu = false
  }
}

#endif
