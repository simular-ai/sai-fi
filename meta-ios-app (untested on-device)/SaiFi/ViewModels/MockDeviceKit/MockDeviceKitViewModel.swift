/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// MockDeviceKitViewModel.swift
//
// View model for managing mock devices during development and testing of DAT SDK features.
// Mock devices simulate real Meta wearable device behavior, allowing developers to test
// streaming, photo capture, and device management workflows without physical hardware.
//

#if DEBUG

import Foundation
import MWDATMockDevice
import Observation

extension MockDeviceKitView {
  @Observable
  @MainActor
  class ViewModel {
    private let mockDeviceKit: MockDeviceKitInterface
    var cardViewModels: [MockDeviceCardView.ViewModel] = []
    var isEnabled: Bool

    init(mockDeviceKit: MockDeviceKitInterface) {
      self.mockDeviceKit = mockDeviceKit
      self.isEnabled = mockDeviceKit.isEnabled
      self.cardViewModels = mockDeviceKit.pairedDevices.compactMap { $0 as? MockGlasses }.map { MockDeviceCardView.ViewModel(device: $0) }
    }

    func enable() {
      mockDeviceKit.enable(
        config: MockDeviceKitConfig(initiallyRegistered: true, initialPermissionsGranted: true)
      )
      isEnabled = true
    }

    func disable() {
      mockDeviceKit.disable()
      cardViewModels = []
      isEnabled = false
    }

    /// One tap for the Simulator: fake registration, pair Ray-Ban Meta, wear them, plant the
    /// bundled plant.mp4 / plant.png so capture has a still. No Bluetooth involved.
    func setupSimulatorGlasses() {
      if !isEnabled { enable() }
      if cardViewModels.isEmpty { pairGlasses() }
      guard let card = cardViewModels.last else { return }
      card.powerOn()
      card.unfold()
      card.don()
      if let video = Bundle.main.url(forResource: "plant", withExtension: "mp4") {
        card.selectVideo(from: video)
      }
      if let image = Bundle.main.url(forResource: "plant", withExtension: "png") {
        card.selectImage(from: image)
      }
    }

    // Add a new mock Ray-Ban Meta device
    func pairGlasses() {
      let mockDevice: MockGlasses
      do {
        mockDevice = try mockDeviceKit.pairGlasses(model: .rayBanMeta)
      } catch {
        print("Failed to pair mock glasses: \(error)")
        return
      }
      cardViewModels.append(MockDeviceCardView.ViewModel(device: mockDevice))
    }

    func unpairDevice(_ device: MockDevice) {
      if let idx = cardViewModels.firstIndex(where: { $0.id == device.deviceIdentifier }) {
        cardViewModels.remove(at: idx)
        mockDeviceKit.unpairDevice(device)
      }
    }
  }
}

#endif
