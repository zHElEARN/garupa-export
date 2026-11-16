import GarupaKit
import SwiftUI
import UIKit

struct CaptureGuideSection: View {
    let app: AppModel
    @Environment(\.openURL) private var openURL
    @State private var hostCopied = false

    var body: some View {
        Section {
            switch app.stage {
            case .preparing, .startingProxy, .verifyingProxy, .generating, .stopping:
                ProgressView(app.profiles.refreshingCatalog ? "正在刷新卡牌数据" : app.stage.title)
            default:
                Text(app.stage.title).font(.headline).accessibilityAddTraits(.isHeader)
                if !app.stage.description.isEmpty { Text(app.stage.description) }
            }
            actions
        }
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
            if let error = app.sessionError { errorText(error) }
            if app.locationNeedsSettings {
                Text("需允许定位，以便切换到游戏后维持后台代理。")
                Text("若定位服务已关闭，请前往设置 → 隐私与安全性 → 定位服务开启。")
                    .foregroundStyle(.secondary)
                appSettingsButton
                Button("重新开始", action: app.startSession).disabled(!app.canStart)
            } else {
                Button(app.stage == .welcome ? "开始获取" : "重新开始", action: app.startSession)
                    .buttonStyle(.borderedProminent)
                    .disabled(!app.canStart)
            }
        case .certificate:
            LabeledContent("证书", value: app.certificates.state.title)
            Button(app.certificates.profileProvided ? "重新下载描述文件" : "下载描述文件", action: app.certificates.installProfile)
                .buttonStyle(.borderedProminent)
                .disabled(!app.certificates.canInstall || app.busy)
            LabeledContent("安装描述文件") {
                Text("设置 → 通用 → VPN 与设备管理 → Garupa Export")
            }
            LabeledContent("开启完全信任") {
                Text("设置 → 通用 → 关于本机 → 证书信任设置 → Garupa Export Local CA")
            }
            if case .failed(let error) = app.certificates.state {
                errorText(error)
                Button("重新检测证书") { app.recheck(forceProxyCheck: true) }.disabled(app.busy)
            }
            if let error = app.certificates.installationError { errorText(error) }
        case .configureProxy:
            if app.capture.environment?.wifi == false { Text("请先连接 Wi-Fi。") }
            LabeledContent("服务器") {
                HStack {
                    Text("127.0.0.1").textSelection(.enabled)
                    Button {
                        UIPasteboard.general.string = "127.0.0.1"
                        hostCopied = true
                    } label: {
                        Image(systemName: hostCopied ? "checkmark" : "doc.on.doc")
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(hostCopied ? "服务器地址已复制" : "复制服务器地址")
                }
            }
            LabeledContent("端口", value: String(LocalProxy.port)).textSelection(.enabled)
            if let error = app.capture.verificationError {
                Text(error).foregroundStyle(.secondary)
                Button("重新检测连接") { app.recheck(forceProxyCheck: true) }
                    .disabled(app.capture.verifying)
            }
        case .waitingForGame:
            if let error = app.capture.error { errorText(error) }
            if !app.notifications.enabled { Text("通知未开启，请返回应用查看结果。").foregroundStyle(.secondary) }
        case .receiving:
            receivingProgress
        case .backgroundRequired:
            if let error = app.sessionError { errorText(error) }
            if app.locationNeedsSettings {
                Text("若定位服务已关闭，请前往设置 → 隐私与安全性 → 定位服务开启。")
                    .foregroundStyle(.secondary)
                appSettingsButton
            } else {
                Button("恢复后台运行", action: app.resumeBackground)
                    .buttonStyle(.borderedProminent).disabled(app.busy)
            }
        case .preparing, .startingProxy, .verifyingProxy, .generating, .stopping, .complete, .conversionFailed:
            EmptyView()
        }
    }

    @ViewBuilder
    private var receivingProgress: some View {
        if let progress = app.capture.progress {
            if let total = progress.totalBytes {
                ProgressView(value: Double(min(progress.receivedBytes, total)), total: Double(total))
                Text("已接收 \(size(progress.receivedBytes)) / \(size(total))")
            } else {
                ProgressView()
                Text("已接收 \(size(progress.receivedBytes))")
            }
        } else { ProgressView() }
    }

    private var appSettingsButton: some View {
        Button("打开本应用设置") {
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
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
