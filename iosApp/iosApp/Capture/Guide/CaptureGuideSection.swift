import GarupaKit
import SwiftUI
import UIKit

struct CaptureGuideSection: View {
    let app: AppModel
    @Environment(\.openURL) private var openURL
    @State private var hostCopied = false

    var body: some View {
        Section {
            if let step = app.stage.step {
                Text("步骤 \(step) / 5").font(.subheadline).foregroundStyle(.secondary)
            }
            Text(app.stage.title).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
            Text(app.stage.description)
            actions
            if !app.notifications.enabled, app.sessionActive {
                Text("通知未开启。获取存档时请手动返回本应用查看进度，后台会话仍可继续。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var actions: some View {
        switch app.stage {
        case .welcome, .startFailed:
            if let error = app.sessionError { errorText(error) }
            Text("通知用于在连接就绪和档案生成后提醒你，拒绝通知也能继续。切换到设置或游戏前，需要允许定位以维持后台运行；位置不会保存或上传。")
                .font(.footnote).foregroundStyle(.secondary)
            Button(app.stage == .welcome ? "开始获取存档" : "重新开始", action: app.startSession)
                .disabled(!app.canStart)
            if app.stage == .startFailed { appSettingsButton }
        case .preparing, .startingProxy, .verifyingProxy, .generating, .stopping:
            ProgressView().accessibilityLabel(app.stage.title)
        case .certificate:
            certificateInstructions
        case .configureProxy:
            proxyInstructions
        case .waitingForGame:
            Text("1. 如果游戏已经打开，请先从最近任务中关闭游戏。")
            Text("2. 手动打开游戏，登录并进入游戏首页。")
            Text("3. 等待档案生成通知，或返回本应用查看进度。无需在游戏里逐个查看卡牌。")
            if let error = app.capture.error { errorText(error) }
            Button("重新检测连接") { app.recheck(forceProxyCheck: true) }
                .disabled(app.capture.verifying)
        case .receiving:
            receivingProgress
        case .complete, .conversionFailed:
            if app.canStart { Button("重新获取存档", action: app.startSession) }
        case .backgroundRequired:
            if let error = app.sessionError { errorText(error) }
            Text("如果系统定位服务已关闭，请在“设置 → 隐私与安全性 → 定位服务”中开启。")
                .font(.footnote).foregroundStyle(.secondary)
            Button("恢复后台运行", action: app.resumeBackground).disabled(app.busy)
            appSettingsButton
        }
    }

    private var certificateInstructions: some View {
        Group {
            LabeledContent("证书状态", value: app.certificates.state.title)
            Button("下载描述文件", action: app.certificates.installProfile)
                .disabled(!app.certificates.canInstall || app.busy)
            if app.certificates.profileProvided {
                Text("已提供描述文件。安装和完全信任仍需在系统设置中完成。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Text("1. 下载后，手动打开“设置 → 通用 → VPN 与设备管理”，选择 Garupa Export 并安装。")
            Text("2. 打开“设置 → 通用 → 关于本机 → 证书信任设置”，开启 Garupa Export Local CA 的完全信任。")
            Text("3. 完成后返回本应用。只安装描述文件还不够，需要同时开启完全信任。")
            Button("重新检测证书") { app.recheck(forceProxyCheck: true) }
                .disabled(app.busy)
            if case .failed(let error) = app.certificates.state { errorText(error) }
            if let error = app.certificates.installationError { errorText(error) }
        }
    }

    private var proxyInstructions: some View {
        Group {
            if app.capture.environment?.wifi == false {
                Text("手动配置代理需要先连接 Wi-Fi。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Text("1. 手动打开“设置 → Wi-Fi”，点击当前已连接网络右侧的信息按钮。")
            Text("2. 打开“配置代理”，选择“手动”，填入下方服务器和端口，然后保存。")
            LabeledContent("服务器", value: "127.0.0.1").textSelection(.enabled)
            LabeledContent("端口", value: String(LocalProxy.port)).textSelection(.enabled)
            Button {
                UIPasteboard.general.string = "127.0.0.1"
                hostCopied = true
            } label: {
                Label(hostCopied ? "服务器地址已复制" : "复制服务器地址",
                      systemImage: hostCopied ? "checkmark" : "doc.on.doc")
            }
            Button("检测代理连接") { app.recheck(forceProxyCheck: true) }
                .disabled(app.capture.verifying)
            if let error = app.capture.verificationError {
                Text(error).font(.footnote).foregroundStyle(.secondary)
            }
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
        } else {
            ProgressView()
        }
    }

    private var appSettingsButton: some View {
        Button("打开本应用设置") {
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
        }
    }

    private func size(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }

    private func errorText(_ message: String) -> some View {
        Text(message).foregroundStyle(.red).textSelection(.enabled)
    }
}
