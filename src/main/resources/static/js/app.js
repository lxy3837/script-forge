// ScriptForge Frontend App

// ============================================
//  WebSocket Progress Listener (replaces SSE)
// ============================================
function connectWS(projectId, onStepUpdate) {
    var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    var wsUrl = protocol + '//' + window.location.host + '/ws/projects/' + projectId;
    var ws = new WebSocket(wsUrl);
    var logEl = document.getElementById('sse-log');
    var progressEl = document.getElementById('progress-fill');
    var statusEl = document.getElementById('workflow-status');

    ws.onopen = function() {
        console.log('WebSocket connected for project', projectId);
    };

    ws.onmessage = function(e) {
        try {
            var msg = JSON.parse(e.data);
            var eventName = msg.event;
            var eventDataRaw = msg.data;

            switch (eventName) {
                case 'progress':
                    var data = JSON.parse(eventDataRaw);
                    if (logEl) {
                        var entry = document.createElement('div');
                        entry.className = 'log-entry log-info';
                        var timeStr = new Date().toLocaleTimeString('zh-CN', { hour12: false });
                        entry.innerHTML = '<span class="log-time">' + timeStr + '</span><span>' + (data.message || '') + '</span>';
                        logEl.appendChild(entry);
                        logEl.scrollTop = logEl.scrollHeight;
                    }
                    if (progressEl && data.progress != null) progressEl.style.width = data.progress + '%';
                    if (statusEl && data.message) statusEl.textContent = data.message;
                    if (onStepUpdate && data.step) onStepUpdate(data.step);
                    break;

                case 'complete':
                    if (logEl) {
                        var completeEntry = document.createElement('div');
                        completeEntry.className = 'log-entry log-completed';
                        var ctStr = new Date().toLocaleTimeString('zh-CN', { hour12: false });
                        completeEntry.innerHTML = '<span class="log-time">' + ctStr + '</span><span>&#10003; 工作流已完成</span>';
                        logEl.appendChild(completeEntry);
                        logEl.scrollTop = logEl.scrollHeight;
                    }
                    if (progressEl) progressEl.style.width = '100%';
                    if (statusEl) statusEl.textContent = '工作流已完成';
                    if (onStepUpdate) onStepUpdate('COMPLETE');
                    break;

                case 'error':
                    if (logEl) {
                        var errorEntry = document.createElement('div');
                        errorEntry.className = 'log-entry log-error';
                        var etStr = new Date().toLocaleTimeString('zh-CN', { hour12: false });
                        var errMsg = (typeof eventDataRaw === 'string') ? eventDataRaw : '连接错误';
                        errorEntry.innerHTML = '<span class="log-time">' + etStr + '</span><span>&#10007; ' + escapeHtml(errMsg) + '</span>';
                        logEl.appendChild(errorEntry);
                        logEl.scrollTop = logEl.scrollHeight;
                    }
                    break;
            }
        } catch (err) {
            console.error('WebSocket message parse error:', err);
        }
    };

    ws.onerror = function(err) {
        console.error('WebSocket error for project', projectId, err);
    };

    ws.onclose = function() {
        console.log('WebSocket disconnected for project', projectId);
    };

    return ws;
}

/**
 * @deprecated 使用 connectWS 替代，保留此函数用于向后兼容.
 */
function connectSSE(projectId, onStepUpdate) {
    return connectWS(projectId, onStepUpdate);
}

// ============================================
//  Project CRUD
// ============================================
function createProject() {
    const title = document.getElementById('project-title').value.trim();
    const gameName = document.getElementById('project-game').value.trim();
    if (!title || !gameName) { showToast('请填写项目标题和游戏名称', 'warning'); return; }

    fetch('/api/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: title, gameName: gameName })
    })
    .then(res => res.json())
    .then(data => {
        if (data.code === 0) {
            window.location.href = '/projects/' + data.data.id;
        } else {
            showToast(data.message || '创建失败', 'error');
        }
    })
    .catch(err => showToast('创建失败: ' + err.message, 'error'));
}

function loadProject(projectId) {
    fetch('/api/projects/' + projectId)
        .then(res => res.json())
        .then(data => {
            if (data.code === 0) {
                const p = data.data;
                const titleEl = document.getElementById('project-title-display');
                const statusEl = document.getElementById('project-status');
                const gameEl = document.getElementById('project-game');
                const stepEl = document.getElementById('project-step');
                if (titleEl) titleEl.textContent = p.title;
                if (statusEl) statusEl.textContent = p.status;
                if (gameEl) gameEl.textContent = p.gameName;
                if (stepEl) stepEl.textContent = p.currentStep || '未开始';
            }
        })
        .catch(err => console.error('加载项目失败:', err));
}

function loadProjectList() {
    const listEl = document.getElementById('project-list');
    if (!listEl) return;

    listEl.innerHTML = '<div class="skeleton" style="height: 48px; margin-bottom: 0.5rem;"></div>' +
                        '<div class="skeleton" style="height: 48px; margin-bottom: 0.5rem;"></div>' +
                        '<div class="skeleton" style="height: 48px;"></div>';

    fetch('/api/projects')
        .then(res => res.json())
        .then(data => {
            if (data.code === 0 && data.data.length > 0) {
                listEl.innerHTML = '<ul class="project-list">' +
                    data.data.map(p => {
                        const statusClass = 'status-' + (p.status || 'draft').toLowerCase().replace('_', '-');
                        const statusLabel = getStatusLabel(p.status);
                        return '<li class="project-item">' +
                            '<div class="project-info">' +
                                '<h3><span class="text-muted" style="font-size:0.8rem">#' + p.displayOrder + '</span> <a href="/projects/' + p.id + '">' + escapeHtml(p.title) + '</a></h3>' +
                                '<p class="text-sm text-muted">' + escapeHtml(p.gameName) + ' | ' +
                                (p.currentStep ? escapeHtml(p.currentStep) : '未开始') + '</p>' +
                            '</div>' +
                            '<div class="flex gap-1 items-center">' +
                                '<span class="status-badge ' + statusClass + ' badge-dot">' + statusLabel + '</span>' +
                                '<button class="btn btn-ghost btn-xs" style="color:var(--danger)" onclick="deleteProject(' + p.id + ')" title="删除">&#10005;</button>' +
                            '</div>' +
                        '</li>';
                    }).join('') + '</ul>';
            } else {
                listEl.innerHTML = '<div class="empty-state">' +
                    '<div class="empty-icon">&#128218;</div>' +
                    '<p>暂无项目</p>' +
                    '<p class="text-sm text-muted">请创建一个新项目开始二创之旅！</p>' +
                '</div>';
            }
        })
        .catch(err => {
            listEl.innerHTML = '<p class="text-muted text-sm">加载失败: ' + err.message + '</p>';
        });
}

function getStatusLabel(status) {
    const map = {
        'DRAFT': '草稿',
        'IN_PROGRESS': '进行中',
        'COMPLETED': '已完成',
        'PAUSED': '已暂停',
        'ARCHIVED': '已归档'
    };
    return map[status] || status;
}

function deleteProject(projectId) {
    if (!confirm('确定删除该项目吗？此操作不可撤销。')) return;

    fetch('/api/projects/' + projectId, { method: 'DELETE' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.code === 0) {
                showToast('项目已删除', 'success');
                loadProjectList();
            } else {
                showToast(data.message || '删除失败', 'error');
            }
        })
        .catch(function(err) {
            showToast('删除失败: ' + err.message, 'error');
        });
}

// ============================================
//  Workflow
// ============================================
function startWorkflow(projectId) {
    var progressEl = document.getElementById('progress-fill');
    var logEl = document.getElementById('sse-log');

    if (progressEl) progressEl.style.width = '0%';
    if (logEl) {
        logEl.innerHTML = '';
        var initEntry = document.createElement('div');
        initEntry.className = 'log-entry log-info';
        initEntry.textContent = '正在启动工作流...';
        logEl.appendChild(initEntry);
    }

    var es = connectSSE(projectId, updateStepIndicator);

    fetch('/api/workflow/projects/' + projectId + '/start', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.code !== 0) {
                showToast(data.message || '启动失败', 'error');
                es.close();
            }
        })
        .catch(function(err) {
            showToast('启动失败: ' + err.message, 'error');
            es.close();
        });
}

// ============================================
//  Step Indicator (6 workflow steps)
// ============================================
var WORKFLOW_STEPS = [
    { key: 'REQUIREMENT', label: '需求调研' },
    { key: 'OUTLINE', label: '大纲设计' },
    { key: 'OUTLINE_SELECT', label: '大纲选择' },
    { key: 'SCRIPT', label: '剧本生成' },
    { key: 'REVIEW', label: '质量审核' },
    { key: 'COMPLETE', label: '完成' }
];

function renderStepIndicator(containerId, currentStep) {
    var container = document.getElementById(containerId);
    if (!container) return;

    var currentIdx = -1;
    for (var i = 0; i < WORKFLOW_STEPS.length; i++) {
        if (WORKFLOW_STEPS[i].key === currentStep) {
            currentIdx = i;
            break;
        }
    }
    // If step not found, try partial match
    if (currentIdx === -1 && currentStep) {
        for (var j = 0; j < WORKFLOW_STEPS.length; j++) {
            if (currentStep.indexOf(WORKFLOW_STEPS[j].key) >= 0) {
                currentIdx = j;
                break;
            }
        }
    }

    var html = '<div class="stepper">';
    for (var k = 0; k < WORKFLOW_STEPS.length; k++) {
        var cls = '';
        if (k < currentIdx) {
            cls = 'completed';
        } else if (k === currentIdx) {
            cls = 'active';
        }
        html += '<div class="stepper-step ' + cls + '">' +
                    '<div class="step-circle">' + (k < currentIdx ? '\u2713' : (k + 1)) + '</div>' +
                    '<span class="step-label">' + WORKFLOW_STEPS[k].label + '</span>' +
                '</div>';
        if (k < WORKFLOW_STEPS.length - 1) {
            html += '<div class="stepper-connector ' + (k < currentIdx ? 'completed' : '') + '"></div>';
        }
    }
    html += '</div>';
    container.innerHTML = html;
}

function updateStepIndicator(step) {
    var container = document.getElementById('step-indicator');
    if (!container) return;
    renderStepIndicator('step-indicator', step);
}

// ============================================
//  Outline Selection
// ============================================
var selectedOutlineId = null;

function getOutlines(projectId) {
    var listEl = document.getElementById('outline-list');
    if (!listEl) return;

    listEl.innerHTML = '<div class="text-center mt-2"><div class="spinner spinner-lg"></div><p class="text-sm text-muted mt-1">正在加载大纲...</p></div>';

    fetch('/api/projects/' + projectId + '/outlines')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.code === 0 && data.data && data.data.length > 0) {
                renderOutlineCards(data.data);
            } else {
                listEl.innerHTML = '<div class="empty-state">' +
                    '<div class="empty-icon">&#128196;</div>' +
                    '<p>暂无大纲数据</p>' +
                    '<p class="text-sm text-muted">请先启动工作流完成大纲设计步骤</p>' +
                '</div>';
            }
        })
        .catch(function(err) {
            listEl.innerHTML = '<p class="text-danger text-sm">加载失败: ' + err.message + '</p>';
        });
}

function renderOutlineCards(outlines) {
    var listEl = document.getElementById('outline-list');
    selectedOutlineId = null;

    var html = '<div class="outline-grid" id="outline-grid">';
    outlines.forEach(function(o, idx) {
        var versionLabel = ['A', 'B', 'C'][idx] || (idx + 1);
        html += '<div class="outline-card" data-id="' + o.id + '" data-version="' + versionLabel + '" onclick="selectOutline(' + o.id + ', this)">' +
                    '<span class="outline-version">大纲 ' + versionLabel + '</span>' +
                    '<h3>' + escapeHtml(o.title || '未命名大纲') + '</h3>' +
                    '<div class="outline-summary">' + escapeHtml(o.summary || '暂无摘要') + '</div>';
        if (o.coreConflict) {
            html += '<div class="detail-section"><div class="section-title">&#9881; 核心冲突</div>' +
                    '<div class="section-body">' + escapeHtml(o.coreConflict) + '</div></div>';
        }
        if (o.emotionalArc) {
            html += '<div class="detail-section"><div class="section-title">&#10084; 情感走向</div>' +
                    '<div class="section-body">' + escapeHtml(o.emotionalArc) + '</div></div>';
        }
        if (o.chapters && o.chapters.length > 0) {
            html += '<div class="detail-section"><div class="section-title">&#128196; 章节划分 (' + o.chapters.length + '章)</div>' +
                    '<div class="chapter-summary-grid">';
            o.chapters.forEach(function(ch, ci) {
                html += '<div class="chapter-summary-item">' +
                            '<div class="chapter-idx">第' + (ci + 1) + '章</div>' +
                            (ch.title ? '<div class="font-bold mb-1">' + escapeHtml(ch.title) + '</div>' : '') +
                            '<div class="text-xs">' + escapeHtml(ch.summary || '') + '</div>' +
                        '</div>';
            });
            html += '</div></div>';
        }
        html += '<div class="outline-meta">';
        if (o.wordCount) html += '<span class="outline-meta-item"><span class="meta-label">字数</span><span class="meta-value">' + o.wordCount + '</span></span>';
        if (o.sceneCount) html += '<span class="outline-meta-item"><span class="meta-label">场景</span><span class="meta-value">' + o.sceneCount + '</span></span>';
        html += '</div></div>';
    });
    html += '</div>';

    // Confirm bar
    html += '<div class="outline-confirm-bar" id="outline-confirm-bar" style="display:none;">' +
                '<button class="btn btn-primary btn-lg" onclick="confirmOutline()">&#10003; 确认选择此大纲</button>' +
                '<button class="btn btn-outline" onclick="cancelOutlineSelection()">取消选择</button>' +
            '</div>';

    // Tweak panel
    html += '<div class="tweak-panel mt-2" id="outline-tweak-panel" style="display:none;">' +
                '<div class="tweak-header">&#9998; 微调修改意见（可选）</div>' +
                '<div class="tweak-input-area">' +
                    '<textarea id="outline-tweak-input" class="tweak-textarea"' +
                        'placeholder="如果您希望对选择的大纲进行微调，请在此输入修改意见。例如：希望加强第一章的冲突感、调整第三章的情感线走向..."></textarea>' +
                    '<button class="btn btn-outline btn-sm" onclick="applyTweak()" style="flex-shrink: 0; align-self: flex-end;">应用微调</button>' +
                '</div>' +
            '</div>';

    listEl.innerHTML = html;
}

function selectOutline(outlineId, el) {
    // Remove old selection
    var allCards = document.querySelectorAll('.outline-card');
    allCards.forEach(function(c) { c.classList.remove('selected'); });

    // Select current
    el.classList.add('selected');
    selectedOutlineId = outlineId;

    // Show confirm bar and tweak panel
    var confirmBar = document.getElementById('outline-confirm-bar');
    var tweakPanel = document.getElementById('outline-tweak-panel');
    if (confirmBar) confirmBar.style.display = 'flex';
    if (tweakPanel) tweakPanel.style.display = 'block';
}

function cancelOutlineSelection() {
    var allCards = document.querySelectorAll('.outline-card');
    allCards.forEach(function(c) { c.classList.remove('selected'); });
    selectedOutlineId = null;

    var confirmBar = document.getElementById('outline-confirm-bar');
    var tweakPanel = document.getElementById('outline-tweak-panel');
    if (confirmBar) confirmBar.style.display = 'none';
    if (tweakPanel) tweakPanel.style.display = 'none';
}

function confirmOutline() {
    if (!selectedOutlineId) {
        showToast('请先选择一个大纲', 'warning');
        return;
    }

    var tweakInput = document.getElementById('outline-tweak-input');
    var tweakText = tweakInput ? tweakInput.value.trim() : '';

    var confirmMsg = '确认选择此大纲吗？';
    if (tweakText) {
        confirmMsg += '\n\n微调意见：' + tweakText;
    }

    if (!confirm(confirmMsg)) return;

    var body = { selectedOutlineId: selectedOutlineId };
    if (tweakText) body.tweakNotes = tweakText;

    // Get projectId from URL
    var pathParts = window.location.pathname.split('/');
    var projectId = pathParts[pathParts.indexOf('projects') + 1];

    fetch('/api/projects/' + projectId + '/outlines/select', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.code === 0) {
            showToast('大纲选择成功！', 'success');
            var confirmBar = document.getElementById('outline-confirm-bar');
            var tweakPanel = document.getElementById('outline-tweak-panel');
            if (confirmBar) confirmBar.innerHTML = '<span class="text-success font-bold">&#10003; 大纲已确认</span>';
            if (tweakPanel) tweakPanel.style.display = 'none';
        } else {
            showToast(data.message || '选择失败', 'error');
        }
    })
    .catch(function(err) {
        showToast('请求失败: ' + err.message, 'error');
    });
}

function applyTweak() {
    var tweakInput = document.getElementById('outline-tweak-input');
    if (!tweakInput || !tweakInput.value.trim()) {
        showToast('请输入微调意见', 'warning');
        return;
    }
    showToast('微调意见已记录，确认大纲时将一并提交', 'info');
}

// ============================================
//  Export Format Selector
// ============================================
function toggleExportSelector(triggerEl) {
    var selector = triggerEl.closest('.export-selector');
    if (!selector) return;

    var isOpen = selector.classList.contains('open');

    // Close all other open selectors
    document.querySelectorAll('.export-selector.open').forEach(function(s) {
        s.classList.remove('open');
    });

    if (!isOpen) {
        selector.classList.add('open');
    }
}

function doExport(format) {
    var selector = document.querySelector('.export-selector.open');
    if (selector) selector.classList.remove('open');

    var pathParts = window.location.pathname.split('/');
    var projectId = pathParts[pathParts.indexOf('projects') + 1];

    // Get script content from page
    var contentEl = document.getElementById('script-content');
    var content = contentEl ? (contentEl.dataset.rawContent || contentEl.innerText) : '';

    if (!content || content === '暂无剧本内容') {
        showToast('没有可导出的内容', 'warning');
        return;
    }

    var formatNames = {
        'MD': 'Markdown',
        'TXT': '纯文本',
        'SRT': '字幕文件'
    };

    exportDocument(content, format, 'script-' + projectId);
}

function exportDocument(content, format, fileName) {
    fetch('/api/export', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: content, format: format, fileName: fileName })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.code === 0) {
            var downloadUrl = data.data;
            window.open(downloadUrl, '_blank');
            showToast('导出成功', 'success');
        } else {
            showToast('导出失败: ' + (data.message || '未知错误'), 'error');
        }
    })
    .catch(function(err) {
        showToast('导出请求失败: ' + err.message, 'error');
    });
}

// Close export selector when clicking outside
document.addEventListener('click', function(e) {
    if (!e.target.closest('.export-selector')) {
        document.querySelectorAll('.export-selector.open').forEach(function(s) {
            s.classList.remove('open');
        });
    }
});

// ============================================
//  Incremental Edit
// ============================================
function showEditPanel(targetSelector) {
    var panel = document.getElementById('edit-panel');
    if (!panel) return;
    panel.style.display = 'block';
    panel.scrollIntoView({ behavior: 'smooth' });
}

function hideEditPanel() {
    var panel = document.getElementById('edit-panel');
    if (panel) panel.style.display = 'none';
}

function submitEdit(projectId) {
    var instructionEl = document.getElementById('edit-instruction');
    var instruction = instructionEl ? instructionEl.value.trim() : '';
    if (!instruction) {
        showToast('请输入修改指令', 'warning');
        return;
    }

    var btn = document.getElementById('btn-submit-edit');
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner spinner-sm"></span> 处理中...';
    }

    fetch('/api/projects/' + projectId + '/edit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ instruction: instruction })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.code === 0) {
            showToast('修改已应用', 'success');
            if (instructionEl) instructionEl.value = '';
            hideEditPanel();
            if (data.data && data.data.preview) {
                showDiffPreview(data.data.preview);
            }
        } else {
            showToast('修改失败: ' + (data.message || '未知错误'), 'error');
        }
    })
    .catch(function(err) {
        showToast('请求失败: ' + err.message, 'error');
    })
    .finally(function() {
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = '&#10003; 提交修改';
        }
    });
}

function showDiffPreview(previewText) {
    var diffEl = document.getElementById('edit-diff-preview');
    if (!diffEl) return;
    diffEl.style.display = 'block';
    diffEl.textContent = previewText;
}

// ============================================
//  Tab Switching
// ============================================
function switchTab(tabName) {
    // Update tab items
    document.querySelectorAll('.tab-item').forEach(function(t) {
        t.classList.remove('active');
        if (t.dataset.tab === tabName) {
            t.classList.add('active');
        }
    });

    // Update tab panels
    document.querySelectorAll('.tab-panel').forEach(function(p) {
        p.classList.remove('active');
    });
    var target = document.getElementById('tab-' + tabName);
    if (target) target.classList.add('active');
}

// ============================================
//  Review: Apply Fix
// ============================================
function applyFix(fixId, issueCard) {
    var statusEl = issueCard.querySelector('.fix-status');
    var btn = issueCard.querySelector('.btn-apply-fix');
    if (statusEl) {
        statusEl.textContent = '\u2713 已应用';
        statusEl.className = 'fix-status applied';
    }
    if (btn) {
        btn.textContent = '已应用';
        btn.disabled = true;
        btn.className = 'btn btn-success btn-sm';
    }
    showToast('修复已应用', 'success');
}

function applyAllFixes() {
    var cards = document.querySelectorAll('.review-issue-card');
    var count = 0;
    cards.forEach(function(card) {
        var statusEl = card.querySelector('.fix-status');
        var btn = card.querySelector('.btn-apply-fix');
        if (statusEl && !statusEl.classList.contains('applied')) {
            statusEl.textContent = '\u2713 已应用';
            statusEl.className = 'fix-status applied';
            if (btn) {
                btn.textContent = '已应用';
                btn.disabled = true;
                btn.className = 'btn btn-success btn-sm';
            }
            count++;
        }
    });
    showToast('已应用 ' + count + ' 条修复', 'success');
}

// ============================================
//  Review: Render Score Ring
// ============================================
function renderScoreRing(containerId, score, maxScore) {
    var container = document.getElementById(containerId);
    if (!container) return;

    maxScore = maxScore || 100;
    var pct = Math.min(score / maxScore, 1);
    var circumference = 2 * Math.PI * 58; // r=58
    var offset = circumference * (1 - pct);

    var gradeClass = 'score-a';
    var gradeLabel = 'A';
    if (pct < 0.6) { gradeClass = 'score-d'; gradeLabel = 'D'; }
    else if (pct < 0.75) { gradeClass = 'score-c'; gradeLabel = 'C'; }
    else if (pct < 0.85) { gradeClass = 'score-b'; gradeLabel = 'B'; }

    container.innerHTML =
        '<div class="progress-ring-lg">' +
            '<svg viewBox="0 0 140 140">' +
                '<circle class="bg-ring" cx="70" cy="70" r="58"/>' +
                '<circle class="fg-ring ' + gradeClass + '" cx="70" cy="70" r="58"' +
                    ' stroke-dasharray="' + circumference + '"' +
                    ' stroke-dashoffset="' + offset + '"/>' +
            '</svg>' +
            '<div class="ring-center">' +
                '<span class="ring-score-number">' + score + '</span>' +
                '<span class="ring-score-total">/ ' + maxScore + '</span>' +
                '<span class="ring-grade-tag ' + gradeClass + '">' + gradeLabel + '</span>' +
            '</div>' +
        '</div>';
}

// ============================================
//  Knowledge Base
// ============================================
function searchKnowledge() {
    var searchTerm = document.getElementById('kb-search') ? document.getElementById('kb-search').value.trim().toLowerCase() : '';
    var gameFilter = document.getElementById('kb-game-filter') ? document.getElementById('kb-game-filter').value : '';
    var items = document.querySelectorAll('.kb-entry-item');

    items.forEach(function(item) {
        var title = (item.dataset.title || '').toLowerCase();
        var game = item.dataset.game || '';
        var type = item.dataset.type || '';

        var matchesSearch = !searchTerm || title.indexOf(searchTerm) >= 0 || type.indexOf(searchTerm) >= 0;
        var matchesGame = !gameFilter || game === gameFilter;

        item.style.display = (matchesSearch && matchesGame) ? '' : 'none';
    });

    // Update entry count
    updateKBCount();
}

function filterByGame(gameName) {
    // Update chip active state
    document.querySelectorAll('.kb-game-chip').forEach(function(c) {
        c.classList.remove('active');
        if (c.dataset.game === gameName) c.classList.add('active');
    });
    // Update select if exists
    var select = document.getElementById('kb-game-filter');
    if (select) select.value = gameName;
    searchKnowledge();
}

function updateKBCount() {
    var visible = document.querySelectorAll('.kb-entry-item[style*="display:"]').length;
    var total = document.querySelectorAll('.kb-entry-item').length;
    // If no filter is hiding items, count all visible ones
    if (visible === 0) {
        visible = document.querySelectorAll('.kb-entry-item').length;
        document.querySelectorAll('.kb-entry-item').forEach(function(item) {
            if (item.style.display === 'none') visible--;
        });
    }
    var countEl = document.getElementById('kb-entry-count');
    if (countEl) countEl.textContent = visible + ' / ' + total + ' 条';
}

function loadKnowledgeEntries() {
    var listEl = document.getElementById('kb-entry-list');
    if (!listEl) return;

    listEl.innerHTML = '<div class="text-center mt-2"><div class="spinner"></div><p class="text-sm text-muted">正在加载...</p></div>';

    fetch('/api/knowledge/entries')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.code === 0 && data.data && data.data.length > 0) {
                renderKnowledgeEntries(data.data);
            } else {
                listEl.innerHTML = '<div class="empty-state">' +
                    '<div class="empty-icon">&#128230;</div>' +
                    '<p>暂无知识库条目</p>' +
                    '<p class="text-sm text-muted">请添加游戏知识库条目</p>' +
                '</div>';
            }
        })
        .catch(function(err) {
            listEl.innerHTML = '<p class="text-danger text-sm">加载失败: ' + err.message + '</p>';
        });
}

function renderKnowledgeEntries(entries) {
    var listEl = document.getElementById('kb-entry-list');
    if (!listEl) return;

    // Group by game
    var groups = {};
    entries.forEach(function(e) {
        var game = e.gameName || '未分类';
        if (!groups[game]) groups[game] = [];
        groups[game].push(e);
    });

    var gameNames = Object.keys(groups).sort();
    var html = '';

    // Update game filter chips
    var chipContainer = document.getElementById('kb-game-chips');
    if (chipContainer) {
        chipContainer.innerHTML = '<span class="kb-game-chip active" data-game="" onclick="filterByGame(\'\')">全部</span>' +
            gameNames.map(function(g) {
                return '<span class="kb-game-chip" data-game="' + escapeHtml(g) + '" onclick="filterByGame(\'' + escapeHtml(g) + '\')">' + escapeHtml(g) + '</span>';
            }).join('');
    }

    // Update game filter select
    var selectEl = document.getElementById('kb-game-filter');
    if (selectEl) {
        var currentVal = selectEl.value;
        selectEl.innerHTML = '<option value="">全部游戏</option>' +
            gameNames.map(function(g) {
                return '<option value="' + escapeHtml(g) + '">' + escapeHtml(g) + '</option>';
            }).join('');
        selectEl.value = currentVal;
    }

    gameNames.forEach(function(game) {
        html += '<div class="kb-entry-group">' +
                    '<div class="group-title">' +
                        '<span>&#127918;</span> ' + escapeHtml(game) +
                        '<span class="badge badge-neutral ml-auto">' + groups[game].length + '</span>' +
                    '</div>' +
                    '<ul class="kb-entry-list">';
        groups[game].forEach(function(entry) {
            var typeBadgeClass = 'badge-type-' + (entry.entryType || 'LORE');
            var typeLabel = getEntryTypeLabel(entry.entryType);
            var timeStr = entry.updatedAt ? new Date(entry.updatedAt).toLocaleDateString('zh-CN') : '';
            var sourceUrl = entry.sourceUrl || '';
            html += '<li class="kb-entry-item" data-title="' + escapeHtml(entry.title || '') + '" data-game="' + escapeHtml(game) + '" data-type="' + (entry.entryType || '') + '">' +
                        '<div class="entry-info">' +
                            '<span class="badge badge-sm ' + typeBadgeClass + '">' + typeLabel + '</span>' +
                            '<span class="entry-title">' + escapeHtml(entry.title || '无标题') + '</span>' +
                        '</div>' +
                        '<div class="kb-entry-meta">' +
                            (timeStr ? '<span class="kb-entry-time">' + timeStr + '</span>' : '') +
                            (sourceUrl ? '<a href="' + escapeHtml(sourceUrl) + '" target="_blank" class="text-xs text-muted" title="来源链接">&#128279;</a>' : '') +
                            '<div class="entry-actions">' +
                                '<button class="btn btn-ghost btn-xs" onclick="toggleEntryDetail(this, \'' + escapeHtml(entry.content || '') + '\')">查看</button>' +
                                '<button class="btn btn-ghost btn-xs text-danger" onclick="deleteKnowledgeEntry(' + entry.id + ')">删除</button>' +
                            '</div>' +
                        '</div>' +
                    '</li>';
            // Detail panel
            if (entry.content) {
                html += '<div class="kb-detail-content hidden" style="margin-bottom:0.4rem;"></div>';
            }
        });
        html += '</ul></div>';
    });

    listEl.innerHTML = html;
    updateKBCount();
}

function toggleEntryDetail(btn, content) {
    var detailEl = btn.closest('.kb-entry-item').nextElementSibling;
    if (detailEl && detailEl.classList.contains('kb-detail-content')) {
        if (detailEl.classList.contains('hidden')) {
            detailEl.textContent = content;
            detailEl.classList.remove('hidden');
            btn.textContent = '收起';
        } else {
            detailEl.classList.add('hidden');
            btn.textContent = '查看';
        }
    }
}

function getEntryTypeLabel(type) {
    var map = {
        'CHARACTER': '角色',
        'LORE': '世界观',
        'EVENT': '事件',
        'MECHANICS': '机制'
    };
    return map[type] || type || '其他';
}

function saveKnowledgeEntry() {
    var game = document.getElementById('kg-game');
    var type = document.getElementById('kg-type');
    var title = document.getElementById('kg-title');
    var content = document.getElementById('kg-content');
    var url = document.getElementById('kg-url');

    if (!game || !title || !content) {
        showToast('请填写所有必填字段', 'warning');
        return;
    }

    var gameVal = game.value.trim();
    var typeVal = type.value;
    var titleVal = title.value.trim();
    var contentVal = content.value.trim();
    var urlVal = url ? url.value.trim() : '';

    if (!gameVal || !titleVal || !contentVal) {
        showToast('请填写必填字段', 'warning');
        return;
    }

    var btn = document.querySelector('#kb-form-card .btn-primary');
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner spinner-sm"></span> 保存中...';
    }

    fetch('/api/knowledge/entries', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            gameName: gameVal,
            entryType: typeVal,
            title: titleVal,
            content: contentVal,
            sourceUrl: urlVal,
            tags: null
        })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.code === 0) {
            showToast('条目保存成功！', 'success');
            // Clear form
            if (title) title.value = '';
            if (content) content.value = '';
            if (url) url.value = '';
            // Reload list
            loadKnowledgeEntries();
        } else {
            showToast('保存失败: ' + (data.message || '未知错误'), 'error');
        }
    })
    .catch(function(err) {
        showToast('请求失败: ' + err.message, 'error');
    })
    .finally(function() {
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = '保存条目';
        }
    });
}

function deleteKnowledgeEntry(entryId) {
    if (!confirm('确定删除此知识库条目吗？此操作不可恢复。')) return;

    fetch('/api/knowledge/entries/' + entryId, { method: 'DELETE' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.code === 0) {
                showToast('条目已删除', 'success');
                loadKnowledgeEntries();
            } else {
                showToast('删除失败: ' + (data.message || '未知错误'), 'error');
            }
        })
        .catch(function(err) {
            showToast('请求失败: ' + err.message, 'error');
        });
}

// ============================================
//  Toast Notifications
// ============================================
function showToast(message, type) {
    type = type || 'info';
    var container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        container.className = 'toast-container';
        document.body.appendChild(container);
    }

    var icons = {
        success: '\u2713',
        error: '\u2717',
        warning: '\u26A0',
        info: '\u2139'
    };

    var toast = document.createElement('div');
    toast.className = 'toast toast-' + type;
    toast.innerHTML = '<span class="toast-icon">' + (icons[type] || '') + '</span>' +
                      '<span class="toast-msg">' + escapeHtml(message) + '</span>' +
                      '<button class="toast-close" onclick="this.parentElement.remove()">&times;</button>';

    container.appendChild(toast);

    // Auto remove after 3.5s
    setTimeout(function() {
        if (toast.parentElement) {
            toast.classList.add('toast-exit');
            setTimeout(function() {
                if (toast.parentElement) toast.remove();
            }, 250);
        }
    }, 3500);
}

// ============================================
//  Modal Helpers
// ============================================
function openModal(modalId) {
    var modal = document.getElementById(modalId);
    if (!modal) return;
    modal.style.display = 'flex';
    document.body.style.overflow = 'hidden';
}

function closeModal(modalId) {
    var modal = document.getElementById(modalId);
    if (!modal) return;
    modal.style.display = 'none';
    document.body.style.overflow = '';
}

// Close modal on overlay click
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('modal-overlay')) {
        e.target.style.display = 'none';
        document.body.style.overflow = '';
    }
});

// ============================================
//  Collapsible Sections
// ============================================
function toggleCollapsible(toggleEl) {
    var body = toggleEl.nextElementSibling;
    if (!body || !body.classList.contains('collapsible-body')) return;

    toggleEl.classList.toggle('expanded');
    body.classList.toggle('open');
}

// ============================================
//  Utility: HTML Escape
// ============================================
function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// ============================================
//  Utility: Debounce
// ============================================
function debounce(fn, delay) {
    var timer = null;
    return function() {
        var ctx = this;
        var args = arguments;
        clearTimeout(timer);
        timer = setTimeout(function() { fn.apply(ctx, args); }, delay);
    };
}

// ============================================
//  Markdown Renderer (used in project page)
// ============================================
function renderMarkdown(md) {
    if (!md) return '';
    var html = md.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

    // -- Code blocks (```...```) with optional language --
    var codeBlocks = [];
    html = html.replace(/```(\w*)\n([\s\S]*?)```/g, function(m, lang, code) {
        var idx = codeBlocks.length;
        codeBlocks.push({ lang: lang.toLowerCase(), code: code.trimEnd() });
        return '%%CODEBLOCK_' + idx + '%%';
    });

    // Tables: detect lines containing | and process them as a table block
    var lines = html.split('\n');
    var inTable = false, tableRows = [];
    var result = [];
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim();
        if (line.indexOf('|') >= 0) {
            var cells = line.split('|').map(function(c) { return c.trim(); });
            if (cells.length >= 3 && (line.startsWith('|') || line.indexOf('|') > 0)) {
                var isSep = cells.every(function(c) { return /^[-:]+$/.test(c); });
                if (!isSep) {
                    tableRows.push(cells.filter(function(c) { return c !== ''; }));
                }
                inTable = true;
                continue;
            }
        }
        if (inTable) {
            result.push(renderTable(tableRows));
            tableRows = [];
            inTable = false;
        }
        result.push(line);
    }
    if (inTable) {
        result.push(renderTable(tableRows));
    }
    html = result.join('\n');

    // headers
    html = html.replace(/^#### (.+)$/gm, '<h5>$1</h5>');
    html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>');
    html = html.replace(/^## (.+)$/gm, '<h3>$1</h3>');
    html = html.replace(/^# (.+)$/gm, '<h2>$1</h2>');
    // bold & italic
    html = html.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
    // inline code
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
    // links
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');
    // unordered list items
    html = html.replace(/^[\*\-] (.+)$/gm, '<li>$1</li>');
    // ordered list items
    html = html.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');
    // wrap consecutive <li> in <ul>
    html = html.replace(/(<li>.*?<\/li>(\n<li>.*?<\/li>)*)/g, '<ul>$1</ul>');
    // horizontal rules
    html = html.replace(/^---+$/gm, '<hr>');
    // blockquotes
    html = html.replace(/^&gt; (.+)$/gm, '<blockquote>$1</blockquote>');
    html = html.replace(/<\/blockquote>\n<blockquote>/g, '<br>');
    // paragraphs
    html = html.replace(/^(?!<[hulotc]|<\/?[hulotc]|<hr)(.+)$/gm, '<p>$1</p>');
    html = html.replace(/<p><\/p>/g, '');
    html = html.replace(/(<br\s*\/?>){3,}/g, '<br><br>');

    // Restore code blocks
    html = html.replace(/%%CODEBLOCK_(\d+)%%/g, function(m, idx) {
        var cb = codeBlocks[parseInt(idx)];
        if (!cb) return '';
        if (cb.lang === 'mermaid') {
            return '<div class="mermaid">' + cb.code + '</div>';
        }
        var escapedCode = cb.code.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        var langLabel = cb.lang ? '<span class="code-lang">' + cb.lang + '</span>' : '';
        return '<div class="code-block">' + langLabel
            + '<pre><code>' + escapedCode + '</code></pre></div>';
    });

    return html;
}

function renderTable(rows) {
    if (rows.length === 0) return '';
    var headerRow = rows[0];
    var dataRows = rows.slice(1);
    var h = '<table class="md-table"><thead><tr>';
    for (var hi = 0; hi < headerRow.length; hi++) {
        h += '<th>' + headerRow[hi] + '</th>';
    }
    h += '</tr></thead><tbody>';
    for (var ri = 0; ri < dataRows.length; ri++) {
        h += '<tr>';
        var maxCols = Math.max(headerRow.length, dataRows[ri].length);
        for (var ci = 0; ci < maxCols; ci++) {
            h += '<td>' + (dataRows[ri][ci] || '') + '</td>';
        }
        h += '</tr>';
    }
    h += '</tbody></table>';
    return h;
}
