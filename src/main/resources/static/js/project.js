var projectId = (window.location.pathname.match(/\/projects\/(\d+)/) || [0,0])[1] | 0;
var autoScroll = true;

function toggleAutoScroll() {
    autoScroll = !autoScroll;
    var btn = document.getElementById('btn-auto-scroll');
    if (autoScroll) {
        btn.innerHTML = '&#8595; 实时跟随';
        btn.style.color = 'var(--primary)';
    } else {
        btn.innerHTML = '&#8595; 已暂停';
        btn.style.color = 'var(--warning)';
    }
}
var selectedOutlineId = null;
var currentStep = '';
var activeTab = 'welcome';
var typewriterTimers = [];

function timeNow() {
    return new Date().toLocaleTimeString('zh-CN', {hour12:false});
}

document.addEventListener('DOMContentLoaded', function() {
    // 启动编辑器内容自动跟随观察器（监听流式跟踪器容器）
    var tracker = document.getElementById('stream-tracker-container');
    if (tracker) {
        editorScrollObserver.observe(tracker, { childList: true, subtree: true });
    }
    loadAndMaybeStart();
});

// ============ Typing Effect ============
function typewriterText(el, html, speed) {
    speed = speed || 15;
    clearTypewriters();
    el.innerHTML = '';
    // For HTML content, we type by inserting fragments
    var temp = document.createElement('div');
    temp.innerHTML = html;
    var nodes = Array.from(temp.childNodes);
    var i = 0;
    function nextNode() {
        if (i >= nodes.length) { return; }
        var clone = nodes[i].cloneNode(true);
        el.appendChild(clone);
        i++;
        var tid = setTimeout(nextNode, speed * (clone.textContent ? Math.min(clone.textContent.length, 20) : 3));
        typewriterTimers.push(tid);
        scrollEditorToBottom();
    }
    nextNode();
}

function typewriterPlain(el, text, speed) {
    speed = speed || 8;
    clearTypewriters();
    el.innerHTML = '';
    var i = 0;
    var out = '';
    function nextChar() {
        if (i >= text.length) { return; }
        out += text[i];
        el.innerHTML = escapeHtml(out) + '<span class="typing-cursor"></span>';
        i++;
        var delay = speed;
        if (text[i-1] === '\n') delay = speed * 3;
        var tid = setTimeout(nextChar, delay);
        typewriterTimers.push(tid);
        // 每个字符都滚动到底
        scrollEditorToBottom();
    }
    nextChar();
}

function clearTypewriters() {
    typewriterTimers.forEach(function(t) { clearTimeout(t); });
    typewriterTimers = [];
}

// ============ Auto Follow ============
var autoFollow = true;
function toggleAutoFollow() {
    autoFollow = !autoFollow;
    var btn = document.getElementById('btn-follow');
    if (autoFollow) {
        btn.innerHTML = '&#8595; 自动跟随';
        btn.style.opacity = '0.7';
        btn.style.color = '';
    } else {
        btn.innerHTML = '&#128274; 已锁定';
        btn.style.opacity = '0.9';
        btn.style.color = 'var(--warning)';
    }
}

// 编辑器内容区自动滚动到底部（用 requestAnimationFrame 确保布局完成）
function scrollEditorToBottom() {
    if (!autoFollow) return;
    requestAnimationFrame(function() {
        var editorBody = document.getElementById('editor-body');
        if (editorBody) {
            editorBody.scrollTop = editorBody.scrollHeight;
        }
    });
}

// 监听 stream-tracker-container 内容变化，自动滚动（比全树监听精准）
var editorScrollObserver = new MutationObserver(function() {
    scrollEditorToBottom();
});

// ============ Panel Toggle ============
function togglePanel() {
    var panel = document.getElementById('ide-panel');
    panel.classList.toggle('open');
}

// 面板拖拽调整高度
(function() {
    var handle = document.getElementById('panel-resize-handle');
    var panel = document.getElementById('ide-panel');
    var startY, startHeight;
    handle.addEventListener('mousedown', function(e) {
        startY = e.clientY;
        startHeight = panel.offsetHeight;
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
        e.preventDefault();
    });
    function onMove(e) {
        var newHeight = startHeight + (startY - e.clientY);
        if (newHeight < 60) newHeight = 60;
        if (newHeight > 500) newHeight = 500;
        panel.style.height = newHeight + 'px';
        if (newHeight > 60) panel.classList.add('open');
        else panel.classList.remove('open');
    }
    function onUp() {
        document.removeEventListener('mousemove', onMove);
        document.removeEventListener('mouseup', onUp);
    }
})();

// ============ Tab System ============
function openTab(tabId, icon, label, doSwitch) {
    // doSwitch 默认为 true；批量加载时传 false 避免互相覆盖
    if (doSwitch === undefined) doSwitch = true;
    // welcome 有专用 div，不创建 content 容器
    if (tabId !== 'welcome') {
        var contentDiv = document.getElementById('content-' + tabId);
        if (!contentDiv) {
            contentDiv = document.createElement('div');
            contentDiv.id = 'content-' + tabId;
            contentDiv.style.display = 'none';
            document.getElementById('editor-body').appendChild(contentDiv);
        }
    }
    var existing = document.querySelector('#editor-tabs [data-tab="' + tabId + '"]');
    if (!existing) {
        var tab = document.createElement('div');
        tab.className = 'editor-tab';
        tab.dataset.tab = tabId;
        tab.innerHTML = '<span class="tab-icon">' + (icon || '') + '</span>' + label;
        tab.onclick = function() { switchTab(tabId); };
        // close button
        var close = document.createElement('span');
        close.innerHTML = ' &times;';
        close.style.cssText = 'margin-left:6px;cursor:pointer;opacity:0.5;';
        close.onclick = function(e) { e.stopPropagation(); closeTab(tabId); };
        tab.appendChild(close);
        document.getElementById('editor-tabs').appendChild(tab);
    }
    if (doSwitch) switchTab(tabId);
}

function switchTab(tabId) {
    document.querySelectorAll('#editor-tabs .editor-tab').forEach(function(t) { t.classList.remove('active'); });
    var tab = document.querySelector('#editor-tabs [data-tab="' + tabId + '"]');
    if (tab) tab.classList.add('active');
    activeTab = tabId;
    // Show/hide content divs
    document.querySelectorAll('#editor-body > div').forEach(function(d) { d.style.display = 'none'; });
    // welcome 优先检查，防止被自动创建的 content-welcome 空 div 覆盖
    if (tabId === 'welcome') {
        document.getElementById('welcome-view').style.display = '';
        // 欢迎页同时显示流式跟踪器（卡片），让用户看到进度摘要
        var tracker = document.getElementById('stream-tracker-container');
        if (tracker) tracker.style.display = '';
    } else {
        var contentDiv = document.getElementById('content-' + tabId);
        if (contentDiv) {
            contentDiv.style.display = '';
        }
    }
    // 同步文件树高亮
    highlightTreeFile(tabId);
}

function closeTab(tabId) {
    var tab = document.querySelector('#editor-tabs [data-tab="' + tabId + '"]');
    if (!tab) return;
    var wasActive = tab.classList.contains('active');
    var prev = tab.previousElementSibling;
    tab.remove();
    if (wasActive) {
        if (prev && prev.dataset.tab) {
            switchTab(prev.dataset.tab);
        } else {
            switchTab('welcome');
        }
    }
}

// ============ File Tree ============
// 展开/折叠文件夹
function toggleFolder(headerEl) {
    var folder = headerEl.parentElement;
    folder.classList.toggle('collapsed');
}

// 点击文件树中的文件
function openFile(tabId, icon, label) {
    openTab(tabId, icon, label, true);
    // 高亮文件树中的对应文件
    highlightTreeFile(tabId);
    // 如果内容为空，按需加载
    var contentDiv = document.getElementById('content-' + tabId);
    if (contentDiv && (!contentDiv.innerHTML || contentDiv.innerHTML.trim() === '')) {
        loadContentForTab(tabId);
    }
    // 手动点击 → 停止自动跟随
    if (autoFollow) toggleAutoFollow();
}

// 按需加载 tab 内容
function loadContentForTab(tabId) {
    if (tabId === 'requirement') loadRequirementContent();
    else if (tabId === 'search') loadSearchCharacterContent();
    else if (tabId === 'outline') loadOutlinesInline();
    else if (tabId === 'script') loadScriptContent();
    else if (tabId === 'review') loadReviewContent();
    // welcome / complete 不需要加载
}

// 高亮文件树中的活动文件
function highlightTreeFile(tabId) {
    document.querySelectorAll('#file-tree .tree-file, #file-tree .tree-home').forEach(function(el) {
        el.classList.remove('active');
    });
    if (tabId === 'welcome') {
        var home = document.querySelector('#file-tree .tree-home');
        if (home) home.classList.add('active');
    }
    // 找到对应的 tree-file（通过 onclick 属性中的 tabId）
    var files = document.querySelectorAll('#file-tree .tree-file');
    for (var i = 0; i < files.length; i++) {
        var onclick = files[i].getAttribute('onclick') || '';
        if (onclick.indexOf("'" + tabId + "'") >= 0) {
            files[i].classList.add('active');
            // 自动展开父文件夹
            var folder = files[i].closest('.tree-folder');
            if (folder) folder.classList.remove('collapsed');
            break;
        }
    }
}

// 旧的 focusStep 重定向，保留兼容性
function focusStep(step) {
    var tabId = mapStepToTab(step) || step;
    openTab(tabId, '', getStepLabel(step));
    refreshStepContent(step);
    if (autoFollow) toggleAutoFollow();
    // 通过 data-step 高亮对应文件夹
    highlightFolder(step);
}

function highlightFolder(step) {
    var folders = document.querySelectorAll('#file-tree .tree-folder[data-step]');
    folders.forEach(function(f) {
        if (f.dataset.step === step) {
            f.classList.remove('collapsed');
            f.querySelector('.tree-folder-header').classList.add('active');
        } else {
            f.querySelector('.tree-folder-header').classList.remove('active');
        }
    });
}

function getStepLabel(step) {
    var map = {
        'REQUIREMENT_GATHERING': '需求调研',
        'SEARCH_AND_CHARACTER': '信息检索',
        'OUTLINE_DESIGN': '大纲设计',
        'OUTLINE_SELECT': '大纲确认',
        'SCRIPT_GENERATION': '剧本生成',
        'QUALITY_REVIEW': '质量审核',
        'EXPORT': '导出',
        'COMPLETE': '完成'
    };
    for (var k in map) if (step.indexOf(k) >= 0) return map[k];
    return step || '未知';
}

function mapStepToTab(step) {
    if (!step) return null;
    if (step.indexOf('REQUIREMENT') >= 0) return 'requirement';
    if (step.indexOf('SEARCH') >= 0 || step.indexOf('CHARACTER') >= 0) return 'search';
    if (step.indexOf('OUTLINE') >= 0 && step.indexOf('SELECT') < 0) return 'outline';
    if (step.indexOf('SCRIPT') >= 0) return 'script';
    if (step.indexOf('REVIEW') >= 0) return 'review';
    if (step.indexOf('COMPLETE') >= 0 || step.indexOf('DONE') >= 0) return 'complete';
    return null;
}

// ============ Agent 文件导航 ============

/** 处理 Agent 发来的 navigate 事件（编辑器跳转 + 文件树高亮） */
function handleNavigateEvent(content) {
    var nav;
    try {
        nav = typeof content === 'string' ? JSON.parse(content) : content;
    } catch (e) { return; }
    var filePath = nav.filePath || '';
    var displayName = nav.displayName || filePath.split('/').pop() || filePath;
    if (!filePath) return;
    navigateToFile(filePath, displayName);
}

/** 在编辑器中打开工作空间文件，并在文件树中动态高亮 */
function navigateToFile(filePath, displayName) {
    if (!projectId) return;
    // 生成稳定的 tabId
    var tabId = 'ws-' + filePath.replace(/[^a-zA-Z0-9\u4e00-\u9fa5.-]/g, '_');
    var icon = '&#128196;';
    openTab(tabId, icon, displayName, true);
    // 加载文件内容
    var contentDiv = document.getElementById('content-' + tabId);
    if (contentDiv) {
        contentDiv.innerHTML = '<div style="padding:1rem;color:var(--muted);">正在加载文件...</div>';
        fetch('/api/projects/' + projectId + '/files?path=' + encodeURIComponent(filePath))
            .then(function(r) { return r.json(); })
            .then(function(resp) {
                if (resp.code === 0 && resp.data) {
                    var ext = filePath.split('.').pop().toLowerCase();
                    var lang = 'plaintext';
                    if (ext === 'md') lang = 'markdown';
                    else if (ext === 'java') lang = 'java';
                    else if (ext === 'js') lang = 'javascript';
                    else if (ext === 'json') lang = 'json';
                    else if (ext === 'py') lang = 'python';
                    else if (ext === 'html') lang = 'html';
                    else if (ext === 'css') lang = 'css';
                    else if (ext === 'yml' || ext === 'yaml') lang = 'yaml';
                    contentDiv.innerHTML = '<pre class="file-content"><code class="language-' + lang + '">'
                        + escapeHtml(resp.data.content) + '</code></pre>';
                } else {
                    contentDiv.innerHTML = '<div style="padding:2rem;color:var(--muted);text-align:center;">文件暂不可用</div>';
                }
            })
            .catch(function() {
                contentDiv.innerHTML = '<div style="padding:2rem;color:var(--warning);text-align:center;">加载失败</div>';
            });
    }
    // 在文件树中动态高亮该文件
    highlightOrAddTreeFile(filePath, displayName);
    // 状态栏提示
    var stepEl = document.getElementById('status-step');
    if (stepEl) stepEl.textContent = '正在查看: ' + displayName;
}

/** 在文件树中查找或添加工作空间文件条目，并高亮它 */
function highlightOrAddTreeFile(filePath, displayName) {
    // 取消所有高亮
    document.querySelectorAll('#file-tree .tree-file, #file-tree .tree-home').forEach(function(el) {
        el.classList.remove('active');
    });
    // 尝试查找已存在的动态文件条目
    var existing = document.querySelector('#file-tree .tree-file[data-ws-path="' + filePath + '"]');
    if (existing) {
        existing.classList.add('active');
        return;
    }
    // 动态添加到文件树底部：添加到"工作空间"文件夹
    var wsFolder = document.querySelector('#file-tree .tree-folder[data-step="WORKSPACE"]');
    if (!wsFolder) {
        // 首次创建"工作空间"文件夹
        wsFolder = document.createElement('div');
        wsFolder.className = 'tree-folder';
        wsFolder.setAttribute('data-step', 'WORKSPACE');
        wsFolder.innerHTML = '<div class="tree-folder-header" onclick="toggleFolder(this)">'
            + '<span class="folder-arrow">&#9660;</span>'
            + '<span class="folder-dot" style="background:var(--success);"></span>'
            + '&#128193; 工作空间'
            + '</div>'
            + '<div class="tree-folder-items"></div>';
        document.getElementById('file-tree').appendChild(wsFolder);
    }
    var items = wsFolder.querySelector('.tree-folder-items');
    var fileEl = document.createElement('div');
    fileEl.className = 'tree-file active';
    fileEl.setAttribute('data-ws-path', filePath);
    fileEl.innerHTML = '<span class="file-icon generated">&#128196;</span>'
        + '<span>' + escapeHtml(displayName) + '</span>';
    fileEl.onclick = function() {
        navigateToFile(filePath, displayName);
    };
    items.appendChild(fileEl);
}

// ============ 侧边栏双视图切换 ============

var _sidebarTab = 'steps';

function switchSidebarTab(name) {
    _sidebarTab = name;
    document.querySelectorAll('.sidebar-tab').forEach(function(t) {
        t.classList.toggle('active', t.textContent.trim().indexOf(name === 'steps' ? '步骤' : '文件') >= 0);
    });
    var stepsTree = document.getElementById('file-tree');
    var filesTree = document.getElementById('file-tree-real');
    if (name === 'steps') {
        stepsTree.style.display = '';
        filesTree.style.display = 'none';
    } else {
        stepsTree.style.display = 'none';
        filesTree.style.display = 'block';
        loadRealFileTree();
    }
}

function loadRealFileTree() {
    if (!projectId) return;
    var container = document.getElementById('file-tree-real');
    container.innerHTML = '<div class="tree-empty">正在加载文件树...</div>';
    fetch('/api/projects/' + projectId + '/files/tree')
        .then(function(r) { return r.json(); })
        .then(function(resp) {
            if (resp.code !== 0 || !resp.data) {
                container.innerHTML = '<div class="tree-empty">加载失败</div>';
                return;
            }
            if (!resp.data || resp.data.length === 0) {
                container.innerHTML = '<div class="tree-empty">工作空间暂无文件<br><span style="font-size:0.65rem;">Agent 生成的文件将出现在这里</span></div>';
                return;
            }
            container.innerHTML = '';
            renderRealTreeNodes(container, resp.data, '');
        })
        .catch(function() {
            container.innerHTML = '<div class="tree-empty">加载失败</div>';
        });
}

function renderRealTreeNodes(parent, nodes, depth) {
    if (!nodes) return;
    depth = depth || 0;
    for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (node.type === 'folder') {
            var folder = document.createElement('div');
            folder.className = 'tree-folder';
            folder.style.paddingLeft = (depth * 12) + 'px';
            folder.innerHTML = '<div class="tree-folder-header" onclick="toggleFolder(this)">'
                + '<span class="folder-arrow">&#9660;</span>'
                + '<span class="folder-dot" style="background:var(--primary-light);"></span>'
                + '&#128193; ' + escapeHtml(node.name)
                + '</div>'
                + '<div class="tree-folder-items"></div>';
            parent.appendChild(folder);
            var items = folder.querySelector('.tree-folder-items');
            renderRealTreeNodes(items, node.children || [], depth + 1);
        } else {
            var file = document.createElement('div');
            file.className = 'tree-file';
            file.style.paddingLeft = (1.2 + depth * 0.8) + 'rem';
            var icon = '&#128196;';
            var name = node.name.toLowerCase();
            if (name.endsWith('.md')) icon = '&#128214;';
            else if (name.endsWith('.json')) icon = '&#128230;';
            else if (name.endsWith('.txt')) icon = '&#128196;';
            file.innerHTML = '<span class="file-icon generated">' + icon + '</span>'
                + '<span>' + escapeHtml(node.name) + '</span>';
            file.onclick = function(n) {
                return function() {
                    navigateToFile(n.path, n.name);
                    // 切换到编辑器
                    switchToEditorTab('ws-' + n.path.replace(/[^a-zA-Z0-9\u4e00-\u9fa5.-]/g, '_'));
                };
            }(node);
            parent.appendChild(file);
        }
    }
}

/** 切换编辑器到指定 tab */
function switchToEditorTab(tabId) {
    var tab = document.querySelector('.editor-tab[data-tab="' + tabId + '"]');
    if (tab) {
        tab.click();
    }
}

// ============ Project Loading ============
function loadAndMaybeStart() {
    fetch('/api/projects/' + projectId)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.code === 0) {
                var p = data.data;
                document.getElementById('toolbar-game').textContent = p.gameName || '-';
                updateWelcome(p);

                var badge = document.getElementById('toolbar-status');
                badge.textContent = getStatusLabel(p.status);
                badge.className = 'badge badge-' + (p.status || 'draft').toLowerCase().replace('_','-');

                currentStep = p.currentStep || 'INIT';
                updateStepIndicator(currentStep);
                updateProgressFromStep(currentStep);

                if (p.status === 'DRAFT' || currentStep === 'INIT') {
                    updateStatusBar('自动启动中...');
                    startWorkflow(projectId);
                } else if (p.status === 'IN_PROGRESS') {
                    updateStatusBar('运行中');
                    // 刷新页面时从数据库恢复所有已保存内容
                    loadAllSavedContent(currentStep);
                    rebuildStreamCards(currentStep);
                    connectSSE(projectId, updateStepIndicator);
                    showPauseButton();
                } else if (p.status === 'PAUSED' || p.status === '已暂停') {
                    updateStatusBar('已暂停 — 数据已从数据库恢复');
                    document.getElementById('btn-start').textContent = '&#9654; 继续';
                    document.getElementById('btn-start').disabled = false;
                    // 从数据库加载已保存的所有内容
                    loadAllSavedContent(currentStep);
                    rebuildStreamCards(currentStep);
                    switchTab('welcome');
                } else if (p.status === 'COMPLETED' || currentStep === 'DONE') {
                    updateStatusBar('已完成');
                    loadAllSavedContent(currentStep);
                    rebuildStreamCards(currentStep);
                    switchTab('welcome');
                }
            }
        });
}

function updateWelcome(p) {
    var titleEl = document.getElementById('welcome-title');
    var subEl = document.getElementById('welcome-sub');
    titleEl.textContent = p.title || '无标题';

    var step = p.currentStep || 'INIT';
    var status = p.status;
    var stepLabel = getStepLabel(step);
    var statusLabel = getStatusLabel(status);

    // 根据当前状态生成有意义的欢迎信息
    var info = '';
    if (status === 'DRAFT' || step === 'INIT') {
        info = '准备自动启动工作流，从需求调研开始...';
    } else if (status === 'IN_PROGRESS') {
        info = '当前步骤: ' + stepLabel + ' — 工作流正在运行中';
    } else if (status === 'PAUSED') {
        info = '当前步骤: ' + stepLabel + ' — 点击左侧步骤可查看已生成内容，点击「继续」恢复工作流';
    } else if (status === 'COMPLETED' || step === 'DONE') {
        info = '工作流已完成，可查看剧本和审核报告';
    }
    if (subEl) subEl.textContent = info;
}

// 根据 currentStep 重建步骤进度卡片（暂停/刷新后恢复用）
function rebuildStreamCards(currentStep) {
    var allSteps = ['REQUIREMENT_GATHERING', 'SEARCH_AND_CHARACTER', 'OUTLINE_DESIGN',
                    'SCRIPT_GENERATION', 'QUALITY_REVIEW'];
    var stepOrder = ['INIT','REQUIREMENT_GATHERING','SEARCH_AND_CHARACTER',
                     'OUTLINE_DESIGN','SCRIPT_GENERATION','QUALITY_REVIEW','DONE'];
    var currentIdx = stepOrder.indexOf(currentStep);
    if (currentIdx < 0) {
        for (var i = 0; i < allSteps.length; i++) {
            if (currentStep && currentStep.indexOf(allSteps[i]) >= 0) { currentIdx = stepOrder.indexOf(allSteps[i]); break; }
        }
    }
    if (currentIdx <= 0) currentIdx = 1; // 至少需求调研是当前

    var container = document.getElementById('stream-tracker-container');
    container.innerHTML = '';
    container.style.display = '';

    for (var i = 0; i < allSteps.length; i++) {
        var step = allSteps[i];
        var si = stepOrder.indexOf(step);
        var label = getStepLabel(step);
        var icon, status, cls;

        if (currentStep === 'DONE' || currentStep === 'COMPLETE') {
            icon = '&#9989;'; status = '已完成'; cls = 'completed';
        } else if (si < currentIdx) {
            icon = '&#9989;'; status = '已完成'; cls = 'completed';
        } else if (si === currentIdx) {
            icon = '&#128260;'; status = '当前步骤'; cls = 'active';
        } else {
            icon = '&#9203;'; status = '等待中'; cls = 'pending';
        }

        var card = document.createElement('div');
        card.className = 'tracker-card card-' + cls;
        card.style.cssText = 'margin:0.5rem 1rem;padding:0.65rem 0.9rem;border:1px solid var(--border);' +
            'border-radius:8px;background:var(--surface);display:flex;align-items:center;gap:0.6rem;';
        card.innerHTML = '<span style="font-size:1.2rem;">' + icon + '</span>' +
            '<span style="font-weight:500;flex:1;">' + label + '</span>' +
            '<span style="font-size:0.75rem;color:var(--muted);">' + status + '</span>';
        container.appendChild(card);
    }
}

// 重写 startWorkflow
startWorkflow = function(pid) {
    // 即时禁用按钮，防止快速连击
    var btnStart = document.getElementById('btn-start');
    btnStart.disabled = true;
    btnStart.textContent = '... 启动中';

    document.title = pid + ' 启动中...';
    appendLog(timeNow(), '正在启动引擎...', 'log-info');
    document.getElementById('status-progress-fill').style.width = '0%';
    document.getElementById('status-pct').textContent = '0%';
    document.getElementById('status-step').textContent = '连接中...';

    // 显示流式跟踪器容器
    var trackerContainer = document.getElementById('stream-tracker-container');
    trackerContainer.style.display = '';
    // 如果是继续（之前有卡片内容），不清理，加分隔线
    var isResume = trackerContainer.innerHTML.trim().length > 0;
    if (isResume) {
        var sep = document.createElement('div');
        sep.style.cssText = 'margin:1rem 0;padding:0.3rem 0;text-align:center;color:var(--muted);' +
            'font-size:0.78rem;border-top:1px dashed var(--border);border-bottom:1px dashed var(--border);';
        sep.textContent = '—— 恢复运行 ——';
        trackerContainer.appendChild(sep);
    } else {
        trackerContainer.innerHTML = '';
    }
    // 继续时切回欢迎，让用户看到恢复运行状态
    if (isResume) {
        switchTab('welcome');
    }

    // 单一 WebSocket 连接同时承载 progress / question / track 事件
    var proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    var ws = new WebSocket(proto + '//' + window.location.host + '/ws/projects/' + pid);
    var tracker = initStreamTracker('stream-tracker-container', ws);

    ws.addEventListener('message', function(e) {
        try {
            var msg = JSON.parse(e.data);
            var evtName = msg.event;
            var raw = msg.data;

            if (evtName === 'progress') {
                var d = (typeof raw === 'string') ? JSON.parse(raw) : raw;
                if (d.step) updateStepIndicator(d.step);
                if (d.message) appendLog(timeNow(), '['+d.step+'] '+d.message,
                    d.status==='completed'?'log-completed':'log-info');
                if (d.status==='completed' && d.step) {
                    updateProgressFromStep(d.step);
                    if (autoFollow && d.step) refreshStepContent(d.step);
                }
                if (d.step==='CANCELLED'||d.status==='cancelled') updateStatusBar('已暂停');
            } else if (evtName === 'question') {
                try {
                    var d = (typeof raw === 'string') ? JSON.parse(raw) : raw;
                    showQuestion(d.questionId, d.question, d.options, d.multiSelect);
                } catch(ex) { showQuestion('', raw, null, false); }
            } else if (evtName === 'complete') {
                appendLog(timeNow(), '\u2713 工作流完成', 'log-completed');
                updateStepIndicator('COMPLETE');
                updateStatusBar('完成');
                document.getElementById('status-progress-fill').style.width = '100%';
                document.getElementById('status-pct').textContent = '100%';
                hidePauseButton();
                document.getElementById('btn-start').textContent = '&#9654; 重新启动';
                document.getElementById('btn-start').disabled = false;
            } else if (evtName === 'error') {
                appendLog(timeNow(), '\u2717 ' + (typeof raw === 'string' ? raw : '错误'), 'log-error');
            }
        } catch(_) {}
    });

    // 启动工作流
    fetch('/api/workflow/projects/' + pid + '/start', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.code !== 0) {
                showToast(data.message || '启动失败', 'error');
            } else {
                showPauseButton();
                appendLog(timeNow(), '引擎已启动', 'log-completed');
                document.getElementById('status-step').textContent = '运行中';
            }
        })
        .catch(function(err) {
            appendLog(timeNow(), '启动失败: ' + err.message, 'log-error');
            showToast('启动失败: ' + err.message, 'error');
        });
};

function updateStatusBar(msg) {
    document.getElementById('status-step').textContent = msg || '就绪';
    var now = new Date();
    document.getElementById('status-time').textContent = now.toLocaleTimeString('zh-CN', {hour12:false});
}

function showPauseButton() {
    document.getElementById('btn-pause').style.display = '';
    var btn = document.getElementById('btn-start');
    btn.textContent = '&#9654; 运行中...';
    btn.disabled = true;
}

function hidePauseButton() {
    document.getElementById('btn-pause').style.display = 'none';
}

function pauseWorkflow(pid) {
    if (!confirm('确定暂停？完成当前步骤后才会暂停。')) return;
    hidePauseButton();
    updateStatusBar('正在暂停...');
    fetch('/api/workflow/projects/' + pid + '/cancel', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(d) {
            if (d.code === 0) {
                showToast('暂停中...', 'success');
                var btn = document.getElementById('btn-start');
                btn.textContent = '&#9654; 继续';
                btn.disabled = false;
            } else {
                showToast(d.message || '暂停失败', 'error');
                showPauseButton();
            }
        })
        .catch(function(err) { showToast('请求失败', 'error'); showPauseButton(); });
}

// ============ Content Loading ============
function loadRequirementContent() {
    fetch('/api/projects/' + projectId + '/requirement')
        .then(function(res) { return res.json(); })
        .then(function(d) {
            if (d.code === 0 && d.data && d.data.summaryContent) {
                var el = document.getElementById('content-requirement');
                var html = renderMarkdown(d.data.summaryContent);
                el.innerHTML = html;
                openTab('requirement', '&#128269;', '需求调研', false);
                updateStepIndicator('REQUIREMENT_GATHERING');
            }
        })
        .catch(function() {});
}

function loadSearchCharacterContent() {
    fetch('/api/projects/' + projectId + '/search-character')
        .then(function(res) { return res.json(); })
        .then(function(d) {
            if (d.code === 0 && d.data) {
                var el = document.getElementById('content-search');
                var html = '<h3>&#128300; 信息检索与人设分析</h3>';
                if (d.data.searchContent) {
                    html += '<h4>&#127760; 联网搜索结果</h4>';
                    html += '<div style="margin-bottom:1.5rem;">' + renderMarkdown(d.data.searchContent) + '</div>';
                }
                if (d.data.characterContent) {
                    html += '<h4>&#128100; 人设分析</h4>';
                    html += '<div>' + renderMarkdown(d.data.characterContent) + '</div>';
                }
                if (!d.data.searchContent && !d.data.characterContent) {
                    html += '<p style="color:var(--muted)">暂无检索结果，请等待工作流执行至此步骤。</p>';
                }
                el.innerHTML = html;
                openTab('search', '&#128300;', '信息检索', false);
                updateStepIndicator('SEARCH_AND_CHARACTER');
            }
        })
        .catch(function() {});
}

function loadOutlinesInline() {
    fetch('/api/projects/' + projectId + '/outlines')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.code === 0 && data.data && data.data.length > 0) {
                var outlines = data.data;
                for (var i = 0; i < outlines.length; i++) {
                    if (outlines[i].selected) selectedOutlineId = outlines[i].id;
                }
                var el = document.getElementById('content-outline');
                var html = '<h3>&#128214; 大纲方案</h3>';
                outlines.forEach(function(o, idx) {
                    var versionLabel = ['A', 'B', 'C'][idx] || (idx + 1);
                    var sel = o.selected ? ' selected' : '';
                    if (o.selected && !selectedOutlineId) selectedOutlineId = o.id;
                    html += '<div class="outline-mini' + sel + '" data-id="' + o.id + '" onclick="pickOutline(' + o.id + ', this)">' +
                        '<h4>大纲 ' + versionLabel + ': ' + escapeHtml(o.title || '未命名') + '</h4>';
                    if (o.summary) html += '<div style="font-size:0.85rem;color:var(--muted);">' + escapeHtml(o.summary).substring(0, 200) + '...</div>';
                    if (o.coreConflict) html += '<div class="outline-meta-sm">&#9881; ' + escapeHtml(o.coreConflict).substring(0, 100) + '</div>';
                    html += '</div>';
                });
                el.innerHTML = html;
                openTab('outline', '&#128214;', '大纲', false);
                updateStepIndicator('OUTLINE_DESIGN');
            }
        })
        .catch(function() {});
}

var _pickingOutline = false;
function pickOutline(id, el) {
    if (_pickingOutline) return;
    _pickingOutline = true;
    document.querySelectorAll('#content-outline .outline-mini').forEach(function(c) { c.classList.remove('selected'); });
    el.classList.add('selected');
    selectedOutlineId = id;
    fetch('/api/projects/' + projectId + '/outlines/select', {
        method: 'POST',
        headers: {'Content-Type':'application/json'},
        body: JSON.stringify({selectedOutlineId: id})
    })
    .then(function(res) { return res.json(); })
    .then(function(d) {
        if (d.code === 0) { showToast('大纲已选择', 'success'); }
        else { showToast(d.message || '失败', 'error'); }
    })
    .catch(function() {})
    .finally(function() { _pickingOutline = false; });
}

function loadScriptContent() {
    fetch('/api/projects/' + projectId + '/script')
        .then(function(res) { return res.json(); })
        .then(function(d) {
            if (d.code === 0 && d.data && d.data.fullScript) {
                var el = document.getElementById('content-script');
                el.innerHTML = renderMarkdown(d.data.fullScript);
                openTab('script', '&#127917;', '剧本', false);
                updateStepIndicator('SCRIPT_GENERATION');
            }
        })
        .catch(function() {});
}

function loadReviewContent() {
    fetch('/api/projects/' + projectId + '/review')
        .then(function(res) { return res.json(); })
        .then(function(d) {
            if (d.code === 0 && d.data) {
                var el = document.getElementById('content-review');
                var r = d.data;
                var html = '<h3>&#128202; 审核报告</h3>';
                if (r.overallScore != null) {
                    html += '<p><strong>总分:</strong> <span style="color:var(--success);font-size:1.2rem;">' + r.overallScore + '</span></p>';
                }
                if (r.summary) {
                    html += '<div>' + renderMarkdown(r.summary) + '</div>';
                }
                el.innerHTML = html;
                openTab('review', '&#128202;', '审核', false);
                updateStepIndicator('QUALITY_REVIEW');
            }
        })
        .catch(function() {});
}

// 从数据库恢复所有已保存的内容（首页加载 / 刷新时使用）
function loadAllSavedContent(step) {
    loadRequirementContent();
    loadSearchCharacterContent();
    // 大纲只在到达对应步骤时才加载
    if (step && (step.indexOf('OUTLINE') >= 0 || step.indexOf('SCRIPT') >= 0
            || step.indexOf('REVIEW') >= 0 || step === 'DONE' || step === 'COMPLETE')) {
        loadOutlinesInline();
    }
    if (step && (step.indexOf('SCRIPT') >= 0 || step.indexOf('REVIEW') >= 0
            || step === 'DONE' || step === 'COMPLETE')) {
        loadScriptContent();
    }
    if (step && (step.indexOf('REVIEW') >= 0 || step === 'DONE' || step === 'COMPLETE')) {
        loadReviewContent();
    }
    // 所有 tab 创建后，切到当前步骤对应的 tab
    switchToStepTab(step);
}

// 根据步骤切换到对应 tab（用于刷新后恢复）
function switchToStepTab(step) {
    var activeTabId = mapStepToTab(step);
    if (!activeTabId) { switchTab('welcome'); return; }
    var tab = document.querySelector('#editor-tabs [data-tab="' + activeTabId + '"]');
    if (tab) switchTab(activeTabId);
    else {
        // tab 还没创建（异步加载中），延迟再试
        setTimeout(function() {
            var retry = document.querySelector('#editor-tabs [data-tab="' + activeTabId + '"]');
            if (retry) switchTab(activeTabId);
        }, 1500);
    }
}

function refreshStepContent(step) {
    if (!step) return;
    if (step.indexOf('REQUIREMENT') >= 0) { loadRequirementContent(); switchTab('requirement'); }
    else if (step.indexOf('SEARCH') >= 0 || step.indexOf('CHARACTER') >= 0) { loadSearchCharacterContent(); switchTab('search'); }
    else if (step.indexOf('OUTLINE') >= 0 && step.indexOf('SELECT') < 0) { loadOutlinesInline(); switchTab('outline'); }
    else if (step.indexOf('SCRIPT') >= 0) { loadScriptContent(); switchTab('script'); }
    else if (step.indexOf('REVIEW') >= 0) { loadReviewContent(); switchTab('review'); }
    else if (step === 'COMPLETE' || step === 'DONE') {
        openTab('complete', '&#127881;', '完成');
        updateStepIndicator('COMPLETE');
    }
}

function updateStepIndicator(step) {
    currentStep = step;
    // 更新文件树文件夹状态指示
    var folders = document.querySelectorAll('#file-tree .tree-folder[data-step]');
    folders.forEach(function(folder) {
        var header = folder.querySelector('.tree-folder-header');
        header.classList.remove('active', 'completed', 'running');
        var folderStep = folder.dataset.step;
        if (step === 'COMPLETE' || step === 'DONE') {
            header.classList.add('completed');
            folder.classList.remove('collapsed');
        } else if (folderStep === step) {
            header.classList.add('active');
            folder.classList.remove('collapsed');
        } else if (step && step.indexOf(folderStep) >= 0) {
            header.classList.add('running');
        }
    });

    // 当 steps 都完成时也取消 active
    if (step === 'COMPLETE' || step === 'DONE') {
        document.querySelectorAll('#file-tree .tree-home').forEach(function(h) { h.classList.remove('active'); });
        document.querySelectorAll('#file-tree .tree-file').forEach(function(f) { f.classList.remove('active'); });
    }

    updateProgressFromStep(step);
    document.getElementById('toolbar-step-info').textContent = getStepLabel(step);
    updateStatusBar(getStepLabel(step));
}

function updateProgressFromStep(step) {
    var steps = ['REQUIREMENT_GATHERING','SEARCH_AND_CHARACTER','OUTLINE_DESIGN','OUTLINE_SELECT','SCRIPT_GENERATION','QUALITY_REVIEW','EXPORT','COMPLETE'];
    var idx = steps.indexOf(step);
    var pct = 0;
    if (idx >= 0) pct = Math.round(((idx + 1) / steps.length) * 100);
    else if (step) {
        for (var i = 0; i < steps.length; i++) {
            if (step.indexOf(steps[i]) >= 0) { pct = Math.round(((i + 1) / steps.length) * 100); break; }
        }
    }
    document.getElementById('status-progress-fill').style.width = pct + '%';
    document.getElementById('status-pct').textContent = pct + '%';
}

// ============ Chat Panel (ScriptForge Solo Agent) ============
var chatWs = null;
var chatThinkingSection = null;   // 思考区域容器
var chatThinkingContent = null;   // 思考文本气泡（流式追加）
var chatStreamingEl = null;       // 当前流式回复气泡
var chatMsgIndex = 0;             // 消息序号（用于可折叠的区域标识）
var chatHistoryTimer = null;      // loadChatHistory 延迟定时器
var currentSessionId = null;      // 当前会话 ID
var _chatSessionReady = false;    // 会话是否已初始化完成（防止竞态）

function toggleChat() {
    var panel = document.getElementById('ide-chat');
    var btn = document.getElementById('btn-chat-toggle');
    var isOpen = !panel.classList.contains('collapsed');
    if (isOpen) {
        panel.classList.add('collapsed');
        btn.classList.remove('active');
    } else {
        panel.classList.remove('collapsed');
        btn.classList.add('active');
        document.getElementById('chat-input').focus();
    }
}

function sendChat() {
    var input = document.getElementById('chat-input');
    var msg = input.value.trim();
    if (!msg) return;

    // 如果会话还没初始化完成，提示等待
    if (!_chatSessionReady) {
        alert('会话正在初始化，请稍后再试...');
        return;
    }
    input.value = '';
    input.disabled = true;
    document.getElementById('btn-chat-send').disabled = true;
    chatMsgIndex = Date.now();

    // 取消待执行的 loadChatHistory，防止它覆盖当前轮次的 DOM
    if (chatHistoryTimer) { clearTimeout(chatHistoryTimer); chatHistoryTimer = null; }

    // 添加用户消息
    appendChatMessage('user', msg);
    var emptyEl = document.querySelector('#chat-messages .chat-empty');
    if (emptyEl) emptyEl.remove();

    // 清除旧思考区域 DOM（避免残留 collapsed / think-done 污染下轮）
    var oldSections = document.querySelectorAll('#chat-messages .think-section');
    for (var i = 0; i < oldSections.length; i++) { oldSections[i].remove(); }

    // 重置状态
    chatThinkingSection = null;
    chatThinkingContent = null;
    chatStreamingEl = null;

    if (!chatWs || chatWs.readyState !== WebSocket.OPEN) {
        var proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        chatWs = new WebSocket(proto + '//' + window.location.host + '/ws/projects/' + projectId);
        chatWs.onmessage = function(e) {
            try {
                var msg = JSON.parse(e.data);
                if (msg.event !== 'chat') return;
                var d = (typeof msg.data === 'string') ? JSON.parse(msg.data) : msg.data;
                handleChatEvent(d);
            } catch(_) {}
        };
        chatWs.onopen = function() {
            chatWs.send(JSON.stringify({type: 'chat', message: msg, sessionId: currentSessionId}));
        };
    } else {
        chatWs.send(JSON.stringify({type: 'chat', message: msg, sessionId: currentSessionId}));
    }
}

function handleChatEvent(d) {
    switch (d.type) {
        case 'think_start':
            // 防御：如果已有思考区域，先移除
            if (chatThinkingSection) {
                chatThinkingSection.remove();
                chatThinkingSection = null;
            }
            var stale = document.querySelector('#chat-messages .think-section');
            if (stale) stale.remove();
            // 创建新思考区域（可折叠）
            chatThinkingSection = createThinkingSection();
            chatThinkingContent = null;
            break;

        case 'think_chunk':
            // 如果引用已脱离 DOM（被 loadChatHistory 覆盖），重置并重建
            if (chatThinkingSection && !chatThinkingSection.parentNode) {
                chatThinkingSection = null;
                chatThinkingContent = null;
            }
            // 确保思考区域存在
            if (!chatThinkingSection) chatThinkingSection = createThinkingSection();
            // 获取或创建思考文本气泡
            if (!chatThinkingContent) {
                chatThinkingContent = document.createElement('div');
                chatThinkingContent.className = 'chat-msg thinking';
                chatThinkingContent.innerHTML = '';
                chatThinkingSection.querySelector('.think-body').appendChild(chatThinkingContent);
            }
            // 追加思考文本
            var chunk = escapeHtml(d.content || '');
            chunk = chunk.replace(/\n/g, '<br>');
            chatThinkingContent.innerHTML += chunk;
            scrollChatBottom();
            break;

        case 'think_end':
            // 思考完成：保持展开，显示可查看提示
            if (chatThinkingSection && chatThinkingSection.parentNode) {
                chatThinkingSection.classList.add('think-done');
                chatThinkingSection.querySelector('.think-header').innerHTML =
                    '<span class="think-dot"></span>思考完成 <span style="font-size:0.65rem;opacity:0.5;">点击查看/折叠</span> <span class="think-toggle">&#9660;</span>';
                chatThinkingContent = null;
            }
            break;

        case 'tool_call':
            // 工具调用中
            appendToolAction(d.content || '');
            break;

        case 'tool_result':
            // 工具结果（紧凑显示）
            appendToolResult(d.content || '');
            break;

        case 'navigate':
            // Agent 通知前端导航到指定文件
            handleNavigateEvent(d.content || '');
            break;

        case 'reply_chunk':
            // 流式回复
            if (!chatStreamingEl) {
                chatStreamingEl = appendChatMessage('agent', '');
            }
            var rc = escapeHtml(d.content || '');
            rc = rc.replace(/\n/g, '<br>')
                .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
                .replace(/`([^`]+)`/g, '<code>$1</code>');
            chatStreamingEl.innerHTML += rc;
            scrollChatBottom();
            break;

        case 'reply':
            chatStreamingEl = null;
            appendChatMessage('agent', d.content || '');
            break;

        case 'done':
            chatStreamingEl = null;
            document.getElementById('chat-input').disabled = false;
            document.getElementById('btn-chat-send').disabled = false;
            document.getElementById('chat-input').focus();
            // 静默更新消息缓存（不刷新 DOM，避免思考内容/回复消失）
            chatHistoryTimer = setTimeout(function() {
                var url = '/api/projects/' + projectId + '/chat/history';
                if (currentSessionId != null) url += '?sessionId=' + currentSessionId;
                fetch(url)
                    .then(function(r) { return r.json(); })
                    .then(function(resp) {
                        _allMessagesCache = resp.data || [];
                    })
                    .catch(function() {});
            }, 500);
            break;

        case 'error':
            if (chatThinkingSection) { chatThinkingSection.remove(); chatThinkingSection = null; }
            chatStreamingEl = null;
            appendChatMessage('error', d.content || '出错了');
            document.getElementById('chat-input').disabled = false;
            document.getElementById('btn-chat-send').disabled = false;
            break;
    }
}

/* ---------- 思考区域（可折叠卡片） ---------- */
function createThinkingSection() {
    var container = document.getElementById('chat-messages');
    var idx = ++chatMsgIndex;
    var sec = document.createElement('div');
    sec.className = 'think-section';
    sec.innerHTML = '<div class="think-header" onclick="this.parentElement.classList.toggle(\'collapsed\')">'
        + '<span class="think-dot"></span>思考中...</div>'
        + '<div class="think-body"></div>';
    container.appendChild(sec);
    scrollChatBottom();
    return sec;
}

/* ---------- 工具调用（紧凑任务列表） ---------- */
var currentToolList = null;
var currentToolCount = 0;

function ensureToolList() {
    if (!currentToolList || !currentToolList.parentNode) {
        currentToolList = document.createElement('div');
        currentToolList.className = 'tool-call-list';
        document.getElementById('chat-messages').appendChild(currentToolList);
        currentToolCount = 0;
    }
    return currentToolList;
}

function appendToolAction(raw) {
    var name, filePath;
    if (typeof raw === 'string' && raw.trim().startsWith('{')) {
        try {
            var j = JSON.parse(raw);
            name = j.toolName || '';
            filePath = j.filePath || '';
        } catch (e) {
            var parts = (raw || '').split('|');
            name = parts[0] || '';
        }
    } else {
        var parts = (raw || '').split('|');
        name = parts[0] || '';
    }
    var friendly = toolFriendlyName(name);
    // 如果有文件路径，显示文件名
    var displayLabel = friendly;
    if (filePath) {
        displayLabel = '<span style="color:var(--primary-light);">&#9998; ' + escapeHtml(friendly) + '</span>'
            + ' <span style="color:var(--muted);font-size:0.7rem;">' + escapeHtml(filePath) + '</span>';
    }
    var list = ensureToolList();
    currentToolCount++;
    var item = document.createElement('div');
    item.className = 'tool-call-item running';
    item.setAttribute('data-tool-name', name);
    item.innerHTML = '<span class="tc-badge">&#9679;</span>'
        + '<span class="tc-name">' + (filePath ? displayLabel : escapeHtml(friendly)) + '</span>';
    list.appendChild(item);
    scrollChatBottom();
}

function appendToolResult(raw) {
    var name, filePath, result;
    if (typeof raw === 'string' && raw.trim().startsWith('{')) {
        try {
            var j = JSON.parse(raw);
            name = j.toolName || '';
            filePath = j.filePath || '';
            result = j.result || '';
        } catch (e) {
            var parts = (raw || '').split('|');
            name = parts[0] || '';
            result = parts.slice(1).join('|') || '';
        }
    } else {
        var parts = (raw || '').split('|');
        name = parts[0] || '';
        result = parts.slice(1).join('|') || '';
    }
    var friendly = toolFriendlyName(name);
    var list = ensureToolList();
    var items = list.querySelectorAll('.tool-call-item.running');
    var targetItem = null;
    for (var i = 0; i < items.length; i++) {
        if (items[i].getAttribute('data-tool-name') === name) {
            targetItem = items[i];
            break;
        }
    }
    if (targetItem) {
        targetItem.classList.remove('running');
        targetItem.classList.add('done');
        targetItem.querySelector('.tc-badge').innerHTML = '&#10003;';
        if (result && result.trim()) {
            var btn = document.createElement('span');
            btn.className = 'tc-expand';
            btn.textContent = '\u25b6';
            btn.onclick = function(e) { e.stopPropagation(); toggleToolDetail(targetItem); };
            targetItem.appendChild(btn);
            var detail = document.createElement('div');
            detail.className = 'tool-call-detail';
            detail.textContent = result.length > 500 ? result.substring(0,500)+' ...' : result;
            targetItem.appendChild(detail);
        }
    } else {
        var item = document.createElement('div');
        item.className = 'tool-call-item done';
        item.setAttribute('data-tool-name', name);
        item.innerHTML = '<span class="tc-badge">&#10003;</span>'
            + '<span class="tc-name">' + escapeHtml(friendly) + '</span>';
        list.appendChild(item);
    }
    scrollChatBottom();
}

function toggleToolDetail(item) {
    var detail = item.querySelector('.tool-call-detail');
    if (!detail) return;
    var showing = detail.style.display === 'block';
    detail.style.display = showing ? 'none' : 'block';
    var btn = item.querySelector('.tc-expand');
    if (btn) btn.textContent = showing ? '\u25b6' : '\u25bc';
}

function finishToolRound() {
    currentToolList = null;
    currentToolCount = 0;
}

function toolFriendlyName(name) {
    var map = {
        'create_project': '创建项目', 'list_projects': '列出项目',
        'get_project_status': '查询项目状态', 'start_workflow': '启动工作流',
        'delete_project': '删除项目',
        'call_search_agent': '联网搜索', 'call_character_agent': '角色检索',
        'call_requirement_agent': '需求调研', 'call_outline_agent': '大纲设计',
        'call_script_agent': '剧本生成', 'call_review_agent': '质量审核',
        'call_question_agent': '提问用户',
        'read_requirement': '读取需求', 'read_outline': '读取大纲',
        'read_script': '读取剧本', 'read_review': '读取审核',
        'read_search_results': '读取搜索结果', 'list_knowledge': '知识库',
        'list_character_cards': '角色卡',
        'write_file': '写入文件', 'replace_in_file': '修改文件', 'read_file': '读取文件',
        'execute_command': '终端命令', 'search_files': '搜索文件', 'list_files': '列出文件'
    };
    return map[name] || name;
}

/* ---------- 历史记录 / 撤回 / 新对话 ---------- */

// 缓存全量消息（用于历史面板和回退判断）
var _allMessagesCache = [];

/** 加载当前会话的活跃消息到聊天面板 */
function loadChatHistory() {
    var url = '/api/projects/' + projectId + '/chat/history';
    if (currentSessionId != null) {
        url += '?sessionId=' + currentSessionId;
    }
    fetch(url)
        .then(function(r) { return r.json(); })
        .then(function(resp) {
            var data = resp.data || [];
            renderHistory(data);
            // 同时更新全量缓存
            _allMessagesCache = data;
        })
        .catch(function() {});
}

function renderHistory(messages) {
    var container = document.getElementById('chat-messages');
    container.innerHTML = '';

    // 过滤掉会话标记消息，计算真实消息数
    var realMsgs = [];
    for (var i = 0; i < messages.length; i++) {
        if (messages[i].subType !== 'session_start') {
            realMsgs.push(messages[i]);
        }
    }

    if (realMsgs.length === 0) {
        container.innerHTML = '<div class="chat-empty"><div class="chat-empty-icon">&#129302;</div>'
            + '<p>ScriptForge Agent 已就绪</p>'
            + '<p style="font-size:0.7rem;">试试说：帮我创建一个鸣潮的二创项目</p></div>';
        return;
    }
    for (var i = 0; i < realMsgs.length; i++) {
        var m = realMsgs[i];
        var st = m.subType || '';
        if (m.role === 'user') {
            appendChatMessage('user', m.content, m.id, i >= realMsgs.length - 10);
        } else if (st === 'thinking') {
            var sec = document.createElement('div');
            sec.className = 'think-section think-done';
            sec.innerHTML = '<div class="think-header" onclick="this.parentElement.classList.toggle(\'collapsed\')">'
                + '<span class="think-dot"></span>思考完成 <span style="font-size:0.65rem;opacity:0.5;">点击查看/折叠</span> <span class="think-toggle">&#9660;</span></div>'
                + '<div class="think-body"><div class="chat-msg thinking">'
                + renderMarkdown(m.content) + '</div></div>';
            container.appendChild(sec);
        } else if (st === 'tool_call') {
            var name, filePath;
            var raw = m.content || '';
            if (raw.trim().startsWith('{')) {
                try {
                    var j = JSON.parse(raw);
                    name = j.toolName || '';
                    filePath = j.filePath || '';
                } catch (e) {
                    var parts = raw.split('|');
                    name = parts[0] || '';
                }
            } else {
                var parts = raw.split('|');
                name = parts[0] || '';
            }
            var div = document.createElement('div');
            div.className = 'chat-msg tool-action';
            var label = '正在调用: <strong>' + escapeHtml(toolFriendlyName(name)) + '</strong>';
            if (filePath) {
                label += ' <span style="color:var(--muted);font-size:0.7rem;">' + escapeHtml(filePath) + '</span>';
            }
            div.innerHTML = '<span class="tool-icon">&#9881;</span> ' + label;
            container.appendChild(div);
        } else if (st === 'tool_result') {
            renderHistoryToolResult(container, m.content);
        } else if (st === 'reply') {
            appendChatMessage('agent', m.content);
        } else {
            appendChatMessage('agent', m.content);
        }
    }
    scrollChatBottom();
}

function renderHistoryToolResult(container, raw) {
    var name, filePath, result;
    if (typeof raw === 'string' && raw.trim().startsWith('{')) {
        try {
            var j = JSON.parse(raw);
            name = j.toolName || '';
            filePath = j.filePath || '';
            result = j.result || '';
        } catch (e) {
            var parts = (raw || '').split('|');
            name = parts[0] || '';
            result = parts.slice(1).join('|') || '';
        }
    } else {
        var parts = (raw || '').split('|');
        name = parts[0] || '';
        result = parts.slice(1).join('|') || '';
    }
    var friendly = toolFriendlyName(name);
    var idx = ++chatMsgIndex;
    var div = document.createElement('div');
    div.className = 'chat-msg tool-result';
    div.innerHTML = '<div class="tool-result-header" onclick="var b=document.getElementById(\'trb-'+idx+'\');b.style.display=b.style.display===\'none\'?\'\':\'none\'">'
        + '<span class="tool-icon">&#10003;</span> <strong>' + escapeHtml(friendly) + '</strong> 完成'
        + '<span class="tool-expand">&#9660;</span></div>'
        + '<div class="tool-result-body" id="trb-' + idx + '" style="display:none">'
        + escapeHtml(result).replace(/\n/g,'<br>') + '</div>';
    container.appendChild(div);
}

/* ===== 历史会话面板（左侧滑出） ===== */

function toggleHistoryPanel() {
    var panel = document.getElementById('chat-history-panel');
    var backdrop = document.getElementById('chat-history-backdrop');
    var isOpen = panel.classList.contains('open');
    if (isOpen) {
        panel.classList.remove('open');
        backdrop.classList.remove('open');
    } else {
        loadFullHistory();
        panel.classList.add('open');
        backdrop.classList.add('open');
    }
}

/** 加载所有会话（历史面板） */
function loadFullHistory() {
    fetch('/api/projects/' + projectId + '/chat/sessions')
        .then(function(r) { return r.json(); })
        .then(function(resp) {
            var sessions = resp.data || [];
            renderSessionEntries(sessions);
            // 同时缓存当前会话的消息用于回退
            if (currentSessionId != null) {
                var url = '/api/projects/' + projectId + '/chat/history?sessionId=' + currentSessionId;
                fetch(url)
                    .then(function(r) { return r.json(); })
                    .then(function(resp2) {
                        _allMessagesCache = resp2.data || [];
                    });
            }
        })
        .catch(function() {});
}

/** 渲染会话列表 */
function renderSessionEntries(sessions) {
    var listEl = document.getElementById('history-list');
    listEl.innerHTML = '';

    if (sessions.length === 0) {
        listEl.innerHTML = '<div class="history-empty">暂无历史会话</div>';
        return;
    }

    for (var i = 0; i < sessions.length; i++) {
        var s = sessions[i];
        var entry = document.createElement('div');
        entry.className = 'history-entry';
        if (s.sessionId === currentSessionId) {
            entry.classList.add('active');
        }

        var preview = (s.preview || '（空会话）');
        var time = s.createdAt ? formatHistoryTime(s.createdAt) : '';
        var count = s.messageCount || 0;

        entry.innerHTML = '<span class="h-num">' + (i + 1) + '</span>'
            + '<div class="h-body">'
            + '<div class="h-preview">' + escapeHtml(preview) + '</div>'
            + '<div class="h-time">' + time + ' &middot; ' + count + ' 条消息</div>'
            + '</div>'
            + '<button class="h-delete" title="删除此会话（不可恢复）">&#10005;</button>';

        // 点击切换到该会话
        entry.addEventListener('click', function(sid, evt) {
            if (evt.target.classList.contains('h-delete')) return;
            loadSession(sid);
            toggleHistoryPanel();
        }.bind(null, s.sessionId));

        // 删除按钮
        var delBtn = entry.querySelector('.h-delete');
        delBtn.addEventListener('click', function(sid, e) {
            e.stopPropagation();
            deleteSession(sid);
        }.bind(null, s.sessionId));

        listEl.appendChild(entry);
    }
}

/** 切换到指定会话 */
function loadSession(sessionId) {
    if (sessionId === currentSessionId) return;
    currentSessionId = sessionId;
    try { sessionStorage.setItem('scriptforge_last_session_' + projectId, sessionId); } catch(e) {}
    _chatSessionReady = true;
    fetch('/api/projects/' + projectId + '/chat/history?sessionId=' + sessionId)
        .then(function(r) { return r.json(); })
        .then(function(resp) {
            var messages = resp.data || [];
            renderHistory(messages);
            _allMessagesCache = messages;
            resetChatInput();
        })
        .catch(function() {});
}

/** 删除指定会话及其所有消息（不可恢复） */
function deleteSession(sessionId) {
    if (!confirm('确定删除此会话及其所有对话吗？此操作不可恢复！')) return;
    fetch('/api/projects/' + projectId + '/chat/session/' + encodeURIComponent(sessionId),
        { method: 'DELETE' })
        .then(function(r) { return r.json(); })
        .then(function() {
            // 如果删除的是当前会话，切换到最新会话
            if (sessionId === currentSessionId) {
                currentSessionId = null;
                loadChatHistory();
            }
            loadFullHistory();
            resetChatInput();
        });
}

function formatHistoryTime(dateStr) {
    if (!dateStr) return '';
    try {
        var d = new Date(dateStr);
        var now = new Date();
        var diffMs = now - d;
        var diffMin = Math.floor(diffMs / 60000);
        if (diffMin < 1) return '刚刚';
        if (diffMin < 60) return diffMin + ' 分钟前';
        var diffHr = Math.floor(diffMin / 60);
        if (diffHr < 24) return diffHr + ' 小时前';
        return d.toLocaleDateString('zh-CN', {month:'short', day:'numeric'})
            + ' ' + d.toLocaleTimeString('zh-CN', {hour:'2-digit', minute:'2-digit'});
    } catch(e) { return dateStr; }
}

/* ===== 回退功能（软删除 + 确认弹窗） ===== */

var _pendingRevertMsgId = null;

/** 显示回退确认弹窗 */
function showRevertDialog(msgId) {
    if (!msgId) return;
    _pendingRevertMsgId = msgId;

    // 计算受影响范围
    var affected = [];
    var found = false;
    for (var i = 0; i < _allMessagesCache.length; i++) {
        if (_allMessagesCache[i].id === msgId) found = true;
        if (found) affected.push(_allMessagesCache[i]);
    }

    var listEl = document.getElementById('rm-affected-list');
    listEl.innerHTML = '';
    if (affected.length === 0) {
        listEl.innerHTML = '<div class="rm-item" style="color:var(--muted)">（无详细记录）</div>';
    } else {
        var shown = Math.min(affected.length, 6);
        for (var j = 0; j < shown; j++) {
            var m = affected[j];
            var roleLabel = m.role === 'user' ? '你' : 'Agent';
            var preview = (m.content || '').substring(0, 40);
            if (m.content && m.content.length > 40) preview += '...';
            listEl.innerHTML += '<div class="rm-item"><strong>' + roleLabel + ':</strong> '
                + escapeHtml(preview) + '</div>';
        }
        if (affected.length > 6) {
            listEl.innerHTML += '<div class="rm-item" style="color:var(--muted)">...及其他 '
                + (affected.length - 6) + ' 条消息</div>';
        }
    }

    document.getElementById('revert-modal-backdrop').classList.add('open');
}

function closeRevertModal() {
    document.getElementById('revert-modal-backdrop').classList.remove('open');
    _pendingRevertMsgId = null;
}

function confirmRevert() {
    var msgId = _pendingRevertMsgId;
    if (!msgId) return;
    closeRevertModal();
    fetch('/api/projects/' + projectId + '/chat/undo/' + encodeURIComponent(msgId),
        { method: 'DELETE' })
        .then(function(r) { return r.json(); })
        .then(function() {
            loadChatHistory();
            resetChatInput();
        });
}

/** 恢复所有被撤回的消息 */
function restoreChat() {
    if (!confirm('恢复所有已撤回的消息？')) return;
    fetch('/api/projects/' + projectId + '/chat/restore', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function() { loadChatHistory(); });
}

function resetChatInput() {
    document.getElementById('chat-input').disabled = false;
    document.getElementById('btn-chat-send').disabled = false;
    chatStreamingEl = null;
}

var _newChatting = false;
function newChat() {
    if (_newChatting) return;
    if (!confirm('确定要开始新对话吗？当前对话将保留在历史中。')) return;
    _newChatting = true;
    fetch('/api/projects/' + projectId + '/chat/new-session', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(resp) {
            var sid = resp.data.sessionId;
            currentSessionId = sid;
            _chatSessionReady = true;
            _allMessagesCache = [];
            var container = document.getElementById('chat-messages');
            container.innerHTML = '<div class="chat-empty"><div class="chat-empty-icon">&#129302;</div>'
                + '<p>ScriptForge Agent 已就绪</p>'
                + '<p style="font-size:0.7rem;">试试说：帮我创建一个鸣潮的二创项目</p></div>';
            resetChatInput();
            // 如果历史面板开着，刷新它
            var histPanel = document.getElementById('chat-history-panel');
            if (histPanel.classList.contains('open')) {
                loadFullHistory();
            }
        })
        .finally(function() { _newChatting = false; });
}

// 打开聊天面板时初始化会话（仅 open 时触发一次）
var _chatSessionInited = false;
var _origToggleChat = toggleChat;
toggleChat = function() {
    _origToggleChat();
    var panel = document.getElementById('ide-chat');
    if (!panel.classList.contains('collapsed') && !_chatSessionInited) {
        _chatSessionInited = true;
        _chatSessionReady = false;  // 初始化中，阻止发送
        // 先尝试从 sessionStorage 恢复上次的会话ID（刷新页面后保持）
        var savedSid = sessionStorage.getItem('scriptforge_last_session_' + projectId);
        if (savedSid != null) {
            currentSessionId = parseInt(savedSid) || null;
        }
        // 获取会话列表，自动选择最新会话
        fetch('/api/projects/' + projectId + '/chat/sessions')
            .then(function(r) { return r.json(); })
            .then(function(resp) {
                var sessions = resp.data || [];
                if (sessions.length > 0) {
                    // 优先使用 sessionStorage 保存的会话，其次用最新会话
                    if (currentSessionId != null) {
                        // 验证保存的会话是否还存在
                        var found = sessions.some(function(s) { return s.sessionId === currentSessionId; });
                        if (!found) currentSessionId = null;
                    }
                    if (currentSessionId == null) {
                        var latest = sessions[sessions.length - 1];
                        currentSessionId = latest.sessionId;
                    }
                    try { sessionStorage.setItem('scriptforge_last_session_' + projectId, currentSessionId); } catch(e) {}
                }
                if (currentSessionId == null) {
                    // 没有任何会话，创建一个
                    return fetch('/api/projects/' + projectId + '/chat/new-session', { method: 'POST' })
                        .then(function(r) { return r.json(); })
                        .then(function(resp2) {
                            currentSessionId = resp2.data.sessionId;
                        });
                }
            })
            .then(function() {
                _chatSessionReady = true;  // 初始化完成，允许发送
                loadChatHistory();
            })
            .catch(function() {
                // 初始化失败时，创建默认会话并允许发送
                currentSessionId = currentSessionId == null ? 1 : currentSessionId;
                _chatSessionReady = true;
                loadChatHistory();
            });
    }
    if (panel.classList.contains('collapsed')) {
        _chatSessionInited = false;
        _chatSessionReady = false;
    }
};

/* ---------- 通用 ---------- */
function appendChatMessage(type, content, msgId, showRevert) {
    var container = document.getElementById('chat-messages');
    var div = document.createElement('div');
    div.className = 'chat-msg ' + type;

    // Agent/reply 消息使用完整 Markdown 渲染；用户消息保持简单
    if (type === 'agent' || type === 'reply') {
        div.innerHTML = renderMarkdown(content);
    } else {
        var html = escapeHtml(content)
            .replace(/\n/g, '<br>')
            .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
            .replace(/`([^`]+)`/g, '<code>$1</code>');
        div.innerHTML = html;
    }

    // 用户消息：左侧挂载回退按钮（仅最近 10 轮显示）
    if (type === 'user' && msgId && showRevert !== false) {
        var btn = document.createElement('button');
        btn.className = 'chat-revert-btn';
        btn.title = '回退到此';
        btn.innerHTML = '&#8630;';
        btn.onclick = function(e) { e.stopPropagation(); showRevertDialog(msgId); };
        div.appendChild(btn);
    }

    container.appendChild(div);
    scrollChatBottom();

    // 如果有 mermaid 图表，触发渲染
    renderMermaidBlocks(div);

    return div;
}

/** 渲染容器内的 mermaid 图表块 */
function renderMermaidBlocks(container) {
    var mermaidEls = container.querySelectorAll('.mermaid');
    if (mermaidEls.length === 0) return;
    for (var i = 0; i < mermaidEls.length; i++) {
        var el = mermaidEls[i];
        if (el.getAttribute('data-processed')) continue;
        el.setAttribute('data-processed', '1');
        try {
            (function(el) {
                mermaid.render('mermaid-' + Date.now() + '-' + i, el.textContent)
                    .then(function(result) {
                        el.innerHTML = result.svg;
                    })
                    .catch(function() {
                        el.innerHTML = '<pre style="color:var(--danger);font-size:0.75rem;">Mermaid 渲染失败</pre>';
                    });
            })(el);
        } catch(e) {
            el.innerHTML = '<pre style="color:var(--danger);font-size:0.75rem;">' + e.message + '</pre>';
        }
    }
}

function scrollChatBottom() {
    var c = document.getElementById('chat-messages');
    c.scrollTop = c.scrollHeight;
}

if (typeof escapeHtml === 'undefined') {
    function escapeHtml(str) {
        if (!str) return '';
        return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }
}

// ============ Log Panel ============
function clearLog() {
    document.getElementById('panel-body').innerHTML = '<div class="log-entry log-info"><span class="log-time">--:--</span>日志已清除</div>';
}

function appendLog(time, msg, cls) {
    var el = document.getElementById('panel-body');
    var entry = document.createElement('div');
    entry.className = 'log-entry ' + (cls || 'log-info');
    entry.innerHTML = '<span class="log-time">' + time + '</span>' + msg;
    el.appendChild(entry);
    if (autoScroll) el.scrollTop = el.scrollHeight;
    // Auto-open panel
    document.getElementById('ide-panel').classList.add('open');
}

// ============ Sidebar Toggle ============
function toggleSidebar() {
    document.getElementById('ide-sidebar').classList.toggle('collapsed');
}

// ============ Question (inline cards) ============
var currentQuestionId = null;
var selectedOptions = [];
var activeQuestionCard = null;

function showQuestion(qid, qtext, options, multiSelect) {
    currentQuestionId = qid;
    selectedOptions = [];

    // 创建问题卡片
    var card = document.createElement('div');
    card.className = 'question-card';
    card.id = 'qc-' + qid;

    var html = '<div class="qc-header">&#128172; Agent 提问</div>';
    html += '<p class="qc-text">' + escapeHtml(qtext) + '</p>';

    // 选项区域
    html += '<div class="qc-options" id="qc-opts-' + qid + '"></div>';

    // 输入行
    html += '<div class="qc-input-row">';
    html += '<input type="text" id="qc-input-' + qid + '" placeholder="输入回答..." onkeydown="if(event.key===\'Enter\')submitAnswer()">';
    html += '<button class="btn btn-primary btn-sm" onclick="submitAnswer()">&#10003; 提交</button>';
    html += '<button class="btn btn-ghost btn-sm" onclick="skipQuestion()">跳过</button>';
    html += '</div>';

    card.innerHTML = html;

    // 插入到编辑器内容区
    var container = document.getElementById('stream-tracker-container');
    container.appendChild(card);
    card.scrollIntoView({behavior:'smooth', block:'center'});
    activeQuestionCard = card;

    // 渲染选项按钮
    var optContainer = document.getElementById('qc-opts-' + qid);
    if (options && options.length > 0) {
        options.forEach(function(opt, idx) {
            var btn = document.createElement('button');
            btn.className = 'btn btn-sm';
            btn.style.cssText = 'border-color:var(--primary-light);color:var(--primary);transition:0.15s;';
            btn.textContent = opt.label;
            if (opt.description) btn.title = opt.description;
            btn.onclick = function() {
                var input = document.getElementById('qc-input-' + qid);
                if (multiSelect) {
                    var selIdx = selectedOptions.indexOf(idx);
                    if (selIdx >= 0) {
                        selectedOptions.splice(selIdx, 1);
                        btn.style.background = '';
                        btn.style.color = 'var(--primary)';
                    } else {
                        selectedOptions.push(idx);
                        btn.style.background = 'var(--primary)';
                        btn.style.color = '#fff';
                    }
                    input.value = selectedOptions.map(function(i) { return options[i].label; }).join(', ');
                } else {
                    input.value = opt.label;
                    submitAnswer();
                }
            };
            optContainer.appendChild(btn);
        });
        if (multiSelect) {
            var tip = document.createElement('span');
            tip.style.cssText = 'font-size:0.75rem;color:var(--muted);margin-left:0.5rem;';
            tip.textContent = '(可多选，选完后点提交)';
            optContainer.appendChild(tip);
        }
    }

    var input = document.getElementById('qc-input-' + qid);
    setTimeout(function() { input.focus(); }, 100);

    appendLog(timeNow(),
              '<span style="color:var(--warning)">[QUESTION] ' + escapeHtml(qtext) + '</span>', 'log-warning');
}

var _submittingAnswer = false;
function submitAnswer() {
    if (_submittingAnswer) return;
    var input = document.getElementById('qc-input-' + currentQuestionId);
    if (!input) return;
    var a = input.value.trim();
    if (!a) { showToast('请输入回答', 'warning'); return; }

    _submittingAnswer = true;
    // 标记卡片为已答
    if (activeQuestionCard) {
        activeQuestionCard.classList.add('answered');
        var qcText = activeQuestionCard.querySelector('.qc-text');
        if (qcText) qcText.textContent = qcText.textContent + ' → ' + a;
        // 禁用输入
        input.disabled = true;
        var btns = activeQuestionCard.querySelectorAll('button');
        btns.forEach(function(b) { b.disabled = true; });
        activeQuestionCard = null;
    }

    fetch('/api/projects/'+projectId+'/answer?questionId='+encodeURIComponent(currentQuestionId||'')+'&answer='+encodeURIComponent(a), {method:'POST'})
        .then(function() { showToast('已提交', 'success'); })
        .catch(function() {})
        .finally(function() { _submittingAnswer = false; });
}

function skipQuestion() {
    if (activeQuestionCard) {
        activeQuestionCard.classList.add('answered');
        var input = document.getElementById('qc-input-' + currentQuestionId);
        if (input) input.disabled = true;
        var btns = activeQuestionCard.querySelectorAll('button');
        btns.forEach(function(b) { b.disabled = true; });
        activeQuestionCard = null;
    }
    fetch('/api/projects/'+projectId+'/answer?questionId='+encodeURIComponent(currentQuestionId||'')+'&answer=%28%E8%B7%B3%E8%BF%87%29', {method:'POST'})
        .then(function() { showToast('已跳过', 'info'); })
        .catch(function() {});
}

// ============ WebSocket 重连（IN_PROGRESS 页面刷新时用）============
connectSSE = function(pid, onStepUpdate) {
    var proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    var ws = new WebSocket(proto + '//' + window.location.host + '/ws/projects/' + pid);
    initStreamTracker('editor-body', ws);
    return ws;
};

function updateProgressFromStatus(evt) {
    var map = {requirement:25, outline:45, script:75, review:100};
    var pct = map[evt.stepId] || 0;
    document.getElementById('status-progress-fill').style.width = pct + '%';
    document.getElementById('status-pct').textContent = pct + '%';
}