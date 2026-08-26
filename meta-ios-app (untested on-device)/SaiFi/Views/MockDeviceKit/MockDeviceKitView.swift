/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// MockDeviceKitView.swift
//
// Debug-only interface for managing mock Meta wearable devices during development.
// This view allows developers to create, configure, and test with simulated devices
// without requiring physical Meta hardware.
//

#if DEBUG

import Foundation
import SwiftUI

struct MockDeviceKitView: View {
  var viewModel: ViewModel
  var onGlassesChanged: () -> Void = {}

  var body: some View {
    NavigationView {
      ScrollView {
        VStack(spacing: 12) {
          CardView {
            VStack(spacing: 6) {
              HStack {
                Text("Mock Device Kit")
                  .font(.headline)
                  .fontWeight(.bold)
                  .foregroundStyle(.primary)
                Spacer()

                if viewModel.isEnabled {
                  Text("\(viewModel.cardViewModels.count) device(s) paired")
                    .font(.subheadline)
                    .foregroundStyle(.green)
                }
              }

              Text(
                "Simulator has no Bluetooth. This kit is the glasses: Enable (or Set up), "
                  + "then Home can register/capture against the mock. Audio stays on the Mac mic."
              )
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)

              Divider()

              if viewModel.isEnabled {
                MockDeviceKitButton("Disable MockDeviceKit", style: .destructive) {
                  viewModel.disable()
                  onGlassesChanged()
                }

                MockDeviceKitButton("Set up Simulator glasses") {
                  viewModel.setupSimulatorGlasses()
                  onGlassesChanged()
                }

                MockDeviceKitButton("Pair RayBan Meta", disabled: viewModel.cardViewModels.count >= 3) {
                  viewModel.pairGlasses()
                  onGlassesChanged()
                }
              } else {
                MockDeviceKitButton("Enable MockDeviceKit") {
                  viewModel.enable()
                  onGlassesChanged()
                }
                MockDeviceKitButton("Set up Simulator glasses") {
                  viewModel.setupSimulatorGlasses()
                  onGlassesChanged()
                }
              }
            }
            .padding(12)
          }

          if viewModel.isEnabled {
            ForEach(viewModel.cardViewModels, id: \.id) { cardViewModel in
              MockDeviceCardView(
                viewModel: cardViewModel,
                onUnpairDevice: {
                  viewModel.unpairDevice(cardViewModel.device)
                  onGlassesChanged()
                }
              )
            }
          }

          Spacer()
        }
        .padding()
      }
      .background(Color(.systemGroupedBackground))
    }
  }
}

#endif
