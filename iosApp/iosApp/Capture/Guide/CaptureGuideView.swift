import GarupaKit
import SwiftUI
import UIKit

struct CaptureGuideView: View {
    let app: AppModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.openURL) private var openURL
    @State private var hostCopied = false

    var body: some View {
        Group {
            if app.stage.isIndeterminateProgress {
                activity(app.profiles.refreshingCatalog ? "正在刷新卡牌数据" : app.stage.title)
                    .frame(maxWidth: .infinity)
            } else {
                VStack(alignment: .leading, spacing: 20) {
                    Text(app.stage.title)
                        .font(.title2.bold())
                        .accessibilityAddTraits(.isHeader)
                    actions
                }
            }
        }
        .controlSize(.large)
        .task(id: hostCopied) {
            guard hostCopied else { return }
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            hostCopied = false
        }
    }

    @ViewBuilder
    private var actions: some View {
        switch app.stage {
        case .welcome, .startFailed:
            if app.stage == .welcome {
                Text(app.stage.description).foregroundStyle(.secondary)
            }
            if let error = app.sessionError { errorText(error) }
            if app.locationNeedsSettings {
                Text("需允许定位，以便切换到游戏后维持后台代理。")
                Text("若定位服务已关闭，请前往设置 → 隐私与安全性 → 定位服务开启。")
                    .foregroundStyle(.secondary)
                appSettingsButton
                Button(action: app.startSession) {
                    Text("重新开始").frame(minHeight: 44)
                }
                .buttonStyle(.borderless)
                .disabled(!app.canStart)
            } else {
                Button(action: app.startSession) {
                    Text(app.stage == .welcome ? "开始获取" : "重新开始")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!app.canStart)
            }
        case .certificate:
            Button(action: app.certificates.installProfile) {
                Label(app.certificates.profileProvided ? "重新下载描述文件" : "下载描述文件",
                      systemImage: "square.and.arrow.down")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!app.certificates.canInstall || app.busy)
            VStack(alignment: .leading, spacing: 20) {
                certificateStep("安装描述文件", number: 1,
                                path: "设置 → 通用 → VPN 与设备管理 → Garupa Export")
                certificateStep("开启完全信任", number: 2,
                                path: "设置 → 通用 → 关于本机 → 证书信任设置 → Garupa Export Local CA")
            }
            if case .failed(let error) = app.certificates.state {
                errorText(error)
                Button {
                    app.recheck(forceProxyCheck: true)
                } label: {
                    Text("重新检测证书").frame(minHeight: 44)
                }
                .buttonStyle(.borderless)
                .disabled(app.busy)
            }
            if let error = app.certificates.installationError { errorText(error) }
        case .configureProxy:
            Text(app.stage.description).foregroundStyle(.secondary)
            if app.capture.environment?.wifi == false { Text("请先连接 Wi-Fi。") }
            VStack(alignment: .leading, spacing: 8) {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("服务器")
                        serverAddress
                    }
                } else {
                    LabeledContent("服务器") {
                        serverAddress
                    }
                }
                LabeledContent("端口", value: String(LocalProxy.port)).textSelection(.enabled)
            }
            if let error = app.capture.verificationError {
                Text(error).foregroundStyle(.secondary)
                Button {
                    app.recheck(forceProxyCheck: true)
                } label: {
                    Text("重新检测连接").frame(minHeight: 44)
                }
                .buttonStyle(.borderless)
                .disabled(app.capture.verifying)
            }
        case .waitingForGame:
            Text(app.stage.description).foregroundStyle(.secondary)
            if let error = app.capture.error { errorText(error) }
            if !app.notifications.enabled { Text("通知未开启，请返回应用查看结果。").foregroundStyle(.secondary) }
        case .receiving:
            Text(app.stage.description).foregroundStyle(.secondary)
            receivingProgress
        case .backgroundRequired:
            Text(app.stage.description).foregroundStyle(.secondary)
            if let error = app.sessionError { errorText(error) }
            if app.locationNeedsSettings {
                Text("若定位服务已关闭，请前往设置 → 隐私与安全性 → 定位服务开启。")
                    .foregroundStyle(.secondary)
                appSettingsButton
            } else {
                Button(action: app.resumeBackground) {
                    Text("恢复后台运行").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(app.busy)
            }
        case .preparing, .startingProxy, .verifyingProxy, .generating, .stopping, .complete, .conversionFailed:
            EmptyView()
        }
    }

    private var serverAddress: some View {
        HStack(spacing: 8) {
            Text("127.0.0.1")
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            Button {
                UIPasteboard.general.string = "127.0.0.1"
                hostCopied = true
            } label: {
                Image(systemName: hostCopied ? "checkmark" : "doc.on.doc")
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(hostCopied ? "服务器地址已复制" : "复制服务器地址")
        }
    }

    @ViewBuilder
    private var receivingProgress: some View {
        if let progress = app.capture.progress {
            if let total = progress.totalBytes {
                ProgressView(value: Double(min(progress.receivedBytes, total)), total: Double(total)) {
                    Text("已接收 \(size(progress.receivedBytes)) / \(size(total))")
                        .font(.subheadline)
                }
            } else {
                activity("已接收 \(size(progress.receivedBytes))")
            }
        } else { ProgressView() }
    }

    private func activity(_ title: String) -> some View {
        HStack(spacing: 12) {
            ProgressView()
                .controlSize(.regular)
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
        }
        .foregroundStyle(.secondary)
    }

    private func certificateStep(_ title: LocalizedStringKey, number: Int, path: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Image(systemName: "\(number).circle")
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.headline)
                Text(path).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var appSettingsButton: some View {
        Button {
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
        } label: {
            Text("打开本应用设置").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
    }

    private func size(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }

    private func errorText(_ message: String) -> some View {
        Text(message).foregroundStyle(.red).textSelection(.enabled)
    }
}
