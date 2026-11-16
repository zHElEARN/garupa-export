import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ContentView: View {
    let app: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmStop = false
    @State private var copied = false
    @State private var saved = false
    @State private var showDetails = false
    @State private var profileToSave: ProfileDocument?
    @State private var saving = false
    @State private var saveError: String?

    var body: some View {
        ScrollViewReader { reader in
            NavigationStack {
                Form {
                    CaptureGuideSection(app: app).id("guide")
                    if hasProfile { profileSection }
                    if app.sessionActive { sessionControls }
                    Section {
                        DisclosureGroup("卡牌数据与运行状态", isExpanded: $showDetails) {
                            catalogRows
                            LabeledContent("证书", value: app.certificates.state.title)
                            LabeledContent("本地代理", value: app.capture.status)
                        }
                    }
                }
                .navigationTitle("Garupa Export")
                .alert("已关闭 Wi-Fi 代理？", isPresented: $confirmStop) {
                    Button("已关闭代理，停止服务", role: .destructive) { Task { await app.stopSession() } }
                    Button("取消", role: .cancel) {}
                } message: {
                    Text("请确认已在设置过代理的 Wi-Fi 中关闭 HTTP 代理。仅切换网络不会移除原设置。")
                }
            }
            .onChange(of: app.notifications.focusRequest) { _, _ in
                withAnimation { reader.scrollTo("guide", anchor: .top) }
            }
        }
        .task {
            app.setForeground(scenePhase == .active)
            await app.activate()
        }
        .onChange(of: scenePhase) { _, phase in
            app.setForeground(phase == .active)
            if phase == .active { Task { await app.activate() } }
        }
        .onChange(of: profileID) { _, _ in
            copied = false
            saved = false
        }
    }

    private var sessionControls: some View {
        Section {
            if app.capture.phase == .running {
                Text("本地代理仍在后台运行。请先在原 Wi-Fi 中关闭 HTTP 代理，再停止服务。")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("停止代理", role: .destructive) { confirmStop = true }
                    .disabled(app.busy)
            } else {
                Button("取消本次设置", role: .cancel) { Task { await app.stopSession() } }
                    .disabled(app.busy)
            }
        }
    }

    @ViewBuilder
    private var catalogRows: some View {
        LabeledContent("状态") {
            HStack {
                if app.catalog.loading { ProgressView() }
                Text(catalogStatus)
            }
        }
        if let cache = app.catalog.cache {
            LabeledContent("本地缓存", value: "\(cache.cardCount) 张卡牌")
            LabeledContent("最近更新") {
                Text(Date(timeIntervalSince1970: Double(cache.updatedAtMillis) / 1000),
                     format: .dateTime.year().month().day().hour().minute())
            }
        }
        if let error = app.catalog.error { errorText(error) }
        Button(app.catalog.cache == nil ? "下载数据" : "刷新数据") {
            Task { await app.catalog.refresh() }
        }
        .disabled(app.catalog.loading)
    }

    private var catalogStatus: String {
        if app.catalog.loading { return app.catalog.cache == nil ? "正在下载" : "正在刷新" }
        if app.catalog.upToDate { return "已是最新数据" }
        return app.catalog.cache == nil ? "未下载" : "已缓存"
    }

    private var profileSection: some View {
        Section("Bestdori 档案") {
            switch app.profiles.state {
            case .parsing:
                LabeledContent("状态") { HStack { ProgressView(); Text("正在生成档案") } }
            case .ready(let profile):
                LabeledContent("状态", value: "档案已生成")
                VStack(alignment: .leading, spacing: 4) {
                    Text(profile.name).font(.headline)
                    Text("卡牌 \(profile.cardCount) · 区域道具 \(profile.areaItemCount) · 潜能 \(profile.potentialCount)")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                .textSelection(.enabled)
                if !profile.warnings.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Label("转换提示（\(profile.warnings.count)）", systemImage: "exclamationmark.triangle")
                            .font(.subheadline.weight(.semibold))
                        ForEach(profile.warnings.indices, id: \.self) { index in
                            Text(profile.warnings[index]).font(.subheadline).textSelection(.enabled)
                        }
                    }
                }
                Button {
                    UIPasteboard.general.string = profile.json
                    copied = true
                } label: {
                    Label(copied ? "已复制档案" : "复制档案", systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                Button {
                    profileToSave = profile
                    saving = true
                } label: {
                    Label(saved ? "已保存文件" : "保存文件", systemImage: saved ? "checkmark" : "square.and.arrow.down")
                }
                ShareLink(item: profile.url) { Label("分享", systemImage: "square.and.arrow.up") }
            case .failed(let message):
                LabeledContent("状态", value: "生成失败")
                errorText(message)
                if let error = app.catalog.error { errorText(error) }
                Button("刷新卡牌数据") { Task { await app.catalog.refresh() } }
                    .disabled(app.catalog.loading)
                Button("重新解析", action: app.profiles.retry).disabled(app.catalog.loading)
                if app.catalog.loading { ProgressView() }
            case .empty:
                LabeledContent("状态", value: "暂无档案")
            }
        }
        .fileExporter(isPresented: $saving,
                      document: profileToSave.map { ProfileFile(json: $0.json) },
                      contentType: .json,
                      defaultFilename: profileToSave?.url.deletingPathExtension().lastPathComponent ?? "bestdori") { result in
            switch result {
            case .success:
                // A new capture may have started while the system picker was open.
                if profileToSave?.id == profileID { saved = true }
            case .failure(let error):
                if (error as? CocoaError)?.code != .userCancelled { saveError = error.localizedDescription }
            }
        }
        .alert("保存失败", isPresented: Binding(
            get: { saveError != nil }, set: { if !$0 { saveError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(saveError ?? "")
        }
    }

    private var hasProfile: Bool {
        switch app.profiles.state {
        case .ready, .failed: return true
        case .empty, .parsing: return false
        }
    }

    private var profileID: UUID? {
        if case .ready(let profile) = app.profiles.state { return profile.id }
        return nil
    }

    private func errorText(_ message: String) -> some View {
        Text(message).foregroundStyle(.red).textSelection(.enabled)
    }
}
