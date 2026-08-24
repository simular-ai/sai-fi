/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import MWDATCamera
import MWDATCore
import Observation
import SwiftUI

enum StreamingStatus {
  case streaming
  case waiting
  case stopped
}

/// ViewModel for video streaming UI. Delegates device management to DeviceSessionManager.
@Observable
@MainActor
final class StreamSessionViewModel {
  // MARK: - State

  var currentVideoFrame: UIImage?
  var hasReceivedFirstFrame: Bool = false
  var streamingStatus: StreamingStatus = .stopped
  var showError: Bool = false
  var errorMessage: String = ""
  var requiresDATAppUpdate: Bool = false

  var capturedPhoto: UIImage?
  var showPhotoPreview: Bool = false
  var showPhotoCaptureError: Bool = false
  var isCapturingPhoto: Bool = false

  var hasActiveDevice: Bool { sessionManager.hasActiveDevice }
  var isDeviceSessionReady: Bool { sessionManager.isReady }

  var isStreaming: Bool { streamingStatus != .stopped }

  // MARK: - Private

  private let sessionManager: DeviceSessionManager
  private let wearables: WearablesInterface
  private var stream: MWDATCamera.Stream?

  private var stateListenerToken: AnyListenerToken?
  private var videoFrameListenerToken: AnyListenerToken?
  private var errorListenerToken: AnyListenerToken?
  private var photoDataListenerToken: AnyListenerToken?

  // MARK: - Init

  init(wearables: WearablesInterface) {
    self.wearables = wearables
    self.sessionManager = DeviceSessionManager(wearables: wearables)
  }

  // MARK: - Public API

  func handleStartStreaming() async {
    let permission = Permission.camera
    do {
      var status = try await wearables.checkPermissionStatus(permission)
      if status != .granted {
        status = try await wearables.requestPermission(permission)
      }
      guard status == .granted else {
        showError("Permission denied")
        return
      }
      await startSession()
    } catch {
      // Use `localizedDescription` for user-facing text — `description` is
      // always English and intended for logs.
      showError("Permission error: \(error.localizedDescription)")
    }
  }

  func stopSession() {
    stream?.stop()
  }

  /// Stops both the stream and the underlying device session. Call in test tearDown.
  func endSession() {
    stream = nil
    clearListeners()
    streamingStatus = .stopped
    currentVideoFrame = nil
    hasReceivedFirstFrame = false
    sessionManager.cleanup()
  }

  func capturePhoto() {
    guard !isCapturingPhoto, streamingStatus == .streaming else {
      showPhotoCaptureError = true
      return
    }
    isCapturingPhoto = true
    let success = stream?.capturePhoto(format: .jpeg) ?? false
    if !success {
      isCapturingPhoto = false
      showPhotoCaptureError = true
    }
  }

  func dismissError() {
    showError = false
    errorMessage = ""
  }

  func dismissPhotoCaptureError() {
    showPhotoCaptureError = false
  }

  func dismissPhotoPreview() {
    showPhotoPreview = false
    capturedPhoto = nil
  }

  // MARK: - Private

  private func startSession() async {
    let deviceSession: DeviceSession
    do {
      deviceSession = try await sessionManager.getSession()
      requiresDATAppUpdate = false
    } catch DeviceSessionError.datAppOnTheGlassesUpdateRequired {
      requiresDATAppUpdate = true
      showError(DeviceSessionError.datAppOnTheGlassesUpdateRequired.localizedDescription)
      return
    } catch {
      showError("Failed to start session: \(error.localizedDescription)")
      return
    }

    guard deviceSession.state == .started else {
      showError("Device session is not ready. Please try again.")
      return
    }

    let config = StreamConfiguration(
      videoCodec: VideoCodec.raw,
      resolution: StreamingResolution.low,
      frameRate: 24
    )

    do {
      guard let newStream = try deviceSession.addStream(config: config) else {
        showError("Unable to create stream. Please try again.")
        return
      }
      stream = newStream
      streamingStatus = .waiting
      setupListeners(for: newStream)
      newStream.start()
    } catch {
      showError("Failed to start stream: \(error.localizedDescription)")
    }
  }

  private func setupListeners(for stream: MWDATCamera.Stream) {
    stateListenerToken = stream.statePublisher.listen { [weak self] state in
      Task { @MainActor in self?.handleStateChange(state) }
    }

    videoFrameListenerToken = stream.videoFramePublisher.listen { [weak self] frame in
      Task { @MainActor in self?.handleVideoFrame(frame) }
    }

    errorListenerToken = stream.errorPublisher.listen { [weak self] error in
      Task { @MainActor in self?.handleError(error) }
    }

    photoDataListenerToken = stream.photoDataPublisher.listen { [weak self] data in
      Task { @MainActor in self?.handlePhotoData(data) }
    }
  }

  private func clearListeners() {
    stateListenerToken = nil
    videoFrameListenerToken = nil
    errorListenerToken = nil
    photoDataListenerToken = nil
  }

  private func handleStateChange(_ state: StreamState) {
    switch state {
    case .stopped:
      currentVideoFrame = nil
      streamingStatus = .stopped
      stream = nil
      clearListeners()
      hasReceivedFirstFrame = false
      sessionManager.stopCurrentSession()
    case .waitingForDevice, .starting, .stopping, .paused:
      streamingStatus = .waiting
    case .streaming:
      streamingStatus = .streaming
    }
  }

  private func handleVideoFrame(_ frame: VideoFrame) {
    if let image = frame.makeUIImage() {
      currentVideoFrame = image
      if !hasReceivedFirstFrame {
        hasReceivedFirstFrame = true
      }
    }
  }

  private func handleError(_ error: StreamError) {
    let message = error.localizedDescription
    if message != errorMessage {
      showError(message)
    }
  }

  private func handlePhotoData(_ data: PhotoData) {
    isCapturingPhoto = false
    if let image = UIImage(data: data.data) {
      capturedPhoto = image
      showPhotoPreview = true
    }
  }

  private func showError(_ message: String) {
    errorMessage = message
    showError = true
  }
}
