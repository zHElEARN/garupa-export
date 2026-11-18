import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ContentView: View {
    private enum Destination: Hashable { case warnings }

    let app: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var navigationPath: [Destination] = []
    @State private var showSettings = false
    @State private var showFinish = false
    @State private var copied = false
    @State private var saved = false
    @State private var profileToSave: ProfileDocument?
    @State private var saving = false
    @State private var saveError: String?

    var body: some View {
        NavigationStack(path: $navigationPath) {
            ScrollViewReader { reader in
                ScrollView {
                    VStack(alignment: .leading, spacing: 28) {
                        if showsResult {
                            profileContent
                            if app.canStart {
                                Button(action: app.startSession) {
                                    Label("重新获取存档", systemImage: "arrow.clockwise")
                                        .frame(minHeight: 44)
                                }
                                .buttonStyle(.borderless)
                            }
                        } else {
                            CaptureGuideView(app: app)
                        }
                    }
                    .frame(maxWidth: 520, alignment: .leading)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 24)
                    .frame(maxWidth: .infinity)
                    .id("content")
                }
                .defaultScrollAnchor(app.stage.isIndeterminateProgress ? .center : .top, for: .alignment)
                .onChange(of: app.stage) { _, _ in reader.scrollTo("content", anchor: .top) }
                .onChange(of: app.notifications.focusRequest) { _, _ in
                    navigationPath.removeAll()
                    showSettings = false
                    showFinish = false
                    reader.scrollTo("content", anchor: .top)
                }
            }
            .navigationTitle("Garupa Export")
            .navigationBarTitleDisplayMode(dynamicTypeSize.isAccessibilitySize ? .inline : .large)
            .navigationDestination(for: Destination.self) { _ in
                List {
                    if case .ready(let profile) = app.profiles.state {
                        ForEach(profile.warnings.indices, id: \.self) { index in
                            Text(profile.warnings[index]).textSelection(.enabled)
                        }
                    }
                }
                .navigationTitle("转换提示")
                .navigationBarTitleDisplayMode(.inline)
            }
            .toolbar {
                if app.sessionActive && app.phase != .stopping {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(app.capture.phase == .running ? "结束获取" : "取消获取") {
                            if app.capture.phase == .running { showFinish = true }
                            else { Task { await app.stopSession() } }
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("设置", systemImage: "gearshape") { showSettings = true }
                }
            }
            .sheet(isPresented: $showSettings) {
                NavigationStack {
                    ExportSettingsView(app: app)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showSettings = false } } }
                }
            }
            .sheet(isPresented: $showFinish) {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("设置 → Wi-Fi → 原网络 → 配置代理 → 关闭。")
                                .font(.headline)
                            Text("切换网络不会清除原 Wi-Fi 的代理设置。")
                                .foregroundStyle(.secondary)
                            if app.capture.environment?.route == .local {
                                Label("当前网络仍使用本地代理，请先关闭。", systemImage: "exclamationmark.triangle")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            Button {
                                showFinish = false
                                Task { await app.stopSession() }
                            } label: {
                                Text("已关闭代理，结束获取")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                            .disabled(app.capture.environment?.route == .local)
                        }
                        .frame(maxWidth: 520, alignment: .leading)
                        .padding(24)
                        .frame(maxWidth: .infinity)
                    }
                    .navigationTitle("结束获取")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { showFinish = false } } }
                }
                .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium, .large])
            }
            .fileExporter(isPresented: $saving,
                          document: profileToSave.map { ProfileFile(json: $0.json) },
                          contentType: .json,
                          defaultFilename: profileToSave?.url.deletingPathExtension().lastPathComponent ?? "bestdori") { result in
                switch result {
                case .success:
                    if profileToSave?.id == profileID { saved = true }
                case .failure(let error):
                    if (error as? CocoaError)?.code != .userCancelled { saveError = error.localizedDescription }
                }
                profileToSave = nil
            }
            .alert("保存失败", isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })) {
                Button("好", role: .cancel) {}
            } message: { Text(saveError ?? "") }
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
            navigationPath.removeAll()
            copied = false
            saved = false
        }
        .task(id: copied) {
            guard copied else { return }
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            copied = false
        }
        .task(id: saved) {
            guard saved else { return }
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
            saved = false
        }
    }

    @ViewBuilder
    private var profileContent: some View {
        switch app.profiles.state {
        case .ready(let profile):
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Bestdori 档案")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(profile.name)
                        .font(.title.bold())
                        .accessibilityAddTraits(.isHeader)
                        .textSelection(.enabled)
                }
                profileCounts(profile)
                VStack(spacing: 10) {
                    Button {
                        UIPasteboard.general.string = profile.json
                        copied = true
                    } label: {
                        Label(copied ? "已复制" : "复制档案", systemImage: copied ? "checkmark" : "doc.on.doc")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    fileActions(profile)
                }
                .controlSize(.large)
                if !profile.warnings.isEmpty {
                    VStack(spacing: 8) {
                        Divider()
                        NavigationLink(value: Destination.warnings) {
                            HStack {
                                Label("转换提示（\(profile.warnings.count)）", systemImage: "exclamationmark.triangle")
                                Spacer()
                                Image(systemName: "chevron.forward")
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(Color.secondary)
                                    .accessibilityHidden(true)
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
        case .failed(let message, let refreshSuggested):
            VStack(alignment: .leading, spacing: 16) {
                Text(app.stage.title)
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                Text(message).foregroundStyle(.red).textSelection(.enabled)
                if let error = app.catalog.error, error != message { Text(error).foregroundStyle(.red) }
                Button {
                    app.profiles.retry(refreshCatalog: refreshSuggested)
                } label: {
                    Text(refreshSuggested ? "刷新数据并重试" : "重新解析")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(app.catalog.loading)
                Button {
                    app.profiles.retry(refreshCatalog: !refreshSuggested)
                } label: {
                    Text(refreshSuggested ? "重新解析" : "刷新数据并重试")
                        .frame(minHeight: 44)
                }
                .buttonStyle(.borderless)
                .disabled(app.catalog.loading)
                if app.catalog.loading { ProgressView("正在刷新卡牌数据") }
            }
        case .empty, .parsing:
            EmptyView()
        }
    }

    private func profileCounts(_ profile: ProfileDocument) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 14))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 12))
        return layout {
            profileCount("卡牌", count: profile.cardCount)
            profileCount("区域道具", count: profile.areaItemCount)
            profileCount("潜能", count: profile.potentialCount)
        }
    }

    @ViewBuilder
    private func profileCount(_ title: LocalizedStringKey, count: Int) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            LabeledContent(title) {
                Text(count, format: .number).monospacedDigit()
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text(count, format: .number)
                    .font(.title3.weight(.semibold).monospacedDigit())
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(title))
            .accessibilityValue(Text(count, format: .number))
        }
    }

    private func fileActions(_ profile: ProfileDocument) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 10))
            : AnyLayout(HStackLayout(spacing: 10))
        return layout {
            Button {
                guard !saving else { return }
                profileToSave = profile
                saving = true
            } label: {
                Label(saved ? "已保存" : "保存文件", systemImage: saved ? "checkmark" : "square.and.arrow.down")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(saving)
            ShareLink(item: profile.url) {
                Label("分享", systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    private var showsResult: Bool { app.stage == .complete || app.stage == .conversionFailed }

    private var profileID: UUID? {
        if case .ready(let profile) = app.profiles.state { return profile.id }
        return nil
    }
}

private struct ExportSettingsView: View {
    let app: AppModel
    @Environment(\.openURL) private var openURL

    var body: some View {
        List {
            Section("Bestdori 卡牌数据") {
                LabeledContent("状态") {
                    HStack {
                        if app.catalog.loading { ProgressView().accessibilityHidden(true) }
                        Text(catalogStatus)
                    }
                }
                if let cache = app.catalog.cache {
                    LabeledContent("卡牌", value: "\(cache.cardCount) 张")
                    LabeledContent("最近更新") {
                        Text(Date(timeIntervalSince1970: Double(cache.updatedAtMillis) / 1000),
                             format: .dateTime.year().month().day().hour().minute())
                    }
                }
                if let error = app.catalog.error { Text(error).foregroundStyle(.red) }
                Button(app.catalog.cache == nil ? "下载数据" : "刷新数据") { Task { await app.catalog.refresh() } }
                    .disabled(app.catalog.loading)
            }
            Section("运行状态") {
                LabeledContent("证书", value: app.certificates.state.title)
                LabeledContent("代理", value: app.capture.status)
                if app.capture.phase == .running {
                    LabeledContent("连接", value: app.capture.proxyVerified ? "已连接" : "未连接")
                    if let error = app.capture.verificationError { Text(error).foregroundStyle(.secondary) }
                    Button("重新检测连接") { app.recheck(forceProxyCheck: true) }
                        .disabled(app.capture.verifying || app.busy)
                }
            }
            Section("权限") {
                LabeledContent("通知", value: app.notifications.enabled ? "已开启" : "未开启")
                Button("打开本应用设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var catalogStatus: String {
        if app.catalog.loading { return app.catalog.cache == nil ? "正在下载" : "正在刷新" }
        if app.catalog.upToDate { return "已是最新数据" }
        return app.catalog.cache == nil ? "未下载" : "已缓存"
    }
}
