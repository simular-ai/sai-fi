/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// MockDeviceViewModel.swift
//
// View model for individual mock devices used in development and testing of DAT SDK features.
// This controls mock device behaviors like power states, physical states (folded/unfolded),
// and media content (camera feeds and captured images).
//

#if DEBUG

import AVFoundation
import Foundation
import MWDATMockDevice
import Observation
import UIKit

extension MockDeviceCardView {
  @Observable
  @MainActor
  final class ViewModel {
    let device: MockGlasses
    var hasCameraFeed: Bool = false
    var hasCapturedImage: Bool = false
    var cameraSource: CameraFacing?
    var isPoweredOn: Bool = false
    var isDonned: Bool = false
    var isUnfolded: Bool = false
    var showCameraPermissionAlert: Bool = false

    init(device: MockGlasses, hasCameraFeed: Bool = false, hasCapturedImage: Bool = false) {
      self.device = device
      self.hasCameraFeed = hasCameraFeed
      self.hasCapturedImage = hasCapturedImage
    }

    var id: String { device.deviceIdentifier }

    // Display name for the mock device in the UI
    var deviceName: String { "Mock Glasses" }

    func powerOn() {
      device.powerOn()
      isPoweredOn = true
    }

    func powerOff() {
      device.powerOff()
      isPoweredOn = false
      isDonned = false
      isUnfolded = false
    }

    func don() {
      device.don()
      isDonned = true
      isUnfolded = true
    }

    func doff() {
      device.doff()
      isDonned = false
    }

    func unfold() {
      device.unfold()
      isUnfolded = true
    }

    func fold() {
      device.fold()
      isUnfolded = false
      isDonned = false
    }

    func captouchTap() {
      device.services.captouch.tap()
    }

    func captouchTapAndHold() {
      device.services.captouch.tapAndHold()
    }

    func setCameraFeed(_ facing: CameraFacing) {
      Task {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        if status == .denied || status == .restricted {
          self.showCameraPermissionAlert = true
          return
        }
        let granted = await AVCaptureDevice.requestAccess(for: .video)
        guard granted else {
          self.showCameraPermissionAlert = true
          return
        }
        await device.services.camera.setCameraFeed(cameraFacing: facing)
        self.cameraSource = facing
        self.hasCameraFeed = false
      }
    }

    func openSettings() {
      if let url = URL(string: UIApplication.openSettingsURLString) {
        UIApplication.shared.open(url)
      }
    }

    func selectVideo(from url: URL) {
      device.services.camera.setCameraFeed(fileURL: url)
      hasCameraFeed = true
      cameraSource = nil
    }

    func selectImage(from url: URL) {
      device.services.camera.setCapturedImage(fileURL: url)
      hasCapturedImage = true
    }
  }
}

#endif
