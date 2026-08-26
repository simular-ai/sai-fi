/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// NonStreamView.swift
//
// Default screen to show getting started tips after app connection
// Initiates streaming
//

import MWDATCore
import SwiftUI

private let updateRequiredBackgroundColor = Color(red: 1.0, green: 0.957, blue: 0.839)
private let updateRequiredForegroundColor = Color(red: 0.541, green: 0.294, blue: 0.0)
private let updateRequiredTitle = "Update required"
private let waitingForActiveDeviceText = "Waiting for an active device"

struct NonStreamView: View {
  var viewModel: StreamSessionViewModel
  @Bindable var wearablesVM: WearablesViewModel
  @State private var sheetHeight: CGFloat = 300
  @State private var showSettingsMenu: Bool = false

  private var isUpdateRequired: Bool {
    wearablesVM.requiresFirmwareUpdate || viewModel.requiresDATAppUpdate
  }

  var body: some View {
    ZStack {
      Color.black.edgesIgnoringSafeArea(.all)

      // Dismiss overlay when tapping outside the settings menu (placed first so it's behind content)
      if showSettingsMenu {
        Color.clear
          .contentShape(Rectangle())
          .onTapGesture {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
              showSettingsMenu = false
            }
          }
          .edgesIgnoringSafeArea(.all)
      }

      VStack {
        HStack {
          Spacer()
          Button {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
              showSettingsMenu.toggle()
            }
          } label: {
            Image(systemName: "gearshape")
              .resizable()
              .aspectRatio(contentMode: .fit)
              .foregroundStyle(.white)
              .frame(width: 24, height: 24)
          }
          .overlay(alignment: .trailing) {
            if showSettingsMenu {
              CustomButton(
                title: "Disconnect",
                style: .destructive,
                isDisabled: wearablesVM.registrationState != .registered
              ) {
                wearablesVM.disconnectGlasses()
                withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                  showSettingsMenu = false
                }
              }
              .frame(width: 120)
              .transition(.scale(scale: 0.01, anchor: .trailing).combined(with: .opacity))
            }
          }
        }

        Spacer()

        VStack(spacing: 12) {
          Image(.cameraAccessIcon)
            .resizable()
            .renderingMode(.template)
            .foregroundStyle(.white)
            .aspectRatio(contentMode: .fit)
            .frame(width: 120)

          Text("Stream Your Glasses Camera")
            .font(.system(size: 20, weight: .semibold))
            .foregroundStyle(.white)

          Text("Tap the Start streaming button to stream video from your glasses or use the camera button to take a photo from your glasses.")
            .font(.system(size: 15))
            .multilineTextAlignment(.center)
            .foregroundStyle(.white)
        }
        .padding(.horizontal, 12)

        Spacer()

        VStack(spacing: 12) {
          HStack(spacing: 8) {
            Image(systemName: "hourglass")
              .resizable()
              .aspectRatio(contentMode: .fit)
              .foregroundStyle(Color.white.opacity(0.7))
              .frame(width: 16, height: 16)

            Text(waitingForActiveDeviceText)
              .font(.system(size: 14))
              .foregroundStyle(Color.white.opacity(0.7))
          }
          .opacity(viewModel.hasActiveDevice ? 0 : 1)

          if isUpdateRequired {
            UpdateRequiredMessage(
              showFirmwareUpdate: wearablesVM.requiresFirmwareUpdate,
              showDATAppUpdate: viewModel.requiresDATAppUpdate
            )
          }

          if wearablesVM.requiresFirmwareUpdate {
            CustomButton(
              title: "Update firmware",
              style: .primary,
              isDisabled: false
            ) {
              Task {
                await wearablesVM.openFirmwareUpdate()
              }
            }
          }

          if viewModel.requiresDATAppUpdate {
            CustomButton(
              title: "Update app on glasses",
              style: .primary,
              isDisabled: false
            ) {
              Task {
                await wearablesVM.openDATGlassesAppUpdate()
              }
            }
          }

          CustomButton(
            title: "Start streaming",
            style: .primary,
            isDisabled: !viewModel.hasActiveDevice || isUpdateRequired
          ) {
            Task {
              await viewModel.handleStartStreaming()
            }
          }
        }
      }
      .padding(.all, 24)
    }
    .sheet(isPresented: $wearablesVM.showGettingStartedSheet) {
      GettingStartedSheetView(height: $sheetHeight)
        .presentationDetents([.height(sheetHeight)])
        .presentationDragIndicator(.visible)
    }
  }
}

struct UpdateRequiredMessage: View {
  let showFirmwareUpdate: Bool
  let showDATAppUpdate: Bool

  private var message: String {
    if showFirmwareUpdate && showDATAppUpdate {
      return "Your glasses firmware and app need updates before Camera Access can start."
    }
    if showFirmwareUpdate {
      return "Your glasses firmware needs an update before Camera Access can start."
    }
    return "The app on your glasses needs an update before Camera Access can start."
  }

  var body: some View {
    HStack(alignment: .top, spacing: 12) {
      Image(systemName: "exclamationmark.triangle.fill")
        .resizable()
        .aspectRatio(contentMode: .fit)
        .foregroundStyle(updateRequiredForegroundColor)
        .frame(width: 24, height: 24)
        .accessibilityHidden(true)

      VStack(alignment: .leading, spacing: 4) {
        Text(updateRequiredTitle)
          .font(.system(size: 16, weight: .semibold))
          .foregroundStyle(updateRequiredForegroundColor)

        Text(message)
          .font(.system(size: 15))
          .foregroundStyle(updateRequiredForegroundColor)
          .fixedSize(horizontal: false, vertical: true)
      }

      Spacer(minLength: 0)
    }
    .padding(.all, 16)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(updateRequiredBackgroundColor)
    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
  }
}

struct GettingStartedSheetView: View {
  @Environment(\.dismiss) var dismiss
  @Binding var height: CGFloat

  var body: some View {
    VStack(spacing: 24) {
      Text("Getting started")
        .font(.system(size: 18, weight: .semibold))
        .foregroundStyle(.primary)

      VStack(spacing: 12) {
        TipItemView(
          resource: .videoIcon,
          text: "First, Camera Access needs permission to use your glasses camera."
        )
        TipItemView(
          resource: .tapIcon,
          text: "Capture photos by tapping the camera button."
        )
        TipItemView(
          resource: .smartGlassesIcon,
          text: "The capture LED lets others know when you're capturing content or going live."
        )
      }
      .padding(.bottom, 16)

      CustomButton(
        title: "Continue",
        style: .primary,
        isDisabled: false
      ) {
        dismiss()
      }
    }
    .padding(.all, 24)
    .background(
      GeometryReader { geo -> Color in
        DispatchQueue.main.async {
          height = geo.size.height
        }
        return Color.clear
      }
    )
  }
}

struct TipItemView: View {
  let resource: ImageResource
  let text: String

  var body: some View {
    HStack(alignment: .top, spacing: 12) {
      Image(resource)
        .resizable()
        .renderingMode(.template)
        .foregroundStyle(.primary)
        .aspectRatio(contentMode: .fit)
        .frame(width: 24)
        .padding(.leading, 4)
        .padding(.top, 4)

      Text(text)
        .font(.system(size: 15))
        .foregroundStyle(.primary)
        .fixedSize(horizontal: false, vertical: true)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
  }
}
